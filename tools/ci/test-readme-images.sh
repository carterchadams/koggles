#!/usr/bin/env bash
# Every image the README references exists in the repo at that path (a banner that 404s on GitHub is a
# silent failure), and the banner is referenced.
set -uo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
fail=0
while read -r path; do
  [ -f "$root/$path" ] || { echo "FAIL readme-images: $path missing"; fail=1; }
done < <(grep -oE '!\[[^]]*\]\(([^) ]+)\)' "$root/README.md" | sed -E 's/.*\(([^) ]+)\)/\1/')
grep -q '](images/KogglesREADMEBanner.png)' "$root/README.md" || { echo "FAIL readme-images: banner not referenced"; fail=1; }
[ "$fail" -eq 0 ] && echo "readme-images: OK"
exit "$fail"
