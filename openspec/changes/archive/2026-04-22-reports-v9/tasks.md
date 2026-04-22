## 1. V9 Flyway migration

- [x] 1.1 Create `backend/ktor/src/main/resources/db/migration/V9__reports_moderation.sql` with the header comment block documenting: (a) single-side schema dependency on V3 `users` via `reports.reporter_id` CASCADE, (b) polymorphic `target_id` intentionally without FK, (c) deferred-FK pattern for `reviewed_by` / `resolved_by` targeting the future `admin_users` table, (d) V9-era consumers (ReportService POST, transactional auto-hide path, `auto_hide_3_reports` queue writer), (e) reference to `docs/05-Implementation.md` §745–816 as canonical source
- [x] 1.2 Add `CREATE TABLE reports` with all 11 columns, CHECK constraints (target_type 4-value, reason_category 8-value, status 3-value), UNIQUE `(reporter_id, target_type, target_id)`, and `reporter_id` FK → `users(id) ON DELETE CASCADE`
- [x] 1.3 Add 3 indexes on `reports`: `reports_status_idx(status, created_at DESC)`, `reports_target_idx(target_type, target_id)`, `reports_reporter_idx(reporter_id, created_at DESC)`
- [x] 1.4 Add `COMMENT ON COLUMN reports.reviewed_by` documenting the deferred FK target `admin_users(id) ON DELETE SET NULL`
- [x] 1.5 Add `CREATE TABLE moderation_queue` with all 12 columns, CHECK constraints (target_type 4-value, trigger 7-value full enum, status 2-value, resolution 8-value + NULL, priority SMALLINT DEFAULT 5), UNIQUE `(target_type, target_id, trigger)`
- [x] 1.6 Add 2 indexes on `moderation_queue`: `moderation_queue_status_idx(status, priority, created_at)`, `moderation_queue_target_idx(target_type, target_id)`
- [x] 1.7 Add `COMMENT ON COLUMN moderation_queue.resolved_by` documenting the deferred FK target `admin_users(id) ON DELETE SET NULL`
- [x] 1.8 Run `./scripts/flyway-migrate-local.sh` (or equivalent) against a fresh local Postgres to verify V1→V9 cold migrate succeeds
- [x] 1.9 Run Flyway a second time against the V9 DB to verify idempotency (no new `flyway_schema_history` rows; no errors)

## 2. Detekt rule allowlist update

- [x] 2.1 Locate the `RawFromPostsRule` Detekt custom rule source under `backend/ktor/config/detekt/` (or wherever V4/V7/V8 registered prior allowlist entries) and read the current allowlist structure
- [x] 2.2 Add an allowlist entry that matches: files under `backend/ktor/src/main/kotlin/id/nearyou/app/moderation/` whose filename begins with `Report` (e.g., `ReportService.kt`, `ReportRoutes.kt`, `ReportController.kt`, `ReportTargetResolver.kt`)
- [x] 2.3 Add a rule-source comment citing the `visible-posts-view` spec and the four-case rationale (blocker/blocked/shadow-banned/auto-hidden targets are all legitimate report sources)
- [x] 2.4 Add/extend Detekt fixture tests to verify: (a) `ReportService.kt` under `.../moderation/` with `SELECT 1 FROM posts WHERE id = ? AND deleted_at IS NULL` passes, (b) `ModerationDashboardReader.kt` under `.../moderation/` with `SELECT * FROM posts` still fails, (c) `ReportLikeDashboard.kt` OUTSIDE `.../moderation/` with `SELECT * FROM posts` still fails
- [x] 2.5 Run `./gradlew detekt` locally and confirm green

## 3. Repository + service layer

