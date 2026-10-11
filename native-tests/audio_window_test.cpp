#include "audio_window.h"

#include <gtest/gtest.h>

#include <cstdint>
#include <vector>

namespace {

std::vector<float> rampStereo(size_t frames) {
  std::vector<float> v(frames * 2);
  for (size_t i = 0; i < frames; ++i) {
    v[2 * i] = static_cast<float>(i);
    v[2 * i + 1] = static_cast<float>(i);
  }
  return v;
}

}  // namespace

TEST(AudioWindow, ReadNewReturnsOnlyUnread) {
  AudioWindow w;
  auto in = rampStereo(100);
  w.writeStereo(in.data(), 100);
  std::vector<float> out(2000);
  EXPECT_EQ(w.readNew(out.data(), 1000), 100u);
  EXPECT_EQ(w.readNew(out.data(), 1000), 0u);
}

TEST(AudioWindow, ReadNewReturnsNewest) {
  AudioWindow w;
  auto in = rampStereo(500);
  w.writeStereo(in.data(), 500);
  std::vector<float> out(256);
  ASSERT_EQ(w.readNew(out.data(), 128), 128u);
  EXPECT_EQ(out[0], 372.0f);
  EXPECT_EQ(out[1], 372.0f);
  EXPECT_EQ(out[254], 499.0f);
}

TEST(AudioWindow, OverflowKeepsNewest) {
  AudioWindow w(8);
  auto in = rampStereo(20);
  w.writeStereo(in.data(), 20);
  std::vector<float> out(200);
  ASSERT_EQ(w.readNew(out.data(), 100), 8u);
  EXPECT_EQ(out[0], 12.0f);
  EXPECT_EQ(out[14], 19.0f);
}

TEST(AudioWindow, MonoU8ConvertsAndDuplicates) {
  AudioWindow w;
  const uint8_t in[] = {0, 128, 255};
  w.writeMonoU8(in, 3);
  std::vector<float> out(6);
  ASSERT_EQ(w.readNew(out.data(), 3), 3u);
  const std::vector<float> expected = {-1.0f, -1.0f, 0.0f, 0.0f,
                                       127 / 128.f, 127 / 128.f};
  EXPECT_EQ(out, expected);
}

TEST(AudioWindow, ClearDropsAll) {
  AudioWindow w;
  auto in = rampStereo(50);
  w.writeStereo(in.data(), 50);
  w.clear();
  std::vector<float> out(200);
  EXPECT_EQ(w.readNew(out.data(), 100), 0u);
}

TEST(AudioWindow, RmsOfLastRead) {
  AudioWindow w;
  std::vector<float> in(64 * 2, 0.5f);
  w.writeStereo(in.data(), 64);
  std::vector<float> out(128);
  ASSERT_EQ(w.readNew(out.data(), 64), 64u);
  EXPECT_NEAR(w.lastReadRms(), 0.5f, 1e-6);
  EXPECT_EQ(w.readNew(out.data(), 64), 0u);
  EXPECT_EQ(w.lastReadRms(), 0.0f);
}
