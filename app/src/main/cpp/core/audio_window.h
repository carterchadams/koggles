#pragma once

#include <cstddef>
#include <cstdint>
#include <mutex>
#include <vector>

// Fixed-capacity ring of interleaved stereo float frames. Thread-safe: any
// thread may write; the reader takes the newest unread frames.
class AudioWindow {
 public:
  explicit AudioWindow(size_t capacityFrames = 8192);

  void writeStereo(const float* interleaved, size_t frames);
  // Visualizer bytes: (x - 128) / 128, duplicated to L = R.
  void writeMonoU8(const uint8_t* samples, size_t frames);

  // Copies at most maxFrames of the newest unread frames, oldest first, into
  // outInterleaved, then marks every frame read. Returns the frame count.
  size_t readNew(float* outInterleaved, size_t maxFrames);

  void clear();

  // RMS over both channels of the last readNew output; 0 if it was empty.
  float lastReadRms() const;

 private:
  void pushFrameLocked(float l, float r);

  const size_t capacity_;
  std::vector<float> ring_;  // capacity_ * 2 floats
  size_t writePos_ = 0;      // next frame slot to write
  size_t unread_ = 0;        // frames written since the last read, <= capacity_
  float lastRms_ = 0.0f;
  mutable std::mutex mutex_;
};
