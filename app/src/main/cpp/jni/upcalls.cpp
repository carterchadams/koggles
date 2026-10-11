#include "upcalls.h"

#include <android/log.h>

namespace koggles::upcalls {

namespace {
JavaVM* gVm = nullptr;
jclass gBridge = nullptr;
jmethodID gSwitchRequested = nullptr;
jmethodID gPresetFailed = nullptr;
jmethodID gPresetLoaded = nullptr;

JNIEnv* env() {
    JNIEnv* e = nullptr;
    if (gVm == nullptr || gVm->GetEnv(reinterpret_cast<void**>(&e), JNI_VERSION_1_6) != JNI_OK) return nullptr;
    return e;
}

void checkException(JNIEnv* e) {
    if (e->ExceptionCheck()) {
        e->ExceptionDescribe();
        e->ExceptionClear();
    }
}
}  // namespace

void init(JNIEnv* e) {
    if (gBridge != nullptr) return;
    e->GetJavaVM(&gVm);
    jclass local = e->FindClass("dev/koggles/NativeBridge");
    if (local == nullptr) {
        checkException(e);
        __android_log_print(ANDROID_LOG_ERROR, "Koggles", "upcalls: NativeBridge class not found");
        return;
    }
    gBridge = reinterpret_cast<jclass>(e->NewGlobalRef(local));
    e->DeleteLocalRef(local);
    gSwitchRequested = e->GetStaticMethodID(gBridge, "onSwitchRequested", "(ZJ)V");
    gPresetFailed = e->GetStaticMethodID(gBridge, "onPresetFailed", "(JLjava/lang/String;)V");
    gPresetLoaded = e->GetStaticMethodID(gBridge, "onPresetLoaded", "(J)V");
    checkException(e);
}

void switchRequested(bool hardCut, int64_t displayedRequestId) {
    JNIEnv* e = env();
    if (e == nullptr || gSwitchRequested == nullptr) return;
    e->CallStaticVoidMethod(gBridge, gSwitchRequested, hardCut ? JNI_TRUE : JNI_FALSE,
                           static_cast<jlong>(displayedRequestId));
    checkException(e);
}

void presetFailed(int64_t requestId, const char* message) {
    JNIEnv* e = env();
    if (e == nullptr || gPresetFailed == nullptr) return;
    jstring msg = e->NewStringUTF(message != nullptr ? message : "");
    e->CallStaticVoidMethod(gBridge, gPresetFailed, static_cast<jlong>(requestId), msg);
    if (msg != nullptr) e->DeleteLocalRef(msg);
    checkException(e);
}

void presetLoaded(int64_t requestId) {
    JNIEnv* e = env();
    if (e == nullptr || gPresetLoaded == nullptr) return;
    e->CallStaticVoidMethod(gBridge, gPresetLoaded, static_cast<jlong>(requestId));
    checkException(e);
}

}  // namespace koggles::upcalls
