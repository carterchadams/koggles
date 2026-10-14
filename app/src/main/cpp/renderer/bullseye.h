#pragma once

#include <GLES3/gl3.h>

namespace koggles {

// Spacing calibration (the author, 2026-10-13): a bullseye target, white concentric rings on black centred in the
// frame, drawn in place of the visualizer frame so both eyes show one and the lens spacing moves them together
// or apart. Rings at 12, 24 and 36 % of the half frame, in frame (texture) units, ~1 px feathering. GL thread only.
class Bullseye {
public:
    Bullseye() = default;
    ~Bullseye() { release(); }
    Bullseye(const Bullseye&) = delete;
    Bullseye& operator=(const Bullseye&) = delete;

    // Draws into the current framebuffer over the viewport (x, y, w, h), clearing it black first.
    void draw(int x, int y, int w, int h);
    void release();

private:
    bool ensure();
    GLuint program_ = 0;
    GLuint vao_ = 0;
    GLuint vbo_ = 0;
};

}  // namespace koggles
