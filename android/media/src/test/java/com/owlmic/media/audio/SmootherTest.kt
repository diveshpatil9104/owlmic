package com.owlmic.media.audio

import com.owlmic.media.audio.Smoother.Pull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SmootherTest {
    private var t = 0L
    private val s = Smoother(frameMs = 10, minMs = 20, maxMs = 80) { t }

    private fun frame(n: Int) = byteArrayOf(n.toByte())

    private fun pushOnTime(seq: Long) {
        t += 10
        s.push(seq, frame(seq.toInt()))
    }

    @Test
    fun fillsToTheTargetBeforePlayingThenPlaysInOrder() {
        pushOnTime(0)
        assertSame(Pull.Silence, s.pull())
        pushOnTime(1)
        assertArrayEquals(frame(0), (s.pull() as Pull.Frame).data)
        assertArrayEquals(frame(1), (s.pull() as Pull.Frame).data)
    }

    @Test
    fun aMissingFrameIsConcealedWithTheNextForFec() {
        for (seq in listOf(0L, 1L, 3L)) pushOnTime(seq)
        s.pull()
        s.pull()
        val lost = s.pull()
        assertTrue(lost is Pull.Lost)
        assertArrayEquals(frame(3), (lost as Pull.Lost).next)
        assertArrayEquals(frame(3), (s.pull() as Pull.Frame).data)
    }

    @Test
    fun anEmptyBufferConcealsBrieflyThenRebuffers() {
        pushOnTime(0)
        pushOnTime(1)
        s.pull()
        s.pull()
        repeat(3) { assertNull((s.pull() as Pull.Lost).next) }
        assertSame(Pull.Silence, s.pull())
    }

    @Test
    fun aLatePacketForAPlayedSlotIsDropped() {
        pushOnTime(0)
        pushOnTime(1)
        s.pull()
        s.pull()
        s.push(0, frame(0))
        assertEquals(0, s.buffered())
    }

    @Test
    fun jitteryArrivalsRaiseTheTargetWithinBounds() {
        for (seq in 0L until 60L) {
            t += if (seq % 2 == 0L) 2 else 40
            s.push(seq, frame(0))
        }
        assertTrue(s.targetMs in 40..80)
        for (seq in 60L until 200L) {
            t += 500
            s.push(seq, frame(0))
        }
        assertEquals(80, s.targetMs)
    }

    @Test
    fun runningFarAboveTargetSkipsAFrame() {
        for (seq in 0L until 10L) pushOnTime(seq)
        // 10 frames buffered against a 20 ms target: the first pull skips one to catch up.
        assertArrayEquals(frame(1), (s.pull() as Pull.Frame).data)
    }

    @Test
    fun seqNumbersWrapWithoutReordering() {
        pushOnTime(0xFFFFFFFFL)
        pushOnTime(0)
        assertArrayEquals(frame(0xFF), (s.pull() as Pull.Frame).data)
        assertArrayEquals(frame(0), (s.pull() as Pull.Frame).data)
    }

    @Test
    fun aPacketTooFarAheadDropsWhatItCannotWaitFor() {
        pushOnTime(0)
        pushOnTime(1)
        s.pull()
        // A burst after a long stall: 200 frames on, far past what the buffer holds.
        pushOnTime(201)
        assertEquals(1, s.buffered())
        // Everything before it is lost; the gap is concealed until it plays.
        assertTrue(s.pull() is Pull.Lost)
    }
}
