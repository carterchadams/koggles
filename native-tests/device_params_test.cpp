#include "device_params.h"

#include <gtest/gtest.h>

#include <cstdint>
#include <cstring>
#include <vector>

namespace {

using Bytes = std::vector<uint8_t>;

void putFloat(Bytes& b, float f) {
    uint32_t u;
    std::memcpy(&u, &f, 4);
    for (int i = 0; i < 4; ++i) b.push_back(static_cast<uint8_t>(u >> (8 * i)));
}

float getFloat(const Bytes& b, size_t at) {
    uint32_t u = 0;
    for (int i = 0; i < 4; ++i) u |= static_cast<uint32_t>(b.at(at + i)) << (8 * i);
    float f;
    std::memcpy(&f, &u, 4);
    return f;
}

// field 3 (screen_to_lens_distance) = 0.042, field 4 (inter_lens_distance) = 0.064,
// field 7 (distortion_coefficients, packed) = {0.34, 0.55}
Bytes field3() { Bytes b{0x1D}; putFloat(b, 0.042f); return b; }
Bytes field4(float v) { Bytes b{0x25}; putFloat(b, v); return b; }
Bytes field7() { Bytes b{0x3A, 0x08}; putFloat(b, 0.34f); putFloat(b, 0.55f); return b; }

Bytes concat(std::initializer_list<Bytes> parts) {
    Bytes out;
    for (const auto& p : parts) out.insert(out.end(), p.begin(), p.end());
    return out;
}

Bytes sample() { return concat({field3(), field4(0.064f), field7()}); }

}  // namespace

TEST(DeviceParams, PatchesExisting) {
    const Bytes in = sample();
    const Bytes out = withInterLensOffset(in, 2.0f);
    ASSERT_EQ(out.size(), in.size());
    EXPECT_EQ(out[5], 0x25);
    EXPECT_NEAR(getFloat(out, 6), 0.066f, 1e-6);
    // Every byte outside field 4's payload is identical.
    for (size_t i = 0; i < in.size(); ++i) {
        if (i >= 6 && i < 10) continue;
        EXPECT_EQ(out[i], in[i]) << "byte " << i;
    }
}

TEST(DeviceParams, ZeroIsIdentity) {
    const Bytes in = sample();
    EXPECT_EQ(withInterLensOffset(in, 0.0f), in);
}

TEST(DeviceParams, NegativeOffset) {
    const Bytes out = withInterLensOffset(sample(), -4.0f);
    ASSERT_EQ(out.size(), 20u);
    EXPECT_NEAR(getFloat(out, 6), 0.060f, 1e-6);
}

TEST(DeviceParams, AppendsWhenMissing) {
    const Bytes in = concat({field3(), field7()});
    const Bytes out = withInterLensOffset(in, 1.0f);
    ASSERT_EQ(out.size(), in.size() + 5);
    EXPECT_TRUE(std::equal(in.begin(), in.end(), out.begin()));
    EXPECT_EQ(out[in.size()], 0x25);
    EXPECT_NEAR(getFloat(out, in.size() + 1), 0.001f, 1e-6);
}

TEST(DeviceParams, SkipsVarintAndLengthDelimited) {
    const Bytes field1{0x0A, 0x01, 'x'};  // field 1, string "x"
    const Bytes field11{0x58, 0x01};      // field 11, varint 1
    const Bytes in = concat({field1, field11, field3(), field4(0.064f), field7()});
    const Bytes out = withInterLensOffset(in, 2.0f);
    ASSERT_EQ(out.size(), in.size());
    const size_t f4 = field1.size() + field11.size() + 5;
    EXPECT_EQ(out[f4], 0x25);
    EXPECT_NEAR(getFloat(out, f4 + 1), 0.066f, 1e-6);
    for (size_t i = 0; i < in.size(); ++i) {
        if (i > f4 && i <= f4 + 4) continue;
        EXPECT_EQ(out[i], in[i]) << "byte " << i;
    }
}

TEST(DeviceParams, MalformedReturnsInput) {
    Bytes in = concat({field3(), field4(0.064f)});
    in.resize(in.size() - 2);  // truncate field 4's fixed32 payload
    EXPECT_EQ(withInterLensOffset(in, 2.0f), in);
}
