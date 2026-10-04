package com.owlmic.media.camera

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * The camera's picture on its way to the encoder (section 17.2, "Shape"): one EGL context on its own thread takes the
 * camera's external texture, rotates it upright and crops it to the output's shape, then draws it into the encoder's
 * input surface and, while visible, into the preview. Nothing is copied on the CPU.
 */
class GlRenderer {
    private val thread = HandlerThread("owlmic-gl").apply { start() }
    val handler = Handler(thread.looper)

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var idle: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var texture = 0
    private var cameraTexture: SurfaceTexture? = null
    private var cameraSurface: Surface? = null

    private var encoder: EGLSurface = EGL14.EGL_NO_SURFACE
    private var encoderWidth = 0
    private var encoderHeight = 0
    private var preview: EGLSurface = EGL14.EGL_NO_SURFACE
    private var previewWidth = 0
    private var previewHeight = 0

    /** Degrees the camera's buffer turns clockwise to be upright, from CameraX. */
    @Volatile var rotation = 0

    /** The encoder gets at most this many frames a second; the preview gets them all. */
    @Volatile var fps = 30

    private var bufferWidth = 0
    private var bufferHeight = 0
    private val gate = FrameGate()
    private val texMatrix = FloatArray(16)
    private val uvMatrix = FloatArray(16)
    private val scratch = FloatArray(16)

    private val quad: FloatBuffer = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val uv: FloatBuffer = floats(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

    init {
        handler.post(::setUp)
    }

    /** The surface the camera draws into, sized [width]×[height]. Call on the GL thread ([handler]). */
    fun cameraSurface(width: Int, height: Int): Surface {
        bufferWidth = width
        bufferHeight = height
        val st = cameraTexture!!
        st.setDefaultBufferSize(width, height)
        return cameraSurface ?: Surface(st).also { cameraSurface = it }
    }

    /** The encoder's input surface, or null to stop encoding. GL thread. */
    fun setEncoder(surface: Surface?, width: Int, height: Int) {
        if (encoder != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(display, idle, idle, context)
            EGL14.eglDestroySurface(display, encoder)
            encoder = EGL14.EGL_NO_SURFACE
        }
        if (surface != null) {
            encoder = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
            encoderWidth = width
            encoderHeight = height
        }
    }

    /** The preview's surface, or null while it isn't on screen. GL thread. */
    fun setPreview(surface: Surface?, width: Int, height: Int) {
        if (preview != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(display, idle, idle, context)
            EGL14.eglDestroySurface(display, preview)
            preview = EGL14.EGL_NO_SURFACE
        }
        if (surface != null && surface.isValid) {
            preview = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
            previewWidth = width
            previewHeight = height
        }
    }

    fun release() {
        handler.post {
            setEncoder(null, 0, 0)
            setPreview(null, 0, 0)
            cameraSurface?.release()
            cameraTexture?.release()
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, idle)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
            thread.quitSafely()
        }
    }

    private fun setUp() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(display, version, 0, version, 1)
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0)
        config = configs[0]
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        idle = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(display, idle, idle, context)

        program = link(VERTEX, FRAGMENT)
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        texture = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        cameraTexture = SurfaceTexture(texture).apply { setOnFrameAvailableListener({ frame() }, handler) }
    }

    private fun frame() {
        val st = cameraTexture ?: return
        EGL14.eglMakeCurrent(display, idle, idle, context)
        st.updateTexImage()
        st.getTransformMatrix(texMatrix)
        val ts = st.timestamp
        val upright = if (rotation % 180 == 0) bufferWidth to bufferHeight else bufferHeight to bufferWidth
        if (encoder != EGL14.EGL_NO_SURFACE && gate.pass(ts, fps)) {
            EGL14.eglMakeCurrent(display, encoder, encoder, context)
            draw(upright.first, upright.second, encoderWidth, encoderHeight, 0, 0, encoderWidth, encoderHeight)
            EGLExt.eglPresentationTimeANDROID(display, encoder, ts)
            EGL14.eglSwapBuffers(display, encoder)
        }
        if (preview != EGL14.EGL_NO_SURFACE) {
            // Exactly what is sent: the encoder's framing, fitted inside the preview with black around it.
            val ow = if (encoderWidth > 0) encoderWidth else upright.first
            val oh = if (encoderHeight > 0) encoderHeight else upright.second
            val scale = minOf(previewWidth.toFloat() / ow, previewHeight.toFloat() / oh)
            val w = (ow * scale).toInt()
            val h = (oh * scale).toInt()
            EGL14.eglMakeCurrent(display, preview, preview, context)
            GLES20.glViewport(0, 0, previewWidth, previewHeight)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            draw(upright.first, upright.second, ow, oh, (previewWidth - w) / 2, (previewHeight - h) / 2, w, h)
            if (!EGL14.eglSwapBuffers(display, preview)) setPreview(null, 0, 0)
        }
    }

    /** Draws the upright camera picture (uw×uh) cropped to ow:oh into the viewport. */
    private fun draw(uw: Int, uh: Int, ow: Int, oh: Int, x: Int, y: Int, w: Int, h: Int) {
        GLES20.glViewport(x, y, w, h)
        val (cx, cy) = centerCrop(uw, uh, ow, oh)
        // uv in the output → crop around the centre → turn back into the buffer's orientation → the texture's own transform.
        Matrix.setIdentityM(uvMatrix, 0)
        Matrix.translateM(uvMatrix, 0, 0.5f, 0.5f, 0f)
        Matrix.rotateM(uvMatrix, 0, rotation.toFloat(), 0f, 0f, 1f)
        Matrix.scaleM(uvMatrix, 0, cx, cy, 1f)
        Matrix.translateM(uvMatrix, 0, -0.5f, -0.5f, 0f)
        Matrix.multiplyMM(scratch, 0, texMatrix, 0, uvMatrix, 0)

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        val coord = GLES20.glGetAttribLocation(program, "aCoord")
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTex"), 1, false, scratch, 0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glEnableVertexAttribArray(coord)
        GLES20.glVertexAttribPointer(coord, 2, GLES20.GL_FLOAT, false, 0, uv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(coord)
    }

    private fun link(vertex: String, fragment: String): Int {
        fun shader(type: Int, source: String) = GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, source)
            GLES20.glCompileShader(it)
        }
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, shader(GLES20.GL_VERTEX_SHADER, vertex))
            GLES20.glAttachShader(it, shader(GLES20.GL_FRAGMENT_SHADER, fragment))
            GLES20.glLinkProgram(it)
        }
    }

    private companion object {
        /** EGL_RECORDABLE_ANDROID: the config can feed a MediaCodec input surface. */
        const val EGL_RECORDABLE_ANDROID = 0x3142

        const val VERTEX = """
            attribute vec4 aPosition;
            attribute vec4 aCoord;
            uniform mat4 uTex;
            varying vec2 vCoord;
            void main() {
                gl_Position = aPosition;
                vCoord = (uTex * aCoord).xy;
            }
        """

        const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vCoord;
            uniform samplerExternalOES sTex;
            void main() {
                gl_FragColor = texture2D(sTex, vCoord);
            }
        """

        fun floats(vararg v: Float): FloatBuffer =
            ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(v)
                position(0)
            }
    }
}
