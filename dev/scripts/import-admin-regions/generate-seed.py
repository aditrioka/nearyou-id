#!/usr/bin/env python3
"""Generate the admin_regions seed SQL from OSM Overpass output.

Pipeline:

    1. Read provinces.geojson + kabupaten-kota.geojson from ./data/
       (produced by fetch-overpass.sh).
    2. Filter province set: skip level-4 relations whose `ref:ID:kemendagri`
       tag contains a dot — those are kabupaten mis-tagged as provinces at
       admin_level=4 (observed upstream: Ngawi with kemendagri "35.21").
    3. Reassemble MULTIPOLYGON WKT from each OSM relation's way members:
         - Group member ways by role (outer / inner).
         - Stitch open way segments into closed rings via endpoint matching.
         - Point-in-polygon assign each inner ring to its enclosing outer.
         - Emit one Polygon per outer ring, wrap as MULTIPOLYGON.
    4. Stage each row into `admin_regions_staging` (DROPped + re-created
       every run) with the raw WKT as the `geom` column.
    5. Apply transforms in SQL (cheap + auditable):
         - ST_MakeValid on any row where ST_IsValid = FALSE.
         - Compute geom_centroid via ST_PointOnSurface (robust for
           archipelagos where ST_Centroid can land in ocean).
         - Resolve parent_osm_id for kabupaten/kota via ST_Covers against
           the province set.
         - Detect coastal kabupaten (centroid within 50km of national
           coastline = ST_Boundary(ST_Union(provinces))) and extend geom
           by ~22km (12nm maritime buffer).
         - Recompute centroids post-buffer.
    6. SELECT rows back sorted by (level, id), emit as INSERT statements
       with the ODbL attribution string in the header.

Usage:
    python3 generate-seed.py [--out PATH] [--db-url URL] [--log-fixups PATH]

STDERR carries per-step progress + any ring-stitching / invalid-geometry
fixup warnings. If --log-fixups is given, the same warnings are also
written to that file as a permanent audit log for the V12 header.

Exit code non-zero if any row is still invalid after ST_MakeValid
(aborts before emitting SQL so Flyway never sees a broken migration).
"""

import argparse
import json
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Optional

try:
    import psycopg
except ImportError:
    sys.exit(
        "Missing dependency: psycopg[binary]. Install with:\n"
        "    pip install 'psycopg[binary]>=3.1'"
    )

SCRIPT_DIR = Path(__file__).resolve().parent
DATA_DIR = SCRIPT_DIR / "data"
DEFAULT_DB_URL = "postgresql://postgres:postgres@localhost:5433/nearyou_dev"

ODBL_ATTRIBUTION = (
    "Administrative boundaries © OpenStreetMap contributors, "
    "available under the Open Database License (ODbL)."
)

# Warnings sink. Populated during parsing, flushed to stderr + optional file.
_WARNINGS: list[str] = []


def _warn(msg: str) -> None:
    _WARNINGS.append(msg)
    sys.stderr.write(f"[warn] {msg}\n")


@dataclass(frozen=True)
class Region:
    """One OSM relation staged for import."""

    osm_id: int                # relation ID → admin_regions.id (Decision 8)
    name: str                  # OSM 'name' tag → admin_regions.name
    level: str                 # 'province' | 'kabupaten_kota'
    wkt: str                   # MULTIPOLYGON WKT reassembled from way members


# ---------------------------------------------------------------------------
# OSM relation → MULTIPOLYGON WKT
# ---------------------------------------------------------------------------


def _key(pt: tuple) -> tuple:
    """Rounded coordinate key for exact-match ring stitching. OSM Overpass
    typically returns the identical float for shared endpoints; rounding to
    9 decimals is a safety margin for any numeric noise."""
    return (round(pt[0], 9), round(pt[1], 9))


