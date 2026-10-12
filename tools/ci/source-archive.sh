#!/usr/bin/env bash
# Builds the corresponding-source archive for a release (plans/RELEASE-HARDENING.md, D6/RH5):
# the tree at <ref> plus every submodule (recursively) at its pinned commit, and the pinned
# bundled-content SHAs. Checks the archive holds projectM's sources and the app build.
#   source-archive.sh <ref> <out.tar.gz>
set -euo pipefail
ref="${1:?ref}"; out="${2:?out.tar.gz}"
root="$(git rev-parse --show-toplevel)"
name="$(basename "$out" .tar.gz)"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
git -C "$root" archive --prefix="$name/" "$ref" | tar -x -C "$tmp"
# Submodules at the commits recorded by <ref>, recursively (needs them checked out at those commits).
git -C "$root" submodule foreach --quiet --recursive \
  'git archive --prefix="'"$name"'/$displaypath/" HEAD | tar -x -C "'"$tmp"'"'
{
  echo "Koggles corresponding source for $ref ($(git -C "$root" rev-parse "$ref^{commit}"))"
  git -C "$root" submodule status --recursive
  grep -E '^(classicSha|cotcSha|texturePackSha) =' "$root/gradle/libs.versions.toml"
} > "$tmp/$name/content-shas.txt"
mkdir -p "$(dirname "$out")"
tar -czf "$out" -C "$tmp" "$name"
for must in "$name/third_party/projectm/src/libprojectM/ProjectM.cpp" "$name/app/build.gradle.kts" "$name/content-shas.txt"; do
  tar -tzf "$out" "$must" > /dev/null 2>&1 || { echo "source archive is missing $must" >&2; exit 1; }
done
echo "source archive OK: $out ($(du -h "$out" | cut -f1))"
