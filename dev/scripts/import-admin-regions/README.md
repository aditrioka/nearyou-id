# admin_regions import pipeline

Reproducible one-shot pipeline that pulls Indonesian kabupaten/kota + province boundaries from **OpenStreetMap via the Overpass API**, stages them into a local Postgres+PostGIS, applies the spec-required transforms (12 nautical mile maritime buffer on coastal kabupaten + `ST_MakeValid` on invalid multipolygons), and emits a deterministic SQL seed block ready to append to `backend/ktor/src/main/resources/db/migration/V11__admin_regions.sql`.

## Why this exists

Session 1 of the `global-timeline-with-region-polygons` change landed the V11 schema (table + indexes + `posts_set_city_tg` trigger + `visible_posts` refresh) without the polygon seed, so `admin_regions` starts empty and the trigger harmlessly falls through to step 4. Session 2 is this pipeline — run it once, commit the output, and the Global/Nearby/Following endpoints start surfacing real `city_name` labels on every post.

The pipeline is scripted (not ad-hoc) so that:
- A future polygon refresh (re-seed with newer OSM data) is one command, not a re-derivation of 540 rows by hand.
- The transforms (coastal-buffer heuristic, DKI kotamadya special-case, ID derivation from OSM relation ID) are auditable as code, not as prose in a commit message.
- The output is deterministic given a pinned Overpass timestamp — two invocations on the same day produce byte-identical SQL.

## Dataset decision — OSM (design Open Question 1, resolved)

- **Source**: OSM `admin_level = 5` for kabupaten/kota, `admin_level = 6` for DKI Jakarta's 5 kotamadya + Kepulauan Seribu, `admin_level = 4` for provinces.
- **License**: ODbL. Share-alike does NOT cascade to this project's use (the product projects `city_name` strings into API responses, not derived polygon data).
- **Attribution string** (locked — used in V11 header + app legal section): *"Administrative boundaries © OpenStreetMap contributors, available under the Open Database License (ODbL)."*
- **Why not BPS**: availability risk + boundary-purity arguments evaporate once the 12nm maritime buffer is applied. Full rationale in `design.md` Open Question 1.

## Pre-reqs

- Local Postgres+PostGIS reachable at `$DB_URL` (default matches repo dev compose: `jdbc:postgresql://localhost:5433/nearyou_dev`, user `postgres`, password `postgres`). Bring up with `docker compose -f dev/docker-compose.yml up -d`.
- V1–V11 Flyway migrations applied (so `admin_regions` exists with zero rows).
- `curl` + `jq` (bash script).
- Python 3.11+ with `psycopg[binary]` (install: `pip install 'psycopg[binary]>=3.1'`). No other runtime deps — GeoJSON parsing uses stdlib.

## Pipeline

```
┌─────────────────────────┐
│ fetch-overpass.sh       │  Overpass API → raw GeoJSON (~15-25 MB)
│                         │  - provinces.geojson      (admin_level=4)
│                         │  - kabupaten-kota.geojson (admin_level=5)
│                         │  - dki-kotamadya.geojson  (admin_level=6 inside DKI)
└───────────┬─────────────┘
            │ committed-ignored data/ dir (gitignored)
            ▼
┌─────────────────────────┐
│ generate-seed.py        │  GeoJSON → staging table → transforms → SQL
│                         │  1. Parse each GeoJSON, extract stable OSM
│                         │     relation ID as admin_regions.id.
│                         │  2. Stage into scratch table admin_regions_staging
│                         │     (DROPped + re-created every run).
│                         │  3. Apply ST_MakeValid to any invalid geoms.
│                         │  4. Compute geom_centroid via ST_PointOnSurface
│                         │     (robust for L-shaped / archipelago polygons).
│                         │  5. Resolve parent_id for kabupaten/kota via
│                         │     spatial containment (kota inside province).
│                         │  6. Detect coastal kabupaten (centroid within
│                         │     50 km of national ST_Boundary(ST_Union(provinces))).
│                         │  7. Apply ST_Buffer(geom::geometry, ~22km).
│                         │  8. SELECT back, format as INSERT statements
│                         │     (provinces first, then kabupaten/kota), emit
│                         │     a single SQL block to stdout or --out FILE.
└───────────┬─────────────┘
            │
            ▼
        V11__admin_regions.sql (append between DDL and trigger)
```

## Running it

```bash
cd dev/scripts/import-admin-regions

# Step 1 — fetch. Takes ~5-10 min; Overpass public server can queue on busy days.
./fetch-overpass.sh

# Step 2 — generate seed SQL. Takes ~2 min on a modern laptop.
python3 generate-seed.py --out ../../backend/ktor/src/main/resources/db/migration/V11_seed.sql

# Step 3 — append to V11. Manual edit: paste V11_seed.sql content between
# the DDL (end of indexes) and the trigger creation, matching design Decision 3
# ordering (DDL → indexes → province seed → kabupaten/kota seed → trigger → view).
# Or ship as V12__admin_regions_seed.sql if V11 is already merged without seed
# (deviation from design Decision 3 — document the split in design.md if so).

# Step 4 — re-run Flyway to verify.
./gradlew :backend:ktor:flywayMigrate

# Step 5 — spot-check row count + DKI presence.
psql -h localhost -p 5433 -U postgres -d nearyou_dev -c \
  "SELECT level, COUNT(*) FROM admin_regions GROUP BY level"
# Expect: province ~38, kabupaten_kota ~510-520 (varies with OSM vintage)

psql -h localhost -p 5433 -U postgres -d nearyou_dev -c \
  "SELECT name FROM admin_regions WHERE name LIKE 'Jakarta %' OR name = 'Kepulauan Seribu' ORDER BY name"
# Expect: Jakarta Barat, Jakarta Pusat, Jakarta Selatan, Jakarta Timur,
#         Jakarta Utara, Kepulauan Seribu
```

