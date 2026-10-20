package com.anonrode.downloader.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.anonrode.downloader.util.DebugLog
import com.anonrode.downloader.util.LogUploader

/**
 * DEBUG-ONLY proof path for the pending-upload retry fix (2026-10-19
 * backlog). Release builds never include this class.
 *
 * adb usage (package com.anonrode.downloader.debug):
 *   am broadcast -a com.anonrode.downloader.debug.LOG_UPLOAD \
 *     -n com.anonrode.downloader.debug/com.anonrode.downloader.debug.LogUploadDebugReceiver \
 *     --ez force_fail true --ez trigger true
 *   am broadcast ... --ez status true
 */
class LogUploadDebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_FORCE_FAIL, false)) {
            LogUploader.debugForceFailOnce = true
            Log.i(TAG, "forced one upload transport failure")
            DebugLog.engine("DEBUG log upload: forced one transport failure")
        }
        if (intent.getBooleanExtra(EXTRA_TRIGGER, false)) {
            Log.i(TAG, "triggered upload cycle")
            DebugLog.engine("DEBUG log upload: manual trigger")
            LogUploader.debugUploadNow(context.applicationContext)
        }
        if (intent.getBooleanExtra(EXTRA_STATUS, false)) {
            val status = LogUploader.debugStatus(context.applicationContext)
            Log.i(TAG, "status $status")
            DebugLog.engine("DEBUG log upload status: $status")
        }
    }

    companion object {
        private const val TAG = "AnonLogUploadDebug"
        const val EXTRA_FORCE_FAIL = "force_fail"
        const val EXTRA_TRIGGER = "trigger"
        const val EXTRA_STATUS = "status"
    }
}
