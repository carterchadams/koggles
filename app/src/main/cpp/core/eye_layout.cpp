#include "eye_layout.h"

#include <cmath>

EyeViewports plainEyeViewports(int displayW, int displayH, float xdpi, float offsetMm) {
    const int half = displayW / 2;
    const int shift = static_cast<int>(std::lround(offsetMm * xdpi / 25.4f / 2.0f));
    EyeViewports v;
    v.left = Rect{0 - shift, 0, half, displayH};
    v.right = Rect{half + shift, 0, half, displayH};
    return v;
}
