#pragma once

#include <array>
#include <cstddef>

// Frame-time statistics over the last 600 samples added since the previous snapshot.
class FrameStats {
public:
    void add(double ms);

    // {p50, p95, count} (nearest-rank percentiles) over the samples added since the last
    // snapshot, at most the newest 600; resets the count. All zero when none were added.
    std::array<double, 3> takeSnapshot();

private:
    static constexpr size_t kCapacity = 600;
    std::array<double, kCapacity> ring_{};
    size_t next_ = 0;
    size_t count_ = 0;
};
