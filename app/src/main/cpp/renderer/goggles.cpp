#include "goggles.h"

#include <cardboard.h>

#include <mutex>

namespace koggles {

// Cardboard_initializeAndroid takes a new global reference to `context` on every call and never
// releases it, so it is called at most once per process, with the Application context.
void cardboardInitAndroid(JavaVM* vm, jobject context) {
    static std::once_flag once;
    std::call_once(once, [&] { Cardboard_initializeAndroid(vm, context); });
}

void Goggles::release() {
    if (distortion_ != nullptr) {
        CardboardDistortionRenderer_destroy(distortion_);
        distortion_ = nullptr;
    }
    if (lens_ != nullptr) {
        CardboardLensDistortion_destroy(lens_);
        lens_ = nullptr;
    }
}

}  // namespace koggles
