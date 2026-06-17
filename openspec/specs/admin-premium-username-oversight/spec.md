# admin-premium-username-oversight Specification

## Purpose

The admin **Premium Username Change Oversight** surface (`GET /admin/username-oversight`, admin mockup frame 22) is the moderation/oversight half of the `premium-username-customization` feature: it lets a solo operator triage the standing `username_flagged` moderation queue, browse the `username_history` audit trail read-only, and force-release a handle from its 30-day anti-impersonation hold — without raw SQL. Its **accept** resolution writes a per-candidate, one-shot approval to `username_flag_overrides` (V23) that the live `PATCH /api/v1/user/username` moderation gate consults and consumes (so an admin-approved borderline candidate passes on re-submit, never a per-user moderation bypass); **reject** confirms the automated block. Every write is CSRF- and write-role-gated, per-admin rate-limited via the audit-log-COUNT pattern (10/hour flag resolution, 5/hour hold release), and immutably audit-logged (`username_flag_resolved` / `username_hold_released`). The capability owns the `accept_flagged_username` / `reject_flagged_username` resolution values that `admin-report-queue` cedes; it adds one migration (`username_flag_overrides`) and reuses the existing admin auth, audit, and rate-limit substrate.

## Requirements
### Requirement: Authenticated GET /admin/username-oversight renders the oversight page

The system SHALL serve `GET /admin/username-oversight` as an authenticated route wired INSIDE the `authenticate(ADMIN_AUTH_NAME)` block established by `admin-login`, so the session middleware gates it. The read SHALL be available to any valid admin session (not role-restricted, matching `admin-report-queue` / `admin-reserved-usernames-editor`; `AdminPrincipal.role` is consumed only by the write actions). On a valid session it SHALL return HTTP 200 with an HTML page that extends the shared admin base layout (`admin-panel-scaffold`) and renders three sections: the flagged-candidate queue, the read-only `username_history` viewer, and the 30-day-hold list. Reads of `moderation_queue`, `username_history`, and `users` in the admin module are permitted (the admin module is exempt from the `visible_*`-view + block-exclusion lint).

#### Scenario: Authenticated request renders the three sections
- **GIVEN** an authenticated admin session
- **WHEN** the client sends `GET /admin/username-oversight` carrying a valid `__Host-admin_session` cookie
- **THEN** the response status SHALL be 200
- **AND** the response body SHALL be Pebble-rendered HTML containing the flagged-candidate queue, the `username_history` viewer, and the 30-day-hold sections
- **AND** the rendered HTML SHALL contain the base-layout structural sections (header, nav, footer)

#### Scenario: Unauthenticated request redirects to the login page
- **WHEN** a client sends `GET /admin/username-oversight` with no `__Host-admin_session` cookie (or an invalid / expired / revoked / idle-timed-out session)
- **THEN** the response status SHALL be 302
- **AND** the `Location` header SHALL be `/admin/login`
- **AND** no oversight content SHALL be served

#### Scenario: Empty data renders empty states with HTTP 200
- **GIVEN** an authenticated session AND no pending `username_flagged` rows, no `username_history` rows, and no rows under hold
- **WHEN** `GET /admin/username-oversight` is served
- **THEN** the response status SHALL be 200
- **AND** each section SHALL render an empty-state message (never a 404 or 500)

### Requirement: Flagged-candidate queue lists pending username_flagged moderation rows

