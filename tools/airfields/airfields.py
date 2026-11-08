#!/usr/bin/env python3
"""
Builds the PicPiste airfield files from OpenStreetMap extracts.

  airfields.py extract IN.osm.pbf OUT.osm.pbf
      Keeps only what an airfield needs (aerodromes, runways, taxiways, aprons, hangars,
      windsocks) and the nodes and ways they reference. A continent of 35 GB becomes a few MB.

  airfields.py build OUT_DIR IN.osm.pbf [IN.osm.pbf...]
      Groups those features by aerodrome, the same way as the app's Overpass query
      (OsmImport.query): inside the aerodrome outline or within 150 m of it, or within
      2.5 km of an aerodrome drawn as a single point. Writes, for each aerodrome with a runway:
        a/<CODE>.json.gz  gzip of the Overpass "out geom" format the app already reads
                          (OsmImport.build), one per code (icao, ref, faa); aerodromes
                          without a code get a/osm-<type><id>.json.gz. Gzipped: the world
                          fits well within GitHub Pages' 1 GB (about 5 times smaller).
        index.json      every airfield: key, name, position, longest runway, codes
      Inputs may overlap (neighbouring extracts): objects are taken once.

Data: (c) OpenStreetMap contributors, ODbL 1.0. The output is a derived database under the
same licence.
"""

import gzip
import json
import math
import os
import re
import sys
import time
from datetime import datetime, timezone

import osmium
import shapely
from shapely.geometry import LineString, Point, Polygon, box
from shapely.ops import polygonize, unary_union

FEATURE_AEROWAYS = {"runway", "taxiway", "apron", "hangar"}
EXTRACT_TAGS = [("aeroway", v) for v in ("aerodrome", "runway", "taxiway", "apron", "hangar", "windsock")] + [("building", "hangar")]

# Tags kept in the output: what OsmImport reads, and a few that may be useful later.
AERODROME_KEYS = {"aeroway", "name", "icao", "ref", "faa", "iata", "ele", "aerodrome", "aerodrome:type"}
FEATURE_KEYS = {"aeroway", "building", "runway", "surface", "width", "length", "ref", "area", "name",
                "operational_status", "disused", "abandoned"}

# Same as the app (OsmImport.CODE).
CODE = re.compile(r"^[A-Z0-9]{3,7}$")
CODE_KEYS = ("icao", "ref", "faa")

# As in OsmImport.query.
AROUND_OUTLINE_M = 150.0
AROUND_POINT_M = 2500.0

M_PER_DEG_LAT = 110_574.0
M_PER_DEG_LON = 111_320.0


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, file=sys.stderr, flush=True)


# ----------------------------------------------------------------------------- extract

def extract(src, dst):
    """Aeroway objects of [src] and everything they reference (nodes, relation member ways)."""
    t0 = time.time()
    n = 0
    with osmium.BackReferenceWriter(dst, ref_src=src, overwrite=True) as out:
        for obj in osmium.FileProcessor(src).with_filter(osmium.filter.TagFilter(*EXTRACT_TAGS)):
            tags = obj.tags
            aeroway = tags.get("aeroway")
            if obj.is_relation() and aeroway != "aerodrome":
                continue  # the app only reads relations that are aerodrome outlines
            if obj.is_node() and aeroway not in ("windsock", "aerodrome"):
                continue
            out.add(obj)
            n += 1
    log(f"extract {src}: {n} objects in {time.time() - t0:.0f} s")


# ----------------------------------------------------------------------------- build

def keep(tags, keys):
    return {k: v for k, v in tags if k in keys}


def read(paths):
    """Ways (with coordinates), tagged nodes and aerodrome relations of all inputs, by id."""
    ways, nodes, relations = {}, {}, {}
    for path in paths:
        t0 = time.time()
        for obj in osmium.FileProcessor(path).with_locations():
            if obj.is_way():
                if obj.id in ways:
                    continue
                try:
                    coords = [(n.lat, n.lon) for n in obj.nodes]
                except osmium.InvalidLocationError:
                    coords = [(n.lat, n.lon) for n in obj.nodes if n.location.valid()]
                ways[obj.id] = (dict(obj.tags), coords)
            elif obj.is_node():
                if len(obj.tags) and obj.tags.get("aeroway") in ("aerodrome", "windsock") and obj.id not in nodes:
                    nodes[obj.id] = (dict(obj.tags), obj.location.lat, obj.location.lon)
            elif obj.is_relation():
                if obj.tags.get("aeroway") == "aerodrome" and obj.id not in relations:
                    members = [(m.type, m.ref, m.role) for m in obj.members]
                    relations[obj.id] = (dict(obj.tags), members)
        log(f"read {path}: {len(ways)} ways, {len(nodes)} nodes, {len(relations)} relations so far ({time.time() - t0:.0f} s)")
    return ways, nodes, relations


