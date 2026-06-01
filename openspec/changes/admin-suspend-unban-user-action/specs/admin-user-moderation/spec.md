## ADDED Requirements

### Requirement: Authenticated GET /admin/users renders the user-moderation lookup surface

The system SHALL serve `GET /admin/users` as an authenticated route, wired INSIDE the `authenticate(ADMIN_AUTH_NAME)` block established by the `admin-login` capability, so the session middleware gates it. On a valid session it SHALL return HTTP 200 with an HTML page that extends the shared admin base layout (per `admin-panel-scaffold`) and renders a lookup form. When the request carries a `q` query parameter that resolves to a user (per the lookup requirement below), the page SHALL ALSO render that user's current moderation state (`is_banned`, `suspended_until`) AND the suspend + unban action controls. The route SHALL be read-only — serving it SHALL NOT write any `admin_actions_log` row and SHALL NOT mutate any table.

#### Scenario: Authenticated request with no query renders the lookup form

- **GIVEN** an authenticated admin session
- **WHEN** the client sends `GET /admin/users` with no `q` parameter, carrying the valid `__Host-admin_session` cookie
- **THEN** the response status SHALL be 200
- **AND** the response body SHALL contain the base-layout structural sections (header, nav, footer) AND a lookup form (an input named `q` and a submit control)

#### Scenario: Authenticated request with a resolving query shows the user's state and action controls

- **GIVEN** an authenticated admin session AND a user exists with `is_banned = FALSE`
- **WHEN** the client sends `GET /admin/users?q=<that-user-uuid>`
- **THEN** the response status SHALL be 200
- **AND** the rendered body SHALL display the user's current `is_banned` / `suspended_until` state
- **AND** the rendered body SHALL contain a suspend control posting to `/admin/users/<that-user-uuid>/suspend` AND an unban control posting to `/admin/users/<that-user-uuid>/unban`

#### Scenario: Unauthenticated request redirects to the login page

- **WHEN** a client sends `GET /admin/users` with no `__Host-admin_session` cookie (or an invalid / expired / revoked / idle-timed-out session)
- **THEN** the response status SHALL be 302
- **AND** the `Location` header SHALL be `/admin/login`
- **AND** no user-moderation content SHALL be served

#### Scenario: HTMX request returns only the result fragment

- **GIVEN** an authenticated admin session
- **WHEN** the client sends `GET /admin/users?q=<uuid>` with header `HX-Request: true`
- **THEN** the response status SHALL be 200
- **AND** the response body SHALL contain the swappable result fragment element
- **AND** the response body SHALL NOT contain the full-page `<html>` document wrapper or the base-layout header/footer (it is a fragment, not a full page)

### Requirement: User lookup resolves by UUID then exact username, lenient on miss

The system SHALL resolve the `q` lookup parameter by first attempting to parse it as a user UUID and selecting that user by primary key; if `q` is not a valid UUID, the system SHALL attempt an EXACT (not fuzzy / prefix) `users.username` match. All lookups SHALL use parameterized JDBC queries — never string-interpolated SQL. A `q` that resolves to no user SHALL render an inline empty-state ("no matching user") with HTTP 200 — never a 404 or 500. Reads of `users` in the admin module are permitted (the admin module is exempt from the `visible_*`-view lint).

#### Scenario: Lookup by UUID returns the matching user

- **GIVEN** an authenticated session AND a user with a known UUID
- **WHEN** the client sends `GET /admin/users?q=<that-uuid>`
- **THEN** the response status SHALL be 200 AND the rendered body SHALL display that user's moderation state

#### Scenario: Lookup by exact username returns the matching user

- **GIVEN** an authenticated session AND a user with `username = 'budi_jakarta'`
- **WHEN** the client sends `GET /admin/users?q=budi_jakarta`
- **THEN** the response status SHALL be 200 AND the rendered body SHALL display that user's moderation state

