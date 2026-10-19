package com.owlmic.media.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicConfigTest {
    private val defaults = mapOf("mic.noiseReduction" to "5", "mic.isolation" to "on")

    @Test
    fun theEchoCancellerRunsWhileTheSpeakerPlaysUnlessIsolationIsOff() {
        assertTrue(MicConfig.of(MicCodec.PCM, speakerOn = true, defaults).voiceCommunication)
        assertFalse(MicConfig.of(MicCodec.PCM, speakerOn = false, defaults).voiceCommunication)
        assertFalse(MicConfig.of(MicCodec.PCM, speakerOn = true, defaults + ("mic.isolation" to "off")).voiceCommunication)
    }

    @Test
    fun thePhonesSuppressorRunsFromLevelFiveUp() {
        val at = { level: String -> MicConfig.of(MicCodec.OPUS_WIFI, false, defaults + ("mic.noiseReduction" to level)).noiseReduction }
        assertFalse(at("0"))
        assertFalse(at("4"))
        assertTrue(at("5"))
        assertTrue(at("10"))
        assertTrue("an unknown level is the default", MicConfig.of(MicCodec.PCM, false, emptyMap()).noiseReduction)
    }
}
