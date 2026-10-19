package com.owlmic.media.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class SamplingTest {
    private fun affine(m: FloatArray) = Affine(m[0], m[4], m[1], m[5], m[12], m[13])

    private fun assertMaps(expected: Pair<Float, Float>, m: Affine, x: Float, y: Float) {
        val (px, py) = m.map(x, y)
        assertEquals(expected.first, px, 1e-5f)
        assertEquals(expected.second, py, 1e-5f)
    }

    @Test
    fun theCameraTransformInTheTextureIsTakenOutAgain() {
        // What the texture's matrix holds times our matrix must sample as if the texture held only the flip.
        for (camera in listOf(CameraTransform(90, false), CameraTransform(270, true), CameraTransform(0, false), CameraTransform(180, true))) {
            for (rotation in listOf(0, 90, 180, 270)) {
                for (turn in listOf(0, 90, 270)) {
                    val withTransform = cameraMatrix(camera) * affine(sampling(rotation, 0.5625f, 1f, turn, true, camera))
                    val plain = FLIP * affine(sampling(rotation, 0.5625f, 1f, turn, true, null))
                    for ((x, y) in listOf(0f to 0f, 1f to 0f, 0f to 1f, 0.3f to 0.8f)) assertMaps(plain.map(x, y), withTransform, x, y)
                }
            }
        }
    }

    @Test
    fun aPortraitBackCameraTakesThePicturesTopFromTheBuffersLeftEdge() {
        // Sensor at 90°, phone upright: the buffer turns a quarter clockwise, so its left edge becomes the top.
        assertMaps(0f to 0.5f, FLIP * affine(sampling(90, 1f, 1f, 0, false, null)), 0.5f, 1f)
    }

    @Test
    fun aQuarterTurnDrawsThePicturesTopOnTheScreensRight() {
        // Phone top to the left: the preview's right edge shows the top of the picture, which reads upright to the holder.
        assertMaps(0.5f to 1f, affine(sampling(0, 1f, 1f, 90, false, null)), 1f, 0.5f)
    }

    @Test
    fun theMirrorFlipsLeftAndRightOfThePicture() {
        assertMaps(0.75f to 0.5f, affine(sampling(0, 1f, 1f, 0, true, null)), 0.25f, 0.5f)
        // Sideways, it still flips the picture's own left and right, which run along the screen's height.
        assertMaps(0.25f to 0.5f, affine(sampling(0, 1f, 1f, 90, false, null)), 0.5f, 0.75f)
        assertMaps(0.75f to 0.5f, affine(sampling(0, 1f, 1f, 90, true, null)), 0.5f, 0.75f)
    }

    @Test
    fun theCropKeepsTheCentre() {
        assertMaps(0.5f to 0.5f, affine(sampling(90, 0.5625f, 1f, 0, false, null)), 0.5f, 0.5f)
        assertMaps(0.21875f to 0.5f, affine(sampling(0, 0.5625f, 1f, 0, false, null)), 0f, 0.5f)
    }
}
