#!/usr/bin/env bash
# Prints the highest versionCode among published releases (0 if there are none)
# (plans/RELEASE-HARDENING.md, RH6). Each code is derived from the release's tag name with
# version-from-tag.sh — the same canonical rule that built it — never from release notes.
# Needs GH_TOKEN and GH_REPO. Fails closed on any query failure or any non-version tag.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
tags="$(gh release list --limit 1000 --json tagName --jq '.[].tagName')"   # checked: set -e
max=0
while read -r tag; do
  [ -n "$tag" ] || continue
  read -r _ code < <("$here/version-from-tag.sh" "$tag") \
    || { echo "published release '$tag' is not a canonical vX.Y.Z tag" >&2; exit 1; }
  [ -n "${code:-}" ] || { echo "published release '$tag' is not a canonical vX.Y.Z tag" >&2; exit 1; }
  if [ "$code" -gt "$max" ]; then max="$code"; fi
done <<<"$tags"
echo "$max"
