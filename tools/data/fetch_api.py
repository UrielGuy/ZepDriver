#!/usr/bin/env python3
"""Downloads one year's camps and art from the Burning Man Public API.

The API key comes from the ZEP_BM_API_KEY environment variable (a CI secret)
and is never printed or written anywhere. Only counts are printed, never data:
before the release dates the locations are Burning Man's confidential
information.

Usage: ZEP_BM_API_KEY=... fetch_api.py --year 2027 --out build/api/2027
       ZEP_BM_API_KEY=... fetch_api.py --year 2026 --count-only   (key check)

Writes camp.json and art.json in the API's own format (what
build_playa_data.py reads). Exit codes: 0 done, 3 the year has no location
data for developers yet (normal until three weeks before the event), other
values are errors.
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request

BASE = "https://api.burningman.org/api"


def get(kind, year, key):
    req = urllib.request.Request(
        f"{BASE}/{kind}?year={year}",
        headers={"X-API-Key": key, "Accept": "application/json", "User-Agent": "ZepDriver-build/1.0"},
    )
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                return json.load(r)
        except urllib.error.HTTPError as e:
            if e.code in (401, 403):
                sys.exit(f"API refused the key for {kind} (HTTP {e.code})")
            if e.code == 404:
                return []  # the year isn't in the API yet
            if attempt == 3 or e.code < 500:
                sys.exit(f"API error for {kind}: HTTP {e.code}")
        except (urllib.error.URLError, TimeoutError) as e:
            if attempt == 3:
                sys.exit(f"API unreachable for {kind}: {e.reason if hasattr(e, 'reason') else e}")
        time.sleep(2 ** (attempt + 1))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--year", type=int, required=True)
    ap.add_argument("--out")
    ap.add_argument("--count-only", action="store_true", help="print counts, write nothing")
    a = ap.parse_args()
    if not a.count_only and not a.out:
        ap.error("--out is required")
    key = os.environ.get("ZEP_BM_API_KEY", "").strip()
    if not key:
        sys.exit("ZEP_BM_API_KEY is not set")

    camps = get("camp", a.year, key)
    art = get("art", a.year, key)
    placed_camps = sum(1 for c in camps if c.get("location_string"))
    placed_art = sum(1 for x in art if (x.get("location") or {}).get("gps_latitude") is not None)
    print(f"{a.year}: {len(camps)} camps ({placed_camps} placed), {len(art)} art ({placed_art} placed)")
    if a.count_only:
        return
    if placed_camps == 0:
        print(f"No {a.year} placement data yet; nothing to seal.")
        sys.exit(3)

    os.makedirs(a.out, exist_ok=True)
    for name, data in (("camp.json", camps), ("art.json", art)):
        with open(os.path.join(a.out, name), "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False)


if __name__ == "__main__":
    main()
