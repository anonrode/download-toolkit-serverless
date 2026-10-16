package com.anonrode.downloader.providers

import com.anonrode.downloader.data.rules.DynamicRulesManager
import com.anonrode.downloader.resolvers.DynamicLockerEngine
import com.anonrode.downloader.resolvers.ResolveLineage
import com.anonrode.downloader.resolvers.ResolverRegistry
import com.anonrode.downloader.resolvers.advanceResolveLineage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the resolve-chain recursion guards (2026-10-15 device crash:
 * a cyclic rule graph recursed ~243 DynamicLockerResolver frames deep until
 * StackOverflowError — see ResolveLineage). Pure-JVM only, no network: the
 * crafted pipelines use `terminal.source = "entry"` (zero-fetch recipes),
 * unique `.test` hosts that no compiled resolver claims, and every failure
 * path exercised here is refusal-by-guard.
 */
class CyclicHandoffTerminationTest {

    private val entryA = "https://cyclelock-a.test/go?u=https://cyclelock-b.test/next"
    private val entryB = "https://cyclelock-b.test/go?u=https://cyclelock-a.test/next"

    @After
    fun tearDown() {
        DynamicRulesManager.parseRulesJson("""{"version":"t.reset"}""")
    }

    /** Site A hands off to B, B hands off to A — a closed cycle. Both are
     *  terminal-only entry recipes, so the walk never needs a fetch. */
    private fun installCyclicRules() {
        DynamicRulesManager.parseRulesJson(
            """
            {
              "version": "test.cyclic-handoff",
              "pipelines": {
                "cyclelock-a.test": {
                  "schema": 1,
                  "terminal": {
                    "source": "entry",
                    "regex": "u=(https://cyclelock-b\\.test/\\S+)",
                    "group": 1,
                    "hosts": ["cyclelock-b.test"],
                    "mode": "handoff"
                  }
                },
                "cyclelock-b.test": {
                  "schema": 1,
                  "terminal": {
                    "source": "entry",
                    "regex": "u=(https://cyclelock-a\\.test/\\S+)",
                    "group": 1,
                    "hosts": ["cyclelock-a.test"],
                    "mode": "handoff"
                  }
                }
              }
            }
            """.trimIndent()
        )
    }

    @Test
    fun cyclicHandoffRuleGraph_terminatesWithNull() {
        installCyclicRules()

        // Sanity: the cyclic graph really is wired — A's terminal extracts the
        // B URL and vice versa (otherwise the nulls below would pass vacuously).
        val terminalA = DynamicRulesManager.getPipeline("cyclelock-a.test")?.terminal
        val terminalB = DynamicRulesManager.getPipeline("cyclelock-b.test")?.terminal
        assertNotNull(terminalA)
        assertNotNull(terminalB)
        assertEquals("https://cyclelock-b.test/next", RulesPipeline.resolveCandidate(terminalA!!, entryA, emptyMap()))
        assertEquals("https://cyclelock-a.test/next", RulesPipeline.resolveCandidate(terminalB!!, entryB, emptyMap()))

        // Each direction of the A <-> B cycle must terminate with null instead
        // of recursing into StackOverflowError.
        assertNull(runBlocking { RulesPipeline.runResolveForSite("cyclelock-a.test", entryA, "720p") })
        assertNull(runBlocking { RulesPipeline.runResolveForSite("cyclelock-b.test", entryB, "720p") })
    }

