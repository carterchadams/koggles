#pragma once

#include <GLES3/gl3.h>
#include <jni.h>

#include "quad.h"

struct CardboardLensDistortion;
struct CardboardDistortionRenderer;

namespace koggles {
/** Wide + window: the share of the frame each eye sees (the left eye its left share, the right eye its right). */
constexpr float kWideWindowWidth = 0.85f;
}  // namespace koggles

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
    // wideView: 0 the viewer profile as is; 1 its field of view opened to the screen edges (the mesh then reaches
    // them, same distortion); 2 also a different window of the frame per eye (kWideWindowWidth of it each).
    void configure(int displayW, int displayH, float xdpi, float offsetMm, bool distortion, int wideView);
    void draw(GLuint eyeTexture);
    void release();

private:
    void rebuildLens();

    CardboardLensDistortion* lens_ = nullptr;
    CardboardDistortionRenderer* distortion_ = nullptr;
    Quad quad_;
    int wideView_ = 0;
    int displayW_ = 0;
    int displayH_ = 0;
    float xdpi_ = 0.f;
    float offsetMm_ = 0.f;
    bool distortionOn_ = true;
    int paramsChangedCount_ = -1;
    bool dirty_ = true;
};

}  // namespace koggles