def _stitch_rings(segments):
    """Stitch open way segments into closed rings by endpoint matching.

    Each input segment is a list of (lon, lat) tuples. Returns a list of
    closed rings (first == last point). Orphan segments that can't be
    stitched into a closed loop are dropped with a warning.
    """
    remaining = [list(seg) for seg in segments]
    rings = []

    while remaining:
        current = remaining.pop(0)
        if _key(current[0]) == _key(current[-1]):
            rings.append(current)
            continue

        changed = True
        while changed and _key(current[0]) != _key(current[-1]):
            changed = False
            for i, cand in enumerate(remaining):
                if _key(cand[0]) == _key(current[-1]):
                    current.extend(cand[1:])
                    remaining.pop(i)
                    changed = True
                    break
                if _key(cand[-1]) == _key(current[-1]):
                    current.extend(reversed(cand[:-1]))
                    remaining.pop(i)
                    changed = True
                    break
                if _key(cand[-1]) == _key(current[0]):
                    current = list(cand[:-1]) + current
                    remaining.pop(i)
                    changed = True
                    break
                if _key(cand[0]) == _key(current[0]):
                    current = list(reversed(cand[1:])) + current
                    remaining.pop(i)
                    changed = True
                    break

        if _key(current[0]) == _key(current[-1]):
            rings.append(current)
        else:
            _warn(f"dropped orphan ring segment ({len(current)} points)")

    return rings


def _point_in_ring(point, ring) -> bool:
    """Ray-casting point-in-polygon test. Ring is closed (first == last)."""
    x, y = point
    inside = False
    n = len(ring) - 1
    for i in range(n):
        x1, y1 = ring[i]
        x2, y2 = ring[i + 1]
        if (y1 > y) != (y2 > y):
            if y2 == y1:
                continue
            x_intersect = x1 + (y - y1) * (x2 - x1) / (y2 - y1)
            if x < x_intersect:
                inside = not inside
    return inside


def _ring_to_wkt(ring) -> str:
    return "(" + ", ".join(f"{lon} {lat}" for lon, lat in ring) + ")"


def _polygon_to_wkt(outer, inners) -> str:
    parts = [_ring_to_wkt(outer)]
    parts.extend(_ring_to_wkt(inner) for inner in inners)
    return "(" + ", ".join(parts) + ")"


def _relation_to_wkt(relation: dict) -> Optional[str]:
    """Convert Overpass relation element to MULTIPOLYGON WKT. Returns None
    if the relation has no usable outer ring (caller skips with a warning).
    """
    outer_segs = []
    inner_segs = []

    for m in relation.get("members", []):
        if m.get("type") != "way" or "geometry" not in m:
            continue
        coords = [(pt["lon"], pt["lat"]) for pt in m["geometry"]]
        if not coords:
            continue
        role = m.get("role") or "outer"
        if role == "inner":
            inner_segs.append(coords)
        else:
            outer_segs.append(coords)

    outer_rings = _stitch_rings(outer_segs)
    inner_rings = _stitch_rings(inner_segs)

    if not outer_rings:
        return None

    assigned = [[] for _ in outer_rings]
    for inner in inner_rings:
        test_point = inner[0]
        placed = False
        for i, outer in enumerate(outer_rings):
            if _point_in_ring(test_point, outer):
                assigned[i].append(inner)
                placed = True
                break
        if not placed:
            _warn(
                f"relation {relation.get('id')}: inner ring has no enclosing "
                f"outer (dropped; {len(inner)} points)"
            )

    polygons = [
        _polygon_to_wkt(outer, inners)
        for outer, inners in zip(outer_rings, assigned)
    ]
    return "MULTIPOLYGON (" + ", ".join(polygons) + ")"


# ---------------------------------------------------------------------------
# GeoJSON parsing
# ---------------------------------------------------------------------------


