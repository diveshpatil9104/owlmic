package com.owlmic.media.camera

import com.owlmic.core.hub.LinkKind

/** Size, frame rate and starting bitrate for the camera stream (section 17.2). */
data class VideoPlan(val longSide: Int, val shortSide: Int, val fps: Int, val bitrate: Int) {
    companion object {
        /**
         * [quality] and [fps] are the settings' values. "Auto" means 1080p on a cable and 720p on Wi-Fi. Starting
         * bitrates are the design's table; the 720p 60 and 1080p 24 rows, which it doesn't list, sit between their neighbours.
         */
        fun of(quality: String, fps: Int, link: LinkKind): VideoPlan {
            val cable = link.isCable
            val p1080 = quality == "1080p" || (quality == "auto" && cable)
            val f = fps.coerceIn(15, 60)
            val (wifi, wired) = when {
                p1080 && f >= 60 -> 10_000 to 24_000
                p1080 && f >= 30 -> 6_000 to 16_000
                p1080 -> 5_000 to 13_000
                f >= 60 -> 6_000 to 14_000
                f >= 30 -> 4_000 to 10_000
                else -> 3_000 to 8_000
            }
            val kbps = if (cable) wired else wifi
            return if (p1080) VideoPlan(1920, 1080, f, kbps * 1_000) else VideoPlan(1280, 720, f, kbps * 1_000)
        }
    }
}

/**
 * Bitrate adaptation (section 17.2): loss above 2% or a rising round trip lowers the bitrate by 20%, at most every 2 s;
 * 5 clean seconds raise it by 10%, never above where it started. Pure, so it is tested without a network.
 */
class BitrateController(val start: Int, private val now: () -> Long = System::currentTimeMillis) {
    var current = start
        private set

    private var lastChange = 0L
    private var cleanSince = now()
    private var baselineRtt = 0

    /** Returns the new bitrate when it changes, else null. */
    fun report(lossPct: Double, rttMs: Int): Int? {
        val t = now()
        if (baselineRtt == 0 || rttMs in 1 until baselineRtt) baselineRtt = rttMs
        val rising = baselineRtt > 0 && rttMs > baselineRtt * 3 / 2 && rttMs > baselineRtt + 30
        if (lossPct > 2.0 || rising) {
            cleanSince = t
            if (t - lastChange < DOWN_EVERY_MS) return null
            return change(maxOf(start / 4, current * 4 / 5), t)
        }
        if (t - cleanSince >= CLEAN_FOR_MS && current < start) {
            cleanSince = t
            return change(minOf(start, current * 11 / 10), t)
        }
        return null
    }

    /** A picture too big to send: halve the bitrate at once, never below a quarter of the start. */
    fun refused(): Int? = change(maxOf(start / 4, current / 2), now()).also { cleanSince = now() }

    private fun change(to: Int, t: Long): Int? {
        if (to == current) return null
        current = to
        lastChange = t
        return to
    }

    private companion object {
        const val DOWN_EVERY_MS = 2_000L
        const val CLEAN_FOR_MS = 5_000L
    }
}

/** Frame rate under heat (section 17.2): severe caps at 24, critical and worse at 15. Android's thermal status values. */
fun thermalFps(wanted: Int, thermalStatus: Int): Int = when {
    thermalStatus >= THERMAL_CRITICAL -> minOf(wanted, 15)
    thermalStatus >= THERMAL_SEVERE -> minOf(wanted, 24)
    else -> wanted
}

/** Quality under heat (section 19, "lower fps, then quality"): at critical, 1080p drops to 720p and its bitrate with it. */
fun thermalPlan(plan: VideoPlan, thermalStatus: Int): VideoPlan =
    if (thermalStatus >= THERMAL_CRITICAL && plan.shortSide > 720) plan.copy(longSide = 1280, shortSide = 720, bitrate = plan.bitrate * 2 / 3) else plan

private const val THERMAL_SEVERE = 3
private const val THERMAL_CRITICAL = 4

/**
 * Lets camera frames through to the encoder at [FrameGate.pass]'s fps on average: a frame goes when it is due, and the
 * next is due one period later, so a 30 fps camera feeding a 24 fps stream drops one frame in five. A frame a little
 * early (up to a quarter period) still goes, which absorbs the camera's timing jitter. GL thread only.
 */
class FrameGate {
    private var due = Long.MIN_VALUE

    fun pass(timestampNs: Long, fps: Int): Boolean {
        val period = 1_000_000_000L / fps.coerceAtLeast(1)
        // The first frame, or one after a gap (a pause, a stalled camera): start counting again from here.
        if (due == Long.MIN_VALUE || timestampNs - due > period) {
            due = timestampNs + period
            return true
        }
        if (timestampNs < due - period / 4) return false
        due += period
        return true
    }
}

/** How much of an upright w×h picture to keep, centred, to fill an output of aspect ow:oh. Returns (x scale, y scale), each ≤ 1. */
fun centerCrop(w: Int, h: Int, ow: Int, oh: Int): Pair<Float, Float> {
    val src = w.toFloat() / h
    val dst = ow.toFloat() / oh
    return if (src > dst) (dst / src) to 1f else 1f to (src / dst)
}
