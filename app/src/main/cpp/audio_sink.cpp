// Low-latency AAudio sink for the PCM stream PulseAudio sends from the guest
// (module-simple-protocol-tcp). Kotlin reads the socket and calls write().
#include <aaudio/AAudio.h>
#include <android/log.h>
#include <jni.h>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <mutex>

#define TAG "ArchDock/AAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

struct Sink {
    AAudioStream *stream = nullptr;
    int32_t channels = 2;
    int32_t rate = 48000;
    int32_t burst = 192;
    std::atomic<int64_t> dropped{0};
    std::atomic<bool> needsRestart{false};
    std::mutex lock;
};

void onError(AAudioStream *, void *user, aaudio_result_t error) {
    // Typically AAUDIO_ERROR_DISCONNECTED when HDMI is unplugged/replugged.
    LOGW("stream error %s, restart requested", AAudio_convertResultToText(error));
    static_cast<Sink *>(user)->needsRestart = true;
}

aaudio_result_t openStream(Sink *s) {
    AAudioStreamBuilder *b = nullptr;
    aaudio_result_t r = AAudio_createStreamBuilder(&b);
    if (r != AAUDIO_OK) return r;
    AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setChannelCount(b, s->channels);
    AAudioStreamBuilder_setSampleRate(b, s->rate);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setUsage(b, AAUDIO_USAGE_MEDIA);
    AAudioStreamBuilder_setContentType(b, AAUDIO_CONTENT_TYPE_MUSIC);
    AAudioStreamBuilder_setErrorCallback(b, onError, s);
    r = AAudioStreamBuilder_openStream(b, &s->stream);
    AAudioStreamBuilder_delete(b);
    if (r != AAUDIO_OK) return r;
    s->burst = AAudioStream_getFramesPerBurst(s->stream);
    // Two bursts of buffering: low latency without underruns on the A520s.
    AAudioStream_setBufferSizeInFrames(s->stream, s->burst * 2);
    r = AAudioStream_requestStart(s->stream);
    LOGI("opened: rate=%d ch=%d burst=%d buffer=%d device=%d", AAudioStream_getSampleRate(s->stream),
         AAudioStream_getChannelCount(s->stream), s->burst,
         AAudioStream_getBufferSizeInFrames(s->stream), AAudioStream_getDeviceId(s->stream));
    return r;
}

void closeStream(Sink *s) {
    if (s->stream) {
        AAudioStream_requestStop(s->stream);
        AAudioStream_close(s->stream);
        s->stream = nullptr;
    }
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_dev_archdock_audio_NativeAudio_open(JNIEnv *, jclass, jint rate, jint channels) {
    auto *s = new Sink();
    s->rate = rate;
    s->channels = channels;
    if (openStream(s) != AAUDIO_OK) {
        closeStream(s);
        delete s;
        return 0;
    }
    return reinterpret_cast<jlong>(s);
}

/**
 * Writes interleaved s16 frames. Blocks for at most ~one buffer. When the
 * stream is already holding more than 4 bursts (the guest's clock runs ahead
 * of the HDMI clock) the oldest audio is dropped to keep latency bounded.
 * Returns frames written, or a negative AAudio error.
 */
JNIEXPORT jint JNICALL
Java_dev_archdock_audio_NativeAudio_write(JNIEnv *env, jclass, jlong handle, jbyteArray data, jint length) {
    auto *s = reinterpret_cast<Sink *>(handle);
    if (!s) return -1;
    std::lock_guard<std::mutex> g(s->lock);
    if (s->needsRestart.exchange(false)) {
        closeStream(s);
        if (openStream(s) != AAUDIO_OK) return -2;
    }
    if (!s->stream) return -1;
    const int32_t frameBytes = s->channels * 2;
    int32_t frames = length / frameBytes;
    int64_t queued = AAudioStream_getFramesWritten(s->stream) - AAudioStream_getFramesRead(s->stream);
    if (queued > s->burst * 4) {
        s->dropped += frames;
        return frames;
    }
    jbyte *bytes = env->GetByteArrayElements(data, nullptr);
    aaudio_result_t r = AAudioStream_write(s->stream, bytes, frames, 20 * 1000 * 1000LL /* 20 ms */);
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    if (r == AAUDIO_ERROR_DISCONNECTED) s->needsRestart = true;
    return r;
}

JNIEXPORT jlong JNICALL
Java_dev_archdock_audio_NativeAudio_dropped(JNIEnv *, jclass, jlong handle) {
    auto *s = reinterpret_cast<Sink *>(handle);
    return s ? s->dropped.load() : 0;
}

JNIEXPORT jint JNICALL
Java_dev_archdock_audio_NativeAudio_latencyFrames(JNIEnv *, jclass, jlong handle) {
    auto *s = reinterpret_cast<Sink *>(handle);
    if (!s || !s->stream) return -1;
    return AAudioStream_getBufferSizeInFrames(s->stream);
}

JNIEXPORT void JNICALL
Java_dev_archdock_audio_NativeAudio_close(JNIEnv *, jclass, jlong handle) {
    auto *s = reinterpret_cast<Sink *>(handle);
    if (!s) return;
    {
        std::lock_guard<std::mutex> g(s->lock);
        closeStream(s);
    }
    delete s;
}

}  // extern "C"
