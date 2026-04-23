## ADDED Requirements

### Requirement: notifications table created via Flyway V10

A migration `V10__notifications.sql` SHALL create the `notifications` table verbatim-aligned with `docs/05-Implementation.md` §820–844 with columns:
- `id UUID PRIMARY KEY DEFAULT gen_random_uuid()`
- `user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE`
- `type VARCHAR(48) NOT NULL CHECK (type IN ('post_liked', 'post_replied', 'followed', 'chat_message', 'subscription_billing_issue', 'subscription_expired', 'post_auto_hidden', 'account_action_applied', 'data_export_ready', 'chat_message_redacted', 'privacy_flip_warning', 'username_release_scheduled', 'apple_relay_email_changed'))` — all 13 values per the canonical catalog even though V10 only writes 4
- `actor_user_id UUID REFERENCES users(id) ON DELETE SET NULL` (nullable)
- `target_type VARCHAR(16)` (nullable)
- `target_id UUID` (nullable)
- `body_data JSONB` (nullable)
- `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`
- `read_at TIMESTAMPTZ` (nullable)

Plus two indexes:
- `notifications_user_unread_idx ON notifications (user_id, created_at DESC) WHERE read_at IS NULL` — partial; `read_at IS NULL` predicate is immutable (unlike `> NOW()` predicates which PostgreSQL rejects; see `docs/08-Roadmap-Risk.md:486`)
- `notifications_user_all_idx ON notifications (user_id, created_at DESC)` — full; backs the default "all notifications" list endpoint

