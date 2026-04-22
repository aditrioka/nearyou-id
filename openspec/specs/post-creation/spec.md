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

