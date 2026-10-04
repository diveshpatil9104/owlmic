package com.owlmic.media.audio

import android.os.Process
import com.owlmic.core.hub.Health
import com.owlmic.core.link.SpeakerSink
import com.owlmic.media.codec.OpusDecoder
import kotlin.concurrent.thread

/** What the PC said in STREAM_START for stream 3. */
data class SpeakerFormat(val codec: String, val channels: Int, val frameMs: Int)

/**
 * The PC's sound on the phone (section 17.3): packets go into the [Smoother] on the carrier's thread; a playback thread
 * pulls one frame at a time, decodes or conceals it, and writes it to Oboe, which paces the loop. Usage Media for full
 * quality, Voice Communication while the mic is on so Android's echo canceller hears what plays.
 */
class SpeakerPipeline(private val onHealth: (Health) -> Unit) : SpeakerSink {
    @Volatile private var smoother: Smoother? = null

    @Volatile private var format: SpeakerFormat? = null

    @Volatile private var voiceCommunication = false

    @Volatile private var wireless = true

    @Volatile private var reopen = false

    /** Bumped by every start and stop: a playback thread runs only while its own generation is current. */
    @Volatile private var generation = 0
    private var running = false
    private var worker: Thread? = null

    fun start(format: SpeakerFormat, voiceCommunication: Boolean, wireless: Boolean) {
        this.voiceCommunication = voiceCommunication
        this.wireless = wireless
        configure(format)
        if (running) return
        running = true
        val mine = ++generation
        val previous = worker
        worker = thread(name = "owlmic-speaker", isDaemon = true) {
            // The previous thread closes its output first; the caller (the hub thread) never waits for it.
            previous?.join(STOP_WAIT_MS)
            loop(mine)
        }
    }

    /** A new STREAM_START (another codec after a link switch). */
    fun configure(format: SpeakerFormat) {
        this.format = format
        smoother = if (wireless) Smoother(format.frameMs, 20, 80) else Smoother(format.frameMs, 10, 40)
        reopen = true
    }

    fun setVoiceCommunication(on: Boolean) {
        if (on != voiceCommunication) {
            voiceCommunication = on
            reopen = true
        }
    }

    fun setWireless(on: Boolean) {
        if (on != wireless) {
            wireless = on
            format?.let(::configure)
        }
    }

    override fun packet(seq: Long, timestampUs: Long, data: ByteArray) {
        smoother?.push(seq, data)
    }

    /** Returns at once; the playback thread closes its output within one frame. */
    fun stop() {
        running = false
        generation++
        smoother = null
    }

    private fun loop(mine: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        var handle = 0L
        var decoder: OpusDecoder? = null
        var current: SpeakerFormat? = null
        var pcm = ShortArray(0)
        var healthy: Boolean? = null
        var errors = 0
        while (generation == mine) {
            val f = format ?: break
            if (reopen || handle == 0L || f != current) {
                reopen = false
                if (handle != 0L) Oboe.close(handle)
                decoder?.close()
                current = f
                handle = Oboe.openOutput(
                    f.channels,
                    if (voiceCommunication) Oboe.USAGE_VOICE_COMMUNICATION else Oboe.USAGE_MEDIA,
                    if (voiceCommunication) Oboe.CONTENT_SPEECH else Oboe.CONTENT_MUSIC,
                )
                decoder = if (f.codec == "opus") OpusDecoder(f.channels) else null
                pcm = ShortArray(48 * f.frameMs * f.channels)
                if (handle == 0L) {
                    if (healthy != false) onHealth(Health.Degraded("speaker restarting")).also { healthy = false }
                    Thread.sleep(300)
                    continue
                }
            }
            val frameSamples = 48 * f.frameMs
            val s = smoother ?: break
            when (val p = s.pull()) {
                is Smoother.Pull.Frame -> if (decoder == null) {
                    pcmFromBytes(p.data, pcm)
                } else if (decoder.decode(p.data, pcm, frameSamples) > 0) {
                    errors = 0
                } else {
                    // A bad packet: conceal it rather than play the last frame again, and after three in a row start
                    // the decoder afresh (section 14.8, decoder errors).
                    conceal(decoder, null, pcm, frameSamples)
                    if (++errors >= MAX_DECODE_ERRORS) {
                        errors = 0
                        decoder.close()
                        decoder = OpusDecoder(f.channels)
                        if (healthy != false) onHealth(Health.Degraded("speaker decoder restarting")).also { healthy = false }
                    }
                }
                is Smoother.Pull.Lost -> if (decoder != null) conceal(decoder, p.next, pcm, frameSamples) else fadeOut(pcm)
                Smoother.Pull.Silence -> pcm.fill(0)
            }
            val written = Oboe.write(handle, pcm, frameSamples, WRITE_TIMEOUT_MS)
            if (written < 0) {
                reopen = true
                if (healthy != false) onHealth(Health.Degraded("speaker restarting")).also { healthy = false }
            } else if (healthy != true) {
                onHealth(Health.Ok)
                healthy = true
            }
        }
        if (handle != 0L) Oboe.close(handle)
        decoder?.close()
    }

    private fun conceal(decoder: OpusDecoder, next: ByteArray?, pcm: ShortArray, frameSamples: Int) {
        if (decoder.conceal(next, pcm, frameSamples) == 0) pcm.fill(0)
    }

    /** A lost PCM frame: the last one again, fading to silence, so the gap doesn't click. */
    private fun fadeOut(pcm: ShortArray) {
        val n = pcm.size
        for (i in 0 until n) pcm[i] = (pcm[i] * (n - i) / n).toShort()
    }

    private fun pcmFromBytes(data: ByteArray, out: ShortArray) {
        val n = minOf(out.size, data.size / 2)
        for (i in 0 until n) out[i] = ((data[2 * i].toInt() and 0xFF) or (data[2 * i + 1].toInt() shl 8)).toShort()
        if (n < out.size) out.fill(0, n, out.size)
    }

    private companion object {
        const val WRITE_TIMEOUT_MS = 100
        const val STOP_WAIT_MS = 1_000L
        const val MAX_DECODE_ERRORS = 3
    }
}
