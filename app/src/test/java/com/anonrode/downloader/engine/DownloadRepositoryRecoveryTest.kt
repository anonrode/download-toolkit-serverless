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
        // parkForRestore: a mid-flight status must come back PAUSED — reopen
        // never auto-resumes (a hard product guarantee).
        assertEquals(TaskStatus.PAUSED, repo.tasks.value[0].status)
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
