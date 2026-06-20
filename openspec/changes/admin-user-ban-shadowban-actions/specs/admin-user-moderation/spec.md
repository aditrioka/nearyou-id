## ADDED Requirements

### Requirement: Permanent ban applies an irreversible ban to an eligible user

The system SHALL serve `POST /admin/users/{id}/ban` (authenticated, role-gated, CSRF-gated per the requirements below). For an ELIGIBLE target — a user that is NOT soft-deleted (`deleted_at IS NULL`) AND NOT already permanently banned (`is_banned = TRUE AND suspended_until IS NULL`) — the handler SHALL set `is_banned = TRUE` AND `suspended_until = NULL` (a permanent ban with no automatic unban). The handler SHALL NOT modify `token_version` and SHALL NOT delete refresh tokens (mirroring the shipped suspend / unban / report-queue ban — enforcement is the per-request `is_banned` auth check, not session-token invalidation). A target with `is_banned = TRUE AND suspended_until` in the future OR past (a time-bound suspension) IS eligible: banning such a user SHALL escalate it to a permanent ban (`suspended_until = NULL`) and SHALL capture the prior `suspended_until` in the audit `before_state`. The permanent-ban column write SHALL reuse the same enforcement primitive as the report-queue `ban_author` resolution (one ban behavior across both entry points — no divergent second implementation). On success the handler SHALL redirect (303, or `HX-Redirect` for HTMX) back to the user's profile/lookup view.

#### Scenario: Active user is permanently banned

- **GIVEN** an authenticated `owner`/`admin` session (valid CSRF) AND a target user with `is_banned = FALSE`, `deleted_at IS NULL`
- **WHEN** the client sends `POST /admin/users/{id}/ban`
- **THEN** after the request the target user's row SHALL have `is_banned = TRUE` AND `suspended_until IS NULL`

#### Scenario: Banning a time-bound-suspended user escalates to permanent and records the prior expiry

- **GIVEN** an authenticated `owner`/`admin` session (valid CSRF) AND a target user with `is_banned = TRUE`, `suspended_until = NOW() + INTERVAL '2 days'`
- **WHEN** the client sends `POST /admin/users/{id}/ban`
- **THEN** `suspended_until` SHALL become `NULL` (permanent) AND the resulting `admin_actions_log` row's `before_state.suspended_until` (parsed as an `Instant`) SHALL value-equal the prior expiry (≈ NOW() + 2 days, ±10s)

#### Scenario: Permanent ban leaves token_version unchanged

- **GIVEN** an authenticated `owner`/`admin` session (valid CSRF) AND an eligible target with a known `token_version`
- **WHEN** the client sends `POST /admin/users/{id}/ban`
- **THEN** the target's `token_version` SHALL be unchanged (enforcement is the per-request `is_banned` auth check)

#### Scenario: Banning a soft-deleted user is rejected with no state change

- **GIVEN** an authenticated `owner`/`admin` session (valid CSRF) AND a target with `deleted_at IS NOT NULL`
- **WHEN** the client sends `POST /admin/users/{id}/ban`
- **THEN** no `users` row SHALL be mutated AND no `admin_actions_log` row SHALL be written

#### Scenario: Banning an already-permanently-banned user is a no-op that writes no audit row

- **GIVEN** an authenticated `owner`/`admin` session (valid CSRF) AND a target with `is_banned = TRUE AND suspended_until IS NULL`
- **WHEN** the client sends `POST /admin/users/{id}/ban`
- **THEN** the row SHALL remain permanently banned (no change) AND no new `admin_actions_log` row SHALL be written

### Requirement: Shadow ban hides an eligible user's content invisibly

The system SHALL serve `POST /admin/users/{id}/shadow-ban` (authenticated, role-gated, CSRF-gated). For an ELIGIBLE target — NOT soft-deleted (`deleted_at IS NULL`) AND NOT already shadow-banned (`is_shadow_banned = FALSE`) — the handler SHALL set `is_shadow_banned = TRUE` and SHALL change no other column. The shadow-ban column write SHALL reuse the same enforcement primitive as the report-queue `shadow_ban_author` resolution. A shadow ban SHALL be invisible to the offender — the handler SHALL write NO notification. On success the handler SHALL redirect (303, or `HX-Redirect` for HTMX) back to the user's profile/lookup view.

#### Scenario: Active user is shadow-banned

- **GIVEN** an authenticated write-role session (valid CSRF) AND a target with `is_shadow_banned = FALSE`, `deleted_at IS NULL`
- **WHEN** the client sends `POST /admin/users/{id}/shadow-ban`
- **THEN** after the request the target's row SHALL have `is_shadow_banned = TRUE` AND no other moderation column SHALL change

