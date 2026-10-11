# Source this file to set up the Koggles build environment:
#   source tools/env.sh
# Override JAVA_HOME / ANDROID_HOME beforehand to use other locations.

if [ -z "${JAVA_HOME:-}" ] || ! "$JAVA_HOME/bin/java" -version 2>&1 | grep -q '"21\.'; then
  if command -v mise >/dev/null 2>&1 && mise where java@temurin-21 >/dev/null 2>&1; then
    JAVA_HOME="$(mise where java@temurin-21)"
  fi
fi
export JAVA_HOME
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmake/3.22.1/bin:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
