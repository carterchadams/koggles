#!/usr/bin/env bash
# Lays out a release's files under <out-dir> (the release workflow's "Assemble release files" step):
#   koggles-<tag>.apk, koggles-<tag>.apk.sha256, koggles-<tag>-mapping.txt, koggles-<tag>-source.tar.gz,
#   and a fixed-name copy for the README's direct download link, koggles-current.apk with its own
#   koggles-current.apk.sha256 (each checksum names its own file, so `sha256sum -c` works on either).
#   assemble-release-files.sh <app-release.apk> <mapping.txt> <source.tar.gz> <vX.Y.Z> <out-dir>
set -euo pipefail
apk="${1:?apk}"; mapping="${2:?mapping}"; source="${3:?source.tar.gz}"; tag="${4:?tag}"; out="${5:?out-dir}"
[[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "assemble-release-files: tag '$tag' is not vX.Y.Z" >&2; exit 64; }
for f in "$apk" "$mapping" "$source"; do [ -f "$f" ] || { echo "assemble-release-files: missing input $f" >&2; exit 66; }; done
mkdir -p "$out"
base="koggles-$tag"
cp "$apk" "$out/$base.apk"
cp "$mapping" "$out/$base-mapping.txt"
cp "$source" "$out/$base-source.tar.gz"
cp "$out/$base.apk" "$out/koggles-current.apk"
(cd "$out" && sha256sum "$base.apk" > "$base.apk.sha256" && sha256sum koggles-current.apk > koggles-current.apk.sha256)
ls -1 "$out"
