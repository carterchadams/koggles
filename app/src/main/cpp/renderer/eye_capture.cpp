#include "eye_capture.h"

namespace koggles {

void EyeCapture::begin(int eyeW, int eyeH) {
    if (texture_ == 0 || eyeW != w_ || eyeH != h_) {
        if (texture_ == 0) glGenTextures(1, &texture_);
        glBindTexture(GL_TEXTURE_2D, texture_);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, eyeW, eyeH, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glBindTexture(GL_TEXTURE_2D, 0);
        w_ = eyeW;
        h_ = eyeH;
    }
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glViewport(0, 0, eyeW, eyeH);
}

GLuint EyeCapture::end() {
    glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
    glBindTexture(GL_TEXTURE_2D, texture_);
    glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 0, 0, w_, h_);
    glBindTexture(GL_TEXTURE_2D, 0);
    return texture_;
}

void EyeCapture::release() {
    if (texture_ != 0) glDeleteTextures(1, &texture_);
    texture_ = 0;
    w_ = h_ = 0;
}

}  // namespace koggles
