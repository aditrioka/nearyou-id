## 1. V8 migration: post_replies

- [x] 1.1 Create `backend/ktor/src/main/resources/db/migration/V8__post_replies.sql` with the `post_replies` table verbatim from `docs/05-Implementation.md` §716–729 — `id UUID PK DEFAULT gen_random_uuid()`, `post_id UUID NOT NULL REFERENCES posts(id) ON DELETE CASCADE`, `author_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT`, `content VARCHAR(280) NOT NULL`, `is_auto_hidden BOOLEAN NOT NULL DEFAULT FALSE`, `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`, `updated_at TIMESTAMPTZ` (nullable), `deleted_at TIMESTAMPTZ` (nullable); plus partial indexes `post_replies_post_idx (post_id, created_at DESC) WHERE deleted_at IS NULL` and `post_replies_author_idx (author_id, created_at DESC) WHERE deleted_at IS NULL`
- [x] 1.2 V8 header comment documents: schema-level joint FK dependencies on V3 (`users`) AND V4 (`posts`); explicit "no runtime coupling" note contrasting with V6's `BlockService.block()` cascade; explicit note that `author_id RESTRICT` mirrors V4 `posts.author_id` and is the first `RESTRICT`-side FK on `users(id)` since V4; verbatim DDL source reference `docs/05-Implementation.md` §716–729; V8-era consumers list (`ReplyService` POST/GET/DELETE, `LEFT JOIN LATERAL` reply counter in `NearbyTimelineService` and `FollowingTimelineService`, lint fixtures exercising `post_replies` reader queries)
- [x] 1.3 Run `./gradlew :backend:ktor:flywayMigrate` against the local dev DB; verify a V8 row in `flyway_schema_history` with `success = TRUE`
- [x] 1.4 Write `MigrationV8SmokeTest` (tagged `database`) covering all eight invariants from the `migration-pipeline` spec: (a) both partial indexes with `deleted_at IS NULL` predicate, (b) `post_id → posts(id)` FK delete rule is CASCADE, (c) `author_id → users(id)` FK delete rule is RESTRICT/NO ACTION, (d) `post_id` CASCADE actually removes replies on posts hard-delete, (e) `author_id` RESTRICT blocks user hard-delete with ≥1 reply (including soft-deleted rows), (f) `author_id` RESTRICT permits user hard-delete after all replies hard-deleted, (g) V7→V8 advances + V8→V8 is a no-op, (h) clean V1–V8 cold migrate works (with V7 in between; V7 is NOT skippable)
- [x] 1.5 `./gradlew :backend:ktor:test --tests '*MigrationV8SmokeTest*'` green

## 2. Docs back-reference

- [x] 2.1 Add a one-line "V8 shipped in change post-replies-v8" back-reference note at or near `docs/05-Implementation.md` §713 (above the `post_replies` DDL block); do NOT modify the DDL itself (§716–729) or the `post_replied` notification reference at §854
- [x] 2.2 Update `docs/08-Roadmap-Risk.md` Phase 2 item 3 in the ARCHIVE commit (not this PR): mark Reply shipped in V8; the whole "Like + reply" line item is now complete

## 3. Reply repository + service

