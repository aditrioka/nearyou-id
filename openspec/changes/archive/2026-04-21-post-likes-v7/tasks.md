## 1. V7 migration: post_likes

- [x] 1.1 Create `backend/ktor/src/main/resources/db/migration/V7__post_likes.sql` with the `post_likes` table verbatim from `docs/05-Implementation.md` §694–702 — PK `(post_id, user_id)` + `post_likes_user_idx (user_id, created_at DESC)` + `post_likes_post_idx (post_id, created_at DESC)` + dual `ON DELETE CASCADE` to `posts(id)` and `users(id)` + `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`
- [x] 1.2 V7 header documents: schema-level joint FK dependency on V3 (`users`) AND V4 (`posts`); explicit "no runtime coupling" note contrasting with V6; list of V7-era consumers (like service writes, likes-count endpoint, `liked_by_viewer` LEFT JOIN in `NearbyTimelineService` and `FollowingTimelineService`); reference to canonical DDL at `docs/05-Implementation.md` §694–702
- [x] 1.3 Run `./gradlew :backend:ktor:flywayMigrate` against the local dev DB; verify V7 row in `flyway_schema_history`
- [x] 1.4 Write `MigrationV7SmokeTest` (tagged `database`) covering the five invariants: clean migrate from V6, idempotent second run, both indexes with documented column orders, PK uniqueness, both FK cascade directions (users side, posts side)
- [x] 1.5 `./gradlew :backend:ktor:test --tests '*MigrationV7SmokeTest*'` green

## 2. docs back-reference

- [x] 2.1 Add a one-line "V7 shipped in change post-likes-v7" back-reference note at or near `docs/05-Implementation.md` §690 (above the likes DDL block); do NOT modify the DDL itself (§694–702) or the `post_liked` notification reference at §851
- [x] 2.2 Update `docs/08-Roadmap-Risk.md` Phase 2 item 3 in the ARCHIVE commit (not this PR): mark Likes shipped; Reply deferred to V8

## 3. Like repository + service

- [x] 3.1 Add `PostLikeRepository` interface in `core/data/src/main/kotlin/id/nearyou/data/repository/` with methods `like(postId: UUID, userId: UUID)`, `unlike(postId: UUID, userId: UUID)`, `countVisibleLikes(postId: UUID): Long`, `resolveVisiblePost(postId: UUID, viewerId: UUID): UUID?` (the visibility check used by both `POST /like` and `GET /likes/count`)
- [x] 3.2 Add `JdbcPostLikeRepository` in `infra/supabase/...` implementing the interface
- [x] 3.3 `resolveVisiblePost`: execute `SELECT p.id FROM visible_posts p WHERE p.id = ? AND p.author_user_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = ?) AND p.author_user_id NOT IN (SELECT blocker_id FROM user_blocks WHERE blocked_id = ?) LIMIT 1`; verify the string literal contains `visible_posts`, `user_blocks`, `blocker_id =`, AND `blocked_id =` tokens so `BlockExclusionJoinRule` passes
- [x] 3.4 `like`: `INSERT INTO post_likes (post_id, user_id) VALUES (?, ?) ON CONFLICT (post_id, user_id) DO NOTHING`; no transaction wrapper
- [x] 3.5 `unlike`: `DELETE FROM post_likes WHERE post_id = ? AND user_id = ?`; ignore rowcount; no exception on zero rows; no preliminary visibility check
- [x] 3.6 `countVisibleLikes`: `SELECT COUNT(*) FROM post_likes pl JOIN visible_users vu ON vu.id = pl.user_id WHERE pl.post_id = ?`; add an inline comment at the call site documenting that viewer-block exclusion is deliberately NOT applied (privacy tradeoff per `post-likes` spec Requirement "Count endpoint does NOT apply viewer-block exclusion")
- [x] 3.7 Add `LikeService.kt` in `backend/ktor/src/main/kotlin/id/nearyou/app/engagement/` wrapping the repository: exposes `like`, `unlike`, `countLikes`; calls `resolveVisiblePost` before `like` and `countLikes` and throws `PostNotFoundException` on null; `unlike` MUST NOT call `resolveVisiblePost`

## 4. Like HTTP routes

- [x] 4.1 Create `LikeRoutes.kt` in `backend/ktor/src/main/kotlin/id/nearyou/app/engagement/` registering `POST /api/v1/posts/{post_id}/like`, `DELETE /api/v1/posts/{post_id}/like`, `GET /api/v1/posts/{post_id}/likes/count` under `authenticate(AUTH_PROVIDER_USER)`
- [x] 4.2 UUID path-param parsing: catch `IllegalArgumentException` from `UUID.fromString` and map to `400 invalid_uuid`; this check MUST run before any repository call
- [x] 4.3 Map exceptions: `PostNotFoundException` → 404 `post_not_found`; ensure the response body is the constant `{ "error": { "code": "post_not_found" } }` with no additional fields (no "reason", no direction hint, no distinction between missing/soft-deleted/caller-blocked/author-blocked)
- [x] 4.4 `GET /likes/count` response shape: `{ "count": <long> }` — JSON integer, not string
- [x] 4.5 Wire `likeRoutes(likeService)` into `Application.module()` after `followRoutes`

## 5. Nearby timeline: liked_by_viewer

