#!/usr/bin/env bash
# Tests tools/ci/smoke-verdict.py against log fixtures: a PASS needs the full id-matched upcall
# sequence, and crashes, missing upcalls or an incomplete log must never pass.
#   exit codes: 0 pass, 1 fail, 2 not yet
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
pass=0; fail=0
seq_ok='10-11 17:30:08.338 1 1 I Koggles : upcalls ready 3/3
10-11 17:30:09.000 1 2 I KogglesUp: loaded 1
10-11 17:30:38.000 1 2 I KogglesUp: switch 1
10-11 17:30:38.100 1 2 I KogglesG1: load-begin 2
10-11 17:30:39.000 1 2 I KogglesUp: loaded 2'
case_() { # name expected-rc log-text
  printf '%s\n' "$3" > "$tmp/$1.log"
  python3 "$here/smoke-verdict.py" "$tmp/$1.log" > /dev/null; local rc=$?
  if [ "$rc" -eq "$2" ]; then pass=$((pass+1)); else fail=$((fail+1)); echo "FAIL $1: expected rc=$2 got $rc"; fi
}
case_ ok 0 "$seq_ok"
case_ not_ready 2 "${seq_ok%%$'\n'*}"
case_ zero_upcalls 1 '10-11 17:30:08.338 1 1 E Koggles : upcalls: method onPresetLoaded(J)V not found
10-11 17:30:08.338 1 1 I Koggles : upcalls ready 2/3'
case_ java_crash 1 "$seq_ok
10-11 17:30:40.000 1 1 E AndroidRuntime: FATAL EXCEPTION: main"
case_ native_signal 1 "$seq_ok
10-11 17:30:40.000 1 3 F libc    : Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0 in tid 3 (GLThread 12), pid 1 (dev.koggles)"
case_ tombstone 1 "$seq_ok
10-11 17:30:40.100 9 9 F DEBUG   : *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***"
case_ process_died 1 "$seq_ok
10-11 17:30:41.000 5 6 I ActivityManager: Process dev.koggles (pid 1) has died: fg  TOP"
case_ mismatched_ids 2 '10-11 17:30:08.338 1 1 I Koggles : upcalls ready 3/3
10-11 17:30:09.000 1 2 I KogglesUp: loaded 1
10-11 17:30:38.000 1 2 I KogglesUp: switch 4
10-11 17:30:38.100 1 2 I KogglesG1: load-begin 2
10-11 17:30:39.000 1 2 I KogglesUp: loaded 2'
echo "smoke-verdict: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
