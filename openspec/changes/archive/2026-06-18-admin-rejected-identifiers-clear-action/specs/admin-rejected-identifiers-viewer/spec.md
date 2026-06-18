## RENAMED Requirements

- FROM: `### Requirement: The manual support-clear action is deferred to a fast-follow change`
- TO: `### Requirement: The manual support-clear action is implemented (owner/admin)`

- FROM: `### Requirement: The capability adds only read routes; mutation methods are unmapped`
- TO: `### Requirement: The collection path is read-only; mutation is confined to the clear sub-route`

## MODIFIED Requirements

### Requirement: The manual support-clear action is implemented (owner/admin)

The manual support-clear action — the admin removal of a `rejected_identifiers` row so a falsely-rejected legitimate adult can re-verify (the "purgeable via legitimate adult re-verification workflow" path in [`docs/05-Implementation.md`](../../../docs/05-Implementation.md)) — is **no longer deferred**. The system SHALL provide it in this change as `POST /admin/rejected-identifiers/{id}/clear`, governed by the clear-action requirements ADDED below (endpoint + audit, CSRF + owner/admin role gating, required reason, idempotent not-found, dedicated rate-limit, atomic delete+audit, and the per-row HTMX control). The action SHALL be **role-gated to owner/admin, CSRF-gated, and audit-logged** (one immutable `admin_actions_log` row, `action_type = 'rejected_identifier_cleared'`, the cleared row preserved in `before_state`) and **rate-limited** per admin. It replaces the prior out-of-band raw-SQL clear path. This requirement supersedes the prior deferral; tracking issue [#190](https://github.com/aditrioka/nearyou-id/issues/190) is resolved by this change.

#### Scenario: The clear action is wired (no longer deferred)

- **GIVEN** an authenticated owner/admin session
- **WHEN** the admin opens `GET /admin/rejected-identifiers` AND a `rejected_identifiers` row exists
- **THEN** the rendered page SHALL present a per-row clear control (inverting the prior "no clear / remove control is wired") AND a `POST /admin/rejected-identifiers/{id}/clear` route SHALL be wired to remove that row (per the clear-action requirements added by this change)

### Requirement: The collection path is read-only; mutation is confined to the clear sub-route

The bare collection path `/admin/rejected-identifiers` SHALL expose only `GET` — it SHALL NOT wire any `POST`, `PUT`, `PATCH`, or `DELETE` handler, and serving it (with any filter or cursor parameters) SHALL write no `admin_actions_log` row and mutate no table. All mutation on this capability SHALL be confined to the dedicated clear sub-route `POST /admin/rejected-identifiers/{id}/clear` introduced by this change; the listing itself remains strictly read-only.

#### Scenario: POST on the bare collection path is not wired

- **GIVEN** an authenticated session (so the request passes the auth gate)
- **WHEN** the client sends `POST /admin/rejected-identifiers` (the bare collection path, not a `/{id}/clear` sub-route)
- **THEN** the response status SHALL be 405 Method Not Allowed (only `GET` is wired on the collection path; mutation is served by the `/{id}/clear` sub-route)

#### Scenario: Serving the listing writes no audit row and mutates nothing

- **GIVEN** an authenticated session AND a known count N of rows in `rejected_identifiers` AND a known count M of rows in `admin_actions_log`
- **WHEN** the client sends `GET /admin/rejected-identifiers` (one or more times, with and without filters/cursor)
- **THEN** the count of rows in `rejected_identifiers` SHALL remain N
- **AND** the count of rows in `admin_actions_log` SHALL remain M (viewing the list is not itself an auditable action)

## ADDED Requirements

### Requirement: Authenticated clear endpoint removes the row and writes one audit row

The system SHALL serve `POST /admin/rejected-identifiers/{id}/clear` as an authenticated route wired INSIDE the `authenticate(ADMIN_AUTH_NAME)` block. On a valid session with a valid CSRF token, an owner/admin role, and a non-blank reason, it SHALL, in ONE database transaction, hard-`DELETE` the `rejected_identifiers` row identified by `{id}` and write exactly one `admin_actions_log` row with `action_type = 'rejected_identifier_cleared'`, `target_type = 'rejected_identifier'`, `target_id = {id}`, the acting admin's id, the admin-supplied `reason`, and a `before_state` JSONB capturing the cleared row's `identifier_hash`, `identifier_type`, `reason`, and `rejected_at` (`after_state` is null — the row is gone). On success the `rejected_identifiers` count SHALL drop by exactly one and exactly one new `admin_actions_log` row SHALL be written.

#### Scenario: A clear removes the row and writes one audit row with before_state

- **GIVEN** an authenticated owner/admin session with a valid CSRF token AND a `rejected_identifiers` row with id `R` (`identifier_hash = H`, `reason = age_under_18`)
- **WHEN** the client sends `POST /admin/rejected-identifiers/R/clear` with a non-blank `reason`
- **THEN** the `rejected_identifiers` row `R` SHALL no longer exist
- **AND** exactly one `admin_actions_log` row SHALL be written with `action_type = 'rejected_identifier_cleared'`, `target_type = 'rejected_identifier'`, `target_id = R`, the acting admin's id, and the supplied reason
- **AND** that row's `before_state` SHALL capture all four cleared-row fields (`identifier_hash = H`, `identifier_type`, `reason = age_under_18`, `rejected_at`) AND its `after_state` SHALL be null

#### Scenario: A cleared identifier can be re-rejected on a later signup attempt

- **GIVEN** a `rejected_identifiers` row for `(identifier_hash = H, identifier_type = google)` that has just been cleared
- **WHEN** the age gate later writes a fresh rejection for the same `(H, google)` (e.g. the same still-under-18 identity retries)
- **THEN** the insert SHALL succeed (the `UNIQUE (identifier_hash, identifier_type)` no longer conflicts — the prior row is gone) AND the row SHALL reappear in the viewer (the clear is not a permanent allowlist)

### Requirement: The clear action is session-, CSRF-, and owner/admin-role-gated, in order

The clear endpoint SHALL enforce, in order, the `admin-login` session gate, then CSRF validation, then the role gate. An unauthenticated (or expired / revoked / idle-timed-out) request SHALL redirect 302 to `/admin/login` and write nothing. A request whose `X-CSRF-Token` is missing or does not match `admin_sessions.csrf_token_hash` SHALL return 403, emit an `admin_csrf_violation` audit entry, and perform no clear. An authenticated admin whose role is NOT `owner` or `admin` (this explicitly INCLUDES `moderator` and `read_only`) SHALL be rejected with no mutation and no `rejected_identifier_cleared` audit row. CSRF SHALL be validated BEFORE the role gate. (The role gate for this write is intentionally stricter than the any-admin-role READ view — clearing weakens an anti-abuse control, so it matches the owner/admin tier of permanent-ban / chat-redaction.)

#### Scenario: Unauthenticated clear request redirects and writes nothing

- **WHEN** a client sends `POST /admin/rejected-identifiers/{id}/clear` with no valid `__Host-admin_session` cookie
- **THEN** the response status SHALL be 302 with `Location: /admin/login`
- **AND** no `rejected_identifiers` row SHALL be deleted AND no `admin_actions_log` row SHALL be written

#### Scenario: Missing or invalid CSRF token is rejected and audited

- **GIVEN** an authenticated owner/admin session
- **WHEN** the client sends a clear `POST` without a valid CSRF token
- **THEN** the response status SHALL be 403 AND an `admin_csrf_violation` audit entry SHALL be recorded AND no `rejected_identifiers` row SHALL be deleted

#### Scenario: CSRF is validated before the role gate

- **GIVEN** an authenticated `read_only` admin
- **WHEN** the client sends a clear `POST` WITHOUT a valid CSRF token
- **THEN** the rejection SHALL be the CSRF rejection (403 + `admin_csrf_violation`), demonstrating CSRF is evaluated before the role gate, AND no write SHALL occur

#### Scenario: A moderator is rejected by the role gate

- **GIVEN** an authenticated `moderator`-role admin with a valid CSRF token AND an existing `rejected_identifiers` row
- **WHEN** the client sends `POST /admin/rejected-identifiers/{id}/clear` with a non-blank reason
- **THEN** the request SHALL be rejected (the clear is owner/admin-only) AND the row SHALL still exist AND no `rejected_identifier_cleared` audit row SHALL be written

#### Scenario: A read_only admin is rejected by the role gate

- **GIVEN** an authenticated `read_only` admin with a valid CSRF token AND an existing `rejected_identifiers` row
- **WHEN** the client sends the clear `POST` with a non-blank reason
- **THEN** the request SHALL be rejected AND no `rejected_identifiers` row SHALL be deleted

### Requirement: A clear requires a non-blank, length-bounded reason

The clear endpoint SHALL require a `reason` form field that is non-blank (not empty / not whitespace-only) and within a bounded length. A blank/whitespace-only reason OR an over-length reason SHALL be rejected with NO delete and NO audit row (validated server-side before any DB write), surfaced as an inline validation message rather than a 5xx.

#### Scenario: Blank reason is rejected with no write

- **GIVEN** an authenticated owner/admin session with a valid CSRF token AND an existing `rejected_identifiers` row
- **WHEN** the client sends the clear `POST` with an empty or whitespace-only `reason`
- **THEN** the request SHALL be rejected with an inline validation message (not a 5xx) AND the row SHALL still exist AND no `admin_actions_log` row SHALL be written

#### Scenario: Over-length reason is rejected with no write

- **GIVEN** an authenticated owner/admin session with a valid CSRF token AND an existing `rejected_identifiers` row
- **WHEN** the client sends the clear `POST` with a `reason` longer than the bounded length
- **THEN** the request SHALL be rejected with no delete and no `admin_actions_log` row (not a 5xx)

### Requirement: Clearing a nonexistent or already-cleared identifier is a safe no-op

The clear endpoint SHALL tolerate bad / stale ids without a 5xx and SHALL be idempotent. A malformed path `{id}` (not a UUID) SHALL return 400 with no write. Clearing an `{id}` that does not exist (never existed, or was already cleared by a prior request or a concurrent admin) SHALL perform no delete, write no `admin_actions_log` row, and return a graceful "already removed / not found" inline state rather than a 5xx — serializing the two-admins-clear-the-same-row race so the loser is a harmless no-op.

#### Scenario: Malformed id yields 400 with no write

- **GIVEN** an authenticated owner/admin session with a valid CSRF token
- **WHEN** the client sends `POST /admin/rejected-identifiers/not-a-uuid/clear` with a non-blank reason
- **THEN** the response status SHALL be 400 AND no `rejected_identifiers` / `admin_actions_log` row SHALL be written

#### Scenario: Clearing a nonexistent id is a graceful no-op

- **GIVEN** an authenticated owner/admin session with a valid CSRF token AND an `{id}` that matches no `rejected_identifiers` row
- **WHEN** the client sends the clear `POST` with a non-blank reason AND a known count M of `admin_actions_log` rows
- **THEN** no row SHALL be deleted AND the `admin_actions_log` count SHALL remain M AND the response SHALL be a graceful "already removed / not found" state (not a 5xx)

### Requirement: The clear action is rate-limited per admin at 10 per trailing hour

The system SHALL enforce a dedicated cap of **10 clears per acting admin per trailing one-hour window**, counted from the immutable `admin_actions_log` audit trail (the audit trail is the rate-limit ledger — no second source of truth): rows for the acting `admin_id` with `action_type = 'rejected_identifier_cleared'` and `created_at > NOW() - INTERVAL '1 hour'`. The count SHALL be read inside the same JDBC transaction as the gated clear (a soft abuse-prevention cap with ±1 concurrency tolerance, NOT a hard authorization boundary — it SHALL NOT take a `FOR UPDATE` lock on the ledger). A clear attempt at or over the cap SHALL be rejected with NO delete and NO audit row, surfaced as an inline "quota exceeded" state (never a 5xx). A clear under the cap SHALL proceed normally and, by writing its own audit row, advance the count by one. The cap SHALL be per-admin — one admin reaching the cap SHALL NOT block a different admin. (This is a dedicated cap, NOT the shared `admin-destructive-action-rate-limit`, whose set is user-punitive actions only — see `design.md` D1.)

#### Scenario: A clear under the cap proceeds and advances the count

- **GIVEN** an authenticated owner/admin with 9 `rejected_identifier_cleared` rows in the trailing hour AND an existing `rejected_identifiers` row
- **WHEN** that admin performs a clear with a valid CSRF token and non-blank reason
- **THEN** the clear SHALL apply AND exactly one new `rejected_identifier_cleared` audit row SHALL be written (bringing the trailing-hour count to 10)

#### Scenario: A clear at the cap is rejected without effect

- **GIVEN** an authenticated owner/admin with exactly 10 `rejected_identifier_cleared` rows in the trailing hour AND an existing `rejected_identifiers` row
- **WHEN** that admin attempts an 11th clear with a valid CSRF token and non-blank reason
- **THEN** the response SHALL surface a "quota exceeded" state (not a 5xx) AND the row SHALL still exist AND no new `admin_actions_log` row SHALL be written (the count stays 10)

#### Scenario: The cap counts only in-window clear actions for the acting admin

- **GIVEN** an authenticated owner/admin with, in the last hour, 2 `rejected_identifier_cleared` rows AND 5 `user_suspended` rows, plus 9 `rejected_identifier_cleared` rows OLDER than one hour
- **WHEN** that admin's clear-count is computed
- **THEN** the count SHALL be 2 (only in-window `rejected_identifier_cleared` rows count — other action types and out-of-window rows are excluded)

#### Scenario: The cap is per-admin

- **GIVEN** admin A with 10 `rejected_identifier_cleared` rows in the trailing hour AND admin B with 0
- **WHEN** admin B (owner/admin) performs a clear with a valid CSRF token and non-blank reason
- **THEN** admin B's clear SHALL apply normally (admin A's exhausted quota does not block admin B)