- [x] 5.1 Modify `NearbyTimelineService.kt` canonical query: add `LEFT JOIN post_likes pl ON pl.post_id = p.id AND pl.user_id = :viewer` and project `(pl.user_id IS NOT NULL) AS liked_by_viewer`; verify the query literal still contains `visible_posts`, `user_blocks`, `blocker_id =`, `blocked_id =` tokens (BlockExclusionJoinRule compatibility)
- [x] 5.2 Verify the keyset predicate `(p.created_at, p.id) < (:c, :i)` is UNCHANGED; verify `ORDER BY p.created_at DESC, p.id DESC` is UNCHANGED; verify `LIMIT 31` is UNCHANGED; verify `pl.*` does NOT appear in `ORDER BY` or the keyset predicate
- [x] 5.3 Extend the Nearby response DTO with `likedByViewer: Boolean` (Kotlin `Boolean`, not nullable); JSON serializer key MUST be `liked_by_viewer` (snake_case for wire format, matching existing convention)
- [x] 5.4 Row mapper reads the new column and sets `likedByViewer = rs.getBoolean("liked_by_viewer")`
- [x] 5.5 Extend `NearbyTimelineServiceTest` (tagged `database`) with scenarios from the `nearby-timeline` delta: (a) `liked_by_viewer = true` when caller has liked the post, (b) `liked_by_viewer = false` when not, (c) `liked_by_viewer` key present on every post (iterate and assert), (d) cardinality invariant: 35 visible posts with 7 liked → 35 returned, not 42
- [x] 5.6 Verify existing NearbyTimelineServiceTest scenarios still pass (cursor, block-exclusion, radius, envelope, auto-hidden all unchanged)
- [x] 5.7 `./gradlew :backend:ktor:test --tests '*NearbyTimelineServiceTest*'` green

## 6. Following timeline: liked_by_viewer

- [x] 6.1 Modify `FollowingTimelineService.kt` canonical query: same LEFT JOIN pattern as Nearby (5.1); verify the query literal still contains `visible_posts`, `user_blocks`, `blocker_id =`, `blocked_id =` tokens
- [x] 6.2 Verify keyset, ORDER BY, LIMIT 31 are UNCHANGED (same checklist as 5.2)
- [x] 6.3 Extend the Following response DTO with `likedByViewer: Boolean` → JSON key `liked_by_viewer`; preserve the "no distance_m" shape
- [x] 6.4 Extend `FollowingTimelineServiceTest` (tagged `database`) with scenarios from the `following-timeline` delta: (a) `liked_by_viewer = true`, (b) `= false`, (c) key present on every post, (d) cardinality invariant: 20 eligible posts with 6 liked → 20 returned, not 26
- [x] 6.5 Verify existing FollowingTimelineServiceTest scenarios still pass
- [x] 6.6 `./gradlew :backend:ktor:test --tests '*FollowingTimelineServiceTest*'` green

## 7. Lint: document post_likes exclusion

- [x] 7.1 Add a KDoc paragraph to `BlockExclusionJoinRule.kt` explaining why `post_likes` is deliberately NOT a protected table (three reasons from the `block-exclusion-lint` spec ADDED requirement); place it alongside the existing `follows` paragraph from V6
- [x] 7.2 Add test fixture `post_likes_is_deliberately_not_protected_table` to `BlockExclusionJoinLintTest` asserting the rule does NOT fire on: (a) `"SELECT COUNT(*) FROM post_likes pl JOIN visible_users vu ON vu.id = pl.user_id WHERE pl.post_id = ?"`, (b) `"INSERT INTO post_likes (post_id, user_id) VALUES (?, ?) ON CONFLICT (post_id, user_id) DO NOTHING"`, (c) `"DELETE FROM post_likes WHERE post_id = ? AND user_id = ?"`
- [x] 7.3 `./gradlew :lint:detekt-rules:test` green

## 8. Like endpoints integration tests

- [x] 8.1 Write `LikeEndpointsTest` (tagged `database`) covering all 17 scenarios from the `post-likes` spec: first like 204, re-like idempotent, `POST /like` 404 on missing UUID, 404 on soft-deleted, 404 on caller-blocked-author, 404 on author-blocked-caller, 404 body identical across invisibility cases, `POST /like` 400 on non-UUID, `DELETE /like` 204 with row, `DELETE /like` 204 no-op, `DELETE /like` 204 after author block (self-cleanup still works), `DELETE /like` 400 on non-UUID, `GET /likes/count` returns shadow-ban-filtered count on visible post, `GET /likes/count` returns 0 on no-likes post, `GET /likes/count` 404 on missing/block-hidden post, `GET /likes/count` does NOT vary per-viewer by caller's block list, all three endpoints 401 without JWT
- [x] 8.2 `./gradlew :backend:ktor:test --tests '*LikeEndpointsTest*'` green (17/17)

## 9. Verification + integration

- [x] 9.1 `./gradlew :backend:ktor:test` — all backend tests green with no regressions on signup, post-creation, nearby-timeline, following-timeline, block-endpoints, or follow-endpoints
- [x] 9.2 `./gradlew detekt` green; `./gradlew :lint:detekt-rules:test` green
- [x] 9.3 Manual curl script exercising the full vertical: signup 3 users → A creates a post P → B likes P → GET /likes/count shows 1 → A's Nearby response shows `liked_by_viewer = false` for P; B's Nearby response shows `liked_by_viewer = true` → B unlikes P → count 0 → both timelines show `liked_by_viewer = false` → B blocks A → `POST /like` and `GET /likes/count` on P return 404 from B → B unblocks A → surfaces return 200/204 again. Document the script in the PR description
- [x] 9.4 `openspec validate post-likes-v7 --strict` — change remains valid
- [x] 9.5 Confirm `docs/05-Implementation.md` §694–702 is UNCHANGED by this PR (verbatim DDL not modified); only the §690 back-reference is added
