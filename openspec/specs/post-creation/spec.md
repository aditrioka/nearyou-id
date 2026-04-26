# post-creation Specification

## Purpose
TBD - created by archiving change post-creation-geo. Update Purpose after archive.
## Requirements
### Requirement: POST /api/v1/posts endpoint

`POST /api/v1/posts` SHALL accept a JSON body `{ content: string, latitude: double, longitude: double }` from an authenticated caller (RS256 JWT). On success it SHALL return HTTP 201 with body `{ id, content, latitude, longitude, distance_m: null, created_at }`. The endpoint MUST be wrapped in `authenticate { ... }`; the authenticated principal's `userId` becomes `posts.author_user_id`.

#### Scenario: Authenticated successful create
- **WHEN** an authenticated caller POSTs a valid body
- **THEN** the response is HTTP 201 AND the body's `id` is a UUID AND `distance_m` is null

#### Scenario: Missing JWT
- **WHEN** the request has no `Authorization` header
- **THEN** the response is HTTP 401

#### Scenario: Stale token_version
- **WHEN** the access token's `token_version` claim is lower than `users.token_version` for that user
- **THEN** the response is HTTP 401

### Requirement: Content length guard — 1 to 280 Unicode code points

The endpoint SHALL reject `content` that is empty after NFKC-normalization and trim with HTTP 400 code `content_empty`. It SHALL reject `content` whose NFKC-normalized code-point length exceeds 280 with HTTP 400 code `content_too_long`. The check MUST be on Unicode code points, not bytes, and MUST happen before the DB INSERT.

#### Scenario: Empty content
- **WHEN** `content` is `""` or only whitespace
- **THEN** the response is HTTP 400 with `error.code = "content_empty"` AND no `posts` row is inserted

#### Scenario: Exactly 280 code points accepted
- **WHEN** `content` is a 280-code-point string
- **THEN** the response is HTTP 201

#### Scenario: 281 code points rejected
- **WHEN** `content` is a 281-code-point string
- **THEN** the response is HTTP 400 with `error.code = "content_too_long"` AND no `posts` row is inserted

### Requirement: Coordinate envelope check

The endpoint SHALL reject requests where `latitude` is outside `[-11.0, 6.5]` or `longitude` is outside `[94.0, 142.0]` with HTTP 400 code `location_out_of_bounds`. This check MUST run before any DB write.

#### Scenario: In-envelope accepted
- **WHEN** `latitude = -6.2, longitude = 106.8` (Jakarta)
- **THEN** the response is HTTP 201

#### Scenario: Out-of-envelope rejected
- **WHEN** `latitude = 40.0, longitude = -74.0` (New York)
- **THEN** the response is HTTP 400 with `error.code = "location_out_of_bounds"` AND no `posts` row is inserted

### Requirement: author_id FK is RESTRICT (not cascade)

The `posts.author_id` foreign key to `users(id)` MUST use `ON DELETE RESTRICT` per `docs/05-Implementation.md § Posts Schema`. The endpoint relies on the authenticated principal's `userId` being a live (non-deleted) `users` row; the tombstone / hard-delete worker (a separate change) is responsible for deleting post rows before the author.

#### Scenario: Bare user delete blocked
- **WHEN** an authenticated user has an existing post AND a direct `DELETE FROM users WHERE id = <that user>` is attempted in the integration test
- **THEN** the DELETE fails with SQLSTATE `23503` (foreign-key violation)

### Requirement: Single-INSERT transactional write

The endpoint SHALL compute `display_location` in the app layer and INSERT the `posts` row in a single statement that populates both `actual_location` and `display_location`. It MUST NOT INSERT with a NULL `display_location` and later UPDATE.

#### Scenario: Both geographies populated on the inserted row
- **WHEN** a successful POST completes
- **THEN** the inserted row's `actual_location` and `display_location` are both non-null AND `ST_Distance(actual_location::geometry, display_location::geometry)` is between 50 and 500 meters inclusive

### Requirement: post_id is UUIDv7 generated in the app layer

The `id` column value for a new `posts` row SHALL be a UUIDv7 generated in the Kotlin app layer before the INSERT, so the HMAC input for `display_location` is known without a DB round-trip.

#### Scenario: UUIDv7 format
- **WHEN** a successful POST completes
- **THEN** the response's `id` is a UUID whose version nibble is `7`

### Requirement: Response body shape on success

The 201 response body SHALL contain exactly the fields `id`, `content`, `latitude`, `longitude`, `distance_m`, `created_at`. `latitude` and `longitude` MUST echo the caller-supplied values (the `actual_location`, not the `display_location`). `distance_m` MUST be `null` because the author is the viewer.

