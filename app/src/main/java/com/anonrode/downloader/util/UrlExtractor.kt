package com.anonrode.downloader.util

/** Single source of truth for URL/magnet detection across the app.
 *  Inline regexes in `MainActivity` and `HomeScreen` used to drift, so the
 *  share path would accept a payload the clipboard banner would not. */
object UrlExtractor {

    private val URL_PATTERN = Regex("""https?://[^\s"'<>]+""")
    private val MAGNET_PATTERN = Regex("""magnet:\?[^\s"'<>]+""")

    /** True when the whole trimmed value begins with a scheme the router
     *  understands. Used by the paste-into-search interceptor and the
     *  clipboard-change listener: both care about a full-clipboard value,
     *  not extraction from a sentence. */
    fun isLikelyUrl(text: String): Boolean {
        val t = text.trim()
        return t.startsWith("http://") ||
            t.startsWith("https://") ||
            t.startsWith("magnet:?")
    }

    /** Pulls the first http(s):// or magnet:? run out of arbitrary text and
     *  returns it verbatim, or null if neither appears. The share path uses
     *  this so "Check this out! https://example.com/x" routes the same as a
     *  bare link. */
    fun firstUrl(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return null
        URL_PATTERN.find(t)?.let { return it.value }
        MAGNET_PATTERN.find(t)?.let { return it.value }
        return null
    }
}
