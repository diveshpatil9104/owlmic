package com.owlmic.core.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignTest {
    @Test
    fun tokensComeFromTheDesignFile() {
        assertEquals(0xFF000000, Tokens.Color.BG)
        assertEquals(0xFF30D158, Tokens.Color.GREEN)
        assertEquals(TextSpec(17f, 22f, 600), Tokens.Text.TITLE)
        assertEquals(56f, Tokens.Phone.HEADER_DP)
    }

    @Test
    fun namesComeFromTheDesignFile() {
        assertEquals("Owlmic Mic", Names.MIC_DEVICE)
        assertEquals("Owlmic Cam", Names.CAM_DEVICE)
    }

    @Test
    fun everySettingDefaultsToOneOfItsValuesAndIsShownSomewhere() {
        for (s in SettingsModel.ALL) {
            assertTrue("${s.id} defaults to ${s.default}", s.default in s.values)
            assertTrue("${s.id} is never shown", s.onPhone || s.onPc)
        }
        assertEquals(SettingsModel.ALL.size, SettingsModel.ALL.map { it.id }.toSet().size)
        assertEquals(Scope.PC, SettingsModel.find("speaker.quietPc")!!.scope)
        assertNull(SettingsModel.find("nothing"))
    }
}
