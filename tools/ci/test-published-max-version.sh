#!/usr/bin/env bash
# Tests tools/ci/published-max-version.sh against a fake `gh` (plans/RELEASE-HARDENING.md, RH6):
# it must fail closed on any query failure, never report 0 by accident.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
pass=0; fail=0
fake() { # mode
  cat > "$tmp/gh" <<FAKE
#!/usr/bin/env bash
mode="$1"
if [ "\$1 \$2" = "release list" ]; then
  [ "\$mode" = list-fails ] && { echo "HTTP 502" >&2; exit 1; }
  [ "\$mode" = none ] && exit 0
  printf 'v0.1.0\nv0.2.0\nv0.10.0\n'; exit 0
fi
if [ "\$1 \$2" = "release view" ]; then
  [ "\$mode" = view-fails ] && [ "\$3" = v0.2.0 ] && { echo "HTTP 500" >&2; exit 1; }
  case "\$3" in
    v0.1.0) echo 'Koggles MVP'; echo; echo 'versionCode: 100' ;;
    v0.2.0) echo 'versionCode: 200' ;;
    v0.10.0) [ "\$mode" = no-code ] && echo 'notes without a code' || echo 'versionCode: 1000' ;;
  esac
  exit 0
fi
exit 3
FAKE
  chmod +x "$tmp/gh"
}
check() { # name mode expected-out-or-"fail"
  fake "$2"
  local out rc; out="$(PATH="$tmp:$PATH" "$here/published-max-version.sh" 2>/dev/null)"; rc=$?
  if [ "$3" = fail ]; then
    if [ "$rc" -ne 0 ]; then pass=$((pass+1)); else fail=$((fail+1)); echo "FAIL $1: expected failure, got rc=0 out='$out'"; fi
  else
    if [ "$rc" -eq 0 ] && [ "$out" = "$3" ]; then pass=$((pass+1)); else fail=$((fail+1)); echo "FAIL $1: expected '$3', got rc=$rc out='$out'"; fi
  fi
}
check max-of-three ok 1000
check no-releases none 0
check list-fails list-fails fail
check view-fails view-fails fail
check release-without-code no-code fail
echo "published-max-version: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
