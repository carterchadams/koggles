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

bool cardboardLensProbe(int width, int height) {
    uint8_t* params = nullptr;
    int size = 0;
    CardboardQrCode_getCardboardV1DeviceParams(&params, &size);
    if (params == nullptr || size <= 0) return false;
    CardboardLensDistortion* lens = CardboardLensDistortion_create(params, size, width, height);
    CardboardQrCode_destroy(params);
    if (lens == nullptr) return false;
    CardboardLensDistortion_destroy(lens);
    return true;
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
