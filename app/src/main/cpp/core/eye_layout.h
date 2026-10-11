#pragma once

struct Rect {
    int x, y, w, h;
};

struct EyeViewports {
    Rect left, right;
};

// Splits the display into left/right halves, each shifted outward by
// lround(offsetMm * xdpi / 25.4 / 2) px (left moves -x, right moves +x).
// Negative offsets move the halves inward.
EyeViewports plainEyeViewports(int displayW, int displayH, float xdpi, float offsetMm);
