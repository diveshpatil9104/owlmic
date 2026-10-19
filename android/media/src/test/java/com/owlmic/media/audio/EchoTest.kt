package com.owlmic.media.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoTest {
    @Test
    fun theFarEndRemembersTheLoudestOfTheLast300Ms() {
        var t = 1_000L
        val far = FarEnd { t }
        far.played(-20f)
        t += 100
        far.played(-40f)
        t += 100
        assertEquals(-20f, far.loudest(), 0f)
        t += 150
        assertEquals("the first frame's echo is over", -40f, far.loudest(), 0f)
        t += 300
        assertTrue(far.loudest() < -90f)
    }

    @Test
    fun withNothingPlayingTheMicPasses() {
        val echo = EchoSuppressor()
        assertEquals(1f, echo.target(micDb = -30f, farDb = -80f), 0f)
    }

    @Test
    fun theSpeakersEchoAloneIsTurnedDownFromTheFirstFrame() {
        val echo = EchoSuppressor()
        repeat(50) { assertTrue(echo.target(micDb = -30f, farDb = -20f) < 0.1f) }
        assertEquals(-10f, echo.couplingDb!!, 0.01f)
    }

    @Test
    fun whereTheCancellerWorksAVoiceOverTheSpeakerPasses() {
        val echo = EchoSuppressor()
        // The canceller leaves the echo 30 dB under what plays.
        repeat(40) { echo.target(micDb = -50f, farDb = -20f) }
        assertEquals(1f, echo.target(micDb = -35f, farDb = -20f), 0f)
    }

    @Test
    fun whereItIsWeakOnlyAClearlyLouderVoicePasses() {
        val echo = EchoSuppressor()
        repeat(40) { echo.target(micDb = -25f, farDb = -20f) }
        assertTrue("about as loud as the echo", echo.target(micDb = -22f, farDb = -20f) < 0.1f)
        assertEquals("clearly louder: someone talks on this side", 1f, echo.target(micDb = -12f, farDb = -20f), 0f)
    }

    @Test
    fun aLongVoiceDoesNotTeachItThatTheVoiceIsEcho() {
        val echo = EchoSuppressor()
        repeat(40) { echo.target(micDb = -50f, farDb = -20f) }
        // 2.5 s of someone talking over the Speaker, then the voice is still told apart.
        repeat(250) { echo.target(micDb = -30f, farDb = -20f) }
        assertEquals(1f, echo.target(micDb = -30f, farDb = -20f), 0f)
    }

    @Test
    fun theFrameIsTurnedDownSmoothly() {
        val echo = EchoSuppressor()
        val frame = ShortArray(480) { 1000 }
        echo.process(frame, 480, farDb = -10f)
        assertEquals("starts where the last frame ended", 1000, frame[0].toInt())
        assertTrue("down within 2.5 ms", frame[200] < 70)
    }

    @Test
    fun communicationModeOnlyWhileBothIsolateOutOfThePhonesSpeaker() {
        assertTrue(CallMode.wanted(micIsolated = true, speakerIsolated = true, headset = false))
        assertFalse(CallMode.wanted(micIsolated = true, speakerIsolated = true, headset = true))
        assertFalse(CallMode.wanted(micIsolated = true, speakerIsolated = false, headset = false))
        assertFalse(CallMode.wanted(micIsolated = false, speakerIsolated = true, headset = false))
    }
}
