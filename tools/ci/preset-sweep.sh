#!/usr/bin/env bash
# Runs the bundled-preset compile sweep (BundledPresetsCompileTest) at will, on the one device ANDROID_SERIAL names.
#   tools/ci/preset-sweep.sh [--shaders]
# The sweep loads every bundled preset, minutes on an emulator, so it is not part of the instrumented suite: the
# test skips itself unless the instrumentation argument sweep=1 is passed, which this script does. --shaders adds
# -PkogglesShaderDiagnostics so the shader gate (projectM's silent fallbacks) runs too. Like instrumented.sh:
# ANDROID_SERIAL is required and must be attached; off the emulator the build is suffixed (-PkogglesAppIdSuffix=.brand)
# so it installs beside, never over, the author's dev.koggles. KOGGLES_DRY_RUN=1 prints the Gradle arguments and exits.
set -euo pipefail
: "${ANDROID_SERIAL:?set ANDROID_SERIAL to the emulator or device serial}"
cd "$(dirname "$0")/../.."
shaders=0
for arg in "$@"; do
  case "$arg" in --shaders) shaders=1;; *) echo "preset-sweep: unknown argument '$arg' (only --shaders)" >&2; exit 64;; esac
done
args=(:app:connectedDebugAndroidTest
  -Pandroid.testInstrumentationRunnerArguments.class=dev.koggles.BundledPresetsCompileTest
  -Pandroid.testInstrumentationRunnerArguments.sweep=1)
[ "$shaders" -eq 0 ] || args+=(-PkogglesShaderDiagnostics)
case "$ANDROID_SERIAL" in emulator-*) ;; *) args+=(-PkogglesAppIdSuffix=.brand);; esac
if [ -n "${KOGGLES_DRY_RUN:-}" ]; then printf '%s\n' "${args[@]}"; exit 0; fi
state=$(adb -s "$ANDROID_SERIAL" get-state 2>/dev/null || true)
[ "$state" = device ] || { echo "preset-sweep: $ANDROID_SERIAL is not an attached device (state: ${state:-none})" >&2; exit 2; }
exec ./gradlew "${args[@]}"
