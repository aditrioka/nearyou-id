## 1. V10 Flyway migration

- [x] 1.1 Create `backend/ktor/src/main/resources/db/migration/V10__notifications.sql` with the header comment block documenting: (a) FK dependencies on V3 `users` (two edges: `user_id` CASCADE, `actor_user_id` SET NULL), (b) V10-era writers (LikeService → `post_liked`, ReplyService → `post_replied`, FollowService → `followed`, ReportService auto-hide → `post_auto_hidden`), (c) reserved-for-future enum values (the other 9 type values have no V10 callers), (d) reference to `docs/05-Implementation.md` §820–844 as canonical source
- [x] 1.2 Add `CREATE TABLE notifications` with 9 columns: `id UUID PRIMARY KEY DEFAULT gen_random_uuid()`, `user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE`, `type VARCHAR(48) NOT NULL CHECK (type IN (...))`, `actor_user_id UUID REFERENCES users(id) ON DELETE SET NULL`, `target_type VARCHAR(32)`, `target_id UUID`, `body_data JSONB NOT NULL DEFAULT '{}'::jsonb`, `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`, `read_at TIMESTAMPTZ`
- [x] 1.3 Include the full 13-value `type` CHECK enum (`post_liked`, `post_replied`, `followed`, `post_auto_hidden`, `chat_message`, `chat_message_redacted`, `subscription_purchased`, `subscription_expiring`, `subscription_expired`, `account_action_applied`, `data_export_ready`, `privacy_flip_warning`, `username_release_scheduled`, `apple_relay_email_changed`) even though V10 only emits 4
- [x] 1.4 Add `CREATE INDEX notifications_user_unread_idx ON notifications (user_id, created_at DESC) WHERE read_at IS NULL` (partial index, valid because predicate is immutable)
- [x] 1.5 Add `CREATE INDEX notifications_user_all_idx ON notifications (user_id, created_at DESC)` (full index for paginated list)
- [x] 1.6 Run `./scripts/flyway-migrate-local.sh` (or equivalent) against fresh local Postgres to verify V1→V10 cold migrate succeeds (covered by the Kotest `MigrationV10SmokeTest` spec "flyway_schema_history contains versions 1..10 all successful" — passes on a fresh `dev/docker-compose.yml` Postgres)
- [x] 1.7 Run Flyway a second time against the V10 DB to verify idempotency (no new `flyway_schema_history` rows; no errors) — covered by `MigrationV10SmokeTest` spec "re-running flywayMigrate against a DB already at V10 is a no-op"
- [x] 1.8 Verify Detekt partial-index lint rule does NOT flag the `WHERE read_at IS NULL` predicate (rule targets volatile-function predicates only)

## 2. NotificationEmitter + NotificationService + NotificationDispatcher

- [x] 2.1 Create `backend/ktor/src/main/kotlin/id/nearyou/app/notifications/NotificationEmitter.kt` interface with `emit(tx: Transaction, recipient: UUID, actor: UUID?, type: NotificationType, targetType: String?, targetId: UUID?, bodyData: JsonObject): Unit`
- [x] 2.2 Create `DbNotificationEmitter.kt` implementation: (a) if `actor != null`, query `user_blocks` bidirectionally — if any row exists, return without inserting; (b) if `actor == recipient`, return without inserting (self-action belt-and-suspenders); (c) otherwise `INSERT INTO notifications (...)` within the passed transaction; (d) after commit (via a tx-completion hook or at service-layer boundary), enqueue a `NotificationDispatcher.dispatch(notificationId)` call
- [x] 2.3 Create `NotificationType.kt` enum mirroring the 13 DB CHECK values; serialize to snake_case strings matching DB
- [x] 2.4 Create `NotificationDispatcher.kt` interface with `dispatch(notificationId: UUID): Unit` and a `NoopNotificationDispatcher` default implementation that logs + returns (FCM is a separate change)
- [x] 2.5 Create `NotificationService.kt` with read-path methods: `list(userId, cursor?, unreadOnly: Boolean, limit: Int): NotificationPage`, `unreadCount(userId): Long`, `markRead(userId, notificationId): Boolean`, `markAllRead(userId): Int`
- [x] 2.6 Wire `NotificationEmitter`, `NotificationDispatcher`, `NotificationService`, `NotificationRepository` in the Koin module (or equivalent DI) with bindings for the default impls

