## ADDED Requirements

### Requirement: Warning action issues a recorded, notified warning without changing moderation state

The system SHALL serve `POST /admin/users/{id}/warn` as an authenticated route wired INSIDE the `authenticate(ADMIN_AUTH_NAME)` block, role-gated to `owner`/`admin`/`moderator` (a `read_only` admin SHALL be rejected) and CSRF-gated (CSRF validated BEFORE the role check, per the established gate order). For an eligible target — a user that is NOT soft-deleted (`deleted_at IS NULL`) — the handler SHALL, in ONE database transaction: write exactly one immutable `admin_actions_log` row with `action_type = 'user_warned'` (attributed to the acting human admin, NEVER the `system` sentinel UUID; `before_state`/`after_state` record the warning issuance) AND insert exactly one sanitized notification reusing the existing `notifications.type = 'account_action_applied'` with `body_data.action_type = 'warning'`. The warning SHALL NOT mutate any `users` moderation column — `is_banned`, `suspended_until`, `is_shadow_banned`, and `token_version` are all left unchanged. On success the handler SHALL redirect (303, or `HX-Redirect` for HTMX) back to the user's profile/lookup view. The audit row and the notification SHALL commit-or-rollback together (atomic).

#### Scenario: Warning a user writes one audit row and one notification, with no state change

- **GIVEN** an authenticated `moderator`/`admin`/`owner` session (valid CSRF) AND a target user with `is_banned = FALSE`, `deleted_at IS NULL`
- **WHEN** the client sends `POST /admin/users/{id}/warn`
- **THEN** exactly one `admin_actions_log` row with `action_type = 'user_warned'` SHALL be written AND exactly one `notifications` row with `type = 'account_action_applied'` and `body_data.action_type = 'warning'` SHALL be inserted for the target user
- **AND** the target user's `is_banned`, `suspended_until`, `is_shadow_banned`, and `token_version` SHALL be unchanged

#### Scenario: The warning audit row is attributed to the acting human admin

- **GIVEN** an authenticated admin session for a specific `admin_users` row (valid CSRF) AND an eligible target
- **WHEN** the client sends `POST /admin/users/{id}/warn`
- **THEN** the resulting `admin_actions_log` row's `admin_id` SHALL be the acting admin's id, NOT the `system` sentinel UUID

#### Scenario: The warning transaction is atomic

- **GIVEN** an authenticated authorized session (valid CSRF) AND an eligible target AND an injected failure on the notification insert
- **WHEN** the client sends `POST /admin/users/{id}/warn`
- **THEN** the whole transaction SHALL roll back: no `admin_actions_log` `user_warned` row SHALL persist AND no `notifications` row SHALL persist

### Requirement: The warning reason is audit-only and never echoed to the warned user

A free-text reason supplied with the warning SHALL be stored in the `admin_actions_log` row only. It SHALL NEVER be placed in the notification `body_data` nor otherwise echoed to the warned user — the user-facing notification carries the fixed `body_data.action_type = 'warning'` and no admin free-text (the same discipline as the suspend action's fixed reason code).

#### Scenario: A free-text warning reason is not surfaced to the user

- **GIVEN** an authenticated authorized session (valid CSRF) AND an eligible target
- **WHEN** the client sends `POST /admin/users/{id}/warn` with a `reason` form field containing distinctive text
- **THEN** that distinctive text SHALL appear in the `admin_actions_log` row's `reason` (audit-only) AND SHALL NOT appear anywhere in the inserted `notifications` row's `body_data`

### Requirement: The warning action gates on session, CSRF, and write role, and handles a malformed id safely

`POST /admin/users/{id}/warn` SHALL apply the gate order session → CSRF → write-role → parse `{id}`. An unauthenticated request SHALL redirect to `/admin/login` (302) with no write. A request with a missing or invalid CSRF token SHALL be rejected (403) with an `admin_csrf_violation` audit entry and no warning write — and the CSRF check SHALL precede the role check (a `read_only` admin with a bad CSRF token gets the CSRF rejection). A `read_only` admin with a valid CSRF token SHALL be role-rejected with no write. A `{id}` that does not parse as a UUID SHALL be rejected with a 4xx / inline error (never a 500), mutating nothing and writing no `admin_actions_log` row.

#### Scenario: Unauthenticated warn is redirected with no write

- **WHEN** a client sends `POST /admin/users/{id}/warn` with no valid admin session
- **THEN** the response status SHALL be 302 to `/admin/login` AND no `admin_actions_log` row SHALL be written

#### Scenario: Missing CSRF token is rejected before the role check

- **GIVEN** an authenticated `read_only` admin session
- **WHEN** the client sends `POST /admin/users/{id}/warn` with a missing/invalid CSRF token
- **THEN** the response status SHALL be 403 (CSRF rejection, not a role rejection) AND an `admin_csrf_violation` entry SHALL be recorded AND no `user_warned` row SHALL be written

#### Scenario: Read-only role with valid CSRF is role-rejected

- **GIVEN** an authenticated `read_only` admin session with a valid CSRF token
- **WHEN** the client sends `POST /admin/users/{id}/warn`
- **THEN** the request SHALL be role-rejected AND no `user_warned` row SHALL be written

#### Scenario: Malformed id on the warn route is rejected without a 500 or write

- **GIVEN** an authenticated authorized session with a valid CSRF token
- **WHEN** the client sends `POST /admin/users/not-a-uuid/warn`
- **THEN** the response status SHALL NOT be 500 (it SHALL be a 4xx / inline error) AND no `users` row SHALL be mutated AND no `admin_actions_log` row SHALL be written

### Requirement: The destructive account-state actions enforce the per-admin destructive-action cap

The destructive handlers on this capability — **suspend** (`POST /admin/users/{id}/suspend`) and **warn** (`POST /admin/users/{id}/warn`) — SHALL enforce `admin-destructive-action-rate-limit` before mutating: when the acting admin is at or over the cap, the action SHALL be rejected with an inline "quota exceeded" state, mutating nothing and writing no `admin_actions_log` row (per that capability). The restorative **unban** action (`POST /admin/users/{id}/unban`) is NOT destructive and SHALL NOT be gated by the cap.

#### Scenario: Suspend beyond the cap is rejected without effect

- **GIVEN** an authenticated write-role admin at the destructive-action cap (20 in the trailing hour) AND an eligible target user
- **WHEN** the client sends `POST /admin/users/{id}/suspend`
- **THEN** the response SHALL surface a "quota exceeded" state (not a 5xx) AND the target user's `is_banned` / `suspended_until` SHALL be unchanged AND no new `admin_actions_log` row SHALL be written

#### Scenario: Unban is allowed even at the cap

- **GIVEN** an authenticated write-role admin at the destructive-action cap AND a target user with an active suspension
- **WHEN** the client sends `POST /admin/users/{id}/unban`
- **THEN** the unban SHALL apply normally (the restorative action is not gated by the destructive cap)
