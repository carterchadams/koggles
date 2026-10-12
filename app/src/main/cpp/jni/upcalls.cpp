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
    // Resolve each id separately: a failed lookup leaves a pending exception that must be
    // cleared before the next JNI call. A missing method (e.g. renamed by R8) is reported by
    // name; the release smoke test requires "upcalls ready 3/3".
    struct Upcall { jmethodID* id; const char* name; const char* sig; };
    const Upcall all[] = {
        {&gSwitchRequested, "onSwitchRequested", "(ZJ)V"},
        {&gPresetFailed, "onPresetFailed", "(JLjava/lang/String;)V"},
        {&gPresetLoaded, "onPresetLoaded", "(J)V"},
    };
    int ready = 0;
    for (const auto& u : all) {
        *u.id = e->GetStaticMethodID(gBridge, u.name, u.sig);
        if (e->ExceptionCheck()) e->ExceptionClear();
        if (*u.id == nullptr) {
            __android_log_print(ANDROID_LOG_ERROR, "Koggles", "upcalls: method %s%s not found", u.name, u.sig);
        } else {
            ++ready;
        }
    }
    __android_log_print(ANDROID_LOG_INFO, "Koggles", "upcalls ready %d/3", ready);
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
