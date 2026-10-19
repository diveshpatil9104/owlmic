package com.owlmic.media.camera

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The transform Android writes into the camera's SurfaceTexture when the camera draws straight into it (CameraX's
 * hasCameraTransform): a vertical flip, the sensor's [sensorDegrees] and, for a [front] lens, a mirror.
 */
data class CameraTransform(val sensorDegrees: Int, val front: Boolean)

/**
 * The matrix that goes after the SurfaceTexture's own: from the uv of what is drawn to the uv of the camera's buffer.
 * [rotation] turns the buffer upright (CameraX's rotation degrees, clockwise); [cropX] and [cropY] keep the centre of
 * the upright picture; [mirror] flips it left to right; [turn] lays it on its side for a phone held sideways.
 *
 * With [camera], the texture's matrix already turns the buffer by the sensor's angle, so that transform is taken out
 * first, as CameraX's own processor does. Without that, the picture is turned twice: on its side, and stretched to the
 * other shape. 4×4, column-major, as android.opengl.Matrix keeps them.
 */
fun sampling(rotation: Int, cropX: Float, cropY: Float, turn: Int, mirror: Boolean, camera: CameraTransform?): FloatArray {
    // Output uv → into the picture → mirror → crop around the centre → into the buffer's orientation.
    val inner = Affine.translate(0.5f, 0.5f) * Affine.rotate(rotation) * Affine.scale(cropX, cropY) *
        (if (mirror) Affine.scale(-1f, 1f) else Affine.IDENTITY) * Affine.rotate(turn) * Affine.translate(-0.5f, -0.5f)
    if (camera == null) return inner.toGl()
    return (cameraMatrix(camera).inverse() * FLIP * inner).toGl()
}

/** What a SurfaceTexture fed by the camera holds in its matrix, apart from crop and scale: flip, sensor angle, mirror. */
internal fun cameraMatrix(camera: CameraTransform): Affine =
    FLIP * Affine.translate(0.5f, 0.5f) * Affine.rotate(camera.sensorDegrees) * Affine.translate(-0.5f, -0.5f) *
        (if (camera.front) Affine.translate(1f, 0f) * Affine.scale(-1f, 1f) else Affine.IDENTITY)

/** The SurfaceTexture's flip from image rows to GL's upward y. */
internal val FLIP = Affine.translate(0f, 0.5f) * Affine.scale(1f, -1f) * Affine.translate(0f, -0.5f)

/** A 2D affine map, x' = a·x + b·y + tx and y' = c·x + d·y + ty. `p * q` applies q first. */
internal data class Affine(val a: Float, val b: Float, val c: Float, val d: Float, val tx: Float, val ty: Float) {
    operator fun times(o: Affine) = Affine(
        a * o.a + b * o.c, a * o.b + b * o.d,
        c * o.a + d * o.c, c * o.b + d * o.d,
        a * o.tx + b * o.ty + tx, c * o.tx + d * o.ty + ty,
    )

    fun inverse(): Affine {
        val det = a * d - b * c
        val ia = d / det
        val ib = -b / det
        val ic = -c / det
        val id = a / det
        return Affine(ia, ib, ic, id, -(ia * tx + ib * ty), -(ic * tx + id * ty))
    }

    fun map(x: Float, y: Float) = (a * x + b * y + tx) to (c * x + d * y + ty)

    fun toGl() = FloatArray(16).also {
        it[0] = a
        it[1] = c
        it[4] = b
        it[5] = d
        it[10] = 1f
        it[12] = tx
        it[13] = ty
        it[15] = 1f
    }

    companion object {
        val IDENTITY = Affine(1f, 0f, 0f, 1f, 0f, 0f)

        fun translate(x: Float, y: Float) = Affine(1f, 0f, 0f, 1f, x, y)

        fun scale(x: Float, y: Float) = Affine(x, 0f, 0f, y, 0f, 0f)

        /** Anticlockwise, as Matrix.rotateM: exact for quarter turns, which are all the camera uses. */
        fun rotate(degrees: Int): Affine {
            val r = Math.toRadians(degrees.toDouble())
            val cs = exact(cos(r))
            val sn = exact(sin(r))
            return Affine(cs, -sn, sn, cs, 0f, 0f)
        }

        private fun exact(v: Double): Float = v.roundToInt().takeIf { abs(v - it) < 1e-9 }?.toFloat() ?: v.toFloat()
    }
}
