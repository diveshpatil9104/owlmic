package com.owlmic.media.audio

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.tanh

/**
 * The optional mic boost (section 17.1): a fixed gain with a soft limiter, so peaks round off instead of clipping.
 * Not an automatic gain: the level never pumps.
 */
object SoftLimiter {
    private const val KNEE = 0.8f

    fun gainFor(boostDb: Int): Float = 10f.pow(boostDb / 20f)

    /** Applies [gain] to the first [count] samples in place. */
    fun apply(pcm: ShortArray, count: Int, gain: Float) {
        if (gain == 1f) return
        for (i in 0 until count) {
            val x = pcm[i] / 32768f * gain
            val a = abs(x)
            val y = if (a <= KNEE) x else sign(x) * (KNEE + (1 - KNEE) * tanh((a - KNEE) / (1 - KNEE)))
            pcm[i] = (y * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
    }
}
