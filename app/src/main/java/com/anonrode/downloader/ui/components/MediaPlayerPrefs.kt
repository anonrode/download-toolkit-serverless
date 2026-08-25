package com.anonrode.downloader.ui.components

import android.content.Context

/**
 * SharedPreferences getters/setters for the in-app media player. Stored in the
 * app's canonical "downloader_settings" file so user playback preferences
 * survive alongside every other setting; deliberately not a second prefs file
 * to keep the surface small and the user's mental model simple.
 */
object MediaPlayerPrefs {
    private const val PREFS = "downloader_settings"

    private const val KEY_PLAYBACK_SPEED = "pref_playback_speed"
    private const val KEY_MEDIA_VOLUME = "pref_media_volume"
    private const val KEY_WINDOW_BRIGHTNESS = "pref_window_brightness"
    private const val KEY_SUBTITLE_TRACK = "pref_subtitle_track"

    fun getPlaybackSpeed(context: Context): Float =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_PLAYBACK_SPEED, 1.0f)

    fun setPlaybackSpeed(context: Context, speed: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_PLAYBACK_SPEED, speed)
            .apply()
    }

    fun getMediaVolume(context: Context): Float =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_MEDIA_VOLUME, 1.0f)

    fun setMediaVolume(context: Context, volume: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_MEDIA_VOLUME, volume.coerceIn(0f, 1f))
            .apply()
    }

    // -1f = follow system brightness (the default). 0f..1f = explicit override.
    fun getWindowBrightness(context: Context): Float =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_WINDOW_BRIGHTNESS, -1f)

    fun setWindowBrightness(context: Context, brightness: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            // Persist exactly what the slider is set to; -1f is meaningful
            // (means "follow system") and must be saved, not clamped.
            .putFloat(KEY_WINDOW_BRIGHTNESS, brightness)
            .apply()
    }

    fun getSubtitleTrack(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SUBTITLE_TRACK, null)

    fun setSubtitleTrack(context: Context, label: String?) {
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (label == null) edit.remove(KEY_SUBTITLE_TRACK) else edit.putString(KEY_SUBTITLE_TRACK, label)
        edit.apply()
    }
}
