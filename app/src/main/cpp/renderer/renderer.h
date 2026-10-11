#pragma once

#include <cstdint>
#include <string>
#include <vector>

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
    // Sets projectM's texture search paths; reloads textures only when the list changed.
    void setTextureSearchPaths(const std::vector<std::string>& paths);
    int lastGlError() const { return lastGlError_; }

private:
    projectm_handle pm_ = nullptr;
    std::vector<float> pcm_;  // 2 * projectm_pcm_get_max_samples() interleaved floats
    int lastGlError_ = 0;
    std::vector<std::string> texturePaths_;
    bool texturePathsSet_ = false;
};

}  // namespace koggles
