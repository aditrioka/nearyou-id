## ADDED Requirements

### Requirement: admin_regions table created via Flyway V11

The next unused Flyway migration (file `V11__admin_regions_and_post_city.sql`) SHALL create the `admin_regions` table exactly as specified in `docs/05-Implementation.md` lines 1007–1017, with columns:
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
- **WHEN** reading the first 40 lines of `V11__admin_regions_and_post_city.sql`
- **THEN** the file contains a comment block naming the dataset source (BPS or OSM) AND the license (CC-BY 4.0 or ODbL) AND the attribution string to be surfaced in the app's legal section

### Requirement: posts.city_name and posts.city_admin_region_id columns added in V11

V11 SHALL add two nullable columns to `posts`:
- `city_name TEXT NULL` — the snapshot display string at the time of INSERT.
- `city_admin_region_id INT NULL REFERENCES admin_regions(id) ON DELETE SET NULL` — the stable FK that survives polygon re-seeds.

Both columns MUST be nullable: legacy pre-V11 rows stay NULL and the Global-timeline response spec renders `""`; polygon-coverage gaps also yield NULL without failing the INSERT. The `ON DELETE SET NULL` on the FK ensures that future admin-driven deletion of an `admin_regions` row does NOT cascade-delete posts but nulls their FK while preserving `city_name` as a frozen string snapshot.

#### Scenario: Columns present after V11
- **WHEN** querying `information_schema.columns WHERE table_name = 'posts' AND column_name IN ('city_name', 'city_admin_region_id')`
- **THEN** both rows exist AND `is_nullable = 'YES'` for both

#### Scenario: FK cascade on admin_regions delete sets NULL
- **WHEN** a `posts` row has `city_admin_region_id = R` AND the `admin_regions` row with `id = R` is hard-deleted
- **THEN** the `posts.city_admin_region_id` is updated to NULL AND `posts.city_name` retains its previous string value

### Requirement: posts_set_city_tg BEFORE INSERT trigger populates city columns

V11 SHALL create a PL/pgSQL function `posts_set_city_fn()` and a `BEFORE INSERT` trigger `posts_set_city_tg` on `posts` that runs `FOR EACH ROW`. The function body MUST be:

```sql
IF NEW.city_admin_region_id IS NULL AND NEW.city_name IS NULL THEN
  SELECT id, name
    INTO NEW.city_admin_region_id, NEW.city_name
    FROM admin_regions
   WHERE level = 'kabupaten_kota'
     AND ST_Contains(geom::geometry, NEW.actual_location::geometry)
   LIMIT 1;
END IF;
RETURN NEW;
```

Behavior:
- If the post's `actual_location` falls inside exactly one kabupaten/kota polygon, `city_admin_region_id` and `city_name` on the NEW row are set to that polygon's `id` and `name`.
- If no polygon contains the point (rare — polygon-coverage gap), both fields remain NULL. The INSERT MUST still succeed.
- If the caller already supplied values for both fields (e.g., a backfill or admin import path), the trigger MUST NOT overwrite them. The `IF ... IS NULL AND ... IS NULL` guard enforces this.
- The trigger uses `NEW.actual_location` (NOT `NEW.display_location`). This is a sanctioned DB-side read of `actual_location` per the `coordinate-jitter` capability's updated allowlist.

#### Scenario: Trigger populates city on insert inside polygon
- **WHEN** a post is inserted with `actual_location` inside the "Jakarta Selatan" polygon AND the INSERT does not supply `city_name` or `city_admin_region_id`
- **THEN** after the INSERT, the row has `city_name = 'Jakarta Selatan'` AND `city_admin_region_id` equals the id of that polygon row

#### Scenario: Trigger leaves fields NULL when point outside any polygon
- **WHEN** a post is inserted with `actual_location` in a polygon-coverage gap (e.g., offshore)
- **THEN** the INSERT succeeds AND the row has `city_name IS NULL` AND `city_admin_region_id IS NULL`

#### Scenario: Trigger does not overwrite caller-supplied values
- **WHEN** an INSERT explicitly sets `city_name = 'Bali'` AND `city_admin_region_id = <id of some admin_regions row>`
- **THEN** the trigger's `SELECT ... INTO` branch does NOT execute AND the inserted row retains the caller-supplied values unchanged

#### Scenario: Trigger reads actual_location, not display_location
- **WHEN** reading the function body via `pg_get_functiondef`
- **THEN** the body contains `NEW.actual_location` AND does NOT contain `NEW.display_location`

### Requirement: No read-time reverse-geocoding

Business read paths (Nearby, Following, Global timelines, user profile endpoints, post-detail endpoints) MUST NOT perform `ST_Contains` against `admin_regions` at query time. The only sanctioned reader of `admin_regions` polygons on the hot path is `posts_set_city_tg`. Admin-module paths (Phase 3.5) MAY query `admin_regions` directly for maintenance tooling.

#### Scenario: Timeline services do not JOIN admin_regions
- **WHEN** grepping `backend/ktor/src/main/kotlin/id/nearyou/app/timeline/` for `admin_regions` or `ST_Contains`
- **THEN** neither token is found

### Requirement: Optional backfill job for legacy posts

An optional backfill code path SHALL be available that applies `UPDATE posts p SET city_name = r.name, city_admin_region_id = r.id FROM admin_regions r WHERE r.level = 'kabupaten_kota' AND ST_Contains(r.geom::geometry, p.actual_location::geometry) AND p.city_name IS NULL`. The code path is NOT required to run automatically during migration and is NOT required for Global-timeline correctness (which tolerates NULL). It SHALL be invokable either as a one-shot admin SQL migration or as a manually-triggered Ktor CLI flag; the exact trigger mechanism is implementation-defined and is not part of the spec contract. Tracking: `tasks.md` carries it as optional.

#### Scenario: Backfill is idempotent
- **WHEN** the backfill is run twice in succession
- **THEN** the second run updates zero rows (the `AND p.city_name IS NULL` guard makes the UPDATE idempotent)

#### Scenario: Backfill does not disturb trigger-populated rows
- **WHEN** a post inserted after V11 deployed (and therefore city-populated by the trigger) exists AND the backfill runs
- **THEN** the backfill does NOT re-compute or overwrite that row (the `p.city_name IS NULL` guard skips it)
