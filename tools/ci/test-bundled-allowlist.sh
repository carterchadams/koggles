#!/usr/bin/env bash
# Preset plan P1 (metric M3): the build stages exactly the hash-pinned allowlist, and fails naming
# the entry when an allowlisted upstream file is missing or its content changed.
#   tools/ci/test-bundled-allowlist.sh        (repo root, Android SDK environment loaded)
set -uo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"
list=app/bundled-presets.txt
tlist=app/bundled-transitions.txt
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
fail=0
pass() { echo "PASS $*"; }
bad() { echo "FAIL $*"; fail=1; }
sync() { # presets-allowlist [transitions-allowlist]
  ./gradlew -q --console=plain :app:syncBundledContent -PkogglesPresetAllowlist="$1" \
    -PkogglesTransitionAllowlist="${2:-$root/$tlist}" >"$tmp/out" 2>&1
}
entries() { grep -vE '^[[:space:]]*(#|$)' "$1"; }

# 1. The real allowlist stages exactly its entries, and nothing else.
if sync "$root/$list"; then
  want=$(( $(entries "$list" | wc -l) + $(entries "$tlist" | wc -l) ))
  got=$(find app/build/generated/bundled/presets -type f -name '*.milk' | wc -l)
  trans=$(find "app/build/generated/bundled/presets/Koggles Starter Pack/! Transition" -type f -name '*.milk' 2>/dev/null | wc -l)
  if [ "$got" -eq "$want" ] && [ "$trans" -eq "$(entries "$tlist" | wc -l)" ]; then pass "real allowlists stage $got presets ($trans transitions)"
  else bad "staged $got presets ($trans transitions); allowlists have $want"; fi
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

# 4. A transition path missing upstream fails the build and names the path.
{ cat "$tlist"; printf '%064d  %s\n' 0 "! Transition/Koggles - No Such Transition.milk"; } > "$tmp/tmissing.txt"
if sync "$root/$list" "$tmp/tmissing.txt"; then bad "missing transition: build succeeded"
elif grep -qF "Koggles - No Such Transition.milk" "$tmp/out"; then pass "missing transition fails, naming it"
else bad "missing transition: the failure does not name it: $(tail -3 "$tmp/out")"; fi

# 5. A changed transition hash fails the build and names the path.
tfirst=$(entries "$tlist" | head -1)
tpath="${tfirst#*  }"
{ grep -vxF -- "$tfirst" "$tlist"; printf '%s  %s\n' "$(printf 'f%.0s' $(seq 64))" "$tpath"; } > "$tmp/tbadhash.txt"
if sync "$root/$list" "$tmp/tbadhash.txt"; then bad "transition hash mismatch: build succeeded"
elif grep -qF -- "$tpath" "$tmp/out" && grep -qi "sha256" "$tmp/out"; then pass "transition hash mismatch fails, naming the path"
else bad "transition hash mismatch: the failure does not name it: $(tail -3 "$tmp/out")"; fi

sync "$root/$list" || true   # leave the staged assets matching the real allowlists
exit $fail
