## ADDED Requirements

### Requirement: A per-admin destructive-action cap of 20 per trailing hour

The system SHALL enforce a cap of **20 destructive admin actions per acting admin per trailing one-hour window**. The **destructive set** is the user-punitive account-state actions: a **warning** (`admin_actions_log.action_type = 'user_warned'`), a **suspend** (`'user_suspended'`), a **permanent ban** via report-queue resolution, and a **shadow ban** via report-queue resolution. Restorative and non-punitive actions are NOT in the destructive set and SHALL NOT be counted or capped: **unban**, content **keep**/**hide**, report **decision** bookkeeping (`actioned`/`dismissed`), and any read/login action. The count SHALL be sourced from `admin_actions_log` (the immutable audit trail is the rate-limit ledger): rows for the acting `admin_id` with `created_at > NOW() - INTERVAL '1 hour'` whose action identity is in the destructive set, where the report-queue destructive resolutions (which all log `action_type = 'moderation_queue_resolved'`) are isolated by `after_state ->> 'resolution' IN ('suspend_author_7d', 'ban_author', 'shadow_ban_author')`.

#### Scenario: The count includes the in-window destructive actions for the acting admin

- **GIVEN** an admin who has 3 `user_suspended`, 2 `user_warned`, and 1 `moderation_queue_resolved` (`resolution = ban_author`) rows in `admin_actions_log` within the last hour
- **WHEN** that admin's destructive-action count is computed
- **THEN** the count SHALL be 6

#### Scenario: Non-destructive and out-of-window actions are excluded

- **GIVEN** an admin with, in the last hour, 1 `user_unbanned` row, 1 `moderation_queue_resolved` (`resolution = hide`) row, 1 `report_resolved` row, AND 5 `user_suspended` rows older than one hour
- **WHEN** that admin's destructive-action count is computed
- **THEN** the count SHALL be 0 (unban, content-hide, and report bookkeeping are not destructive; the 5 suspends fall outside the trailing-hour window)

### Requirement: A destructive action at or over the cap is rejected with no mutation and no audit row

When the acting admin's destructive-action count is already `>= 20`, a further destructive action SHALL be rejected: the system SHALL NOT mutate any `users` / content / `moderation_queue` / `reports` row for that attempt AND SHALL NOT write any `admin_actions_log` row for the rejected attempt. The rejection SHALL be surfaced to the admin as an inline "quota exceeded" message (HTMX fragment or re-rendered page), not a 5xx. A destructive action while the count is `< 20` SHALL proceed normally and (because it writes its own `admin_actions_log` row) advance the count by one.

#### Scenario: The 21st destructive action in an hour is rejected without effect

- **GIVEN** an admin with exactly 20 destructive-action rows in the trailing hour AND an eligible target user
- **WHEN** that admin attempts a 21st destructive action (e.g. `POST /admin/users/{id}/warn` with a valid CSRF token and write role)
- **THEN** the response SHALL surface a "quota exceeded" state (not a 5xx) AND no `users` row SHALL be mutated AND no new `admin_actions_log` row SHALL be written (the count stays 20)

#### Scenario: A destructive action under the cap proceeds and advances the count

- **GIVEN** an admin with 19 destructive-action rows in the trailing hour AND an eligible target
- **WHEN** that admin performs a destructive action (e.g. a warning)
- **THEN** the action SHALL apply normally AND exactly one new `admin_actions_log` row SHALL be written (bringing the trailing-hour destructive count to 20)

### Requirement: The cap is per-admin and does not apply to non-destructive actions

The cap SHALL be scoped to the acting admin — one admin reaching the cap SHALL NOT block a different admin. A non-destructive action (unban, content keep/hide, report decision bookkeeping) SHALL never be rejected by this cap, regardless of the acting admin's destructive-action count.

#### Scenario: A second admin is unaffected by the first admin's exhausted quota

- **GIVEN** admin A with 20 destructive-action rows in the trailing hour AND admin B with 0
- **WHEN** admin B performs a destructive action against an eligible target
- **THEN** admin B's action SHALL apply normally (admin A's exhausted quota does not block admin B)

#### Scenario: A non-destructive action is allowed even at the cap

- **GIVEN** an admin with 20 destructive-action rows in the trailing hour
- **WHEN** that admin performs a non-destructive action (e.g. `POST /admin/users/{id}/unban`, or a `moderation_queue` resolution of `keep`/`hide`)
- **THEN** the action SHALL apply normally (the cap does not gate non-destructive actions)

### Requirement: The destructive-action count is exposed for the quota chip

The system SHALL expose the acting admin's current trailing-hour destructive-action count so consuming surfaces (the user-management profile page) can render an informational quota chip ("N/20 this hour"). Reading the count SHALL be read-only — it SHALL NOT mutate any table.

#### Scenario: The exposed count matches the ledger

- **GIVEN** an admin with 14 destructive-action rows in the trailing hour
- **WHEN** the destructive-action count is read for the quota chip
- **THEN** the value SHALL be 14 AND reading it SHALL write no `admin_actions_log` row and mutate no table
