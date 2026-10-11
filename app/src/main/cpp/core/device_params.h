#pragma once

#include <cstdint>
#include <vector>

// Returns a copy of a serialized Cardboard DeviceParams protobuf with field 4
// (inter_lens_distance, fixed32 float, metres) set to old + offsetMm / 1000.
// All other fields stay byte-identical and in order. If field 4 is absent it
// is appended (taking 0 as the old value). Malformed input is returned as-is.
std::vector<uint8_t> withInterLensOffset(const std::vector<uint8_t>& encoded, float offsetMm);