The flagged-candidate section SHALL list `moderation_queue` rows where `trigger = 'username_flagged'` AND `status = 'pending'`, ordered newest-first (`created_at DESC, id DESC`). Because the `username_flagged` row carries `target_type = 'user'` and `target_id = <user id>` (per `premium-username-customization`), each rendered row SHALL display the flag's `created_at`, the flagged **candidate** handle (read from `moderation_queue.notes`, which the modified `premium-username-customization` gate now persists as the latest flagged candidate), and SHALL resolve `target_id` to the flagged user's `username` via a `LEFT JOIN users`, rendering a deep-link to `/admin/users?q=<user>`. The candidate value SHALL be HTML-escaped (it is admin-judged false-positive review material — `docs/06` — and is rendered in full, not masked, so the operator can judge it; the mockup-frame-22 masking is a deferred cosmetic). A row whose user has been hard-deleted (no matching `users` row) SHALL render the `target_id` as text WITHOUT a link and SHALL NOT error. The section SHALL NOT claim to display the matched wordlist entry (the "Hit" mockup column), because the flag row does not persist which list matched.

#### Scenario: A pending username flag renders with the candidate and a user deep-link
- **GIVEN** an authenticated session AND a `moderation_queue` row with `trigger = 'username_flagged'`, `status = 'pending'`, `target_type = 'user'`, `target_id = <U>`, `notes = 'borderlinehandle'` referencing a `users` row with `username = 'budi_kopi'`
- **WHEN** `GET /admin/username-oversight` is served
- **THEN** the flagged-candidate section SHALL render a row showing the flag's `created_at`, the candidate `borderlinehandle` (HTML-escaped), AND a link to `/admin/users?q=budi_kopi`

#### Scenario: Non-username triggers are excluded from the flagged-candidate queue
- **GIVEN** an authenticated session AND a `moderation_queue` row with `trigger = 'auto_hide_3_reports'` (a content trigger) and a row with `trigger = 'username_flagged'`, both `pending`
- **WHEN** `GET /admin/username-oversight` is served
- **THEN** the flagged-candidate section SHALL render only the `username_flagged` row (content triggers belong to `admin-report-queue`)

#### Scenario: Already-resolved username flags are excluded
- **GIVEN** an authenticated session AND a `username_flagged` row with `status = 'resolved'`
- **WHEN** `GET /admin/username-oversight` is served
- **THEN** that resolved row SHALL NOT appear in the (pending-only) flagged-candidate section

#### Scenario: A flag for a hard-deleted user renders without a link
- **GIVEN** an authenticated session AND a pending `username_flagged` row whose `target_id` has no matching `users` row (hard-deleted)
- **WHEN** `GET /admin/username-oversight` is served
- **THEN** the row SHALL render the `target_id` as text with NO deep-link and SHALL NOT error

### Requirement: The username_history viewer is read-only and searchable

The `username_history` section SHALL render rows ordered newest-first (`changed_at DESC, id DESC`), each showing `changed_at`, the `old_username → new_username` transition, and the owning user (resolved via `LEFT JOIN users`, deep-linked to `/admin/users?q=<user>`). It SHALL accept a case-insensitive substring search `q` matching either `old_username` (served by the V3 `username_history_old_lower_idx` `LOWER` index) or `new_username`. The viewer SHALL be strictly read-only: it SHALL NOT expose any control to edit a user's username directly (username changes are user-driven via `PATCH /api/v1/user/username`). All rendered handles and usernames SHALL be HTML-escaped.

#### Scenario: History rows render newest-first with the old→new transition
- **GIVEN** an authenticated session AND two `username_history` rows with strictly increasing `changed_at`
- **WHEN** `GET /admin/username-oversight` is served
- **THEN** the row with the later `changed_at` SHALL appear first AND each row SHALL show its `old_username → new_username`

#### Scenario: Search matches the old handle case-insensitively
- **GIVEN** an authenticated session AND a `username_history` row with `old_username = 'kopibudi'`
- **WHEN** `GET /admin/username-oversight?q=KOPIBUDI` is served
- **THEN** that history row SHALL appear in the results

#### Scenario: Search matches the new handle case-insensitively
- **GIVEN** an authenticated session AND a `username_history` row with `new_username = 'budi_kopi'`
- **WHEN** `GET /admin/username-oversight?q=BUDI_KOPI` is served
- **THEN** that history row SHALL appear in the results

