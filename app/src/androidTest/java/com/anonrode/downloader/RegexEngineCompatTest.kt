package com.anonrode.downloader

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anonrode.downloader.providers.NkiriProvider
import com.anonrode.downloader.util.DownloadLinkLabels
import com.anonrode.downloader.util.NameSanitizer
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tier-2 DEVICE-ENGINE GATE — the permanent answer to the v3.1.1/v3.1.2 crash
 * class. The desktop JVM (unit tests, CI `build` job) compiles regex syntax
 * the phone's java.util.regex may reject; both shipping incidents died inside
 * a class <clinit> at `DownloadEngine.sanitizeComponent` with an
 * ExceptionInInitializerError whose real message never reached the activity
 * log. This suite runs ON A REAL ANDROID EMULATOR against the SAME engine the
 * app uses: every regex-bearing class is class-initialized, every static
 * pattern literal is Pattern.compile'd, and every interpolated builder is
 * CALLED (so its runtime-compiled patterns hit the device engine too). A
 * failure message names the class/file + the exact pattern + the full cause
 * chain — the answer the truncated crash logs withheld.
 *
 * Catalog is generated from source (scripts/gen_regex_compat_catalog.py);
 * rerun the generator after adding any regex and commit both.
 */
@RunWith(AndroidJUnit4::class)
class RegexEngineCompatTest {

    @Test
    fun everyRegexBearingClassInitializesOnTheDeviceEngine() {
        val failures = mutableListOf<String>()
        var loaded = 0
        var absent = 0
        for (name in RegexCompatCatalog.CLINIT_CLASSES) {
            try {
                Class.forName(name)
                loaded++
            } catch (e: ClassNotFoundException) {
                // catalog drift (renamed/moved type) — not an engine verdict
                absent++
            } catch (e: Throwable) {
                failures.add("$name -> ${chain(e)}")
            }
        }
        assertTrue(
            "device engine rejected class init ($loaded loaded, $absent absent):\n" +
                failures.joinToString("\n"),
            failures.isEmpty()
        )
    }

    @Test
    fun everyStaticPatternCompilesOnTheDeviceEngine() {
        val failures = mutableListOf<String>()
        for ((origin, pattern) in RegexCompatCatalog.STATIC_PATTERNS) {
            try {
                java.util.regex.Pattern.compile(pattern)
            } catch (e: Throwable) {
                failures.add("$origin :: pattern=$pattern :: ${chain(e)}")
            }
        }
        assertTrue(
            "device engine rejected ${failures.size}/${RegexCompatCatalog.STATIC_PATTERNS.size} patterns:\n" +
                failures.joinToString("\n"),
            failures.isEmpty()
        )
    }

    @Test
    fun interpolatedPatternBuildersRunOnTheDeviceEngine() {
        // $alts templates (sepBare/endBare) compile at call time — invoke the
        // real entry points so the DEVICE engine is what parses them.
        NameSanitizer.savedName("Show - Watch Online")
        NameSanitizer.savedName("Show _ Free Download")
        NameSanitizer.savedName("Show - TV Series")
        NameSanitizer.savedName("Show - Full Movie")
        NameSanitizer.savedName("Show 17 & 18 Added")
        NameSanitizer.savedName("The Pitt S02 _Episode 15 Added_ _ TV Series - Episode 1")
        NameSanitizer.savedName("Dune (2021)", stripNoise = false)
        DownloadLinkLabels.serverOrPart("Server 2", "file part 12", "Download")
        NkiriProvider.headingEpisodeNumbers("Episode 17 & 18")
        NkiriProvider.headingEpisodeNumbers("E5")
        NkiriProvider.filenameEpisodeNums("https://x/Alchemy.of.Souls.E17.NKIRI.COM.mkv")
    }

    private fun chain(t: Throwable): String {
        val sb = StringBuilder()
        var c: Throwable? = t
        var depth = 0
        while (c != null && depth < 6) {
            sb.append("\n  cause").append(depth).append(": ")
                .append(c.javaClass.name).append(": ").append(c.message)
            c = c.cause
            depth++
        }
        return sb.toString()
    }
}
