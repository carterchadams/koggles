#!/usr/bin/env bash
# Release smoke test (plans/RELEASE-HARDENING.md, Task R1): proves on a device or emulator that a
# minified build's native→Kotlin upcalls work, by watching one auto-advance driven by them.
#
#   tools/ci/release-smoke.sh <apk>          (adb must reach exactly one device)
#   KOGGLES_PKG=dev.koggles.smoke tools/ci/release-smoke.sh <suffixed apk>
#     tests a suffixed build (-PkogglesAppIdSuffix) without touching an installed dev.koggles.
#
# PASS requires, in the captured log (see smoke-verdict.py), and the same app pid alive after a
# settle period:
#   (a) "upcalls ready 3/3";
#   (b) "KogglesUp loaded N", then "KogglesUp switch N", then "load-begin N+1", then "KogglesUp loaded N+1";
#   (c) no FATAL EXCEPTION / NoSuchMethodError / ClassNotFoundException / UnsatisfiedLinkError / "upcalls:" error.
set -euo pipefail

apk="${1:?usage: release-smoke.sh <apk>}"
pkg="${KOGGLES_PKG:-dev.koggles}"   # only this package is uninstalled, installed and launched
timeout_s="${SMOKE_TIMEOUT_S:-90}"
log="$(mktemp)"
trap 'rm -f "$log"' EXIT

adb uninstall "$pkg" >/dev/null 2>&1 || true   # fresh settings: unlocked, 30 s auto-advance
adb install "$apk" >/dev/null
adb shell pm grant "$pkg" android.permission.RECORD_AUDIO
adb logcat -c
adb shell am start -n "$pkg/dev.koggles.MainActivity" >/dev/null   # the code namespace stays dev.koggles

pid=""
for _ in $(seq 1 50); do
  pid="$(adb shell pidof "$pkg" | tr -d '\r' || true)"
  [ -n "$pid" ] && break
  sleep 0.2
done
[ -n "$pid" ] || { echo "FAIL: $pkg did not start"; exit 1; }

here="$(cd "$(dirname "$0")" && pwd)"
settle_s="${SMOKE_SETTLE_S:-5}"

# The app's own lines plus crash evidence that other processes log about it (libc fatal signal,
# tombstone writer, ActivityManager), from the main, system and crash buffers.
capture() {
  adb logcat -d -b main,system,crash 2>/dev/null \
    | awk -v pid="$pid" '$3 == pid || /dev\.koggles/ || / F DEBUG /' > "$log" || true
}

deadline=$((SECONDS + timeout_s))
while [ "$SECONDS" -lt "$deadline" ]; do
  capture
  set +e; python3 "$here/smoke-verdict.py" "$log"; rc=$?; set -e
  [ "$rc" -eq 1 ] && exit 1
  if [ "$rc" -eq 0 ]; then
    # The sequence alone isn't enough: the app must survive a settle period, same process.
    sleep "$settle_s"
    capture
    set +e; python3 "$here/smoke-verdict.py" "$log" > /dev/null; rc=$?; set -e
    now="$(adb shell pidof "$pkg" | tr -d '\r' || true)"
    if [ "$rc" -eq 0 ] && [ "$now" = "$pid" ]; then
      echo "PASS: alive after ${settle_s}s settle (pid $pid)"
      exit 0
    fi
    echo "FAIL: app did not survive the settle period (pid $pid -> '${now}', verdict rc=$rc)"
    grep -E "Fatal|DEBUG|died|FATAL|upcalls" "$log" | tail -20 || true
    exit 1
  fi
  sleep 2
done
echo "FAIL: no upcall-driven auto-advance within ${timeout_s}s"
grep -E "upcalls|KogglesUp|load-begin|AndroidRuntime" "$log" | tail -20 || true
exit 1
