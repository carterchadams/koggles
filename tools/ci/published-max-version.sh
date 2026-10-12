#!/usr/bin/env bash
# Prints the highest versionCode among published releases (0 if none), read from each release's
# notes line "versionCode: N" (plans/RELEASE-HARDENING.md, RH6). Needs GH_TOKEN and GH_REPO.
set -euo pipefail
max=0
while read -r tag; do
  [ -n "$tag" ] || continue
  code="$(gh release view "$tag" --json body --jq .body | grep -oE '^versionCode: [0-9]+' | awk '{print $2}' | head -1)"
  if [ -n "$code" ] && [ "$code" -gt "$max" ]; then max="$code"; fi
done < <(gh release list --limit 1000 --json tagName --jq '.[].tagName')
echo "$max"
