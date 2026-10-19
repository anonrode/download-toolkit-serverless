package com.anonrode.downloader.data.rules

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RulesFlags v3.3.0 additions (A1/F-05): the four platform-surface families
 * are part of the client whitelist (lockstep with encrypt_rules.py
 * FAMILY_IDS) and gate through the same stable-bucket decision as every
 * other family.
 */
class RulesFlagsTest {

    @Test
    fun familyIds_includeThePlatformSurfaces() {
        for (id in listOf("announcements", "update", "ads", "engagement")) {
            assertTrue("FAMILY_IDS missing $id", RulesFlags.FAMILY_IDS.contains(id))
        }
    }

    @Test
    fun parse_acceptsNewFamiliesWithoutJournalingUnknown() {
        val journal = mutableListOf<String>()
        val flags = RulesFlags.parse(
            JSONObject("""{"announcements": {"percent": 25}, "update": {"enabled": true}}""")
        ) { journal.add(it) }
        assertEquals(2, flags.size)
        assertEquals(25, flags.getValue("announcements").percent)
        assertTrue(flags.getValue("update").enabled)
        assertTrue("no journal entries expected: $journal", journal.isEmpty())
    }

    @Test
    fun announcementsFamily_gatesThroughStableBucket() {
        val uuid = "rules-flags-test-uuid"
        // percent = 0 -> selected by no bucket (feature OFF for everyone).
        assertFalse(RulesFlags.isFamilyActiveForDevice(
            FamilyFlag(percent = 0), "announcements", uuid))
        // percent = 100 -> every device.
        assertTrue(RulesFlags.isFamilyActiveForDevice(
            FamilyFlag(percent = 100), "announcements", uuid))
        // The kill switch beats any percent.
        assertFalse(RulesFlags.isFamilyActiveForDevice(
            FamilyFlag(enabled = false, percent = 100), "announcements", uuid))
        // Partial rollout: the decision is exactly the sticky bucket compare.
        val partial = FamilyFlag(percent = 50)
        val bucket = RulesFlags.stableBucket(uuid, "announcements")
        assertEquals(bucket < 50,
            RulesFlags.isFamilyActiveForDevice(partial, "announcements", uuid))
    }
}
