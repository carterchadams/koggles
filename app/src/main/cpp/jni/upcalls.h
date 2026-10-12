#pragma once

#include <jni.h>

#include <cstdint>

namespace koggles::upcalls {

// Caches NativeBridge's class and static callback ids. Call once from a Java thread with the app
// class loader (NativeBridge.nativeInit).
void init(JNIEnv* env);

// GL thread. Each calls the matching @JvmStatic NativeBridge method, which re-posts to main.
void switchRequested(bool hardCut, int64_t displayedRequestId);
void presetFailed(int64_t requestId, const char* message);
void presetLoaded(int64_t requestId);

}  // namespace koggles::upcalls
