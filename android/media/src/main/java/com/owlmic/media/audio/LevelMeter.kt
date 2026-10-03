package com.owlmic.media.audio

import kotlin.math.log10
import kotlin.math.sqrt

/** How loud the mic is, 0 to 1, for the level bar in the mic tile: quick to rise, slower to fall. */
class LevelMeter {
    var level = 0f
        private set

    /** Takes one frame of samples and returns the smoothed level. */
    fun add(pcm: ShortArray, count: Int = pcm.size): Float {
        val target = levelFor(rms(pcm, count))
        level = if (target > level) level + (target - level) * RISE else level * (1 - FALL) + target * FALL
        return level
    }

    private companion object {
        const val RISE = 0.6f
        const val FALL = 0.2f
    }
}

/** Maps a frame's loudness onto the bar: -60 dBFS and below is empty, -12 dBFS and above is full. */
internal fun levelFor(rms: Double): Float {
    if (rms <= 0.0) return 0f
    val db = 20 * log10(rms / 32768)
    return ((db + 60) / 48).toFloat().coerceIn(0f, 1f)
}

internal fun rms(pcm: ShortArray, count: Int = pcm.size): Double {
    if (count == 0) return 0.0
    var sum = 0.0
    for (i in 0 until count) {
        val s = pcm[i].toDouble()
        sum += s * s
    }
    return sqrt(sum / count)
}
