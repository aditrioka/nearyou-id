## Why

`post-likes-v7` (archived 2026-04-21) shipped likes and left Phase 2 item 3 in `docs/08-Roadmap-Risk.md:150` half-done: "Likes shipped in V7; reply deferred to V8." Replies are the next engagement primitive and the last social-write missing before Report/Notifications land (Phase 2 items 4–5). The `post_replies` DDL already exists verbatim at `docs/05-Implementation.md:716`, semantics at `docs/05-Implementation.md:731`, block-exclusion rule at `docs/05-Implementation.md:1306`, and `FROM post_replies` lint target at `docs/05-Implementation.md:1833`.

## What Changes

- **V8 migration `V8__post_replies.sql`** verbatim from `docs/05-Implementation.md:716-729`: `post_replies (id, post_id → posts ON DELETE CASCADE, author_id → users ON DELETE RESTRICT, content VARCHAR(280) NOT NULL, is_auto_hidden BOOLEAN NOT NULL DEFAULT FALSE, created_at, updated_at, deleted_at)` + two partial indexes filtered by `deleted_at IS NULL`. `author_id RESTRICT` mirrors V4 `posts.author_id` (hard-delete requires upstream tombstone/cascade worker; V8 does NOT change that worker).
- **`POST /api/v1/posts/{post_id}/replies`** — Bearer JWT required; body `{ "content": string }` length-validated at 1–280 chars per the content-length middleware (Phase 1 item 20); resolves post through `visible_posts` + bidirectional `user_blocks` NOT-IN (same pattern as `LikeService.resolveVisiblePost`); `404 post_not_found` on missing/soft-deleted/bidirectional-block (same opaque-error convention as V7 — no `403` leak); returns `201` with full reply JSON.
- **`GET /api/v1/posts/{post_id}/replies`** — Bearer JWT required; keyset-paginated on `(created_at DESC, id DESC)` with a `LIMIT 31` probe row (same shape as nearby/following); per-page cap 30; reads `FROM post_replies` JOINed to `visible_users` (shadow-ban exclusion) AND bidirectional `user_blocks` NOT-IN on `author_id` (block exclusion per `docs/05-Implementation.md:736`); filters `deleted_at IS NULL`; filters `is_auto_hidden = FALSE OR author_id = :viewer` (author still sees own auto-hidden replies per the same doc line); same `404 post_not_found` envelope as POST when the parent post is invisible to the viewer.
- **`DELETE /api/v1/posts/{post_id}/replies/{reply_id}`** — Bearer JWT required; author-only soft-delete (`UPDATE post_replies SET deleted_at = NOW() WHERE id = ? AND author_id = ? AND deleted_at IS NULL`); idempotent `204` whether the row was already tombstoned or never existed for this author (no `403`/`404` leak distinguishing "not yours" from "not here"); NEVER a hard delete — soft-delete only per `docs/05-Implementation.md:734`.
- **Extend `nearby-timeline` and `following-timeline` response** with `reply_count` integer per post, computed via `LEFT JOIN LATERAL (SELECT COUNT(*) FROM post_replies pr JOIN visible_users vu ON vu.id = pr.author_id WHERE pr.post_id = p.id AND pr.deleted_at IS NULL) c ON TRUE` (counter-aggregation rule from `docs/05-Implementation.md:1825`). Counter does NOT apply viewer-block exclusion (same privacy tradeoff documented for `likes/count` in V7 — per-viewer count would leak block state). MUST NOT change keyset-cursor semantics, per-page cap, or the `LIMIT 31` probe; MUST preserve the existing `liked_by_viewer` LEFT JOIN; `BlockExclusionJoinRule` positive-pass unchanged (both `user_blocks` NOT-IN subqueries remain).
- **Extend `BlockExclusionJoinRule` protected-tables list** to include `post_replies` (already on the lint target list at `docs/05-Implementation.md:1833` but deferred because no code read it yet; V8 introduces the first readers, so enforce now). Fixture additions: positive fixture (reply-list query with both NOT-INs) and negative fixture (reply-list query missing the `user_blocks` join → rule fails).

## Capabilities

