#!/usr/bin/env bash
# Prints the highest versionCode among published releases (0 if there are none), read from each
# release's notes line "versionCode: N" (plans/RELEASE-HARDENING.md, RH6). Needs GH_TOKEN and
# GH_REPO. Fails closed: any query failure, or a release without a versionCode line, is an error.
set -euo pipefail
tags="$(gh release list --limit 1000 --json tagName --jq '.[].tagName')"   # checked: set -e
max=0
while read -r tag; do
  [ -n "$tag" ] || continue
  body="$(gh release view "$tag" --json body --jq .body)"
  code="$(grep -oE '^versionCode: [0-9]+' <<<"$body" | awk '{print $2}' | head -1)"
  [ -n "$code" ] || { echo "release $tag has no 'versionCode: N' line" >&2; exit 1; }
  if [ "$code" -gt "$max" ]; then max="$code"; fi
done <<<"$tags"
echo "$max"
