package com.owlmic.media.audio

import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.os.SystemClock
import com.owlmic.core.hub.Health
import com.owlmic.core.hub.LinkKind
import com.owlmic.core.link.MediaOut
import com.owlmic.core.proto.Stream
import com.owlmic.media.codec.OpusEncoder
import kotlin.concurrent.thread

/** How the mic is carried (section 17.1): raw PCM on a cable, Opus on wireless links. */
enum class MicCodec(val wire: String, val frameMs: Int, val bitrate: Int) {
    PCM("pcm", 10, 0),
    OPUS_WIFI("opus", 10, 48_000),
    OPUS_BLUETOOTH("opus", 20, 24_000),
    ;

    val frameSamples get() = 48 * frameMs

    companion object {
        fun forLink(link: LinkKind) = when (link) {
            LinkKind.USB_DEBUGGING, LinkKind.USB_TETHERING -> PCM
            LinkKind.WIFI -> OPUS_WIFI
            LinkKind.BLUETOOTH -> OPUS_BLUETOOTH
        }
    }
}

/**
 * [voiceCommunication]: the Speaker is on, so Android's echo canceller must run. [noiseReduction]: the phone's own
 * noise suppressor. Automatic gain control is always off.
 */
data class MicConfig(val codec: MicCodec, val voiceCommunication: Boolean, val noiseReduction: Boolean, val boostDb: Int)

/**
 * The phone's mic (section 17.1): Oboe capture at 48 kHz mono in 10 ms frames on its own urgent-audio thread, the
 * boost, the level, and the codec for the link. A watchdog reopens the stream when frames stop for 500 ms or the device
 * goes away, so the mic never stays silent (section 14.8, step 1).
 */
