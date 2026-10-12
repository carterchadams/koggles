#!/usr/bin/env bash
# A smoke run of a suffixed release build must launch and PASS without
# touching the installed dev.koggles app. A shim adb passes every command through except state
# changes aimed at exactly dev.koggles, which it blocks and logs.
#   test-release-smoke-isolation.sh <suffixed-release.apk> <its-package>
#   (adb reaches exactly one device, which has dev.koggles installed)
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
apk="${1:?apk}"; pkg="${2:?package, e.g. dev.koggles.smoke}"
real_adb="$(command -v adb)"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
mkdir "$tmp/bin"
cat > "$tmp/bin/adb" <<SHIM
#!/usr/bin/env bash
touches=0; change=0
for a in "\$@"; do
  case "\$a" in dev.koggles|dev.koggles/*) touches=1 ;; esac
  case "\$a" in uninstall|grant|revoke|clear|start|force-stop|kill|disable|disable-user) change=1 ;; esac
done
if [ "\$touches" = 1 ] && [ "\$change" = 1 ]; then echo "BLOCKED adb \$*" >> "$tmp/blocked"; exit 0; fi
exec "$real_adb" "\$@"
SHIM
chmod +x "$tmp/bin/adb"
installed() { "$real_adb" shell dumpsys package dev.koggles | tr -d '\r' | awk -F= '/firstInstallTime/{print $2; exit}'; }
before="$(installed)"
[ -n "$before" ] || { echo "FAIL: dev.koggles is not installed on the device (needed to prove isolation)"; exit 2; }
fail=0
PATH="$tmp/bin:$PATH" KOGGLES_PKG="$pkg" "$here/release-smoke.sh" "$apk" > "$tmp/smoke.log" 2>&1; rc=$?
if [ "$rc" -eq 0 ]; then echo "PASS smoke on $pkg"; else echo "FAIL smoke rc=$rc: $(tail -2 "$tmp/smoke.log" | tr '\n' ' ')"; fail=1; fi
if [ -s "$tmp/blocked" ]; then echo "FAIL the smoke tried to change dev.koggles:"; sed 's/^/    /' "$tmp/blocked"; fail=1; else echo "PASS no attempt to change dev.koggles"; fi
after="$(installed)"
if [ "$after" = "$before" ]; then echo "PASS dev.koggles untouched (firstInstallTime $before)"; else echo "FAIL dev.koggles changed: '$before' -> '$after'"; fail=1; fi
"$real_adb" uninstall "$pkg" > /dev/null 2>&1 || true   # remove only the suffixed test install
exit $fail
