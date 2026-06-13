## ADDED Requirements

### Requirement: Authenticated GET /admin/users/{id} renders the per-user profile page

The system SHALL serve `GET /admin/users/{id}` as an authenticated route wired INSIDE the `authenticate(ADMIN_AUTH_NAME)` block established by `admin-login`, so the session middleware gates it. Any authenticated admin role (`owner`/`admin`/`moderator`/`read_only`) MAY view the page. On a valid session and a `{id}` that resolves to a user, it SHALL return HTTP 200 with an HTML page that extends the shared admin base layout (`admin-panel-scaffold`) and renders the profile block, the action-history view, and the action controls (per the requirements below). The route SHALL be **read-only** — serving it SHALL NOT write any `admin_actions_log` row and SHALL NOT mutate any table. Reads of `users`, `admin_actions_log`, and `username_history` in the admin module are permitted (the admin module is exempt from the `visible_*`-view + block-exclusion lint); all lookups SHALL use parameterized JDBC, never string-interpolated SQL.

#### Scenario: Authenticated request for an existing user returns the profile page

- **GIVEN** an authenticated admin session AND a user with a known UUID
- **WHEN** the client sends `GET /admin/users/<that-uuid>` carrying the valid `__Host-admin_session` cookie
- **THEN** the response status SHALL be 200
- **AND** the response body SHALL contain the base-layout structural sections (header, nav, footer) AND the profile block for that user

#### Scenario: Serving the profile page writes no audit row and mutates nothing

- **GIVEN** an authenticated admin session AND a known count of `admin_actions_log` rows
- **WHEN** `GET /admin/users/<uuid>` is served
- **THEN** the `admin_actions_log` row count SHALL be unchanged AND no `users` / `username_history` row SHALL be inserted, updated, or deleted

#### Scenario: Unauthenticated request redirects to the login page

- **WHEN** a client sends `GET /admin/users/<uuid>` with no `__Host-admin_session` cookie (or an invalid / expired / revoked / idle-timed-out session)
- **THEN** the response status SHALL be 302 AND the `Location` header SHALL be `/admin/login` AND no profile content SHALL be served

### Requirement: The profile block renders the user's identity and current moderation state

The profile block SHALL display the target user's `id` (UUID), `username`, `display_name`, `is_premium`, `created_at` (account age), and `private_profile_opt_in`, together with the current moderation state `is_banned`, `suspended_until`, and `is_shadow_banned`. Every rendered value SHALL pass through Pebble autoescape (no `| raw`).

#### Scenario: Identity and moderation state are rendered

- **GIVEN** an authenticated session AND a user with `is_banned = TRUE`, `suspended_until = <a future instant>`, `is_premium = TRUE`
- **WHEN** the client sends `GET /admin/users/<that-uuid>`
- **THEN** the rendered body SHALL display that user's `username` and `display_name` AND its `is_banned` / `suspended_until` / `is_shadow_banned` state AND its `is_premium` status

#### Scenario: A username containing markup is HTML-escaped

- **GIVEN** an authenticated session AND a user whose `display_name` contains `<script>alert(1)</script>`
- **WHEN** the profile page is served
- **THEN** the rendered body SHALL contain the escaped form (no executable `<script>` element from the `display_name` value)

### Requirement: The action-history view merges admin_actions_log and username_history, newest-first

The page SHALL render a history view combining (a) the target user's admin-action rows — `admin_actions_log` WHERE `target_type = 'user' AND target_id = {id}`, surfacing the same columns the audit-log viewer shows (time, acting admin resolved to a human-readable identity, action type, reason, and a `before_state`→`after_state` disclosure) — and (b) the user's `username_history` rows (`old_username`→`new_username`, `changed_at`). Both sources SHALL be ordered newest-first. The history view SHALL be read-only.

#### Scenario: Prior admin actions against the user appear in the history

- **GIVEN** an authenticated session AND a user who was previously suspended (one `admin_actions_log` row with `action_type = 'user_suspended'`, `target_type = 'user'`, `target_id = <user>`)
- **WHEN** the client sends `GET /admin/users/<that-uuid>`
- **THEN** the history view SHALL include a row for that suspend action showing its action type, the acting admin, and a state disclosure