#### Scenario: No direct username-edit control is rendered
- **GIVEN** an authenticated session AND at least one `username_history` row
- **WHEN** `GET /admin/username-oversight` is served
- **THEN** the `username_history` section SHALL render no form or control that edits a user's current username

### Requirement: The 30-day-hold list surfaces handles still under release hold

The 30-day-hold section SHALL list `username_history` rows whose `released_at > NOW()` (the old handle is still blocked from re-claim), ordered by `released_at ASC` (soonest auto-release first), each showing the held `old_username`, the owning user, the scheduled auto-release instant (`released_at`), and — for a write-capable admin — a "Release now" control posting to the manual-release endpoint. A handle whose `released_at <= NOW()` (hold already elapsed) SHALL NOT appear in this section.

#### Scenario: A handle under hold is listed with its auto-release date
- **GIVEN** an authenticated session AND a `username_history` row with `old_username = 'sore_rina'` and `released_at` 10 days in the future
- **WHEN** `GET /admin/username-oversight` is served
- **THEN** the 30-day-hold section SHALL list `sore_rina` with its `released_at` auto-release instant

#### Scenario: A handle whose hold has elapsed is excluded
- **GIVEN** an authenticated session AND a `username_history` row whose `released_at` is in the past
- **WHEN** `GET /admin/username-oversight` is served
- **THEN** that row SHALL NOT appear in the 30-day-hold section

### Requirement: Keyset pagination over each list with a fixed page size

The flagged-candidate queue SHALL paginate by a keyset cursor over `(created_at, id)` descending, and the `username_history` viewer over `(changed_at, id)` descending — both with a fixed page size and never SQL `OFFSET`. When more rows exist, an "older" navigation control SHALL carry an opaque cursor encoding the last-displayed row's key; following it SHALL return the next-older, non-overlapping page. A malformed or absent cursor SHALL be treated as a request for the first (newest) page, never an error.

#### Scenario: A page is capped and exposes an older control
- **GIVEN** an authenticated session AND more `username_history` rows than the fixed page size
- **WHEN** `GET /admin/username-oversight` is served with no cursor
- **THEN** the history list SHALL contain exactly the page-size number of rows AND an "older" control SHALL be present

#### Scenario: Following the cursor returns the next-older, non-overlapping page
- **GIVEN** an authenticated session AND two full pages of `username_history` rows
- **WHEN** the client follows the history "older" control's cursor
- **THEN** the returned rows SHALL all be strictly older than the first page's last row AND no first-page row SHALL reappear

#### Scenario: Malformed cursor falls back to the first page
- **WHEN** an authenticated client sends `GET /admin/username-oversight?history_cursor=not-a-valid-cursor`
- **THEN** the response status SHALL be 200 AND the history list SHALL show the newest page (the malformed cursor is ignored, not an error)

### Requirement: All rendered values are HTML-escaped

The system SHALL HTML-escape every dynamic value rendered into the oversight page — usernames, old/new handles, and any joined display strings. It SHALL NOT render any dynamic value as raw/unescaped HTML.

#### Scenario: A username containing markup is escaped
- **GIVEN** an authenticated session AND a `username_history` row whose `old_username` contains `<script>alert(1)</script>` (constructed for the test)
- **WHEN** `GET /admin/username-oversight` is served
- **THEN** the rendered HTML SHALL contain the escaped form (e.g. `&lt;script&gt;`) and SHALL NOT contain an executable `<script>` element from that value

### Requirement: HTMX partial-swap with a plain-GET fallback

The system SHALL return only the swappable result-fragment element (no full-page `<html>` wrapper, no base-layout header/footer) when the request carries `HX-Request: true`, and SHALL return the full page extending the base layout for a plain `GET`. Pagination, search, and the resolution / release controls SHALL function under both modes.

