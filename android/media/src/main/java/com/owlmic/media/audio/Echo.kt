package com.owlmic.media.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import kotlin.math.log10

/*
 * Voice isolation (the mic.isolation setting): with the Speaker on, what the phone plays must not come back through its
 * mic, or the other side of the call hears itself twice. Android's echo canceller does the work ([CallMode] gives it
 * what it needs on most phones); [EchoSuppressor] is the safety net for phones where it is weak or missing.
 */

/** A frame's level in dBFS. */
internal fun levelDb(pcm: ShortArray, count: Int = pcm.size): Float {
    val r = rms(pcm, count)
    return if (r <= 0.0) SILENT_DB else (20 * log10(r / 32768)).toFloat().coerceAtLeast(SILENT_DB)
}

private const val SILENT_DB = -100f

/**
 * What the Speaker played lately: each frame's level as it went to the output, with when. Written by the speaker's
 * thread, read by the mic's.
 */
class FarEnd(private val now: () -> Long = SystemClock::elapsedRealtime) {
    private val levels = FloatArray(SLOTS) { SILENT_DB }
    private val times = LongArray(SLOTS) { Long.MIN_VALUE / 2 }
    private var at = 0

    @Synchronized
    fun played(levelDb: Float) {
        levels[at] = levelDb
        times[at] = now()
        at = (at + 1) % SLOTS
    }

    /** The loudest frame played in the last [windowMs]: the echo of anything in it can still be reaching the mic. */
    @Synchronized
    fun loudest(windowMs: Long = WINDOW_MS): Float {
        val t = now()
        var max = SILENT_DB
        for (i in 0 until SLOTS) if (t - times[i] <= windowMs && levels[i] > max) max = levels[i]
        return max
    }

    private companion object {
        /** 640 ms of 10 ms frames, more than the window. */
        const val SLOTS = 64

        /** From the output to the mic: the output's buffer, the air and the input's buffer. */
        const val WINDOW_MS = 300L
    }
}

/**
 * The safety net under Android's echo canceller. Per 10 ms mic frame while the Speaker plays, it learns how loud the
 * Speaker comes back into the mic: the quietest the mic gets, relative to what plays, over the last 3 s (a voice always
 * pauses within that). While the mic hears no more than that echo, it turns the mic down; a voice clearly louder than
 * the echo passes, so both sides can still talk at once. Where the canceller works, the echo it learns is faint and
 * nothing is turned down.
 */
class EchoSuppressor {
    /** The minimum of (mic - played) per block of 20 frames, oldest first. */
    private val blocks = arrayOfNulls<Float>(BLOCKS)
    private var at = 0
    private var current: Float? = null
    private var inBlock = 0
    private var gain = 1f

    /** How much of what plays comes back into the mic, in dB; none learned yet. */
    val couplingDb: Float?
        get() = (blocks.filterNotNull() + listOfNotNull(current)).minOrNull()

    /** Turns [frame] down where it is only echo. [farDb] is the loudest the Speaker played lately. */
    fun process(frame: ShortArray, count: Int, farDb: Float) {
        val target = target(levelDb(frame, count), farDb)
        val start = gain
        for (i in 0 until count) {
            // Down within 2.5 ms, so the echo's start goes too; up across the frame, so a voice comes in smoothly.
            val g = if (target < start) target + (start - target) * maxOf(0f, 1f - i / 120f) else start + (target - start) * (i + 1) / count
            frame[i] = (frame[i] * g).toInt().toShort()
        }
        gain = target
    }

    /** The gain for a mic frame at [micDb] while the Speaker played at most [farDb] lately. */
    fun target(micDb: Float, farDb: Float): Float {
        if (farDb < FAR_ACTIVE_DB) return 1f
        learn(micDb - farDb)
        // Until something is learned, the echo is taken to be as loud as what plays.
        val echoDb = farDb + (couplingDb ?: 0f)
        return if (micDb > echoDb + DOUBLE_TALK_DB) 1f else SUPPRESSED
    }

    fun reset() {
        blocks.fill(null)
        current = null
        inBlock = 0
        at = 0
        gain = 1f
    }

    private fun learn(observed: Float) {
        val o = observed.coerceIn(MIN_COUPLING_DB, MAX_COUPLING_DB)
        current = current?.let { minOf(it, o) } ?: o
        if (++inBlock == BLOCK) {
            blocks[at] = current
            at = (at + 1) % BLOCKS
            current = null
            inBlock = 0
        }
    }

    private companion object {
        /** Quieter than this, the Speaker leaves no echo worth taking out. */
        const val FAR_ACTIVE_DB = -55f

        /** A voice this much louder than the echo is someone talking on this side. */
        const val DOUBLE_TALK_DB = 6f

        /** -24 dB: the echo gone under the room's own sound. */
        const val SUPPRESSED = 0.063f

        const val MIN_COUPLING_DB = -60f
        const val MAX_COUPLING_DB = 10f
        const val BLOCK = 20
        const val BLOCKS = 15
    }
}

/**
 * Android's communication mode, which its echo canceller needs on most phones: on while the mic and the Speaker both
 * run with voice isolation, and the sound comes out of the phone itself (headphones leave no echo to cancel). In that
 * mode Android sends calls to the earpiece, so the built-in speaker is chosen explicitly; the phone's own mode comes
 * back when it ends. Calls come from the media hub's thread.
 */
class CallMode(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var previous: Int? = null

    fun update(micIsolated: Boolean, speakerIsolated: Boolean) = set(wanted(micIsolated, speakerIsolated, headset()))

    fun release() = set(false)

    private fun set(on: Boolean) {
        if (on == (previous != null)) return
        if (on) {
            previous = audio.mode
            runCatching { audio.mode = AudioManager.MODE_IN_COMMUNICATION }
            route(toSpeaker = true)
        } else {
            route(toSpeaker = false)
            runCatching { audio.mode = previous ?: AudioManager.MODE_NORMAL }
            previous = null
        }
    }

    private fun route(toSpeaker: Boolean) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val speaker = audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (toSpeaker && speaker != null) audio.setCommunicationDevice(speaker) else audio.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audio.isSpeakerphoneOn = toSpeaker
            }
        }
    }

    private fun headset() = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in HEADSETS }

    companion object {
        /** On only while it matters: both directions on voice isolation, out of the phone's own speaker. */
        fun wanted(micIsolated: Boolean, speakerIsolated: Boolean, headset: Boolean) = micIsolated && speakerIsolated && !headset

        /** AudioDeviceInfo.TYPE_HEARING_AID (API 28) and TYPE_BLE_HEADSET (API 31), by value for older phones. */
        private const val TYPE_HEARING_AID = 23
        private const val TYPE_BLE_HEADSET = 26

        private val HEADSETS = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            TYPE_HEARING_AID,
            TYPE_BLE_HEADSET,
        )
    }
}
