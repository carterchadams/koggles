#!/usr/bin/env bash
# Parses `apksigner verify --verbose --print-certs` output (--verbose is required: the scheme lines
# are not printed without it).
#   parse-signer.sh <apksigner-output-file> <apksigner-exit-code> <expectedCertSha256>
# Prints "PASS|FAIL 1 signature" and "PASS|FAIL 2 cert-pin"; exits 0 only if both pass.
set -uo pipefail
out="$(cat "${1:?output file}")"; rc="${2:?apksigner exit code}"; want="${3:?expected cert}"
failures=0
if [ "$rc" -eq 0 ] && grep -qE '^Verified using v(2|3) scheme \(APK Signature Scheme v(2|3)\): true' <<<"$out"; then
  echo "PASS 1 signature"
else
  echo "FAIL 1 signature: apksigner rc=$rc, no v2/v3 verification"; failures=$((failures + 1))
fi
norm() { tr -d ':' | tr '[:upper:]' '[:lower:]'; }
got="$(grep -m1 '^Signer #1 certificate SHA-256 digest:' <<<"$out" | awk '{print $NF}' | norm)"
if [ -n "$got" ] && [ "$got" = "$(norm <<<"$want")" ]; then
  echo "PASS 2 cert-pin"
else
  echo "FAIL 2 cert-pin: signer '${got:-none}' != expected"; failures=$((failures + 1))
fi
[ "$failures" -eq 0 ]
