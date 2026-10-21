package com.anonrode.downloader.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Off" persistence contract (2026-10-21 trace d6 / matrix row 13). The
 * sheet's Off used to store a plain null — indistinguishable from "never
 * chose" — so every fresh modal re-ran auto-pick and silently re-enabled
 * the subs the user had turned off. Off now persists as
 * [SUBTITLE_PREF_OFF]; [subtitlePrefHoldsUserOff] is the pure testable
 * derivation the modal gates auto-pick and text-renderer disable on.
 */
class SubtitleOffPersistenceTest {

    @Test
    fun sentinelStringIsADecisionNotALabel() {
        assertTrue(subtitlePrefHoldsUserOff(SUBTITLE_PREF_OFF))
        // The sentinel must never collide with the label vocabulary the
        // sheet itself lists: "Off" the ROW TITLE is not the stored value.
        assertNotEquals("Off", SUBTITLE_PREF_OFF)
    }

    @Test
    fun absentPrefMeansNeverChoseAutoPickAllowed() {
        assertFalse(subtitlePrefHoldsUserOff(null))
        assertFalse(subtitlePrefHoldsUserOff(""))
    }

    @Test
    fun realTrackLabelsAreNeverMistakenForTheDecision() {
        // Sidecar label, embedded-language label and synthetic id labels all
        // leave auto-pick trust intact (a re-pick restores normal behavior).
        assertFalse(subtitlePrefHoldsUserOff("Show.en.srt"))
        assertFalse(subtitlePrefHoldsUserOff("EN"))
        assertFalse(subtitlePrefHoldsUserOff("Track 3"))
    }

    @Test
    fun sentinelIsStableAcrossSessionReads() {
        // The modal strips the sentinel before any label display use
        // (chip/sheet show no selection). The strip is takeUnless(this).
        assertNull(SUBTITLE_PREF_OFF.takeUnless { subtitlePrefHoldsUserOff(it) })
        // Real labels survive the strip untouched.
        assertTrue("Show.en.srt" == "Show.en.srt".takeUnless { subtitlePrefHoldsUserOff(it) })
    }
}
