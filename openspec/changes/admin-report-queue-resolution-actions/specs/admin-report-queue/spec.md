## RENAMED Requirements

- FROM: `### Requirement: Report resolution write-back and the edit-history filter are explicitly deferred`
- TO: `### Requirement: The post-edit-history prioritization filter is deferred`

- FROM: `### Requirement: The Report Queue route is strictly read-only`
- TO: `### Requirement: The GET /admin/reports listing never mutates state`

## MODIFIED Requirements

### Requirement: The post-edit-history prioritization filter is deferred

This capability SHALL continue to defer the "post has edit history" prioritization filter to the separate change `admin-report-queue-has-edit-history-filter`. The `GET /admin/reports` listing SHALL ignore a `has_edit_history` parameter (no edit-history filtering applied) and respond 200. The report-resolution write-back that was previously bundled into this deferral is NO LONGER deferred — it ships in this change (see the resolution requirements added by `admin-report-queue-resolution-actions`), so this requirement now governs only the edit-history filter.

#### Scenario: The edit-history prioritization filter is absent
- **WHEN** `GET /admin/reports?has_edit_history=true` is served
- **THEN** the parameter SHALL be ignored (no edit-history filtering is applied) AND the response SHALL be 200

### Requirement: The GET /admin/reports listing never mutates state

Serving `GET /admin/reports` (with any filter or cursor parameters) SHALL NOT write any `admin_actions_log` row and SHALL NOT mutate any table — the listing is strictly read-only. State mutations on this capability SHALL be confined to the dedicated POST resolution sub-routes (`POST /admin/reports/{id}/resolve` and `POST /admin/moderation-queue/{id}/resolve`) introduced by this change; the bare collection path `/admin/reports` SHALL expose only `GET`.

#### Scenario: Serving the listing writes no audit row and mutates nothing
- **GIVEN** an authenticated session AND a known count of `admin_actions_log` rows
- **WHEN** `GET /admin/reports` is served (with and without filters/cursor)
- **THEN** the `admin_actions_log` row count SHALL be unchanged
- **AND** no `reports` or `moderation_queue` row SHALL be inserted, updated, or deleted

#### Scenario: The bare collection path exposes only GET
- **GIVEN** an authenticated session
- **WHEN** the client sends `POST /admin/reports` (the bare collection path, not a `/{id}/resolve` sub-route)
- **THEN** the response status SHALL be 405 (Method Not Allowed) — resolution is served by the `/{id}/resolve` sub-routes, not the collection path

## ADDED Requirements

### Requirement: Authenticated report-status resolution endpoint

The system SHALL serve `POST /admin/reports/{id}/resolve` as an authenticated route wired INSIDE the `authenticate(ADMIN_AUTH_NAME)` block (so the `admin-login` session middleware gates it). On a valid session with a valid CSRF token and a write-capable admin role, it SHALL accept a `decision` form field constrained to `{actioned, dismissed}` and, in ONE database transaction, set `reports.status` to that value, set `reports.reviewed_by` to the acting admin's id, set `reports.reviewed_at` to the current time, and write exactly one `admin_actions_log` row with `action_type = 'report_resolved'` (recording the before/after status). The transition SHALL apply only to a report currently in `status = 'pending'` (conditional update; the already-resolved case is governed by the idempotency requirement).

#### Scenario: A pending report is marked actioned
- **GIVEN** an authenticated write-role admin session with a valid CSRF token AND a `reports` row with `status = 'pending'`
- **WHEN** the client sends `POST /admin/reports/{id}/resolve` with `decision=actioned`
- **THEN** that report's `status` SHALL become `actioned` AND `reviewed_by` SHALL be the acting admin's id AND `reviewed_at` SHALL be set
- **AND** exactly one `admin_actions_log` row with `action_type = 'report_resolved'` SHALL be written

#### Scenario: A pending report is marked dismissed
- **GIVEN** an authenticated write-role admin session with a valid CSRF token AND a `pending` report
- **WHEN** the client sends `POST /admin/reports/{id}/resolve` with `decision=dismissed`
- **THEN** that report's `status` SHALL become `dismissed` AND `reviewed_by` + `reviewed_at` SHALL be set AND exactly one `report_resolved` audit row SHALL be written

### Requirement: Authenticated moderation-queue resolution endpoint

The system SHALL serve `POST /admin/moderation-queue/{id}/resolve` as an authenticated route wired INSIDE the `authenticate(ADMIN_AUTH_NAME)` block. On a valid session with a valid CSRF token and a write-capable admin role, it SHALL accept a `resolution` form field constrained to the content/author resolution subset (`keep`, `hide`, `delete`, `shadow_ban_author`, `suspend_author_7d`, `ban_author`) and, in ONE database transaction, set `moderation_queue.status = 'resolved'`, set `moderation_queue.resolution` to that value, set `resolved_by` to the acting admin's id, set `resolved_at` to the current time, and write exactly one `admin_actions_log` row with `action_type = 'moderation_queue_resolved'`. The transition SHALL apply only to a queue row currently in `status = 'pending'`. The username-moderation resolutions `accept_flagged_username` / `reject_flagged_username` (and the `username_flagged` trigger) are OUT OF SCOPE for this endpoint — they are owned by the future Premium Username Change Oversight feature (`docs/07-Operations.md` § Core Features), which carries its own 10/hour rate limit + override-on-resubmit + manual-handle-release semantics this generic endpoint does not express; this endpoint SHALL reject those values without a write.