- [x] 3.1 Add `PostReplyRepository` interface in `core/data/src/main/kotlin/id/nearyou/data/repository/` with methods `insert(postId: UUID, authorId: UUID, content: String): PostReply`, `listByPost(postId: UUID, viewerId: UUID, cursor: CursorKey?, limit: Int): List<PostReply>`, `softDeleteOwn(replyId: UUID, authorId: UUID): Unit`, `resolveVisiblePost(postId: UUID, viewerId: UUID): UUID?` (the visibility check used by POST and GET; identical template to `PostLikeRepository.resolveVisiblePost`)
- [x] 3.2 Add `JdbcPostReplyRepository` in `infra/supabase/...` implementing the interface
- [x] 3.3 `resolveVisiblePost`: execute the identical canonical SELECT used by `PostLikeRepository.resolveVisiblePost` (same string literal shape so `BlockExclusionJoinRule` passes) — `SELECT p.id FROM visible_posts p WHERE p.id = ? AND p.author_user_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = ?) AND p.author_user_id NOT IN (SELECT blocker_id FROM user_blocks WHERE blocked_id = ?) LIMIT 1`; verify the string literal contains `visible_posts`, `user_blocks`, `blocker_id =`, AND `blocked_id =` tokens
- [x] 3.4 `insert`: `INSERT INTO post_replies (post_id, author_id, content) VALUES (?, ?, ?) RETURNING id, post_id, author_id, content, is_auto_hidden, created_at, updated_at, deleted_at`; no transaction wrapper
- [x] 3.5 `listByPost`: the canonical reply-list query — `SELECT id, post_id, author_id, content, is_auto_hidden, created_at, updated_at FROM post_replies pr JOIN visible_users vu ON vu.id = pr.author_id WHERE pr.post_id = :post_id AND pr.deleted_at IS NULL AND (pr.is_auto_hidden = FALSE OR pr.author_id = :viewer) AND pr.author_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = :viewer) AND pr.author_id NOT IN (SELECT blocker_id FROM user_blocks WHERE blocked_id = :viewer) [AND (pr.created_at, pr.id) < (:c, :i)] ORDER BY pr.created_at DESC, pr.id DESC LIMIT 31`; verify the string literal contains `post_replies`, `user_blocks`, `blocker_id =`, AND `blocked_id =` tokens
- [x] 3.6 `softDeleteOwn`: `UPDATE post_replies SET deleted_at = NOW() WHERE id = ? AND author_id = ? AND deleted_at IS NULL`; ignore rowcount; no exception on zero rows; no preliminary visibility check on the parent post
- [x] 3.7 Add `ReplyService.kt` in `backend/ktor/src/main/kotlin/id/nearyou/app/engagement/` wrapping the repository: exposes `post`, `list`, `softDelete`; `post` calls `resolveVisiblePost` and throws `PostNotFoundException` on null; `list` calls `resolveVisiblePost` and throws `PostNotFoundException` on null BEFORE executing the reply-list query; `softDelete` MUST NOT call `resolveVisiblePost`
- [x] 3.8 Add `PostNotFoundException` to the engagement package ONLY if one does not already exist from the V7 `LikeService` work; otherwise import the V7 type so the two endpoints emit identical error envelopes
- [x] 3.9 Add a build-time / unit-test guard that greps the `ReplyService` + `JdbcPostReplyRepository` sources for the literal `DELETE FROM post_replies` and fails if any match is found (enforces the no-hard-delete decision)

## 4. Reply HTTP routes

- [x] 4.1 Create `ReplyRoutes.kt` in `backend/ktor/src/main/kotlin/id/nearyou/app/engagement/` registering `POST /api/v1/posts/{post_id}/replies`, `GET /api/v1/posts/{post_id}/replies`, `DELETE /api/v1/posts/{post_id}/replies/{reply_id}` under `authenticate(AUTH_PROVIDER_USER)`
- [x] 4.2 UUID path-param parsing: catch `IllegalArgumentException` from `UUID.fromString` on `post_id` (all three routes) and on `reply_id` (DELETE route) and map to `400 invalid_uuid`; this check MUST run before any body parsing and before any repository call
- [x] 4.3 POST body parse + length guard: reject `400 invalid_request` when `content` is missing / null / empty-after-trim / >280 chars; 280-char boundary is inclusive (`content.length <= 280` passes)
- [x] 4.4 Map exceptions: `PostNotFoundException` → 404 `post_not_found` on POST and GET; response body MUST be the constant `{ "error": { "code": "post_not_found" } }` with no additional fields (identical to the V7 like endpoint; no "reason", no direction hint, no distinction between missing / soft-deleted / auto-hidden / caller-blocked / author-blocked)
- [x] 4.5 GET cursor parse: map malformed cursor to `400 invalid_cursor`; reuse the shared cursor codec from `nearby-timeline` / `following-timeline` so the base64url JSON format (`{"c":"<iso>","i":"<uuid>"}`) is identical
- [x] 4.6 POST success: HTTP 201 with body `{ id, post_id, author_id, content, is_auto_hidden = false, created_at, updated_at = null, deleted_at = null }`; `author_id` is sourced from the JWT `sub`, NEVER from the request body
- [x] 4.7 GET success: HTTP 200 with body `{ replies: [...], next_cursor }`; probe-row strategy — query `LIMIT 31`, slice to 30 for the response, encode the 30th item as `next_cursor` when a 31st was returned; each reply has `deleted_at = null` always (the query excludes soft-deleted rows)
- [x] 4.8 DELETE response: HTTP 204 with empty body; NEVER 403, NEVER 404 — not-yours / already-tombstoned / never-existed ALL return 204 (opaque-by-design)
- [x] 4.9 Wire `replyRoutes(replyService)` into `Application.module()` after `likeRoutes`

