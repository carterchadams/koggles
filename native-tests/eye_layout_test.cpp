#include "eye_layout.h"

#include <gtest/gtest.h>

TEST(EyeLayout, ZeroOffsetSplitsEvenly) {
    const EyeViewports v = plainEyeViewports(2400, 1080, 400.0f, 0.0f);
    EXPECT_EQ(v.left.x, 0);
    EXPECT_EQ(v.left.y, 0);
    EXPECT_EQ(v.left.w, 1200);
    EXPECT_EQ(v.left.h, 1080);
    EXPECT_EQ(v.right.x, 1200);
    EXPECT_EQ(v.right.y, 0);
    EXPECT_EQ(v.right.w, 1200);
    EXPECT_EQ(v.right.h, 1080);
}

TEST(EyeLayout, PositiveOffsetMovesOutward) {
    // 2 mm at 400 dpi = 31.5 px total, 15.75 per eye -> 16 px.
    const EyeViewports v = plainEyeViewports(2400, 1080, 400.0f, 2.0f);
    EXPECT_EQ(v.left.x, -16);
    EXPECT_EQ(v.right.x, 1216);
    EXPECT_EQ(v.left.w, 1200);
    EXPECT_EQ(v.right.w, 1200);
}

TEST(EyeLayout, NegativeOffsetMovesInward) {
    const EyeViewports v = plainEyeViewports(2400, 1080, 400.0f, -2.0f);
    EXPECT_EQ(v.left.x, 16);
    EXPECT_EQ(v.right.x, 1184);
}