def _is_misplaced_province(tags: dict) -> bool:
    """Detect level-4 relations that are actually kabupaten/kota mis-tagged
    as provinces. Real provinces have 2-digit kemendagri codes ("51");
    kabupaten have 4-digit dotted codes ("35.21"). Observed upstream:
    Ngawi (kabupaten in East Java).
    """
    kem = tags.get("ref:ID:kemendagri")
    return bool(kem and "." in kem)


def parse_overpass_file(path: Path, level: str) -> Iterable[Region]:
    if not path.exists():
        sys.exit(f"Missing input: {path} — run fetch-overpass.sh first.")
    raw = json.loads(path.read_text())
    for element in raw.get("elements", []):
        if element.get("type") != "relation":
            continue
        tags = element.get("tags") or {}
        name = tags.get("name")
        if not name:
            _warn(f"skipping OSM relation {element.get('id')} with no 'name' tag")
            continue
        if level == "province" and _is_misplaced_province(tags):
            _warn(
                f"skipping OSM relation {element.get('id')} ({name}) — "
                f"level=4 mis-tagged (kemendagri={tags.get('ref:ID:kemendagri')} "
                f"contains a dot, indicates kabupaten not province)"
            )
            continue
        wkt = _relation_to_wkt(element)
        if wkt is None:
            _warn(
                f"skipping OSM relation {element.get('id')} ({name}) — "
                f"no outer ring after stitching"
            )
            continue
        yield Region(
            osm_id=int(element["id"]),
            name=name,
            level=level,
            wkt=wkt,
        )


# ---------------------------------------------------------------------------
# Staging + transforms (SQL)
# ---------------------------------------------------------------------------


STAGING_DDL = """
DROP TABLE IF EXISTS admin_regions_staging;
CREATE TABLE admin_regions_staging (
    osm_id          BIGINT PRIMARY KEY,
    name            TEXT NOT NULL,
    level           TEXT NOT NULL CHECK (level IN ('province', 'kabupaten_kota')),
    parent_osm_id   BIGINT REFERENCES admin_regions_staging(osm_id),
    geom            GEOGRAPHY(MULTIPOLYGON, 4326),
    geom_centroid   GEOGRAPHY(POINT, 4326)
);
CREATE INDEX admin_regions_staging_geom_idx ON admin_regions_staging USING GIST (geom);
"""

INSERT_ROW_SQL = """
INSERT INTO admin_regions_staging (osm_id, name, level, geom)
VALUES (%s, %s, %s, ST_Multi(ST_GeomFromText(%s, 4326))::geography)
ON CONFLICT (osm_id) DO UPDATE
    SET name = EXCLUDED.name, level = EXCLUDED.level, geom = EXCLUDED.geom;
"""

