#!/usr/bin/env bash
# Builds the embargoed year's camp and art data and seals it into APK assets.
# Runs in CI with the Burning Man API key from the BURNING_MAN_API_KEY secret.
#
#   ZEP_BM_API_KEY=... tools/data/build_sealed.sh <year>
#
# Output: build/sealed-assets/, which the app build picks up as assets. The
# plaintext download is deleted afterwards, and only counts are printed: CI logs
# are public and the locations are confidential until their release dates.
# Without a key, or before the year's placement data exists, it builds nothing
# and the app ships the previous year only.
#
# ZEP_EXTRA_CERTS: comma-separated SHA-256s of other signing certificates
# allowed to open the data (e.g. Play App Signing's), besides app/debug.keystore.
set -euo pipefail
YEAR="$1"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/build/sealed-assets"
WORK="$ROOT/build/sealed-work"
rm -rf "$OUT" "$WORK"
trap 'rm -rf "$WORK"' EXIT

if [ -z "${ZEP_BM_API_KEY:-}" ]; then
  echo "No Burning Man API key (secret BURNING_MAN_API_KEY): building without $YEAR data."
  exit 0
fi

# Exit code 3 from the helpers means "not available yet", which is fine.
run_or_skip() {
  set +e
  "$@"
  local rc=$?
  set -e
  if [ $rc -eq 3 ]; then
    echo "Building without $YEAR data for now."
    exit 0
  fi
  return $rc
}

run_or_skip python3 "$ROOT/tools/data/fetch_api.py" --year "$YEAR" --out "$WORK/api"
git clone --depth 1 -q https://github.com/burningmantech/innovate-GIS-data "$WORK/gis"
run_or_skip python3 "$ROOT/tools/data/build_playa_data.py" --gis "$WORK/gis" --api "$WORK/api" \
  --year "$YEAR" --out "$WORK/bundle" --source "Burning Man Public API" --geocode-later

CERTS="keystore:$ROOT/app/debug.keystore:android:androiddebugkey"
if [ -n "${ZEP_EXTRA_CERTS:-}" ]; then CERTS="$CERTS,$ZEP_EXTRA_CERTS"; fi
"$ROOT/gradlew" -q -p "$ROOT" :core:sealData -PsealIn="$WORK/bundle" -PsealOut="$OUT" -PsealCerts="$CERTS"