## 5. Nearby timeline: reply_count

- [x] 5.1 Modify `NearbyTimelineService.kt` canonical query: add `LEFT JOIN LATERAL (SELECT COUNT(*) AS n FROM post_replies pr JOIN visible_users vu ON vu.id = pr.author_id WHERE pr.post_id = p.id AND pr.deleted_at IS NULL) c ON TRUE` and project `c.n AS reply_count`; verify the primary query literal still contains `visible_posts`, `user_blocks`, `blocker_id =`, `blocked_id =` tokens (`BlockExclusionJoinRule` compatibility unchanged)
- [x] 5.2 Verify the V7 `LEFT JOIN post_likes pl ON pl.post_id = p.id AND pl.user_id = :viewer` is UNCHANGED and STILL in the query; verify the keyset predicate `(p.created_at, p.id) < (:c, :i)` is UNCHANGED; verify `ORDER BY p.created_at DESC, p.id DESC` is UNCHANGED; verify `LIMIT 31` is UNCHANGED; verify `c.*` does NOT appear in `ORDER BY` or the keyset predicate
- [x] 5.3 Extend the Nearby response DTO with `replyCount: Int` (Kotlin `Int`, not nullable); JSON serializer key MUST be `reply_count` (snake_case for wire format, matching existing convention); keep `likedByViewer` unchanged
- [x] 5.4 Row mapper reads the new column via `rs.getInt("reply_count")`
- [x] 5.5 Extend `NearbyTimelineServiceTest` (tagged `database`) with scenarios from the `nearby-timeline` delta: (a) `reply_count = 0` for post with no replies, (b) `reply_count` = exact count when multiple visible replies exist, (c) shadow-banned replier excluded from counter (3 replies, 1 shadow-banned → `reply_count = 2`), (d) soft-deleted replies excluded (5 replies, 2 tombstoned → `reply_count = 3`), (e) viewer-blocked replier NOT excluded from counter (3 visible replies, 1 by viewer-blocked user → `reply_count = 3`), (f) `reply_count` key present on every post (iterate and assert), (g) LATERAL cardinality invariant: 35 visible posts with 200 collective replies → 35 returned, not 235
- [x] 5.6 Verify existing `NearbyTimelineServiceTest` scenarios still pass (cursor, block-exclusion, radius, envelope, auto-hidden, `liked_by_viewer` — all unchanged)
- [x] 5.7 `./gradlew :backend:ktor:test --tests '*NearbyTimelineServiceTest*'` green

## 6. Following timeline: reply_count

- [x] 6.1 Modify `FollowingTimelineService.kt` canonical query: add the SAME `LEFT JOIN LATERAL (...) c ON TRUE` and `c.n AS reply_count` projection as Nearby (literal reuse is encouraged — keep the subquery string identical across the two services for lint consistency); verify the primary query literal still contains `visible_posts`, `user_blocks`, `blocker_id =`, `blocked_id =` tokens
- [x] 6.2 Verify the V7 `LEFT JOIN post_likes` is UNCHANGED and STILL in the query; verify the `follows` IN-subquery is UNCHANGED; verify keyset, `ORDER BY`, and `LIMIT 31` are UNCHANGED; verify `c.*` does NOT appear in `ORDER BY` or the keyset predicate
- [x] 6.3 Extend the Following response DTO with `replyCount: Int` (non-nullable); JSON key `reply_count`; response shape remains "Nearby minus `distance_m`"
- [x] 6.4 Row mapper reads the new column via `rs.getInt("reply_count")`
- [x] 6.5 Extend `FollowingTimelineServiceTest` with the same seven scenarios as Nearby (items a–g above), adjusted for the follows relationship: the 200-reply cardinality invariant uses 20 eligible posts with 150 collective replies → 20 returned, not 170
- [x] 6.6 Verify existing `FollowingTimelineServiceTest` scenarios still pass (follows, block-exclusion, cursor, auto-hidden, empty result, `liked_by_viewer`, no-`distance_m`)
- [x] 6.7 `./gradlew :backend:ktor:test --tests '*FollowingTimelineServiceTest*'` green

