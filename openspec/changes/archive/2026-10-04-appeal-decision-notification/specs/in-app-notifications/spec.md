## ADDED Requirements

### Requirement: notifications.type enum extended with `appeal_decided` (V40)

Migration `V40__notifications_type_appeal_decided.sql` SHALL extend the `notifications.type` CHECK enum with the value `appeal_decided`, raising the allowed set to 14 values: `post_liked`, `post_replied`, `followed`, `chat_message`, `subscription_billing_issue`, `subscription_expired`, `post_auto_hidden`, `account_action_applied`, `data_export_ready`, `chat_message_redacted`, `privacy_flip_warning`, `username_release_scheduled`, `apple_relay_email_changed`, `appeal_decided`. Because the V10 enum is an inline column CHECK (auto-named `notifications_type_check`), the migration SHALL perform one `ALTER TABLE notifications DROP CONSTRAINT IF EXISTS notifications_type_check, ADD CONSTRAINT notifications_type_check CHECK (type IN ( … 14 values … ))` — atomic, so the constraint is never absent to a concurrent session, and re-runnable. The extension is additive: existing rows are untouched and all 13 previously-valid values continue to pass, so the V10 scenarios remain valid. The `NotificationType` enum (`core/data`) SHALL carry the matching `APPEAL_DECIDED("appeal_decided")` value so the list read path (which filters `type IN (NotificationType.entries)`) returns the new rows.

#### Scenario: appeal_decided insert succeeds after V40
- **WHEN** an INSERT supplies `type = 'appeal_decided'` against a DB migrated to V40
- **THEN** the INSERT succeeds

#### Scenario: All fourteen type values are accepted after V40
- **WHEN** an INSERT supplies `type` from any of the fourteen values
- **THEN** the INSERT succeeds

#### Scenario: Out-of-enum type still rejected after V40
- **WHEN** an INSERT supplies `type = 'post_shared'` against a DB migrated to V40
- **THEN** the INSERT fails with SQLSTATE `23514` (the extension added only `appeal_decided`)

#### Scenario: Pre-V40 rows remain valid
- **WHEN** the V40 constraint swap runs against a DB that already holds `notifications` rows with any of the thirteen original type values
- **THEN** the swap succeeds AND all existing rows remain present and valid (no data rewrite)

### Requirement: appeal_decided emit site and body_data shape

The `appeal_decided` notification type SHALL be written by the `admin-appeal-review` capability when an admin applies an appeal decision (approve or reject). Each row SHALL have:

- `type = 'appeal_decided'`
- `actor_user_id = NULL` (system-originated; the deciding admin is not a `public.users` row)
- `target_type = 'appeal'`, `target_id = <decided appeal id>` (the canonical `(target_type, target_id)` deep-link addressing pair)
- `body_data = {"decision": "approved" | "rejected"}` — **exactly one key**

`body_data` SHALL NOT carry the free-text `decision_reason` (the appellant reads it through the own-appeal-status read), the appealed `action_type`, any admin identity, or the appeal id (it is the row's `target_id` — the catalog's "do not duplicate `target_id` inside `body_data`" rule). The write SHALL use the shipped admin notification pattern (a raw in-transaction `INSERT`, as for `account_action_applied` and `chat_message_redacted`) — NOT `NotificationEmitter` — so no block-suppression or shadow-ban actor-masking applies. Delivery is **in-app feed only**: no FCM push is dispatched for `appeal_decided`, and `PushCopy` carries no `appeal_decided` case. The row SHALL be returned by `GET /api/v1/notifications` and counted by `GET /api/v1/notifications/unread-count` like any other type.

#### Scenario: appeal_decided body_data shape
- **WHEN** an `appeal_decided` notification is written for the approval of appeal `<P>`
- **THEN** the row has `type = 'appeal_decided'`, `actor_user_id = NULL`, `target_type = 'appeal'`, `target_id = <P>` AND `body_data` is exactly `{"decision": "approved"}` (one key — no `decision_reason`, no `action_type`, no appeal id, no admin identity)

#### Scenario: appeal_decided row is returned by the list endpoint
- **GIVEN** user A has an unread `appeal_decided` notification
- **WHEN** A calls `GET /api/v1/notifications`
- **THEN** the response includes the row with `type = "appeal_decided"`, `target_type = "appeal"`, and `body_data.decision` AND `GET /api/v1/notifications/unread-count` counts it

#### Scenario: appeal_decided is not FCM-pushed
- **WHEN** an `appeal_decided` notification is written
- **THEN** it appears in the recipient's in-app `GET /api/v1/notifications` feed AND no FCM push is dispatched for it (matching the shipped admin `account_action_applied` / `chat_message_redacted` behavior)

## MODIFIED Requirements

### Requirement: notifications table created via Flyway V10

A migration `V10__notifications.sql` SHALL create the `notifications` table verbatim-aligned with `docs/05-Implementation.md` §820–844 with columns:
- `id UUID PRIMARY KEY DEFAULT gen_random_uuid()`
- `user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE`
- `type VARCHAR(48) NOT NULL CHECK (type IN ('post_liked', 'post_replied', 'followed', 'chat_message', 'subscription_billing_issue', 'subscription_expired', 'post_auto_hidden', 'account_action_applied', 'data_export_ready', 'chat_message_redacted', 'privacy_flip_warning', 'username_release_scheduled', 'apple_relay_email_changed'))` — all 13 values of the V10 catalog. V40 later widens this CHECK to 14 values with `appeal_decided` (see § "notifications.type enum extended with `appeal_decided` (V40)"); the per-type emit sites and `body_data` shapes are defined in their own requirements below and in `docs/05-Implementation.md` § Notifications Schema.
- `actor_user_id UUID REFERENCES users(id) ON DELETE SET NULL` (nullable)
- `target_type VARCHAR(16)` (nullable)
- `target_id UUID` (nullable)
- `body_data JSONB` (nullable)
- `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`
- `read_at TIMESTAMPTZ` (nullable)

Plus two indexes:
- `notifications_user_unread_idx ON notifications (user_id, created_at DESC) WHERE read_at IS NULL` — partial; `read_at IS NULL` predicate is immutable (unlike `> NOW()` predicates which PostgreSQL rejects; see the partial-index `NOW()` CI lint rule in `docs/08-Roadmap-Risk.md` § Development Tools)
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
