## ADDED Requirements

### Requirement: V11 introduces the first spatially-seeded reference table plus a BEFORE INSERT trigger

Migration `V11__admin_regions.sql` SHALL land in a single Flyway file and SHALL atomically:
1. Create the `admin_regions` table and its indexes (GIST on `geom` and `geom_centroid`, btree on `level` and `parent_id`).
2. Seed `admin_regions` with Indonesian provinces (~38 rows) and kabupaten/kota polygons (~500 rows) from the chosen dataset. Coastal polygons are pre-buffered by 12 nm (~22 km) at import time (see `region-polygons` capability).
3. Create the PL/pgSQL function `posts_set_city_fn()` implementing the 4-step fallback ladder (strict → buffered_10m → fuzzy_match → NULL) and the BEFORE INSERT trigger `posts_set_city_tg` on `posts`.
4. Issue `CREATE OR REPLACE VIEW visible_posts AS SELECT * FROM posts WHERE is_auto_hidden = FALSE` as an idempotent no-op refresh — `posts.city_name` and `posts.city_match_type` already exist from V4 and were already projected via `SELECT *`.

V11 MUST NOT execute any `ALTER TABLE posts`. The target columns for the trigger (`city_name`, `city_match_type`) already exist on `posts` since V4.

The migration MUST execute in a single Flyway transaction (Flyway's default behavior for SQL migrations against PostgreSQL). Splitting any of the four steps into a separate versioned migration is explicitly rejected — the trigger's `SELECT ... FROM admin_regions` would dereference an unseeded or nonexistent table between migrations.

#### Scenario: V11 applies cleanly on an empty V10-schema DB
- **WHEN** Flyway runs V11 against a Postgres+PostGIS instance with V1–V10 applied
- **THEN** the migration succeeds AND `flyway_schema_history` records V11 AND all four steps are reflected in the DB

#### Scenario: V11 is a single file
- **WHEN** listing `backend/ktor/src/main/resources/db/migration/V11*`
- **THEN** exactly one file matches: `V11__admin_regions.sql`

#### Scenario: V11 contains no ALTER TABLE posts
- **WHEN** scanning `V11__admin_regions.sql` for `ALTER TABLE posts`
- **THEN** no match is found

### Requirement: V11 header carries dataset licensing and consumer list

The V11 migration file SHALL open with a header comment block that includes:
1. The dataset source (BPS or OSM) and its license (CC-BY 4.0 or ODbL).
2. The attribution text required by the license, formatted as it must appear in the app's legal section.
3. The list of read-path consumers that MUST bidirectionally join `user_blocks` on top of `visible_posts`: `nearby-timeline`, `following-timeline`, `global-timeline` (extending the list documented in V5 and V6 headers).
4. A note that the `posts_set_city_tg` trigger is the first BEFORE INSERT trigger in the migration history AND that the `admin_regions` seed is the first spatially-seeded reference table.
5. A note that the coastal kabupaten polygons were pre-buffered by 12 nm (~22 km) at import time per `docs/02-Product.md:200–203`.

#### Scenario: Header present and non-empty
- **WHEN** reading the first 50 lines of `V11__admin_regions.sql`
- **THEN** a comment block is present covering the five items above

#### Scenario: Attribution text disclosable
- **WHEN** grepping the header for `CC-BY` OR `ODbL`
- **THEN** at least one match is found AND the exact attribution string intended for the app's legal section is included verbatim

### Requirement: V11 precedent for future polygon updates

Future changes that re-seed `admin_regions` (e.g., annual BPS boundary refresh, OSM refresh) SHALL land as new versioned migrations (`V<later>__refresh_admin_regions.sql`), NOT as edits to V11 (Flyway checksum immutability). The refresh migration MUST use `UPDATE admin_regions SET ... WHERE id = ...` (preserving the stable `id` per `region-polygons` capability Decision 8) and MAY use `INSERT ... ON CONFLICT (id) DO UPDATE` for newly-split regions. Repeatable (`R__`) migrations are explicitly NOT used for `admin_regions` — the project has no `R__` precedent and introducing one for a single reference table is scope creep.

#### Scenario: Refresh migration shape
- **WHEN** a hypothetical future `V14__refresh_admin_regions.sql` is authored
- **THEN** it contains UPSERT-by-id statements AND does NOT delete + re-insert rows (which would churn the `parent_id` self-FK referents and lose seed-stability)

#### Scenario: No R__ migrations for admin_regions
- **WHEN** listing `backend/ktor/src/main/resources/db/migration/R__admin_regions*`
- **THEN** no file matches (the reference-data refresh pattern is versioned migrations, not repeatables)

### Requirement: V11 is additive and safe on live DB

V11 MUST NOT alter, drop, or rename any existing column. It MUST NOT re-define any existing index. It MUST NOT modify existing constraints on `users`, `posts`, `follows`, `user_blocks`, `post_likes`, `post_replies`, `reports`, `moderation_queue`, or `notifications`. No `ALTER TABLE posts` runs — the migration's only writes to `posts` are via the new trigger's BEFORE INSERT path on subsequent inserts.

The V11 migration is deploy-safe without downtime: Ktor writers continue to INSERT into `posts` (the trigger's caller-override guard means any explicit `city_name` supplied by the caller is preserved), and existing readers continue to `SELECT * FROM visible_posts` (no column changed).

The migration file itself is large (~15–25 MB of polygon WKT inline). That is an expected one-time cost for the spatially-seeded reference table; GitHub and Flyway handle it.

#### Scenario: No DROP / RENAME / ALTER COLUMN on existing tables
- **WHEN** scanning V11 for `DROP COLUMN`, `ALTER COLUMN`, `RENAME COLUMN`, or `ALTER TABLE posts`
- **THEN** no match is found

#### Scenario: Existing readers unaffected
- **WHEN** the Nearby and Following timeline queries run after V11 against a DB with existing V10-era rows
- **THEN** both queries return the same rows as before V11 (the trigger only affects new INSERTs; the view projection is unchanged)
