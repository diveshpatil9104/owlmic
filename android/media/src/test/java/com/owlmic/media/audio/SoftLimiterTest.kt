package com.owlmic.media.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftLimiterTest {
    @Test
    fun boostsAreFixedGains() {
        assertEquals(1f, SoftLimiter.gainFor(0), 0f)
        assertEquals(1.995f, SoftLimiter.gainFor(6), 0.01f)
        assertEquals(3.981f, SoftLimiter.gainFor(12), 0.01f)
    }

    @Test
    fun quietSamplesScaleLinearly() {
        val pcm = shortArrayOf(1_000, -1_000)
        SoftLimiter.apply(pcm, 2, 2f)
        assertEquals(2_000f, pcm[0].toFloat(), 1f)
        assertEquals(-2_000f, pcm[1].toFloat(), 1f)
    }

    @Test
    fun peaksRoundOffInsteadOfClipping() {
        // At +12 dB a hard clip would flatten all three; the soft knee keeps them apart and below full scale.
        val pcm = shortArrayOf(7_000, 9_000, -9_000)
        SoftLimiter.apply(pcm, 3, SoftLimiter.gainFor(12))
        assertTrue(pcm[0] in 26_000 until pcm[1])
        assertTrue(pcm[1] < 32_767)
        assertEquals(-pcm[1].toInt(), pcm[2].toInt())
    }

    @Test
    fun noBoostLeavesTheSignalAlone() {
        val pcm = shortArrayOf(32_767, -32_768, 5)
        SoftLimiter.apply(pcm, 3, 1f)
        assertEquals(listOf<Short>(32_767, -32_768, 5), pcm.toList())
    }
}
