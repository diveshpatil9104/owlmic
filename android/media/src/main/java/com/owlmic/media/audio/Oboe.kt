package com.owlmic.media.audio

/** Bindings for cpp/oboe_jni.cpp. Handles are native pointers; every call on one handle comes from one thread. */
internal object Oboe {
    const val PRESET_VOICE_RECOGNITION = 6
    const val PRESET_VOICE_COMMUNICATION = 7
    const val USAGE_MEDIA = 1
    const val USAGE_VOICE_COMMUNICATION = 2
    const val CONTENT_SPEECH = 1
    const val CONTENT_MUSIC = 2

    /** oboe::Result::ErrorDisconnected: the device went away (a headset unplugged, a route changed). */
    const val ERROR_DISCONNECTED = -899

    init {
        System.loadLibrary("owlmic")
    }

    @JvmStatic external fun openInput(preset: Int): Long

    @JvmStatic external fun openOutput(channels: Int, usage: Int, contentType: Int): Long

    @JvmStatic external fun sessionId(handle: Long): Int

    @JvmStatic external fun read(handle: Long, out: ShortArray, frames: Int, timeoutMs: Int): Int

    @JvmStatic external fun write(handle: Long, input: ShortArray, frames: Int, timeoutMs: Int): Int

    @JvmStatic external fun close(handle: Long)
}
