#pragma once

#include <cstdint>
#include <vector>

// Returns a copy of a serialized Cardboard DeviceParams protobuf with field 4
// (inter_lens_distance, fixed32 float, metres) set to old + offsetMm / 1000.
// All other fields stay byte-identical and in order. If field 4 is absent it
// is appended (taking 0 as the old value). Malformed input is returned as-is.
std::vector<uint8_t> withInterLensOffset(const std::vector<uint8_t>& encoded, float offsetMm);

// Returns a copy with field 5 (left_eye_field_of_view_angles: packed repeated float, degrees, [outer,
// inner, bottom, top]) replaced by the four given half-angles: every existing field 5 is dropped and one
// new one appended; every other field stays byte-identical and in order. Malformed input is returned
// as-is. Wide view asks for 90 degrees: the SDK clamps each side to the angle at which the distorted ray
// leaves the screen, so the distortion mesh reaches the screen edges.
std::vector<uint8_t> withFieldOfView(const std::vector<uint8_t>& encoded, float outer, float inner, float bottom, float top);
