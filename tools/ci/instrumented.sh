#!/usr/bin/env bash
# Runs the instrumented suite, or the named test classes, on the one device ANDROID_SERIAL names.
#   tools/ci/instrumented.sh [class[,class...]]
# ANDROID_SERIAL is required and must be an attached device: with two devices attached and no serial,
# Gradle would run on both. Off the emulator the build is suffixed (-PkogglesAppIdSuffix=.brand) so it
# installs beside, never over, the author's dev.koggles. KOGGLES_DRY_RUN=1 prints the Gradle arguments
# and exits without building (for the script's own tests).
set -euo pipefail
: "${ANDROID_SERIAL:?set ANDROID_SERIAL to the emulator or device serial}"
cd "$(dirname "$0")/../.."
[ $# -le 1 ] || { echo "instrumented: one argument at most, a comma-separated class list (got $#)" >&2; exit 64; }
args=(:app:connectedDebugAndroidTest)
[ $# -eq 0 ] || args+=("-Pandroid.testInstrumentationRunnerArguments.class=$1")
case "$ANDROID_SERIAL" in emulator-*) ;; *) args+=(-PkogglesAppIdSuffix=.brand);; esac
if [ -n "${KOGGLES_DRY_RUN:-}" ]; then printf '%s\n' "${args[@]}"; exit 0; fi
state=$(adb -s "$ANDROID_SERIAL" get-state 2>/dev/null || true)
[ "$state" = device ] || { echo "instrumented: $ANDROID_SERIAL is not an attached device (state: ${state:-none})" >&2; exit 2; }
exec ./gradlew "${args[@]}"