- [x] 3.1 Create `backend/ktor/src/main/kotlin/id/nearyou/app/moderation/ReportRepository.kt` interface with `insertReport(...)`, `countDistinctAgedReporters(targetType, targetId)`, and `targetExists(targetType, targetId)` methods
- [x] 3.2 Create `JdbcReportRepository.kt` implementation with: (a) raw `SELECT 1 FROM posts/post_replies/users/chat_messages WHERE id = ? AND deleted_at IS NULL` per target_type, (b) `INSERT INTO reports (...)` returning SQLSTATE 23505 for duplicates, (c) the documented `COUNT(DISTINCT reporter_id) ... JOIN users u ... WHERE u.created_at < NOW() - INTERVAL '7 days' AND u.deleted_at IS NULL` query
- [x] 3.3 Create `ModerationQueueRepository.kt` interface with `upsertAutoHideRow(targetType, targetId)` method using `INSERT ... ON CONFLICT (target_type, target_id, trigger) DO NOTHING`
- [x] 3.4 Create `JdbcModerationQueueRepository.kt` implementation
- [x] 3.5 Create `PostAutoHideRepository.kt` method `flipIsAutoHidden(targetType: 'post'|'reply', targetId)` issuing the per-target-type `UPDATE ... SET is_auto_hidden = TRUE WHERE id = ? AND deleted_at IS NULL`
- [x] 3.6 Create `ReportService.kt` with `submitReport(reporterId, body): Result` running the full flow: rate-limit check → validation → self-report check → target-existence → BEGIN TX → INSERT reports (catch 23505 → 409) → COUNT DISTINCT aged → conditional UPDATE posts/post_replies → INSERT moderation_queue ON CONFLICT DO NOTHING → COMMIT → structured log + Sentry breadcrumb on threshold crossing
- [x] 3.7 Wire Redis-backed sliding-window rate limiter against key `{scope:rate_report}:{user:<id>}` with cap 10 / window 1 hour; returns remaining quota + Retry-After seconds; rate-limit check must run BEFORE any DB work; 409 duplicates must NOT consume a slot
- [x] 3.8 Add a `// TODO: notifications-api-v??` stub comment at the flip site referencing the deferred `post_auto_hidden` notification

## 4. HTTP route + request/response shapes

- [x] 4.1 Create `ReportRoutes.kt` registering `POST /api/v1/reports` behind the existing `authenticate("auth-jwt")` block
- [x] 4.2 Create `ReportRequest` DTO (`target_type`, `target_id`, `reason_category`, `reason_note?`) with kotlinx.serialization
- [x] 4.3 Implement body validation: target_type enum (4 values), target_id UUID parse, reason_category enum (8 values), reason_note ≤ 200 code points after NFKC normalization; failures return 400 with `invalid_request`
- [x] 4.4 Implement self-report rejection: `target_type == "user" && target_id == caller.id` → 400 `self_report_rejected` BEFORE target-existence lookup
- [x] 4.5 Map repository / service outcomes to HTTP: success → 204 no body; target_not_found → 404 `target_not_found`; SQLSTATE 23505 → 409 `duplicate_report`; rate-limit exceeded → 429 `rate_limited` + `Retry-After` header; unauth → 401 `unauthenticated` (plugin)
- [x] 4.6 Apply the project error envelope `{ "error": { "code": ..., "message": ... } }` on all 4xx responses
- [x] 4.7 Register `ReportRoutes` in the root routing module (`Application.module(...)` or equivalent)

## 5. Migration smoke test

- [x] 5.1 Create `backend/ktor/src/test/kotlin/.../MigrationV9SmokeTest.kt` tagged `database`
- [x] 5.2 Assert both tables exist with the full documented column set (name, type, nullability, default) via `information_schema.columns`
- [x] 5.3 Assert all 8 CHECK constraints reject out-of-enum values (reports.target_type, reports.reason_category, reports.status, moderation_queue.target_type, moderation_queue.trigger, moderation_queue.status, moderation_queue.resolution, priority default) by attempting INSERTs and catching check-constraint violations
- [x] 5.4 Assert `reports` UNIQUE `(reporter_id, target_type, target_id)` fires SQLSTATE `23505` on duplicate INSERT
- [x] 5.5 Assert `moderation_queue` UNIQUE `(target_type, target_id, trigger)` fires SQLSTATE `23505` on duplicate INSERT
- [x] 5.6 Assert all 5 indexes exist with their documented column orders via `pg_indexes`
- [x] 5.7 Assert `reports.reporter_id` FK has `delete_rule = 'CASCADE'` via `information_schema.referential_constraints`
- [x] 5.8 Assert `reports.reviewed_by` / `moderation_queue.resolved_by` / `reports.target_id` / `moderation_queue.target_id` each have zero rows in `information_schema.referential_constraints` (no FK)
- [x] 5.9 Assert `reports.reviewed_by` and `moderation_queue.resolved_by` each have a `pg_description` comment containing `admin_users` AND `deferred`
- [x] 5.10 Assert CASCADE behavior: insert user + report; DELETE FROM users (no other RESTRICT FKs blocking); confirm the `reports` row is cascade-deleted in the same transaction
- [x] 5.11 Assert V8→V9 incremental migrate advances schema; then second run against V9 is a no-op
- [x] 5.12 Assert cold V1→V9 migrate succeeds against empty Postgres AND `flyway_schema_history` contains rows for versions `'1'` through `'9'`

