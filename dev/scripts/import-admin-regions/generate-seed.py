#!/usr/bin/env python3
"""Generate the admin_regions seed SQL from OSM Overpass output.

Pipeline (matches README.md section "Pipeline"):

    1. Read provinces.geojson + kabupaten-kota.geojson + dki-kotamadya.geojson
       from ./data/ (produced by fetch-overpass.sh).
    2. Reassemble MULTIPOLYGON WKT from each OSM relation's way members.
       Overpass returns ways as open strings; we close rings + dissolve into
       outer/inner membership via PostGIS server-side rather than in Python
       (ST_MakeValid + ST_MakeMultiPolygon are designed for this).
    3. Stage each row into a temporary `admin_regions_staging` table with the
       raw WKT as the `geom` column.
    4. Apply transforms in SQL (cheap + auditable):
         - ST_MakeValid on any row where ST_IsValid = FALSE.
         - Compute geom_centroid via ST_PointOnSurface (robust for archipelagos).
         - Resolve parent_id for kabupaten/kota via ST_Contains against the
           province set.
         - Detect coastal kabupaten (centroid within 50km of
           ST_Boundary(ST_Union(provinces))) and extend geom by ~22km.
    5. SELECT rows back, sorted by (level, id), emit as INSERT statements.

Usage:
    python3 generate-seed.py [--out PATH] [--db-url URL]

Output SQL is produced sorted: provinces first (parent_id IS NULL), then
kabupaten/kota. Includes a header comment block with the OSM snapshot
timestamp + ODbL attribution string for direct paste into V11 (or V12 if
V11 already shipped without seed — see DEFERRED.md).

STATUS: SCAFFOLD. This script is documented end-to-end but has NOT yet been
run against live data. Before Session 2 executes for real, confirm:
  - The Overpass members-to-WKT reassembly handles all Indonesian edge cases
    (multi-ring outer polygons, islands with inner holes, antimeridian crossing
    in far-east Papua / Maluku Utara).
  - The coastal-buffer heuristic's 22km-in-degrees approximation is acceptable
    at -11 <= lat <= 6.5; if not, switch to ST_Buffer(geog, 22000) server-side.

Key TODOs are marked `# TODO(session-2):` inline.
"""

import argparse
import json
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

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


@dataclass(frozen=True)
class Region:
    """One OSM relation staged for import."""

    osm_id: int                # relation ID → admin_regions.id (Decision 8)
    name: str                  # OSM 'name' tag → admin_regions.name
    level: str                 # 'province' | 'kabupaten_kota'
    wkt: str                   # MULTIPOLYGON WKT reassembled from way members


# ---------------------------------------------------------------------------
# OSM parsing
# ---------------------------------------------------------------------------


def _relation_to_wkt(relation: dict) -> str:
    """Convert an Overpass relation element to MULTIPOLYGON WKT.

    Overpass returns relation members as `{type: 'way', role: 'outer'|'inner',
    geometry: [{lat, lon}, ...]}`. We need to:

    1. Group member ways by role.
    2. Stitch outer ways into closed rings (ways can share endpoints).
    3. Match inner ways to their enclosing outer ring (point-in-polygon) — OR
       rely on PostGIS to do this via ST_MakeValid + ST_BuildArea server-side.

    For scaffold simplicity we take approach (b): emit one WKT polygon per
    outer ring, wrap as MULTIPOLYGON, and let PostGIS resolve hole assignment
    via ST_MakeValid when the staging INSERT applies it. This is a well-known
    OSM-to-PostGIS idiom (osm2pgsql does the same).

    TODO(session-2): handle the edge case where an outer ring is composed of
    multiple way members that need endpoint-stitching into one closed ring.
    Naive per-way polygonization produces invalid MULTIPOLYGON that ST_MakeValid
    can usually fix, but produce an audit log of fixups for the V11 header.
    """
    # TODO(session-2): real implementation. Pseudocode below documents intent.
    #
    # outer_rings: list[list[tuple[float, float]]] = []
    # inner_rings: list[list[tuple[float, float]]] = []
    # pending_outer_ways: list[list[tuple[float, float]]] = []
    # for member in relation.get("members", []):
    #     if member["type"] != "way":
    #         continue
    #     coords = [(pt["lon"], pt["lat"]) for pt in member["geometry"]]
    #     if member["role"] == "outer":
    #         if coords[0] == coords[-1]:
    #             outer_rings.append(coords)
    #         else:
    #             pending_outer_ways.append(coords)
    #     elif member["role"] == "inner":
    #         inner_rings.append(coords)
    # outer_rings.extend(_stitch_open_ways(pending_outer_ways))
    # polygons = [_assemble_polygon(outer, inner_rings) for outer in outer_rings]
    # return _format_multipolygon_wkt(polygons)
    raise NotImplementedError(
        "Scaffold: OSM relation → MULTIPOLYGON WKT conversion not implemented. "
        "See TODO(session-2) in _relation_to_wkt and the README Pipeline section."
    )