    @Test
    fun lockerReEntryGuard_refusesCyclesAndDepthLimit_butNotFreshChains() {
        // A -> B -> A: the third hop re-enters A and must be refused — this is
        // the exact re-entry that recursed ~243 frames deep on device.
        val hop1 = DynamicLockerEngine.nextLockerHop("cyclelock-a.test", "cyclelock-a.test", 0, emptySet())
        assertNotNull(hop1)
        val hop2 = DynamicLockerEngine.nextLockerHop("cyclelock-b.test", "cyclelock-b.test", 1, hop1!!)
        assertNotNull(hop2)
        assertNull(DynamicLockerEngine.nextLockerHop("cyclelock-a.test", "cyclelock-a.test", 2, hop2!!))

        // A legitimate chain of distinct sites keeps walking under the limit.
        var visited = emptySet<String>()
        for ((i, site) in listOf("one.test", "two.test", "three.test", "four.test").withIndex()) {
            val hop = DynamicLockerEngine.nextLockerHop(site, site, i, visited)
            assertNotNull("hop $i to $site must proceed", hop)
            visited = hop!!
        }
        assertEquals(4, visited.size) // distinct sites accumulate (key == host here)

        // Depth exhaustion refuses even a brand-new site.
        assertNull(
            DynamicLockerEngine.nextLockerHop("fresh.test", "fresh.test", ResolverRegistry.RESOLVE_DEPTH_LIMIT, emptySet())
        )

        // The real engine entry point refuses the pre-seeded chain state of
        // the crash moment (guard fires before any pipeline work).
        installCyclicRules()
        assertNull(runBlocking { DynamicLockerEngine.resolve(entryA, "720p", 2, setOf("cyclelock-a.test", "cyclelock-b.test")) })
        assertNull(runBlocking { DynamicLockerEngine.resolve(entryA, "720p", ResolverRegistry.RESOLVE_DEPTH_LIMIT, emptySet()) })
        assertNull(runBlocking { DynamicLockerEngine.resolve("https://unregistered-locker.test/file/1", "720p") })
    }

    @Test
    fun chainLineage_exhaustsBudgetEvenWhenCallersResetDepth() {
        // The gateway-resolver leak shape: every registry re-entry passes
        // depth 0 and no visited sites. The lineage must still grow.
        var lineage: ResolveLineage? = null
        var hops = 0
        var prevDepth = -1
        while (hops <= ResolverRegistry.RESOLVE_DEPTH_LIMIT + 2) {
            val next = advanceResolveLineage(lineage, 0, emptySet())
            assertTrue("depth must never shrink", next.depth > prevDepth)
            prevDepth = next.depth
            lineage = next
            hops++
            if (next.depth >= ResolverRegistry.RESOLVE_DEPTH_LIMIT) break
        }
        val finalLineage = lineage
        assertNotNull(finalLineage)
        assertTrue(finalLineage!!.depth >= ResolverRegistry.RESOLVE_DEPTH_LIMIT)
        // First hop is depth 0 (outermost), so the limit is reached within
        // LIMIT + 1 registry entries — never unboundedly.
        assertTrue(hops <= ResolverRegistry.RESOLVE_DEPTH_LIMIT + 1)
    }

    @Test
    fun chainLineage_unionsVisitedSitesCapsEntriesAndPassesTopLevelThrough() {
        val seeded = advanceResolveLineage(null, 0, setOf("site-a"))
        assertEquals(0, seeded.depth)
        assertEquals(1, seeded.entries)
        assertEquals(setOf("site-a"), seeded.visitedSites)

        // A reset-style nested caller (depth 0, empty visited) cannot drop the
        // chain's memory of site A, so the A re-entry stays a visible cycle.
        val nested = advanceResolveLineage(seeded, 0, emptySet())
        assertEquals(1, nested.depth)
        assertEquals(setOf("site-a"), nested.visitedSites)

        // Hard backstop: entries grow monotonically past MAX_ENTRIES.
        var deep: ResolveLineage? = seeded
        var entries = seeded.entries
        while (entries <= ResolveLineage.MAX_ENTRIES) {
            val next = advanceResolveLineage(deep, 0, emptySet())
            deep = next
            entries = next.entries
        }
        assertNotNull(deep)
        assertTrue(deep!!.entries > ResolveLineage.MAX_ENTRIES)

        // Outermost calls (no ambient lineage) pass arguments through: the
        // depth==0 cache/health affordances and every legit chain keep their
        // exact pre-fix values.
        val top = advanceResolveLineage(null, 3, setOf("x", "y"))
        assertEquals(3, top.depth)
        assertEquals(setOf("x", "y"), top.visitedSites)
        assertEquals(1, top.entries)
    }
}