### Requirement: Clear, audit, and rate-check are atomic

For every clear, the `rejected_identifiers` `DELETE`, the `admin_actions_log` insert, and the in-transaction rate-count read SHALL commit or roll back together in one transaction. There SHALL be no observable partial state — no delete without its audit row, and no audit row without the delete.

#### Scenario: An audit-write failure rolls back the delete

- **GIVEN** an authenticated owner/admin session, an existing `rejected_identifiers` row, AND the `admin_actions_log` insert is made to fail (fault injection, as in the `admin-user-moderation` rollback tests)
- **WHEN** the client sends a valid clear `POST`
- **THEN** the transaction SHALL roll back: the `rejected_identifiers` row SHALL still exist AND no `admin_actions_log` row SHALL be written

### Requirement: The per-row clear control renders escaped, HTMX-partial with a no-JS fallback, for owner/admin only

The rejected-identifiers table SHALL render a per-row clear control — a form carrying the session CSRF token plus a required reason input that posts to `POST /admin/rejected-identifiers/{id}/clear` — ONLY for sessions whose role is `owner` or `admin`. A `moderator` or `read_only` session SHALL see NO clear control (the read view still renders for them, per the unchanged any-role read requirement). Because the clear takes effect immediately and is destructive (the row is hard-deleted), the control SHALL make its destructive nature clear (a confirm affordance / explicit label). Every dynamic value rendered into the control SHALL be HTML-escaped. An `HX-Request: true` clear SHALL return the swappable table fragment with the cleared row removed; a successful no-JS (plain `POST`) clear SHALL 303-redirect back to the (filter-preserving) `/admin/rejected-identifiers` listing.