class Local:
    """Metres around a point: plenty accurate over a few kilometres."""

    def __init__(self, lat, lon):
        self.lat0, self.lon0 = lat, lon
        self.kx = M_PER_DEG_LON * math.cos(math.radians(lat))

    def geom(self, g):
        lat0, lon0, kx = self.lat0, self.lon0, self.kx
        return shapely.transform(g, lambda c: (c - (lon0, lat0)) * (kx, M_PER_DEG_LAT))

    def degrees(self, m):
        return m / M_PER_DEG_LAT, m / max(self.kx, 1.0)


def line_or_point(coords):
    """Shapely geometry in (lon, lat), or None."""
    pts = [(lon, lat) for lat, lon in coords]
    if not pts:
        return None
    if len(pts) == 1:
        return Point(pts[0])
    return LineString(pts)


def aerodrome_geometry(kind, oid, ways, nodes, relations):
    """(geometry in lon/lat, metres around it that still belong to it) or None."""
    if kind == "n":
        _, lat, lon = nodes[oid]
        return Point(lon, lat), AROUND_POINT_M
    if kind == "w":
        coords = ways[oid][1]
        if len(coords) >= 4 and coords[0] == coords[-1]:
            poly = Polygon([(lon, lat) for lat, lon in coords])
            if poly.is_valid:
                return poly, AROUND_OUTLINE_M
        g = line_or_point(coords)
        return (g, AROUND_OUTLINE_M) if g is not None else None
    # Relation: its outer ways made into polygons, else just the lines.
    lines = [line_or_point(ways[ref][1]) for t, ref, role in relations[oid][1]
             if t == "w" and ref in ways and role in ("outer", "")]
    lines = [g for g in lines if g is not None]
    if not lines:
        return None
    polys = [p for p in polygonize(lines) if p.is_valid]
    g = unary_union(polys) if polys else unary_union(lines)
    return g, AROUND_OUTLINE_M


def geometry_json(coords):
    return [{"lat": round(lat, 7), "lon": round(lon, 7)} for lat, lon in coords]


def codes_of(tags):
    out = []
    for k in CODE_KEYS:
        for part in (tags.get(k) or "").split(";"):
            c = part.strip().upper()
            if CODE.match(c) and c not in out:
                out.append(c)
    return out


