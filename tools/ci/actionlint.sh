#!/usr/bin/env bash
# Runs actionlint, pinned by version and SHA-256 (plans/RELEASE-HARDENING.md, Task R5).
set -euo pipefail
version=1.7.12
sha256=8aca8db96f1b94770f1b0d72b6dddcb1ebb8123cb3712530b08cc387b349a3d8   # linux_amd64 tarball
root="$(cd "$(dirname "$0")/../.." && pwd)"
dir="$root/build/tools/actionlint-$version"
bin="$dir/actionlint"
if [ ! -x "$bin" ]; then
  mkdir -p "$dir"
  tgz="$dir/actionlint.tar.gz"
  curl -fsSL -o "$tgz" "https://github.com/rhysd/actionlint/releases/download/v$version/actionlint_${version}_linux_amd64.tar.gz"
  echo "$sha256  $tgz" | sha256sum -c --quiet -
  tar -xzf "$tgz" -C "$dir" actionlint
  rm -f "$tgz"
fi
cd "$root"
"$bin" -no-color "$@"