#### Scenario: Shadow-banning a soft-deleted user is rejected with no state change

- **GIVEN** an authenticated write-role session (valid CSRF) AND a target with `deleted_at IS NOT NULL`
- **WHEN** the client sends `POST /admin/users/{id}/shadow-ban`
- **THEN** no `users` row SHALL be mutated AND no `admin_actions_log` row SHALL be written

#### Scenario: Shadow-banning an already-shadow-banned user is a no-op that writes no audit row

- **GIVEN** an authenticated write-role session (valid CSRF) AND a target with `is_shadow_banned = TRUE`
- **WHEN** the client sends `POST /admin/users/{id}/shadow-ban`
- **THEN** the row SHALL remain shadow-banned (no change) AND no new `admin_actions_log` row SHALL be written

### Requirement: Un-shadow-ban lifts a shadow ban for a shadow-banned target

The system SHALL serve `POST /admin/users/{id}/shadow-unban` (authenticated, role-gated, CSRF-gated) as the restorative reversal of a shadow ban — the ONLY admin path that clears `is_shadow_banned` (the existing `unban` action clears `is_banned`/`suspended_until` only and never touches `is_shadow_banned`). For a target with `is_shadow_banned = TRUE` the handler SHALL set `is_shadow_banned = FALSE` and write NO notification. When the target is NOT currently shadow-banned the handler SHALL be a no-op that writes NO `admin_actions_log` row (the log records only actual transitions). A soft-deleted-but-shadow-banned target MAY be un-shadow-banned (`deleted_at` unchanged). On success the handler SHALL redirect (303, or `HX-Redirect` for HTMX) back to the user's profile/lookup view.

#### Scenario: Shadow-banned user is un-shadow-banned

- **GIVEN** an authenticated write-role session (valid CSRF) AND a target with `is_shadow_banned = TRUE`
- **WHEN** the client sends `POST /admin/users/{id}/shadow-unban`
- **THEN** after the request the target's row SHALL have `is_shadow_banned = FALSE`

#### Scenario: Un-shadow-banning a not-shadow-banned user is a no-op that writes no audit row

- **GIVEN** an authenticated write-role session (valid CSRF) AND a target with `is_shadow_banned = FALSE`
- **WHEN** the client sends `POST /admin/users/{id}/shadow-unban`
- **THEN** no `users` row SHALL be mutated AND no new `admin_actions_log` row SHALL be written

### Requirement: The ban / shadow-ban / shadow-unban actions are role-gated

The system SHALL role-gate the three new actions, checked INSIDE the action transaction so a rejected role writes nothing. Permanent ban (`POST /admin/users/{id}/ban`) SHALL be restricted to `owner` / `admin` (the higher-trust tier, symmetric with lifting a permanent ban). Shadow ban (`POST /admin/users/{id}/shadow-ban`) AND un-shadow-ban (`POST /admin/users/{id}/shadow-unban`) SHALL be permitted for all write roles (`owner` / `admin` / `moderator`). A `read_only` admin SHALL be forbidden from all three. A forbidden attempt SHALL mutate no `users` row and write no `admin_actions_log` row.

#### Scenario: moderator is forbidden from permanent ban

- **GIVEN** an authenticated `moderator` session (valid CSRF) AND an eligible target
- **WHEN** the client sends `POST /admin/users/{id}/ban`
- **THEN** the response SHALL be a forbidden/redirect-to-error result AND no `users` row SHALL be mutated AND no `admin_actions_log` row SHALL be written

#### Scenario: admin is permitted to permanently ban

- **GIVEN** an authenticated `admin` session (valid CSRF) AND an eligible target
- **WHEN** the client sends `POST /admin/users/{id}/ban`
- **THEN** the target SHALL be permanently banned (`is_banned = TRUE`, `suspended_until IS NULL`)

#### Scenario: moderator is permitted to shadow-ban and un-shadow-ban

- **GIVEN** an authenticated `moderator` session (valid CSRF) AND an eligible target
- **WHEN** the client sends `POST /admin/users/{id}/shadow-ban` and then `POST /admin/users/{id}/shadow-unban`
- **THEN** the target SHALL be shadow-banned and then un-shadow-banned (`is_shadow_banned` TRUE then FALSE)

#### Scenario: read_only admin is forbidden from shadow-ban

