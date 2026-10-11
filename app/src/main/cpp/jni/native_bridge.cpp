#include <jni.h>

#include <string>

#include "../renderer/renderer.h"

using koggles::Renderer;

namespace {
Renderer* asRenderer(jlong h) { return reinterpret_cast<Renderer*>(h); }
}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_dev_koggles_NativeBridge_rendererCreate(JNIEnv*, jobject) {
    return reinterpret_cast<jlong>(new Renderer());
}

JNIEXPORT void JNICALL
Java_dev_koggles_NativeBridge_rendererDestroy(JNIEnv*, jobject, jlong h) {
    delete asRenderer(h);
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
