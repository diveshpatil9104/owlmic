package com.owlmic.core.proto

import com.owlmic.core.proto.Vectors.hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FramesTest {
    private val vectors = Vectors.load("frames.json")

    @Test
    fun constantsMatchTheVectors() {
        assertEquals(vectors.getJSONObject("channelBytes").getInt("control"), Frames.CHANNEL_CONTROL)
        assertEquals(vectors.getJSONObject("channelBytes").getInt("media"), Frames.CHANNEL_MEDIA)
        assertEquals(vectors.getInt("maxControlPayload"), Frames.MAX_CONTROL_PAYLOAD)
        assertEquals(vectors.getInt("maxFragmentPayload"), Frames.MAX_FRAGMENT_PAYLOAD)
    }

    @Test
    fun controlHeadersMatchTheVectors() {
        val headers = vectors.getJSONArray("controlHeaders")
        for (i in 0 until headers.length()) {
            val v = headers.getJSONObject(i)
            val h = ControlHeader(v.getInt("type"), v.getInt("length"))
            assertArrayEquals(hex(v.getString("hex")), h.encode())
            assertEquals(h, ControlHeader.decode(hex(v.getString("hex"))))
        }
        val rejected = vectors.getJSONArray("controlHeadersRejected")
        for (i in 0 until rejected.length()) {
            val v = rejected.getJSONObject(i)
            assertNull(v.getString("name"), ControlHeader.decode(hex(v.getString("hex"))))
        }
    }

    @Test
    fun mediaAndFragmentHeadersMatchTheVectors() {
        val media = vectors.getJSONArray("mediaHeaders")
        for (i in 0 until media.length()) {
            val v = media.getJSONObject(i)
            val h = MediaHeader(v.getInt("stream"), v.getBoolean("keyframe"), v.getLong("seq"), v.getLong("timestampUs"))
            assertArrayEquals(hex(v.getString("hex")), h.encode())
            assertEquals(h, MediaHeader.decode(hex(v.getString("hex"))))
        }
        val fragments = vectors.getJSONArray("fragmentHeaders")
        for (i in 0 until fragments.length()) {
            val v = fragments.getJSONObject(i)
            val h = FragmentHeader(v.getInt("frame"), v.getInt("index"), v.getInt("count"))
            assertArrayEquals(hex(v.getString("hex")), h.encode())
            assertEquals(h, FragmentHeader.decode(hex(v.getString("hex"))))
        }
        assertNull(FragmentHeader.decode(byteArrayOf(0, 0, 2, 2)))
    }

    @Test
    fun streamWrappingMatchesTheVectors() {
        val wraps = vectors.getJSONArray("streamWrap")
        for (i in 0 until wraps.length()) {
            val v = wraps.getJSONObject(i)
            assertArrayEquals(hex(v.getString("hex")), Frames.wrapForStream(hex(v.getString("packetHex"))))
        }
    }

    @Test
    fun everyMessageInTheVectorsHasAKnownType() {
        val known = listOf(
            Kind.HELLO, Kind.HELLO_ACK, Kind.PROOF, Kind.PENDING, Kind.WELCOME, Kind.REJECT, Kind.PING, Kind.PONG,
            Kind.REPORT, Kind.STATE, Kind.SETTINGS, Kind.STREAM_START, Kind.STREAM_STOP, Kind.KEYFRAME_REQUEST,
            Kind.RESTART_STREAM, Kind.SWITCH, Kind.BYE,
        )
        val messages = Vectors.load("messages.json").getJSONArray("messages")
        assertEquals(known, (0 until messages.length()).map { messages.getJSONObject(it).getInt("type") })
    }
}
