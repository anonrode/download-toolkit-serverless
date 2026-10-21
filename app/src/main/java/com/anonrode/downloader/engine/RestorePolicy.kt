package com.anonrode.downloader.engine

import com.anonrode.downloader.data.models.DownloadTask
import com.anonrode.downloader.data.models.TaskStatus

/**
 * Restore policy under the owner's acceptance invariant (2026-10-21,
 * "every error ends in a download or a proven no-source"): an interrupted or
 * system-parked download must AUTO-RETRY with backoff after a restart — it
 * may never be stranded as a manual pause. Only a task the USER explicitly
 * paused stays paused.
 *
 * The pre-fix contract ("reopen never auto-resumes anything", v3.0.4) was a
 * fix for silent mobile-data drain on open: every restored task was crushed
 * to PAUSED + userPaused=true + no message, which also permanently stranded
 * every SYSTEM park (host cooldown, network flap, Wi-Fi gate, storage) —
 * parkForRestore only fires once per process, so those parks could never be
 * picked up by their own retry loops after any app restart or crash
 * (share-43: c6710cfb's 6-cycle park storm, 0c2068fc, e6c37af6). The two
 * concerns are now separated:
 *  - the v3.0.4 drain surprise is answered by a BACKOFF, not by stranding:
 *    restored auto-retry tasks land as PAUSED(!userPaused) with the network
 *    marker, and DownloadEngine's boot block stamps
 *    {RESTORED_REQUEUE_FLOOR_MS} into requeueNotBeforeMs, which the network
 *    observer's requeue sweep honors — resume happens ~1 minute into the
 *    session, once, on a real connection;
 *  - a user pause (userPaused=true) survives restarts exactly as before:
 *    marker cleared, auto-resume filters all skip it.
 */
internal fun parkForRestore(task: DownloadTask): DownloadTask = when (task.status) {
    TaskStatus.DOWNLOADING, TaskStatus.RESOLVING, TaskStatus.VALIDATING, TaskStatus.QUEUED ->
        // Interrupted by process death / crash: keep banked bytes, mark as a
        // system network pause so the connect sweep requeues it (with the
        // boot floor). userPaused stays false — no user asked for this pause.
        task.copy(
            status = TaskStatus.PAUSED,
            speedBytesPerSec = 0.0,
            errorMessage = DownloadEngine.NETWORK_PAUSE_MESSAGE,
            userPaused = false
        )
    TaskStatus.PAUSED ->
        if (task.userPaused || !isRequeueableParkMarker(task.errorMessage)) {
            // Manual pause (or a pause no family loop can retry): cleared
            // marker + userPaused=true, the historical safe mapping.
            task.copy(errorMessage = null, userPaused = true)
        } else {
            // System park whose family loop owns the retry: keep the marker
            // and the not-user-paused flag so that loop requeues it.
            task.copy(speedBytesPerSec = 0.0, userPaused = false)
        }
    else -> task
}

/** True for park markers whose family retry loop owns the task: host
 *  cooldown (DownloadEngine.PARKED_HOST_MESSAGE prefix), network pause,
 *  Wi-Fi gate, storage guard. Any other message has no retry loop — restore
 *  must not leave it !userPaused with a live marker nobody will act on. */
internal fun isRequeueableParkMarker(errorMessage: String?): Boolean {
    if (errorMessage == null) return false
    return errorMessage.startsWith(DownloadEngine.PARKED_HOST_MESSAGE) ||
        errorMessage == DownloadEngine.NETWORK_PAUSE_MESSAGE ||
        errorMessage.startsWith("Waiting for Wi-Fi") ||
        errorMessage.startsWith("Storage limit reached")
}
