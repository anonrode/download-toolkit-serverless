package com.anonrode.downloader.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.anonrode.downloader.security.TorrentSecurityShield
import com.anonrode.downloader.ui.theme.*
import kotlinx.coroutines.CompletableDeferred

/**
 * Bridge between the engine's suspend picker callback (running on IO) and the
 * Compose UI. The engine calls [pick] on the IO thread; the main-thread dialog
 * observes [requests] and completes the deferred with the user's choice.
 * Null selection (dismiss) means "download the whole torrent".
 */
object TorrentFilePicker {
    data class Request(
        val files: List<TorrentSecurityShield.TorrentFileEntry>,
        val deferred: CompletableDeferred<List<Int>?>
    )

    @Volatile
    var requests: Request? = null
        private set

    /** How long the engine waits for the UI to show the picker before falling
     *  back to downloading the whole torrent. Guards against the dialog never
     *  being composed (e.g. a magnet enqueued from QuickShareActivity, or the
     *  user leaving HomeScreen while a queued magnet starts). */
    private const val PICK_TIMEOUT_MS = 60_000L

    suspend fun pick(files: List<TorrentSecurityShield.TorrentFileEntry>): List<Int>? {
        val deferred = CompletableDeferred<List<Int>?>()

        // The bridge has a single slot: a second request arriving before the
        // host consumes the first would orphan the first deferred forever.
        // Resolve it to "whole torrent" first so the engine never hangs.
        val previous = requests
        if (previous != null && !previous.deferred.isCompleted) {
            previous.deferred.complete(null)
        }

        val req = Request(files, deferred)
        requests = req

        // If no dialog appears within the timeout (host not composed, task
        // paused while the dialog is open), fall back to whole-torrent instead
        // of suspending the engine's IO thread forever.
        val result = try {
            kotlinx.coroutines.withTimeoutOrNull(PICK_TIMEOUT_MS) { deferred.await() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Caller (task job) cancelled: never leave the deferred pending —
            // a later host consume() would show a dialog that can never resolve.
            deferred.complete(null)
            throw e
        }
        if (result == null && !deferred.isCompleted) {
            deferred.complete(null)
        }
        // If the host never consumed the slot, clear it so a later host
        // instance does not surface a stale dialog for an already-timed-out
        // decision. (consume() already nulls it in the normal path.)
        if (requests === req) requests = null
        return result
    }

    /** Non-blocking poll used by the dialog host; returns and clears the request. */
    fun consume(): Request? {
        val r = requests ?: return null
        requests = null
        return r
    }
}

/** Compose dialog: checkbox list of safe torrent files. Unsafe (blocked by the
 *  shield) entries are shown disabled with a warning and are never selectable. */
@Composable
fun TorrentFilePickerDialog(
    request: TorrentFilePicker.Request,
    onDismiss: (List<Int>?) -> Unit
) {
    val safeFiles = request.files.filter { it.isSafe }
    val blockedFiles = request.files.filter { !it.isSafe }
    var selected by remember { mutableStateOf(safeFiles.map { it.index }.toSet()) }

    AlertDialog(
        onDismissRequest = { onDismiss(null) },
        shape = RoundedCornerShape(Radius.lg),
        containerColor = SurfaceCard,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = { Text("Select files to download") },
        text = {
            Column {
                Text(
                    "Season pack: choose what to grab. Downloading only the wanted " +
                        "episodes is much faster than the whole batch.",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
                LazyColumn(modifier = Modifier.padding(top = Spacing.sm)) {
                    items(safeFiles, key = { it.index }) { file ->
                        val checked = file.index in selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected = if (checked) selected - file.index
                                    else selected + file.index
                                }
                                .padding(vertical = Spacing.xs),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = checked, onCheckedChange = {
                                selected = if (it) selected + file.index else selected - file.index
                            })
                            Column(modifier = Modifier.weight(1f)) {
                                Text(file.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, color = TextPrimary, fontSize = 13.sp)
                                Text(
                                    "%.1f MB".format(file.length / 1048576.0),
                                    color = TextMuted,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                    if (blockedFiles.isNotEmpty()) {
                        items(blockedFiles, key = { "b${it.index}" }) { file ->
                            Row(
                                modifier = Modifier.padding(vertical = Spacing.xs),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(checked = false, onCheckedChange = null)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        file.displayName,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = StatusError,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        "Blocked by security shield (${file.length / 1048576} MB)",
                                        color = StatusError.copy(alpha = 0.7f),
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                    if (safeFiles.isEmpty()) {
                        Text(
                            "No safe files found — the whole torrent is blocked.",
                            color = StatusError,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss(if (selected.isEmpty()) null else selected.toList())
            }, enabled = safeFiles.isNotEmpty()) {
                Text("Download (${selected.size})", color = AccentPrimary)
            }
        },
        dismissButton = {
            TextButton(onClick = { onDismiss(null) }) { Text("Whole torrent", color = AccentPrimary) }
        }
    )
}
