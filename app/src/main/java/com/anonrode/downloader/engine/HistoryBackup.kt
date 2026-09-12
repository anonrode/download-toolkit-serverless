package com.anonrode.downloader.engine

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore

/**
 * Uninstall-proof history mirror (feature: "history should survive an
 * uninstall"). download_tasks.json lives in filesDir, which Android deletes
 * on uninstall — the whole queue/history vanished with the app (feature
 * #28). This mirrors every persist to Documents/AnonDownloader/ via
 * MediaStore (API 29+: no permission, no SAF dialog; the user can even open
 * the file in a file manager) and imports it back on first launch after a
 * fresh install.
 *
 * API 26-28 keep app-private persistence only: writing public storage there
 * needs runtime WRITE_EXTERNAL_STORAGE, which the app does not request —
 * adding a permission prompt for a 3-year-old code path is the worse trade.
 */
object HistoryBackup {

    private const val DISPLAY_NAME = "download_tasks.json"
    // MediaStore stores RELATIVE_PATH with both trailing and leading slashes.
    private const val RELATIVE_DIR = "Documents/AnonDownloader/"

    /** Upsert the JSON into public Documents. Best-effort: any failure
     *  (provider hiccup, user revoked storage) only means this mirror is
     *  skipped — the private file remains the source of truth. */
    fun save(ctx: Context, encoded: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val resolver = ctx.contentResolver
            val collection = MediaStore.Files.getContentUri("external")
            val existing = resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf(RELATIVE_DIR, DISPLAY_NAME),
                null
            )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, DISPLAY_NAME)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR)
            }
            val uri = if (existing != null) {
                android.content.ContentUris.withAppendedId(collection, existing)
            } else {
                resolver.insert(collection, values)
            } ?: return
            resolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(encoded.toByteArray(Charsets.UTF_8))
            }
        } catch (_: Throwable) {}
    }

    /** Read the mirrored JSON back on a fresh install. */
    fun load(ctx: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val resolver = ctx.contentResolver
            val collection = MediaStore.Files.getContentUri("external")
            val uri = resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf(RELATIVE_DIR, DISPLAY_NAME),
                null
            )?.use { c -> if (c.moveToFirst()) MediaStore.Files.getContentUri("external", c.getLong(0)) else null }
                ?: return null
            resolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (_: Throwable) {
            null
        }
    }
}
