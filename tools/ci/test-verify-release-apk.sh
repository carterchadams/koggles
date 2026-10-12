#!/usr/bin/env bash
# Tests tools/ci/verify-release-apk.sh: the signed release passes 8/8, and each check fails on a
# real input built to break exactly it, so a check can't pass by accident.
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
  # Content is exactly the hash-pinned allowlist: one preset missing, one extra and one with changed
  # bytes must each fail on the content check alone. Each mutation is
  # re-signed with a throwaway key whose cert is passed in, so only check 5 can fail.
  bt="${BUILD_TOOLS:-${ANDROID_HOME:-/missing-android-home}/build-tools/36.0.0}"
  rs_pw='koggles-verifier-test'
  keytool -genkeypair -keystore "$tmp/rs.jks" -storetype PKCS12 -alias rs -keyalg RSA -keysize 2048 -validity 2 \
    -storepass "$rs_pw" -keypass "$rs_pw" -dname "CN=Koggles verifier test (throwaway)" > /dev/null 2>&1
  rs_cert="$(keytool -list -v -keystore "$tmp/rs.jks" -storepass "$rs_pw" -alias rs | awk '/SHA256:/{print $2}')"
  resign() { "$bt/zipalign" -f -p 4 "$1" "$2.aligned" && "$bt/apksigner" sign --ks "$tmp/rs.jks" --ks-pass "pass:$rs_pw" \
    --key-pass "pass:$rs_pw" --out "$2" "$2.aligned" && rm -f "$2.aligned"; }
  only5() { # name apk: exactly one FAIL line, and it is check 5
    local out; out="$("$v" "$2" "$map" "$name" "$code" "$rs_cert" 2>&1)"
    if [ "$(grep -c '^FAIL' <<<"$out")" -eq 1 ] && grep -q '^FAIL 5 content' <<<"$out"; then pass=$((pass+1))
    else fail=$((fail+1)); echo "FAIL $1: expected only check 5 to fail:"; echo "$out" | sed 's/^/    /'; fi
  }
  pk='assets/presets/Koggles Starter Pack'
  victim="$(unzip -Z1 "$apk" | grep -m1 -E "^$pk/[A-Za-z0-9 ._-]+\.milk$")"  # no zip wildcard characters
  resign "$apk" "$tmp/rs-base.apk"
  out_rs="$("$v" "$tmp/rs-base.apk" "$map" "$name" "$code" "$rs_cert" 2>&1)"
  if [ "$(grep -c '^PASS' <<<"$out_rs")" -eq 8 ]; then pass=$((pass+1)); else fail=$((fail+1)); echo "FAIL resigned-baseline:"; echo "$out_rs" | sed 's/^/    /'; fi
  cp "$apk" "$tmp/m-missing.apk"; zip -qd "$tmp/m-missing.apk" "$victim"
  resign "$tmp/m-missing.apk" "$tmp/m-missing.s.apk"; only5 content-missing "$tmp/m-missing.s.apk"
  mkdir -p "$tmp/x/$pk"; printf 'MILKDROP_PRESET_VERSION=201\n' > "$tmp/x/$pk/Koggles - Extra.milk"
  cp "$apk" "$tmp/m-extra.apk"; (cd "$tmp/x" && zip -q "$tmp/m-extra.apk" "$pk/Koggles - Extra.milk")
  resign "$tmp/m-extra.apk" "$tmp/m-extra.s.apk"; only5 content-extra "$tmp/m-extra.s.apk"
  mkdir -p "$tmp/c"; (cd "$tmp/c" && unzip -q "$apk" "$victim" && printf '\n// changed\n' >> "$victim")
  cp "$apk" "$tmp/m-changed.apk"; zip -qd "$tmp/m-changed.apk" "$victim"; (cd "$tmp/c" && zip -q "$tmp/m-changed.apk" "$victim")
  resign "$tmp/m-changed.apk" "$tmp/m-changed.s.apk"; only5 content-changed "$tmp/m-changed.s.apk"
  mkdir -p "$tmp/n/$pk/Other"; printf 'MILKDROP_PRESET_VERSION=201\n' > "$tmp/n/$pk/Other/Koggles - Nested.milk"
  cp "$apk" "$tmp/m-nested.apk"; (cd "$tmp/n" && zip -q "$tmp/m-nested.apk" "$pk/Other/Koggles - Nested.milk")
  resign "$tmp/m-nested.apk" "$tmp/m-nested.s.apk"; only5 content-nested-outside-transitions "$tmp/m-nested.s.apk"
  # H7: the presets ship with the projectM team's public-domain statement.
  cp "$apk" "$tmp/m-nostatement.apk"; zip -qd "$tmp/m-nostatement.apk" 'assets/licenses/koggles-starter-pack-presets.md'
  resign "$tmp/m-nostatement.apk" "$tmp/m-nostatement.s.apk"; only5 no-public-domain-statement "$tmp/m-nostatement.s.apk"
fi
echo "verify-release-apk: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
