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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.anonrode.downloader.security.TorrentSecurityShield
import com.anonrode.downloader.ui.theme.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bridge between the engine's suspend picker callback (running on IO) and the
 * Compose UI. The engine calls [pick] on the IO thread; the main-thread dialog
 * observes [requestState] and completes the deferred with the user's choice.
 * Tapping outside / dismiss cancels the request so unshielded files are never downloaded.
 * The active request is held in a StateFlow so configuration changes (e.g. screen
 * rotation) do not drop the dialog.
 */
object TorrentFilePicker {
    sealed interface TorrentPickResult {
        data class Selected(val indices: List<Int>) : TorrentPickResult
        data object Cancelled : TorrentPickResult
    }

    data class Request(
        val files: List<TorrentSecurityShield.TorrentFileEntry>,
        val deferred: CompletableDeferred<TorrentPickResult>
    )

    private val _requestState = MutableStateFlow<Request?>(null)
    val requestState: StateFlow<Request?> = _requestState.asStateFlow()

    val requests: Request?
        get() = _requestState.value

    /** How long the engine waits for the UI to show the picker before falling
     *  back to cancelling. Guards against the dialog never
     *  being composed. */
    private const val PICK_TIMEOUT_MS = 60_000L

    suspend fun pick(files: List<TorrentSecurityShield.TorrentFileEntry>): TorrentPickResult {
        val deferred = CompletableDeferred<TorrentPickResult>()

        // The bridge has a single slot: a second request arriving before the
        // host resolves the first would orphan the first deferred forever.
        val previous = _requestState.value
        if (previous != null && !previous.deferred.isCompleted) {
            previous.deferred.complete(TorrentPickResult.Cancelled)
        }

        val req = Request(files, deferred)
        _requestState.value = req

        val result = try {
            kotlinx.coroutines.withTimeoutOrNull(PICK_TIMEOUT_MS) { deferred.await() }
                ?: TorrentPickResult.Cancelled
        } catch (e: kotlinx.coroutines.CancellationException) {
            deferred.complete(TorrentPickResult.Cancelled)
            throw e
        } finally {
            if (!deferred.isCompleted) {
                deferred.complete(TorrentPickResult.Cancelled)
            }
            if (_requestState.value === req) {
                _requestState.value = null
            }
        }
        return result
    }

    /** Completes the active request and clears the request state so the dialog dismisses. */
    fun resolve(request: Request, selection: TorrentPickResult) {
        request.deferred.complete(selection)
        if (_requestState.value === request) {
            _requestState.value = null
        }
    }

    /** Deprecated non-blocking poll preserved for backward compatibility. */
    @Deprecated("Observe requestState instead of polling consume()", ReplaceWith("requestState.value"))
    fun consume(): Request? {
        val r = _requestState.value ?: return null
        _requestState.value = null
        return r
    }
}

/** Compose dialog: checkbox list of safe torrent files. Unsafe (blocked by the
 *  shield) entries are shown disabled with a warning and are never selectable. */
@Composable
fun TorrentFilePickerDialog(
    request: TorrentFilePicker.Request,
    onDismiss: (TorrentFilePicker.TorrentPickResult) -> Unit
) {
    val safeFiles = request.files.filter { it.isSafe }
    val blockedFiles = request.files.filter { !it.isSafe }
    var selectedList by rememberSaveable { mutableStateOf(safeFiles.map { it.index }) }
    val selected = selectedList.toSet()

    AlertDialog(
        onDismissRequest = { onDismiss(TorrentFilePicker.TorrentPickResult.Cancelled) },
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
                    fontSize = Type.body.fontSize
                )
                LazyColumn(modifier = Modifier.padding(top = Spacing.sm)) {
                    items(safeFiles, key = { it.index }) { file ->
                        val checked = file.index in selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedList = if (checked) selectedList - file.index
                                    else selectedList + file.index
                                }
                                .padding(vertical = Spacing.xs),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = checked, onCheckedChange = {
                                selectedList = if (it) selectedList + file.index else selectedList - file.index
                            })
                            Column(modifier = Modifier.weight(1f)) {
                                Text(file.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, color = TextPrimary, fontSize = Type.body.fontSize)
                                Text(
                                    "%.1f MB".format(file.length / 1048576.0),
                                    color = TextMuted,
                                    fontSize = Type.label.fontSize
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
                                Checkbox(
                                    checked = false,
                                    onCheckedChange = null,
                                    modifier = Modifier.alpha(0.38f)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        file.displayName,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = StatusError,
                                        fontSize = Type.body.fontSize
                                    )
                                    Text(
                                        "Blocked by security shield (${file.length / 1048576} MB)",
                                        color = StatusError.copy(alpha = 0.7f),
                                        fontSize = Type.label.fontSize
                                    )
                                }
                            }
                        }
                    }
                }
                if (safeFiles.isEmpty()) {
                    Text(
                        "No safe files found — the whole torrent is blocked.",
                        color = StatusError,
                        fontSize = Type.body.fontSize
                    )
                }
            }
        },
        confirmButton = {
            if (safeFiles.isEmpty()) {
                TextButton(onClick = { onDismiss(TorrentFilePicker.TorrentPickResult.Cancelled) }) {
                    Text("Close", color = AccentPrimary)
                }
            } else {
                TextButton(
                    enabled = selected.isNotEmpty(),
                    onClick = {
                        onDismiss(TorrentFilePicker.TorrentPickResult.Selected(selected.toList()))
                    }
                ) {
                    Text("Download (${selected.size})", color = AccentPrimary)
                }
            }
        },
        dismissButton = if (safeFiles.isNotEmpty()) {
            {
                TextButton(onClick = {
                    onDismiss(TorrentFilePicker.TorrentPickResult.Selected(safeFiles.map { it.index }))
                }) {
                    Text("All Safe Files", color = AccentPrimary)
                }
            }
        } else null
    )
}
