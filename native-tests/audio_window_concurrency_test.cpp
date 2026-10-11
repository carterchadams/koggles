#include "audio_window.h"

#include <gtest/gtest.h>

#include <atomic>
#include <thread>
#include <vector>

TEST(AudioWindowConcurrency, WriterAndReader) {
  AudioWindow w;
  std::atomic<bool> done{false};
  constexpr size_t kTotal = 100000;
  constexpr size_t kBlock = 256;
  constexpr size_t kMaxRead = 576;

  std::thread writer([&] {
    std::vector<float> block(kBlock * 2, 0.25f);
    for (size_t written = 0; written < kTotal; written += kBlock) {
      size_t n = std::min(kBlock, kTotal - written);
      w.writeStereo(block.data(), n);
    }
    done.store(true);
  });

  std::vector<float> out(kMaxRead * 2);
  size_t maxSeen = 0;
  size_t reads = 0;
  while (!done.load()) {
    size_t n = w.readNew(out.data(), kMaxRead);
    maxSeen = std::max(maxSeen, n);
    (void)w.lastReadRms();
    ++reads;
  }
  size_t n = w.readNew(out.data(), kMaxRead);
  maxSeen = std::max(maxSeen, n);
  writer.join();

  EXPECT_LE(maxSeen, kMaxRead);
  EXPECT_GT(reads, 0u);
}
