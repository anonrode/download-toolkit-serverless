package com.anonrode.downloader.engine

import java.net.URI

internal object MirrorPool {
    const val MAX_SOURCES = 8

    fun sources(primary: String, alternatives: List<String>): List<String> =
        (sequenceOf(primary) + alternatives.asSequence())
            .filter { url ->
                try {
                    val uri = URI(url)
                    uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null
                } catch (_: Exception) { false }
            }
            .distinct()
            .take(MAX_SOURCES)
            .toList()

    // Without byte-identity proof, a partial belongs only to its selected source.
    fun eligible(primary: String, alternatives: List<String>, selected: String, hasPartial: Boolean): List<String> {
        val all = sources(primary, alternatives)
        val current = selected.takeIf { it in all } ?: primary
        return if (hasPartial) listOf(current) else sources(current, all)
    }
}