#### Scenario: A pending queue item is resolved with a recorded resolution
- **GIVEN** an authenticated write-role admin session with a valid CSRF token AND a `moderation_queue` row with `status = 'pending'`
- **WHEN** the client sends `POST /admin/moderation-queue/{id}/resolve` with `resolution=hide`
- **THEN** that queue row's `status` SHALL become `resolved` AND `resolution` SHALL be `hide` AND `resolved_by` + `resolved_at` SHALL be set
- **AND** exactly one `admin_actions_log` row with `action_type = 'moderation_queue_resolved'` SHALL be written

### Requirement: Intrinsic auto-hide visibility toggle on keep/hide

When resolving a `moderation_queue` row whose `trigger = 'auto_hide_3_reports'`, the system SHALL apply the content-visibility effect intrinsic to that auto-hide signal, in the same transaction as the resolution: `resolution = keep` SHALL set the target's `is_auto_hidden = FALSE` (restoring visibility — the inverse of the V9 auto-hide trigger), and `resolution = hide` SHALL leave `is_auto_hidden = TRUE`. The toggle SHALL apply to `posts` (`target_type = 'post'`) and `post_replies` (`target_type = 'reply'`). For `target_type` values without an `is_auto_hidden` column (`user`, `chat_message`), the toggle SHALL be a no-op and the resolution SHALL still succeed. This change SHALL NOT perform any other content mutation (no soft-delete; see the record-not-enforce requirement).

#### Scenario: keep restores visibility of an auto-hidden post
- **GIVEN** an `auto_hide_3_reports` queue row for a `post` whose `is_auto_hidden = TRUE`
- **WHEN** the queue row is resolved with `resolution=keep`
- **THEN** that post's `is_auto_hidden` SHALL become `FALSE` (visibility restored) in the same transaction as the queue resolution

#### Scenario: hide leaves the post hidden
- **GIVEN** an `auto_hide_3_reports` queue row for a `post` whose `is_auto_hidden = TRUE`
- **WHEN** the queue row is resolved with `resolution=hide`
- **THEN** that post's `is_auto_hidden` SHALL remain `TRUE`

#### Scenario: keep on a user-target queue row is a no-op (no error)
- **GIVEN** a queue row with `target_type = 'user'` (a table with no `is_auto_hidden` column)
- **WHEN** the queue row is resolved with `resolution=keep`
- **THEN** no `is_auto_hidden` write SHALL be attempted AND the resolution SHALL succeed without error

### Requirement: Resolution writes are session-, CSRF-, and write-role-gated

Both resolution endpoints SHALL enforce, in order, the `admin-login` session gate, then CSRF validation, then the write-role gate — mirroring `admin-user-moderation`. An unauthenticated (or expired/revoked/idle-timed-out) request SHALL redirect 302 to `/admin/login` and write nothing. A request whose CSRF token is missing or invalid SHALL return 403, emit the existing `admin_csrf_violation` audit entry, and perform no resolution write. An authenticated admin whose role is not write-capable (`AdminRoleGate.requireWriteRole`) SHALL be rejected with no resolution write. CSRF SHALL be validated BEFORE the role gate.

#### Scenario: Unauthenticated resolution request redirects and writes nothing
- **WHEN** a client sends `POST /admin/reports/{id}/resolve` (or `POST /admin/moderation-queue/{id}/resolve`) with no valid `__Host-admin_session` cookie
- **THEN** the response status SHALL be 302 with `Location: /admin/login` AND no `reports` / `moderation_queue` / `admin_actions_log` row SHALL be written

#### Scenario: Missing or invalid CSRF token is rejected and audited
- **GIVEN** an authenticated admin session
- **WHEN** the client sends a resolution `POST` without a valid CSRF token
- **THEN** the response status SHALL be 403 AND an `admin_csrf_violation` audit entry SHALL be recorded AND no resolution write SHALL occur

#### Scenario: A non-write-role admin is gated
- **GIVEN** an authenticated admin whose role is not write-capable AND a valid CSRF token
- **WHEN** the client sends a resolution `POST`
- **THEN** the request SHALL be rejected by the write-role gate AND no `reports` / `moderation_queue` row SHALL be mutated

### Requirement: Resolution records the decision without performing author or content-deletion enforcement

The resolution endpoints SHALL record the moderator's decision but SHALL NOT perform author-level enforcement or content soft-deletion. Recording `resolution ∈ {suspend_author_7d, ban_author, shadow_ban_author}` SHALL NOT modify the `users` table (no change to `is_banned`, `suspended_until`, `is_shadow_banned`, or `token_version`), and `resolution = delete` SHALL NOT soft-delete the target content (no change to the target's `deleted_at`). Author enforcement remains reachable via the existing `/admin/users` deep-link rendered by the report-queue listing. This boundary SHALL be stated in the in-row controls so a moderator is not misled into believing recording a resolution enforces it.

