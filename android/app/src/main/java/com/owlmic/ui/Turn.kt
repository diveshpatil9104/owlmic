package com.owlmic.ui

import android.view.OrientationEventListener
import android.view.Surface
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import com.owlmic.media.camera.surfaceRotationFor

/*
 * The screen stays upright (the activity is locked to portrait). When the phone is held sideways, the grid turns with it
 * and everything drawn on it turns back a quarter, so it reads upright, as in the prototype (landing/proto-templates).
 */

/** Upright. */
const val TURN_NONE = 0

/** Sideways with the phone's top to the left: glyphs turn a quarter clockwise. */
const val TURN_LEFT = 90

/** Sideways with the phone's top to the right: glyphs turn a quarter anticlockwise. */
const val TURN_RIGHT = 270

/**
 * The next quarter turn from the orientation sensor's [degrees], with the camera's own thresholds: a change needs to be
 * 20° past the halfway point, so a phone held near a diagonal doesn't flicker. Upside down, and lying flat, keep [current].
 */
fun nextTurn(degrees: Int, current: Int): Int {
    if (degrees == OrientationEventListener.ORIENTATION_UNKNOWN) return current
    val surface = when (current) {
        TURN_LEFT -> Surface.ROTATION_90
        TURN_RIGHT -> Surface.ROTATION_270
        else -> Surface.ROTATION_0
    }
    return when (surfaceRotationFor(degrees, surface)) {
        Surface.ROTATION_0 -> TURN_NONE
        Surface.ROTATION_90 -> TURN_LEFT
        Surface.ROTATION_270 -> TURN_RIGHT
        else -> current
    }
}

/** How far glyphs turn, clockwise, to read upright. */
fun uprightAngle(turn: Int): Float = when (turn) {
    TURN_LEFT -> 90f
    TURN_RIGHT -> -90f
    else -> 0f
}

/** The current [turn] and the [angle] glyphs are drawn at, which follows it over 250 ms. */
class Upright(val turn: Int, val angle: State<Float>) {
    val sideways get() = turn != TURN_NONE
}

/**
 * Draws this element turned with [up]. With [swap], text and other wide content is laid out for the reader's width
 * while sideways (the tile's height on screen), not the screen's.
 */
fun Modifier.upright(up: Upright, swap: Boolean = false): Modifier {
    val laidOut = if (swap && up.sideways) {
        layout { measurable, constraints ->
            val p = measurable.measure(Constraints(constraints.minHeight, constraints.maxHeight, constraints.minWidth, constraints.maxWidth))
            layout(p.height, p.width) { p.place((p.height - p.width) / 2, (p.width - p.height) / 2) }
        }
    } else {
        this
    }
    return laidOut.graphicsLayer { rotationZ = up.angle.value }
}

/** A corner or edge as the reader sees it. */
enum class Spot { TOP_START, TOP_END, BOTTOM }

/** Where [spot] is on the upright screen for [turn]. */
fun spotOf(spot: Spot, turn: Int): Alignment = when (turn) {
    // The phone's top is on the reader's left: the screen's right edge is the reader's top.
    TURN_LEFT -> when (spot) {
        Spot.TOP_START -> Alignment.TopEnd
        Spot.TOP_END -> Alignment.BottomEnd
        Spot.BOTTOM -> Alignment.CenterStart
    }
    // The phone's top is on the reader's right: the screen's left edge is the reader's top.
    TURN_RIGHT -> when (spot) {
        Spot.TOP_START -> Alignment.BottomStart
        Spot.TOP_END -> Alignment.TopStart
        Spot.BOTTOM -> Alignment.CenterEnd
    }
    else -> when (spot) {
        Spot.TOP_START -> Alignment.TopStart
        Spot.TOP_END -> Alignment.TopEnd
        Spot.BOTTOM -> Alignment.BottomCenter
    }
}

/** The wordmark's letters in screen order, left to right: reversed when the reader's top is the screen's right. */
fun wordmarkOrder(turn: Int): List<Char> = if (turn == TURN_LEFT) "owlmic".reversed().toList() else "owlmic".toList()
