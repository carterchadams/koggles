#!/usr/bin/env bash
# Static verification of a release APK (plans/RELEASE-HARDENING.md, Task R3).
#   verify-release-apk.sh <apk> <mapping.txt> <versionName> <versionCode> <certSha256>
# Prints "PASS n <check>" / "FAIL n <check>: <why>" per check; exits 0 only if all 8 pass.
# Env: MAX_APK_MB (default 150), MIN_PRESETS (default 9000), BUILD_TOOLS (default
# $ANDROID_HOME/build-tools/36.0.0). Signer parsing lives in parse-signer.sh (tested with fixtures).
set -uo pipefail
apk="${1:?apk}"; mapping="${2:?mapping}"; want_name="${3:?versionName}"; want_code="${4:?versionCode}"; want_cert="${5:?certSha256}"
bt="${BUILD_TOOLS:-$ANDROID_HOME/build-tools/36.0.0}"
apkanalyzer="${APKANALYZER:-$ANDROID_HOME/cmdline-tools/latest/bin/apkanalyzer}"
failures=0
ok()  { echo "PASS $*"; }
bad() { echo "FAIL $1 $2: $3"; failures=$((failures + 1)); }

# 1 signature (v2 or v3) and 2 cert pin: always against the real APK (no test bypass).
here="$(cd "$(dirname "$0")" && pwd)"
signer_out="$(mktemp)"; trap 'rm -f "$signer_out"' EXIT
"$bt/apksigner" verify --verbose --print-certs "$apk" > "$signer_out" 2>&1; signer_rc=$?
# Fail closed: checks 1-2 count as passed only with the parser's explicit PASS lines and exit 0.
parsed="$("$here/parse-signer.sh" "$signer_out" "$signer_rc" "$want_cert" 2>&1)"; parse_rc=$?
echo "$parsed" | grep -E '^(PASS|FAIL) [12] ' || true
for check in "1 signature" "2 cert-pin"; do
  if [ "$parse_rc" -ne 0 ] || ! grep -qx "PASS $check" <<<"$parsed"; then
    grep -q "^FAIL $check" <<<"$parsed" || echo "FAIL $check: signer parser failed (rc=$parse_rc)"
    failures=$((failures + 1))
  fi
done

badging="$("$bt/aapt2" dump badging "$apk" 2>/dev/null)"
# 3 version
# Literal comparison of the package record's fields (no regex: '0.1.0' must not match '0x1x0').
pkg_line="$(grep -m1 '^package:' <<<"$badging")"
got_code="$(sed -nE "s/.* versionCode='([^']*)'.*/\1/p" <<<"$pkg_line")"
got_name="$(sed -nE "s/.* versionName='([^']*)'.*/\1/p" <<<"$pkg_line")"
if [ -n "$pkg_line" ] && [ "$got_code" = "$want_code" ] && [ "$got_name" = "$want_name" ]; then ok 3 version
else bad 3 version "got versionCode='$got_code' versionName='$got_name'"; fi
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
