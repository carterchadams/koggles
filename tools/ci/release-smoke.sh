#!/usr/bin/env bash
# Release smoke test (plans/RELEASE-HARDENING.md, Task R1): proves on a device or emulator that a
# minified build's native→Kotlin upcalls work, by watching one auto-advance driven by them.
#
#   tools/ci/release-smoke.sh <apk>          (adb must reach exactly one device)
#
# PASS requires, in the app process's log:
#   (a) "upcalls ready 3/3";
#   (b) "KogglesUp loaded N", then "KogglesUp switch N", then "load-begin N+1", then "KogglesUp loaded N+1";
#   (c) no FATAL EXCEPTION / NoSuchMethodError / ClassNotFoundException / UnsatisfiedLinkError / "upcalls:" error.
set -euo pipefail

apk="${1:?usage: release-smoke.sh <apk>}"
pkg=dev.koggles
timeout_s="${SMOKE_TIMEOUT_S:-90}"
log="$(mktemp)"
trap 'rm -f "$log"' EXIT

adb uninstall "$pkg" >/dev/null 2>&1 || true   # fresh settings: unlocked, 30 s auto-advance
adb install "$apk" >/dev/null
adb shell pm grant "$pkg" android.permission.RECORD_AUDIO
adb logcat -c
adb shell am start -n "$pkg/.MainActivity" >/dev/null

pid=""
for _ in $(seq 1 50); do
  pid="$(adb shell pidof "$pkg" | tr -d '\r' || true)"
  [ -n "$pid" ] && break
  sleep 0.2
done
[ -n "$pid" ] || { echo "FAIL: $pkg did not start"; exit 1; }

verdict() {
  python3 - "$log" <<'PY'
import re, sys
text = open(sys.argv[1], errors="replace").read()
bad = re.search(r"FATAL EXCEPTION|NoSuchMethodError|ClassNotFoundException|UnsatisfiedLinkError|upcalls: ", text)
if bad:
    print("FAIL: " + text[max(0, bad.start() - 200): bad.end() + 400]); sys.exit(1)
if "upcalls ready 3/3" not in text:
    sys.exit(2)  # not yet
events = re.findall(r"(KogglesUp: loaded|KogglesUp: switch|KogglesG1: load-begin) (\d+)", text)
seq = [(kind.split(": ")[1], int(n)) for kind, n in events]
for i, (k, n) in enumerate(seq):
    if k != "loaded":
        continue
    want = [("switch", n), ("load-begin", n + 1), ("loaded", n + 1)]
    rest = iter(seq[i + 1:])
    if all(any(e == w for e in rest) for w in want):
        print(f"PASS: upcalls ready 3/3; loaded {n} -> switch {n} -> load-begin {n+1} -> loaded {n+1}")
        sys.exit(0)
sys.exit(2)
PY
}

deadline=$((SECONDS + timeout_s))
while [ "$SECONDS" -lt "$deadline" ]; do
  adb logcat -d --pid="$pid" > "$log" 2>/dev/null || true
  set +e; verdict; rc=$?; set -e
  [ "$rc" -eq 0 ] && exit 0
  [ "$rc" -eq 1 ] && exit 1
  sleep 2
done
echo "FAIL: no upcall-driven auto-advance within ${timeout_s}s"
grep -E "upcalls|KogglesUp|load-begin|AndroidRuntime" "$log" | tail -20 || true
exit 1
