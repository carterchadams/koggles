#!/usr/bin/env bash
# Preset plan P1 (metric M3): the build stages exactly the hash-pinned allowlist, and fails naming
# the entry when an allowlisted upstream file is missing or its content changed.
#   tools/ci/test-bundled-allowlist.sh        (repo root, Android SDK environment loaded)
set -uo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"
list=app/bundled-presets.txt
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
fail=0
pass() { echo "PASS $*"; }
bad() { echo "FAIL $*"; fail=1; }
sync() { ./gradlew -q --console=plain :app:syncBundledContent -PkogglesPresetAllowlist="$1" >"$tmp/out" 2>&1; }
entries() { grep -vE '^[[:space:]]*(#|$)' "$1"; }

# 1. The real allowlist stages exactly its entries, and nothing else.
if sync "$root/$list"; then
  want=$(entries "$list" | wc -l)
  got=$(find app/build/generated/bundled/presets -type f -name '*.milk' | wc -l)
  if [ "$got" -eq "$want" ]; then pass "real allowlist stages $got presets"; else bad "staged $got presets, allowlist has $want"; fi
else
  bad "real allowlist failed: $(tail -3 "$tmp/out")"
fi

# 2. A path missing upstream fails the build and names the path.
{ cat "$list"; printf '%064d  %s\n' 0 "milkdrop/Koggles - No Such Preset.milk"; } > "$tmp/missing.txt"
if sync "$tmp/missing.txt"; then bad "missing path: build succeeded"
elif grep -qF "Koggles - No Such Preset.milk" "$tmp/out"; then pass "missing path fails, naming it"
else bad "missing path: the failure does not name it: $(tail -3 "$tmp/out")"; fi

# 3. A changed hash fails the build and names the path.
first=$(entries "$list" | head -1)
path="${first#*  }"
{ grep -vxF -- "$first" "$list"; printf '%s  %s\n' "$(printf 'f%.0s' $(seq 64))" "$path"; } > "$tmp/badhash.txt"
if sync "$tmp/badhash.txt"; then bad "hash mismatch: build succeeded"
elif grep -qF -- "$path" "$tmp/out" && grep -qi "sha256" "$tmp/out"; then pass "hash mismatch fails, naming the path"
else bad "hash mismatch: the failure does not name it: $(tail -3 "$tmp/out")"; fi

sync "$root/$list" || true   # leave the staged assets matching the real allowlist
exit $fail