#### Scenario: HTMX request returns only the result fragment
- **GIVEN** an authenticated admin session
- **WHEN** the client sends `GET /admin/username-oversight` with header `HX-Request: true`
- **THEN** the response status SHALL be 200 AND the body SHALL contain the swappable result fragment AND SHALL NOT contain the full-page `<html>` wrapper or the base-layout header/footer

#### Scenario: Plain GET returns the full page
- **GIVEN** an authenticated admin session
- **WHEN** the client sends `GET /admin/username-oversight` with NO `HX-Request` header
- **THEN** the body SHALL contain the full-page document extending the base layout (header, nav, footer)

### Requirement: The GET /admin/username-oversight listing never mutates state

Serving `GET /admin/username-oversight` (with any search or cursor parameters) SHALL NOT write any `admin_actions_log` row and SHALL NOT mutate any table — the listing is strictly read-only. State mutations SHALL be confined to the dedicated POST sub-routes (`/flags/{queue_id}/resolve` and `/holds/{history_id}/release`).

#### Scenario: Serving the listing writes no audit row and mutates nothing
- **GIVEN** an authenticated session AND a known count of `admin_actions_log` rows
- **WHEN** `GET /admin/username-oversight` is served (with and without search/cursor)
- **THEN** the `admin_actions_log` row count SHALL be unchanged AND no `moderation_queue` / `username_history` / `users` row SHALL be inserted, updated, or deleted

### Requirement: Authenticated flag-resolution endpoint records the admin decision

