#!/usr/bin/env bash
# Static verification of a release APK (plans/RELEASE-HARDENING.md, Task R3).
#   verify-release-apk.sh <apk> <mapping.txt> <versionName> <versionCode> <certSha256>
# Prints "PASS n <check>" / "FAIL n <check>: <why>" per check; exits 0 only if all 8 pass.
# Env: MAX_APK_MB (default 15), ALLOWLIST (default app/bundled-presets.txt), BUILD_TOOLS (default
# $ANDROID_HOME/build-tools/36.0.0). Signer parsing lives in parse-signer.sh (tested with fixtures).
set -uo pipefail
apk="${1:?apk}"; mapping="${2:?mapping}"; want_name="${3:?versionName}"; want_code="${4:?versionCode}"; want_cert="${5:?certSha256}"
bt="${BUILD_TOOLS:-${ANDROID_HOME:-/missing-android-home}/build-tools/36.0.0}"
apkanalyzer="${APKANALYZER:-${ANDROID_HOME:-/missing-android-home}/cmdline-tools/latest/bin/apkanalyzer}"
if [ ! -x "$bt/apksigner" ] || [ ! -x "$bt/aapt2" ] || [ ! -x "$apkanalyzer" ]; then
  echo "FAIL 1 signature: build tools not found (apksigner/aapt2 in $bt, apkanalyzer) — set ANDROID_HOME"
  exit 1
fi
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
# 5 content and licences: the bundled presets are exactly the hash-pinned allowlist (one flat folder,
# same names, same SHA-256; plans/PRESET-PACK-CLASSIC.md), and the licence files are present.
allowlist="${ALLOWLIST:-$here/../../app/bundled-presets.txt}"
transitions="${TRANSITIONS_ALLOWLIST:-$here/../../app/bundled-transitions.txt}"
content="$(python3 - "$apk" "$allowlist" "$transitions" <<'PY' 2>&1
import hashlib, sys, zipfile
apk, allow, trans = sys.argv[1], sys.argv[2], sys.argv[3]
pack = "assets/presets/Koggles Starter Pack/"
sub = "! Transition/"  # the only subfolder allowed (transition presets)
want = {}
for listfile, prefix in ((allow, ""), (trans, sub)):
    for line in open(listfile, encoding="utf-8"):
        line = line.strip()
        if line and not line.startswith("#"):
            digest, path = line.split("  ", 1)
            want[prefix + path.rsplit("/", 1)[-1]] = digest
with zipfile.ZipFile(apk) as z:
    names = [n for n in z.namelist() if n.startswith("assets/presets/") and not n.endswith("/")]
    def placed(n):
        rel = n[len(pack):]
        return n.startswith(pack) and ("/" not in rel or (rel.startswith(sub) and "/" not in rel[len(sub):]))
    outside = sorted(n for n in names if not placed(n))
    have = {n[len(pack):]: hashlib.sha256(z.read(n)).hexdigest() for n in names if n not in outside}
missing = sorted(set(want) - set(have))
extra = sorted(set(have) - set(want))
changed = sorted(n for n in set(want) & set(have) if want[n] != have[n])
if missing or extra or changed or outside:
    print(f"missing={missing[:3]} extra={extra[:3]} changed={changed[:3]} outside-pack={outside[:3]}")
    sys.exit(1)
print(len(have))
PY
)"; content_rc=$?
lic_missing=""
for f in assets/licenses/projectM-LGPL-2.1.txt assets/licenses/cardboard-Apache-2.0.txt; do
  grep -qx "$f" <<<"$entries" || lic_missing="$lic_missing $f"
done
if [ "$content_rc" -eq 0 ] && [ -z "$lic_missing" ]; then ok 5 content "($content presets, allowlist matched)"
else bad 5 content "presets: ${content} licences-missing:${lic_missing:- none}"; fi
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
if [ "$mb" -lt "${MAX_APK_MB:-15}" ]; then ok 8 size "(${mb} MB)"; else bad 8 size "${mb} MB >= ${MAX_APK_MB:-15} MB"; fi

[ "$failures" -eq 0 ]
