#!/usr/bin/env python3
"""Turn an Overpass JSON dump of LFNL into the PicPiste airfield config.

Usage: python3 -I tools/osm_to_airfield.py lfnl-osm.json > core/src/main/resources/airfields/LFNL.json
"""
import json
import math
import sys

# OSM way ids for LFNL, checked by hand against the airfield (Oct 2026).
RUNWAYS = [
    # id, name, surface, outline way, centerline way
    ("herbe", "herbe", "grass", 163601806, 673062433),
    ("dur-droite", "dur droite", "paved", 35079513, 673062434),
    ("dur-gauche", "dur gauche", "paved", 35079516, 673062435),
]
APRON = [35079520]
TAXIWAYS = [35079518, 35079521, 35109231, 953982443]
HANGARS = [161597559, 161597854, 161597901, 163601743, 163601744]
WINDSOCKS = [9735711672]  # the one on the parking area does not exist
BOUNDARY = 174170781

# Starter: placed by hand on the runway-aligned plan (not in OSM).
STARTER_PLAN_PX = (1110, 437)
PLAN_ORIGIN = (43.7995, 3.7825)
PLAN_SCALE, PLAN_OX, PLAN_OY = 1.6, 758, 290
AXIS = 119.2


def main(path):
    els = {e["id"]: e for e in json.load(open(path))["elements"]}

    def pts(way_id):
        e = els[way_id]
        g = e.get("geometry") or [{"lat": e["lat"], "lon": e["lon"]}]
        return [[round(p["lat"], 7), round(p["lon"], 7)] for p in g]

    def poly(way_id):
        p = pts(way_id)
        return p[:-1] if len(p) > 1 and p[0] == p[-1] else p

    runways = []
    for rid, name, surface, outline, center in RUNWAYS:
        c = pts(center)
        a, b = c[0], c[-1]  # west end first = threshold 12
        if a[1] > b[1]:
            a, b = b, a
        runways.append({
            "id": rid, "name": name, "surface": surface,
            "threshold12": a, "threshold30": b,
            "outline": poly(outline),
        })

    # Starter: inverse of the plan projection used for the design canvas.
    x = (STARTER_PLAN_PX[0] - PLAN_OX) / PLAN_SCALE
    y = (STARTER_PLAN_PX[1] - PLAN_OY) / PLAN_SCALE
    bu, bv = math.radians(AXIS), math.radians(AXIS - 90)
    east = x * math.sin(bu) + (-y) * math.sin(bv)
    north = x * math.cos(bu) + (-y) * math.cos(bv)
    lat0, lon0 = PLAN_ORIGIN
    starter = [round(lat0 + north / 110540, 7),
               round(lon0 + east / (111320 * math.cos(math.radians(lat0))), 7)]

    out = {
        "icao": "LFNL",
        "name": "Saint-Martin-de-Londres",
        "elevationM": 182,
        "reference": [43.7995, 3.7825],
        "axisBearingDeg": AXIS,
        "runways": runways,
        "boundary": poly(BOUNDARY),
        "aprons": [poly(i) for i in APRON],
        "taxiways": [{"path": pts(i), "widthM": float(els[i]["tags"].get("width", 8))} for i in TAXIWAYS],
        "hangars": [poly(i) for i in HANGARS],
        "windsocks": [pts(i)[0] for i in WINDSOCKS],
        "starter": starter,
        "source": "OpenStreetMap contributors (ODbL), starter placed by hand",
    }
    json.dump(out, sys.stdout, indent=1)
    sys.stdout.write("\n")


if __name__ == "__main__":
    main(sys.argv[1])
