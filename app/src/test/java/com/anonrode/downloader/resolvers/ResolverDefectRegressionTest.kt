package com.anonrode.downloader.resolvers

import com.anonrode.downloader.providers.NkiriProvider
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * CI-only. Nkiri tests invoke production parsing. Registry/Loadedfiles checks
 * are source-contract guards, not mocked HTTP integration tests: those private
 * paths currently have no injectable network/registry seam. Keep that distinction
 * explicit until full behavioral coverage can be added without production edits.
 */
class ResolverDefectRegressionTest {
    private fun unwrap(url: String): String {
        val method = NkiriProvider::class.java.getDeclaredMethod(
            "unwrapDownloadManagerRedirect", String::class.java
        )
        method.isAccessible = true
        return method.invoke(NkiriProvider, url) as String
    }

    @Test fun firstAndLaterRedirectParametersAreDecoded() {
        assertEquals("https://downloadwella.com/Movie.mkv.html", unwrap(
            "https://thenkiri.com/dl/download-1/?redirect=https%3A%2F%2Fdownloadwella.com%2FMovie.mkv.html"
        ))
        assertEquals("https://nkiserv.com/series/S01/E19.mkv?a=1&b=2", unwrap(
            "https://thenkiri.com/dl/download-2/?foo=1&redirect=https%3A%2F%2Fnkiserv.com%2Fseries%2FS01%2FE19.mkv%3Fa%3D1%26b%3D2"
        ))
        assertEquals("http://nkiserv.com/movie.mp4", unwrap(
            "https://thenkiri.com/dl/download-3/?REDIRECT=http%3A%2F%2Fnkiserv.com%2Fmovie.mp4"
        ))
    }

    @Test fun duplicateParametersUseFirstAndDecodeOnlyOnce() {
        assertEquals("https://a.example/first", unwrap(
            "https://thenkiri.com/dl/x/?redirect=https://a.example/first&redirect=https://b.example/second"
        ))
        assertEquals("https://a.example/a%2Fb.mkv?token=a%2Bb", unwrap(
            "https://thenkiri.com/dl/x/?redirect=https%3A%2F%2Fa.example%2Fa%252Fb.mkv%3Ftoken%3Da%252Bb"
        ))
    }

    @Test fun invalidTargetsAndNonWrapperPathsRemainUnchanged() {
        val inputs = listOf(
            "https://nkiserv.com/movies/Movie.mp4",
            "https://thenkiri.com/post/?redirect=https://a.example/1",
            "https://thenkiri.com/dl/x/?url=https://a.example/1",
            "https://thenkiri.com/dl/x/?redirect=ftp://a.example/1",
            "https://thenkiri.com/dl/x/?redirect=httpx://a.example/1",
            "https://thenkiri.com/dl/x/?redirect=https://",
            "https://thenkiri.com/dl/x/?redirect=https:relative",
            "https://thenkiri.com/dl/x/?redirect=%ZZbad",
            "https://thenkiri.com/dl/x/?redirect=",
            "https://thenkiri.com/dl/x/#redirect=https://a.example/1"
        )
        inputs.forEach { assertEquals(it, it, unwrap(it)) }
    }

    private fun resolverSource(): String {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/java/com/anonrode/downloader/resolvers/Resolvers.kt").isFile }
            ?: error("Repository source needed for source-contract regression checks")
        return File(root, "app/src/main/java/com/anonrode/downloader/resolvers/Resolvers.kt").readText()
    }

    @Test fun recursiveFailureGuardStaysBeforeIntermediarySuccess() {
        val source = resolverSource()
        val branch = source.substringAfter("val deeper = resolveInternal(direct, quality, depth + 1)")
            .substringBefore("return ResolverOutcome.Success(direct)")
        assertTrue(branch.contains("if (deeper is ResolverOutcome.Success) return deeper"))
        assertTrue(branch.contains("if (deeper is ResolverOutcome.Failure) return deeper"))
        // The guard condition includes the sameResolverReclaims check:
        assertTrue(source.contains("!(sameResolverReclaims && mediaPath)"))
        // Universal direct-stream guard (bd93aae follow-up): any provably-direct
        // resolver output bypasses the recursive descent entirely so no other
        // resolver can POST to a finished media file. Both conditions must appear
        // together in the guard branch.
        assertTrue(source.contains("isProvablyDirectFile(direct)"))
        assertTrue(source.contains("!com.anonrode.downloader.pipeline.LinkResolver.isProvablyDirectFile(direct) &&"))
        // Does not claim coverage/fixes for NoMatch or exhausted depth.
    }

    @Test fun stalledTokenGuardAndExistingTerminalBranchesRemain() {
        val source = resolverSource().substringAfter("object LoadedfilesResolver : BaseResolver {")
            .substringBefore("private suspend fun probeEffectiveUrl")
        assertTrue(source.contains("val pageBeforeStep = currUrl"))
        assertTrue(Regex("if \\(currUrl == pageBeforeStep\\) \\{[^}]*\\bbreak\\b").containsMatchIn(source))
        assertTrue(source.contains("for (step in 1..8)"))
        // Existing terminal/header-first behavior must not be lost to a loop rewrite.
        assertTrue(source.contains("if (ptHops >= 1)"))
        assertTrue(source.contains("safeLoc.contains(\"/token/download/\")"))
        val mediaHeader = source.indexOf("ct.startsWith(\"video/\")")
        val bodyRead = source.indexOf("val body = HttpClient.cappedText(res)")
        assertTrue(mediaHeader >= 0 && bodyRead > mediaHeader)
        assertTrue(source.contains("currUrl = HttpClient.safeUrl(next)"))
        // Clearing the pin is not a same-call mirror-fallback implementation.
        assertTrue(source.contains("lastWorkingHost = null"))
    }
}
