#pragma once

#include <jni.h>

struct CardboardLensDistortion;
struct CardboardDistortionRenderer;

namespace koggles {

// Calls Cardboard_initializeAndroid(vm, context). Must run before any other
// Cardboard API call; MainActivity does it from onCreate (D5).
void cardboardInitAndroid(JavaVM* vm, jobject context);

// Creates and destroys a lens distortion from the Cardboard V1 viewer params.
bool cardboardLensProbe(int width, int height);

// Goggles (lens-distortion) output. Stub until Task 11: it owns nothing yet,
// and release() is a teardown sanity check that is safe to call any number
// of times, including before anything was created.
class Goggles {
public:
    Goggles() = default;
    ~Goggles() { release(); }
    Goggles(const Goggles&) = delete;
    Goggles& operator=(const Goggles&) = delete;

    void release();

private:
    CardboardLensDistortion* lens_ = nullptr;
    CardboardDistortionRenderer* distortion_ = nullptr;
};

}  // namespace koggles
