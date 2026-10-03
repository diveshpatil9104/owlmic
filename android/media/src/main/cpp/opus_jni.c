// JNI side of com.owlmic.media.codec.OpusEncoder and OpusDecoder. Handles are native pointers as jlong.
#include <jni.h>
#include <stdint.h>
#include <opus.h>

JNIEXPORT jlong JNICALL Java_com_owlmic_media_codec_OpusEncoder_create(
        JNIEnv *env, jclass clazz, jint sampleRate, jint channels, jint application, jint bitrate, jint complexity, jboolean fec) {
    int error = OPUS_OK;
    OpusEncoder *encoder = opus_encoder_create(sampleRate, channels, application, &error);
    if (error != OPUS_OK || encoder == NULL) return 0;
    opus_encoder_ctl(encoder, OPUS_SET_BITRATE(bitrate));
    opus_encoder_ctl(encoder, OPUS_SET_COMPLEXITY(complexity));
    opus_encoder_ctl(encoder, OPUS_SET_INBAND_FEC(fec ? 1 : 0));
    // No DTX: a steady stream is how the PC knows the mic is alive.
    opus_encoder_ctl(encoder, OPUS_SET_DTX(0));
    return (jlong) (intptr_t) encoder;
}

JNIEXPORT void JNICALL Java_com_owlmic_media_codec_OpusEncoder_setPacketLoss(JNIEnv *env, jclass clazz, jlong handle, jint percent) {
    opus_encoder_ctl((OpusEncoder *) (intptr_t) handle, OPUS_SET_PACKET_LOSS_PERC(percent));
}

// Returns the packet length, or a negative Opus error code.
JNIEXPORT jint JNICALL Java_com_owlmic_media_codec_OpusEncoder_encode(
        JNIEnv *env, jclass clazz, jlong handle, jshortArray pcm, jint frameSamples, jbyteArray out) {
    opus_int16 samples[5760];
    unsigned char packet[1500];
    jsize count = (*env)->GetArrayLength(env, pcm);
    if (count > 5760) count = 5760;
    (*env)->GetShortArrayRegion(env, pcm, 0, count, samples);
    jsize max = (*env)->GetArrayLength(env, out);
    if (max > (jsize) sizeof(packet)) max = sizeof(packet);
    int length = opus_encode((OpusEncoder *) (intptr_t) handle, samples, frameSamples, packet, max);
    if (length > 0) (*env)->SetByteArrayRegion(env, out, 0, length, (const jbyte *) packet);
    return length;
}

JNIEXPORT void JNICALL Java_com_owlmic_media_codec_OpusEncoder_destroy(JNIEnv *env, jclass clazz, jlong handle) {
    opus_encoder_destroy((OpusEncoder *) (intptr_t) handle);
}

JNIEXPORT jlong JNICALL Java_com_owlmic_media_codec_OpusDecoder_create(JNIEnv *env, jclass clazz, jint sampleRate, jint channels) {
    int error = OPUS_OK;
    OpusDecoder *decoder = opus_decoder_create(sampleRate, channels, &error);
    if (error != OPUS_OK || decoder == NULL) return 0;
    return (jlong) (intptr_t) decoder;
}

// data == null asks for concealment of a lost frame; fec != 0 recovers the previous frame from data's FEC.
// out receives interleaved samples. Returns samples per channel, or a negative Opus error code.
JNIEXPORT jint JNICALL Java_com_owlmic_media_codec_OpusDecoder_decode(
        JNIEnv *env, jclass clazz, jlong handle, jint channels, jbyteArray data, jint length, jshortArray out, jint frameSamples, jboolean fec) {
    unsigned char packet[1500];
    opus_int16 samples[5760 * 2];
    const unsigned char *in = NULL;
    if (data != NULL && length > 0) {
        if (length > (jint) sizeof(packet)) length = sizeof(packet);
        (*env)->GetByteArrayRegion(env, data, 0, length, (jbyte *) packet);
        in = packet;
    } else {
        length = 0;
    }
    if (frameSamples * channels > (jint) (sizeof(samples) / sizeof(samples[0]))) return OPUS_BUFFER_TOO_SMALL;
    int decoded = opus_decode((OpusDecoder *) (intptr_t) handle, in, length, samples, frameSamples, fec ? 1 : 0);
    if (decoded > 0) {
        jsize total = decoded * channels;
        jsize capacity = (*env)->GetArrayLength(env, out);
        if (total > capacity) total = capacity;
        (*env)->SetShortArrayRegion(env, out, 0, total, samples);
    }
    return decoded;
}

JNIEXPORT void JNICALL Java_com_owlmic_media_codec_OpusDecoder_destroy(JNIEnv *env, jclass clazz, jlong handle) {
    opus_decoder_destroy((OpusDecoder *) (intptr_t) handle);
}