## 6. ReportService integration tests

- [x] 6.1 Happy path per target_type: create seed aged reporter + target of each type (post/reply/user/chat_message), POST /api/v1/reports with valid body, assert 204 + row inserted
- [x] 6.2 400 validation: missing field, out-of-enum target_type, non-UUID target_id, out-of-enum reason_category, 201-char reason_note
- [x] 6.3 400 self-report: `target_type = "user" && target_id = caller.id` returns 400 `self_report_rejected`; `target_type = "post"` on a post the caller authored does NOT self-reject
- [x] 6.4 404 target_not_found: random UUID per target_type; soft-deleted post (`deleted_at IS NOT NULL`); soft-deleted reply
- [x] 6.5 Block-aware acceptance: (a) reporter blocked BY target author, (b) reporter blocks target author, (c) target author is shadow-banned, (d) target post is already auto-hidden — ALL four return 204 + row inserted
- [x] 6.6 409 duplicate: same reporter + same (target_type, target_id) twice → second is 409 `duplicate_report`; `reports` table has exactly one row for the tuple; auto-hide COUNT DISTINCT must NOT re-run on the 409 path
- [x] 6.7 429 rate limit: 10 distinct valid reports within an hour → all 204; 11th → 429 `rate_limited` with positive-integer `Retry-After`; Redis key has hash-tag form `{scope:rate_report}:{user:<uuid>}`; 409 duplicates do NOT consume a slot
- [x] 6.8 Auto-hide at exactly 3 aged reporters — post variant: seed 2 aged reports; 3rd aged reporter submits; assert `posts.is_auto_hidden = TRUE` AND `moderation_queue` row with `trigger = 'auto_hide_3_reports'` + `target_type = 'post'` + `priority = 5`
- [x] 6.9 Auto-hide at exactly 3 aged reporters — reply variant: same flow, assert `post_replies.is_auto_hidden = TRUE` + queue row with `target_type = 'reply'`
- [x] 6.10 Auto-hide at exactly 3 aged reporters — user variant: assert NO column is updated on users AND a queue row with `target_type = 'user'` exists
- [x] 6.11 Auto-hide at exactly 3 aged reporters — chat_message variant: assert NO column is updated on chat_messages AND a queue row with `target_type = 'chat_message'` exists
- [x] 6.12 < 3 aged reporters: 2 aged + 1 young (< 7 days) reporter → COUNT DISTINCT = 2 → `is_auto_hidden` remains FALSE AND no queue row
- [x] 6.13 Soft-deleted reporter excluded: 3 distinct reporters but one has `users.deleted_at IS NOT NULL` → COUNT DISTINCT = 2 → no flip
- [x] 6.14 Aged-past-threshold account now counts: reporter A's account created 6 days ago files report #1; A ages to > 7 days; 2 other aged reporters already exist; A's *same* target gets a 3rd report from a new aged reporter → COUNT DISTINCT includes A → flip fires (validates count-at-query-time semantics)
- [x] 6.15 UPDATE idempotency: already-hidden post + 4th aged reporter → 204, no churn, `moderation_queue` still has exactly one row
- [x] 6.16 UPDATE skips soft-deleted target: target post with `deleted_at IS NOT NULL` + 3 aged reporters → UPDATE affects 0 rows (WHERE excludes tombstoned); queue row IS still inserted
- [x] 6.17 Concurrent race: two transactions both cross the threshold on the same target simultaneously → exactly one `moderation_queue` row persists AND neither tx fails (ON CONFLICT DO NOTHING handles it)
- [x] 6.18 Transaction atomicity: after auto-hide commits, `reports` row + `is_auto_hidden = TRUE` + queue row are all visible together (no partial-commit window observable from a concurrent reader)
- [x] 6.19 Observability — threshold crossing: assert a structured log line with `event = "auto_hide_triggered"`, `target_type`, `target_id`, `reporter_count >= 3` is emitted on the flip
- [x] 6.20 Observability — sub-threshold: 1st / 2nd aged reporter submissions emit NO `auto_hide_triggered` log line
- [x] 6.21 Observability — 4th+ reporter on already-hidden: 4th aged reporter emits NO `auto_hide_triggered` log line (threshold not crossed by this submission)
- [x] 6.22 Error envelope shape: assert all 4xx bodies match `{ "error": { "code": ..., "message": ... } }` with non-empty message

