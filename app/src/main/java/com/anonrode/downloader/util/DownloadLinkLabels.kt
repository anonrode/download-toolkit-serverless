package com.anonrode.downloader.util

/**
 * Drawer labels for the shapes that AREN'T episodes. Live-verified device
 * bug class (2026-09-13, 9jarocks "Safety" film): movie posts publish ONE
 * file as several locker links whose text says "DOWNLOAD VIDEO SERVER 1/2"
 * (mirrors) or "PART 1/2" (splits). Any drawer that numbers marker-free
 * links sequentially turns such a film into fake "Episode 1/2" rows; every
 * affected path asks this helper BEFORE falling back to numbering.
 * SERVER is tested first, and the word-boundary keeps "apartment" or
 * "party" from matching PART.
 */
object DownloadLinkLabels {
    private val SERVER = Regex("""\b(?:video\s+)?server\s*(\d+)\b""", RegexOption.IGNORE_CASE)
    private val PART = Regex("""\b(?:file\s+)?part\s*(\d{1,2})\b""", RegexOption.IGNORE_CASE)

    /** "Server N" / "Part N" from any of the given texts (anchor text,
     *  parent text, filename, heading), or null when nothing marks. */
    fun serverOrPart(vararg texts: String?): String? {
        for (t in texts) {
            if (t.isNullOrBlank()) continue
            SERVER.find(t)?.let { return "Server ${it.groupValues[1]}" }
            PART.find(t)?.let { return "Part ${it.groupValues[1]}" }
        }
        return null
    }
}
