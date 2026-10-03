package com.owlmic.core.link

import com.owlmic.core.proto.Crypto
import com.owlmic.core.proto.FragmentHeader
import com.owlmic.core.proto.Frames
import com.owlmic.core.proto.MediaHeader
import com.owlmic.core.proto.Stream

/**
 * Builds the media packets of one session direction (protocol section 6). Each stream has its own producer thread
 * and its own seq; on wireless links the seq is also the AES-GCM counter.
 */
class Packetizer(private val sealing: Crypto.Sealing?) {
    private val seq = LongArray(4)
    private var frame = 0

    fun audio(stream: Int, timestampUs: Long, data: ByteArray, offset: Int, length: Int): ByteArray =
        packet(stream, false, timestampUs, data, offset, length, null)

    /**
     * Splits one encoded picture into fragments of at most [Frames.MAX_FRAGMENT_PAYLOAD] bytes and hands each packet to
     * [emit]. False, and nothing sent, for a picture too big for 255 fragments.
     */
    fun video(timestampUs: Long, keyframe: Boolean, data: ByteArray, offset: Int, length: Int, emit: (ByteArray) -> Unit): Boolean {
        val count = fragmentCount(length)
        if (count > 255) return false
        val f = frame
        frame = (frame + 1) and 0xFFFF
        for (i in 0 until count) {
            val start = i * Frames.MAX_FRAGMENT_PAYLOAD
            val size = minOf(Frames.MAX_FRAGMENT_PAYLOAD, length - start)
            emit(packet(Stream.CAMERA, keyframe, timestampUs, data, offset + start, size, FragmentHeader(f, i, count)))
        }
        return true
    }

    /** Stream 0: session id and its MAC. Never sealed: the MAC already proves it, and the PC learns our address from it. */
    fun carrierHello(sessionId: ByteArray, mac: ByteArray): ByteArray =
        MediaHeader(Stream.CARRIER_HELLO, false, 0, 0).encode() + sessionId + mac

    private fun packet(stream: Int, keyframe: Boolean, timestampUs: Long, data: ByteArray, offset: Int, length: Int, fragment: FragmentHeader?): ByteArray {
        val s = seq[stream]
        seq[stream] = (s + 1) and 0xFFFFFFFFL
        val header = MediaHeader(stream, keyframe, s, timestampUs and 0xFFFFFFFFL).encode()
        val body = if (fragment != null) fragment.encode() + data.copyOfRange(offset, offset + length) else data.copyOfRange(offset, offset + length)
        return header + (sealing?.seal(stream, s, header, body) ?: body)
    }

    companion object {
        fun fragmentCount(length: Int) = maxOf(1, (length + Frames.MAX_FRAGMENT_PAYLOAD - 1) / Frames.MAX_FRAGMENT_PAYLOAD)
    }
}

/** Opens the PC's media packets: checks the seal on wireless links and drops replays (protocol section 6). */
class Unpacker(private val sealing: Crypto.Sealing?) {
    private val windows = Array(4) { Crypto.ReplayWindow() }

    /** The header and payload, or null for a packet that fails its seal, repeats, or is malformed. */
    fun open(packet: ByteArray, length: Int): Pair<MediaHeader, ByteArray>? {
        val header = MediaHeader.decode(packet, length) ?: return null
        if (header.stream !in 1..3) return null
        val body = packet.copyOfRange(MediaHeader.LEN, length)
        val payload = if (sealing != null) {
            sealing.open(header.stream, header.seq, packet.copyOf(MediaHeader.LEN), body) ?: return null
        } else {
            body
        }
        if (!windows[header.stream].accept(header.seq)) return null
        return header to payload
    }
}
