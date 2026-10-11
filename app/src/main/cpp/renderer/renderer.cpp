#include "renderer.h"

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

Renderer::Renderer() {
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
    if (pm_ == nullptr) return;
    projectm_set_window_size(pm_, static_cast<size_t>(w), static_cast<size_t>(h));
}

void Renderer::drawFrame() {
    if (pm_ == nullptr) return;
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    projectm_opengl_render_frame(pm_);
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
