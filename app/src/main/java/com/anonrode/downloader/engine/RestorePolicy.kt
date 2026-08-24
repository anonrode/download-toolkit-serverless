package com.anonrode.downloader.engine

import com.anonrode.downloader.data.models.DownloadTask
import com.anonrode.downloader.data.models.TaskStatus

/**
 * Reopen never auto-resumes, period. Mid-flight statuses
 * (DOWNLOADING/RESOLVING/VALIDATING) park as PAUSED: silently re-consuming
 * mobile data on reopen (user-reported) is the wrong surprise. VALIDATING is
 * included deliberately — a pause landing during the integrity check (which
 * now does real work: atom scans, decoder probes) must not resurrect as
 * QUEUED, and the file on disk means a manual resume re-validates in seconds.
 * errorMessage is cleared for every parked task: the network observer
 * auto-resumes tasks carrying NETWORK_PAUSE_MESSAGE / "Waiting for Wi-Fi"
 * markers, and those markers survived a restart — so a download parked right
 * before the app died silently resumed on reopen (user-reported). Reopen
 * never auto-resumes; in-session network recovery still works.
 *
 * QUEUED joins the parked set: an enqueue that never got to start must not
 * survive a restart as QUEUED — the network observer's first emission
 * (processQueue within ~32ms of app open) would otherwise auto-start it and
 * drain data while the user is in another app (user-reported v3.0.4).
 */
internal fun parkForRestore(task: DownloadTask): DownloadTask = when (task.status) {
    TaskStatus.DOWNLOADING, TaskStatus.RESOLVING, TaskStatus.VALIDATING, TaskStatus.QUEUED ->
        task.copy(status = TaskStatus.PAUSED, speedBytesPerSec = 0.0, errorMessage = null)
    TaskStatus.PAUSED ->
        task.copy(errorMessage = null)
    else -> task
}
