package com.owlmic.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.owlmic.Owlmic
import com.owlmic.core.design.Tokens
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private const val BARS = 25

/**
 * The mic's sound wave from the prototype: 25 rounded bars that move with the real mic level. Big in the middle of the
 * preview while the camera is off; [docked] small in a strip at the bottom while the camera shows.
 */
@Composable
fun Waveform(docked: Boolean, modifier: Modifier = Modifier) {
    val level by Owlmic.level.collectAsState()
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        // The prototype's 0.055 a frame at 60 fps.
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) time.floatValue += (now - last) / 1e9f * 3.3f
                last = now
            }
        }
    }
    val barW = if (docked) 3.5.dp else 5.dp
    val gapW = if (docked) 4.dp else 6.dp
    val boxH = if (docked) 24.dp else 80.dp
    val minH = if (docked) 3.5.dp else 5.dp
    val maxH = if (docked) 19.dp else 74.dp
    val wave = Modifier.width(barW * BARS + gapW * (BARS - 1)).height(boxH)
    val bars: @Composable () -> Unit = {
        Canvas(if (docked) wave else modifier.then(wave)) {
            val t = time.floatValue
            val w = barW.toPx()
            val step = w + gapW.toPx()
            val lo = minH.toPx()
            val hi = maxH.toPx()
            val boost = max(0f, level.coerceAtLeast(0.06f) * 2.2f)
            val center = (BARS - 1) / 2f
            for (i in 0 until BARS) {
                val envelope = max(0.2f, cos(abs(i - center) / center * PI.toFloat() * 0.46f))
                val motion = (sin(t * 5.2f + i * 0.52f) + sin(t * 9.1f + i * 1.1f) * 0.5f + cos(t * 14f + i * 0.28f) * 0.25f) / 1.75f
                val fraction = max(0.08f, boost * 0.95f + (motion + 1) * 0.14f * (0.2f + boost)) * envelope
                val h = lo + (hi - lo) * min(1f, fraction)
                drawRoundRect(
                    Theme.ink,
                    topLeft = Offset(i * step, (size.height - h) / 2),
                    size = Size(w, h),
                    cornerRadius = CornerRadius(w / 2, w / 2),
                )
            }
        }
    }
    if (docked) StripBox(modifier) { bars() } else bars()
}

/**
 * The preview's dot grid: a dot every 18 dp from the centre, fading out towards the edges like the prototype's
 * radial mask (full to 15%, half at 55%, gone at 85% of the way to a corner).
 */
@Composable
fun DotGrid(modifier: Modifier) {
    Canvas(
        modifier.drawWithCache {
            val step = Tokens.Phone.DOT_SPACING_DP.dp.toPx()
            val r = 1.dp.toPx()
            val w = size.width
            val h = size.height
            val cx = w / 2
            val cy = h / 2
            val rx = cx * sqrt(2f)
            val ry = cy * sqrt(2f)
            val dots = buildList {
                var x = cx - (cx / step).toInt() * step
                while (x <= w) {
                    var y = cy - (cy / step).toInt() * step
                    while (y <= h) {
                        val dx = (x - cx) / rx
                        val dy = (y - cy) / ry
                        val fade = fade(sqrt(dx * dx + dy * dy))
                        if (fade > 0f) add(Triple(x, y, fade))
                        y += step
                    }
                    x += step
                }
            }
            onDrawBehind {
                dots.forEach { (x, y, fade) -> drawCircle(Color.White.copy(alpha = Tokens.Phone.DOT_ALPHA * fade), r, Offset(x, y)) }
            }
        },
    ) {}
}

private fun fade(d: Float): Float = when {
    d <= 0.15f -> 1f
    d <= 0.55f -> 1f - 0.5f * (d - 0.15f) / 0.4f
    d <= 0.85f -> 0.5f * (1f - (d - 0.55f) / 0.3f)
    else -> 0f
}
