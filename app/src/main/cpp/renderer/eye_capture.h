#pragma once

#include <GLES3/gl3.h>

namespace koggles {

// Copy variant (G1 probe P1: projectM 4.1.7 always draws its final pass into framebuffer 0).
// begin() sets an eye-sized viewport on framebuffer 0; projectM renders there; end() copies
// that region into the eye texture. GL thread only.
class EyeCapture {
public:
    EyeCapture() = default;
    ~EyeCapture() { release(); }
    EyeCapture(const EyeCapture&) = delete;
    EyeCapture& operator=(const EyeCapture&) = delete;

    void begin(int eyeW, int eyeH);
    GLuint end();
    void release();

private:
    GLuint texture_ = 0;
    int w_ = 0;
    int h_ = 0;
};

}  // namespace koggles