#### Scenario: Field set exact
- **WHEN** parsing the 201 body as JSON
- **THEN** the key set equals `{ "id", "content", "latitude", "longitude", "distance_m", "created_at" }` AND `distance_m` is `null`

### Requirement: Error envelope matches existing auth routes

All 4xx responses SHALL use the envelope `{ "error": { "code": "<kebab-or-snake>", "message": "<human-readable>" } }` already established by `/api/v1/auth/*`. Error codes SHALL be exactly `content_empty`, `content_too_long`, `location_out_of_bounds`, `invalid_json`, and `unauthenticated` (the last emitted by the `Authentication` plugin, not by the route handler).

#### Scenario: Error envelope for content_too_long
- **WHEN** the request is rejected for exceeding 280 code points
- **THEN** the response body is `{ "error": { "code": "content_too_long", "message": "..." } }` AND `message` is non-empty

### Requirement: posts.is_auto_hidden actively written by the V9 reports auto-hide path

V4 introduced `posts.is_auto_hidden BOOLEAN NOT NULL DEFAULT FALSE` and V4's `visible_posts` view filters `WHERE is_auto_hidden = FALSE`. Until V9 no code path flipped the column. V9 introduces the FIRST writer of this column: the reports auto-hide path (see `reports` capability) SHALL execute `UPDATE posts SET is_auto_hidden = TRUE WHERE id = :target_id AND deleted_at IS NULL` when 3 distinct reporters aged > 7 days have reported the post.

The V4 contract — every `SELECT ... FROM visible_posts` query skips rows where `is_auto_hidden = TRUE` — MUST continue to hold, so flipping the column automatically hides the post from every authenticated timeline (`nearby-timeline`, `following-timeline`, future Global timeline) and from search. The `posts` table, the `visible_posts` view definition, and the V4 migration remain unchanged.