#### Scenario: Non-resolving query renders an empty state, not a 404

- **GIVEN** an authenticated session AND no user matching the query
- **WHEN** the client sends `GET /admin/users?q=does-not-exist`
- **THEN** the response status SHALL be 200
- **AND** the rendered body SHALL contain an empty-state indicator ("no matching user") rather than an error page or a 404

#### Scenario: SQL-metacharacter query is treated as a literal

- **WHEN** an authenticated client sends `GET /admin/users?q=%27%3B+DROP+TABLE+users%3B--` (URL-encoded `'; DROP TABLE users;--`)
- **THEN** the response status SHALL be 200
- **AND** the `users` table SHALL still exist and be queryable afterward (the value matched zero users as a literal; no SQL was executed from it)

### Requirement: Suspend applies a server-fixed 7-day suspension to an eligible user

The system SHALL serve `POST /admin/users/{id}/suspend` (authenticated, role-gated, CSRF-gated per the requirements below). For an ELIGIBLE target — a user that is NOT soft-deleted (`deleted_at IS NULL`) AND NOT already permanently banned (`is_banned = TRUE AND suspended_until IS NULL`) — the handler SHALL set `is_banned = TRUE` AND `suspended_until = NOW() + INTERVAL '7 days'`. The 7-day window SHALL be computed server-side; the handler SHALL NOT accept a client-supplied suspension duration (any such request field SHALL be ignored). Re-suspending an already-time-bound-suspended user SHALL reset `suspended_until` to `NOW() + INTERVAL '7 days'` and SHALL capture the prior expiry in the audit `before_state`. On success the handler SHALL redirect (303, or `HX-Redirect` for HTMX) back to the user's lookup view.

#### Scenario: Active user is suspended for 7 days

- **GIVEN** an authenticated `moderator`/`admin`/`owner` session (valid CSRF) AND a target user with `is_banned = FALSE`, `deleted_at IS NULL`
- **WHEN** the client sends `POST /admin/users/{id}/suspend`
- **THEN** after the request the target user's row SHALL have `is_banned = TRUE` AND `suspended_until` ≈ `NOW() + INTERVAL '7 days'` (±10s clock tolerance)

#### Scenario: Suspension duration is server-fixed and ignores client-supplied duration

- **GIVEN** an authenticated authorized session (valid CSRF) AND an eligible target user
- **WHEN** the client sends `POST /admin/users/{id}/suspend` with a form field attempting to set a custom duration (e.g. `duration_days=3650`)
- **THEN** `suspended_until` SHALL be ≈ `NOW() + INTERVAL '7 days'` (±10s) — the client-supplied value SHALL be ignored

#### Scenario: Re-suspending an already-suspended user resets the 7-day clock

- **GIVEN** an authenticated authorized session (valid CSRF) AND a target user with `is_banned = TRUE`, `suspended_until = NOW() + INTERVAL '2 days'`
- **WHEN** the client sends `POST /admin/users/{id}/suspend`
- **THEN** `suspended_until` SHALL be reset to ≈ `NOW() + INTERVAL '7 days'` (±10s)
- **AND** the resulting `admin_actions_log` row's `before_state` SHALL record the prior `suspended_until` (≈ NOW() + 2 days)

### Requirement: Suspend is rejected for soft-deleted or permanently-banned targets

The system SHALL reject a suspend request whose target is soft-deleted (`deleted_at IS NOT NULL`) OR already permanently banned (`is_banned = TRUE AND suspended_until IS NULL`). On rejection the handler SHALL make NO state change to the `users` row, SHALL write NO `admin_actions_log` row, SHALL insert NO notification, and SHALL surface an informational message (no 500). Rejecting the permanent-ban case prevents the suspend control from silently DOWNGRADING a permanent ban to a 7-day window.

#### Scenario: Suspending a soft-deleted user is rejected with no state change

