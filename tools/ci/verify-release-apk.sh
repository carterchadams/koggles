#!/usr/bin/env bash
# Static verification of a release APK (plans/RELEASE-HARDENING.md, Task R3).
#   verify-release-apk.sh <apk> <mapping.txt> <versionName> <versionCode> <certSha256>
# Prints "PASS n <check>" / "FAIL n <check>: <why>" per check; exits 0 only if all 8 pass.
# Env: MAX_APK_MB (default 150), MIN_PRESETS (default 9000), BUILD_TOOLS (default
# $ANDROID_HOME/build-tools/36.0.0), SIGNER_OUTPUT_FILE (test hook: parse this file instead of
# running apksigner).
set -uo pipefail
apk="${1:?apk}"; mapping="${2:?mapping}"; want_name="${3:?versionName}"; want_code="${4:?versionCode}"; want_cert="${5:?certSha256}"
bt="${BUILD_TOOLS:-$ANDROID_HOME/build-tools/36.0.0}"
apkanalyzer="${APKANALYZER:-$ANDROID_HOME/cmdline-tools/latest/bin/apkanalyzer}"
failures=0
ok()  { echo "PASS $*"; }
bad() { echo "FAIL $1 $2: $3"; failures=$((failures + 1)); }

# 1 signature (v2 or v3) and 2 cert pin
if [ -n "${SIGNER_OUTPUT_FILE:-}" ]; then signer="$(cat "$SIGNER_OUTPUT_FILE")"; signer_rc=0
  grep -q '^DOES NOT VERIFY' <<<"$signer" && signer_rc=1
else signer="$("$bt/apksigner" verify --verbose --print-certs "$apk" 2>&1)"; signer_rc=$?; fi
if [ "$signer_rc" -eq 0 ] && grep -qE '^Verified using v(2|3) scheme \(APK Signature Scheme v(2|3)\): true' <<<"$signer"; then
  ok 1 signature
else bad 1 signature "apksigner rc=$signer_rc, no v2/v3 verification"; fi
norm() { tr -d ':' | tr '[:upper:]' '[:lower:]'; }
got_cert="$(grep -m1 '^Signer #1 certificate SHA-256 digest:' <<<"$signer" | awk '{print $NF}' | norm)"
if [ -n "$got_cert" ] && [ "$got_cert" = "$(norm <<<"$want_cert")" ]; then ok 2 cert-pin
else bad 2 cert-pin "signer '${got_cert:-none}' != expected"; fi

[ -n "${SIGNER_OUTPUT_FILE:-}" ] && { [ "$failures" -eq 0 ]; exit $?; }   # parser-only mode

badging="$("$bt/aapt2" dump badging "$apk" 2>/dev/null)"
# 3 version
if grep -q "versionCode='$want_code' versionName='$want_name'" <<<"$badging"; then ok 3 version
else bad 3 version "$(grep -o "versionCode='[^']*' versionName='[^']*'" <<<"$badging" | head -1)"; fi
# 4 native libraries
entries="$(unzip -Z1 "$apk" 2>/dev/null)"
missing=""
for lib in libkoggles.so libprojectM-4.so libGfxPluginCardboard.so libc++_shared.so; do
  grep -qx "lib/arm64-v8a/$lib" <<<"$entries" || missing="$missing $lib"
done
if [ -z "$missing" ]; then ok 4 native-libs; else bad 4 native-libs "missing$missing"; fi
# 5 content and licences
presets="$(grep -cE '^assets/presets/.*\.milk$' <<<"$entries")"
lic_missing=""
for f in assets/licenses/projectM-LGPL-2.1.txt assets/licenses/cardboard-Apache-2.0.txt; do
  grep -qx "$f" <<<"$entries" || lic_missing="$lic_missing $f"
done
if [ "$presets" -ge "${MIN_PRESETS:-9000}" ] && [ -z "$lic_missing" ]; then ok 5 content "($presets presets)"
else bad 5 content "presets=$presets licences-missing:${lic_missing:- none}"; fi
# 6 JNI upcalls survived R8 (dex is authoritative) and the bridge class kept its name (mapping)
dex="$("$apkanalyzer" dex packages --defined-only "$apk" 2>/dev/null)"
up_missing=""
for m in "void onSwitchRequested(boolean,long)" "void onPresetFailed(long,java.lang.String)" "void onPresetLoaded(long)"; do
  grep -qF "dev.koggles.NativeBridge $m" <<<"$dex" || up_missing="$up_missing [$m]"
done
if [ -z "$up_missing" ] && grep -qx 'dev.koggles.NativeBridge -> dev.koggles.NativeBridge:' "$mapping"; then ok 6 jni-upcalls
else bad 6 jni-upcalls "missing in dex:${up_missing:- none}"; fi
# 7 not debuggable
if grep -q '^application-debuggable' <<<"$badging"; then bad 7 not-debuggable "application-debuggable present"; else ok 7 not-debuggable; fi
# 8 size
mb=$(( $(stat -c %s "$apk") / 1024 / 1024 ))
if [ "$mb" -lt "${MAX_APK_MB:-150}" ]; then ok 8 size "(${mb} MB)"; else bad 8 size "${mb} MB >= ${MAX_APK_MB:-150} MB"; fi

[ "$failures" -eq 0 ]