- **GIVEN** an authenticated `read_only` session (valid CSRF) AND an eligible target
- **WHEN** the client sends `POST /admin/users/{id}/shadow-ban`
- **THEN** the response SHALL be forbidden AND no `users` row SHALL be mutated AND no `admin_actions_log` row SHALL be written

### Requirement: The new actions require a valid CSRF token

Each of `POST /admin/users/{id}/ban`, `/shadow-ban`, and `/shadow-unban` SHALL require a valid session CSRF token. A request with a missing or wrong CSRF token SHALL be rejected with no `users` mutation and no `admin_actions_log` row, BEFORE any role check or state change.

#### Scenario: Ban without a CSRF token is rejected

- **GIVEN** an authenticated `owner` session AND an eligible target
- **WHEN** the client sends `POST /admin/users/{id}/ban` with no `_csrf` field
- **THEN** the request SHALL be rejected AND no `users` row SHALL be mutated AND no `admin_actions_log` row SHALL be written

#### Scenario: Shadow-ban with a wrong CSRF token is rejected

- **GIVEN** an authenticated write-role session AND an eligible target
- **WHEN** the client sends `POST /admin/users/{id}/shadow-ban` with an incorrect `_csrf` value
- **THEN** the request SHALL be rejected AND no `users` row SHALL be mutated AND no `admin_actions_log` row SHALL be written

#### Scenario: CSRF is checked before the role gate

- **GIVEN** an authenticated `read_only` session (which the role gate would reject) presenting a missing/wrong CSRF token AND an eligible target
- **WHEN** the client sends `POST /admin/users/{id}/ban`
- **THEN** the request SHALL be rejected as a CSRF violation (the CSRF gate runs before the role gate) AND no `users` row SHALL be mutated AND no `admin_actions_log` row SHALL be written

### Requirement: A malformed path identifier on the new action routes is handled safely

The system SHALL tolerate a malformed `{id}` path segment on `POST /admin/users/{id}/ban`, `/shadow-ban`, and `/shadow-unban` without a 500 and without any state change. A `{id}` that does not parse as a UUID SHALL be rejected with an inline error / 4xx response (NOT a 500), SHALL NOT mutate any `users` row, and SHALL NOT write any `admin_actions_log` row.

#### Scenario: Non-UUID id on the ban route is rejected without a 500 or state change

- **GIVEN** an authenticated authorized session with a valid CSRF token
- **WHEN** the client sends `POST /admin/users/not-a-uuid/ban`
- **THEN** the response status SHALL NOT be 500 (it SHALL be a 4xx / inline error) AND no `users` row SHALL be mutated AND no `admin_actions_log` row SHALL be written

#### Scenario: Non-UUID id on the shadow-ban route is rejected without a 500 or state change

- **GIVEN** an authenticated authorized session with a valid CSRF token
- **WHEN** the client sends `POST /admin/users/not-a-uuid/shadow-ban`
- **THEN** the response status SHALL NOT be 500 (it SHALL be a 4xx / inline error) AND no `users` row SHALL be mutated AND no `admin_actions_log` row SHALL be written

### Requirement: Permanent ban and shadow ban are rate-limited; un-shadow-ban is not

Permanent ban and shadow ban are destructive actions and SHALL enforce the shared `admin-destructive-action-rate-limit` cap (counted toward the per-admin 20/trailing-hour budget); at or over the cap they SHALL be rejected with no mutation and no audit row, surfaced as an inline "quota exceeded" state (not a 5xx). Un-shadow-ban is restorative and SHALL NOT be counted or capped.

#### Scenario: A permanent ban at the cap is rejected without effect

- **GIVEN** an authenticated `owner`/`admin` with exactly 20 destructive-action rows in the trailing hour (valid CSRF) AND an eligible target
- **WHEN** the client sends `POST /admin/users/{id}/ban`
- **THEN** the response SHALL surface a "quota exceeded" state (not a 5xx) AND no `users` row SHALL be mutated AND no new `admin_actions_log` row SHALL be written

#### Scenario: Un-shadow-ban is allowed even at the cap

- **GIVEN** an authenticated write-role admin with 20 destructive-action rows in the trailing hour (valid CSRF) AND a shadow-banned target
- **WHEN** the client sends `POST /admin/users/{id}/shadow-unban`
- **THEN** the action SHALL apply normally (`is_shadow_banned = FALSE`) — the cap does not gate the restorative action

### Requirement: Each applied new action writes one immutable audit row attributed to the acting human admin

