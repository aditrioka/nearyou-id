## 1. V6 migration: follows

- [x] 1.1 Create `backend/ktor/src/main/resources/db/migration/V6__follows.sql` with the `follows` table — PK `(follower_id, followee_id)` + `follows_follower_idx (follower_id, created_at DESC)` + `follows_followee_idx (followee_id, created_at DESC)` + CHECK + dual ON DELETE CASCADE to `users(id)`
- [x] 1.2 V6 header documents: schema-level FK dependency on V3 (`users`), runtime coupling to V5 (`user_blocks` — mutual-block check + transactional follow-cascade), and the canonical `followed_id → followee_id` rename with a pointer to update `docs/05-Implementation.md` §1295
- [x] 1.3 V6 header names `nearby-timeline` and `following-timeline` as the read-path consumers that MUST bidirectionally join `user_blocks`
- [x] 1.4 Run `./gradlew :backend:ktor:flywayMigrate` against the local dev DB; verify V6 row in `flyway_schema_history`
- [x] 1.5 Write `MigrationV6SmokeTest` (tagged `database`) covering the five invariants: clean migrate from V5, idempotent second run, both indexes with documented column orders, PK uniqueness, CHECK self-follow rejection, both FK cascade directions
- [x] 1.6 `./gradlew :backend:ktor:test --tests '*MigrationV6SmokeTest*'` green

## 2. docs/05-Implementation.md rename alignment

- [x] 2.1 Update `docs/05-Implementation.md` §669–687 (`follows` schema block): rename `followed_id` → `followee_id` in the `CREATE TABLE` body, in the `follows_followed_idx` index (now `follows_followee_idx`), in the indexes' column list, and in any surrounding prose
- [x] 2.2 Update `docs/05-Implementation.md` §1057–1067 (canonical Following timeline query): rename `followed_id` → `followee_id` in the `IN (SELECT followed_id FROM follows ...)` subquery
- [x] 2.3 Update `docs/05-Implementation.md` §1286–1300 (Block Action Flow): rename `followed_id` → `followee_id` in the cascade DELETE SQL
- [x] 2.4 Update `docs/05-Implementation.md` §1124 (private-profile visibility gate referencing `follows`): rename `followed_id` → `followee_id`
- [x] 2.5 Grep `docs/` for any remaining `followed_id` occurrences; rename each one; ensure no dangling references

## 3. Follow repository + service

- [x] 3.1 Add `UserFollowsRepository` interface in `core/data/src/main/kotlin/id/nearyou/data/repository/` with methods `follow(follower: UUID, followee: UUID)`, `unfollow(follower: UUID, followee: UUID)`, `listFollowers(profileId: UUID, viewerId: UUID, cursor: Cursor?, limit: Int)`, `listFollowing(profileId: UUID, viewerId: UUID, cursor: Cursor?, limit: Int)`
- [x] 3.2 Add `JdbcUserFollowsRepository` in `infra/supabase/...` implementing the interface
- [x] 3.3 `follow`: execute `SELECT 1 FROM user_blocks WHERE (blocker_id=? AND blocked_id=?) OR (blocker_id=? AND blocked_id=?) LIMIT 1` first; if present, throw `FollowBlockedException`. Then `INSERT INTO follows (follower_id, followee_id) VALUES (?, ?) ON CONFLICT (follower_id, followee_id) DO NOTHING`; on SQLState `23503` (FK violation), throw `UserNotFoundException`
- [x] 3.4 `unfollow`: `DELETE FROM follows WHERE follower_id = ? AND followee_id = ?`; ignore rowcount; no exception on zero rows
- [x] 3.5 `listFollowers`: keyset query with bidirectional viewer-block NOT-IN subqueries against `follows.follower_id`, ordered `(created_at DESC, follower_id DESC)`, LIMIT 31. First verify the profile user exists (`SELECT 1 FROM users WHERE id = ?`) or map `404 user_not_found`
- [x] 3.6 `listFollowing`: symmetric to 3.5, operating on `follows.followee_id`
- [x] 3.7 Add `FollowService.kt` in `backend/ktor/.../follow/` wrapping the repository: exposes `follow`, `unfollow`, `listFollowers`, `listFollowing`; enforces self-follow rejection (`CannotFollowSelfException`) before repository call

## 4. Follow HTTP routes

- [x] 4.1 Create `FollowRoutes.kt` in `backend/ktor/.../follow/` registering `POST /api/v1/follows/{user_id}` and `DELETE /api/v1/follows/{user_id}` under `authenticate(AUTH_PROVIDER_USER)`
- [x] 4.2 Map exceptions: `CannotFollowSelfException` → 400 `cannot_follow_self`; `UserNotFoundException` → 404 `user_not_found`; `FollowBlockedException` → 409 `follow_blocked`; `InvalidCursorException` → 400 `invalid_cursor`
- [x] 4.3 Ensure the 409 `follow_blocked` response body is a constant `{ "error": { "code": "follow_blocked" } }` with no additional fields (no direction hint)
- [x] 4.4 Create `UserSocialRoutes.kt` (or extend existing user routes file) registering `GET /api/v1/users/{user_id}/followers` and `GET /api/v1/users/{user_id}/following` under `authenticate(AUTH_PROVIDER_USER)`; response shape `{ users: [{ user_id, created_at }], next_cursor }`
- [x] 4.5 Wire `followRoutes(followService)` and `userSocialRoutes(followService)` into `Application.module()` after `blockRoutes`
- [x] 4.6 Follower/following list route handlers use the same base64url JSON cursor format as `nearby-timeline`; `i` field encodes the `follower_id` / `followee_id` UUID at the page boundary

