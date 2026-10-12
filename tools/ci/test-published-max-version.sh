#!/usr/bin/env bash
# Tests tools/ci/published-max-version.sh against a fake `gh` (it guards version ordering across releases):
# versionCodes come from the published tag names (canonical vX.Y.Z), never from release notes;
# it must fail closed on any query failure or non-version tag, never report 0 by accident.
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
  [ "\$mode" = bad-tag ] && { printf 'v0.1.0\nnightly\n'; exit 0; }
  printf 'v0.1.0\nv0.2.0\nv0.10.0\n'
  # Drafts (never shipped) are listed only when --exclude-drafts is missing.
  case " \$* " in *" --exclude-drafts "*) ;; *) printf 'v1.0.0\ndraft-junk\n' ;; esac
  exit 0
fi
if [ "\$1 \$2" = "release view" ]; then
  # Notes are never consulted; a misleading body must not matter.
  echo 'versionCode: 1'; echo 'versionCode: 99999999999999999999'; exit 0
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
check non-version-tag bad-tag fail
echo "published-max-version: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
