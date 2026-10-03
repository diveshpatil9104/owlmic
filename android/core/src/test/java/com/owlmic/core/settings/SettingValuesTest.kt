package com.owlmic.core.settings

import com.owlmic.core.proto.SettingChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingValuesTest {
    private val base = SettingValues.defaults()

    @Test
    fun defaultsCoverPhoneAndSharedSettingsOnly() {
        assertEquals("back", base.getValue("camera.lens").value)
        assertTrue("camera.orientation" in base)
        assertFalse("camera.framing" in base)
        assertFalse("speaker.quietPc" in base)
    }

    @Test
    fun aLocalChangeMovesPastEveryVersionSeen() {
        val (synced, _) = SettingValues.applyRemote(base, listOf(SettingChange("mic.boost", "plus6", 7)))
        val v = SettingValues.change(synced, "camera.lens", "front")
        assertEquals(Versioned("front", 8), v["camera.lens"])
        assertEquals(v, SettingValues.change(v, "camera.lens", "sideways"))
    }

    @Test
    fun theNewerChangeWinsAndTheTieGoesToThePc() {
        val mine = SettingValues.change(base, "camera.lens", "front")
        val version = mine.getValue("camera.lens").version
        val (older, noChange) = SettingValues.applyRemote(mine, listOf(SettingChange("camera.lens", "back", version - 1)))
        assertEquals("front", older.getValue("camera.lens").value)
        assertTrue(noChange.isEmpty())
        val (tie, changed) = SettingValues.applyRemote(mine, listOf(SettingChange("camera.lens", "back", version)))
        assertEquals("back", tie.getValue("camera.lens").value)
        assertEquals(setOf("camera.lens"), changed)
    }

    @Test
    fun remoteChangesToPcOnlyOrInvalidValuesAreIgnored() {
        val (v, changed) = SettingValues.applyRemote(
            base,
            listOf(SettingChange("camera.framing", "fit", 99), SettingChange("mic.boost", "plus99", 99)),
        )
        assertEquals(base, v)
        assertTrue(changed.isEmpty())
    }

    @Test
    fun welcomeTakesThePcsValuesExceptThoseChangedWhileAway() {
        val mine = SettingValues.change(base, "camera.quality", "1080p")
        val v = SettingValues.applyWelcome(mine, mapOf("camera.quality" to "720p", "camera.frameRate" to "30"), pending = setOf("camera.quality"))
        assertEquals("1080p", v.getValue("camera.quality").value)
        assertEquals("30", v.getValue("camera.frameRate").value)
    }
}
