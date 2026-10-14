#pragma once

#include <array>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <string>
#include <vector>

#include <projectM-4/projectM.h>

#include "bullseye.h"
#include "eye_capture.h"
#include "frame_stats.h"
#include "goggles.h"

namespace koggles {

// Owns every GL resource for one EGL context. Call only on the GL thread.
class Renderer {
public:
    Renderer();
    ~Renderer();
    Renderer(const Renderer&) = delete;
    Renderer& operator=(const Renderer&) = delete;

    void surfaceChanged(int w, int h);
    void drawFrame();
    void loadPresetData(const std::string& data, int64_t requestId, bool smooth);
    void loadIdle(int64_t requestId);
    // Sets projectM's texture search paths; reloads textures only when the list changed.
    void setTextureSearchPaths(const std::vector<std::string>& paths);
    int lastGlError() const { return lastGlError_; }
    // Frames whose eye copy (glCopyTexSubImage2D) raised a GL error; cumulative, any thread.
    int eyeCopyErrors() const { return eyeCopyErrors_.load(); }

    void setTransition(bool softCut, double blendSec, int presetDurationSec, bool hardCuts, bool locked);
    void setGoggles(bool on);
    void setLens(float offsetMm, bool distortion, float xdpi, int wideView);
    // Spacing calibration: the eye frame is a bullseye instead of the visualizer (goggles mode only).
    void setCalibration(bool on) { calibration_.store(on); }
    bool gogglesActive() const { return goggles_.load(); }
    std::array<double, 3> takeFrameStats() { return frameStats_.takeSnapshot(); }

private:
    void setProjectMSize(int w, int h);

    projectm_handle pm_ = nullptr;
    int64_t pendingId_ = -1;    // load awaiting its outcome: failure callback, or loaded after a frame
    int64_t displayedId_ = -1;  // last load reported loaded; tags switch requests for freshness checks

    static void onSwitchFailed(const char* filename, const char* message, void* self);
    static void onSwitchRequested(bool isHardCut, void* self);
    std::atomic<bool> goggles_{false};
    std::atomic<bool> calibration_{false};
    float lensOffsetMm_ = 0.f;
    bool lensDistortion_ = true;
    int wideView_ = 0;  // 0 normal, 1 wide field of view, 2 wide + a window per eye
    float xdpi_ = 0.f;
    int width_ = 0;
    int height_ = 0;
    int pmWidth_ = 0;
    int pmHeight_ = 0;
    EyeCapture eye_;
    FrameStats frameStats_;
    std::chrono::steady_clock::time_point lastFrameStart_{};
    bool haveLastFrame_ = false;
    Goggles goggles2d_;
    Bullseye bullseye_;
    std::vector<float> pcm_;  // 2 * projectm_pcm_get_max_samples() interleaved floats
    int lastGlError_ = 0;
    std::atomic<int> eyeCopyErrors_{0};
    std::vector<std::string> texturePaths_;
    bool texturePathsSet_ = false;
};

}  // namespace koggles