## 5. BlockService transactional cascade

- [x] 5.1 Modify `JdbcUserBlockRepository.block()` (or equivalent) to wrap two statements in a single JDBC transaction: (a) the existing `INSERT INTO user_blocks ... ON CONFLICT DO NOTHING`, (b) new `DELETE FROM follows WHERE (follower_id = ? AND followee_id = ?) OR (follower_id = ? AND followee_id = ?)`
- [x] 5.2 Use `connection.autoCommit = false` + explicit `commit()`/`rollback()` — same pattern as `JdbcPostRepository.createPost()`; do NOT introduce a generic `@Transactional` helper
- [x] 5.3 Extend `BlockEndpointsTest` with scenarios: (a) block cascades outbound follow, (b) block cascades inbound follow, (c) block cascades both directions in one transaction, (d) block with no follows rows still returns 204 and leaves follows untouched
- [x] 5.4 Verify existing `BlockEndpointsTest` scenarios still pass (they test outcomes, not statement counts)

## 6. Following timeline service + endpoint

- [x] 6.1 Add `FollowingTimelineService.kt` in `backend/ktor/.../timeline/` alongside `NearbyTimelineService`; add `PostsFollowingRepository` interface (or extend `PostsTimelineRepository`) + Jdbc impl in `:infra:supabase`
- [x] 6.2 Implement canonical Following query verbatim per design Decision 3: `FROM visible_posts p` WHERE `p.author_user_id IN (SELECT followee_id FROM follows WHERE follower_id = :viewer)` AND both `user_blocks` NOT-IN subqueries AND keyset on `(created_at, id)` AND `ORDER BY created_at DESC, id DESC` AND `LIMIT 31`; `lat`/`lng` via `ST_Y`/`ST_X` over `display_location::geometry`
- [x] 6.3 Verify query contains `user_blocks`, `blocker_id =`, `blocked_id =` tokens (confirms `BlockExclusionJoinRule` compatibility; rule only scans Kotlin files, so this is defensive)
- [x] 6.4 Param parsing: only `cursor` is recognized; unknown params ignored; malformed cursor → 400 `invalid_cursor` via shared `decodeCursor`
- [x] 6.5 Response shape: `{ id, authorUserId, content, latitude, longitude, createdAt }` — NO `distance_m` field, NO `radius_m` param; `nextCursor` from 31st row if present
- [x] 6.6 Extend `TimelineRoutes.kt` to register `GET /api/v1/timeline/following` behind `authenticate(AUTH_PROVIDER_USER)`
- [x] 6.7 Write `FollowingTimelineServiceTest` (tagged `database`) covering all 9 scenarios from the `following-timeline` spec: happy path, empty set, cursor pagination, auto-hidden exclusion, bidirectional block exclusion (two sub-cases), non-followed-author exclusion, response has no `distance_m`, 401 without JWT, 400 invalid cursor
- [x] 6.8 `./gradlew :backend:ktor:test --tests '*FollowingTimelineServiceTest*'` green

## 7. Lint: document follows exclusion

- [x] 7.1 Add a KDoc paragraph to `BlockExclusionJoinRule.kt` explaining why `follows` is deliberately NOT a protected table (three reasons from the `block-exclusion-lint` spec ADDED requirement)
- [x] 7.2 Add test fixture `follows_is_deliberately_not_protected_table` to `BlockExclusionJoinLintTest` asserting the rule does NOT fire on: (a) `"SELECT follower_id FROM follows WHERE followee_id = ?"`, (b) `"INSERT INTO follows (follower_id, followee_id) VALUES (?, ?) ON CONFLICT (follower_id, followee_id) DO NOTHING"`, (c) `"DELETE FROM follows WHERE follower_id = ? AND followee_id = ?"`
- [x] 7.3 `./gradlew :lint:detekt-rules:test` green

## 8. Follow endpoints integration tests

- [x] 8.1 Write `FollowEndpointsTest` (tagged `database`) covering all 16 scenarios from the `follow-system` spec: first follow 204, re-follow idempotent, self-follow 400, target not found 404, caller-blocked-target 409, target-blocked-caller 409, 409 body identical in both directions, unfollow existing 204, unfollow no-op 204, `/followers` ordered correctly, `/followers` viewer-filter (both directions), `/followers` pagination, `/followers` 404 on unknown profile, `/following` ordered correctly, `/following` viewer-filter (both directions), all five endpoints 401 without JWT
- [x] 8.2 `./gradlew :backend:ktor:test --tests '*FollowEndpointsTest*'` green (16/16)

## 9. Verification + integration

- [x] 9.1 `./gradlew :backend:ktor:test` — all backend tests green with no regressions on signup, post-creation, nearby-timeline, or block endpoints
- [x] 9.2 `./gradlew detekt` green; `./gradlew :lint:detekt-rules:test` green
- [x] 9.3 Covered by integration tests plus a brief manual curl script exercising the full vertical: signup 3 users → A follows B → A/timeline/following shows B's posts → A blocks B → follows row gone, /timeline/following empty → A unblocks B → A can re-follow. Document the script in the PR description
- [x] 9.4 `openspec validate following-timeline-with-follow-cascade --strict` — change remains valid
- [x] 9.5 Update `docs/08-Roadmap-Risk.md`: Phase 1 item 15 (Following shipped; only Global remains), item 16 (follow-cascade shipped), item 21 `follows` bullet (shipped as V6)