## 7. Block-exclusion lint: activate post_replies

- [x] 7.1 Add a `ReplyOwn*` filename pattern to `BlockExclusionJoinRule`'s own-content allowlist (matching the existing `PostOwnContent`, `UserOwn`, `ChatOwn` patterns) so a future `ReplyOwnContentRepository.kt` for the own-content path is exempt
- [x] 7.2 Add the V8 positive-pass fixture `reply_list_query_with_bidirectional_block_exclusion` to `BlockExclusionJoinLintTest`: the canonical reply-list literal from task 3.5 — MUST NOT trip the rule
- [x] 7.3 Add the V8 positive-fail fixture `reply_list_query_missing_blocked_id_fails`: a variant of the canonical literal with the second `user_blocks` NOT-IN subquery removed (`blocker_id =` present, `blocked_id =` missing) — MUST trip `BlockExclusionJoinRule`
- [x] 7.4 Update the KDoc on `BlockExclusionJoinRule` to replace the V7-era "not-yet-added reason for `post_replies`" phrasing with a "`post_replies` is active as of V8 with live production readers in `JdbcPostReplyRepository`" note; do NOT remove the existing `follows` or `post_likes` exclusion paragraphs
- [x] 7.5 `./gradlew :lint:detekt-rules:test --tests '*BlockExclusionJoinLintTest*'` green
- [x] 7.6 `./gradlew detekt` (full project sweep) green — verify the new `JdbcPostReplyRepository` reply-list literal passes the rule; verify no other Kotlin file has inadvertently tripped it

## 8. Reply service integration tests

- [x] 8.1 Create `ReplyServiceTest` (tagged `database`) in `backend/ktor/src/test/kotlin/id/nearyou/app/engagement/`
- [x] 8.2 POST scenarios (19 invariants per the `post-replies` spec's test-coverage requirement): (a) happy path 201 with full reply JSON, (b) `invalid_uuid` on non-UUID path, (c) `invalid_request` on empty / whitespace / 281-char / missing / null content (five sub-cases), (d) `post_not_found` on missing / soft-deleted / auto-hidden / caller-blocked-author / author-blocked-caller (five sub-cases, all returning the same opaque envelope), (e) `author_id` derived from JWT, not body (rogue body sets `author_id` to a different UUID — stored row uses JWT `sub`)
- [x] 8.3 GET scenarios: (a) happy path three replies `created_at DESC, id DESC`, (b) `post_not_found` on invisible parent (same five sub-cases as POST), (c) cursor pagination 35 → 30+cursor → 5, no overlap, (d) soft-deleted replies excluded for everyone including author, (e) shadow-banned author replies excluded via `visible_users` JOIN, (f) block-exclusion both directions, (g) auto-hidden reply visible to its author only, (h) response `deleted_at` always `null`
- [x] 8.4 DELETE scenarios: (a) author happy path `deleted_at` set, 204, (b) non-author 204 AND row UNCHANGED, (c) already-tombstoned 204 AND stored `deleted_at` UNCHANGED (guard prevents overwrite), (d) nonexistent reply_id 204
- [x] 8.5 Auth scenario: HTTP 401 without JWT on all three endpoints
- [x] 8.6 Grep assertion: scan `ReplyService.kt` + `JdbcPostReplyRepository.kt` source bytes for the literal substring `DELETE FROM post_replies`; assert zero matches (enforces the no-hard-delete decision from design §2)
- [x] 8.7 `./gradlew :backend:ktor:test --tests '*ReplyServiceTest*'` green

## 9. Full verification

- [x] 9.1 `./gradlew :backend:ktor:test` green (full backend test suite)
- [x] 9.2 `./gradlew detekt` green (full lint sweep; includes the new fixtures and the live reply repository)
- [x] 9.3 `./gradlew :backend:ktor:flywayMigrate` idempotent against V8 (second run is a no-op)
- [x] 9.4 Manual end-to-end smoke against the local dev DB: POST a reply, GET the list, soft-delete it, GET the list again (the tombstoned reply is gone); verify `nearby-timeline` and `following-timeline` responses now include `reply_count` on every post
