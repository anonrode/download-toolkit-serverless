package com.anonrode.downloader.resolvers

import com.anonrode.downloader.data.net.HttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the parsedHost retrofit (2026-09-12): resolver DISPATCH must be decided
 * by the host okhttp would actually fetch (HttpClient.parsedHost), never by
 * whether a host string merely appears in the URL. Forged URLs of the two
 * documented classes — userinfo (`claimed.com:443@evil.com`) and backslash
 * (`evil\.claimed.com`) — MUST be refused by every retrofit resolver, while
 * every real serving shape stays claimed.
 */
class ResolverHostClaimTest {

    // ---- the helper's entry-type semantics ---------------------------------

    @Test
    fun hostClaim_entryShapes() {
        // dotted domain: exact or sub-domain
        assertTrue(hostClaim("https://vikingfile.com/f/abc", listOf("vikingfile.com")))
        assertTrue(hostClaim("https://uz.vikingfile.com/f/abc", listOf("vikingfile.com")))
        assertTrue(hostClaim("https://VIKINGFILE.COM/f", listOf("vikingfile.com")))
        assertFalse(hostClaim("https://evilvikingfile.com/f", listOf("vikingfile.com")))
        assertFalse(hostClaim("https://vikingfile.comx.org/f", listOf("vikingfile.com")))
        assertFalse(hostClaim("https://evil.com/?u=https://vikingfile.com/f", listOf("vikingfile.com")))
        // trailing-dot fragment: label prefix at host start or after a dot
        assertTrue(hostClaim("https://dood.to/e/abc", listOf("dood.")))
        assertTrue(hostClaim("https://sub.dood.to/e/abc", listOf("dood.")))
        assertFalse(hostClaim("https://mydood.to/e", listOf("dood.")))
        assertFalse(hostClaim("https://evil.com/dood.to", listOf("dood.")))
        // host+path entry: host must be true, path may ride the string
        assertTrue(hostClaim("https://plutomovies.com/series/vincenzo-1", listOf("plutomovies.com/series/")))
        assertTrue(hostClaim("https://nepu.gd/watch/t123", listOf("nepu.gd/watch")))
        assertFalse(hostClaim("https://evil.com/plutomovies.com/series/x", listOf("plutomovies.com/series/")))
        assertFalse(hostClaim("https://plutomovies.com/movie", listOf("plutomovies.com/series/")))
        // path-only entry (empty host part): claims on the path alone — legacy
        // semantics, pinned so it can never rot into a silently-dead branch.
        assertTrue(hostClaim("https://evil.com/embed/x", listOf("/embed/")))
        assertFalse(hostClaim("https://evil.com/e/x", listOf("/embed/")))
        // dot-free fragment: substring of the HOST only
        assertTrue(hostClaim("https://ajmidyad.se/e/1", listOf("ajmidyad")))
        assertFalse(hostClaim("https://evil.com/?x=ajmidyad", listOf("ajmidyad")))
        // unparseable -> legacy string fallback keeps old behavior
        assertTrue(hostClaim("not a url vikingfile.com", listOf("vikingfile.com")))
    }

    // ---- the two forgery classes, per family -------------------------------

    @Test
    fun userinfoForgery_notClaimedByAnyRetrofitResolver() {
        val forged = "https://vikingfile.com:443@evil.com/f/abc"
        assertEquals("evil", HttpClient.parsedHost(forged)?.substringBefore(".")) // premise check
        assertFalse(VikingFileResolver.canResolve(forged))
        assertFalse(GenericLockerResolver.canResolve(forged))
        assertFalse(DownloadwellaResolver.canResolve("https://downloadwella.com@evil.com/x.mkv.html"))
        assertFalse(DoodstreamResolver.canResolve("https://dood.to@evil.com/e/1"))
        assertFalse(LightDLResolver.canResolve("https://lightdl.cc@evil.com/d/1"))
        assertFalse(PixelDrainResolver.canResolve("https://pixeldrain.com@evil.com/u/1"))
        assertFalse(PlutoMoviesResolver.canResolve("https://plutomovies.com@evil.com/series/1"))
    }

