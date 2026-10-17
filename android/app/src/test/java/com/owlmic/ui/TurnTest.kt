package com.owlmic.ui

import android.view.OrientationEventListener
import androidx.compose.ui.Alignment
import org.junit.Assert.assertEquals
import org.junit.Test

class TurnTest {
    @Test
    fun aQuarterTurnNeedsTwentyDegreesPastHalfway() {
        // Sensor degrees: 90 is the phone's top to the right, 270 its top to the left.
        assertEquals(TURN_NONE, nextTurn(50, TURN_NONE))
        assertEquals(TURN_NONE, nextTurn(64, TURN_NONE))
        assertEquals(TURN_RIGHT, nextTurn(70, TURN_NONE))
        assertEquals(TURN_NONE, nextTurn(300, TURN_NONE))
        assertEquals(TURN_LEFT, nextTurn(290, TURN_NONE))
    }

    @Test
    fun nearTheDiagonalTheTurnHolds() {
        // Back towards upright from sideways: the same 65° from where it is before it changes.
        assertEquals(TURN_LEFT, nextTurn(330, TURN_LEFT))
        assertEquals(TURN_NONE, nextTurn(340, TURN_LEFT))
        assertEquals(TURN_RIGHT, nextTurn(30, TURN_RIGHT))
        assertEquals(TURN_NONE, nextTurn(20, TURN_RIGHT))
        // Jittering around 45° changes nothing either way.
        listOf(40, 45, 50, 55, 45, 40).forEach { assertEquals(TURN_NONE, nextTurn(it, TURN_NONE)) }
        listOf(40, 45, 50, 55, 45, 40).forEach { assertEquals(TURN_RIGHT, nextTurn(it, TURN_RIGHT)) }
    }

    @Test
    fun upsideDownAndFlatKeepTheTurn() {
        assertEquals(TURN_NONE, nextTurn(180, TURN_NONE))
        assertEquals(TURN_LEFT, nextTurn(180, TURN_LEFT))
        assertEquals(TURN_RIGHT, nextTurn(OrientationEventListener.ORIENTATION_UNKNOWN, TURN_RIGHT))
        // Through upside down from one side to the other.
        assertEquals(TURN_RIGHT, nextTurn(90, TURN_LEFT))
    }

    @Test
    fun glyphsTurnBackAgainstThePhone() {
        assertEquals(0f, uprightAngle(TURN_NONE))
        assertEquals(90f, uprightAngle(TURN_LEFT))
        assertEquals(-90f, uprightAngle(TURN_RIGHT))
    }

    @Test
    fun theReadersCornersFollowThePhone() {
        assertEquals(Alignment.TopStart, spotOf(Spot.TOP_START, TURN_NONE))
        assertEquals(Alignment.BottomCenter, spotOf(Spot.BOTTOM, TURN_NONE))
        // Top to the left: the screen's right edge is the reader's top, its left edge the reader's bottom.
        assertEquals(Alignment.TopEnd, spotOf(Spot.TOP_START, TURN_LEFT))
        assertEquals(Alignment.BottomEnd, spotOf(Spot.TOP_END, TURN_LEFT))
        assertEquals(Alignment.CenterStart, spotOf(Spot.BOTTOM, TURN_LEFT))
        // Top to the right: the mirror image.
        assertEquals(Alignment.BottomStart, spotOf(Spot.TOP_START, TURN_RIGHT))
        assertEquals(Alignment.TopStart, spotOf(Spot.TOP_END, TURN_RIGHT))
        assertEquals(Alignment.CenterEnd, spotOf(Spot.BOTTOM, TURN_RIGHT))
    }

    @Test
    fun theWordmarkStacksFromTheReadersTopInBothDirections() {
        assertEquals("owlmic", wordmarkOrder(TURN_NONE).joinToString(""))
        // Top to the left: the reader's top is the screen's right, so the screen row ends with the first letter.
        assertEquals("cimlwo", wordmarkOrder(TURN_LEFT).joinToString(""))
        // Top to the right: the reader's top is the screen's left, so the screen row starts with it.
        assertEquals("owlmic", wordmarkOrder(TURN_RIGHT).joinToString(""))
    }

    @Test
    fun theReaderReadsOwlmicTopToBottomEitherWay() {
        // What the reader sees from top to bottom: the screen row right to left for TURN_LEFT, left to right for TURN_RIGHT.
        val readerTopToBottom = { turn: Int -> wordmarkOrder(turn).let { if (turn == TURN_LEFT) it.reversed() else it }.joinToString("") }
        assertEquals("owlmic", readerTopToBottom(TURN_LEFT))
        assertEquals("owlmic", readerTopToBottom(TURN_RIGHT))
    }
}
