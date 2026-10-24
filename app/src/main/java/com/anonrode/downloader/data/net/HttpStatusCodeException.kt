package com.anonrode.downloader.data.net

/**
 * A non-2xx answer that IS a real HTTP status — the server's edge named its
 * rejection (Cloudflare-style 403/436 embed blocks, 451 legal blocks, plain
 * 404s) and the transport can carry the code end to end. Subclass of
 * [java.io.IOException] with the EXACT same message the untyped throw
 * produced ("HTTP {code} for {url}") so every existing [Throwable.message]
 * caller (lastFailure, park reasons, resolver error text) keeps its shape.
 *
 * Before this class existed, getText swallowed the code into a bare
 * IOException string and every consumer re-parsed prose (investigation 11,
 * share-43: 2anime.xyz's 436 was fetched FIVE times inside one resolve —
 * nothing ever learned the host rejects us until the task died "chain
 * EMPTY").
 */
internal class HttpStatusCodeException(
    val code: Int,
    url: String,
    val isChallenge: Boolean = false
) : java.io.IOException("HTTP $code for ${url.take(120)}")