    @Test
    fun backslashForgery_notClaimedByAnyRetrofitResolver() {
        assertFalse(VikingFileResolver.canResolve("https://evil\\.vikingfile.com/f/1"))
        assertFalse(DownloadwellaResolver.canResolve("https://evil\\.downloadwella.com/f/1.mkv.html"))
        assertFalse(WaffiCloudResolver.canResolve("https://evil\\.waffi.cloud/f/1"))
    }

    @Test
    fun realShapes_stillClaimed() {
        // every family the app actually feeds these resolvers, one live shape each
        assertTrue(VikingFileResolver.canResolve("https://uz.vikingfile.com/f/AbC123"))
        assertTrue(GenericLockerResolver.canResolve("https://lulacloud.com/f/xyz"))
        assertTrue(DownloadwellaResolver.canResolve(
            "https://downloadwella.com/otvefiigsf40/Love.on.the.Menu.S01E01.(THENKIRI.COM).mkv.html?preview"))
        assertTrue(DownloadwellaResolver.canResolve("https://wetafiles.com/f/abc.mkv"))
        assertTrue(DoodstreamResolver.canResolve("https://d0000d.com/f/abc"))
        assertTrue(MixdropResolver.canResolve("https://mixdrop.bz/f/abc"))
        assertTrue(StreamtapeResolver.canResolve("https://streamtape.com/v/abc"))
        assertTrue(StreamwishResolver.canResolve("https://streamwish.to/e/abc"))
        assertTrue(StreamwishResolver.canResolve("https://hglink.to/e/abc"))
        assertTrue(StreamwishResolver.canResolve("https://ajmidyad.se/e/abc"))
        assertTrue(VidmolyResolver.canResolve("https://vidmoly.to/e/abc"))
        assertTrue(BloggerResolver.canResolve("https://www.blogger.com/video.g?token=abc"))
        assertTrue(VidsrcResolver.canResolve("https://vidsrc.cc/v2/embed/movie/123"))
        assertTrue(VidsrcResolver.canResolve("https://nepu.gd/watch/tt123"))
        assertFalse(VidsrcResolver.canResolve("https://evil.com/nepu.gd/watch")) // path-only no longer claims
        assertTrue(WildshareResolver.canResolve("https://wildshare.net/f/abc"))
        assertTrue(FivePlayResolver.canResolve("https://5play.cc/embed/1"))
        assertTrue(EmbedResolver.canResolve("https://megaplay.buzz/embed/1"))
        assertTrue(PlutoMoviesResolver.canResolve("https://dl.plutomovies.com/dl/abc"))
        assertTrue(PlutoMoviesResolver.canResolve("https://plutomovies.com/series/vincenzo-1/"))
        assertTrue(NaijaVaultGatewayResolver.canResolve("https://www.naijavault.com/dl-movie-abc/"))
        assertFalse(NaijaVaultGatewayResolver.canResolve("https://naijavault.com.evil.co/dl-abc/"))
        assertTrue(DramaGatewayResolver.canResolve("https://dramarain.com/download/abc"))
        assertTrue(KissasianResolver.canResolve("https://kissasian9.ro/kisskh/1.html"))
        assertTrue(KisskhMegaplayResolver.canResolve("https://megaplays.se/embed/1"))
        assertTrue(LoadedfilesResolver.canResolve("https://loadedfiles.net/f/abc"))
        assertFalse(LoadedfilesResolver.canResolve("https://evil.com/?u=loadedfiles.net"))
        assertTrue(WaffiCloudResolver.canResolve("https://japa.waffi.cloud/f/abc"))
        assertTrue(LightDLResolver.canResolve("https://lightdl.cc/d/6efdzqi"))
        assertFalse(LightDLResolver.canResolve("https://lightdl.cc/api/download/x"))
        assertTrue(VikingFileResolver.canResolve("https://vikingfile.com/f/x"))
        assertFalse(VikingFileResolver.canResolve("https://vikingfile.com/f/x.mp4")) // ext guard intact
        assertTrue(VikingFileResolver.canResolve("https://vikingfile.com/d/wtKpY7FHW2/Lanterns.S01E01.mkv")) // /d/ token path accepted
    }
}
