## ADDED Requirements

### Requirement: V11 introduces the first spatially-seeded reference table plus a BEFORE INSERT trigger

Migration `V11__admin_regions_and_post_city.sql` SHALL land in a single Flyway file and SHALL atomically:
1. Create the `admin_regions` table and its indexes.
2. Seed `admin_regions` with Indonesian provinces + kabupaten/kota polygons from the chosen dataset.
3. Add `city_name TEXT NULL` and `city_admin_region_id INT NULL REFERENCES admin_regions(id) ON DELETE SET NULL` to `posts`.
4. Create the PL/pgSQL function `posts_set_city_fn()` and the BEFORE INSERT trigger `posts_set_city_tg`.
5. Issue `CREATE OR REPLACE VIEW visible_posts AS SELECT * FROM posts WHERE is_auto_hidden = FALSE` (unchanged semantics; re-creation picks up the two new columns in the view's projection).

The migration MUST execute in a single Flyway transaction (Flyway's default behavior for SQL migrations against PostgreSQL). Splitting any of the five steps into a separate versioned migration is explicitly rejected — the trigger's `SELECT ... FROM admin_regions` would dereference an unseeded or nonexistent table between migrations.

#### Scenario: V11 applies cleanly on an empty V10-schema DB
- **WHEN** Flyway runs V11 against a Postgres+PostGIS instance with V1–V10 applied
- **THEN** the migration succeeds AND `flyway_schema_history` records V11 AND all five steps are reflected in the DB

#### Scenario: V11 is a single file
- **WHEN** listing `backend/ktor/src/main/resources/db/migration/V11*`
- **THEN** exactly one file matches: `V11__admin_regions_and_post_city.sql`

### Requirement: V11 header carries dataset licensing and consumer list

The V11 migration file SHALL open with a header comment block that includes:
1. The dataset source (BPS or OSM) and its license (CC-BY 4.0 or ODbL).
2. The attribution text required by the license, formatted as it must appear in the app's legal section.
3. The list of read-path consumers that MUST bidirectionally join `user_blocks` on top of `visible_posts`: `nearby-timeline`, `following-timeline`, `global-timeline` (extending the list documented in V5 and V6 headers).
4. A note that the `posts_set_city_tg` trigger is the first BEFORE INSERT trigger in the migration history AND that the `admin_regions` seed is the first spatially-seeded reference table.

#### Scenario: Header present and non-empty
- **WHEN** reading the first 50 lines of `V11__admin_regions_and_post_city.sql`
- **THEN** a comment block is present covering the four items above

#### Scenario: Attribution text disclosable
- **WHEN** grepping the header for `CC-BY` OR `ODbL`
- **THEN** at least one match is found AND the exact attribution string intended for the app's legal section is included verbatim

### Requirement: V11 precedent for future polygon updates

Future changes that re-seed `admin_regions` (e.g., annual BPS boundary refresh, OSM refresh) SHALL land as new versioned migrations (`V<later>__refresh_admin_regions.sql`), NOT as edits to V11 (Flyway checksum immutability). The refresh migration MUST use `UPDATE admin_regions SET ... WHERE id = ...` (preserving the stable `id` per `region-polygons` capability Decision 8) and MAY use `INSERT ... ON CONFLICT (id) DO UPDATE` for newly-split regions. Repeatable (`R__`) migrations are explicitly NOT used for `admin_regions` — the project has no `R__` precedent and introducing one for a single reference table is scope creep.

#### Scenario: Refresh migration shape
- **WHEN** a hypothetical future `V14__refresh_admin_regions.sql` is authored
- **THEN** it contains UPSERT-by-id statements AND does NOT delete + re-insert rows (which would orphan `posts.city_admin_region_id` references)

#### Scenario: No R__ migrations for admin_regions
- **WHEN** listing `backend/ktor/src/main/resources/db/migration/R__admin_regions*`
- **THEN** no file matches (the reference-data refresh pattern is versioned migrations, not repeatables)

### Requirement: V11 is additive and safe on live DB

V11 MUST NOT alter, drop, or rename any existing column. It MUST NOT re-define any existing index. It MUST NOT modify existing constraints on `users`, `posts`, `follows`, `user_blocks`, `post_likes`, `post_replies`, `reports`, `moderation_queue`, or `notifications`. The ALTER TABLE additions on `posts` MUST use nullable columns (no `NOT NULL` + default combination that forces a rewrite on a large table). Brief exclusive lock on `ALTER TABLE posts ADD COLUMN ...` is acceptable (seconds at Supabase Pro prod scale).

The V11 migration is deploy-safe without downtime: Ktor writers continue to INSERT into `posts` (the trigger is idempotent under the `IF ... IS NULL` guard), and existing readers continue to `SELECT * FROM visible_posts` (the two new columns are additive).

#### Scenario: No DROP / RENAME / ALTER COLUMN on existing tables
- **WHEN** scanning V11 for `DROP COLUMN`, `ALTER COLUMN`, or `RENAME COLUMN`
- **THEN** no match is found

#### Scenario: New columns are nullable
- **WHEN** reading the `ALTER TABLE posts ADD COLUMN city_name` and `... city_admin_region_id` statements
- **THEN** both lack `NOT NULL` (and lack any `DEFAULT` that would require rewriting the existing `posts` rows)
