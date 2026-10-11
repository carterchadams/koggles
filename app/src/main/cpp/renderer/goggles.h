#pragma once

#include <GLES3/gl3.h>
#include <jni.h>

#include "quad.h"

struct CardboardLensDistortion;
struct CardboardDistortionRenderer;

namespace koggles {

// Calls Cardboard_initializeAndroid(vm, context) at most once per process. KogglesApp passes a
// display context derived from the Application (the SDK retains it; see NativeBridge.kt).
void cardboardInitAndroid(JavaVM* vm, jobject context);

// Creates and destroys a lens distortion from the Cardboard V1 viewer params.
bool cardboardLensProbe(int width, int height);

// Side-by-side output: Cardboard lens distortion of one eye texture to both eyes, or plain
// half-screen viewports when distortion is off. GL thread only.
class Goggles {
public:
    Goggles() = default;
    ~Goggles() { release(); }
    Goggles(const Goggles&) = delete;
    Goggles& operator=(const Goggles&) = delete;

    // Rebuilds lens and meshes only when an argument or the viewer profile changes.
    void configure(int displayW, int displayH, float xdpi, float offsetMm, bool distortion);
    void draw(GLuint eyeTexture);
    void release();

private:
    void rebuildLens();

    CardboardLensDistortion* lens_ = nullptr;
    CardboardDistortionRenderer* distortion_ = nullptr;
    Quad quad_;
    int displayW_ = 0;
    int displayH_ = 0;
    float xdpi_ = 0.f;
    float offsetMm_ = 0.f;
    bool distortionOn_ = true;
    int paramsChangedCount_ = -1;
    bool dirty_ = true;
};

}  // namespace koggles