- **GIVEN** an authenticated authorized session (valid CSRF) AND a target user with `deleted_at = NOW() - INTERVAL '1 day'`
- **WHEN** the client sends `POST /admin/users/{id}/suspend`
- **THEN** the target user's `is_banned` / `suspended_until` SHALL be unchanged
- **AND** no new `admin_actions_log` row SHALL be written for the target
- **AND** no new `notifications` row SHALL be inserted for the target

#### Scenario: Suspending a permanently-banned user is rejected (no downgrade)

- **GIVEN** an authenticated authorized session (valid CSRF) AND a target user with `is_banned = TRUE` AND `suspended_until IS NULL` (permanent ban)
- **WHEN** the client sends `POST /admin/users/{id}/suspend`
- **THEN** the target user's row SHALL remain `is_banned = TRUE` AND `suspended_until IS NULL` (the permanent ban is NOT downgraded to a time-bound suspension)
- **AND** no new `admin_actions_log` row SHALL be written for the target

### Requirement: Manual unban clears the ban and suspension for a banned target

The system SHALL serve `POST /admin/users/{id}/unban` (authenticated, role-gated, CSRF-gated). For a target that is currently banned (`is_banned = TRUE`), the handler SHALL set `is_banned = FALSE` AND `suspended_until = NULL` — lifting BOTH a time-bound suspension AND a permanent ban (the admin's deliberate override). This is the same `(is_banned, suspended_until) → (FALSE, NULL)` transition the automated `suspension-unban-worker` performs on elapse, triggered early by a human. For a target that is NOT currently banned (`is_banned = FALSE`), the handler SHALL make no state change, SHALL write NO `admin_actions_log` row (the log records only actual transitions), and SHALL surface an informational "user is not banned" message.

#### Scenario: Time-bound-suspended user is unbanned

- **GIVEN** an authenticated authorized session (valid CSRF) AND a target user with `is_banned = TRUE`, `suspended_until = NOW() + INTERVAL '5 days'`
- **WHEN** the client sends `POST /admin/users/{id}/unban`
- **THEN** after the request the target user's row SHALL have `is_banned = FALSE` AND `suspended_until = NULL`

#### Scenario: Permanently-banned user is unbanned by admin override

- **GIVEN** an authenticated authorized session (valid CSRF) AND a target user with `is_banned = TRUE`, `suspended_until IS NULL` (permanent ban)
- **WHEN** the client sends `POST /admin/users/{id}/unban`
- **THEN** after the request the target user's row SHALL have `is_banned = FALSE` AND `suspended_until = NULL`

#### Scenario: Unbanning a not-currently-banned user is a no-op that writes no audit row

- **GIVEN** an authenticated authorized session (valid CSRF) AND a target user with `is_banned = FALSE`
- **WHEN** the client sends `POST /admin/users/{id}/unban`
- **THEN** the target user's row SHALL remain `is_banned = FALSE`
- **AND** no new `admin_actions_log` row SHALL be written for the target (the log records only actual state transitions)

### Requirement: State-changing actions are gated to owner / admin / moderator roles

The system SHALL restrict `POST /admin/users/{id}/suspend` and `POST /admin/users/{id}/unban` to admins whose `admin_users.role` is `owner`, `admin`, or `moderator`. A `read_only` admin SHALL be rejected with HTTP 403 (the admin is authenticated but unauthorized — NOT a redirect, which would falsely imply a session problem). On a `read_only` rejection the handler SHALL make no state change and SHALL write no `user_suspended` / `user_unbanned` audit row. The role check SHALL run after CSRF validation. (Contrast: `admin-actions-log-viewer` reads are available to ALL roles including `read_only`; this is the first role-gated admin WRITE.)

#### Scenario: read_only admin is forbidden from suspending

- **GIVEN** an authenticated session for an admin whose `role = 'read_only'` (with a valid CSRF token)
- **WHEN** the client sends `POST /admin/users/{id}/suspend`
- **THEN** the response status SHALL be 403
- **AND** the target user's `is_banned` / `suspended_until` SHALL be unchanged
- **AND** no `user_suspended` `admin_actions_log` row SHALL be written

#### Scenario: moderator admin is permitted to suspend

- **GIVEN** an authenticated session for an admin whose `role = 'moderator'` (with a valid CSRF token) AND an eligible target user
- **WHEN** the client sends `POST /admin/users/{id}/suspend`
- **THEN** the request SHALL be authorized (not 403) AND the target user SHALL be suspended per the suspend requirement

#### Scenario: read_only admin is forbidden from unbanning

- **GIVEN** an authenticated session for an admin whose `role = 'read_only'` (with a valid CSRF token) AND a banned target user
- **WHEN** the client sends `POST /admin/users/{id}/unban`
- **THEN** the response status SHALL be 403
- **AND** the target user SHALL remain banned AND no `user_unbanned` audit row SHALL be written

### Requirement: State-changing actions require a valid CSRF token

The system SHALL validate the per-session CSRF token on `POST /admin/users/{id}/suspend` and `POST /admin/users/{id}/unban` via the `admin-login` CSRF contract — each handler SHALL call the shared CSRF validation FIRST (before the role check and any state change), accepting the token from the `X-CSRF-Token` header or the `_csrf` form field. A missing or mismatched token SHALL return HTTP 403, write an `admin_csrf_violation` audit row (per `admin-login`), make no state change, and write no `user_suspended` / `user_unbanned` row.

#### Scenario: Suspend without a CSRF token is rejected

- **GIVEN** an authenticated authorized session
- **WHEN** the client sends `POST /admin/users/{id}/suspend` with no `X-CSRF-Token` header and no `_csrf` form field
- **THEN** the response status SHALL be 403
- **AND** the target user SHALL be unchanged AND no `user_suspended` audit row SHALL be written

#### Scenario: Suspend with a wrong CSRF token is rejected

- **GIVEN** an authenticated authorized session
- **WHEN** the client sends `POST /admin/users/{id}/suspend` with `X-CSRF-Token: <wrong-value>`
- **THEN** the response status SHALL be 403
- **AND** the target user SHALL be unchanged

#### Scenario: Suspend with a valid CSRF token proceeds

- **GIVEN** an authenticated authorized session with stored `csrf_token_hash = SHA-256(<plaintext-csrf>)` AND an eligible target user
- **WHEN** the client sends `POST /admin/users/{id}/suspend` with `X-CSRF-Token: <plaintext-csrf>`
- **THEN** the CSRF check SHALL pass and the target user SHALL be suspended per the suspend requirement

### Requirement: Every applied action writes one immutable audit row attributed to the acting human admin

For every action that changes state (a successful suspend, or a successful unban of a banned user), the system SHALL INSERT exactly one `admin_actions_log` row with: `admin_id` = the acting admin's own UUID (the `AdminPrincipal` from the session — NEVER the `system` sentinel UUID `54b53072-540e-3eb8-b8e9-343e71f28176`); `action_type` = `'user_suspended'` for suspend or `'user_unbanned'` for unban; `target_type` = `'user'`; `target_id` = the target user's UUID rendered as text; `reason` = the admin-entered reason (NULL-tolerant); `before_state` = `{"is_banned": <prior>, "suspended_until": <prior | null>}`; `after_state` = `{"is_banned": <new>, "suspended_until": <new | null>}`; `ip` from `call.clientIp` (the `client-ip-extraction` capability — never raw `X-Forwarded-For`); `user_agent` from the request header (NULL when absent). All values SHALL be written via parameterized placeholders.

#### Scenario: Suspend writes a user_suspended row with before/after state

- **WHEN** an active user (`is_banned = FALSE`) is suspended by an authorized admin
- **THEN** exactly one new `admin_actions_log` row SHALL exist with `action_type = 'user_suspended'`, `target_type = 'user'`, `target_id` = the user's UUID, `before_state->>'is_banned' = 'false'`, `after_state->>'is_banned' = 'true'`, AND `after_state->>'suspended_until'` non-null (≈ NOW() + 7 days)

#### Scenario: Unban writes a user_unbanned row with before/after state

- **WHEN** a banned user is unbanned by an authorized admin
- **THEN** exactly one new `admin_actions_log` row SHALL exist with `action_type = 'user_unbanned'`, `target_type = 'user'`, `target_id` = the user's UUID, `before_state->>'is_banned' = 'true'`, AND `after_state = {"is_banned": false, "suspended_until": null}`

#### Scenario: Audit row attributes to the human admin, never the system sentinel

- **WHEN** an authorized admin with UUID `<admin-uuid>` suspends or unbans a user
- **THEN** the resulting `admin_actions_log` row's `admin_id` SHALL equal `<admin-uuid>`
- **AND** `admin_id` SHALL NOT equal the `system` sentinel UUID `54b53072-540e-3eb8-b8e9-343e71f28176`

#### Scenario: Audit row records the forwarded client IP and request user-agent

- **GIVEN** the request carries `CF-Connecting-IP: 1.2.3.4` (per the `client-ip-extraction` capability) and a `User-Agent` header
- **WHEN** an authorized admin suspends a user
- **THEN** the resulting `admin_actions_log` row's `ip` SHALL be `1.2.3.4` (NOT the Cloudflare-edge / load-balancer hop) AND `user_agent` SHALL be the request's `User-Agent` value (NULL when the header is absent)

### Requirement: Suspend inserts an account_action_applied notification; unban inserts none

On a successful suspend, the system SHALL insert exactly one `notifications` row of `type = 'account_action_applied'` for the suspended user (the documented user-facing signal; `body_data` carries `{action_type, reason, suspended_until}` per the `docs/05-Implementation.md` notification catalog). On a successful manual unban, the system SHALL insert NO notification row (mirroring `suspension-unban-worker` design D5 — no `account_action_lifted` type exists, and the `account_action_applied` copy does not fit a positive restoration). FCM push dispatch for this notification is out of scope (deferred); the in-app `notifications` row is the in-band signal.

#### Scenario: Suspend inserts one account_action_applied notification for the target

- **WHEN** an active user is suspended by an authorized admin
- **THEN** exactly one new `notifications` row SHALL exist for the target user with `type = 'account_action_applied'`

#### Scenario: Unban inserts no notification

- **WHEN** a banned user is unbanned by an authorized admin
- **THEN** zero new `notifications` rows SHALL be inserted referencing the target user

### Requirement: The state change, its audit row, and its notification commit atomically

The system SHALL execute the `users` UPDATE, the `admin_actions_log` INSERT, and (for suspend) the `notifications` INSERT in a SINGLE database transaction, so they commit or roll back together. If the audit INSERT (or the notification INSERT) fails, the user UPDATE SHALL be rolled back — there SHALL never be a state change without its audit row, nor an audit row without the corresponding state change.

#### Scenario: Audit-insert failure rolls back the user update (suspend atomicity)

- **GIVEN** an authorized admin suspends an active user BUT the `admin_actions_log` INSERT fails within the transaction (e.g. a constraint violation injected in test)
- **WHEN** the request completes
- **THEN** the transaction SHALL be rolled back: the target user's row SHALL still have `is_banned = FALSE` AND its original `suspended_until` AND no `admin_actions_log` row AND no `notifications` row SHALL have been written for the target

#### Scenario: Successful suspend commits the update, the audit row, and the notification together

- **WHEN** an authorized admin successfully suspends an active user
- **THEN** after the request all three SHALL be present: the target user's row is `is_banned = TRUE` with `suspended_until` ≈ NOW() + 7 days, exactly one `user_suspended` `admin_actions_log` row exists, AND exactly one `account_action_applied` `notifications` row exists for the target