The UPDATE MUST be idempotent: running it on an already-hidden post MUST be a no-op (the `SET is_auto_hidden = TRUE` reassignment to the same value is a no-op in Postgres; the WHERE clause's `deleted_at IS NULL` guard prevents an accidental flip on a tombstoned post).

#### Scenario: Auto-hide flip hides post from nearby timeline immediately
- **WHEN** a post is visible in `GET /api/v1/timeline/nearby` AND the reports auto-hide path flips `is_auto_hidden = TRUE`
- **THEN** the next call to `GET /api/v1/timeline/nearby` with the same parameters does NOT return that post (the `visible_posts` view excludes it, no additional read-path change is needed)

#### Scenario: Auto-hide flip hides post from following timeline immediately
- **WHEN** a post is visible in `GET /api/v1/timeline/following` AND the reports auto-hide path flips `is_auto_hidden = TRUE`
- **THEN** the next call to `GET /api/v1/timeline/following` does NOT return that post

#### Scenario: Author still sees their own auto-hidden post on retrieval (if applicable)
- **WHEN** a post is auto-hidden AND the author queries an own-content path that does NOT go through `visible_posts` (e.g., future profile "my posts" endpoint)
- **THEN** the post is still retrievable via that own-content path (the column flip does not soft-delete; it only affects `visible_posts` consumers)

#### Scenario: UPDATE on already-hidden post is a no-op
- **WHEN** the reports auto-hide path runs its UPDATE on a post whose `is_auto_hidden` is already TRUE
- **THEN** the UPDATE affects 0 additional state (no row churn, no trigger cascade)

#### Scenario: UPDATE skips soft-deleted post
- **WHEN** the reports auto-hide path runs its UPDATE on a post whose `deleted_at IS NOT NULL`
- **THEN** the UPDATE affects 0 rows (the WHERE clause excludes soft-deleted targets; the associated `moderation_queue` row is still inserted per the `moderation-queue` capability)

### Requirement: POST /api/v1/posts behavior unchanged by V9

V9 MUST NOT alter the shape, auth, validation, or response of `POST /api/v1/posts`. The column `is_auto_hidden` defaults to `FALSE` on INSERT and stays FALSE until the reports auto-hide path (or a future admin action) flips it.

#### Scenario: New post starts with is_auto_hidden = FALSE
- **WHEN** a successful `POST /api/v1/posts` completes (post-V9)
- **THEN** the inserted row's `is_auto_hidden` column equals `FALSE`

#### Scenario: Response body shape unchanged
- **WHEN** parsing the 201 body as JSON (post-V9)
- **THEN** the key set still equals `{ "id", "content", "latitude", "longitude", "distance_m", "created_at" }` (no `is_auto_hidden` leak)

### Requirement: Post INSERT populates city_name and city_match_type via the 4-step fallback trigger

As of V11, the `posts` INSERT path SHALL populate `city_name` and `city_match_type` via the `posts_set_city_tg` BEFORE INSERT trigger (see `region-polygons` capability), which runs the 4-step fallback ladder from [`docs/02-Product.md:192–196`](docs/02-Product.md): strict `ST_Contains` → 10 m buffered match with centroid tie-breaker → 50 km nearest-neighbor `fuzzy_match` → NULL.

Application code on the post-creation path MUST NOT supply `city_name` or `city_match_type` in its INSERT column list — those columns are reserved for the trigger. The caller's responsibility is unchanged: supply `content`, `latitude`, `longitude`, and the derived `display_location` + `actual_location`.

The `POST /api/v1/posts` handler MAY remain unaware of the trigger; it SHALL NOT issue an additional `SELECT` on `admin_regions` or perform any reverse-geocoding in application code. The trigger is the single source of truth for the post-creation-time reverse-geocode.

#### Scenario: Application code does not reference admin_regions on create path
- **WHEN** grepping `backend/ktor/src/main/kotlin/id/nearyou/app/post/` for `admin_regions`
- **THEN** no match is found (the trigger is DB-side; the application has no reason to read `admin_regions` during post creation)

#### Scenario: INSERT column list does not include city_* columns
- **WHEN** inspecting the SQL issued by `JdbcPostsRepository.insert` (or the equivalent write-path repository)
- **THEN** the INSERT column list does NOT contain `city_name` or `city_match_type` (the trigger populates both)

#### Scenario: Step 1 strict match populates city_name on create
- **WHEN** a post is created via `POST /api/v1/posts` with `latitude`/`longitude` well inside the Jakarta Selatan polygon
- **THEN** any subsequent read of that post (via the Global timeline or future post-detail endpoint) returns `city_name = "Jakarta Selatan"` AND the DB row carries `city_match_type = 'strict'`

#### Scenario: Step 2 buffered_10m match on polygon boundary
- **WHEN** a post is created with coordinates ~5 m outside a kabupaten's polygon edge (floating-point boundary artifact) AND that kabupaten is the nearest centroid within 10 m
- **THEN** the DB row carries that kabupaten's name in `city_name` AND `city_match_type = 'buffered_10m'`

#### Scenario: Step 3 fuzzy_match in coastal/polygon-gap point
- **WHEN** a post is created with coordinates in a coastal or polygon-gap area beyond the 12nm maritime buffer but within 50 km of a kabupaten polygon
- **THEN** the DB row carries the nearest kabupaten's name in `city_name` AND `city_match_type = 'fuzzy_match'`

#### Scenario: Step 4 open-ocean post stays NULL
- **WHEN** a post is created with coordinates more than 50 km from any kabupaten polygon (e.g., deep-sea EEZ)
- **THEN** the INSERT still succeeds (HTTP 201) AND the row has `city_name IS NULL` AND `city_match_type IS NULL`

#### Scenario: Bulk-import caller override
- **WHEN** an admin bulk-import path issues an INSERT explicitly supplying `city_name = 'Bali'`
- **THEN** the trigger's caller-override guard short-circuits the ladder AND the inserted row retains `city_name = 'Bali'` without any of the 4 steps running

### Requirement: Post creation response may project city_name

The `POST /api/v1/posts` response body MAY include a `city_name` field reflecting the trigger-populated value on the newly-created row. This is optional for this capability — clients MUST NOT depend on its presence in the `POST` response; the authoritative source for `city_name` is any subsequent read of the post. If the field is present, it MUST be a JSON string following the same NULL-handling convention as the Global timeline response (underlying NULL serializes as `""`).

The `city_match_type` column is admin/internal only and MUST NOT appear in any client-facing response (POST, GET timeline, GET post-detail). It is consumed by admin tooling and analytics only.

#### Scenario: city_name in POST response consistent with later read
- **WHEN** the `POST /api/v1/posts` response body includes `city_name` AND the same post is later fetched via the Global timeline
- **THEN** the two `city_name` values are identical

#### Scenario: city_match_type never appears in client responses
- **WHEN** inspecting any client-facing response DTO (post creation, Nearby, Following, Global, post-detail)
- **THEN** no field named `city_match_type` (or equivalent camelCase `cityMatchType`) is present

### Requirement: Coordinate-envelope validation unchanged by V11

V11 MUST NOT alter the existing coordinate-envelope validation on the post-creation path. The Indonesia envelope `[-11.0, 6.5]` lat × `[94.0, 142.0]` lng and the `400 location_out_of_bounds` error code remain unchanged. The trigger's fallback ladder is SEPARATE from (and does NOT supersede) the envelope check: the envelope is application-layer cheap rejection; the trigger's polygon lookup is DB-side and produces NULL (not a reject) when the point falls past step 3.

#### Scenario: Envelope rejection still happens pre-INSERT
- **WHEN** a post creation request has `latitude=10.0, longitude=120.0` (outside the envelope)
- **THEN** the response is HTTP 400 with `error.code = "location_out_of_bounds"` AND the INSERT does NOT execute AND the trigger does NOT fire

#### Scenario: Polygon-gap point still accepted
- **WHEN** a post creation request has coordinates inside the envelope but outside every kabupaten/kota polygon after all 4 fallback steps (e.g., far open-ocean point)
- **THEN** the response is HTTP 201 AND the row's `city_name` + `city_match_type` are both NULL

### Requirement: posts.content_tsv GENERATED column + GIN FTS indexes (V13 migration)

A Flyway migration `V13__premium_search_fts.sql` SHALL extend the `posts` table with the FTS infrastructure that V4 explicitly deferred (see V4 file comment: "FTS-specific columns (content_tsv + GIN indexes) are deferred to the Search change.") The migration MUST:

1. `CREATE EXTENSION IF NOT EXISTS pg_trgm;` — creates the trigram extension required for both the `%` similarity operator and `gin_trgm_ops` opclass
2. `ALTER TABLE posts ADD COLUMN content_tsv TSVECTOR GENERATED ALWAYS AS (to_tsvector('simple', content)) STORED;` — matches the canonical column definition in `docs/05-Implementation.md:562`. The `'simple'` config is the explicit MVP choice per `docs/05-Implementation.md:1159` (no Indonesian stopwords / stemming yet)
3. `CREATE INDEX posts_content_tsv_idx ON posts USING GIN(content_tsv);` — supports `@@ plainto_tsquery('simple', :query)` lookups
4. `CREATE INDEX posts_content_trgm_idx ON posts USING GIN(content gin_trgm_ops);` — supports `content % :query` fuzzy lookups

The migration MUST be idempotent against pre-existing `pg_trgm` (the `IF NOT EXISTS` guard handles re-run scenarios). The GENERATED column is automatic and STORED, so no application-side write changes are needed — existing INSERT paths from `POST /api/v1/posts` continue unchanged and the column is populated by Postgres.

#### Scenario: V13 migration creates pg_trgm + content_tsv + GIN indexes
- **WHEN** Flyway runs `V13__premium_search_fts.sql` against a Postgres instance where V12 has completed
- **THEN** the migration succeeds AND `pg_extension` lists `pg_trgm` AND `information_schema.columns` shows `posts.content_tsv` with type `tsvector` AND `pg_indexes` lists both `posts_content_tsv_idx` and `posts_content_trgm_idx` as GIN indexes

#### Scenario: content_tsv auto-populates on existing rows
- **WHEN** V13 runs against a database with pre-existing `posts` rows
- **THEN** every existing row's `content_tsv` is populated by Postgres' GENERATED-column rewrite (no application-side backfill needed)

#### Scenario: content_tsv auto-populates on new INSERTs
- **WHEN** a new row is INSERTed via the existing `POST /api/v1/posts` flow without supplying `content_tsv`
- **THEN** the inserted row's `content_tsv` equals `to_tsvector('simple', content)` (Postgres computes it as part of the GENERATED ALWAYS contract)

#### Scenario: content_tsv auto-regenerates on UPDATE of content
- **WHEN** an existing `posts` row's `content` is UPDATEd (e.g., by a future post-edit feature)
- **THEN** the row's `content_tsv` automatically regenerates to `to_tsvector('simple', new_content)` per the GENERATED ALWAYS STORED contract — no application-side recomputation is needed AND no separate trigger is required

#### Scenario: Direct INSERT to content_tsv rejected
- **WHEN** any INSERT or UPDATE attempts to write `content_tsv` directly
- **THEN** Postgres rejects the write with the `cannot insert into column "content_tsv"` error per the GENERATED ALWAYS semantics

#### Scenario: FTS query is index-backed
- **WHEN** EXPLAIN is run on `SELECT id FROM posts WHERE content_tsv @@ plainto_tsquery('simple', 'jakarta')`
- **THEN** the plan shows a Bitmap Index Scan on `posts_content_tsv_idx` (not a sequential scan)

#### Scenario: Trigram fuzzy match is index-backed
- **WHEN** EXPLAIN is run on `SELECT id FROM posts WHERE content % 'jakart'`
- **THEN** the plan shows a Bitmap Index Scan on `posts_content_trgm_idx`

#### Scenario: V13 idempotent against pre-existing pg_trgm
- **WHEN** V13 runs AND `pg_trgm` was created by a prior migration (e.g., a future V14+ migration that pre-creates it for another feature)
- **THEN** the `CREATE EXTENSION IF NOT EXISTS` clause is a no-op AND the migration completes without error

