#include "bullseye.h"

namespace koggles {

namespace {
const char* kVertex = R"(#version 300 es
layout(location = 0) in vec2 aPos;
out vec2 vPos;
void main() { vPos = aPos; gl_Position = vec4(aPos, 0.0, 1.0); }
)";

// Rings in texture space (clip position, one unit = half the frame each way): the SDK stretches the frame over the
// profile's field of view, which is square for the normal view, so these are circles in the headset. Each ring is
// about 2 px wide (uPxPerUnit = half the smaller side in px) with 1 px of feathering.
const char* kFragment = R"(#version 300 es
precision highp float;
in vec2 vPos;
uniform float uPxPerUnit;
out vec4 color;
void main() {
    float r = length(vPos);
    float px = 1.0 / uPxPerUnit;
    float lit = 0.0;
    for (int i = 1; i <= 3; ++i) {
        float d = abs(r - 0.12 * float(i));   // 0.12 of the half height = 6 % of the height
        lit = max(lit, 1.0 - smoothstep(px, 2.0 * px, d));
    }
    color = vec4(vec3(lit), 1.0);
}
)";

GLuint compile(GLenum type, const char* src) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, nullptr);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        glDeleteShader(s);
        return 0;
    }
    return s;
}
}  // namespace

bool Bullseye::ensure() {
    if (program_ != 0) return true;
    GLuint vs = compile(GL_VERTEX_SHADER, kVertex);
    GLuint fs = compile(GL_FRAGMENT_SHADER, kFragment);
    if (vs == 0 || fs == 0) {
        if (vs) glDeleteShader(vs);
        if (fs) glDeleteShader(fs);
        return false;
    }
    program_ = glCreateProgram();
    glAttachShader(program_, vs);
    glAttachShader(program_, fs);
    glLinkProgram(program_);
    glDeleteShader(vs);
    glDeleteShader(fs);
    GLint ok = 0;
    glGetProgramiv(program_, GL_LINK_STATUS, &ok);
    if (!ok) {
        glDeleteProgram(program_);
        program_ = 0;
        return false;
    }
    const float verts[] = {-1.f, -1.f, 1.f, -1.f, -1.f, 1.f, 1.f, 1.f};
    glGenVertexArrays(1, &vao_);
    glGenBuffers(1, &vbo_);
    glBindVertexArray(vao_);
    glBindBuffer(GL_ARRAY_BUFFER, vbo_);
    glBufferData(GL_ARRAY_BUFFER, sizeof(verts), verts, GL_STATIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 2 * sizeof(float), nullptr);
    glBindVertexArray(0);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    return true;
}

void Bullseye::draw(int x, int y, int w, int h) {
    glViewport(x, y, w, h);
    glClearColor(0.f, 0.f, 0.f, 1.f);
    glClear(GL_COLOR_BUFFER_BIT);
    if (!ensure() || w <= 0 || h <= 0) return;
    glUseProgram(program_);
    glUniform1f(glGetUniformLocation(program_, "uPxPerUnit"), static_cast<float>(w < h ? w : h) / 2.f);
    glBindVertexArray(vao_);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindVertexArray(0);
    glUseProgram(0);
}

void Bullseye::release() {
    if (vbo_) glDeleteBuffers(1, &vbo_);
    if (vao_) glDeleteVertexArrays(1, &vao_);
    if (program_) glDeleteProgram(program_);
    vbo_ = vao_ = program_ = 0;
}

}  // namespace koggles
