## Why

`following-timeline-with-follow-cascade` (archived 2026-04-21) shipped V6 `follows`, the Following read path, and the transactional follow-cascade on block — but left Phase 2 item 3 (Like + reply) as the next social-engagement primitive. Likes are strictly smaller than replies (no content string, no `is_auto_hidden` flag, no soft-delete column, no moderation coupling), so they land as V7 on their own; replies follow as a separate V8 change. The likes DDL already exists verbatim in `docs/05-Implementation.md` §690–708, and the `post_liked` notification type at §851 is deferred to the notifications-schema change. See `docs/08-Roadmap-Risk.md` Phase 2 item 3.

## What Changes

- Add V7 migration `V7__post_likes.sql`: `post_likes (post_id UUID, user_id UUID, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), PRIMARY KEY (post_id, user_id))` with dual `ON DELETE CASCADE` FKs to `posts(id)` and `users(id)`, plus `post_likes_user_idx (user_id, created_at DESC)` and `post_likes_post_idx (post_id, created_at DESC)`. Verbatim from `docs/05-Implementation.md` §694–702.
- Add `POST /api/v1/posts/{post_id}/like` — Bearer JWT required, idempotent 204 via `INSERT ... ON CONFLICT (post_id, user_id) DO NOTHING`. Errors: `400 invalid_uuid` for non-UUID path; `404 post_not_found` if the post row is missing/soft-deleted (selected via `visible_posts`) OR if the post author has blocked the caller OR the caller has blocked the post author. Block exclusion MUST surface as the same `404 post_not_found` code that the timeline returns for an invisible post — liking a block-hidden post is indistinguishable from liking a non-existent post. The route MUST NOT return `403`.
- Add `DELETE /api/v1/posts/{post_id}/like` — Bearer JWT required, idempotent 204 whether or not a row was removed. No `404 post_not_found` on missing like (unlike follow/delete, there is no upstream mutual-block read to distinguish cases here; DELETE is a pure no-op on zero rows).
- Add `GET /api/v1/posts/{post_id}/likes/count` — Bearer JWT required. Response shape `{ "count": <int> }`. Computed via `SELECT COUNT(*) FROM post_likes pl JOIN visible_users vu ON vu.id = pl.user_id WHERE pl.post_id = :post_id`, so shadow-banned likers are excluded from the public counter (`docs/05-Implementation.md` §705). Block exclusion is NOT applied to the counter: a viewer's block list is private state, and a count that varies per-viewer would leak that state to any observer comparing two responses. The same `404 post_not_found` rules apply for post-absence and for bidirectional block of the post author.
- Extend `nearby-timeline` and `following-timeline` response shape with a boolean `liked_by_viewer` per post, computed via a single `LEFT JOIN post_likes ON post_likes.post_id = p.id AND post_likes.user_id = :viewer`. The JOIN is additive and MUST NOT change the `(created_at DESC, id DESC)` keyset cursor, the per-page cap of 30, or the `LIMIT 31` probe-row strategy. `liked_by_viewer` MUST appear on every post in the response (never omitted; always `true` or `false`).
- Update `BlockExclusionJoinRule` KDoc (NO behavior change) to document why `post_likes` is deliberately NOT in the protected-tables list, mirroring the existing `follows` note from V6.

## Capabilities

### New Capabilities
- `post-likes`: HTTP contract for `POST`/`DELETE /api/v1/posts/{post_id}/like` and `GET /api/v1/posts/{post_id}/likes/count`; the `post_likes` table schema, constraints, dual cascade, directional indexes; idempotency semantics (ON CONFLICT DO NOTHING, DELETE is no-op on zero rows); `404 post_not_found` on missing/soft-deleted post AND on bidirectional block of post author (no 403 leak); count computed over `visible_users` JOIN so shadow-banned likers are excluded; count does NOT apply viewer-block exclusion (privacy tradeoff documented in the spec).

### Modified Capabilities
- `nearby-timeline`: response-shape requirement gains `liked_by_viewer` boolean on every post, computed via an additive `LEFT JOIN post_likes` that does NOT alter keyset-cursor semantics, per-page cap, or the `BlockExclusionJoinRule` positive-pass guarantee (both `user_blocks` NOT-IN subqueries remain).
- `following-timeline`: same additive `liked_by_viewer` delta; response shape remains "Nearby minus `distance_m`" with `liked_by_viewer` added.
- `users-schema`: V7 adds `post_likes` referencing `users(id)` with `ON DELETE CASCADE`; a `users` hard-delete now cascades through `post_likes` in addition to the existing V5 `user_blocks` and V6 `follows` cascades. The V4 `posts.author_id RESTRICT` invariant still applies and is NOT affected by V7.
- `migration-pipeline`: record V7 lands; V7 has a schema-only FK dependency on V3 (`users`) AND V4 (`posts`) — NO runtime coupling like V6's mutual-block read. Document this third dependency shape (schema-only joint V3+V4, contrasted with V5's first-joint-schema and V6's schema-plus-runtime).
- `block-exclusion-lint`: spec gains a non-normative note documenting why `post_likes` is deliberately NOT a protected table (the writes are caller-scoped; the count read passes through `visible_users` for shadow-ban exclusion; `post_likes` itself carries no user-visible content). No behavior change; no new rule; the fixture list grows to include `post_likes`-only queries.

## Impact

- **Code**: new `backend/ktor/src/main/kotlin/id/nearyou/app/engagement/LikeService.kt` + `LikeRoutes.kt`; new `PostLikeRepository` interface in `:core:data`; new `JdbcPostLikeRepository` in `:infra:supabase`; `NearbyTimelineService` and `FollowingTimelineService` modified to add the `LEFT JOIN post_likes` and project `liked_by_viewer`; `BlockExclusionJoinRule.kt` KDoc updated (no behavior change).
- **Schema**: new `backend/ktor/src/main/resources/db/migration/V7__post_likes.sql`. No changes to V1–V6 (immutable; already applied).
- **APIs**: three new endpoints (`POST`/`DELETE /api/v1/posts/{post_id}/like`, `GET /api/v1/posts/{post_id}/likes/count`); two modified response shapes (`nearby-timeline` and `following-timeline` gain `liked_by_viewer` on every post).
- **Dependencies**: none new. PostGIS untouched. Flyway, Ktor JWT, PostgreSQL, JDBC — all already wired.
- **Out of scope (explicit)**:
  - Like-list endpoint (`GET /api/v1/posts/{post_id}/likes` returning user pages). Liker visibility is a separate privacy design (would need bidirectional `user_blocks` exclusion applied to the list, unlike the counter). Defer to a follow-up change if product asks.
  - Rate limits (Phase 2 #3 Free 10/day + 500/hour burst) — wait on the 4-layer rate-limit change (Phase 1 #24).
  - `notifications` insert for `post_liked` type — waits on the notifications-schema change (Phase 2 #5). This change emits no side effect beyond the INSERT.
  - Denormalized like counter on `posts`. MVP computes at read time (same rationale as follower-count deferral in V6).
  - Mobile UI wiring for the like button and `liked_by_viewer` rendering.
  - Admin read-side surfaces for likes (not in the admin panel yet).
  - Private-profile gate on liking private-profile posts (`docs/05-Implementation.md` §1124 follows-EXISTS check) — lands with the privacy feature.
