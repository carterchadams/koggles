#include "device_params.h"

#include <cstring>

namespace {

constexpr uint32_t kInterLensField = 4;
constexpr uint8_t kInterLensTag = (kInterLensField << 3) | 5;  // 0x25
constexpr uint32_t kFovField = 5;
constexpr uint8_t kFovTag = (kFovField << 3) | 2;  // 0x2a, length-delimited (packed floats)

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

// Walks every field of [encoded]; calls onField(fieldNumber, wireType, start, end) for each. Returns false on a
// malformed encoding (the caller then returns the input unchanged).
template <typename F>
bool walkFields(const std::vector<uint8_t>& b, F onField) {
    size_t pos = 0;
    while (pos < b.size()) {
        const size_t start = pos;
        uint64_t tag;
        if (!readVarint(b, pos, tag)) return false;
        const uint64_t field = tag >> 3;
        const uint32_t wireType = static_cast<uint32_t>(tag & 7);
        if (field == 0) return false;
        uint64_t len;
        switch (wireType) {
            case 0:
                if (!readVarint(b, pos, len)) return false;
                break;
            case 1:
                if (!skip(b, pos, 8)) return false;
                break;
            case 2:
                if (!readVarint(b, pos, len) || !skip(b, pos, len)) return false;
                break;
            case 5:
                if (!skip(b, pos, 4)) return false;
                break;
            default:
                return false;  // groups / unknown wire types are unsupported
        }
        onField(field, wireType, start, pos);
    }
    return true;
}

std::vector<uint8_t> withFieldOfView(const std::vector<uint8_t>& encoded, float outer, float inner, float bottom, float top) {
    std::vector<uint8_t> out;
    out.reserve(encoded.size() + 18);
    bool wrongWire = false;
    const bool ok = walkFields(encoded, [&](uint64_t field, uint32_t wireType, size_t start, size_t end) {
        if (field == kFovField) {
            if (wireType != 2) wrongWire = true;
            return;  // dropped: one new field 5 is appended below
        }
        out.insert(out.end(), encoded.begin() + static_cast<std::ptrdiff_t>(start), encoded.begin() + static_cast<std::ptrdiff_t>(end));
    });
    if (!ok || wrongWire) return encoded;
    out.push_back(kFovTag);
    out.push_back(16);  // four floats
    for (float a : {outer, inner, bottom, top}) {
        out.resize(out.size() + 4);
        storeFloatLE(&out[out.size() - 4], a);
    }
    return out;
}

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