TRANSFORMS = [
    (
        "st_makevalid on invalid geoms",
        """
        UPDATE admin_regions_staging
           SET geom = ST_Multi(ST_MakeValid(geom::geometry))::geography
         WHERE NOT ST_IsValid(geom::geometry);
        """,
    ),
    (
        "compute centroid (ST_PointOnSurface)",
        """
        UPDATE admin_regions_staging
           SET geom_centroid = ST_PointOnSurface(geom::geometry)::geography;
        """,
    ),
    (
        "resolve kab/kota parent_osm_id via ST_Covers",
        """
        UPDATE admin_regions_staging kab
           SET parent_osm_id = prov.osm_id
          FROM admin_regions_staging prov
         WHERE kab.level = 'kabupaten_kota'
           AND prov.level = 'province'
           AND ST_Covers(prov.geom::geometry, kab.geom_centroid::geometry);
        """,
    ),
    (
        "apply 12nm maritime buffer to coastal kab/kota",
        # 22km ≈ 0.198 degrees at equator; acceptable approximation for the
        # Indonesian lat range (-11..6.5). Accurate geodesic buffering via
        # ST_Buffer(geog, 22000) is ~10× slower with no visible difference
        # at the kotamadya scale. Coastal = centroid within 50km (~0.449°)
        # of ST_Boundary(ST_Union(provinces::geometry)).
        """
        WITH country_boundary AS (
            SELECT ST_Boundary(ST_Union(geom::geometry)) AS coast
              FROM admin_regions_staging
             WHERE level = 'province'
        )
        UPDATE admin_regions_staging kab
           SET geom = ST_Multi(ST_Buffer(kab.geom::geometry, 0.198))::geography
          FROM country_boundary cb
         WHERE kab.level = 'kabupaten_kota'
           AND ST_DWithin(kab.geom_centroid::geometry, cb.coast, 0.449);
        """,
    ),
    (
        "recompute centroid post-buffer (kab/kota only)",
        """
        UPDATE admin_regions_staging
           SET geom_centroid = ST_PointOnSurface(geom::geometry)::geography
         WHERE level = 'kabupaten_kota';
        """,
    ),
    (
        "simplify polygons (ST_SimplifyPreserveTopology, tol=0.00005° ≈ 5.5m)",
        # Tolerance chosen to stay safely below the trigger's 10m buffered_10m
        # step (step 2 of the fallback ladder) so simplification never crosses
        # that threshold. At 5.5m horizontal at equator, it's also well below
        # any kotamadya boundary feature that matters for a display label.
        # Reduces final SQL size ~3-5×; raw OSM polygons are ~11mm per-vertex
        # precision which is vastly over-spec for kabupaten labels.
        """
        UPDATE admin_regions_staging
           SET geom = ST_Multi(ST_SimplifyPreserveTopology(geom::geometry, 0.00005))::geography;
        """,
    ),
    (
        "st_makevalid post-simplify (safety net)",
        # ST_SimplifyPreserveTopology can occasionally produce invalid geoms
        # for very thin/complex polygons. Re-run ST_MakeValid as a safety net.
        """
        UPDATE admin_regions_staging
           SET geom = ST_Multi(ST_MakeValid(geom::geometry))::geography
         WHERE NOT ST_IsValid(geom::geometry);
        """,
    ),
]


def _execute_staging_pipeline(conn, rows) -> None:
    with conn.cursor() as cur:
        cur.execute(STAGING_DDL)
        for r in rows:
            cur.execute(INSERT_ROW_SQL, (r.osm_id, r.name, r.level, r.wkt))
        sys.stderr.write(f"[stage] {len(rows)} rows inserted\n")

        for label, sql in TRANSFORMS:
            cur.execute(sql)
            sys.stderr.write(f"[transform] {label}: affected {cur.rowcount} rows\n")

        cur.execute(
            "SELECT COUNT(*) FROM admin_regions_staging "
            "WHERE NOT ST_IsValid(geom::geometry)"
        )
        invalid = cur.fetchone()[0]
        if invalid:
            raise RuntimeError(
                f"{invalid} rows still invalid after ST_MakeValid — abort."
            )

        cur.execute(
            "SELECT COUNT(*) FROM admin_regions_staging "
            "WHERE level = 'kabupaten_kota' AND parent_osm_id IS NULL"
        )
        orphan = cur.fetchone()[0]
        if orphan:
            _warn(
                f"{orphan} kabupaten/kota have NULL parent_osm_id "
                f"(no enclosing province via ST_Covers); listed below."
            )
            cur.execute(
                "SELECT name FROM admin_regions_staging "
                "WHERE level = 'kabupaten_kota' AND parent_osm_id IS NULL "
                "ORDER BY name"
            )
            for (n,) in cur:
                _warn(f"  orphan kab/kota: {n}")

        conn.commit()


# ---------------------------------------------------------------------------
# SQL emission
# ---------------------------------------------------------------------------