The system SHALL serve `POST /admin/username-oversight/flags/{queue_id}/resolve` as an authenticated route wired INSIDE `authenticate(ADMIN_AUTH_NAME)`. On a valid session with a valid CSRF token and a write-capable admin role, it SHALL accept a `resolution` form field constrained server-side to `{accept_flagged_username, reject_flagged_username}` and, in ONE database transaction, conditionally update the target `moderation_queue` row (only when its `status = 'pending'` AND `trigger = 'username_flagged'`) to `status = 'resolved'` + the chosen `resolution` + `resolved_by = <acting admin id>` + `resolved_at = NOW()`, and write exactly one `admin_actions_log` row with `action_type = 'username_flag_resolved'` whose `after_state` records the chosen `resolution`. When the resolution is `accept_flagged_username`, the same transaction SHALL additionally write a per-candidate override into `username_flag_overrides` for `(user_id = the flag's target_id, candidate = the flag row's notes, normalized lowercase)` via `INSERT … ON CONFLICT (user_id, candidate) DO UPDATE SET approved_by = <acting admin id>, approved_at = NOW(), consumed_at = NULL` (re-arming a previously-consumed approval); `reject_flagged_username` SHALL write NO override row. A `queue_id` referencing a row whose `trigger` is not `username_flagged` SHALL be rejected with no mutation (that target belongs to `admin-report-queue`'s endpoint). When the flag row has no usable `notes` candidate (e.g. a legacy pre-V23 flag), an `accept_flagged_username` SHALL still resolve the queue row and audit, but SHALL write no override (there is no candidate to approve); this case SHALL surface an in-band note and SHALL NOT 5xx.

#### Scenario: Accept resolves the flag and writes a per-candidate override
- **GIVEN** an authenticated write-role admin with a valid CSRF token AND a pending `username_flagged` queue row for user `<U>` with `notes = 'borderlinehandle'`
- **WHEN** the client sends `POST /admin/username-oversight/flags/{queue_id}/resolve` with `resolution=accept_flagged_username`
- **THEN** that queue row's `status` SHALL become `resolved` AND `resolution` SHALL be `accept_flagged_username` AND `resolved_by` + `resolved_at` SHALL be set
- **AND** a `username_flag_overrides` row SHALL exist for `(user_id = <U>, candidate = 'borderlinehandle')` with `approved_by` set and `consumed_at IS NULL`
- **AND** exactly one `admin_actions_log` row with `action_type = 'username_flag_resolved'` SHALL be written whose `after_state` records `resolution = accept_flagged_username`

#### Scenario: Reject resolves the flag confirming the block
- **GIVEN** an authenticated write-role admin with a valid CSRF token AND a pending `username_flagged` queue row
- **WHEN** the client sends `resolution=reject_flagged_username`
- **THEN** that queue row's `status` SHALL become `resolved` AND `resolution` SHALL be `reject_flagged_username` AND exactly one `username_flag_resolved` audit row SHALL be written

#### Scenario: Resolving a non-username-flagged queue row is rejected
- **GIVEN** an authenticated write-role admin with a valid CSRF token AND a `moderation_queue` row whose `trigger = 'auto_hide_3_reports'`
- **WHEN** the client posts a resolution to `/admin/username-oversight/flags/{that_id}/resolve`
- **THEN** the request SHALL be rejected with no `moderation_queue` mutation and no audit row (the row is out of this endpoint's scope)

### Requirement: An accept override is scoped to the approved candidate, never a per-user moderation bypass

A `username_flag_overrides` approval SHALL whitelist exactly the approved candidate handle for exactly the flagged user — it SHALL be keyed `(user_id, candidate)` with the candidate stored normalized (lowercased), and it SHALL be one-shot (consumed on the first successful change to that handle). Accepting a flag SHALL NOT grant the user any blanket exemption from username moderation: a subsequently-submitted, still-flagged candidate that does NOT match an unconsumed approval SHALL be rejected by the live gate exactly as before. The override SHALL NOT mutate `users` and SHALL NOT alter any other gate (reserved, release-hold, uniqueness, 30-day cooldown, charset) — it only suppresses the profanity/UU-ITE rejection for the one approved handle.

#### Scenario: An override whitelists only the approved candidate
- **GIVEN** an accepted flag for user `<U>` that wrote a `username_flag_overrides` row for candidate `'borderlinehandle'`
- **WHEN** `<U>` submits a DIFFERENT still-flagged candidate `'anotherbadword'`
- **THEN** the live moderation gate SHALL reject `'anotherbadword'` (no matching approval) exactly as before — the accept did NOT grant a per-user bypass

#### Scenario: The override is consumed on first successful use
- **GIVEN** an unconsumed `username_flag_overrides` row for `(user_id = <U>, candidate = 'borderlinehandle')`
- **WHEN** `<U>` successfully changes their username to `borderlinehandle`
- **THEN** that override row's `consumed_at` SHALL be set (the live gate consumes it inside the successful-change transaction), so it cannot grant a second free pass

### Requirement: Authenticated manual-release endpoint force-releases a held handle

The system SHALL serve `POST /admin/username-oversight/holds/{history_id}/release` as an authenticated route wired INSIDE `authenticate(ADMIN_AUTH_NAME)`. On a valid session with a valid CSRF token and a write-capable admin role, it SHALL, in ONE database transaction, conditionally update the target `username_history` row (only when its `released_at > NOW()`) to `released_at = NOW()`, and write exactly one `admin_actions_log` row with `action_type = 'username_hold_released'` whose `before_state` records the prior `released_at`. After a successful release the held `old_username` SHALL be immediately claimable (the `premium-username-customization` release-hold check `released_at > NOW()` no longer matches it).

#### Scenario: Release clears the hold and is audit-logged
- **GIVEN** an authenticated write-role admin with a valid CSRF token AND a `username_history` row with `old_username = 'sore_rina'` whose `released_at` is in the future
- **WHEN** the client sends `POST /admin/username-oversight/holds/{history_id}/release`
- **THEN** that row's `released_at` SHALL become `NOW()` (no longer `> NOW()`) AND exactly one `admin_actions_log` row with `action_type = 'username_hold_released'` whose `before_state` records the prior `released_at` SHALL be written

#### Scenario: Releasing an already-released hold is a safe no-op
- **GIVEN** an authenticated write-role admin with a valid CSRF token AND a `username_history` row whose `released_at <= NOW()` (hold already elapsed) AND a known `admin_actions_log` count
- **WHEN** the client posts a release for that row
- **THEN** no `username_history` mutation SHALL occur AND no `admin_actions_log` row SHALL be written AND no error SHALL be returned (the conditional `UPDATE … WHERE released_at > NOW()` affects zero rows)

### Requirement: Write endpoints are CSRF- and write-role-gated in order

Both write endpoints SHALL validate the CSRF token FIRST (a missing or mismatched token returns 403, writes an `admin_csrf_violation` audit row, and performs no mutation), THEN require a write role (a read-only admin role returns 403 with no mutation), THEN parse and validate the target/body. An unauthenticated (or expired/revoked/idle-timed-out) request SHALL redirect 302 to `/admin/login` and write nothing. CSRF SHALL be validated BEFORE the role gate.

#### Scenario: A write without a valid CSRF token is rejected and audited
- **WHEN** a write `POST` (flag-resolve or hold-release) is made without a valid CSRF token
- **THEN** the response SHALL be 403 AND one `admin_actions_log` row with `action_type = 'admin_csrf_violation'` SHALL be written AND no `moderation_queue` / `username_history` row SHALL be mutated

#### Scenario: A read-only-role admin is rejected on a write route
- **GIVEN** an admin whose role lacks write permission, with a valid CSRF token
- **WHEN** they attempt a flag-resolve or hold-release
- **THEN** the response SHALL be 403 AND no `moderation_queue` / `username_history` row SHALL be mutated

#### Scenario: CSRF is checked before the role gate
- **GIVEN** an authenticated `read_only` admin WITHOUT a valid CSRF token
- **WHEN** they send a write `POST`
- **THEN** the rejection SHALL be the CSRF rejection (403 + `admin_csrf_violation`), demonstrating CSRF is evaluated before the role gate

#### Scenario: An unauthenticated write request redirects and writes nothing
- **WHEN** a client sends a write `POST` with no valid `__Host-admin_session` cookie
- **THEN** the response SHALL be 302 with `Location: /admin/login` AND no `moderation_queue` / `username_history` / `admin_actions_log` row SHALL be written

### Requirement: Flag resolution is rate-limited at 10 per trailing hour per admin

The system SHALL enforce a per-acting-admin cap of 10 `username_flag_resolved` actions per trailing one-hour window, sourced by COUNT over `admin_actions_log` for the acting admin within `NOW() - INTERVAL '1 hour'` (the audit-log-COUNT pattern, mirroring `admin-reserved-usernames-editor`), checked inside the same transaction as the gated write (soft cap, ±1 concurrency tolerance accepted). A resolution at or over the cap SHALL be rejected in-band ("quota exceeded (10/hour)") with no `moderation_queue` mutation and no audit row, never a 5xx. The cap SHALL be per-admin and SHALL count only `username_flag_resolved` rows in-window.

#### Scenario: A resolution under the cap proceeds
- **GIVEN** an admin with 9 `username_flag_resolved` rows in the trailing hour AND a pending `username_flagged` queue row
- **WHEN** they resolve it
- **THEN** the action SHALL apply normally AND one new `username_flag_resolved` audit row SHALL be written (bringing the trailing-hour count to 10)

#### Scenario: A resolution at the cap is rejected without effect
- **GIVEN** an admin with 10 `username_flag_resolved` rows in the trailing hour AND a pending `username_flagged` queue row
- **WHEN** they attempt another resolution
- **THEN** the response SHALL surface "quota exceeded (10/hour)" AND the queue row SHALL stay `pending` AND no new `admin_actions_log` row SHALL be written

#### Scenario: The cap is per-admin and counts only its action type
- **GIVEN** admin A at the cap (10 `username_flag_resolved` in-window) and admin B with 0
- **WHEN** admin B resolves a pending flag
- **THEN** admin B's resolution SHALL apply normally (admin A's exhausted quota does not block admin B)

### Requirement: Manual release is rate-limited at 5 per trailing hour per admin

The system SHALL enforce a per-acting-admin cap of 5 `username_hold_released` actions per trailing one-hour window, sourced by the same audit-log-COUNT pattern over `admin_actions_log`, checked inside the same transaction as the release. A release at or over the cap SHALL be rejected in-band ("quota exceeded (5/hour)") with no `username_history` mutation and no audit row, never a 5xx. The cap SHALL be per-admin and SHALL count only `username_hold_released` rows in-window.

#### Scenario: A release under the cap proceeds
- **GIVEN** an admin with 4 `username_hold_released` rows in the trailing hour AND a held `username_history` row
- **WHEN** they release it
- **THEN** the action SHALL apply normally AND one new `username_hold_released` audit row SHALL be written (bringing the trailing-hour count to 5)

#### Scenario: A release at the cap is rejected without effect
- **GIVEN** an admin with 5 `username_hold_released` rows in the trailing hour AND a held `username_history` row
- **WHEN** they attempt another release
- **THEN** the response SHALL surface "quota exceeded (5/hour)" AND the row's `released_at` SHALL be unchanged AND no new `admin_actions_log` row SHALL be written

### Requirement: Mutation and audit are atomic; malformed and repeat inputs are tolerated and idempotent

For every write, the `moderation_queue` / `username_history` mutation, any `username_flag_overrides` write (on accept), and the `admin_actions_log` row SHALL commit or roll back together in one transaction — no observable partial state (no override or resolution without its audit row, no audit row without the state change). The endpoints SHALL tolerate bad input without a 5xx: a malformed path `{queue_id}` / `{history_id}` (not a UUID) SHALL return 400 with no write; a `resolution` value outside the server-side allowlist SHALL be rejected BEFORE any DB write (never relying on a DB `CHECK` to throw a 5xx); re-resolving an already-resolved flag (or re-releasing an already-released hold) SHALL be a safe no-op — the conditional `UPDATE … WHERE status = 'pending'` / `WHERE released_at > NOW()` affects zero rows, so no state change, no duplicate audit row, and no error (this also serializes the two-admins-act-on-the-same-row race — the loser is a no-op).

#### Scenario: An audit-write failure rolls back the resolution
- **GIVEN** a pending `username_flagged` queue row AND the audit insert is made to fail (fault injection)
- **WHEN** the client resolves it with `resolution=reject_flagged_username`
- **THEN** the transaction SHALL roll back: the queue row SHALL remain `pending` AND no `admin_actions_log` row SHALL be written

#### Scenario: Malformed id yields 400 with no write
- **WHEN** an authenticated write-role admin sends `POST /admin/username-oversight/flags/not-a-uuid/resolve` with a valid CSRF token
- **THEN** the response SHALL be 400 AND no `moderation_queue` / `admin_actions_log` row SHALL be written

#### Scenario: Out-of-allowlist resolution is rejected before any DB write
- **GIVEN** a pending `username_flagged` queue row
- **WHEN** an authenticated write-role admin posts `resolution=ban_author` (or non-enum garbage) with a valid CSRF token
- **THEN** the value SHALL be rejected by the server-side allowlist with no `moderation_queue` mutation and no audit row AND the response SHALL NOT be a 5xx

#### Scenario: Re-resolving an already-resolved flag is a safe no-op
- **GIVEN** a `username_flagged` queue row already in `status = 'resolved'` AND a known `admin_actions_log` count
- **WHEN** an authenticated write-role admin posts another resolution for it with a valid CSRF token
- **THEN** no second `admin_actions_log` row SHALL be written (count unchanged) AND the existing `resolution` / `resolved_by` SHALL be unchanged AND no error SHALL be returned

