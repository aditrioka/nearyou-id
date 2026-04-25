## ADDED Requirements

### Requirement: admin_regions table created via Flyway V11

The next unused Flyway migration (file `V11__admin_regions.sql`) SHALL create the `admin_regions` table exactly as specified in `docs/05-Implementation.md` lines 1007–1017, with columns:
- `id INT PRIMARY KEY` (surrogate, equal to the dataset's stable code — BPS `kode_wilayah` or OSM relation ID)
- `name TEXT NOT NULL`
- `level TEXT NOT NULL CHECK (level IN ('province', 'kabupaten_kota'))`
- `parent_id INT REFERENCES admin_regions(id)` (self-FK; NULL for `level = 'province'`)
- `geom GEOGRAPHY(MULTIPOLYGON, 4326) NOT NULL`
- `geom_centroid GEOGRAPHY(POINT, 4326) NOT NULL`

Plus indexes:
- `CREATE INDEX admin_regions_geom_idx ON admin_regions USING GIST (geom)`
- `CREATE INDEX admin_regions_centroid_idx ON admin_regions USING GIST (geom_centroid)`
- `CREATE INDEX admin_regions_level_idx ON admin_regions (level)`
- `CREATE INDEX admin_regions_parent_idx ON admin_regions (parent_id)`

#### Scenario: Table exists with canonical columns
- **WHEN** querying `information_schema.columns WHERE table_name = 'admin_regions'`
- **THEN** the column list matches the canonical set above (name + type + nullability)

#### Scenario: GIST indexes present
- **WHEN** querying `pg_indexes WHERE tablename = 'admin_regions'`
- **THEN** both `admin_regions_geom_idx` and `admin_regions_centroid_idx` exist AND their `indexdef` contains `USING gist`

#### Scenario: level CHECK constraint enforced
- **WHEN** attempting `INSERT INTO admin_regions (..., level, ...) VALUES (..., 'kecamatan', ...)`
- **THEN** the INSERT fails with a check-constraint violation

### Requirement: admin_regions seeded with Indonesian kabupaten/kota + provinces in V11

V11 SHALL seed `admin_regions` with:
- Every Indonesian province at `level = 'province'` (~38 rows, `parent_id IS NULL`).
- Every Indonesian kabupaten/kota at `level = 'kabupaten_kota'` (~500 rows, `parent_id` pointing to the province row).
- DKI Jakarta's 5 kotamadya (Jakarta Pusat, Jakarta Utara, Jakarta Selatan, Jakarta Timur, Jakarta Barat) plus Kepulauan Seribu — all at `level = 'kabupaten_kota'`, `parent_id` pointing to the DKI Jakarta province row. This hand-curated fixup is required because both BPS and OSM conflate DKI Jakarta at a single administrative level.

The source dataset MUST be either BPS (CC-BY 4.0) or OpenStreetMap (`admin_level = 5`, ODbL). The migration header MUST state which source was used AND include the attribution text required by the chosen license. Polygon validity (`ST_IsValid(geom) = TRUE`) MUST hold for every seeded row.

#### Scenario: Row count sanity
- **WHEN** querying `SELECT COUNT(*) FROM admin_regions WHERE level = 'kabupaten_kota'`
- **THEN** the count is at least 500 (the exact number depends on dataset vintage but is lower-bounded)

#### Scenario: DKI 5 kotamadya + Kepulauan Seribu at kabupaten_kota level
- **WHEN** querying `SELECT name FROM admin_regions WHERE level = 'kabupaten_kota' AND name LIKE 'Jakarta %' OR name = 'Kepulauan Seribu'`
- **THEN** the result contains exactly `Jakarta Pusat`, `Jakarta Utara`, `Jakarta Selatan`, `Jakarta Timur`, `Jakarta Barat`, `Kepulauan Seribu`

#### Scenario: All seeded polygons valid
- **WHEN** querying `SELECT COUNT(*) FROM admin_regions WHERE NOT ST_IsValid(geom::geometry)`
- **THEN** the count is `0`

#### Scenario: Migration header carries license attribution
- **WHEN** reading the first 40 lines of `V11__admin_regions.sql`
- **THEN** the file contains a comment block naming the dataset source (BPS or OSM) AND the license (CC-BY 4.0 or ODbL) AND the attribution string to be surfaced in the app's legal section

### Requirement: Coastal kabupaten polygons extended by a 12nm maritime buffer at import time

Per [`docs/02-Product.md:200–203`](docs/02-Product.md), coastal kabupaten polygons SHALL be extended by a 12 nautical mile (~22 km) maritime buffer in the import / dataset-prep step BEFORE the SQL INSERT statements are generated. The buffering MUST NOT be applied at query time — the trigger (see below) operates on the already-buffered polygons uniformly regardless of whether a region is inland or coastal.

Posts inside the nearshore maritime buffer MUST therefore match step 1 (`ST_Contains`) of the fallback ladder, so `city_match_type = 'strict'` — not `'fuzzy_match'` — for posts in nearshore waters (e.g., Teluk Jakarta) that legitimately belong to a coastal kabupaten.

#### Scenario: Nearshore post matches strict step
- **WHEN** a post is inserted with `actual_location` 500 m offshore from Jakarta Utara's pre-buffer shoreline (inside the 22 km maritime buffer)
- **THEN** the resulting row has `city_name = 'Jakarta Utara'` AND `city_match_type = 'strict'`

#### Scenario: Buffer applied at import, not at query
- **WHEN** reading the body of `posts_set_city_fn` via `pg_get_functiondef`
- **THEN** the function does NOT call `ST_Buffer` (the buffering is baked into the stored `geom` column during seed)

### Requirement: posts row shape unchanged by V11 — city_name and city_match_type already exist from V4

V11 MUST NOT execute any `ALTER TABLE posts`. The columns the trigger writes to (`city_name TEXT` and `city_match_type VARCHAR(16)`) ALREADY exist on `posts` since V4 (see `backend/ktor/src/main/resources/db/migration/V4__post_creation.sql:22–23`). V11 is strictly additive at the reference-data layer: new `admin_regions` table + new trigger + idempotent `visible_posts` view refresh. No column is added, dropped, renamed, or retyped on `posts`.

Both target columns remain nullable (as defined in V4): legacy pre-V11 rows stay NULL; polygon-coverage gaps (step 4 of the fallback ladder) also yield NULL without failing the INSERT. The Global-timeline response spec renders NULL `city_name` as `""`.

No FK from `posts` to `admin_regions` exists or is introduced by V11. An admin-driven hard-delete of an `admin_regions` row does NOT cascade to `posts`; posts retain their snapshot `city_name` string and their `city_match_type` tag as frozen historical truth.

#### Scenario: No ALTER TABLE posts in V11
- **WHEN** scanning `V11__admin_regions.sql` for `ALTER TABLE posts`
- **THEN** no match is found

#### Scenario: city_name + city_match_type columns pre-exist from V4
- **WHEN** querying `information_schema.columns WHERE table_name = 'posts' AND column_name IN ('city_name', 'city_match_type')`
- **THEN** both rows exist AND `is_nullable = 'YES'` for both AND the columns were introduced by V4 (confirmable via `pg_attribute` ordering or by reading V4's source)

#### Scenario: No FK from posts to admin_regions
- **WHEN** querying `information_schema.table_constraints WHERE table_name = 'posts' AND constraint_type = 'FOREIGN KEY'`
- **THEN** no FK referencing `admin_regions` is present (neither before nor after V11)

### Requirement: city_match_type vocabulary is fixed to four values

`posts.city_match_type` SHALL carry exactly one of four values reflecting which step of the fallback ladder produced the `city_name`:
- `'strict'` — step 1, `ST_Contains` match against the (possibly maritime-buffered) polygon.
- `'buffered_10m'` — step 2, `ST_DWithin` 10-meter buffer with centroid-distance tie-breaker.
- `'fuzzy_match'` — step 3, `ST_DWithin` 50-kilometer nearest-neighbor fallback.
- `NULL` — step 4, no polygon within 50 km; `city_name` is also NULL.

No other values are produced by the trigger. Adding a new value requires amending `docs/02-Product.md` first (canonical), then shipping a change that bumps the enum with an explicit CHECK constraint.

#### Scenario: Only the four sanctioned values appear
- **WHEN** querying `SELECT DISTINCT city_match_type FROM posts`
- **THEN** the result is a subset of `{'strict', 'buffered_10m', 'fuzzy_match', NULL}`

### Requirement: posts_set_city_tg BEFORE INSERT trigger implements the 4-step fallback ladder

V11 SHALL create a PL/pgSQL function `posts_set_city_fn()` and a `BEFORE INSERT` trigger `posts_set_city_tg` on `posts` that runs `FOR EACH ROW`. The function body MUST implement the 4-step fallback ladder specified verbatim in [`docs/02-Product.md:192–196`](docs/02-Product.md) §"Polygon-Based Reverse Geocoding":

```sql
CREATE OR REPLACE FUNCTION posts_set_city_fn() RETURNS TRIGGER AS $$
DECLARE
    matched_name TEXT;
BEGIN
    -- Caller override: if city_name was explicitly supplied, do nothing.
    IF NEW.city_name IS NOT NULL THEN
        RETURN NEW;
    END IF;

    -- Step 1: strict ST_Contains match.
    SELECT name INTO matched_name
      FROM admin_regions
     WHERE level = 'kabupaten_kota'
       AND ST_Contains(geom::geometry, NEW.actual_location::geometry)
     LIMIT 1;
    IF matched_name IS NOT NULL THEN
        NEW.city_name := matched_name;
        NEW.city_match_type := 'strict';
        RETURN NEW;
    END IF;

    -- Step 2: 10-meter buffered match + deterministic centroid tie-breaker.
    SELECT name INTO matched_name
      FROM admin_regions
     WHERE level = 'kabupaten_kota'
       AND ST_DWithin(geom, NEW.actual_location, 10)
     ORDER BY ST_Distance(geom_centroid, NEW.actual_location) ASC
     LIMIT 1;
    IF matched_name IS NOT NULL THEN
        NEW.city_name := matched_name;
        NEW.city_match_type := 'buffered_10m';
        RETURN NEW;
    END IF;

    -- Step 3: nearest kabupaten/kota within 50 km (catches coastal + EEZ-adjacent posts).
    SELECT name INTO matched_name
      FROM admin_regions
     WHERE level = 'kabupaten_kota'
       AND ST_DWithin(geom, NEW.actual_location, 50000)
     ORDER BY ST_Distance(geom, NEW.actual_location) ASC
     LIMIT 1;
    IF matched_name IS NOT NULL THEN
        NEW.city_name := matched_name;
        NEW.city_match_type := 'fuzzy_match';
        RETURN NEW;
    END IF;

    -- Step 4: out of range. Both columns stay NULL.
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
```

Behavior invariants:
- The trigger reads `NEW.actual_location` (NOT `NEW.display_location`). This is a sanctioned DB-side read of `actual_location` per the `coordinate-jitter` capability's updated allowlist.
- Distance arguments in steps 2 and 3 are meters (GEOGRAPHY semantics); both `admin_regions.geom` and `posts.actual_location` are `GEOGRAPHY(*, 4326)`.
- Step 2's `ORDER BY ST_Distance(geom_centroid, NEW.actual_location) ASC LIMIT 1` provides a deterministic tie-breaker against floating-point boundary artifacts.
- The INSERT MUST succeed in all four outcomes, including step 4 where both fields remain NULL.
- The caller-override guard (`IF NEW.city_name IS NOT NULL THEN RETURN NEW`) short-circuits the ladder when a bulk-import / backfill caller explicitly supplies `city_name`. In that case `city_match_type` is also left untouched — the caller owns both columns.

#### Scenario: Step 1 strict match
- **WHEN** a post is inserted with `actual_location` well inside the Jakarta Selatan polygon AND the INSERT does not supply `city_name`
- **THEN** after the INSERT, the row has `city_name = 'Jakarta Selatan'` AND `city_match_type = 'strict'`

#### Scenario: Step 2 buffered_10m match on polygon boundary
- **WHEN** a post is inserted with `actual_location` ~5 m outside Jakarta Pusat's polygon edge (a floating-point boundary artifact) AND Jakarta Pusat is the closest centroid within 10 m
- **THEN** after the INSERT, the row has `city_name = 'Jakarta Pusat'` AND `city_match_type = 'buffered_10m'`

#### Scenario: Step 3 fuzzy_match in coastal gap
- **WHEN** a post is inserted with `actual_location` in an offshore coastal gap beyond the 12nm maritime buffer but within 50 km of a kabupaten polygon (e.g., a small island missing from the dataset)
- **THEN** after the INSERT, the row has `city_name` set to the nearest kabupaten's name AND `city_match_type = 'fuzzy_match'`

#### Scenario: Step 4 NULL in open ocean
- **WHEN** a post is inserted with `actual_location` in the open ocean more than 50 km from any kabupaten polygon
- **THEN** the INSERT succeeds AND the row has `city_name IS NULL` AND `city_match_type IS NULL`

#### Scenario: Caller override short-circuits the ladder
- **WHEN** an INSERT explicitly sets `city_name = 'Bali'` (e.g., a bulk-import path)
- **THEN** the trigger returns NEW without running any of the 4 steps AND the inserted row retains `city_name = 'Bali'` with whatever `city_match_type` the caller supplied (NULL if not set)

#### Scenario: Trigger reads actual_location, not display_location
- **WHEN** reading the function body via `pg_get_functiondef`
- **THEN** the body contains `NEW.actual_location` AND does NOT contain `NEW.display_location`

### Requirement: No read-time reverse-geocoding

Business read paths (Nearby, Following, Global timelines, user profile endpoints, post-detail endpoints) MUST NOT perform `ST_Contains`, `ST_DWithin`, or any polygon join against `admin_regions` at query time. The only sanctioned reader of `admin_regions` polygons on the hot path is `posts_set_city_tg`. Admin-module paths (Phase 3.5) MAY query `admin_regions` directly for maintenance tooling.

#### Scenario: Timeline services do not JOIN admin_regions
- **WHEN** grepping `backend/ktor/src/main/kotlin/id/nearyou/app/timeline/` for `admin_regions`, `ST_Contains`, or `ST_DWithin` against `admin_regions`
- **THEN** no match is found

### Requirement: Optional backfill job for legacy posts

An optional backfill code path SHALL be available that applies the same 4-step fallback ladder to legacy rows with `city_name IS NULL`. The SQL form is a `WITH matched AS (... 4 UNION-ALL'd ladder steps producing (post_id, name, match_type) ...) UPDATE posts SET city_name = m.name, city_match_type = m.match_type FROM matched m WHERE posts.id = m.post_id AND posts.city_name IS NULL`. The code path is NOT required to run automatically during migration and is NOT required for Global-timeline correctness (which tolerates NULL). It SHALL be invokable either as a one-shot admin SQL migration or as a manually-triggered Ktor CLI flag; the exact trigger mechanism is implementation-defined and is not part of the spec contract. Tracking: `tasks.md` carries it as optional.

#### Scenario: Backfill is idempotent
- **WHEN** the backfill is run twice in succession
- **THEN** the second run updates zero rows (the `AND posts.city_name IS NULL` guard makes the UPDATE idempotent)

#### Scenario: Backfill does not disturb trigger-populated rows
- **WHEN** a post inserted after V11 deployed (and therefore city-populated by the trigger) exists AND the backfill runs
- **THEN** the backfill does NOT re-compute or overwrite that row (the `posts.city_name IS NULL` guard skips it)
