package com.anonrode.downloader.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.anonrode.downloader.AnonApp
import com.anonrode.downloader.util.DebugLog
import java.io.File

/**
 * DEBUG-ONLY HLS test driver. Release builds never include this class.
 *
 * adb usage (package com.anonrode.downloader.debug):
 *   am broadcast -a com.anonrode.downloader.debug.HLS_TEST \
 *     -n com.anonrode.downloader.debug/com.anonrode.downloader.debug.HlsTestDebugReceiver \
 *     --es op settings --ei max_tasks 3 --ei lanes 8
 *   am broadcast ... --es op enqueue --es url "http://.../master.m3u8?t=..." \
 *     --es title "A1-task1" --ei task 1 --ei lanes 8
 *   am broadcast ... --es op state
 *
 * The state dump is written to files/hls-test-state.txt and mirrored to the
 * activity journal; `adb exec-out run-as ... cat files/hls-test-state.txt`
 * pulls it without UI automation.
 */
class HlsTestDebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = try {
            AnonApp.instance
        } catch (_: UninitializedPropertyAccessException) {
            Log.w(TAG, "AnonApp not initialized")
            return
        }
        val engine = app.engine
        when (intent.getStringExtra(EXTRA_OP)) {
            OP_SETTINGS -> {
                val maxTasks = intent.getIntExtra(EXTRA_MAX_TASKS, 1).coerceIn(1, 6)
                val lanes = intent.getIntExtra(EXTRA_LANES, 8).coerceIn(1, 16)
                engine.maxConcurrentDownloads = maxTasks
                engine.parallelSocketsPerFile = lanes
                engine.hlsFragmentConcurrency = lanes
                DebugLog.engine("DEBUG hls-test settings: maxTasks=$maxTasks lanes=$lanes")
                Log.i(TAG, "settings maxTasks=$maxTasks lanes=$lanes")
            }
            OP_ENQUEUE -> {
                val url = intent.getStringExtra(EXTRA_URL).orEmpty()
                if (url.isEmpty()) {
                    Log.w(TAG, "enqueue without url")
                    return
                }
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "hls-test"
                val task = intent.getIntExtra(EXTRA_TASK, 1)
                val lanes = intent.getIntExtra(EXTRA_LANES, 8).coerceIn(1, 16)
                val id = engine.enqueue(
                    showTitle = title,
                    episodeNum = task,
                    episodeTitle = title,
                    sourceUrl = url,
                    isDirect = true,
                    backend = "ytdlp",
                    parallelSockets = lanes,
                    site = "hls-test"
                )
                DebugLog.engine("DEBUG hls-test enqueue id=$id task=$task lanes=$lanes title=$title")
                Log.i(TAG, "enqueued id=$id task=$task lanes=$lanes")
            }
            OP_STATE -> writeState(context)
        }
    }

    private fun writeState(context: Context) {
        val rows = AnonApp.repository.snapshot()
        val text = buildString {
            append("tasks=").append(rows.size).append('\n')
            for (t in rows) {
                append(t.id).append('|')
                    .append(t.status).append('|')
                    .append(t.backend).append('|')
                    .append(t.downloadedBytes).append('|')
                    .append(t.totalBytes).append('|')
                    .append(t.parallelSockets).append('|')
                    .append(t.errorMessage?.replace('\n', ' ') ?: "").append('|')
                    .append(t.episodeTitle.replace('\n', ' ')).append('\n')
            }
        }
        try {
            File(context.filesDir, "hls-test-state.txt").writeText(text)
        } catch (t: Throwable) {
            Log.w(TAG, "state write failed: ${t.javaClass.simpleName}")
        }
        DebugLog.engine("DEBUG hls-test state: ${text.lineSequence().take(8).joinToString(" ; ")}")
        Log.i(TAG, "state rows=${rows.size}")
    }

    companion object {
        private const val TAG = "AnonHlsTestDebug"
        const val EXTRA_OP = "op"
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TASK = "task"
        const val EXTRA_LANES = "lanes"
        const val EXTRA_MAX_TASKS = "max_tasks"
        const val OP_SETTINGS = "settings"
        const val OP_ENQUEUE = "enqueue"
        const val OP_STATE = "state"
    }
}
