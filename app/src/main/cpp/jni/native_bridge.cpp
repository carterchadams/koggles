#include <jni.h>

#include <GLES3/gl3.h>
#include <android/log.h>

#include <array>
#include <atomic>
#include <cstdint>
#include <vector>
#include <string>

#include <cardboard.h>

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

// Main thread, once per process from KogglesApp.onCreate, with a display context derived from
// the Application (Cardboard retains it and calls getDisplay() on it).
JNIEXPORT void JNICALL
Java_dev_koggles_NativeBridge_cardboardInit(JNIEnv* env, jobject, jobject appContext) {
    JavaVM* vm = nullptr;
    if (appContext == nullptr || env->GetJavaVM(&vm) != JNI_OK) return;
    koggles::cardboardInitAndroid(vm, appContext);
}

JNIEXPORT jboolean JNICALL
Java_dev_koggles_NativeBridge_cardboardLensProbe(JNIEnv*, jobject, jint width, jint height) {
    return koggles::cardboardLensProbe(width, height) ? JNI_TRUE : JNI_FALSE;
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

JNIEXPORT void JNICALL
Java_dev_koggles_NativeBridge_rendererSetGoggles(JNIEnv*, jobject, jlong h, jboolean on) {
    if (h != 0) asRenderer(h)->setGoggles(on == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_dev_koggles_NativeBridge_rendererSetLens(JNIEnv*, jobject, jlong h, jfloat offsetMm, jboolean distortion,
                                              jfloat xdpi) {
    if (h != 0) asRenderer(h)->setLens(offsetMm, distortion == JNI_TRUE, xdpi);
}

JNIEXPORT jboolean JNICALL
Java_dev_koggles_NativeBridge_rendererGogglesActive(JNIEnv*, jobject, jlong h) {
    return h != 0 && asRenderer(h)->gogglesActive() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jdoubleArray JNICALL
Java_dev_koggles_NativeBridge_rendererFrameStats(JNIEnv* env, jobject, jlong h) {
    std::array<double, 3> s{0.0, 0.0, 0.0};
    if (h != 0) s = asRenderer(h)->takeFrameStats();
    jdoubleArray out = env->NewDoubleArray(3);
    env->SetDoubleArrayRegion(out, 0, 3, s.data());
    return out;
}

JNIEXPORT jint JNICALL
Java_dev_koggles_NativeBridge_viewerParamsChangedCount(JNIEnv*, jobject) {
    return CardboardQrCode_getDeviceParamsChangedCount();
}

JNIEXPORT void JNICALL
Java_dev_koggles_NativeBridge_rendererSetTextureSearchPaths(JNIEnv* env, jobject, jlong h,
                                                            jobjectArray paths) {
    if (h == 0 || paths == nullptr) return;
    const jsize n = env->GetArrayLength(paths);
    std::vector<std::string> list;
    list.reserve(static_cast<size_t>(n));
    for (jsize i = 0; i < n; ++i) {
        auto s = static_cast<jstring>(env->GetObjectArrayElement(paths, i));
        if (s == nullptr) continue;
        const char* chars = env->GetStringUTFChars(s, nullptr);
        if (chars != nullptr) {
            list.emplace_back(chars);
            env->ReleaseStringUTFChars(s, chars);
        }
        env->DeleteLocalRef(s);
    }
    asRenderer(h)->setTextureSearchPaths(list);
}

JNIEXPORT jint JNICALL
Java_dev_koggles_NativeBridge_rendererLastGlError(JNIEnv*, jobject, jlong h) {
    return h != 0 ? asRenderer(h)->lastGlError() : 0;
}

}  // extern "C"
