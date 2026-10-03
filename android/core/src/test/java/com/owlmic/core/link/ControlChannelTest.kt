package com.owlmic.core.link

import com.owlmic.core.proto.Crypto
import com.owlmic.core.proto.Ping
import com.owlmic.core.proto.State
import com.owlmic.core.proto.FeatureState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException

class ControlChannelTest {
    private val phoneToPc = ByteArray(32) { 3 }
    private val pcToPhone = ByteArray(32) { 4 }

    private fun writer(out: ByteArrayOutputStream, sealing: ControlSealing?) =
        ControlChannel(ByteArrayInputStream(ByteArray(0)), out, Closeable {}).also { it.sealing = sealing }

    private fun reader(bytes: ByteArray, sealing: ControlSealing?) =
        ControlChannel(ByteArrayInputStream(bytes), ByteArrayOutputStream(), Closeable {}).also { it.sealing = sealing }

    @Test
    fun sealedFramesOpenInOrderOnTheOtherSide() {
        val wire = ByteArrayOutputStream()
        val phone = writer(wire, ControlSealing(phoneToPc, pcToPhone))
        phone.send(Ping(1))
        phone.send(State(FeatureState.ON, FeatureState.OFF, FeatureState.PAUSED))
        // The length in the header counts the 16-byte tag.
        val bytes = wire.toByteArray()
        assertEquals(Ping(1).toPayload().size + 16, ((bytes[1].toInt() and 0xFF) shl 16) or ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF))

        val pc = reader(bytes, ControlSealing(pcToPhone, phoneToPc))
        assertEquals(Ping(1), (pc.read() as Incoming.Control).message)
        assertEquals(State(FeatureState.ON, FeatureState.OFF, FeatureState.PAUSED), (pc.read() as Incoming.Control).message)
    }

    @Test(expected = IOException::class)
    fun aTamperedFrameEndsTheChannel() {
        val wire = ByteArrayOutputStream()
        writer(wire, ControlSealing(phoneToPc, pcToPhone)).send(Ping(1))
        val bytes = wire.toByteArray().also { it[6] = (it[6] + 1).toByte() }
        reader(bytes, ControlSealing(pcToPhone, phoneToPc)).read()
    }

    @Test
    fun mediaOnASharedStreamIsToldApartByItsMarker() {
        val wire = ByteArrayOutputStream()
        val ch = writer(wire, null)
        ch.send(Ping(5))
        ch.sendMedia(byteArrayOf(1, 2, 3, 4), 3)
        ch.send(Ping(6))
        val r = reader(wire.toByteArray(), null)
        assertEquals(Ping(5), (r.read() as Incoming.Control).message)
        val media = r.read()
        assertTrue(media is Incoming.Media)
        assertArrayEquals(byteArrayOf(1, 2, 3), (media as Incoming.Media).packet)
        assertEquals(Ping(6), (r.read() as Incoming.Control).message)
    }

    @Test
    fun theControlNonceUsesStream0xFF() {
        assertEquals(0xFF, Crypto.CONTROL_STREAM)
    }
}
