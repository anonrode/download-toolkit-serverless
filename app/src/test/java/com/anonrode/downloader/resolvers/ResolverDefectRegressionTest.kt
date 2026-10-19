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

    private fun sourceAt(relativePath: String): String {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/java/com/anonrode/downloader/resolvers/Resolvers.kt").isFile }
            ?: error("Repository source needed for source-contract regression checks")
        return File(root, relativePath).readText()
    }

    private fun resolverSource(): String = sourceAt("app/src/main/java/com/anonrode/downloader/resolvers/Resolvers.kt")

    // TRIAGE (A) TEST DRIFT: the stall watchdog re-reads the freshest state as
    // `val t = repository.find(task.id)` (DownloadEngine.kt), so its
    // `bytesLanded(t)` IS the live variable — banning all `bytesLanded(t)` text
    // would now ban the anti-stale pattern itself. Restated invariant: the
    // failover banking decision uses the live re-read state and hands that same
    // state to attemptCrossProviderFailover; no other `bytesLanded(t)` use
    // (stale lambda variables anywhere else) is allowed.
    @Test fun queueSourceDoesNotUseStaleTaskVariable() {
        val source = sourceAt("app/src/main/java/com/anonrode/downloader/engine/DownloadEngine.kt")
        assertTrue(source.contains("bytesLanded(task)"))
        source.lineSequence().forEachIndexed { idx, line ->
            if (line.contains("bytesLanded(t)")) {
                assertTrue(
                    "stale bytesLanded(t) at line ${idx + 1}: $line",
                    line.contains("t.downloadedBytes == 0L") && line.contains("attemptCrossProviderFailover(t)")
                )
            }
        }
        // The watchdog must re-read state from the repository per tick — that is
        // what makes its variable live rather than stale.
        // TRIAGE (A) TEST DRIFT: the re-read is now `val live =
        // repository.find(task.id) ?: task` (renamed t → live; the null branch
        // falls back to the captured task instead of breaking — the same
        // per-tick freshness contract, and the dead `?: break` form no longer
        // exists in the engine).
        assertTrue(source.contains("val live = repository.find(task.id) ?: task"))
    }

    @Test fun concurrencyDefaultsAndBoundsStayBounded() {
        val settings = sourceAt("app/src/main/java/com/anonrode/downloader/data/settings/AppSettings.kt")
        val engine = sourceAt("app/src/main/java/com/anonrode/downloader/engine/DownloadEngine.kt")
        assertTrue(settings.contains("val maxConcurrentDownloads: Int = 4"))
        assertTrue(settings.contains("""pref_max_downloads", 4).coerceIn(1, 6"""))
        assertTrue(settings.contains("""pref_parallel_sockets", 16).coerceIn(1, 16"""))
        assertTrue(engine.contains("var maxConcurrentDownloads: Int = 4"))
        // TRIAGE (A) TEST DRIFT: processQueue's UNION guard is now built from
        // status counts ∪ runtimeRegistry.activeRunningTaskIds(), and that
        // registry accessor returns entries that are isStarting OR have an
        // active job (TaskRuntimeRegistry: activeJob?.isActive == true ||
        // isStarting.get()) — the starting-but-not-yet-registered window is
        // still counted into THE concurrency choke-point.
        assertTrue(engine.contains("addAll(runtimeRegistry.activeRunningTaskIds())"))
        assertTrue(engine.contains("if (activeCount >= maxConcurrentDownloads) return"))
    }

    // TRIAGE (A) TEST DRIFT: the OTA handoff call grew named bypassHealth /
    // recordHealth / visitedSites args and the recursion cap was tightened from
    // `depth >` to `depth >=` (strictly safer). Restated invariant: depth is
    // still carried +1 through the registry handoff and both sides refuse past
    // RESOLVE_DEPTH_LIMIT.
    @Test fun otaHandoffCarriesResolverDepth() {
        val source = sourceAt("app/src/main/java/com/anonrode/downloader/providers/RulesPipeline.kt")
        val engine = sourceAt("app/src/main/java/com/anonrode/downloader/resolvers/DynamicLockerEngine.kt")
        assertTrue(Regex("ResolverRegistry\\.resolve\\(\\s*cand,\\s*quality,\\s*depth \\+ 1,").containsMatchIn(source))
        assertTrue(source.contains("depth >= ResolverRegistry.RESOLVE_DEPTH_LIMIT"))
        assertTrue(engine.contains("runResolveForSite(siteKey, url, quality, depth + 1,"))
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
        // TRIAGE (A) TEST DRIFT: the hop budget was widened 8 → 10 (still a
        // bounded `1..N` loop; expanded budget for longer token chains).
        assertTrue(source.contains("for (step in 1..10)"))
        // Existing terminal/header-first behavior must not be lost to a loop rewrite.
        // TRIAGE (A) TEST DRIFT: the branch gained more exempt shapes after the
        // ptHops check (mirror TLD, direct-media, /d/ links) — invariant intact.
        assertTrue(source.contains("if (ptHops >= 1 ||"))
        assertTrue(source.contains("safeLoc.contains(\"/token/download/\")"))
        val mediaHeader = source.indexOf("ct.startsWith(\"video/\")")
        val bodyRead = source.indexOf("val body = HttpClient.cappedText(res)")
        assertTrue(mediaHeader >= 0 && bodyRead > mediaHeader)
        assertTrue(source.contains("currUrl = HttpClient.safeUrl(next)"))
        // Clearing the pin is not a same-call mirror-fallback implementation.
        assertTrue(source.contains("lastWorkingHost = null"))
    }
}
