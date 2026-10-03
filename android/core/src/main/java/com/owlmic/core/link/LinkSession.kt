package com.owlmic.core.link

import com.owlmic.core.hub.LinkKind
import com.owlmic.core.proto.Message
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * One established link to the PC: its control channel, media path and health. Control writes go through a queue and
 * a writer thread, so the Link Hub never blocks on a socket.
 */
class LinkSession(
    val candidate: Candidate,
    val link: LinkKind,
    val channel: ControlChannel,
    val welcome: Handshake.Result.Welcomed,
    path: MediaPath,
    val hotspot: Boolean,
) {
    @Volatile var path: MediaPath = path

    val monitor = Monitor(link.isWireless)
    val sessionId: ByteArray get() = welcome.sessionId

    @Volatile var closed = false
        private set

    private val outbox = ArrayBlockingQueue<Message>(32)

    /** Starts the reader and writer threads. [onControl] and [onClosed] run on the reader thread. */
    fun start(router: MediaRouter, onControl: (Message) -> Unit, onClosed: () -> Unit) {
        thread(name = "owlmic-session-in", isDaemon = true) {
            try {
                while (!closed) {
                    when (val incoming = channel.read()) {
                        is Incoming.Control -> incoming.message?.let(onControl)
                        is Incoming.Media -> router.received(path, incoming.packet, incoming.packet.size)
                    }
                }
            } catch (_: IOException) {
                // The link ended; the Link Hub decides what comes next.
            }
            close()
            onClosed()
        }
        thread(name = "owlmic-session-out", isDaemon = true) {
            while (!closed) {
                val message = outbox.poll(500, TimeUnit.MILLISECONDS) ?: continue
                try {
                    channel.send(message)
                } catch (_: IOException) {
                    close()
                }
            }
        }
    }

    /** Queues [message]. When the queue is full the link is stuck; the oldest message gives way. */
    fun send(message: Message) {
        while (!outbox.offer(message)) outbox.poll()
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { path.carrier.close() }
        channel.close()
    }
}