### New Capabilities
- `post-replies`: HTTP contract for `POST`/`GET`/`DELETE` on `/api/v1/posts/{post_id}/replies[/{reply_id}]`; `post_replies` table schema + dual partial indexes filtered by `deleted_at IS NULL`; soft-delete semantics (DELETE idempotent `204`, no distinguishing `403`/`404`); `404 post_not_found` on parent-post invisibility (missing/soft-deleted/bidirectional-block); reply list block-aware and auto-hidden-aware with author-bypass (`is_auto_hidden = FALSE OR author_id = :viewer`); content length guard 1–280; `author_id RESTRICT` coupling to the upstream tombstone/cascade worker.

### Modified Capabilities
- `nearby-timeline`: response-shape delta — every post gains `reply_count: Int` (never null, never omitted), computed additively via `LEFT JOIN LATERAL` over `visible_users`; keyset cursor, per-page cap, `LIMIT 31` probe, and existing `liked_by_viewer` LEFT JOIN all unchanged; `BlockExclusionJoinRule` positive-pass (both `user_blocks` NOT-IN subqueries) unchanged.
- `following-timeline`: same additive `reply_count` delta; response shape remains "Nearby minus `distance_m`" with `liked_by_viewer` + `reply_count` added.
- `users-schema`: V8 adds `post_replies.author_id → users(id) ON DELETE RESTRICT` (aligns with V4 `posts.author_id`, not V5/V6/V7 cascades); a `users` hard-delete still requires the upstream tombstone/cascade worker (no scope change to that worker).
- `migration-pipeline`: record V8; schema-only joint FK dependency on V3 (`users`) + V4 (`posts`); no runtime coupling (contrast with V6 follow-cascade). Document the fourth dependency shape (schema-only joint V3+V4, same shape as V7 but introducing the first `RESTRICT`-side FK since V4).
- `block-exclusion-lint`: add `post_replies` to the protected-tables list (first real readers introduced in V8); add positive + negative rule-test fixtures; update the spec's non-normative note so the V7-era "not-yet-added" rationale for `post_replies` is retired.

## Impact

- **Code**: new `backend/ktor/src/main/kotlin/id/nearyou/app/engagement/ReplyService.kt` + `ReplyRoutes.kt`; new `PostReplyRepository` interface in `:core:data`; new `JdbcPostReplyRepository` in `:infra:supabase`; `NearbyTimelineService` + `FollowingTimelineService` extended with the `LEFT JOIN LATERAL` counter and `reply_count` projection; `BlockExclusionJoinRule.kt` gains `post_replies` in its protected-tables list with new positive + negative fixtures.
- **Schema**: new `backend/ktor/src/main/resources/db/migration/V8__post_replies.sql`. V1–V7 immutable (already applied).
- **APIs**: 3 new endpoints (`POST`/`GET`/`DELETE` on `/api/v1/posts/{post_id}/replies[/{reply_id}]`); 2 modified timeline response shapes (`nearby-timeline` and `following-timeline` gain `reply_count`).
- **Dependencies**: none new. PostgreSQL, Flyway, Ktor JWT, JDBC — all already wired.
- **Out of scope (explicit)**:
  - Report submission + 3-reporter auto-hide trigger (Phase 2 item 4, its own change; V8 ships the `is_auto_hidden` column, not the trigger that sets it).
  - Rate limiting (Free 20/day, Premium unlimited per `docs/05-Implementation.md:1686`) — waits on the 4-layer rate-limit change (Phase 1 item 24).
  - `post_replied` notification (Phase 2 item 5 notifications schema).
  - Reply editing (not in Phase 2 spec; post edit is Premium-only in Phase 4).
  - Private-profile follows-EXISTS gate on replying to private-profile authors — lands with the privacy feature.
  - Mobile UI wiring for the reply composer, reply list, and `reply_count` rendering.
  - Admin reply moderation surfaces (reply-level hide/unhide UI, moderation queue integration for `target_type = 'reply'`).
  - `user_blocks` exclusion on the `reply_count` counter (privacy tradeoff: per-viewer count would leak block state, same rationale as the V7 `likes/count` decision).