The `user_id` FK MUST use `ON DELETE CASCADE` (recipient's per-user feed is PII about them; wiped on hard-delete with the tombstone worker's treatment of other per-user rows). The `actor_user_id` FK MUST use `ON DELETE SET NULL` (actor churn preserves the recipient's historical feed; render layer shows "a deleted user" for null actors).

#### Scenario: Migration runs cleanly from V9
- **WHEN** Flyway runs `V10__notifications.sql` against a DB at V9
- **THEN** the migration succeeds AND `flyway_schema_history` records V10

#### Scenario: All canonical columns present
- **WHEN** querying `information_schema.columns WHERE table_name = 'notifications'`
- **THEN** every column above is present with its documented type, nullability, and default

#### Scenario: type CHECK enum accepts all 13 values
- **WHEN** an INSERT supplies any of the 13 documented `type` values (`post_liked`, `post_replied`, `followed`, `chat_message`, `subscription_billing_issue`, `subscription_expired`, `post_auto_hidden`, `account_action_applied`, `data_export_ready`, `chat_message_redacted`, `privacy_flip_warning`, `username_release_scheduled`, `apple_relay_email_changed`)
- **THEN** the INSERT succeeds for each value

#### Scenario: type CHECK enum rejects out-of-enum value
- **WHEN** an INSERT supplies `type = 'post_shared'`
- **THEN** the INSERT fails with SQLSTATE `23514` (check-constraint violation)

#### Scenario: Both indexes exist
- **WHEN** querying `pg_indexes WHERE tablename = 'notifications'`
- **THEN** the result contains `notifications_user_unread_idx` AND `notifications_user_all_idx`

#### Scenario: Partial index has the immutable WHERE predicate
- **WHEN** querying `pg_indexes` for `notifications_user_unread_idx`
- **THEN** the index definition contains `WHERE read_at IS NULL` (or equivalent `WHERE (read_at IS NULL)`)

#### Scenario: user_id CASCADE removes notifications on recipient hard-delete
- **WHEN** a `users` row is hard-deleted AND it is referenced as `notifications.user_id` by N rows
- **THEN** all N `notifications` rows are cascade-deleted in the same transaction

#### Scenario: actor_user_id SET NULL on actor hard-delete preserves recipient feed
- **WHEN** a `users` row is hard-deleted AND it is referenced as `notifications.actor_user_id` by N rows on OTHER users' feeds
- **THEN** all N rows persist with `actor_user_id = NULL` (they are NOT deleted; the recipient's feed remains intact)

### Requirement: NotificationEmitter write-path with block-suppression and self-action suppression

A `NotificationEmitter` component SHALL encapsulate the write path for all notification types. Each emit call MUST:
1. Short-circuit with `suppressed_reason = "self_action"` when `actor_user_id = recipient_user_id` (self-actions never produce a notification).
2. Query `user_blocks` for bidirectional block: `SELECT 1 FROM user_blocks WHERE (blocker_id = :recipient AND blocked_id = :actor) OR (blocker_id = :actor AND blocked_id = :recipient) LIMIT 1`. If a row matches, short-circuit with `suppressed_reason = "blocked"`. System-originated notifications (actor_user_id IS NULL) skip this check.
3. Otherwise, `INSERT INTO notifications (user_id, type, actor_user_id, target_type, target_id, body_data)` in the caller's existing DB transaction.

The emitter MUST be constructor-injectable into the four V10-wired writer services (`LikeService`, `ReplyService`, `FollowService`, `ReportService`) via Koin. The block-check and INSERT MUST execute in the same DB transaction as the caller's primary write.

#### Scenario: Self-action suppression (like own post)
- **WHEN** user Alice likes her own post
- **THEN** zero rows are inserted into `notifications` AND the emitter logs `suppressed_reason = "self_action"` at DEBUG

#### Scenario: Block-suppression (Alice blocked Bob, Bob likes Alice's post)
- **WHEN** Alice has a `user_blocks` row `(blocker_id = Alice, blocked_id = Bob)` AND Bob likes Alice's post
- **THEN** zero `notifications` rows are inserted for Alice AND the emitter logs `suppressed_reason = "blocked"` at DEBUG

#### Scenario: Block-suppression (Bob blocked Alice, Bob likes Alice's post)
- **WHEN** Bob has a `user_blocks` row `(blocker_id = Bob, blocked_id = Alice)` AND Bob likes Alice's post (Alice's post is still visible via the reporter / direct-link path)
- **THEN** zero `notifications` rows are inserted for Alice (bidirectional suppression)

#### Scenario: Normal emit (no block, not self)
- **WHEN** Bob likes Alice's post AND no `user_blocks` row exists between Alice and Bob
- **THEN** exactly one `notifications` row is inserted for Alice with `type = 'post_liked'`, `actor_user_id = Bob`

#### Scenario: System-originated emit skips block-check
- **WHEN** an emit occurs with `actor_user_id = NULL` (e.g. `post_auto_hidden`, `privacy_flip_warning`)
- **THEN** the `user_blocks` query is NOT issued AND the INSERT proceeds unconditionally (no user to block)

#### Scenario: Emit failure rolls back primary write
- **WHEN** a primary write (like INSERT) succeeds AND the notification INSERT fails (e.g. recipient user hard-deleted between validation and emit)
- **THEN** the encompassing transaction rolls back AND no `post_likes` row persists

### Requirement: GET /api/v1/notifications paginated list

A Ktor route SHALL be registered at `GET /api/v1/notifications` requiring Bearer JWT. Query parameters:
- `cursor` (optional, ISO8601 timestamp) — if absent, return newest rows; if present, return rows `WHERE created_at < :cursor`.
- `limit` (optional int, 1..50, default 20) — out-of-range rejected with 400 `invalid_request`.
- `unread_only` (optional bool, default false) — when true, additionally filter `read_at IS NULL`.

The handler MUST execute a single SELECT against `notifications` scoped `WHERE user_id = :caller [AND read_at IS NULL] [AND created_at < :cursor] ORDER BY created_at DESC LIMIT :limit`. The response body SHALL be `{items: NotificationDto[], next_cursor: string | null}` where `next_cursor` is the `created_at` of the oldest returned item if the page is full (length == limit), else `null`.

`NotificationDto` fields: `id`, `type`, `actor_user_id` (nullable), `target_type` (nullable), `target_id` (nullable), `body_data` (JSON object, nullable), `created_at`, `read_at` (nullable). The response MUST NOT include rows belonging to other users (per-caller ownership filter is mandatory).

#### Scenario: Unauthenticated rejected
- **WHEN** `GET /api/v1/notifications` is called with no `Authorization` header
- **THEN** the response is HTTP 401 with `error.code = "unauthenticated"`

#### Scenario: First page (no cursor) returns newest rows
- **WHEN** Alice has 30 notifications AND `GET /api/v1/notifications?limit=20` is called
- **THEN** the response contains 20 items ordered `created_at DESC` AND `next_cursor` equals the `created_at` of the 20th item

#### Scenario: Cursor pagination returns older rows
- **WHEN** Alice passes the `next_cursor` from the first page back as `?cursor=<iso8601>`
- **THEN** the response contains the next older 10 items AND `next_cursor = null` (page not full)

#### Scenario: unread_only filter uses partial index
- **WHEN** Alice has 50 notifications of which 3 are unread AND `GET /api/v1/notifications?unread_only=true` is called
- **THEN** the response contains exactly 3 items AND the query plan (EXPLAIN) shows `notifications_user_unread_idx` in use

#### Scenario: Per-caller ownership filter
- **WHEN** Alice has 10 notifications AND Bob has 5 notifications AND Alice calls `GET /api/v1/notifications?limit=50`
- **THEN** the response contains exactly Alice's 10 items AND none of Bob's

#### Scenario: limit out of range
- **WHEN** `?limit=51` is supplied
- **THEN** the response is HTTP 400 with `error.code = "invalid_request"`

#### Scenario: malformed cursor
- **WHEN** `?cursor=not-a-timestamp` is supplied
- **THEN** the response is HTTP 400 with `error.code = "invalid_request"`

### Requirement: GET /api/v1/notifications/unread-count

A Ktor route SHALL be registered at `GET /api/v1/notifications/unread-count` requiring Bearer JWT. The handler SHALL execute `SELECT COUNT(*) FROM notifications WHERE user_id = :caller AND read_at IS NULL` and return `{unread_count: <int>}`. The query MUST be served by the `notifications_user_unread_idx` partial index (index-only scan).

#### Scenario: Unauthenticated rejected
- **WHEN** called without Authorization
- **THEN** HTTP 401 `unauthenticated`

#### Scenario: Accurate unread count
- **WHEN** Alice has 10 notifications of which exactly 3 have `read_at IS NULL`
- **THEN** the response is `{unread_count: 3}`

#### Scenario: Zero unread returns 0 (not 404)
- **WHEN** Alice has 10 notifications all with `read_at` set
- **THEN** the response is `{unread_count: 0}` with HTTP 200

#### Scenario: Query uses partial index
- **WHEN** inspecting the query plan for the unread-count handler
- **THEN** the plan shows `notifications_user_unread_idx` as the scan method (not a sequential scan or the full `_all_idx`)

### Requirement: PATCH /api/v1/notifications/:id/read marks a single notification read

A Ktor route SHALL be registered at `PATCH /api/v1/notifications/:id/read` requiring Bearer JWT. The handler SHALL execute `UPDATE notifications SET read_at = NOW() WHERE id = :id AND user_id = :caller AND read_at IS NULL` and return HTTP 200 no body on exactly 1 row affected, HTTP 404 `notification_not_found` on 0 rows affected (not found, not owned, or already read — all three collapse to 404 to avoid leaking ownership).

#### Scenario: Unauthenticated rejected
- **WHEN** called without Authorization
- **THEN** HTTP 401 `unauthenticated`

#### Scenario: Mark unread notification read
- **WHEN** Alice owns notification `N` with `read_at IS NULL` AND calls `PATCH /notifications/N/read`
- **THEN** HTTP 200 AND the row has `read_at` set to approximately NOW()

#### Scenario: Already-read notification returns 404
- **WHEN** Alice owns notification `N` with `read_at` already set AND calls `PATCH /notifications/N/read`
- **THEN** HTTP 404 `notification_not_found` (idempotent-looking; no state change)

#### Scenario: Other user's notification returns 404 (not 403)
- **WHEN** Bob owns notification `N` AND Alice calls `PATCH /notifications/N/read`
- **THEN** HTTP 404 `notification_not_found` (ownership not leaked via distinct status)

#### Scenario: Non-existent id returns 404
- **WHEN** no row has `id = :id` AND Alice calls the endpoint
- **THEN** HTTP 404 `notification_not_found`

### Requirement: PATCH /api/v1/notifications/read-all marks all caller notifications read

A Ktor route SHALL be registered at `PATCH /api/v1/notifications/read-all` requiring Bearer JWT. The handler SHALL execute `UPDATE notifications SET read_at = NOW() WHERE user_id = :caller AND read_at IS NULL` and return `{marked: <int>}` with HTTP 200 (including `marked: 0` when already all-read).

#### Scenario: Unauthenticated rejected
- **WHEN** called without Authorization
- **THEN** HTTP 401 `unauthenticated`

#### Scenario: Marks all unread for caller
- **WHEN** Alice has 5 unread AND 10 already-read notifications AND calls `PATCH /notifications/read-all`
- **THEN** HTTP 200 with `{marked: 5}` AND all 15 rows now have `read_at` set

#### Scenario: Idempotent when already all-read
- **WHEN** Alice has 0 unread notifications AND calls the endpoint
- **THEN** HTTP 200 with `{marked: 0}`

#### Scenario: Does NOT touch other users' rows
- **WHEN** Alice has 3 unread AND Bob has 2 unread AND Alice calls the endpoint
- **THEN** Alice's 3 flip to read AND Bob's 2 remain unread

### Requirement: NotificationDispatcher seam for future FCM

An interface `NotificationDispatcher` SHALL be defined in `:core:data` with a single method `fun dispatch(notification: NotificationDto)`. V10 SHALL ship one implementation (`InAppOnlyDispatcher`) that is a no-op (logs at INFO, does not push anywhere). `NotificationService.emit()` SHALL call `dispatch()` after the DB commit succeeds. The FCM push dispatch change SHALL add a new implementation (or composite) without modifying `NotificationService` or any emitter.

#### Scenario: Interface lives in :core:data
- **WHEN** inspecting the `:core:data` module sources
- **THEN** `NotificationDispatcher` interface is present AND has no vendor imports

#### Scenario: In-app-only implementation is a no-op
- **WHEN** `InAppOnlyDispatcher.dispatch(...)` is called
- **THEN** a log line at INFO is emitted AND no push / network call is made

#### Scenario: Dispatcher called after commit
- **WHEN** `NotificationService.emit(...)` completes its DB transaction successfully
- **THEN** `NotificationDispatcher.dispatch(...)` is invoked exactly once with the emitted notification DTO

#### Scenario: Dispatcher NOT called on emit suppression
- **WHEN** `NotificationEmitter` short-circuits with `suppressed_reason = "self_action" | "blocked"`
- **THEN** `NotificationDispatcher.dispatch(...)` is NOT invoked (no row to dispatch)

#### Scenario: Dispatcher NOT called on primary-write rollback
- **WHEN** the primary write transaction rolls back (e.g. notification INSERT fails)
- **THEN** `NotificationDispatcher.dispatch(...)` is NOT invoked

### Requirement: body_data shape per emitted type

For the four types V10 writes, `body_data` SHALL be a JSONB object with the following shapes:
- `post_liked`: `{"post_excerpt": <string, ≤ 80 code points>}`
- `post_replied`: `{"reply_id": <UUID string>, "reply_excerpt": <string, ≤ 80 code points>}`
- `followed`: `{}`
- `post_auto_hidden`: `{"reason": "auto_hide_3_reports"}`

Excerpts SHALL be the first 80 code points of the source content (post content for `post_liked`, reply content for `post_replied`), taken at emit time. The excerpt MUST NOT be regenerated on read; subsequent edits to the source content do NOT update the already-written `body_data`.

#### Scenario: post_liked body_data shape
- **WHEN** Bob likes Alice's 180-char post
- **THEN** the `notifications.body_data` JSONB has exactly one key `post_excerpt` whose value is the first 80 code points of the post content

#### Scenario: post_replied body_data shape
- **WHEN** Bob replies "ayo ketemu" to Alice's post
- **THEN** the `body_data` JSONB has exactly two keys `reply_id` (UUID) AND `reply_excerpt` (string, the reply content since it's ≤ 80)

#### Scenario: followed body_data shape
- **WHEN** Bob follows Alice
- **THEN** the `body_data` JSONB is the empty object `{}`

#### Scenario: post_auto_hidden body_data shape
- **WHEN** the V9 auto-hide path flips a post via the 3-reporter threshold
- **THEN** the `body_data` JSONB has exactly one key `reason` with value `"auto_hide_3_reports"`

#### Scenario: Excerpt frozen at emit (source edit does not mutate notification)
- **WHEN** Bob likes Alice's post (excerpt captured) AND Alice later edits the post via the Premium edit feature
- **THEN** the `notifications.body_data.post_excerpt` on the already-written row is unchanged

### Requirement: 90-day retention documented (enforcement deferred)

The V10 migration SHALL include a `COMMENT ON TABLE notifications IS 'Per-user notification feed; 90-day retention policy; purge worker lands in the Phase 3.5 admin-panel change.'` The admin-panel worker change will implement the DELETE. V10 itself does NOT include a purge worker.

#### Scenario: Retention comment present after V10
- **WHEN** querying `obj_description('public.notifications'::regclass, 'pg_class')`
- **THEN** the returned comment contains both the phrases `90-day` AND `purge`