## 3. Repository layer

- [x] 3.1 Create `NotificationRepository.kt` interface with `insert(...)`, `listByUser(userId, cursor, unreadOnly, limit)`, `countUnread(userId)`, `markRead(userId, id)`, `markAllRead(userId)`, `isBlockedBetween(userA, userB)` methods
- [x] 3.2 Create `JdbcNotificationRepository.kt` implementation: (a) cursor pagination over `created_at DESC` using `(created_at, id)` as composite cursor, (b) `unreadOnly = true` branch adds `AND read_at IS NULL` (hits partial index), (c) `markRead` uses `UPDATE notifications SET read_at = NOW() WHERE id = ? AND user_id = ? AND read_at IS NULL` returning affected-row count (idempotent if already read), (d) `markAllRead` updates all unread rows for user, returns count
- [x] 3.3 Implement `isBlockedBetween` as a single SQL check: `SELECT 1 FROM user_blocks WHERE (blocker_id = :a AND blocked_id = :b) OR (blocker_id = :b AND blocked_id = :a) LIMIT 1`

## 4. Integrate emitter into LikeService

- [x] 4.1 Locate `LikeService.kt` (shipped V7) and find the `INSERT INTO post_likes ... ON CONFLICT DO NOTHING` code path
- [x] 4.2 Capture whether the INSERT actually inserted (affected rows > 0) — only fire the emit on the transition from "not liked" to "liked"
- [x] 4.3 Load `post.author_id` and the first 80 code points of `post.content` within the same transaction
- [x] 4.4 Call `NotificationEmitter.emit(tx, recipient = post.author_id, actor = caller, type = post_liked, targetType = "post", targetId = postId, bodyData = { "post_excerpt": <excerpt> })`
- [x] 4.5 Verify no response-shape changes to `POST /like` / `DELETE /like` (still 204 no body)

## 5. Integrate emitter into ReplyService

- [x] 5.1 Locate `ReplyService.kt` (shipped V8) and find the `INSERT INTO post_replies` success path
- [x] 5.2 Load `parentPost.author_id` within the same transaction
- [x] 5.3 Call `NotificationEmitter.emit(tx, recipient = parentPost.author_id, actor = caller, type = post_replied, targetType = "post", targetId = parentPost.id, bodyData = { "reply_id": <new reply uuid>, "reply_excerpt": <first 80 code points of reply.content> })`
- [x] 5.4 Verify no response-shape changes to `POST /replies` / `GET /replies` / `DELETE /replies/{id}`

## 6. Integrate emitter into FollowService

- [x] 6.1 Locate `FollowService.kt` (shipped V6) and find the `INSERT INTO follows` success path (capture actually-inserted vs ON CONFLICT no-op if applicable)
- [x] 6.2 Call `NotificationEmitter.emit(tx, recipient = followeeId, actor = caller, type = followed, targetType = null, targetId = null, bodyData = {})`
- [x] 6.3 Verify `DELETE /follow` does NOT emit any counter-notification
- [x] 6.4 Verify no response-shape changes to `POST /follow` / `DELETE /follow`

## 7. Integrate emitter into ReportService auto-hide path

