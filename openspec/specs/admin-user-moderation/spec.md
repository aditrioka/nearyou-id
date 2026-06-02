# admin-user-moderation Specification

## Purpose

The `admin-user-moderation` capability is the durable home for admin-initiated account-state moderation — changes to a target user's ban / suspension state, served under `/admin/` behind the `admin-login` session + CSRF + role gate. It is the admin panel's FIRST state-changing surface (contrast the read-only `admin-actions-log-viewer`). It currently provides two actions — a server-fixed 7-day **suspend** and a **manual unban** (lifting either a time-bound suspension OR a pre-existing permanent ban) — each applied inside a single transaction that writes one immutable `admin_actions_log` row (attributed to the acting human admin, NEVER the `system` sentinel) and, for suspend, one sanitized `account_action_applied` notification (a fixed `"suspension"` reason code — the admin's free-text reason is audit-only, never echoed to the offender). Actions are role-gated in two tiers: `owner` / `admin` / `moderator` may suspend and lift a time-bound suspension, while lifting a *permanent* ban is restricted to `owner` / `admin`. Future account-state actions (permanent-ban creation, shadow ban, warning) extend this SAME capability with ADDED requirements; the user search / profile / history browse page is a DISTINCT future capability (`admin-user-management`).

See [`docs/07-Operations.md`](../../../docs/07-Operations.md) § Core Features (User Management) + § Security (the destructive-action-rate-limit target, deferred per `FOLLOW_UPS.md` § `admin-destructive-action-rate-limit`), the `suspension-unban-worker` capability (the automated elapse-driven sibling of manual unban), `system-actor` (the sentinel UUID this MUST NOT use), and `admin-login` (the auth / CSRF / `AdminPrincipal` gate this consumes).

## Requirements
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

The system SHALL resolve the `q` lookup parameter by first attempting to parse it as a user UUID and selecting that user by primary key; if `q` is not a valid UUID, the system SHALL attempt an EXACT, case-sensitive `users.username` match (the `username` column is `VARCHAR(60) NOT NULL UNIQUE`, so an exact match is deterministic). A value that parses as a UUID SHALL always be resolved via the UUID branch (a hypothetical UUID-shaped username is unreachable by lookup — an acceptable edge given usernames cannot contain the `-` grouping of a UUID under the username regex). All lookups SHALL use parameterized JDBC queries — never string-interpolated SQL. A `q` that resolves to no user SHALL render an inline empty-state ("no matching user") with HTTP 200 — never a 404 or 500. Reads of `users` in the admin module are permitted (the admin module is exempt from the `visible_*`-view + block-exclusion lint).

#### Scenario: Lookup by UUID returns the matching user

- **GIVEN** an authenticated session AND a user with a known UUID
- **WHEN** the client sends `GET /admin/users?q=<that-uuid>`
- **THEN** the response status SHALL be 200 AND the rendered body SHALL display that user's moderation state

#### Scenario: Lookup by exact username returns the matching user

- **GIVEN** an authenticated session AND a user with `username = 'budi_jakarta'`
- **WHEN** the client sends `GET /admin/users?q=budi_jakarta`
- **THEN** the response status SHALL be 200 AND the rendered body SHALL display that user's moderation state

#### Scenario: Username lookup is case-sensitive

- **GIVEN** an authenticated session AND a user with `username = 'budi_jakarta'`
- **WHEN** the client sends `GET /admin/users?q=BUDI_JAKARTA`
- **THEN** the response status SHALL be 200 AND the rendered body SHALL show the empty-state ("no matching user"), because the exact match is case-sensitive

#### Scenario: Non-resolving query renders an empty state, not a 404

- **GIVEN** an authenticated session AND no user matching the query
- **WHEN** the client sends `GET /admin/users?q=does-not-exist`
- **THEN** the response status SHALL be 200
- **AND** the rendered body SHALL contain an empty-state indicator ("no matching user") rather than an error page or a 404

#### Scenario: SQL-metacharacter query is treated as a literal

- **WHEN** an authenticated client sends `GET /admin/users?q=%27%3B+DROP+TABLE+users%3B--` (URL-encoded `'; DROP TABLE users;--`)
- **THEN** the response status SHALL be 200
- **AND** the `users` table SHALL still exist and be queryable afterward (the value matched zero users as a literal; no SQL was executed from it)

### Requirement: A malformed path identifier on the action routes is handled safely

The system SHALL tolerate a malformed `{id}` path segment on `POST /admin/users/{id}/suspend` and `POST /admin/users/{id}/unban` without a 500 and without any state change. A `{id}` that does not parse as a UUID SHALL be rejected with an inline error / 4xx response (the exact code is implementation-defined — 400 Bad Request or a re-rendered error page — but it SHALL NOT be a 500, SHALL NOT mutate any `users` row, and SHALL NOT write any `admin_actions_log` row). This is a distinct parse site from the `GET` lookup's `q` parameter (a path segment, not a query parameter).

#### Scenario: Non-UUID id on the suspend route is rejected without a 500 or state change

- **GIVEN** an authenticated authorized session with a valid CSRF token
- **WHEN** the client sends `POST /admin/users/not-a-uuid/suspend`
- **THEN** the response status SHALL NOT be 500 (it SHALL be a 4xx / inline error)
- **AND** no `users` row SHALL be mutated AND no `admin_actions_log` row SHALL be written

#### Scenario: Non-UUID id on the unban route is rejected without a 500 or state change

- **GIVEN** an authenticated authorized session with a valid CSRF token
- **WHEN** the client sends `POST /admin/users/not-a-uuid/unban`
- **THEN** the response status SHALL NOT be 500 (it SHALL be a 4xx / inline error)
- **AND** no `users` row SHALL be mutated AND no `admin_actions_log` row SHALL be written

### Requirement: Suspend applies a server-fixed 7-day suspension to an eligible user

The system SHALL serve `POST /admin/users/{id}/suspend` (authenticated, role-gated, CSRF-gated per the requirements below). For an ELIGIBLE target — a user that is NOT soft-deleted (`deleted_at IS NULL`) AND NOT already permanently banned (`is_banned = TRUE AND suspended_until IS NULL`) — the handler SHALL set `is_banned = TRUE` AND `suspended_until = NOW() + INTERVAL '7 days'`. The 7-day window SHALL be computed server-side; the handler SHALL NOT accept a client-supplied suspension duration (any such request field SHALL be ignored). The eligibility test for "already suspended" SHALL key on `suspended_until IS NULL` (permanent) vs non-null, NOT on `suspended_until > NOW()` — a user whose `suspended_until` is in the PAST but `is_banned` is still TRUE (the daily worker has not yet swept it) is still re-suspendable. Re-suspending any time-bound-suspended user (future- OR past-dated `suspended_until`) SHALL reset `suspended_until` to `NOW() + INTERVAL '7 days'` and SHALL capture the prior expiry in the audit `before_state`. On success the handler SHALL redirect (303, or `HX-Redirect` for HTMX) back to the user's lookup view.

#### Scenario: Active user is suspended for 7 days

- **GIVEN** an authenticated `moderator`/`admin`/`owner` session (valid CSRF) AND a target user with `is_banned = FALSE`, `deleted_at IS NULL`
- **WHEN** the client sends `POST /admin/users/{id}/suspend`
- **THEN** after the request the target user's row SHALL have `is_banned = TRUE` AND `suspended_until` ≈ `NOW() + INTERVAL '7 days'` (±10s clock tolerance)

#### Scenario: Suspension duration is server-fixed and ignores client-supplied duration

- **GIVEN** an authenticated authorized session (valid CSRF) AND an eligible target user
- **WHEN** the client sends `POST /admin/users/{id}/suspend` with a form field attempting to set a custom duration (e.g. `duration_days=3650`)
- **THEN** `suspended_until` SHALL be ≈ `NOW() + INTERVAL '7 days'` (±10s) — the client-supplied value SHALL be ignored

#### Scenario: Re-suspending a future-dated suspension resets the 7-day clock

- **GIVEN** an authenticated authorized session (valid CSRF) AND a target user with `is_banned = TRUE`, `suspended_until = NOW() + INTERVAL '2 days'`
- **WHEN** the client sends `POST /admin/users/{id}/suspend`
- **THEN** `suspended_until` SHALL be reset to ≈ `NOW() + INTERVAL '7 days'` (±10s)
- **AND** the resulting `admin_actions_log` row's `before_state` `suspended_until` (parsed as an `Instant`) SHALL value-equal the prior expiry (≈ NOW() + 2 days, ±10s) — asserted by parsed-instant equality, NOT string match

#### Scenario: Re-suspending an elapsed-but-unswept suspension is allowed

- **GIVEN** an authenticated authorized session (valid CSRF) AND a target user with `is_banned = TRUE`, `suspended_until = NOW() - INTERVAL '1 hour'` (window elapsed, the daily worker has not yet run), `deleted_at IS NULL`
- **WHEN** the client sends `POST /admin/users/{id}/suspend`
- **THEN** the suspend SHALL be applied (the guard keys on `suspended_until IS NULL`, not on `> NOW()`): `suspended_until` SHALL be reset to ≈ `NOW() + INTERVAL '7 days'`

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

The system SHALL serve `POST /admin/users/{id}/unban` (authenticated, role-gated, CSRF-gated). For a target that is currently banned (`is_banned = TRUE`), the handler SHALL set `is_banned = FALSE` AND `suspended_until = NULL` — lifting BOTH a time-bound suspension AND a permanent ban (the admin's deliberate override; the permanent-ban case is additionally role-restricted per the role-gate requirement). This is the same `(is_banned, suspended_until) → (FALSE, NULL)` transition the automated `suspension-unban-worker` performs on elapse, triggered early by a human. For a target that is NOT currently banned (`is_banned = FALSE`), the handler SHALL make no state change, SHALL write NO `admin_actions_log` row (the log records only actual transitions), and SHALL surface an informational "user is not banned" message. A soft-deleted-but-banned target MAY be unbanned (lifting a ban on a tombstoned row is harmless; `deleted_at` is unchanged).

#### Scenario: Time-bound-suspended user is unbanned

- **GIVEN** an authenticated authorized session (valid CSRF) AND a target user with `is_banned = TRUE`, `suspended_until = NOW() + INTERVAL '5 days'`
- **WHEN** the client sends `POST /admin/users/{id}/unban`
- **THEN** after the request the target user's row SHALL have `is_banned = FALSE` AND `suspended_until = NULL`

#### Scenario: Permanently-banned user is unbanned by an owner/admin override

- **GIVEN** an authenticated `owner` or `admin` session (valid CSRF) AND a target user with `is_banned = TRUE`, `suspended_until IS NULL` (permanent ban)
- **WHEN** the client sends `POST /admin/users/{id}/unban`
- **THEN** after the request the target user's row SHALL have `is_banned = FALSE` AND `suspended_until = NULL`

#### Scenario: Unbanning a not-currently-banned user is a no-op that writes no audit row

- **GIVEN** an authenticated authorized session (valid CSRF) AND a target user with `is_banned = FALSE`
- **WHEN** the client sends `POST /admin/users/{id}/unban`
- **THEN** the target user's row SHALL remain `is_banned = FALSE`
- **AND** no new `admin_actions_log` row SHALL be written for the target (the log records only actual state transitions)

#### Scenario: Soft-deleted-but-banned user may be unbanned

- **GIVEN** an authenticated authorized session (valid CSRF) AND a target user with `is_banned = TRUE`, `suspended_until = NOW() + INTERVAL '3 days'`, `deleted_at = NOW() - INTERVAL '2 days'`
- **WHEN** the client sends `POST /admin/users/{id}/unban`
- **THEN** the target user's row SHALL have `is_banned = FALSE` AND `suspended_until = NULL` AND `deleted_at` SHALL be unchanged (the unban does not resurrect a tombstoned account)

### Requirement: State-changing actions are role-gated; lifting a permanent ban is restricted to owner/admin

The system SHALL restrict `POST /admin/users/{id}/suspend` and `POST /admin/users/{id}/unban` to admins whose `admin_users.role` is `owner`, `admin`, or `moderator`. A `read_only` admin SHALL be rejected with HTTP 403 (authenticated but unauthorized — NOT a redirect) on either route, with no state change and no audit row. This base role check SHALL run AFTER CSRF validation. ADDITIONALLY, because lifting a PERMANENT ban (`is_banned = TRUE AND suspended_until IS NULL`) is a higher-trust, harder-to-undo action than a 7-day suspension or lifting a time-bound suspension, the unban handler SHALL require `role` ∈ {`owner`, `admin`} when the target is permanently banned — a `moderator` attempting to unban a permanently-banned target SHALL be rejected with HTTP 403, with no state change and no audit row. The permanent-ban role check SHALL occur after reading the target's current state within the same transaction, so the moderator rejection writes nothing. (Contrast: `admin-actions-log-viewer` reads are available to ALL roles including `read_only`; this is the first role-gated admin WRITE, and the permanent-ban tier mirrors the higher-trust gate that chat redaction places at `owner`/`admin`.)

#### Scenario: read_only admin is forbidden from suspending

- **GIVEN** an authenticated session for an admin whose `role = 'read_only'` (with a valid CSRF token)
- **WHEN** the client sends `POST /admin/users/{id}/suspend`
- **THEN** the response status SHALL be 403
- **AND** the target user's `is_banned` / `suspended_until` SHALL be unchanged
- **AND** no `user_suspended` `admin_actions_log` row SHALL be written

#### Scenario: read_only admin is forbidden from unbanning

- **GIVEN** an authenticated session for an admin whose `role = 'read_only'` (with a valid CSRF token) AND a banned target user
- **WHEN** the client sends `POST /admin/users/{id}/unban`
- **THEN** the response status SHALL be 403
- **AND** the target user SHALL remain banned AND no `user_unbanned` audit row SHALL be written

#### Scenario: moderator admin is permitted to suspend

- **GIVEN** an authenticated session for an admin whose `role = 'moderator'` (with a valid CSRF token) AND an eligible target user
- **WHEN** the client sends `POST /admin/users/{id}/suspend`
- **THEN** the request SHALL be authorized (not 403) AND the target user SHALL be suspended per the suspend requirement

#### Scenario: moderator admin is permitted to unban a time-bound suspension

- **GIVEN** an authenticated session for an admin whose `role = 'moderator'` (with a valid CSRF token) AND a target user with `is_banned = TRUE`, `suspended_until = NOW() + INTERVAL '4 days'`
- **WHEN** the client sends `POST /admin/users/{id}/unban`
- **THEN** the request SHALL be authorized AND the target user SHALL have `is_banned = FALSE`, `suspended_until = NULL`

#### Scenario: moderator admin is forbidden from unbanning a permanent ban

- **GIVEN** an authenticated session for an admin whose `role = 'moderator'` (with a valid CSRF token) AND a target user with `is_banned = TRUE`, `suspended_until IS NULL` (permanent ban)
- **WHEN** the client sends `POST /admin/users/{id}/unban`
- **THEN** the response status SHALL be 403
- **AND** the target user SHALL remain `is_banned = TRUE`, `suspended_until IS NULL` AND no `user_unbanned` audit row SHALL be written

#### Scenario: admin role is permitted to unban a permanent ban

- **GIVEN** an authenticated session for an admin whose `role = 'admin'` (with a valid CSRF token) AND a permanently-banned target user
- **WHEN** the client sends `POST /admin/users/{id}/unban`
- **THEN** the request SHALL be authorized AND the target user SHALL have `is_banned = FALSE`, `suspended_until = NULL`

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

#### Scenario: Unban without a CSRF token is rejected

- **GIVEN** an authenticated authorized session AND a banned target user
- **WHEN** the client sends `POST /admin/users/{id}/unban` with no `X-CSRF-Token` header and no `_csrf` form field
- **THEN** the response status SHALL be 403
- **AND** the target user SHALL remain banned AND no `user_unbanned` audit row SHALL be written

#### Scenario: Unban with a valid CSRF token proceeds

- **GIVEN** an authenticated authorized session (token stored) AND a time-bound-suspended target user
- **WHEN** the client sends `POST /admin/users/{id}/unban` with `X-CSRF-Token: <plaintext-csrf>`
- **THEN** the CSRF check SHALL pass and the target user SHALL be unbanned per the unban requirement

### Requirement: The admin-entered reason is read from the form body after CSRF validation and recorded in the audit row only

The handlers SHALL accept an OPTIONAL admin-entered free-text `reason` as a form field on the suspend / unban POST body. Because the shared CSRF gate may already have consumed the request body via `receiveParameters()` (the `_csrf`-form-field path), and Ktor does NOT re-serve a consumed body without the DoubleReceive plugin, the CSRF gate SHALL stash the parsed form parameters in the call attributes; the handler SHALL read `reason` from those post-validation parameters (via `AdminCsrfGate.formParametersAfterValidation`) AFTER calling `validateCsrf` — returning the same values whether the CSRF token arrived via the header (body untouched, so the handler performs the first read) or the `_csrf` field (body already parsed and stashed by the gate). The free-text `reason` SHALL be recorded in the `admin_actions_log.reason` column (admin-only audit surface), NULL when absent/blank. The free-text `reason` SHALL NOT be echoed into the user-facing notification (see the notification requirement) — it is moderator-internal.

#### Scenario: Suspend with both _csrf and reason in one form body records the reason in the audit row

- **GIVEN** an authenticated authorized session AND an eligible target user
- **WHEN** the client sends `POST /admin/users/{id}/suspend` as an `application/x-www-form-urlencoded` body containing BOTH `_csrf=<plaintext-csrf>` AND `reason=spam+and+harassment`
- **THEN** the CSRF check SHALL pass (token read from the `_csrf` field) AND the suspend SHALL be applied
- **AND** the resulting `admin_actions_log` row's `reason` SHALL be `spam and harassment` (read from the form parameters the CSRF gate stashed, not dropped)

#### Scenario: Suspend with no reason field records a NULL reason

- **GIVEN** an authenticated authorized session AND an eligible target user
- **WHEN** the client sends `POST /admin/users/{id}/suspend` (valid CSRF via header) with no `reason` field
- **THEN** the suspend SHALL be applied AND the resulting `admin_actions_log` row's `reason` SHALL be NULL

### Requirement: Every applied action writes one immutable audit row attributed to the acting human admin

For every action that changes state (a successful suspend, or a successful unban of a banned user), the system SHALL INSERT exactly one `admin_actions_log` row with: `admin_id` = the acting admin's own UUID (the `AdminPrincipal` from the session — NEVER the `system` sentinel UUID `54b53072-540e-3eb8-b8e9-343e71f28176` owned by the `system-actor` capability); `action_type` = `'user_suspended'` for suspend or `'user_unbanned'` for unban; `target_type` = `'user'`; `target_id` = the target user's UUID rendered as text; `reason` = the admin-entered free-text (NULL-tolerant, per the reason requirement); `before_state` = `{"is_banned": <prior>, "suspended_until": <prior | null>}`; `after_state` = `{"is_banned": <new>, "suspended_until": <new | null>}`; `ip` from `call.clientIp` (the `client-ip-extraction` capability — never raw `X-Forwarded-For`); `user_agent` from the request header (NULL when absent). All values SHALL be written via parameterized placeholders.

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

#### Scenario: Audit row records the forwarded client IP

- **GIVEN** the request carries `CF-Connecting-IP: 1.2.3.4` (per the `client-ip-extraction` capability)
- **WHEN** an authorized admin suspends a user
- **THEN** the resulting `admin_actions_log` row's `ip` SHALL be `1.2.3.4` (NOT the Cloudflare-edge / load-balancer hop)

#### Scenario: Audit row records NULL user_agent when the request omitted the header

- **WHEN** an authorized admin suspends a user via a request that carries NO `User-Agent` header
- **THEN** the resulting `admin_actions_log` row's `user_agent` SHALL be NULL (and the positive case — header present — records the verbatim `User-Agent` value)

### Requirement: Suspend inserts an account_action_applied notification with a sanitized reason; unban inserts none

On a successful suspend, the system SHALL insert exactly one `notifications` row with `user_id` = the suspended (target) user, `type = 'account_action_applied'`, and `body_data` = `{"action_type": "user_suspended", "reason": <sanitized code>, "suspended_until": <ISO-8601 instant>}` per the `docs/05-Implementation.md` notification catalog. The `body_data.reason` SHALL be a SANITIZED, non-free-text value (this change sets it to the fixed code `"suspension"`) — it SHALL NOT carry the admin-entered free-text `reason`, which is moderator-internal and lives only in `admin_actions_log.reason` (the notification is read by the suspended end-user; leaking moderator rationale or third-party PII to the offender is forbidden). The notification row SHALL NOT duplicate `target_id` inside `body_data` (per the catalog), and `actor_user_id` SHALL be NULL (the actor is an admin, not a `public.users` row). On a successful manual unban, the system SHALL insert NO notification row (mirroring `suspension-unban-worker` design D5 — no `account_action_lifted` type exists, and the `account_action_applied` copy does not fit a positive restoration). FCM push dispatch for this notification is out of scope (deferred); the in-app `notifications` row is the in-band signal.

#### Scenario: Suspend inserts one account_action_applied notification for the target

- **WHEN** an active user is suspended by an authorized admin
- **THEN** exactly one new `notifications` row SHALL exist with `user_id` = the target user AND `type = 'account_action_applied'`

#### Scenario: Notification body_data carries the action_type + suspended_until but NOT the free-text reason

- **WHEN** an authorized admin suspends a user with the free-text `reason = 'suspected minor, see report #42'`
- **THEN** the inserted notification's `body_data` SHALL contain `action_type = 'user_suspended'` AND a non-null `suspended_until`
- **AND** the notification's `body_data` SHALL NOT contain the substring `suspected minor` (the free-text reason is recorded ONLY in `admin_actions_log.reason`, never in the user-facing notification)

#### Scenario: Unban inserts no notification

- **WHEN** a banned user is unbanned by an authorized admin
- **THEN** zero new `notifications` rows SHALL be inserted referencing the target user

### Requirement: The state change, its audit row, and its notification commit atomically

The system SHALL execute the `users` UPDATE, the `admin_actions_log` INSERT, and (for suspend) the `notifications` INSERT in a SINGLE database transaction, so they commit or roll back together. If the audit INSERT OR the notification INSERT fails, the user UPDATE SHALL be rolled back — there SHALL never be a state change without its audit row, nor an audit row without the corresponding state change, nor a partial (update-without-notification) suspend.

#### Scenario: Audit-insert failure rolls back the user update (suspend atomicity)

- **GIVEN** an authorized admin suspends an active user BUT the `admin_actions_log` INSERT fails within the transaction (e.g. a constraint violation injected in test)
- **WHEN** the request completes
- **THEN** the transaction SHALL be rolled back: the target user's row SHALL still have `is_banned = FALSE` AND its original `suspended_until` AND no `admin_actions_log` row AND no `notifications` row SHALL have been written for the target

#### Scenario: Notification-insert failure rolls back the user update and audit row (suspend atomicity)

- **GIVEN** an authorized admin suspends an active user BUT the `notifications` INSERT (the last write in the suspend transaction) fails within the transaction (e.g. a constraint violation injected in test)
- **WHEN** the request completes
- **THEN** the transaction SHALL be rolled back: the target user's row SHALL still have `is_banned = FALSE` AND its original `suspended_until` AND NO `admin_actions_log` `user_suspended` row AND NO `notifications` row SHALL have been written for the target

#### Scenario: Successful suspend commits the update, the audit row, and the notification together

- **WHEN** an authorized admin successfully suspends an active user
- **THEN** after the request all three SHALL be present: the target user's row is `is_banned = TRUE` with `suspended_until` ≈ NOW() + 7 days, exactly one `user_suspended` `admin_actions_log` row exists, AND exactly one `account_action_applied` `notifications` row exists for the target