## 7. Read-path non-regression tests

- [x] 7.1 After flipping `posts.is_auto_hidden = TRUE` via the reports path, assert the post is absent from `GET /api/v1/timeline/nearby` for a non-author viewer
- [x] 7.2 After flipping `posts.is_auto_hidden = TRUE`, assert the post is absent from `GET /api/v1/timeline/following` for a non-author viewer
- [x] 7.3 After flipping `post_replies.is_auto_hidden = TRUE`, assert the reply is absent from `GET /api/v1/posts/{post_id}/replies` for a non-author viewer
- [x] 7.4 Author-bypass preserved: after flipping `post_replies.is_auto_hidden = TRUE`, the author themselves still sees their own reply in `GET /replies`
- [x] 7.5 Reply-counter preserved: a post whose reply was auto-hidden still reports the same `reply_count` on both timelines (V8 counter excludes only shadow-banned authors, not auto-hidden replies)
- [x] 7.6 POST /api/v1/posts response shape unchanged: new posts still have `is_auto_hidden = FALSE` on insert; response key set still `{ "id", "content", "latitude", "longitude", "distance_m", "created_at" }`
- [x] 7.7 GET /api/v1/posts/{post_id}/replies WHERE clause still contains `(is_auto_hidden = FALSE OR author_id = :viewer)` (inspect SQL)
- [x] 7.8 `visible_posts` view definition after V9 still equals `SELECT * FROM posts WHERE is_auto_hidden = FALSE` (semantic equivalence via `pg_views`)
- [x] 7.9 Grep V9-introduced code for `FROM visible_posts` — assert zero matches (V9 is write-only against `is_auto_hidden`)

## 8. Deploy + verification

- [x] 8.1 Run full local test suite: `./gradlew :backend:ktor:test` (includes MigrationV9SmokeTest + integration tests) — all green
- [x] 8.2 Run `./gradlew detekt` — green, including the new `RawFromPostsRule` allowlist entry
- [ ] 8.3 Open PR; verify CI passes all checks (build, detekt, test, migration-smoke)
- [ ] 8.4 Staging: merge to `main` auto-triggers `nearyou-migrate` Cloud Run Job → V9 applies → backend deploy reads V9 schema
- [ ] 8.5 Staging smoke: POST /api/v1/reports with a seed user against seed targets — verify target-existence 404, 409 duplicate, 400 self-report, and a manually crafted 3-aged-reporter auto-hide fires on a seed post (confirm `is_auto_hidden = TRUE` + queue row in staging DB)
- [ ] 8.6 Prod: git tag `v*` after staging smoke passes; monitor Sentry + structured logs for the first production `auto_hide_triggered` event; confirm no migration errors in `flyway_schema_history`
- [ ] 8.7 Run `openspec archive reports-v9` after the change is fully shipped and the specs have synced