#### Scenario: A clear control is rendered for an owner/admin session

- **GIVEN** an authenticated `owner`-role (or `admin`-role) session AND a `rejected_identifiers` row
- **WHEN** `GET /admin/rejected-identifiers` is served
- **THEN** that row SHALL render a clear control (with a CSRF hidden field and a reason input) posting to `POST /admin/rejected-identifiers/{id}/clear`

#### Scenario: No clear control is rendered for a read_only or moderator session

- **GIVEN** an authenticated session whose role is `read_only` (or `moderator`) AND a `rejected_identifiers` row
- **WHEN** `GET /admin/rejected-identifiers` is served
- **THEN** the response status SHALL be 200 (the read view still renders) AND no clear / remove control SHALL be present for any row

#### Scenario: A successful HTMX clear removes the row from the rendered fragment

- **GIVEN** an authenticated owner/admin session submitting a clear with header `HX-Request: true` and a valid CSRF token and non-blank reason
- **WHEN** the clear succeeds
- **THEN** the response SHALL be the table fragment (or row-swap) from which the cleared row is absent

#### Scenario: A successful no-JS clear redirects back to the listing

- **GIVEN** an authenticated owner/admin submitting a plain `POST` (no `HX-Request` header) clear with a valid CSRF token and non-blank reason
- **WHEN** the clear succeeds
- **THEN** the response SHALL be a 303 redirect back to `/admin/rejected-identifiers` (preserving any active filters)

#### Scenario: Rendered clear-control values are HTML-escaped

- **GIVEN** an authenticated owner/admin session AND a `rejected_identifiers` row whose `identifier_hash` has been set (via a test fixture) to the literal string `<script>alert(1)</script>`
- **WHEN** `GET /admin/rejected-identifiers` is served with the clear controls
- **THEN** the response body SHALL contain the escaped form (e.g. `&lt;script&gt;`) and SHALL NOT contain a live, unescaped `<script>alert(1)</script>` element
