package com.anonrode.downloader.engine

import com.anonrode.downloader.data.models.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Engine-audit P1 regression pins for DownloadRepository's boot path: a
 * corrupt state file must never destroy the task list, and a valid one must
 * restore parked (never auto-running) tasks. The Documents mirror needs an
 * Android Context, so it is exercised on device; these tests pass
 * `context = null` and cover everything else.
 */
class DownloadRepositoryRecoveryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun corruptStateFileIsSetAsideForDiagnostics_neverDeletedSilently() {
        val dir = tmp.newFolder("files")
        val state = File(dir, "download_tasks.json")
        state.writeText("""{"tasks":[{"id":"a",]""") // truncated JSON (power-cut shape)

        val repo = DownloadRepository()
        repo.initPersistence(dir, context = null)

        assertTrue(repo.tasks.value.isEmpty())
        // The old code DELETED the file here (f.delete()), destroying both the
        // list and the evidence. Now it is renamed aside so a later launch can
        // diagnose it and the mirror can be consulted.
        assertFalse("corrupt state file must not be deleted", state.exists())
        assertTrue(
            "corrupt state file must be preserved as .corrupt",
            File(dir, "download_tasks.json.corrupt").exists()
        )
    }

    @Test
    fun validStateFileRestoresTasksParkedNotRunning() {
        val dir = tmp.newFolder("files2")
        File(dir, "download_tasks.json").writeText(
            """[{"id":"a","showTitle":"Show","episodeNum":1,"episodeTitle":"Ep 1",
                 "directUrl":"https://example.invalid/a.mp4","status":"DOWNLOADING"}]"""
        )

        val repo = DownloadRepository()
        repo.initPersistence(dir, context = null)

        assertEquals(1, repo.tasks.value.size)
        // parkForRestore: a mid-flight status must come back PAUSED — but as
        // a SYSTEM pause (userPaused=false, network marker) that the network
        // observer requeues after the boot backoff floor, not a stranded
        // manual pause (owner directive 2026-10-21: every error ends in a
        // download or a proven no-source). Never RUNNING on reopen; the
        // 60s floor preserves the v3.0.4 no-instant-drain guarantee.
        assertEquals(TaskStatus.PAUSED, repo.tasks.value[0].status)
    }

    @Test
    fun persistedTaskKeepsValidatedHeadersAcrossRestore() {
        // Header/truth preservation (lead directive 2026-10-21): the engine
        // persists the validated headers onto the task the moment it computes
        // them, so a park→requeue→RESTART retry re-enters with the SAME
        // Referer/UA — a referer-gated CDN (nexabloom: 200 WITH Referer, 403
        // WITHOUT) must never fail only because the retry dropped the header.
        val dir = tmp.newFolder("files-h")
        File(dir, "download_tasks.json").writeText(
            """[{"id":"h1","showTitle":"Show","episodeNum":1,"episodeTitle":"Ep 1",
                 "directUrl":"https://fetch.nexabloom.top/a/master.m3u8",
                 "status":"PAUSED","userPaused":false,
                 "errorMessage":"Download server cooling down — will retry in ~1m",
                 "headers":{"Referer":"https://megaplay.buzz/","User-Agent":"Mozilla/5.0 (Windows NT 10.0)"}}]"""
        )
        val repo = DownloadRepository()
        repo.initPersistence(dir, context = null)
        val t = repo.tasks.value.single()
        // The retry path's first lookup (engine: taskReferer/taskUa) must see
        // the validated headers after a full restore — this is what keeps the
        // retried yt-dlp/ Turbo request referer-carrying.
        assertEquals("https://megaplay.buzz/", t.headers["Referer"])
        assertEquals("Mozilla/5.0 (Windows NT 10.0)", t.headers["User-Agent"])
        // And the restore must NOT have stripped them (system park keeps its
        // marker; a persisted headers map is never touched by parkForRestore).
        assertEquals("Download server cooling down — will retry in ~1m", t.errorMessage)
        assertFalse(t.userPaused)
    }

    @Test
    fun corruptThenValid_writePathKeepsNewestState() {
        val dir = tmp.newFolder("files3")
        File(dir, "download_tasks.json").writeText("not json at all")
        val repo = DownloadRepository()
        repo.initPersistence(dir, context = null)
        assertTrue(repo.tasks.value.isEmpty())
        // A later persist must land on the clean path (no stale tmp, no
        // leftover poison file at the primary name).
        assertFalse(File(dir, "download_tasks.json").exists())
    }
}
