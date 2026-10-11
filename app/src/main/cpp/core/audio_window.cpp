#include "audio_window.h"

#include <algorithm>
#include <cmath>

AudioWindow::AudioWindow(size_t capacityFrames)
    : capacity_(std::max<size_t>(capacityFrames, 1)), ring_(capacity_ * 2, 0.0f) {}

void AudioWindow::pushFrameLocked(float l, float r) {
  ring_[2 * writePos_] = l;
  ring_[2 * writePos_ + 1] = r;
  writePos_ = (writePos_ + 1) % capacity_;
  if (unread_ < capacity_) ++unread_;
}

void AudioWindow::writeStereo(const float* interleaved, size_t frames) {
  std::lock_guard<std::mutex> lock(mutex_);
  for (size_t i = 0; i < frames; ++i) {
    pushFrameLocked(interleaved[2 * i], interleaved[2 * i + 1]);
  }
}

void AudioWindow::writeMonoU8(const uint8_t* samples, size_t frames) {
  std::lock_guard<std::mutex> lock(mutex_);
  for (size_t i = 0; i < frames; ++i) {
    const float v = (static_cast<float>(samples[i]) - 128.0f) / 128.0f;
    pushFrameLocked(v, v);
  }
}

size_t AudioWindow::readNew(float* outInterleaved, size_t maxFrames) {
  std::lock_guard<std::mutex> lock(mutex_);
  const size_t n = std::min(unread_, maxFrames);
  // Oldest of the n newest frames sits n slots behind writePos_.
  size_t pos = (writePos_ + capacity_ - n) % capacity_;
  double sumSq = 0.0;
  for (size_t i = 0; i < n; ++i) {
    const float l = ring_[2 * pos];
    const float r = ring_[2 * pos + 1];
    outInterleaved[2 * i] = l;
    outInterleaved[2 * i + 1] = r;
    sumSq += static_cast<double>(l) * l + static_cast<double>(r) * r;
    pos = (pos + 1) % capacity_;
  }
  unread_ = 0;
  lastRms_ = n == 0 ? 0.0f : static_cast<float>(std::sqrt(sumSq / (2.0 * n)));
  return n;
}

void AudioWindow::clear() {
  std::lock_guard<std::mutex> lock(mutex_);
  writePos_ = 0;
  unread_ = 0;
  lastRms_ = 0.0f;
  std::fill(ring_.begin(), ring_.end(), 0.0f);
}

float AudioWindow::lastReadRms() const {
  std::lock_guard<std::mutex> lock(mutex_);
  return lastRms_;
}
