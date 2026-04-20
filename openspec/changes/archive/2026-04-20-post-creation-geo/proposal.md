## Why

Auth-foundation and signup-flow landed the backbone for a real `users` row to exist, but nothing downstream can consume it until posts are writable. Phase 1 items 13, 14, and 22 in `docs/08-Roadmap-Risk.md` form the first feature vertical that stands on a populated `users` table: post creation with the dual-column geo contract, the shared `renderDistance` implementation, and the `visible_posts` view + CI lint that together gate every future read path. Without these three, every later change (timeline, reply, report, chat embed, moderation auto-hide) would either leak `actual_location` into a non-admin query (triangulation risk per `docs/05-Implementation.md` § Coordinate Fuzzing) or bypass the shadow-ban gate the CI lint rule is meant to enforce from commit 1.

The three pieces are mutually dependent: the INSERT writes both `actual_location` and `display_location` in one statement, the read path exposes only `display_location` via the view, and the client-side `DistanceRenderer` assumes the fuzz-first / floor-5km / round-to-1km contract for input distances already computed against `display_location`. Splitting them produces stubs that every consumer has to special-case. Landing them together delivers one usable endpoint, one coherent V4 migration, and the Detekt rule that keeps correctness enforced automatically.

## What Changes

### Backend — new endpoint
- `POST /api/v1/posts` (auth required, RS256 middleware). Body: `{ content: string, latitude: double, longitude: double }`. Response 201: `{ id, content, latitude, longitude, distance_m: null, created_at }` (author's own create returns no distance; timeline reads will compute distance in a later change).
- Single DB transaction flow:
  1. Content length guard: 1..=280 chars after NFKC-normalize + trim. Empty → 400 `content_empty`; > 280 → 400 `content_too_long`. Implemented as middleware `ContentLengthGuard` with a per-route registered limits table (reply / chat / bio / display_name register later).
  2. Coord envelope check: `-11.0 <= lat <= 6.5`, `94.0 <= lng <= 142.0` (Indonesia + 12-mile maritime). Out of envelope → 400 `location_out_of_bounds`. Polygon-precise `admin_regions` check is deferred.
  3. Generate `post_id` as UUIDv7 in the app layer (HMAC input known before INSERT).
  4. Compute `display_location = offset_by_bearing(actual, bearing, distance)` where `bearing_radians = (hmac[0..4] as uint32) / 2^32 * 2π` and `distance_meters = 50 + (hmac[4..8] as uint32) / 2^32 * 450`, with `hmac = HMAC-SHA256(JITTER_SECRET, post_id.bytes)`. Deterministic (same `post_id` always yields same display point); 50–500m offset envelope.
  5. Single INSERT writing both geographies.
- Errors follow the existing `{ error: { code, message } }` envelope.

### Backend — `visible_posts` view + Detekt lint
- V4 creates `visible_posts` as `SELECT * FROM posts WHERE is_auto_hidden = FALSE`. Block-exclusion join is not in this view yet (separate change owns `user_blocks`).
- Detekt custom rule `RawFromPostsRule` in `build-logic/detekt-rules/` (new or extended). Rule fires on grep-level matches of `FROM posts` / `JOIN posts` / identifier `posts` in SQL strings and on JDBC bindings. Allowed paths: `backend/ktor/.../post/repository/PostOwnContent*.kt`, any file annotated `@AllowRawPostsRead("reason")`, anything under `backend/ktor/.../admin/`, and the V4 view-definition SQL file itself. Everything else fails CI.

### Shared — `:shared:distance` module
- New Gradle module. MVP source set is `commonMain` only; `jvmMain` / `nativeMain` hooks empty and ready for the mobile change to fill.
- `JitterEngine.offsetByBearing(actualLatLng, postId, secret): LatLng` — pure function; HMAC key and message passed explicitly (no DI; easy to test).
- `DistanceRenderer.render(distanceMeters: Double): String` — fuzz-first / floor 5km / round-to-1km canonical contract from `docs/05-Implementation.md` § renderDistance.
- `backend/ktor` adds a `:shared:distance` dependency.

### Database — Flyway V4
- `V4__post_creation.sql` creates `posts` verbatim from `docs/05-Implementation.md` § Posts Schema: `id UUID PK`, `author_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE`, `content VARCHAR(280) NOT NULL`, `actual_location GEOGRAPHY(POINT, 4326) NOT NULL`, `display_location GEOGRAPHY(POINT, 4326) NOT NULL`, `is_auto_hidden BOOLEAN NOT NULL DEFAULT FALSE`, `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`.
- Indexes (all Phase 1 item 21 entries that `posts` owns, so Timeline doesn't need an ALTER later):
  - `posts_display_location_idx` GIST on `display_location`
  - `posts_actual_location_idx` GIST on `actual_location` (admin-only reads)
  - `posts_timeline_cursor_idx` composite `(created_at DESC, id DESC)`
  - `posts_nearby_cursor_idx` composite `(display_location, created_at DESC)`
- `visible_posts` view created in the same migration.
- Deferred from V4: `content_tsv` generated column + FTS GIN indexes (Search change, Phase 2 item 6).

### Secrets
- `JITTER_SECRET` joins the `SecretResolver` chain (same pattern as `invite-code-secret`). `dev/.env.example` gets a placeholder; `dev/scripts/generate-rsa-keypair.sh` emits a `JITTER_SECRET=<base64>` line. Staging/prod Secret Manager wiring is deferred.

### Tests
- `JitterEngineTest` (Kotest, pure, no DB):
  - Determinism: byte-exact equality across repeated calls with same inputs.
  - Distance bounds: 1000 random `post_id`s + fixed actual point produce `display_location` within [50, 500] m (pure-Kotlin haversine assertion here; PostGIS cross-check in the integration test).
  - Secret sensitivity: same `post_id` with different secrets yields different display points.
- `DistanceRendererTest`: canonical matrix — 4.5km→"5km", 7.4km→"7km", 7.6km→"8km", 5000.0→"5km".
- `CreatePostServiceTest` (integration, `database` tag):
  - Happy path: 201 + row has both geographies populated, `ST_Distance(actual, display)` within [50, 500] m.
  - Content length: 280 accepted, 281 rejected, empty rejected, whitespace-only rejected.
  - Coord bounds: rejected outside the envelope.
  - Auth: missing JWT → 401; stale `token_version` → 401.
  - Cascade: user hard-delete via direct SQL cascades the post row (tombstone worker is out of scope).
- `MigrationV4SmokeTest`: runs Flyway, asserts the four indexes, the view, the CHECK / NOT NULL / FK semantics.
- `VisiblePostsViewTest`: `is_auto_hidden = TRUE` row is invisible via the view; flipping to FALSE makes it visible.
- `RawFromPostsLintTest`: fixture with `SELECT * FROM posts` in a non-allowed package fails; same query under `admin/` or with `@AllowRawPostsRead` passes.

## Capabilities

### New Capabilities
- `post-creation`: HTTP contract for `POST /api/v1/posts` — request shape, content-length + coord-bounds guards, error taxonomy (`content_empty` / `content_too_long` / `location_out_of_bounds` / `unauthenticated`), single-INSERT transactional guarantee, 201 response shape.
- `coordinate-jitter`: HMAC-SHA256-based jitter contract — input (actual point + post_id + JITTER_SECRET), output (display point in 50–500m envelope), determinism, non-reversibility without secret, `:shared:distance` module as the canonical implementation site.
- `distance-rendering`: `renderDistance` contract — fuzz-first input, floor at 5km, round-to-1km, required test matrix.
- `visible-posts-view`: `visible_posts` view definition + "business code queries view, admin + own-content queries raw table" contract + Detekt rule `RawFromPostsRule` enforcing it.

### Modified Capabilities
- `users-schema`: V4 migration adds the `posts` table + `visible_posts` view; schema contract grows — `posts.author_user_id` FK on `users(id)` with `ON DELETE CASCADE`.
- `migration-pipeline`: V4 is the first PostGIS-dependent migration; smoke-test harness pattern extends to include GIST-index assertions.
- `backend-bootstrap`: adds the per-route content-length limits registry and wires the post-creation route into `Application.module()`.

## Impact

- **Code paths**: new `:shared:distance` module; new packages under `backend/ktor/src/main/kotlin/id/nearyou/app/post/` (route, service, repository, jitter wiring); extends `:infra:supabase` with `PostRepository` interface + JDBC impl; new `V4__post_creation.sql`; new or extended `build-logic/detekt-rules/` module for `RawFromPostsRule`; new `ContentLengthGuard` middleware with a per-route limits registry.
- **Secrets**: `JITTER_SECRET` added to resolver chain. Dev `.env.example` + generate-keypair helper updated. Staging/prod Secret Manager plumbing deferred.
- **External dependencies**: none new. PostGIS already in the dev Postgres image (from auth-foundation).
- **Onward dependencies unblocked**: Timeline endpoints (Phase 2 items 1-3) have posts to read via `visible_posts`. Report feature (Phase 2 item 4) has `is_auto_hidden` to flip. Chat embed (Phase 2 item 9) can reference a real post. Coordinate-jitter audit (Phase 2 item 15) can verify non-admin spatial queries hit `display_location`. Moderation auto-hide trigger has a schema to act on without ALTER.
- **Out of scope (flags, carried or introduced)**:
  - Timeline endpoints (Nearby / Following / Global) — next change.
  - Post editing + `post_edits` table — Phase 4 Premium feature.
  - Post soft-delete / hard-delete + tombstone worker — later change.
  - Block user feature + block-exclusion join in `visible_posts` — separate change (Phase 1 item 16); the view starts with the auto-hide filter only.
  - Rate limiting on post creation — Redis change (Phase 1 item 24).
  - Attestation gate on post creation — attestation-integration change.
  - FTS (`content_tsv` + GIN) — Search change (Phase 2 item 6).
  - `admin_regions` reverse-geocoding enrichment — deferred; the envelope check is coarse.
  - Content-length middleware registers the `post.content` limit only; reply / chat / bio / display_name / username register in their own changes.
  - `:shared:distance` `jvmMain` / `nativeMain` source sets exist but are empty; the mobile change fills them.
