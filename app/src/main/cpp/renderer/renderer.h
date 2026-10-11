#pragma once

#include <cstdint>
#include <string>

#include <projectM-4/projectM.h>

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
    int lastGlError() const { return lastGlError_; }

private:
    projectm_handle pm_ = nullptr;
    int lastGlError_ = 0;
};

}  // namespace koggles