Each successfully applied new action SHALL write exactly one immutable `admin_actions_log` row attributed to the acting human admin (NEVER the `system` sentinel), with the distinct `action_type` `user_banned` (permanent ban), `user_shadow_banned` (shadow ban), or `user_shadow_unbanned` (un-shadow-ban), the relevant `before_state` / `after_state`, the forwarded client IP, and a NULL-tolerant `user_agent`. The admin-entered free-text reason (read from the form body after CSRF validation) SHALL be recorded in the audit row ONLY — never echoed to the offender. A rejected (ineligible / forbidden / no-op / over-cap) attempt SHALL write NO audit row.

#### Scenario: Permanent ban writes a user_banned row with before/after state

- **GIVEN** an authenticated `owner`/`admin` session (valid CSRF) AND an eligible target with `is_banned = FALSE`
- **WHEN** the client sends `POST /admin/users/{id}/ban`
- **THEN** exactly one `admin_actions_log` row SHALL be written with `action_type = 'user_banned'`, `before_state` reflecting the prior state, and `after_state` reflecting `is_banned = TRUE, suspended_until = NULL`

#### Scenario: Shadow ban writes a user_shadow_banned row attributed to the human admin

- **GIVEN** an authenticated write-role session (valid CSRF) AND an eligible target
- **WHEN** the client sends `POST /admin/users/{id}/shadow-ban`
- **THEN** exactly one `admin_actions_log` row SHALL be written with `action_type = 'user_shadow_banned'`, attributed to the acting admin's id (NOT the system sentinel)

#### Scenario: Un-shadow-ban writes a user_shadow_unbanned row

- **GIVEN** an authenticated write-role session (valid CSRF) AND a shadow-banned target
- **WHEN** the client sends `POST /admin/users/{id}/shadow-unban`
- **THEN** exactly one `admin_actions_log` row SHALL be written with `action_type = 'user_shadow_unbanned'`

#### Scenario: The audit row records the forwarded client IP and a NULL user_agent when omitted

- **GIVEN** an authenticated `owner`/`admin` session (valid CSRF) AND an eligible target, the request carrying a forwarded client IP and NO user-agent header
- **WHEN** the client sends `POST /admin/users/{id}/ban`
- **THEN** the `user_banned` audit row SHALL record the forwarded client IP AND a NULL `user_agent`

### Requirement: Permanent ban inserts a sanitized account_action_applied notification; shadow-ban and un-shadow-ban insert none

A successful permanent ban SHALL insert exactly one `account_action_applied` notification for the target, reusing the report-queue ban notification: its `body_data` carries the sanitized fixed action/reason code (NOT the admin's free-text reason) and `actor_user_id` is NULL. A successful shadow ban SHALL insert NO notification (invisible by design). A successful un-shadow-ban SHALL insert NO notification.

#### Scenario: Permanent ban inserts one sanitized notification

- **GIVEN** an authenticated `owner`/`admin` session (valid CSRF) AND an eligible target
- **WHEN** the client sends `POST /admin/users/{id}/ban`
- **THEN** exactly one `account_action_applied` notification SHALL be inserted for the target AND its `body_data` SHALL NOT contain the admin's free-text reason

#### Scenario: Shadow ban inserts no notification

- **GIVEN** an authenticated write-role session (valid CSRF) AND an eligible target
- **WHEN** the client sends `POST /admin/users/{id}/shadow-ban`
- **THEN** NO notification SHALL be inserted for the target

#### Scenario: Un-shadow-ban inserts no notification

- **GIVEN** an authenticated write-role session (valid CSRF) AND a shadow-banned target
- **WHEN** the client sends `POST /admin/users/{id}/shadow-unban`
- **THEN** NO notification SHALL be inserted for the target

### Requirement: The state change, its audit row, and its notification commit atomically

Each new action's `users` update, its `admin_actions_log` row, and (for permanent ban) its notification SHALL commit together in one transaction. If the audit insert (or, for ban, the notification insert) fails, the `users` update SHALL roll back so no partial state is observable.

#### Scenario: Audit-insert failure rolls back the ban

- **GIVEN** an authenticated `owner`/`admin` session (valid CSRF) AND an eligible target, with the audit insert forced to fail
- **WHEN** the client sends `POST /admin/users/{id}/ban`
- **THEN** the target's `is_banned` / `suspended_until` SHALL be unchanged (the `users` update rolled back) AND no notification SHALL persist

#### Scenario: Successful ban commits the update, the audit row, and the notification together

- **GIVEN** an authenticated `owner`/`admin` session (valid CSRF) AND an eligible target
- **WHEN** the client sends `POST /admin/users/{id}/ban`
- **THEN** the `users` update, the `user_banned` audit row, and the `account_action_applied` notification SHALL all be present after commit
