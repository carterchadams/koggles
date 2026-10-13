#!/usr/bin/env bash
# Launcher icon resources (brand-images M2): the manifest declares the icon and round icon, the adaptive
# XML and background colour exist, every density ships the 108 dp foreground and the 48 dp legacy rasters,
# every foreground keeps the swirl inside the 66/108 safe circle (no channel >= 24/255 outside it) with a bright
# centre, and the rasters (lossy WebP, quality 90) stay under 300 KB in total. Needs ImageMagick with WebP.
set -uo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"; res="$root/app/src/main/res"; fail=0
# ImageMagick 7 locally (`magick`); GitHub's ubuntu runners ship ImageMagick 6 (`convert`/`identify`).
if command -v magick >/dev/null 2>&1; then IM=magick; IDENT="magick identify"
elif command -v convert >/dev/null 2>&1; then IM=convert; IDENT=identify
else echo "FAIL launcher-icon: ImageMagick (magick or convert) not found"; exit 1; fi
bad() { echo "FAIL launcher-icon: $*"; fail=1; }
m="$root/app/src/main/AndroidManifest.xml"
grep -q 'android:icon="@mipmap/ic_launcher"' "$m" || bad "missing android:icon"
grep -q 'android:roundIcon="@mipmap/ic_launcher_round"' "$m" || bad "missing android:roundIcon"
for f in ic_launcher ic_launcher_round; do
  [ -f "$res/mipmap-anydpi-v26/$f.xml" ] || bad "missing mipmap-anydpi-v26/$f.xml"
done
grep -q 'name="ic_launcher_background">#000000<' "$res/values/ic_launcher_background.xml" 2>/dev/null || bad "background colour is not #000000"
dim() { $IDENT -format '%wx%h' "$1" 2>/dev/null; }
for d in mdpi:108:48 hdpi:162:72 xhdpi:216:96 xxhdpi:324:144 xxxhdpi:432:192; do
  IFS=: read -r dir fg lg <<<"$d"
  [ "$(dim "$res/mipmap-$dir/ic_launcher_foreground.webp")" = "${fg}x${fg}" ] || bad "mipmap-$dir/ic_launcher_foreground.webp is not ${fg}x${fg}"
  for f in ic_launcher ic_launcher_round; do
    [ "$(dim "$res/mipmap-$dir/$f.webp")" = "${lg}x${lg}" ] || bad "mipmap-$dir/$f.webp is not ${lg}x${lg}"
  done
done
# Safe zone at every density, after WebP encoding: outside the 66/108 circle (no slack) no pixel has any channel
# at or above 24/255 (the trim level is 8% = 20/255, so this is "no visible glow", including saturated blue or
# magenta that a luma threshold would miss); the centre 24x24 has a bright pixel (max channel > 0.5).
for d in mdpi:108 hdpi:162 xhdpi:216 xxhdpi:324 xxxhdpi:432; do
  dir=${d%%:*}; px=${d##*:}; c=$(( px / 2 )); r=$(( px * 66 / 108 / 2 ))
  fg="$res/mipmap-$dir/ic_launcher_foreground.webp"; [ -f "$fg" ] || continue
  lit=$($IM "$fg" -separate -evaluate-sequence Max -threshold 9.3% \
        \( +clone -fill white -colorize 100 -fill black -draw "circle $c,$c $c,$((c - r))" \) \
        -compose Multiply -composite -format '%[fx:int(mean*w*h+0.5)]' info:)
  [ "${lit:-1}" -eq 0 ] || bad "mipmap-$dir: $lit lit pixels outside the safe zone"
  peak=$($IM "$fg" -gravity center -crop 24x24+0+0 +repage -separate -evaluate-sequence Max -format '%[fx:maxima]' info:)
  awk -v p="${peak:-0}" 'BEGIN{exit !(p > 0.5)}' || bad "mipmap-$dir: no bright pixel at the centre (max $peak)"
done
total=$(cat "$res"/mipmap-*/ic_launcher*.webp 2>/dev/null | wc -c)
[ "$total" -lt 307200 ] || bad "rasters total $total bytes (limit 307200)"
[ "$fail" -eq 0 ] && echo "launcher-icon: OK ($total bytes of rasters)"
exit "$fail"
