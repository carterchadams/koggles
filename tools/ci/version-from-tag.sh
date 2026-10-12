#!/usr/bin/env bash
# Prints "<versionName> <versionCode>" for a release tag, or exits 2. Only canonical tags are accepted
# so two spellings of one version can't produce the same versionCode.
#   Tag: vX.Y.Z, each part 0-99 in canonical form (no leading zeros); versionCode = X*10000 + Y*100 + Z.
set -euo pipefail
tag="${1:-}"
re='^v(0|[1-9][0-9]?)\.(0|[1-9][0-9]?)\.(0|[1-9][0-9]?)$'
if [[ ! "$tag" =~ $re ]]; then
  echo "invalid release tag '$tag': expected vX.Y.Z, each part 0-99 without leading zeros" >&2
  exit 2
fi
x="${BASH_REMATCH[1]}"; y="${BASH_REMATCH[2]}"; z="${BASH_REMATCH[3]}"
code=$((x * 10000 + y * 100 + z))
if [ "$code" -lt 1 ]; then
  echo "invalid release tag '$tag': versionCode must be at least 1" >&2
  exit 2
fi
echo "$x.$y.$z $code"
