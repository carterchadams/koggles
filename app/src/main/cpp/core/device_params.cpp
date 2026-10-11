#include "device_params.h"

#include <cstring>

namespace {

constexpr uint32_t kInterLensField = 4;
constexpr uint8_t kInterLensTag = (kInterLensField << 3) | 5;  // 0x25

bool readVarint(const std::vector<uint8_t>& b, size_t& pos, uint64_t& out) {
    out = 0;
    for (int shift = 0; shift < 64; shift += 7) {
        if (pos >= b.size()) return false;
        const uint8_t byte = b[pos++];
        out |= static_cast<uint64_t>(byte & 0x7F) << shift;
        if ((byte & 0x80) == 0) return true;
    }
    return false;
}

bool skip(const std::vector<uint8_t>& b, size_t& pos, uint64_t n) {
    if (n > b.size() - pos) return false;
    pos += static_cast<size_t>(n);
    return true;
}

float loadFloatLE(const uint8_t* p) {
    const uint32_t u = static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) |
                       (static_cast<uint32_t>(p[2]) << 16) | (static_cast<uint32_t>(p[3]) << 24);
    float f;
    std::memcpy(&f, &u, sizeof f);
    return f;
}

void storeFloatLE(uint8_t* p, float f) {
    uint32_t u;
    std::memcpy(&u, &f, sizeof u);
    for (int i = 0; i < 4; ++i) p[i] = static_cast<uint8_t>(u >> (8 * i));
}

}  // namespace

std::vector<uint8_t> withInterLensOffset(const std::vector<uint8_t>& encoded, float offsetMm) {
    // Walk every field; remember the payload offset of the last field 4
    // (protobuf "last one wins" semantics for scalar fields).
    bool found = false;
    size_t payload = 0;
    size_t pos = 0;
    while (pos < encoded.size()) {
        uint64_t tag;
        if (!readVarint(encoded, pos, tag)) return encoded;
        const uint64_t field = tag >> 3;
        const uint32_t wireType = static_cast<uint32_t>(tag & 7);
        if (field == 0) return encoded;
        uint64_t len;
        switch (wireType) {
            case 0:
                if (!readVarint(encoded, pos, len)) return encoded;
                break;
            case 1:
                if (!skip(encoded, pos, 8)) return encoded;
                break;
            case 2:
                if (!readVarint(encoded, pos, len) || !skip(encoded, pos, len)) return encoded;
                break;
            case 5:
                if (field == kInterLensField) {
                    found = true;
                    payload = pos;
                }
                if (!skip(encoded, pos, 4)) return encoded;
                break;
            default:
                return encoded;  // groups / unknown wire types are unsupported
        }
        if (field == kInterLensField && wireType != 5) return encoded;
    }

    std::vector<uint8_t> out = encoded;
    if (found) {
        storeFloatLE(&out[payload], loadFloatLE(&out[payload]) + offsetMm / 1000.0f);
    } else {
        out.push_back(kInterLensTag);
        out.resize(out.size() + 4);
        storeFloatLE(&out[out.size() - 4], offsetMm / 1000.0f);
    }
    return out;
}
