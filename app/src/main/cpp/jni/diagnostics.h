#pragma once

#include <string>

// projectM preset/shader diagnostics for test builds (-PkogglesShaderDiagnostics, which turns on
// projectM's own ENABLE_DEBUG_MILKDROP_PRESET). Release and normal debug builds compile this as a
// no-op: enabled() is false and take() returns "".
namespace koggles::diag {

bool enabled();

// Everything projectM wrote to stderr since the last call, fully drained; then clears it.
std::string take();

}  // namespace koggles::diag
