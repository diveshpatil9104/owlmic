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
    thermalStatus >= 4 -> minOf(wanted, 15)
    thermalStatus >= 3 -> minOf(wanted, 24)
    else -> wanted
}

/** How much of an upright w×h picture to keep, centred, to fill an output of aspect ow:oh. Returns (x scale, y scale), each ≤ 1. */
fun centerCrop(w: Int, h: Int, ow: Int, oh: Int): Pair<Float, Float> {
    val src = w.toFloat() / h
    val dst = ow.toFloat() / oh
    return if (src > dst) (dst / src) to 1f else 1f to (src / dst)
}
