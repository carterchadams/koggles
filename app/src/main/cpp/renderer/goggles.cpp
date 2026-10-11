#include "goggles.h"

#include <cardboard.h>

#include <mutex>
#include <vector>

#include "device_params.h"
#include "eye_layout.h"

namespace koggles {

// Cardboard_initializeAndroid takes a new global reference to `context` on every call and never
// releases it, so it is called at most once per process.
void cardboardInitAndroid(JavaVM* vm, jobject context) {
    static std::once_flag once;
    std::call_once(once, [&] { Cardboard_initializeAndroid(vm, context); });
}

bool cardboardLensProbe(int width, int height) {
    uint8_t* params = nullptr;
    int size = 0;
    // V1 params are static SDK storage: "Users of this API should not free memory."
    CardboardQrCode_getCardboardV1DeviceParams(&params, &size);
    if (params == nullptr || size <= 0) return false;
    CardboardLensDistortion* lens = CardboardLensDistortion_create(params, size, width, height);
    if (lens == nullptr) return false;
    CardboardLensDistortion_destroy(lens);
    return true;
}

namespace {
// Saved viewer params if a QR code was scanned, else the Cardboard V1 default. Saved params are
// allocated for the caller and must be destroyed; V1 params are static and must not be.
std::vector<uint8_t> currentDeviceParams() {
    uint8_t* buf = nullptr;
    int size = 0;
    CardboardQrCode_getSavedDeviceParams(&buf, &size);
    if (buf != nullptr && size > 0) {
        std::vector<uint8_t> out(buf, buf + size);
        CardboardQrCode_destroy(buf);
        return out;
    }
    if (buf != nullptr) CardboardQrCode_destroy(buf);
    buf = nullptr;
    size = 0;
    CardboardQrCode_getCardboardV1DeviceParams(&buf, &size);
    if (buf == nullptr || size <= 0) return {};
    return std::vector<uint8_t>(buf, buf + size);
}
}  // namespace

void Goggles::configure(int displayW, int displayH, float xdpi, float offsetMm, bool distortion) {
    const int changed = CardboardQrCode_getDeviceParamsChangedCount();
    if (displayW != displayW_ || displayH != displayH_ || xdpi != xdpi_ || offsetMm != offsetMm_ ||
        distortion != distortionOn_ || changed != paramsChangedCount_) {
        displayW_ = displayW;
        displayH_ = displayH;
        xdpi_ = xdpi;
        offsetMm_ = offsetMm;
        distortionOn_ = distortion;
        paramsChangedCount_ = changed;
        dirty_ = true;
    }
}

void Goggles::rebuildLens() {
    dirty_ = false;
    if (lens_ != nullptr) {
        CardboardLensDistortion_destroy(lens_);
        lens_ = nullptr;
    }
    if (!distortionOn_ || displayW_ <= 0 || displayH_ <= 0) return;
    const std::vector<uint8_t> params = withInterLensOffset(currentDeviceParams(), offsetMm_);
    if (params.empty()) return;
    lens_ = CardboardLensDistortion_create(params.data(), static_cast<int>(params.size()), displayW_, displayH_);
    if (lens_ == nullptr) return;
    if (distortion_ == nullptr) {
        CardboardOpenGlEsDistortionRendererConfig config{kGlTexture2D};
        distortion_ = CardboardOpenGlEs3DistortionRenderer_create(&config);
    }
    if (distortion_ == nullptr) return;
    CardboardMesh mesh;
    CardboardLensDistortion_getDistortionMesh(lens_, kLeft, &mesh);
    CardboardDistortionRenderer_setMesh(distortion_, &mesh, kLeft);
    CardboardLensDistortion_getDistortionMesh(lens_, kRight, &mesh);
    CardboardDistortionRenderer_setMesh(distortion_, &mesh, kRight);
}

void Goggles::draw(GLuint eyeTexture) {
    if (dirty_) rebuildLens();
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glViewport(0, 0, displayW_, displayH_);
    glClearColor(0.f, 0.f, 0.f, 1.f);
    glClear(GL_COLOR_BUFFER_BIT);
    if (distortionOn_ && lens_ != nullptr && distortion_ != nullptr) {
        CardboardEyeTextureDescription eye{};
        eye.texture = eyeTexture;
        eye.left_u = 0.f;
        eye.right_u = 1.f;
        eye.top_v = 1.f;
        eye.bottom_v = 0.f;
        CardboardDistortionRenderer_renderEyeToDisplay(distortion_, 0, 0, 0, displayW_, displayH_, &eye, &eye);
        return;
    }
    const EyeViewports v = plainEyeViewports(displayW_, displayH_, xdpi_, offsetMm_);
    quad_.draw(eyeTexture, v.left.x, v.left.y, v.left.w, v.left.h);
    quad_.draw(eyeTexture, v.right.x, v.right.y, v.right.w, v.right.h);
    glViewport(0, 0, displayW_, displayH_);
}

void Goggles::release() {
    if (distortion_ != nullptr) {
        CardboardDistortionRenderer_destroy(distortion_);
        distortion_ = nullptr;
    }
    if (lens_ != nullptr) {
        CardboardLensDistortion_destroy(lens_);
        lens_ = nullptr;
    }
    quad_.release();
    dirty_ = true;
}

}  // namespace koggles
