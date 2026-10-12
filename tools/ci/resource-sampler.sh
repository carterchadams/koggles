#!/usr/bin/env bash
# Samples free disk every 5 s in the background (plans/RELEASE-HARDENING.md, RC3).
#   resource-sampler.sh start <file>   # appends "<epoch> <free-MB-on-/> <free-MB-on-workspace>"
#   resource-sampler.sh stop  <file>   # stops it and prints "min free MB: N"
set -euo pipefail
cmd="${1:?start|stop}"; file="${2:?file}"
case "$cmd" in
  start)
    ws="${GITHUB_WORKSPACE:-$PWD}"
    ( while true; do
        echo "$(date +%s) $(df -BM --output=avail / | tail -1 | tr -dc 0-9) $(df -BM --output=avail "$ws" | tail -1 | tr -dc 0-9)" >> "$file"
        sleep 5
      done ) > /dev/null 2>&1 &
    echo $! > "$file.pid"
    ;;
  stop)
    [ -f "$file.pid" ] && kill "$(cat "$file.pid")" 2>/dev/null || true
    rm -f "$file.pid"
    awk 'NR==1{m=$2} {if($2<m)m=$2; if($3<m)m=$3} END{print "min free MB: " m}' "$file"
    ;;
  *) echo "usage: resource-sampler.sh start|stop <file>" >&2; exit 2 ;;
esac