def parse_overpass_file(path: Path, level: str) -> Iterable[Region]:
    """Yield Region rows from one Overpass-JSON file."""
    if not path.exists():
        sys.exit(f"Missing input: {path} — run fetch-overpass.sh first.")
    raw = json.loads(path.read_text())
    for element in raw.get("elements", []):
        if element.get("type") != "relation":
            continue
        tags = element.get("tags", {})
        name = tags.get("name")
        if not name:
            # OSM relations without a 'name' tag can't produce a meaningful
            # city_name label. Skip + log — implementer should spot-check.
            sys.stderr.write(
                f"[warn] skipping OSM relation {element.get('id')} with no 'name' tag\n"
            )
            continue
        try:
            wkt = _relation_to_wkt(element)
        except NotImplementedError:
            # Scaffold: bubble up the NotImplementedError so callers know
            # the pipeline is not yet executable.
            raise
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

TRANSFORMS_SQL = [
    # 1. Fix any invalid multipolygons. Log a count so the implementer can
    #    spot-check whether ST_MakeValid produced anything dramatic.
    """
    UPDATE admin_regions_staging
       SET geom = ST_Multi(ST_MakeValid(geom::geometry))::geography
     WHERE NOT ST_IsValid(geom::geometry);
    """,
    # 2. Compute centroids. ST_PointOnSurface is preferred over ST_Centroid for
    #    archipelagos — centroid can land in ocean for island chains like Riau
    #    or Maluku, which trips the coastal-buffer heuristic unpredictably.
    """
    UPDATE admin_regions_staging
       SET geom_centroid = ST_PointOnSurface(geom::geometry)::geography;
    """,
    # 3. Resolve parent_osm_id for kabupaten/kota by spatial containment.
    #    LATERAL join picks the first (and usually only) province whose polygon
    #    contains the kabupaten's centroid.
    """
    UPDATE admin_regions_staging kab
       SET parent_osm_id = prov.osm_id
      FROM admin_regions_staging prov
     WHERE kab.level = 'kabupaten_kota'
       AND prov.level = 'province'
       AND ST_Covers(prov.geom::geometry, kab.geom_centroid::geometry);
    """,
    # 4. Coastal buffer. 22km ≈ 0.198 degrees at equator; acceptable approximation
    #    for Indonesian lat range (−11..6.5). See README "Output determinism notes".
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
       AND ST_DWithin(kab.geom_centroid::geometry, cb.coast, 50000.0 / 111320.0);
    """,
    # 5. Post-buffer: centroids may shift slightly. Recompute so step-2 of the
    #    trigger's fallback ladder gets a consistent tie-breaker.
    """
    UPDATE admin_regions_staging
       SET geom_centroid = ST_PointOnSurface(geom::geometry)::geography
     WHERE level = 'kabupaten_kota';
    """,
]


def stage_and_transform(conn: psycopg.Connection, rows: Iterable[Region]) -> None:
    """Run the staging DDL + INSERT each row + apply transforms."""
    with conn.cursor() as cur:
        cur.execute(STAGING_DDL)
        n = 0
        for r in rows:
            cur.execute(INSERT_ROW_SQL, (r.osm_id, r.name, r.level, r.wkt))
            n += 1
        sys.stderr.write(f"[stage] {n} rows inserted into admin_regions_staging\n")

        for sql in TRANSFORMS_SQL:
            cur.execute(sql)

        # Post-transform validity check. If this ever fires, _relation_to_wkt
        # produced something ST_MakeValid couldn't fix — bail rather than
        # emit SQL that Flyway will reject.
        cur.execute(
            "SELECT COUNT(*) FROM admin_regions_staging WHERE NOT ST_IsValid(geom::geometry)"
        )
        invalid_count = cur.fetchone()[0]
        if invalid_count:
            raise RuntimeError(
                f"{invalid_count} rows still invalid after ST_MakeValid — abort."
            )
        conn.commit()


# ---------------------------------------------------------------------------
# SQL emission
# ---------------------------------------------------------------------------


EMIT_SQL = """
SELECT level,
       osm_id,
       name,
       parent_osm_id,
       ST_AsText(geom::geometry)          AS geom_wkt,
       ST_AsText(geom_centroid::geometry) AS centroid_wkt
  FROM admin_regions_staging
 ORDER BY CASE level WHEN 'province' THEN 0 ELSE 1 END, osm_id;
"""

INSERT_TEMPLATE = (
    "INSERT INTO admin_regions (id, name, level, parent_id, geom, geom_centroid) "
    "VALUES ({id}, {name}, '{level}', {parent}, "
    "ST_Multi(ST_GeomFromText('{geom}', 4326))::geography, "
    "ST_GeomFromText('{centroid}', 4326)::geography);"
)


def _sql_quote(s: str) -> str:
    return "'" + s.replace("'", "''") + "'"


def emit(conn: psycopg.Connection, out) -> int:
    """Emit INSERT statements. Returns row count for stderr summary."""
    out.write("-- admin_regions seed — generated by dev/scripts/import-admin-regions/generate-seed.py\n")
    out.write(f"-- Source: OpenStreetMap via Overpass API; attribution: {ODBL_ATTRIBUTION}\n")
    out.write("-- Coastal kabupaten polygons carry a 12nm (~22km) maritime buffer baked into geom.\n")
    out.write("-- Sort: provinces first (parent_id NULL), then kabupaten/kota (parent_id resolved).\n")
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
    ap.add_argument("--db-url", default=DEFAULT_DB_URL, help="Postgres connection URL (default: local dev compose).")
    args = ap.parse_args()

    # Parse all three GeoJSONs. DKI's admin_level=5 row in kabupaten-kota.geojson
    # is SKIPPED here — we use the 6 admin_level=6 rows from dki-kotamadya.geojson
    # instead. Filter by OSM ID of the DKI relation (discovered in fetch step)
    # to drop it deterministically.
    # TODO(session-2): hardcode DKI_KABUPATEN_LEVEL5_OSM_ID after running
    # fetch-overpass.sh once and inspecting kabupaten-kota.geojson.
    DKI_KABUPATEN_LEVEL5_OSM_ID = None  # e.g., 6362934 — confirm at run time.

    provinces = list(parse_overpass_file(DATA_DIR / "provinces.geojson", "province"))
    kabupaten_kota_raw = list(
        parse_overpass_file(DATA_DIR / "kabupaten-kota.geojson", "kabupaten_kota")
    )
    kabupaten_kota = [
        r for r in kabupaten_kota_raw
        if DKI_KABUPATEN_LEVEL5_OSM_ID is None or r.osm_id != DKI_KABUPATEN_LEVEL5_OSM_ID
    ]
    dki_kotamadya = list(
        parse_overpass_file(DATA_DIR / "dki-kotamadya.geojson", "kabupaten_kota")
    )

    all_rows = provinces + kabupaten_kota + dki_kotamadya
    sys.stderr.write(
        f"[parse] provinces={len(provinces)} "
        f"kabupaten_kota={len(kabupaten_kota)} "
        f"dki_kotamadya={len(dki_kotamadya)} "
        f"total={len(all_rows)}\n"
    )

    with psycopg.connect(args.db_url) as conn:
        stage_and_transform(conn, all_rows)

        if args.out:
            args.out.parent.mkdir(parents=True, exist_ok=True)
            with args.out.open("w", encoding="utf-8") as f:
                n = emit(conn, f)
            sys.stderr.write(f"[emit] {n} INSERTs → {args.out}\n")
        else:
            n = emit(conn, sys.stdout)
            sys.stderr.write(f"[emit] {n} INSERTs → stdout\n")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
