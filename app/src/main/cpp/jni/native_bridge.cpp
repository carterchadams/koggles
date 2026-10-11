#include <jni.h>

#include <GLES3/gl3.h>
#include <android/log.h>

#include <atomic>
#include <cstdint>
#include <string>

#include "../renderer/engine.h"
#include "../renderer/goggles.h"
#include "../renderer/renderer.h"

using koggles::Engine;
using koggles::Renderer;

namespace {
Renderer* asRenderer(jlong h) { return reinterpret_cast<Renderer*>(h); }
std::atomic<int> gRendererDestroyCount{0};
}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_dev_koggles_NativeBridge_nativeInit(JNIEnv*, jobject) {
    Engine::instance();
}

// Main thread, from MainActivity.onCreate (D5).
JNIEXPORT void JNICALL
Java_dev_koggles_NativeBridge_cardboardInit(JNIEnv* env, jobject, jobject activity) {
    JavaVM* vm = nullptr;
    if (activity == nullptr || env->GetJavaVM(&vm) != JNI_OK) return;
    koggles::cardboardInitAndroid(vm, activity);
}

// Reads `frames` stereo frames from the start of the direct buffer (its position is ignored).
// Calls whose frame count exceeds the buffer's capacity are rejected.
JNIEXPORT void JNICALL
Java_dev_koggles_NativeBridge_pushPcmFloat(JNIEnv* env, jobject, jobject buf, jint frames) {
    if (buf == nullptr || frames <= 0) return;
    auto* data = static_cast<const float*>(env->GetDirectBufferAddress(buf));
    const jlong capacityFloats = env->GetDirectBufferCapacity(buf);
    if (data == nullptr || capacityFloats < 0) {
        __android_log_print(ANDROID_LOG_WARN, "Koggles", "pushPcmFloat: not a direct buffer, ignored");
        return;
    }
    if (static_cast<jlong>(frames) > capacityFloats / 2) {
        __android_log_print(ANDROID_LOG_WARN, "Koggles", "pushPcmFloat: %d frames exceed capacity %lld floats, ignored",
                            frames, static_cast<long long>(capacityFloats));
        return;
    }
    Engine::instance().audio().writeStereo(data, static_cast<size_t>(frames));
}

JNIEXPORT void JNICALL
Java_dev_koggles_NativeBridge_pushPcmU8(JNIEnv* env, jobject, jbyteArray buf, jint frames) {
    if (buf == nullptr || frames <= 0) return;
    const jsize len = env->GetArrayLength(buf);
    const jsize n = frames < len ? frames : len;
    jbyte* bytes = env->GetByteArrayElements(buf, nullptr);
    if (bytes == nullptr) return;
    Engine::instance().audio().writeMonoU8(reinterpret_cast<const uint8_t*>(bytes), static_cast<size_t>(n));
    env->ReleaseByteArrayElements(buf, bytes, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_dev_koggles_NativeBridge_flushAudio(JNIEnv*, jobject) {
    Engine::instance().audio().clear();
}

JNIEXPORT jfloat JNICALL
Java_dev_koggles_NativeBridge_recentRms(JNIEnv*, jobject) {
    return Engine::instance().audio().lastReadRms();
}

// GL thread. Clears pending GL error flags (e.g. GL_INVALID_VALUE from deleting a lost
// context's program names in a new context) and returns how many were cleared.
JNIEXPORT jint JNICALL
Java_dev_koggles_NativeBridge_glDrainErrors(JNIEnv*, jobject) {
    jint count = 0;
    while (glGetError() != GL_NO_ERROR && count < 64) ++count;
    return count;
}

JNIEXPORT jint JNICALL
Java_dev_koggles_NativeBridge_rendererDestroyCount(JNIEnv*, jobject) {
    return gRendererDestroyCount.load();
}

JNIEXPORT jlong JNICALL
Java_dev_koggles_NativeBridge_rendererCreate(JNIEnv*, jobject) {
    return reinterpret_cast<jlong>(new Renderer());
}

JNIEXPORT void JNICALL
Java_dev_koggles_NativeBridge_rendererDestroy(JNIEnv*, jobject, jlong h) {
    if (h == 0) return;
    delete asRenderer(h);
    gRendererDestroyCount.fetch_add(1);
}

JNIEXPORT void JNICALL
Java_dev_koggles_NativeBridge_rendererSurfaceChanged(JNIEnv*, jobject, jlong h, jint w, jint height) {
    if (h != 0) asRenderer(h)->surfaceChanged(w, height);
}

JNIEXPORT void JNICALL
Java_dev_koggles_NativeBridge_rendererDrawFrame(JNIEnv*, jobject, jlong h) {
    if (h != 0) asRenderer(h)->drawFrame();
}

JNIEXPORT void JNICALL
Java_dev_koggles_NativeBridge_rendererLoadPresetData(JNIEnv* env, jobject, jlong h, jstring data,
                                                      jlong requestId, jboolean smooth) {
    if (h == 0 || data == nullptr) return;
    const char* chars = env->GetStringUTFChars(data, nullptr);
    std::string text(chars != nullptr ? chars : "");
    if (chars != nullptr) env->ReleaseStringUTFChars(data, chars);
    asRenderer(h)->loadPresetData(text, requestId, smooth == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_dev_koggles_NativeBridge_rendererLoadIdle(JNIEnv*, jobject, jlong h, jlong requestId) {
    if (h != 0) asRenderer(h)->loadIdle(requestId);
}

JNIEXPORT jint JNICALL
Java_dev_koggles_NativeBridge_rendererLastGlError(JNIEnv*, jobject, jlong h) {
    return h != 0 ? asRenderer(h)->lastGlError() : 0;
}

}  // extern "C"