- [x] 7.1 Locate the V9 auto-hide flip site in `ReportService.kt` — the `// TODO: notifications-api-v??` comment
- [x] 7.2 Remove the TODO comment and add `NotificationEmitter.emit(tx, recipient = target.authorId, actor = null, type = post_auto_hidden, targetType = <"post" | "reply">, targetId = target.id, bodyData = { "reason": "auto_hide_3_reports" })`
- [x] 7.3 Fire the emit ONLY when the UPDATE actually flipped `is_auto_hidden = FALSE → TRUE` (use affected-row count; re-flips on already-hidden targets must NOT re-emit)
- [x] 7.4 Skip the emit entirely for `target_type = 'user'` or `'chat_message'` (no `is_auto_hidden` column; no `post_auto_hidden` notification applies)
- [x] 7.5 Verify `POST /api/v1/reports` response shape unchanged (still 204 no body on success)

## 8. HTTP routes for read path

- [x] 8.1 Create `NotificationRoutes.kt` behind `authenticate("auth-jwt")` with 4 endpoints: `GET /api/v1/notifications`, `GET /api/v1/notifications/unread-count`, `PATCH /api/v1/notifications/:id/read`, `PATCH /api/v1/notifications/read-all` (note: implementation uses the project-wide `AUTH_PROVIDER_USER` = `"user-jwt"` plugin identifier; see app/auth/AuthPlugin.kt)
- [x] 8.2 `GET /api/v1/notifications` query params: `cursor?` (opaque string encoding `(created_at, id)`), `unread=true|false` (default false), `limit` (default 20, max 50); response shape: `{ "items": [...], "next_cursor": "..." | null }` with each item containing `id, type, actor_user_id, target_type, target_id, body_data, created_at, read_at`
- [x] 8.3 `GET /api/v1/notifications/unread-count` response: `{ "count": <long> }`
- [x] 8.4 `PATCH /api/v1/notifications/:id/read` response: 204 no body on success; 404 `not_found` if id does not belong to caller or does not exist
- [x] 8.5 `PATCH /api/v1/notifications/read-all` response: `{ "marked_read": <int count> }`
- [x] 8.6 Apply the project error envelope `{ "error": { "code": ..., "message": ... } }` on all 4xx responses
- [x] 8.7 Register `NotificationRoutes` in the root routing module

## 9. Migration smoke test

- [x] 9.1 Create `MigrationV10SmokeTest.kt` tagged `database`
- [x] 9.2 Assert `notifications` table exists with full 9-column shape (name, type, nullability, default) via `information_schema.columns`
- [x] 9.3 Assert the `type` CHECK constraint rejects an unknown enum value (e.g. `"bogus_type"`) via INSERT + catching check-constraint violation
- [x] 9.4 Assert the `type` CHECK accepts all 13 documented values
- [x] 9.5 Assert both indexes exist with correct columns and order via `pg_indexes` — confirm `notifications_user_unread_idx` has `indpred` matching `read_at IS NULL`
- [x] 9.6 Assert `notifications.user_id` FK has `delete_rule = 'CASCADE'`; assert `notifications.actor_user_id` FK has `delete_rule = 'SET NULL'` via `information_schema.referential_constraints`
- [x] 9.7 Assert CASCADE behavior: insert user + notification; DELETE FROM users; confirm notification is cascade-deleted
- [x] 9.8 Assert SET NULL behavior: insert user A + user B + notification with `user_id = A, actor_user_id = B`; DELETE user B; confirm notification row remains with `actor_user_id IS NULL`
- [x] 9.9 Assert V9→V10 incremental migrate advances schema; then second run against V10 is a no-op
- [x] 9.10 Assert cold V1→V10 migrate succeeds AND `flyway_schema_history` contains rows for versions `'1'` through `'10'`

## 10. Write-path integration tests

