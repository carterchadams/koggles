#include "goggles.h"

#include <cardboard.h>

namespace koggles {

void cardboardInitAndroid(JavaVM* vm, jobject context) {
    Cardboard_initializeAndroid(vm, context);
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