#### Scenario: Username changes appear in the history

- **GIVEN** an authenticated session AND a user with one `username_history` row (`old_username = 'budi'`, `new_username = 'budi_jakarta'`)
- **WHEN** the profile page is served
- **THEN** the history view SHALL include that username change (`budi` → `budi_jakarta`) with its `changed_at`

#### Scenario: A user with no history renders an empty history view, not an error

- **GIVEN** an authenticated session AND a freshly-created user with no `admin_actions_log` and no `username_history` rows
- **WHEN** the profile page is served
- **THEN** the response status SHALL be 200 AND the history view SHALL render an empty-state indicator (no rows) rather than an error

### Requirement: The profile page surfaces the action controls and the destructive-quota chip

The page SHALL render the moderation action controls — the existing suspend and unban controls (posting to `/admin/users/{id}/suspend` and `/admin/users/{id}/unban`, owned by `admin-user-moderation`) and the NEW warning control (posting to `/admin/users/{id}/warn`) — each carrying the session CSRF token as a hidden field. The page SHALL ALSO render the acting admin's live **destructive-action quota chip** showing the current count against the cap (e.g. "3/20 this hour"), sourced from the `admin-destructive-action-rate-limit` count for the acting admin.

#### Scenario: The action controls and quota chip render

- **GIVEN** an authenticated write-role admin session AND a target user
- **WHEN** the profile page is served
- **THEN** the rendered body SHALL contain a suspend control posting to `/admin/users/<id>/suspend`, an unban control posting to `/admin/users/<id>/unban`, and a warning control posting to `/admin/users/<id>/warn`, each with a `_csrf` hidden field
- **AND** the rendered body SHALL display a destructive-action quota indicator showing the acting admin's current destructive-action count against the cap of 20

### Requirement: A malformed {id} path segment is handled safely

The system SHALL tolerate a malformed `{id}` path segment on `GET /admin/users/{id}` without a 500 and without any state change. A `{id}` that does not parse as a UUID SHALL be rejected with an inline error / 4xx response (the exact code is implementation-defined — 400 or a re-rendered error page — but it SHALL NOT be a 500 and SHALL NOT mutate any table).

#### Scenario: Non-UUID id renders safely, not a 500

- **GIVEN** an authenticated admin session
- **WHEN** the client sends `GET /admin/users/not-a-uuid`
- **THEN** the response status SHALL NOT be 500 (it SHALL be a 4xx / inline error) AND no table SHALL be mutated

#### Scenario: SQL-metacharacter id is treated as a literal

- **WHEN** an authenticated client sends `GET /admin/users/%27%3B+DROP+TABLE+users%3B--` (URL-encoded `'; DROP TABLE users;--`)
- **THEN** the response status SHALL NOT be 500 AND the `users` table SHALL still exist and be queryable afterward

### Requirement: A {id} resolving to no user renders an inline empty-state, not a 404

A well-formed UUID `{id}` that matches no `users` row SHALL render an inline empty-state ("no matching user") with HTTP 200 — never a 404 or 500.

#### Scenario: Unknown but well-formed UUID shows the empty-state

- **GIVEN** an authenticated session AND no user with the queried UUID
- **WHEN** the client sends `GET /admin/users/<a-random-unused-uuid>`
- **THEN** the response status SHALL be 200 AND the rendered body SHALL contain an empty-state indicator ("no matching user") rather than an error page or a 404

### Requirement: The user-lookup result deep-links to the profile page

The `GET /admin/users?q=` lookup result (the `admin-user-moderation` lookup surface, `users-result.peb`) SHALL deep-link a resolved user to `/admin/users/{id}` so a moderator can reach the full profile + history page from the lookup.

#### Scenario: A resolved lookup links to the profile page

- **GIVEN** an authenticated session AND a user resolvable by the lookup
- **WHEN** the client sends `GET /admin/users?q=<that-uuid>`
- **THEN** the rendered result fragment SHALL contain a link whose target is `/admin/users/<that-uuid>`
