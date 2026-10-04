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