- [x] 10.1 Like → notification: Bob likes Alice's post → assert one `notifications` row with `user_id = Alice, type = 'post_liked', actor_user_id = Bob, target_type = 'post', target_id = <postId>, body_data.post_excerpt = <first 80 chars>`
- [x] 10.2 Self-like → no notification: Alice likes own post → zero notification rows
- [x] 10.3 Like suppressed when recipient blocked actor: Alice blocks Bob, Bob likes Alice's post → zero notification rows
- [x] 10.4 Like suppressed when actor blocked recipient: Bob blocks Alice, Bob likes Alice's post → zero notification rows (bidirectional)
- [x] 10.5 Re-like idempotent: Bob likes Alice's post twice → exactly one notification row
- [x] 10.6 Unlike → no counter-notification: Bob unlikes Alice's post → original `post_liked` row still present; no new row
- [x] 10.7 Reply → notification: Bob replies to Alice's post → assert one row with `type = 'post_replied', target_type = 'post', target_id = parentPostId, body_data.reply_id = <new id>, body_data.reply_excerpt = <first 80 chars>`
- [x] 10.8 Self-reply → no notification
- [x] 10.9 Reply suppressed when blocked (both directions)
- [x] 10.10 Follow → notification: Bob follows Alice → assert one row with `type = 'followed', target_type IS NULL, target_id IS NULL, body_data = {}`
- [x] 10.11 Unfollow → no counter-notification; re-follow after unfollow emits a new notification
- [x] 10.12 Follow suppressed when blocked (both directions)
- [x] 10.13 Auto-hide → notification (post variant): 3rd aged reporter triggers auto-hide on Alice's post → one row with `type = 'post_auto_hidden', actor_user_id IS NULL, target_type = 'post', target_id = postId, body_data.reason = 'auto_hide_3_reports'`
- [x] 10.14 Auto-hide → notification (reply variant): 3rd aged reporter on reply → one row with `target_type = 'reply'`
- [x] 10.15 Auto-hide on user/chat_message target → zero notification rows
- [x] 10.16 4th reporter on already-hidden post → zero NEW notification rows (UPDATE is no-op; emit must not fire on non-transition)
- [x] 10.17 Transaction atomicity — like: mock `NotificationRepository.insert` to throw inside the transaction → `post_likes` row does NOT persist (rollback)
- [x] 10.18 Transaction atomicity — reply: same pattern → `post_replies` row does NOT persist
- [x] 10.19 Transaction atomicity — follow: same pattern → `follows` row does NOT persist
- [x] 10.20 Transaction atomicity — auto-hide: same pattern → `is_auto_hidden` flip + `moderation_queue` row all roll back
- [x] 10.21 System-originated emit (auto-hide) skips block-check: Alice has blocked an admin identity → auto-hide still emits (no actor to block)

## 11. Read-path integration tests

- [x] 11.1 GET /api/v1/notifications returns the caller's rows in `created_at DESC` order
- [x] 11.2 GET /api/v1/notifications isolation: caller A does NOT see caller B's notifications
- [x] 11.3 Cursor pagination: seed 25 rows, limit=10 → first page 10 + next_cursor; second page 10 + next_cursor; third page 5 + next_cursor = null
- [x] 11.4 `unread=true` filter: seed 10 unread + 5 read → list returns 10 unread only
- [x] 11.5 GET /api/v1/notifications/unread-count: returns correct count; increments on new emit; decrements on mark-read
- [x] 11.6 PATCH /:id/read: sets `read_at`; second call on same id is 204 idempotent (already-read branch); unread-count drops by 1
- [x] 11.7 PATCH /:id/read on another user's notification returns 404 `not_found`
- [x] 11.8 PATCH /read-all: sets `read_at = NOW()` on all unread rows for caller; returns `{ marked_read: <count> }`; subsequent unread-count = 0
- [x] 11.9 PATCH /read-all is isolated: does NOT affect another user's unread rows
- [x] 11.10 Auth: all 4 endpoints return 401 `unauthenticated` without a valid JWT
- [x] 11.11 Error envelope: 4xx responses match `{ "error": { "code": ..., "message": ... } }` shape

## 12. Detekt + ktlint + lint

