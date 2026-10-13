#!/usr/bin/env bash
# Tests tools/ci/assemble-release-files.sh: six files, the fixed-name copy byte-identical to the versioned
# APK, both checksum files verifiable with sha256sum -c, the mapping and source archive present.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
pass=0; fail=0
ok() { pass=$((pass+1)); }
bad() { fail=$((fail+1)); echo "FAIL $*"; }
head -c 4096 /dev/urandom > "$tmp/app-release.apk"
printf 'dev.koggles.NativeBridge -> dev.koggles.NativeBridge:\n' > "$tmp/mapping.txt"
printf 'not really a tarball\n' > "$tmp/source.tar.gz"
out="$tmp/dist"
"$here/assemble-release-files.sh" "$tmp/app-release.apk" "$tmp/mapping.txt" "$tmp/source.tar.gz" v1.2.3 "$out" > "$tmp/log" 2>&1 || bad "assemble exited $? : $(cat "$tmp/log")"
for f in koggles-v1.2.3.apk koggles-v1.2.3.apk.sha256 koggles-v1.2.3-mapping.txt koggles-v1.2.3-source.tar.gz koggles-current.apk koggles-current.apk.sha256; do
  [ -f "$out/$f" ] && ok || bad "missing $f"
done
[ "$(ls "$out" | wc -l)" -eq 6 ] && ok || bad "expected exactly 6 files, got: $(ls "$out" | tr '\n' ' ')"
cmp -s "$tmp/app-release.apk" "$out/koggles-v1.2.3.apk" && ok || bad "versioned APK differs from the build"
cmp -s "$out/koggles-v1.2.3.apk" "$out/koggles-current.apk" && ok || bad "koggles-current.apk is not a byte copy of the versioned APK"
(cd "$out" && sha256sum -c --quiet koggles-v1.2.3.apk.sha256) && ok || bad "versioned checksum does not verify"
(cd "$out" && sha256sum -c --quiet koggles-current.apk.sha256) && ok || bad "current checksum does not verify"
grep -q '  koggles-current.apk$' "$out/koggles-current.apk.sha256" && ok || bad "current checksum names the wrong file"
# A tag that is not vX.Y.Z is refused.
"$here/assemble-release-files.sh" "$tmp/app-release.apk" "$tmp/mapping.txt" "$tmp/source.tar.gz" current "$tmp/dist2" > /dev/null 2>&1 && bad "accepted the tag 'current'" || ok
echo "assemble-release-files: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
