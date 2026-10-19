package com.anonrode.downloader.update

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.anonrode.downloader.BuildConfig
import com.anonrode.downloader.data.rules.DynamicRulesManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CI-executed feature tests for the v3.3.0 announcement picker
 * (AnnouncementManager): payload-order priority, minApp/expiry gates, the
 * oncePer id/rev shown-marker semantics (marker persisted at PICK time), and
 * the off-state guarantee (absent section -> nothing to render).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class AnnouncementFlowTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun reset() {
        DynamicRulesManager.parseRulesJson("""{"version":"qa-ann-reset"}""")
        AnnouncementManager.current.value = null
        ctx.getSharedPreferences("downloader_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun payload(vararg entries: String) = """
        { "version": "qa-ann", "announcements": [ ${entries.joinToString(",")} ] }
    """.trimIndent()

    private fun entry(
        id: String,
        title: String = "Title $id",
        minApp: Int = 0,
        expiresAtMs: Long = 0L,
        oncePer: String = "id"
    ) = """
        {"id":"$id","title":"$title","body":"Body $id","mode":"dialog",
         "action":"none","actionLabel":"","actionUrl":"",
         "oncePer":"$oncePer","minApp":$minApp,"expiresAtMs":$expiresAtMs}
    """.trimIndent()

    @Test
    fun absentSection_rendersNothing() {
        AnnouncementManager.refresh(ctx)
        assertNull(AnnouncementManager.current.value)
    }

    @Test
    fun picksFirstEligible_mapsFields() {
        DynamicRulesManager.parseRulesJson(payload(entry("a1"), entry("a2")))
        AnnouncementManager.refresh(ctx)
        val cur = AnnouncementManager.current.value
        assertNotNull(cur)
        assertEquals("a1", cur!!.id)
        assertEquals("Title a1", cur.title)
        assertEquals("Body a1", cur.body)
        assertEquals("dialog", cur.mode)
    }

    @Test
    fun minAppAboveThisBuild_isSkipped() {
        DynamicRulesManager.parseRulesJson(
            payload(entry("future", minApp = BuildConfig.VERSION_CODE + 1), entry("ok"))
        )
        AnnouncementManager.refresh(ctx)
        assertEquals("ok", AnnouncementManager.current.value?.id)
    }

    @Test
    fun expired_isSkipped() {
        DynamicRulesManager.parseRulesJson(
            payload(entry("old", expiresAtMs = 1L), entry("ok"))
        )
        AnnouncementManager.refresh(ctx)
        assertEquals("ok", AnnouncementManager.current.value?.id)
    }

    @Test
    fun blankTitle_isSkipped() {
        DynamicRulesManager.parseRulesJson(
            payload(entry("blank", title = ""), entry("ok"))
        )
        AnnouncementManager.refresh(ctx)
        assertEquals("ok", AnnouncementManager.current.value?.id)
    }

    @Test
    fun dismiss_clears_andShownMarkerPreventsReplay() {
        DynamicRulesManager.parseRulesJson(payload(entry("a1"), entry("a2")))
        AnnouncementManager.refresh(ctx)
        assertEquals("a1", AnnouncementManager.current.value?.id)
        AnnouncementManager.dismiss(ctx, "a1")
        assertNull(AnnouncementManager.current.value)
        // The shown-marker was persisted at PICK time: a1 must not replay;
        // the next eligible entry takes the slot.
        AnnouncementManager.refresh(ctx)
        assertEquals("a2", AnnouncementManager.current.value?.id)
    }

    @Test
    fun revScoped_shownOncePerIdPerActiveRev() {
        DynamicRulesManager.parseRulesJson(
            payload(entry("r1", oncePer = "rev"), entry("r2", oncePer = "rev"))
        )
        AnnouncementManager.refresh(ctx)
        assertEquals("r1", AnnouncementManager.current.value?.id)
        AnnouncementManager.dismiss(ctx, "r1")
        // Fixed semantics (QA-V3 F9): the rev marker key now carries the id,
        // so a DIFFERENT rev-scoped announcement still renders within the
        // same rev; only a re-show of the SAME id waits for the next rev.
        AnnouncementManager.refresh(ctx)
        assertEquals("r2", AnnouncementManager.current.value?.id)
    }
}