#### Scenario: Recording ban_author does not modify the users table
- **GIVEN** a queue row whose offending author is user `A` with `is_banned = FALSE`
- **WHEN** the queue row is resolved with `resolution=ban_author`
- **THEN** `moderation_queue.resolution` SHALL record `ban_author` AND user `A`'s row SHALL be unchanged (`is_banned` still `FALSE`, `token_version` unchanged)

#### Scenario: Recording delete does not soft-delete the content
- **GIVEN** an `auto_hide_3_reports` queue row for a `post` whose `deleted_at IS NULL`
- **WHEN** the queue row is resolved with `resolution=delete`
- **THEN** the queue row SHALL record `resolution = delete` AND the post's `deleted_at` SHALL remain `NULL` (no soft-delete performed by this endpoint)

### Requirement: Malformed and repeat resolution inputs are tolerated and idempotent

The resolution endpoints SHALL tolerate malformed input without a 5xx and SHALL be idempotent. A malformed path `{id}` (not a UUID) SHALL return 400 with no write. An out-of-enum `decision` / `resolution` value SHALL be rejected without any partial write and without an audit row (re-render with a message, never a 5xx). Re-resolving an already-resolved (non-`pending`) row SHALL be a safe no-op: the conditional `UPDATE ... WHERE status = 'pending'` affects zero rows, so no status change occurs, no duplicate `admin_actions_log` row is written, and no error is returned. This also serializes the two-admins-resolve-the-same-row race (the loser is a no-op).

#### Scenario: Malformed id yields 400 with no write
- **WHEN** an authenticated write-role admin sends `POST /admin/reports/not-a-uuid/resolve` with a valid CSRF token
- **THEN** the response status SHALL be 400 AND no `reports` / `moderation_queue` / `admin_actions_log` row SHALL be written

#### Scenario: Out-of-enum value is rejected without a partial write
- **WHEN** an authenticated write-role admin sends `POST /admin/moderation-queue/{id}/resolve` with `resolution=not-an-enum-value` and a valid CSRF token
- **THEN** no `moderation_queue` row SHALL be mutated AND no `admin_actions_log` row SHALL be written AND the response SHALL NOT be a 5xx

#### Scenario: Out-of-scope username resolution is rejected without a write
- **GIVEN** a `moderation_queue` row (e.g. one with `trigger = 'username_flagged'`)
- **WHEN** an authenticated write-role admin sends `POST /admin/moderation-queue/{id}/resolve` with `resolution=accept_flagged_username` (or `reject_flagged_username`) and a valid CSRF token
- **THEN** the value SHALL be rejected with no `moderation_queue` mutation and no `admin_actions_log` row (it is owned by the Premium Username Change Oversight feature, not this endpoint), and the response SHALL NOT be a 5xx

#### Scenario: Re-resolving an already-resolved row is a safe no-op
- **GIVEN** a `moderation_queue` row already in `status = 'resolved'` with a recorded `resolution`
- **WHEN** an authenticated write-role admin sends `POST /admin/moderation-queue/{id}/resolve` again with a valid CSRF token
- **THEN** no second `admin_actions_log` row SHALL be written AND the existing `resolution` / `resolved_by` SHALL be unchanged AND no error SHALL be returned

### Requirement: In-row resolution controls render escaped, HTMX-partial with a no-JS fallback

The report-queue table SHALL render in-row resolution controls — a per-row form carrying the session CSRF token plus a `decision`/`resolution` selector that posts to the resolution endpoint. Every dynamic value rendered into the controls SHALL be HTML-escaped. The controls SHALL function under both modes: an `HX-Request: true` request SHALL return only the swappable table fragment, and a plain `GET` SHALL return the full page. A successful resolution SHALL re-render the re-queried table fragment for an HTMX request, or 303-redirect back to the (filter-preserving) queue for the no-JS path.

#### Scenario: A resolution control is rendered for a resolvable row
- **GIVEN** an authenticated session AND a `pending` report whose target has a representative `moderation_queue` row
- **WHEN** `GET /admin/reports` is served
- **THEN** that row SHALL render a resolution form (with a CSRF hidden field) posting to the resolution endpoint (inverting the read-only viewer's "no resolution control is rendered")

#### Scenario: Rendered control values are HTML-escaped
- **GIVEN** an authenticated session AND a report whose `reason_note` contains `<script>alert(1)</script>`
- **WHEN** `GET /admin/reports` is served with the resolution controls
- **THEN** the rendered HTML SHALL contain the escaped form (e.g. `&lt;script&gt;`) and SHALL NOT contain an executable `<script>` element from the `reason_note` value

#### Scenario: A successful no-JS resolution redirects back to the queue
- **GIVEN** an authenticated write-role admin submitting a plain `POST` (no `HX-Request` header) that resolves a report with a valid CSRF token
- **WHEN** the resolution succeeds
- **THEN** the response SHALL be a 303 redirect back to `/admin/reports` (preserving any active filters)
