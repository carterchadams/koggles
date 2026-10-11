#pragma once

#include <GLES3/gl3.h>

namespace koggles {

// Draws a 2D texture as a full-viewport quad. GL thread only.
class Quad {
public:
    Quad() = default;
    ~Quad() { release(); }
    Quad(const Quad&) = delete;
    Quad& operator=(const Quad&) = delete;

    void draw(GLuint texture, int x, int y, int w, int h);
    void release();

private:
    bool ensure();
    GLuint program_ = 0;
    GLuint vao_ = 0;
    GLuint vbo_ = 0;
};

}  // namespace koggles