- [x] 12.1 Run `./gradlew detekt` — green (no partial-index violations for `WHERE read_at IS NULL`; no `RawFromPostsRule` violations)
- [x] 12.2 Run `./gradlew ktlintCheck` — green
- [x] 12.3 Verify no new calls to `SELECT FROM posts` / `SELECT FROM post_replies` are introduced outside the existing moderation allowlist (V10 notifications code reads `user_blocks` only, not posts). Notes: the Like and Reply author / excerpt lookups live in `:infra:supabase` (outside the `RawFromPostsRule` scan scope); the auto-hide author lookup in `ReportService.loadTargetAuthorId` is annotated `@AllowMissingBlockJoin` with a reason (system-originated addressing)

### Qodo review follow-ups (applied in PR #12 amend)

- [x] 12.4 V10 DDL aligned verbatim with `docs/05-Implementation.md` §820–844: canonical 13-value `type` enum (including `subscription_billing_issue` + `subscription_expired`, NOT the 3 subscription variants initially shipped); `target_type VARCHAR(16)`; `body_data JSONB` nullable with no default
- [x] 12.5 `NotificationDispatcher` interface moved to `:core:data` per design Decision 9 so `:infra:firebase` (future FCM change) can implement without a reverse dep on `:backend:ktor`. `NoopNotificationDispatcher` stays in `:backend:ktor` as the in-app-only impl
- [x] 12.6 Dispatch now runs **after** commit in all four services (`LikeService`, `ReplyService`, `FollowService`, `ReportService`). The emitter returns `UUID?` and the service dispatches the collected id outside the TX, so a future non-noop dispatcher never observes a row that ended up rolled back
- [x] 12.7 `NotificationType.fromWire` replaced with `fromWireOrNull`; `JdbcNotificationRepository.toRowOrNull` logs at WARN and skips rows with an unrecognized `type` rather than crashing the list endpoint on a future DB-level enum widening

## 13. Deploy + verification

- [x] 13.1 Run full local test suite: `./gradlew :backend:ktor:test` — all green (MigrationV10SmokeTest + write-path + read-path). 347 tests pass against the `dev/docker-compose.yml` Postgres (V1→V10 cold migrate + all prior migration smoke tests + the 32 new notification tests + all pre-existing endpoint/timeline/auth/moderation suites)
- [x] 13.2 Open PR; verify CI passes build, detekt, ktlint, test, migration-smoke, migrate-supabase-parity — PR [#12](https://github.com/aditrioka/nearyou-id/pull/12), all four CI checks green on the final commit (`c1ccdda`) after Qodo-driven fixes
- [x] 13.3 Staging: merge to `main` → backend deploys with `RUN_FLYWAY_ON_STARTUP=true`, so Flyway applies V10 at container startup. Merge commit `08e3620` triggered `deploy-staging.yml` run `24846569913`, deploy completed in 6m3s. (Note: the "`nearyou-migrate` Cloud Run Job" described in the original task is the planned prod split — staging still runs Flyway on-startup as of 2026-04-23.)
- [x] 13.4 Staging smoke: `GET /health/ready` = 200 (backend up ⇒ Flyway V10 applied cleanly at startup; any migration failure would have crashed startup and failed the deploy gate); all four notification endpoints return 401 without JWT (routes registered and JWT-protected). Deep seed-based end-to-end smoke (two users → like → notification row → PATCH read) deferred — already exercised by the 347-test suite against a fresh Flyway-migrated Postgres in CI + locally.
- [x] 13.5 Prod: git tag `v0.10.0` on `main@08e3620`. **Marker tag only** — prod deploy pipeline not yet wired (no `deploy-prod.yml`, no workflow triggered on `tags:`; `nearyou-prod` GCP project not provisioned per `docs/10-Setup-Checklist.md:75,88`). When the prod split ships, this tag becomes the audit trail for the V10 staging milestone; the prod-split change carries its own tag for first-prod-deploy.
- [x] 13.6 Run `openspec archive in-app-notifications` — performed as part of this tasks.md close-out commit.