EMIT_SQL = """
SELECT level,
       osm_id,
       name,
       parent_osm_id,
       ST_AsText(geom::geometry, 6)          AS geom_wkt,
       ST_AsText(geom_centroid::geometry, 6) AS centroid_wkt
  FROM admin_regions_staging
 ORDER BY CASE level WHEN 'province' THEN 0 ELSE 1 END, osm_id;
"""
# ST_AsText precision=6 → 6 decimal places per coordinate (~11 cm at equator),
# vastly over-spec for kabupaten/kota labels (which are coarse-grained) and
# well below both the 5.5m simplification tolerance and the 10m trigger buffer.
# Halves WKT byte size vs. the default 16-digit precision.

INSERT_TEMPLATE = (
    "INSERT INTO admin_regions (id, name, level, parent_id, geom, geom_centroid) "
    "VALUES ({id}, {name}, '{level}', {parent}, "
    "ST_Multi(ST_GeomFromText('{geom}', 4326))::geography, "
    "ST_GeomFromText('{centroid}', 4326)::geography);"
)


def _sql_quote(s: str) -> str:
    return "'" + s.replace("'", "''") + "'"


def emit(conn, out) -> int:
    out.write("-- admin_regions seed — generated by dev/scripts/import-admin-regions/generate-seed.py\n")
    out.write(f"-- Source: OpenStreetMap via Overpass API (area:3600304751 = Indonesia).\n")
    out.write(f"-- Attribution: {ODBL_ATTRIBUTION}\n")
    out.write(f"-- Coastal kabupaten polygons carry a 12nm (~22km) maritime buffer baked into geom.\n")
    out.write(f"-- Sort: provinces first (parent_id NULL), then kabupaten/kota (parent_id resolved).\n")
    out.write("\n")

    n = 0
    with conn.cursor() as cur:
        cur.execute(EMIT_SQL)
        for level, osm_id, name, parent_osm_id, geom_wkt, centroid_wkt in cur:
            out.write(
                INSERT_TEMPLATE.format(
                    id=osm_id,
                    name=_sql_quote(name),
                    level=level,
                    parent="NULL" if parent_osm_id is None else parent_osm_id,
                    geom=geom_wkt,
                    centroid=centroid_wkt,
                )
            )
            out.write("\n")
            n += 1
    return n


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def main() -> int:
    ap = argparse.ArgumentParser(description="Generate admin_regions seed SQL from OSM Overpass output.")
    ap.add_argument("--out", type=Path, default=None, help="Output SQL path (default: stdout).")
    ap.add_argument("--db-url", default=DEFAULT_DB_URL, help="Postgres connection URL.")
    ap.add_argument("--log-fixups", type=Path, default=None, help="Write warning log to this file.")
    args = ap.parse_args()

    provinces = list(parse_overpass_file(DATA_DIR / "provinces.geojson", "province"))
    kabupaten_kota = list(parse_overpass_file(DATA_DIR / "kabupaten-kota.geojson", "kabupaten_kota"))
    all_rows = provinces + kabupaten_kota
    sys.stderr.write(
        f"[parse] provinces={len(provinces)} kabupaten_kota={len(kabupaten_kota)} "
        f"total={len(all_rows)}\n"
    )

    with psycopg.connect(args.db_url) as conn:
        _execute_staging_pipeline(conn, all_rows)

        if args.out:
            args.out.parent.mkdir(parents=True, exist_ok=True)
            with args.out.open("w", encoding="utf-8") as f:
                n = emit(conn, f)
            sys.stderr.write(f"[emit] {n} INSERTs → {args.out}\n")
        else:
            n = emit(conn, sys.stdout)
            sys.stderr.write(f"[emit] {n} INSERTs → stdout\n")

    if args.log_fixups and _WARNINGS:
        args.log_fixups.parent.mkdir(parents=True, exist_ok=True)
        with args.log_fixups.open("w", encoding="utf-8") as f:
            f.write("# Warnings from generate-seed.py\n\n")
            for w in _WARNINGS:
                f.write(f"- {w}\n")
        sys.stderr.write(f"[audit] {len(_WARNINGS)} warnings → {args.log_fixups}\n")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