## Output determinism notes

- OSM relation IDs are stable across re-seeds. A kabupaten renamed or resplit in OSM upstream → its row UPDATEs by ID, not re-INSERTs. Per design Decision 8.
- The Overpass API returns relations in arbitrary order. `generate-seed.py` sorts by `(level, id)` before emission, so diffs between runs reflect only real upstream changes.
- The 12nm buffer uses `ST_Buffer(geom::geometry, <degrees>)` with a per-row degree conversion appropriate for the polygon's latitude. This is a reasonable approximation for the 22 km band; exact geodesic buffering (`ST_Buffer(geom::geography, 22000)`) is more expensive and produces visually-identical results at this scale.

## Coastal-kabupaten heuristic

Per task 1.3.5 and `docs/02-Product.md:200–203`:

> A kabupaten is "coastal" if its centroid lies within 50 km of Indonesia's coastline. Coastline = `ST_Boundary(ST_Union(all_province_polygons))`.

This is a one-shot computation run server-side in the staging DB:

```sql
WITH country_boundary AS (
    SELECT ST_Boundary(ST_Union(geom::geometry))::geography AS coast
    FROM admin_regions_staging
    WHERE level = 'province'
)
UPDATE admin_regions_staging kab
   SET geom = ST_Buffer(kab.geom::geometry, 22000.0 / 111320.0 /* ~22km in degrees at equator */)::geography
  FROM country_boundary
 WHERE kab.level = 'kabupaten_kota'
   AND ST_DWithin(kab.geom_centroid, country_boundary.coast, 50000);
```

Land-locked kabupaten (e.g., Bandung, most of Kalimantan interior) skip the buffer. Coastal kabupaten get their polygon extended outward by ~22 km into adjacent sea, so posts at sea nearshore match step 1 of the fallback ladder with `city_match_type = 'strict'`.

## DKI Jakarta special case

Both BPS and OSM model DKI Jakarta as a single `admin_level = 5` relation (one "kabupaten-equivalent" that spans all 5 kotamadya + Kepulauan Seribu). Product spec requires the 6 child entities at `level = 'kabupaten_kota'` with `parent_id` pointing to DKI's province row.

The scaffold handles this automatically:

1. `fetch-overpass.sh` issues a separate query at `admin_level = 6` inside DKI's area for the 5 kotamadya + Kepulauan Seribu.
2. `generate-seed.py` skips OSM's DKI `admin_level = 5` row (it would otherwise conflate all of Jakarta into one polygon) and inserts only the 6 `admin_level = 6` children at `kabupaten_kota`, with `parent_id` resolved to DKI's `admin_level = 4` province row.

No manual polygon-splitting required.

## Historical context (pipeline already run; preserved for reproducibility)

> **Status (2026-05-07).** This pipeline RAN successfully on 2026-04-25, producing the 552-row polygon seed shipped as `backend/ktor/src/main/resources/db/migration/V12__admin_regions_seed.sql` (34.5 MB). Earlier drafts of this README marked the scripts as "scaffold only" — that label is no longer accurate; V12 is in production. The directory is preserved so a future re-seed (after an Overpass dataset refresh, an admin-boundary change, or a polygon-precision upgrade) can follow the same pipeline rather than starting over.

The notes below are the lessons learned during the original 2026-04-25 run, retained so a re-seed doesn't re-discover them. The original `tasks.md` for the change is archived under `openspec/changes/archive/2026-04-25-global-timeline-with-region-polygons/tasks.md`.

1. **Overpass area IDs require explicit verification at run time.** The original scaffold hardcoded `area:3600304716` — that is the OSM area for an INDIAN relation, not Indonesia. The correct Indonesia area is `area:3600304751` (relation 304751). Before running, fetch `relation(304751); out tags;` and confirm the response contains `name=Indonesia` + `ISO3166-1=ID`. Do NOT trust comments in old import scripts — OSM relation IDs can be re-numbered, and pasting the value forward without verification was the root cause of 3 wasted fetch cycles in PR [#31](https://github.com/aditrioka/nearyou-id/pull/31).
2. **DKI Jakarta polygon split.** OSM models DKI as a single `admin_level = 5` row; product wants the 5 kotamadya + Kepulauan Seribu instead. The pipeline issues a separate Overpass query at `admin_level = 6` inside DKI's area (NOT `admin_level = 5` as initially assumed) and skips DKI's own row to avoid conflation. This was the second discovery in PR [#31](https://github.com/aditrioka/nearyou-id/pull/31).
3. **Degree-vs-geography buffer.** The 22 km maritime buffer is applied as a fixed-degree buffer in degree space. 22 km in degrees at latitude 0 ≠ 22 km at latitude −11; if a future re-seed requires precise km-equidistant buffering, switch to `ST_Buffer(geog, 22000)` and accept the perf hit (one-time during import, not per-request).
4. **Antimeridian-crossing relations.** OSM relations for parts of Maluku Utara / Papua can produce degenerate geometries via `ST_Union`. Validate the seed file against a staging DB before merging to main.
5. **Record the exact Overpass timestamp.** The V12 header comment includes the import timestamp so reviewers can verify row counts against a known OSM snapshot.

If you start a fresh re-seed, copy this README into a dated subfolder under `openspec/changes/archive/<re-seed-change-name>/` so future contributors can see both the original-run notes and the re-seed-run notes side by side.