class MicPipeline(
    private val out: MediaOut,
    private val onLevel: (Float) -> Unit,
    private val onHealth: (Health) -> Unit,
) {
    @Volatile private var config: MicConfig? = null

    /** Bumped by every start and stop: a capture thread runs only while its own generation is current. */
    @Volatile private var generation = 0

    @Volatile private var restartRequested = false

    /** Paused from the PC: capture continues, nothing is sent, so resuming is instant (section 17.4). */
    @Volatile var paused = false

    /** The loss the PC last reported for the mic, for Opus FEC. */
    @Volatile var packetLoss = 0

    private var running = false
    private var worker: Thread? = null

    fun start(config: MicConfig) {
        this.config = config
        if (running) return
        running = true
        val mine = ++generation
        val previous = worker
        worker = thread(name = "owlmic-capture", isDaemon = true) {
            // Android gives the mic to one stream at a time: the previous thread closes its capture first. The caller
            // (the hub thread) never waits for it.
            previous?.join(STOP_WAIT_MS)
            loop(mine)
        }
    }

    /** Picked up at the next frame: a new codec on a link switch, or new processing. */
    fun update(config: MicConfig) {
        this.config = config
    }

    /** Reopens the capture stream (a stall reported by the PC). */
    fun restart() {
        restartRequested = true
    }

    /** Returns at once; the capture thread closes the mic within one read (100 ms). */
    fun stop() {
        running = false
        generation++
    }

    private class Capture(val handle: Long, val effects: List<AudioEffect>) {
        fun close() {
            effects.forEach { runCatching { it.release() } }
            Oboe.close(handle)
        }
    }

    private fun open(c: MicConfig): Capture? {
        val handle = Oboe.openInput(if (c.voiceCommunication) Oboe.PRESET_VOICE_COMMUNICATION else Oboe.PRESET_VOICE_RECOGNITION)
        if (handle == 0L) return null
        val session = Oboe.sessionId(handle)
        val effects = mutableListOf<AudioEffect>()
        if (session > 0) {
            // Automatic gain made voices pump in the first version; it stays off whatever the preset turns on.
            if (AutomaticGainControl.isAvailable()) runCatching { AutomaticGainControl.create(session)?.let { it.enabled = false; effects += it } }
            if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(session)?.let { it.enabled = c.noiseReduction; effects += it } }
        }
        return Capture(handle, effects)
    }

    private fun loop(mine: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        var cfg = config ?: return
        var capture = open(cfg)
        var encoder = encoderFor(cfg.codec)
        val frame = ShortArray(FRAME)
        val chunk = ShortArray(FRAME)
        val pair = ShortArray(FRAME * 2)
        val pcmBytes = ByteArray(FRAME * 2)
        var filled = 0
        var paired = 0
        var lastData = SystemClock.elapsedRealtime()
        var healthy: Boolean? = null
        val meter = LevelMeter()
        var frames = 0
        while (generation == mine) {
            val want = config ?: break
            if (restartRequested || want.voiceCommunication != cfg.voiceCommunication || want.noiseReduction != cfg.noiseReduction) {
                restartRequested = false
                capture?.close()
                capture = open(want)
                filled = 0
            }
            if (want.codec != cfg.codec) {
                encoder?.close()
                encoder = encoderFor(want.codec)
                paired = 0
            }
            cfg = want
            val c = capture
            if (c == null) {
                if (healthy != false) onHealth(Health.Degraded("mic restarting")).also { healthy = false }
                Thread.sleep(REOPEN_MS)
                capture = open(cfg)
                continue
            }
            val n = Oboe.read(c.handle, chunk, FRAME - filled, READ_TIMEOUT_MS)
            val now = SystemClock.elapsedRealtime()
            if (n < 0 || (n == 0 && now - lastData > WATCHDOG_MS)) {
                if (healthy != false) onHealth(Health.Degraded("mic restarting")).also { healthy = false }
                c.close()
                capture = open(cfg)
                filled = 0
                lastData = now
                continue
            }
            if (n == 0) continue
            lastData = now
            System.arraycopy(chunk, 0, frame, filled, n)
            filled += n
            if (filled < FRAME) continue
            filled = 0
            if (healthy != true) onHealth(Health.Ok).also { healthy = true }

            SoftLimiter.apply(frame, FRAME, SoftLimiter.gainFor(cfg.boostDb))
            if (frames++ % LEVEL_EVERY == 0) onLevel(meter.add(frame))
            if (paused) continue
            val ts = SystemClock.elapsedRealtimeNanos() / 1_000
            when (cfg.codec) {
                MicCodec.PCM -> {
                    for (i in 0 until FRAME) {
                        pcmBytes[2 * i] = frame[i].toInt().toByte()
                        pcmBytes[2 * i + 1] = (frame[i].toInt() shr 8).toByte()
                    }
                    out.audio(Stream.MIC, ts, pcmBytes, 0, pcmBytes.size)
                }
                MicCodec.OPUS_WIFI -> encoder?.let { e ->
                    e.setPacketLoss(packetLoss)
                    val len = e.encode(frame, FRAME)
                    if (len > 0) out.audio(Stream.MIC, ts, e.packet, 0, len)
                }
                MicCodec.OPUS_BLUETOOTH -> encoder?.let { e ->
                    System.arraycopy(frame, 0, pair, paired * FRAME, FRAME)
                    if (++paired == 2) {
                        paired = 0
                        val len = e.encode(pair, FRAME * 2)
                        if (len > 0) out.audio(Stream.MIC, ts, e.packet, 0, len)
                    }
                }
            }
        }
        capture?.close()
        encoder?.close()
        onLevel(0f)
    }

    private fun encoderFor(codec: MicCodec): OpusEncoder? =
        if (codec == MicCodec.PCM) null else OpusEncoder(1, OpusEncoder.Application.VOIP, codec.bitrate, fec = true)

    private companion object {
        /** 10 ms at 48 kHz. */
        const val FRAME = 480
        const val READ_TIMEOUT_MS = 100
        const val WATCHDOG_MS = 500L
        const val REOPEN_MS = 300L
        const val STOP_WAIT_MS = 1_000L

        /** The level bar updates every 50 ms. */
        const val LEVEL_EVERY = 5
    }
}
