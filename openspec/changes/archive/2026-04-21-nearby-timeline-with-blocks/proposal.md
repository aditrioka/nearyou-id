## Why

Post-creation-geo landed the write path (`display_location`, `posts_geo`, `visible_posts`) but no read path exists yet. The canonical Nearby query in `docs/05-Implementation.md` § Timeline Implementation embeds bidirectional `user_blocks` NOT-IN subqueries directly — meaning the first read endpoint cannot be written without the blocks schema, and shipping the blocks schema without a read path leaves it unprovable. Landing nearby-timeline + user-blocks + the block-exclusion lint as one vertical delivers the first usable read endpoint, the user-blocking contract, and the lint that enforces the block-exclusion join across all future business queries from commit one.

## What Changes

- Add `GET /api/v1/timeline/nearby?lat=&lng=&radius_m=&cursor=` — auth required, keyset-paginated on `(created_at DESC, id DESC)`, `ST_DWithin(display_location, viewer_loc, radius_m)` over `FROM visible_posts`, with bidirectional `user_blocks` NOT-IN subqueries verbatim from `docs/05-Implementation.md` §1045. Returns `{ posts: [{ id, author_user_id, content, latitude, longitude, distance_m, created_at }], next_cursor }`. In-request cap: 30 posts/page.
- Add `POST /api/v1/blocks/{user_id}` (idempotent, 204), `DELETE /api/v1/blocks/{user_id}` (idempotent, 204), `GET /api/v1/blocks?cursor=` (paginated list of blocked users). Self-block rejected at app layer with 400 `cannot_block_self`; DB `CHECK` enforced as defense-in-depth; `UNIQUE (blocker_id, blocked_id)` collisions on re-block return 204.
- Add V5 migration: `user_blocks (blocker_id, blocked_id, created_at, CHECK blocker_id <> blocked_id, UNIQUE (blocker_id, blocked_id))` with both directional indexes from `docs/05-Implementation.md` §1282–1283. FK cascade on user hard-delete in both directions. No follows cascade (follows table does not yet exist; that cascade lands with the follow system).
- Add Detekt `BlockExclusionJoinRule` in `build-logic/detekt-rules/`: business queries touching `posts | visible_posts | users | chat_messages | post_replies` MUST contain both `user_blocks WHERE blocker_id =` and `WHERE blocked_id =` fragments, OR be under `backend/ktor/.../admin/`, OR be a Repository own-content file (queries scoped by `author_user_id = ?` for the calling user), OR be annotated `@AllowMissingBlockJoin("reason")`. Rule fires on `.kt` string literals and on `.sql` files under `db/migration/` except V5 itself.
- Fill `:shared:distance` `jvmMain` source set with a PostGIS-compatible haversine implementation. Server returns `distance_m` as raw meters; `DistanceRenderer` (mobile UI formatting) is unchanged and stays a client concern.
- Document timeline read soft/hard caps (Phase 1 item 30): in-request per-page cap (30) is enforced now; Redis-backed hard caps defer to the rate-limit change (item 24).

## Capabilities

### New Capabilities
- `nearby-timeline`: HTTP contract for `GET /api/v1/timeline/nearby` — auth requirement, query params, keyset-pagination cursor format, radius/envelope validation, distance computation contract, per-page cap, ordering invariant.
- `user-blocking`: HTTP contract for `POST/DELETE/GET /api/v1/blocks`, `user_blocks` schema (columns, CHECK, UNIQUE, indexes, cascade behavior), self-block prevention at app + DB layers, idempotency semantics, future follow-cascade hook documented but deferred.
- `block-exclusion-lint`: Detekt rule scope (which tables are protected, what counts as a business query), allowlist mechanisms (admin path, own-content repository pattern, annotation, V5 migration self-reference), failure messaging.

### Modified Capabilities
- `visible-posts-view`: spec grows — the view-alone pattern is INSUFFICIENT for authenticated read paths; consumers MUST also join `user_blocks` bidirectionally. Note this in the lint allowlist semantics so future readers understand why the view does not encode block exclusion itself.
- `users-schema`: V5 adds `user_blocks` table referencing `users(id)` twice with `ON DELETE CASCADE`.
- `migration-pipeline`: V5 is the first migration that depends jointly on V3 (`users`) and V4 (`posts`/`visible_posts`); document the joint-dependency precedent.
- `distance-rendering`: `:shared:distance` `jvmMain` source set is filled with a PostGIS-compatible haversine; the same module is now the canonical distance source for both backend and mobile.
- `coordinate-jitter`: no behavior change, but the Phase 2 audit gains a real read path against which jitter masking can be verified end-to-end. Note as onward dependency.

## Impact

- **Code**: new `backend/ktor/.../routes/TimelineRoutes.kt`, `BlockRoutes.kt`; new services `NearbyTimelineService.kt`, `BlockService.kt`; new repository methods on `PostsRepository`, new `UserBlocksRepository`; `:shared:distance/src/jvmMain` populated; `build-logic/detekt-rules/` gains `BlockExclusionJoinRule.kt` and registration.
- **Schema**: new V5 migration `db/migration/V5__user_blocks.sql`.
- **APIs**: three new endpoints under `/api/v1/blocks/*` and one under `/api/v1/timeline/nearby`. All require Bearer JWT (existing `auth-jwt` capability). Error codes: `400 cannot_block_self`, `400 location_out_of_bounds` (reused from post-creation), `400 invalid_cursor`, `404 user_not_found` on block target.
- **Dependencies**: no new external libraries; PostGIS + Flyway already wired.
- **Out of scope (explicit)**: Following timeline (needs follows schema — next change), Global timeline (needs `admin_regions` polygon reverse-geocoding), Redis-backed rate-limit enforcement (item 24), attestation gate on read endpoints, mandatory CTE-batching refactor (Phase 2 optimization), mobile `DistanceRenderer` UI wiring, follow-cascade on block (lands with follow system), `content_tsv` / FTS indexes, admin-triggered unblock.
