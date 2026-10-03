package com.owlmic.media.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelMeterTest {

    @Test
    fun silenceIsEmptyAndFullScaleIsFull() {
        assertEquals(0f, levelFor(0.0))
        assertEquals(0f, levelFor(32768 * 0.0005)) // about -66 dBFS
        assertEquals(1f, levelFor(32768 * 0.5)) // about -6 dBFS
        assertEquals(0.5f, levelFor(32768 * 0.01585), 0.01f) // -36 dBFS, halfway
    }

    @Test
    fun rmsOfAlternatingSamples() {
        assertEquals(1000.0, rms(shortArrayOf(1000, -1000)), 0.001)
    }

    @Test
    fun theLevelRisesFastAndFallsSlowly() {
        val meter = LevelMeter()
        val loud = ShortArray(480) { 0x4000 } // half scale
        val quiet = ShortArray(480)
        val up = meter.add(loud)
        assertTrue("one loud frame gets most of the way up", up > 0.5f)
        val down = meter.add(quiet)
        assertTrue("one quiet frame only takes a little off", down > up * 0.7f)
    }
}
