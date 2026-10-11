#include "frame_stats.h"

#include <algorithm>
#include <cmath>
#include <vector>

void FrameStats::add(double ms) {
    ring_[next_] = ms;
    next_ = (next_ + 1) % kCapacity;
    if (count_ < kCapacity) ++count_;
}

std::array<double, 3> FrameStats::takeSnapshot() {
    const size_t n = count_;
    count_ = 0;
    if (n == 0) return {0.0, 0.0, 0.0};
    std::vector<double> samples;
    samples.reserve(n);
    for (size_t i = 0; i < n; ++i) samples.push_back(ring_[(next_ + kCapacity - 1 - i) % kCapacity]);
    std::sort(samples.begin(), samples.end());
    auto nearestRank = [&](double p) {
        const size_t rank = static_cast<size_t>(std::ceil(p * static_cast<double>(n)));
        return samples[std::max<size_t>(rank, 1) - 1];
    };
    return {nearestRank(0.50), nearestRank(0.95), static_cast<double>(n)};
}
