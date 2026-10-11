#include "frame_stats.h"

#include <gtest/gtest.h>

TEST(FrameStats, PercentilesNearestRank) {
    FrameStats stats;
    for (int i = 1; i <= 100; ++i) stats.add(i);
    const auto s = stats.takeSnapshot();
    EXPECT_DOUBLE_EQ(50.0, s[0]);
    EXPECT_DOUBLE_EQ(95.0, s[1]);
    EXPECT_DOUBLE_EQ(100.0, s[2]);
}

TEST(FrameStats, SnapshotResets) {
    FrameStats stats;
    stats.add(16.0);
    stats.takeSnapshot();
    const auto s = stats.takeSnapshot();
    EXPECT_DOUBLE_EQ(0.0, s[2]);
    EXPECT_DOUBLE_EQ(0.0, s[0]);
}

TEST(FrameStats, RingKeepsLast600) {
    FrameStats stats;
    for (int i = 0; i < 700; ++i) stats.add(i < 100 ? 1000.0 : 10.0);
    const auto s = stats.takeSnapshot();
    EXPECT_DOUBLE_EQ(600.0, s[2]);
    EXPECT_DOUBLE_EQ(10.0, s[1]);  // the 100 large early samples were overwritten
}
