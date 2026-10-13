#!/usr/bin/env python3
"""Builds ZepDriver's bundled playa data from the public Burning Man datasets.

Inputs:
  --gis    path to a checkout of github.com/burningmantech/innovate-GIS-data
  --api    path to a directory holding the Public API dumps (art.json, camp.json)
           e.g. iBurn-Data/data/<year>/APIData/APIData.bundle
  --extra  path to iBurn-Data/data/<year>/geo (for the Man and Center Camp plaza polygons)
  --year   event year
  --out    output directory (app/src/main/assets/playa/<year>)

Personal contact fields (emails, phone numbers) are dropped.
"""
import argparse
import hashlib
import json
import os

ROUND = 6  # ~0.1 m


def rnd(coords):
    if isinstance(coords, (int, float)):
        return round(coords, ROUND)
    return [rnd(c) for c in coords]


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def feature(geom, props):
    return {"type": "Feature", "properties": props,
            "geometry": {"type": geom["type"], "coordinates": rnd(geom["coordinates"])}}


def fc(features):
    return {"type": "FeatureCollection", "features": features}


def write(out, name, obj):
    path = os.path.join(out, name)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, separators=(",", ":"), ensure_ascii=False)
    return path


# Official 2026 street names (2026 BRC Measurements, theme "Axis Mundi").
RINGS_2026 = [
    ("ESP", "Esplanade", 2500, 40),
    ("A", "Ararat", 2935, 30),
    ("B", "Bodhi", 3215, 30),
    ("C", "Chomolungma", 3495, 30),
    ("D", "Delphi", 3775, 30),
    ("E", "Eternal", 4060, 40),
    ("F", "Fulcrum", 4545, 30),
    ("G", "Great Oak", 4825, 30),
    ("H", "Heiau", 5105, 30),
    ("I", "Iroko", 5385, 30),
    ("J", "Jiba", 5565, 30),
    ("K", "Kundalini", 5755, 50),
]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--gis", required=True)
    ap.add_argument("--api", required=True)
    ap.add_argument("--extra", required=True)
    ap.add_argument("--year", type=int, default=2026)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    gis = os.path.join(a.gis, str(a.year), "GeoJSON")
    out = a.out

    # --- Layout -------------------------------------------------------------
    cpns = load(os.path.join(gis, "cpns.geojson"))["features"]
    by_name = {f["properties"]["NAME"]: f["geometry"]["coordinates"] for f in cpns}
    man = by_name["The Man"]
    temple = by_name["The Temple"]
    layout = {
        "year": a.year,
        "man": {"lat": round(man[1], ROUND), "lon": round(man[0], ROUND)},
        "temple": {"lat": round(temple[1], ROUND), "lon": round(temple[0], ROUND)},
        # Compass bearing (degrees true) of the 12:00 direction from the Man.
        "twelveOClockBearingDeg": 45.0,
        "rings": [{"letter": l, "name": n, "radiusFt": r, "widthFt": w} for l, n, r, w in RINGS_2026],
        "cityStartClock": "2:00",
        "cityEndClock": "10:00",
        "radialWidthFt": 40,
        "centerCamp": {"clock": "6:00", "distanceFt": 2999, "plazaRadiusFt": 320},
        "greatCircleRadiusFt": 200,
        "walkInCamping": {"fromClock": "2:00", "toClock": "5:00"},
        "timeZone": "America/Los_Angeles",
        "eventStart": "2026-08-30",
        "eventEnd": "2026-09-07",
        "manBurn": "2026-09-05",
        "templeBurn": "2026-09-06",
        "attribution": "Map data: Burning Man Project GIS (innovate.burningman.org). "
                       "Art and camp data: Burning Man Public API via iBurn-Data.",
    }
    write(out, "layout.json", layout)

    # --- Geometry layers ----------------------------------------------------
    streets = []
    for f in load(os.path.join(gis, "street_lines.geojson"))["features"]:
        p = f["properties"]
        streets.append(feature(f["geometry"], {
            "name": p.get("name"), "kind": p.get("kind") or "street", "widthFt": p.get("width_ft") or 30}))
    write(out, "geo/streets.geojson", fc(streets))

    simple_layers = {
        "city_blocks": "geo/blocks.geojson",
        "trash_fence": "geo/fence.geojson",
        "dmz": "geo/dmz.geojson",
    }
    for src, dst in simple_layers.items():
        feats = [feature(f["geometry"], {}) for f in load(os.path.join(gis, src + ".geojson"))["features"]]
        write(out, dst, fc(feats))

    plazas = [feature(f["geometry"], {"name": f["properties"].get("name")})
              for f in load(os.path.join(gis, "plazas.geojson"))["features"]]
    # The Man's Great Circle comes from iBurn's derived polygons.
    for f in load(os.path.join(a.extra, "polygons.geojson"))["features"]:
        if f["properties"].get("name") == "The Man Plaza":
            plazas.append(feature(f["geometry"], {"name": "Great Circle"}))
    write(out, "geo/plazas.geojson", fc(plazas))

    toilets = [feature(f["geometry"], {"class": f["properties"].get("class") or "outer"})
               for f in load(os.path.join(gis, "toilets.geojson"))["features"]]
    write(out, "geo/toilets.geojson", fc(toilets))

    pois = [feature(f["geometry"], {"name": f["properties"]["NAME"]}) for f in cpns]
    write(out, "geo/poi.geojson", fc(pois))

    # --- Art and camps --------------------------------------------------------
    def keep_images(item):
        return [i["thumbnail_url"] for i in (item.get("images") or []) if i.get("thumbnail_url")][:1]

    art = []
    for x in load(os.path.join(a.api, "art.json")):
        loc = x.get("location") or {}
        if loc.get("gps_latitude") is None:
            continue
        art.append({
            "id": x["uid"], "name": x["name"], "artist": x.get("artist"),
            "hometown": x.get("hometown"), "description": x.get("description"),
            "category": x.get("category"), "address": x.get("location_string"),
            "lat": round(loc["gps_latitude"], ROUND), "lon": round(loc["gps_longitude"], ROUND),
            "image": (keep_images(x) or [None])[0],
        })
    camps = []
    for x in load(os.path.join(a.api, "camp.json")):
        loc = x.get("location") or {}
        if loc.get("gps_latitude") is None:
            continue
        camps.append({
            "id": x["uid"], "name": x["name"], "hometown": x.get("hometown"),
            "description": x.get("description"), "landmark": x.get("landmark"),
            "address": x.get("location_string"), "frontage": loc.get("frontage"),
            "intersection": loc.get("intersection"),
            "lat": round(loc["gps_latitude"], ROUND), "lon": round(loc["gps_longitude"], ROUND),
        })
    write(out, "art.json", art)
    write(out, "camps.json", camps)

    # --- Manifest -------------------------------------------------------------
    files = {}
    for root, _, names in os.walk(out):
        for n in sorted(names):
            if n == "manifest.json":
                continue
            p = os.path.join(root, n)
            rel = os.path.relpath(p, out)
            with open(p, "rb") as f:
                files[rel] = hashlib.sha256(f.read()).hexdigest()
    version = hashlib.sha256(json.dumps(files, sort_keys=True).encode()).hexdigest()[:12]
    write(out, "manifest.json", {"year": a.year, "version": version, "files": files})
    print(f"wrote {len(files)} files, {len(art)} art, {len(camps)} camps, version {version}")


if __name__ == "__main__":
    main()
