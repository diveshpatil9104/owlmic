package com.owlmic.media.audio

import java.util.TreeMap

/**
 * The speaker's jitter buffer (section 17.3): holds just enough audio to ride out the network's timing. The target is
 * the 95th percentile of arrival jitter plus 10 ms, kept within [minMs, maxMs]. It fills before it plays, conceals
 * losses (with the next packet for Opus FEC), and drops a frame now and then when it runs well above target.
 */
class Smoother(private val frameMs: Int, private val minMs: Int, private val maxMs: Int, private val now: () -> Long = System::currentTimeMillis) {
    sealed interface Pull {
        class Frame(val data: ByteArray) : Pull

        /** The frame for this slot is missing; [next] is the following packet, for FEC, when it has arrived. */
        class Lost(val next: ByteArray?) : Pull

        data object Silence : Pull
    }

    private val packets = TreeMap<Long, ByteArray>()
    private var highest = -1L
    private var next = -1L
    private var playing = false
    private var misses = 0
    private var lastArrival = 0L
    private val jitter = ArrayDeque<Int>()

    var targetMs = minMs
        private set

    @Synchronized
    fun push(seq32: Long, data: ByteArray) {
        val seq = unwrap(seq32)
        val t = now()
        if (lastArrival > 0) {
            jitter.addLast(kotlin.math.abs((t - lastArrival).toInt() - frameMs))
            if (jitter.size > WINDOW) jitter.removeFirst()
            val sorted = jitter.sorted()
            val p95 = sorted[(sorted.size * 95 / 100).coerceAtMost(sorted.lastIndex)]
            targetMs = (p95 + 10).coerceIn(minMs, maxMs)
        }
        lastArrival = t
        if (playing && seq < next) return
        packets[seq] = data
        while (packets.size > MAX_PACKETS) packets.pollFirstEntry()
    }

    @Synchronized
    fun pull(): Pull {
        if (!playing) {
            if (packets.size * frameMs < targetMs) return Pull.Silence
            playing = true
            misses = 0
            next = packets.firstKey()
        }
        // Well above target: skip a frame to bring the delay back down.
        if (packets.size * frameMs > targetMs + CATCH_UP_FRAMES * frameMs) packets.remove(next)?.let { next++ }
        packets.remove(next)?.let {
            next++
            misses = 0
            return Pull.Frame(it)
        }
        if (packets.isEmpty()) {
            if (++misses > MAX_CONCEALED) {
                playing = false
                return Pull.Silence
            }
            next++
            return Pull.Lost(null)
        }
        if (packets.firstKey() < next) packets.headMap(next).clear()
        val following = packets[next + 1]
        next++
        return Pull.Lost(following)
    }

    @Synchronized
    fun buffered() = packets.size

    /** 32-bit seq numbers grow without bound here, so wrapping never reorders anything. */
    private fun unwrap(seq32: Long): Long {
        if (highest < 0) {
            highest = seq32
            return seq32
        }
        val base = highest and 0xFFFFFFFFL.inv()
        var seq = base or seq32
        if (seq - highest > HALF) seq -= 1L shl 32 else if (highest - seq > HALF) seq += 1L shl 32
        if (seq > highest) highest = seq
        return seq
    }

    private companion object {
        const val WINDOW = 50
        const val MAX_PACKETS = 100
        const val MAX_CONCEALED = 3
        const val CATCH_UP_FRAMES = 3
        const val HALF = 1L shl 31
    }
}
