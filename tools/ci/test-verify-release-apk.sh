#!/usr/bin/env bash
# Tests tools/ci/verify-release-apk.sh: the signed release passes 8/8, and each check fails on a
# real input built to break exactly it (plans/RELEASE-HARDENING.md, Task R3).
#   test-verify-release-apk.sh <signed-release.apk> <mapping.txt> <versionName> <versionCode> <cert> <debug.apk> <no-keep-rules.apk>
# Static-only mode (CI without device artifacts): test-verify-release-apk.sh --fixtures
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
v="$here/verify-release-apk.sh"
pass=0; fail=0
expect() { # name expected-substring command...
  local name="$1" want="$2"; shift 2
  local out; out="$("$@" 2>&1)"
  if grep -qF "$want" <<<"$out"; then pass=$((pass+1)); else fail=$((fail+1)); echo "FAIL $name: wanted '$want' in:"; echo "$out" | sed 's/^/    /'; fi
}
fx="$here/fixtures"
p="$here/parse-signer.sh"
expect parse-signed   "PASS 1 signature" "$p" "$fx/apksigner-36.0.0-signed.txt" 0 f4994d43451a2e2ff5c684bea3b464e60cd2574406203b75c3253e8939ccfd1a
expect parse-pin      "PASS 2 cert-pin"  "$p" "$fx/apksigner-36.0.0-signed.txt" 0 F4:99:4D:43:45:1A:2E:2F:F5:C6:84:BE:A3:B4:64:E6:0C:D2:57:44:06:20:3B:75:C3:25:3E:89:39:CC:FD:1A
expect parse-unsigned "FAIL 1 signature" "$p" "$fx/apksigner-36.0.0-unsigned.txt" 1 00
expect parse-wrongpin "FAIL 2 cert-pin"  "$p" "$fx/apksigner-36.0.0-signed.txt" 0 deadbeef
expect parse-rc-nonzero "FAIL 1 signature" "$p" "$fx/apksigner-36.0.0-signed.txt" 1 f4994d43451a2e2ff5c684bea3b464e60cd2574406203b75c3253e8939ccfd1a
# The production verifier has no test bypass: it fails on a missing APK even if the old hook is set.
expect no-bypass      "FAIL 1 signature" env SIGNER_OUTPUT_FILE="$fx/apksigner-36.0.0-signed.txt" "$here/verify-release-apk.sh" /nonexistent.apk /nonexistent.txt 0.1.0 1 f4994d43451a2e2ff5c684bea3b464e60cd2574406203b75c3253e8939ccfd1a
if [ "${1:-}" != "--fixtures" ]; then
  apk="$1"; map="$2"; name="$3"; code="$4"; cert="$5"; debug="$6"; norules="$7"
  tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
  out="$("$v" "$apk" "$map" "$name" "$code" "$cert")"; rc=$?
  if [ "$rc" -eq 0 ] && [ "$(grep -c '^PASS' <<<"$out")" -eq 8 ]; then pass=$((pass+1)); else fail=$((fail+1)); echo "FAIL positive (rc=$rc):"; echo "$out"; fi
  expect wrong-cert    "FAIL 2 cert-pin"     "$v" "$apk" "$map" "$name" "$code" deadbeef
  # Fail closed: a missing or broken signer parser must fail the gate, not skip checks 1-2.
  mkdir -p "$tmp/noparser"; cp "$v" "$tmp/noparser/"
  out_np="$("$tmp/noparser/verify-release-apk.sh" "$apk" "$map" "$name" "$code" "$cert" 2>&1)"; rc_np=$?
  if [ "$rc_np" -ne 0 ] && grep -q "FAIL 1 signature" <<<"$out_np"; then pass=$((pass+1)); else fail=$((fail+1)); echo "FAIL parser-missing: rc=$rc_np"; echo "$out_np" | sed 's/^/    /'; fi
  expect wrong-version "FAIL 3 version"      "$v" "$apk" "$map" "$name" 999 "$cert"
  # Literal comparison: regex metacharacters in the expected name must not match (gate finding).
  expect regex-name    "FAIL 3 version"      "$v" "$apk" "$map" "${name%.*}.." "$code" "$cert"
  cp "$apk" "$tmp/nolib.apk"; zip -qd "$tmp/nolib.apk" 'lib/arm64-v8a/libprojectM-4.so'
  expect no-lib        "FAIL 4 native-libs"  "$v" "$tmp/nolib.apk" "$map" "$name" "$code" "$cert"
  cp "$apk" "$tmp/nopresets.apk"; zip -qd "$tmp/nopresets.apk" 'assets/presets/*'
  expect no-presets    "FAIL 5 content"      "$v" "$tmp/nopresets.apk" "$map" "$name" "$code" "$cert"
  expect no-keep-rules "FAIL 6 jni-upcalls"  "$v" "$norules" "$map" "$name" "$code" "$cert"
  expect debuggable    "FAIL 7 not-debuggable" "$v" "$debug" "$map" "$name" "$code" "$cert"
  expect too-big       "FAIL 8 size"         env MAX_APK_MB=1 "$v" "$apk" "$map" "$name" "$code" "$cert"
fi
echo "verify-release-apk: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
