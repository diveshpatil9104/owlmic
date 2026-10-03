package com.owlmic.core.link

import com.owlmic.core.proto.ControlHeader
import com.owlmic.core.proto.Crypto
import com.owlmic.core.proto.Frames
import com.owlmic.core.proto.Message
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** One direction pair of control encryption, used on wireless links from WELCOME on (protocol section 5). */
class ControlSealing(txKey: ByteArray, rxKey: ByteArray) {
    private val tx = Crypto.Sealing(txKey)
    private val rx = Crypto.Sealing(rxKey)
    private var txCounter = 0L
    private var rxCounter = 0L

    fun seal(header: ByteArray, payload: ByteArray) = tx.seal(Crypto.CONTROL_STREAM, txCounter++, header, payload)

    fun open(header: ByteArray, sealed: ByteArray) = rx.open(Crypto.CONTROL_STREAM, rxCounter++, header, sealed)
}

/** What arrived on a control stream: a control frame, or (on Bluetooth only) a wrapped media packet. */
sealed interface Incoming {
    /** [message] is null for a type this version doesn't know. [payload] is the exact plaintext bytes received. */
    class Control(val kind: Int, val payload: ByteArray, val message: Message?) : Incoming

    class Media(val packet: ByteArray) : Incoming
}

/**
 * Control frames over one stream: TCP, the adb tunnel or RFCOMM. Writes from any thread are serialised; reads come
 * from the session's reader thread only.
 */
class ControlChannel(input: InputStream, private val output: OutputStream, private val transport: Closeable) : Closeable {
    private val input = DataInputStream(input)
    private val writeLock = Any()

    @Volatile var sealing: ControlSealing? = null

    /** Sends [message] and returns the exact payload bytes, which the handshake transcript needs. */
    fun send(message: Message): ByteArray {
        val payload = message.toPayload()
        synchronized(writeLock) {
            val seal = sealing
            val length = payload.size + if (seal != null) TAG else 0
            val header = ControlHeader(message.kind, length).encode()
            output.write(header)
            output.write(seal?.seal(header, payload) ?: payload)
            output.flush()
        }
        return payload
    }

    /** A media packet on a shared stream (Bluetooth): the media marker, a 2-byte length, the packet. */
    fun sendMedia(packet: ByteArray, length: Int) {
        synchronized(writeLock) {
            output.write(byteArrayOf(Frames.MEDIA_MARKER.toByte(), (length shr 8).toByte(), length.toByte()))
            output.write(packet, 0, length)
            output.flush()
        }
    }

    fun writeChannelByte(channel: Int) {
        synchronized(writeLock) {
            output.write(channel)
            output.flush()
        }
    }

    /** Blocks for the next frame. Throws when the stream ends, a frame is malformed or a seal doesn't open. */
    fun read(): Incoming {
        val first = input.readUnsignedByte()
        if (first == Frames.MEDIA_MARKER) {
            val length = input.readUnsignedShort()
            return Incoming.Media(ByteArray(length).also { input.readFully(it) })
        }
        val header = ByteArray(ControlHeader.LEN)
        header[0] = first.toByte()
        input.readFully(header, 1, 3)
        val h = ControlHeader.decode(header) ?: throw IOException("bad control header")
        val body = ByteArray(h.length).also { input.readFully(it) }
        val payload = sealing?.let { it.open(header, body) ?: throw IOException("control frame failed to open") } ?: body
        val message = try {
            Message.decode(h.kind, payload)
        } catch (e: Exception) {
            throw IOException("bad ${h.kind} payload", e)
        }
        return Incoming.Control(h.kind, payload, message)
    }

    override fun close() {
        runCatching { transport.close() }
    }

    private companion object {
        const val TAG = 16
    }
}
