#!/usr/bin/env bash
# Tests tools/ci/version-from-tag.sh (plans/RELEASE-HARDENING.md, Task R2).
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
pass=0; fail=0
check() { # tag expected-output-or-"exit2"
  local out rc
  out="$("$here/version-from-tag.sh" "$1" 2>/dev/null)"; rc=$?
  if [ "$2" = "exit2" ]; then
    if [ "$rc" -eq 2 ]; then pass=$((pass+1)); else fail=$((fail+1)); echo "FAIL $1: expected exit 2, got rc=$rc out='$out'"; fi
  else
    if [ "$rc" -eq 0 ] && [ "$out" = "$2" ]; then pass=$((pass+1)); else fail=$((fail+1)); echo "FAIL $1: expected '$2', got rc=$rc out='$out'"; fi
  fi
}
check v0.1.0 "0.1.0 100"
check v1.2.3 "1.2.3 10203"
check v12.34.56 "12.34.56 123456"
check v99.99.99 "99.99.99 999999"
check v01.02.03 exit2
check v1.2 exit2
check v1.2.3-rc1 exit2
check v100.0.0 exit2
check 1.2.3 exit2
check v0.0.0 exit2
echo "version-from-tag: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
