#include "renderer.h"

#include "engine.h"

#include <GLES3/gl3.h>
#include <android/log.h>

#define LOG_TAG "KogglesG1"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace koggles {

// projectM resolves the idle preset only with its full name (ProjectM.cpp uses the same URL).
constexpr const char* kIdlePresetUrl = "idle://Geiss & Sperl - Feedback (projectM idle HDR mix).milk";

namespace {
void onPresetSwitchFailed(const char* /*filename*/, const char* message, void* /*userData*/) {
    LOGI("failed-cb %s", message ? message : "");
}
}  // namespace

Renderer::Renderer() : pcm_(2 * projectm_pcm_get_max_samples()) {
    pm_ = projectm_create();
    if (pm_ != nullptr) {
        projectm_set_preset_switch_failed_event_callback(pm_, &onPresetSwitchFailed, this);
    }
}

Renderer::~Renderer() {
    if (pm_ != nullptr) {
        projectm_destroy(pm_);
    }
}

void Renderer::surfaceChanged(int w, int h) {
    width_ = w;
    height_ = h;
}

void Renderer::setProjectMSize(int w, int h) {
    if (pm_ == nullptr || (w == pmWidth_ && h == pmHeight_)) return;
    projectm_set_window_size(pm_, static_cast<size_t>(w), static_cast<size_t>(h));
    pmWidth_ = w;
    pmHeight_ = h;
}

void Renderer::setGoggles(bool on) { goggles_.store(on); }

void Renderer::setLens(float offsetMm, bool distortion, float xdpi) {
    lensOffsetMm_ = offsetMm;
    lensDistortion_ = distortion;
    xdpi_ = xdpi;
}

void Renderer::drawFrame() {
    if (pm_ == nullptr) return;
    const size_t frames = Engine::instance().audio().readNew(pcm_.data(), projectm_pcm_get_max_samples());
    if (frames > 0) {
        projectm_pcm_add_float(pm_, pcm_.data(), static_cast<unsigned int>(frames), PROJECTM_STEREO);
    }
    if (width_ <= 0 || height_ <= 0) return;
    if (!goggles_.load()) {
        setProjectMSize(width_, height_);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, width_, height_);
        projectm_opengl_render_frame(pm_);
    } else {
        // projectM renders one eye (half width) into framebuffer 0; it is copied into the eye
        // texture and drawn to both eyes (G1 P1: projectM ignores a bound FBO).
        const int eyeW = width_ / 2;
        const int eyeH = height_;
        setProjectMSize(eyeW, eyeH);
        eye_.begin(eyeW, eyeH);
        projectm_opengl_render_frame(pm_);
        const GLuint eyeTexture = eye_.end();
        goggles2d_.configure(width_, height_, xdpi_, lensOffsetMm_, lensDistortion_);
        goggles2d_.draw(eyeTexture);
    }
    lastGlError_ = static_cast<int>(glGetError());
}

void Renderer::loadPresetData(const std::string& data, int64_t requestId, bool smooth) {
    if (pm_ == nullptr) return;
    LOGI("load-begin %lld", static_cast<long long>(requestId));
    projectm_load_preset_data(pm_, data.c_str(), smooth);
    LOGI("load-end %lld", static_cast<long long>(requestId));
}

void Renderer::loadIdle(int64_t /*requestId*/) {
    if (pm_ == nullptr) return;
    projectm_load_preset_file(pm_, kIdlePresetUrl, false);
}

}  // namespace koggles