def build(out_dir, paths):
    ways, nodes, relations = read(paths)

    feature_ids = [wid for wid, (tags, coords) in ways.items()
                   if len(coords) >= 2 and (tags.get("aeroway") in FEATURE_AEROWAYS or tags.get("building") == "hangar")]
    windsock_ids = [nid for nid, (tags, _, _) in nodes.items() if tags.get("aeroway") == "windsock"]
    features = [("w", wid) for wid in feature_ids] + [("n", nid) for nid in windsock_ids]
    feature_geoms = [line_or_point(ways[i][1]) if k == "w" else Point(nodes[i][2], nodes[i][1]) for k, i in features]
    tree = shapely.STRtree(feature_geoms)
    log(f"{len(feature_ids)} feature ways, {len(windsock_ids)} windsocks")

    aerodromes = [("n", i) for i, (t, _, _) in nodes.items() if t.get("aeroway") == "aerodrome"] + \
                 [("w", i) for i, (t, _) in ways.items() if t.get("aeroway") == "aerodrome"] + \
                 [("r", i) for i in relations]
    log(f"{len(aerodromes)} aerodromes")

    found = []  # (kind, id, tags, centre, longest runway, feature indexes)
    for kind, oid in aerodromes:
        tags = nodes[oid][0] if kind == "n" else ways[oid][0] if kind == "w" else relations[oid][0]
        ag = aerodrome_geometry(kind, oid, ways, nodes, relations)
        if ag is None:
            continue
        geom, around = ag
        centre = geom.centroid if not geom.is_empty else None
        if centre is None or centre.is_empty:
            continue
        local = Local(centre.y, centre.x)
        dlat, dlon = local.degrees(around * 1.1)
        minx, miny, maxx, maxy = geom.bounds
        candidates = tree.query(box(minx - dlon, miny - dlat, maxx + dlon, maxy + dlat))
        if len(candidates) == 0:
            continue
        here = local.geom(geom)
        mine = [int(i) for i in candidates if here.distance(local.geom(feature_geoms[i])) <= around]
        runways = [local.geom(feature_geoms[i]).length for i in mine
                   if features[i][0] == "w" and ways[features[i][1]][0].get("aeroway") == "runway"
                   and ways[features[i][1]][0].get("runway") != "displaced_threshold"]
        if not runways:
            continue  # the app needs a runway
        found.append((kind, oid, tags, (centre.y, centre.x), max(runways), mine))
    log(f"{len(found)} aerodromes with a runway")

    # Each code to one aerodrome: an icao tag first, then ref, then faa; then the longest runway.
    owner = {}
    ranked = sorted(range(len(found)), key=lambda j: -found[j][4])
    for k in CODE_KEYS:
        for j in ranked:
            for part in (found[j][2].get(k) or "").split(";"):
                c = part.strip().upper()
                if CODE.match(c) and c not in owner:
                    owner[c] = j

    os.makedirs(os.path.join(out_dir, "a"), exist_ok=True)
    index = []
    written = total = 0
    for j, (kind, oid, tags, (lat, lon), longest, mine) in enumerate(found):
        elements = []
        for i in sorted(mine, key=lambda i: (features[i][0] == "w", features[i][1])):
            k, fid = features[i]
            if k == "n":
                t, nlat, nlon = nodes[fid]
                elements.append({"type": "node", "id": fid, "lat": round(nlat, 7), "lon": round(nlon, 7), "tags": keep(t.items(), FEATURE_KEYS)})
            else:
                t, coords = ways[fid]
                elements.append({"type": "way", "id": fid, "tags": keep(t.items(), FEATURE_KEYS), "geometry": geometry_json(coords)})
        ad_tags = keep(tags.items(), AERODROME_KEYS)
        if kind == "n":
            elements.append({"type": "node", "id": oid, "lat": round(nodes[oid][1], 7), "lon": round(nodes[oid][2], 7), "tags": ad_tags})
        elif kind == "w":
            elements.append({"type": "way", "id": oid, "tags": ad_tags, "geometry": geometry_json(ways[oid][1])})
        else:
            members = [{"type": "way", "ref": ref, "role": role, "geometry": geometry_json(ways[ref][1])}
                       for t, ref, role in relations[oid][1] if t == "w" and ref in ways]
            elements.append({"type": "relation", "id": oid, "tags": ad_tags, "members": members})
        text = json.dumps({"version": 0.6, "generator": "PicPiste airfields (OpenStreetMap, ODbL)", "elements": elements},
                          ensure_ascii=False, separators=(",", ":"))
        data = gzip.compress(text.encode(), compresslevel=9, mtime=0)  # mtime 0: same data, same file
        codes = [c for c in codes_of(tags) if owner.get(c) == j]  # icao first, then ref, faa
        keys = codes or [f"osm-{kind}{oid}"]
        for key in keys:
            with open(os.path.join(out_dir, "a", key + ".json.gz"), "wb") as f:
                f.write(data)
            written += 1
            total += len(data)
        index.append([keys[0], tags.get("name", ""), round(lat, 5), round(lon, 5), round(longest), codes])

    index.sort(key=lambda r: r[0])
    with open(os.path.join(out_dir, "index.json"), "w", encoding="utf-8") as f:
        json.dump({
            "format": 1,
            "generated": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "attribution": "© OpenStreetMap contributors",
            "license": "ODbL-1.0 (https://opendatacommons.org/licenses/odbl/1-0/)",
            "fields": ["key", "name", "lat", "lon", "longestRunwayM", "codes"],
            "airfields": index,
        }, f, ensure_ascii=False, separators=(",", ":"))
    with open(os.path.join(out_dir, "index.html"), "w", encoding="utf-8") as f:
        f.write(PAGE.format(count=len(index), date=datetime.now(timezone.utc).strftime("%Y-%m-%d")))
    open(os.path.join(out_dir, ".nojekyll"), "w").close()
    log(f"wrote {written} files, {total / 1e6:.1f} MB, index of {len(index)} airfields")


PAGE = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>PicPiste airfields</title></head>
<body style="font-family: system-ui, sans-serif; max-width: 40rem; margin: 2rem auto; padding: 0 1rem; line-height: 1.5">
<h1>PicPiste airfields</h1>
<p>{count} airfields with a runway, extracted from OpenStreetMap on {date} for the
<a href="https://github.com/CorentinGrard/PicPiste">PicPiste</a> app.</p>
<ul>
<li><a href="index.json">index.json</a>: every airfield (key, name, position, longest runway, codes)</li>
<li><code>a/&lt;CODE&gt;.json.gz</code>: one airfield (e.g. <a href="a/LFML.json.gz">a/LFML.json.gz</a>), gzipped, in the Overpass API JSON format</li>
</ul>
<p>Data &copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap contributors</a>,
available under the <a href="https://opendatacommons.org/licenses/odbl/1-0/">Open Database License</a>.
This collection is a derived database under the same licence.</p>
</body></html>
"""


def main(argv):
    if len(argv) == 3 and argv[0] == "extract":
        extract(argv[1], argv[2])
    elif len(argv) >= 3 and argv[0] == "build":
        build(argv[1], argv[2:])
    else:
        print(__doc__, file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
