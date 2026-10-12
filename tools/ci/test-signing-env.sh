#!/usr/bin/env bash
# Signing credentials reach Gradle unchanged through environment variables, including characters
# java.util.Properties would mangle (leading space, backslash, '=', ':', '#', '!'). Non-ASCII is not
# tested: Java's PKCS12 keystores reject non-ASCII passwords outright.
# Builds a release with a throwaway key; slow (a release build).
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
pw=' p\a=s:s#w!rd\'
alias_name='koggles-test'
keytool -genkeypair -keystore "$tmp/t.jks" -storetype PKCS12 -alias "$alias_name" -keyalg RSA -keysize 2048 \
  -validity 2 -storepass "$pw" -keypass "$pw" -dname "CN=Koggles signing-env test (throwaway)" > "$tmp/keytool.log" 2>&1 \
  || { cat "$tmp/keytool.log"; echo "FAIL: keytool"; exit 1; }
[ -s "$tmp/t.jks" ] || { cat "$tmp/keytool.log"; echo "FAIL: keytool wrote no keystore"; exit 1; }
want="$(keytool -list -v -keystore "$tmp/t.jks" -storepass "$pw" -alias "$alias_name" | awk '/SHA256:/{print $2}' | tr -d ':' | tr 'A-F' 'a-f')"
[ -f "$root/keystore.properties" ] && { echo "move keystore.properties aside first" >&2; exit 2; }
cd "$root"
rm -f app/build/outputs/apk/release/app-release.apk
KOGGLES_KEYSTORE_FILE="$tmp/t.jks" KOGGLES_STORE_PASSWORD="$pw" KOGGLES_KEY_ALIAS="$alias_name" KOGGLES_KEY_PASSWORD="$pw" \
  ./gradlew -q :app:assembleRelease > "$tmp/build.log" 2>&1 || { tail -20 "$tmp/build.log"; echo "FAIL: build"; exit 1; }
apk=app/build/outputs/apk/release/app-release.apk
[ -f "$apk" ] || { echo "FAIL: no signed app-release.apk (environment credentials ignored)"; exit 1; }
got="$("${ANDROID_HOME}/build-tools/36.0.0/apksigner" verify --print-certs "$apk" | awk '/certificate SHA-256 digest/{print $NF; exit}')"
[ "$got" = "$want" ] || { echo "FAIL: signed by $got, expected $want"; exit 1; }
echo "PASS: signed with the environment credentials ($want)"
