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

The system SHALL serve `POST /admin/reports/{id}/resolve` as an authenticated route wired INSIDE the `authenticate(ADMIN_AUTH_NAME)` block. On a valid session with a valid CSRF token and a write-capable admin role, it SHALL accept a `decision` form field constrained to `{actioned, dismissed}` and, in ONE database transaction, set `reports.status` to that value, set `reports.reviewed_by` to the acting admin's id, set `reports.reviewed_at` to the current time, and write exactly one `admin_actions_log` row with `action_type = 'report_resolved'` (recording before/after status in `after_state`). The transition SHALL apply only to a report currently in `status = 'pending'` (conditional update; idempotency requirement governs the already-resolved case). This endpoint performs bookkeeping only — it SHALL NOT perform user or content enforcement (that is the moderation-queue resolution endpoint's role).

#### Scenario: A pending report is marked actioned
- **GIVEN** an authenticated write-role admin session with a valid CSRF token AND a `reports` row with `status = 'pending'`
- **WHEN** the client sends `POST /admin/reports/{id}/resolve` with `decision=actioned`
- **THEN** that report's `status` SHALL become `actioned` AND `reviewed_by` SHALL be the acting admin's id AND `reviewed_at` SHALL be set
- **AND** exactly one `admin_actions_log` row with `action_type = 'report_resolved'` SHALL be written

#### Scenario: A pending report is marked dismissed
- **GIVEN** an authenticated write-role admin session with a valid CSRF token AND a `pending` report
- **WHEN** the client sends `POST /admin/reports/{id}/resolve` with `decision=dismissed`
- **THEN** that report's `status` SHALL become `dismissed` AND `reviewed_by` + `reviewed_at` SHALL be set AND exactly one `report_resolved` audit row SHALL be written

### Requirement: Authenticated moderation-queue resolution endpoint performs the named enforcement

The system SHALL serve `POST /admin/moderation-queue/{id}/resolve` as an authenticated route wired INSIDE the `authenticate(ADMIN_AUTH_NAME)` block. On a valid session with a valid CSRF token and a sufficiently-roled admin (per the role-gating requirement), it SHALL accept a `resolution` form field constrained to the enforcement subset `{keep, hide, suspend_author_7d, ban_author, shadow_ban_author}` and, in ONE database transaction, set `moderation_queue.status = 'resolved'` + `resolution` + `resolved_by` + `resolved_at`, **perform the enforcement that resolution names** (per the content-resolution and author-resolution requirements), and write exactly one `admin_actions_log` row with `action_type = 'moderation_queue_resolved'` whose `after_state` records both the `resolution` value and the enforcement effect. The transition SHALL apply only to a queue row currently in `status = 'pending'`. The values `delete`, `accept_flagged_username`, and `reject_flagged_username` are OUT OF SCOPE and SHALL be rejected (see the malformed/out-of-scope requirement): `delete` is not in the in-row action set (`docs/07-Operations.md` § Core Features lists Hide/Dismiss/Suspend/Ban/Shadow-ban), and the username resolutions are owned by the future Premium Username Change Oversight feature.

#### Scenario: A pending queue item is resolved and the resolution is recorded
- **GIVEN** an authenticated write-role admin session with a valid CSRF token AND a `moderation_queue` row with `status = 'pending'` for a `post` target
- **WHEN** the client sends `POST /admin/moderation-queue/{id}/resolve` with `resolution=hide`
- **THEN** that queue row's `status` SHALL become `resolved` AND `resolution` SHALL be `hide` AND `resolved_by` + `resolved_at` SHALL be set
- **AND** exactly one `admin_actions_log` row with `action_type = 'moderation_queue_resolved'` SHALL be written whose `after_state` records `resolution = hide`

### Requirement: Content resolutions perform the visibility change

When the resolution is a content action and the queue row's `target_type ∈ {post, reply}`, the system SHALL perform the visibility change in the same transaction as the queue resolution: `keep` SHALL set the target's `is_auto_hidden = FALSE` (restoring visibility — the inverse of the V9-era application-level auto-hide writer in `ReportService`, NOT a DB trigger), and `hide` SHALL set `is_auto_hidden = TRUE`. The toggle SHALL apply to both `posts` (`target_type = 'post'`) and `post_replies` (`target_type = 'reply'`). For a `keep`/`hide` resolution whose `target_type ∈ {user, chat_message}` (no `is_auto_hidden` column), the resolution SHALL be rejected with a message and SHALL perform no write (a content action is not applicable to a user/chat target).

#### Scenario: keep restores visibility of an auto-hidden post
- **GIVEN** a `moderation_queue` row for a `post` whose `is_auto_hidden = TRUE`
- **WHEN** the queue row is resolved with `resolution=keep`
- **THEN** that post's `is_auto_hidden` SHALL become `FALSE` in the same transaction as the queue resolution

#### Scenario: hide hides a reply target
- **GIVEN** a `moderation_queue` row for a `reply` (`target_type = 'reply'`) whose `is_auto_hidden = FALSE`
- **WHEN** the queue row is resolved with `resolution=hide`
- **THEN** that reply's `post_replies.is_auto_hidden` SHALL become `TRUE`

#### Scenario: A content resolution on a user target is rejected
- **GIVEN** a `moderation_queue` row with `target_type = 'user'`
- **WHEN** the queue row is resolved with `resolution=keep` (or `hide`)
- **THEN** the resolution SHALL be rejected with a message AND no `is_auto_hidden` write SHALL occur AND no queue mutation SHALL occur (a content action is not applicable to a user target)

### Requirement: Author resolutions perform user-state enforcement on the resolved author

When the resolution is an author action (`suspend_author_7d`, `ban_author`, `shadow_ban_author`), the system SHALL resolve the offending author from the queue row's target (`post`→author, `reply`→author, `user`→self, `chat_message`→sender, using `LEFT JOIN`s) and perform the enforcement on that author in the same transaction as the queue resolution. `suspend_author_7d` SHALL apply the shipped 7-day suspension (reusing the `admin-user-moderation` suspend path: `is_banned = TRUE`, `suspended_until = NOW() + INTERVAL '7 days'`, plus the sanitized `account_action_applied` notification, plus the soft-deleted/already-permanently-banned guards). `ban_author` SHALL apply a permanent ban (`is_banned = TRUE`, `suspended_until = NULL`) plus the `account_action_applied` notification. `shadow_ban_author` SHALL set `users.is_shadow_banned = TRUE`. When the author cannot be resolved (hard-deleted target), the resolution SHALL be rejected with no write. When a suspend guard rejects (target soft-deleted, or already permanently banned), the resolution SHALL be rejected and the queue SHALL NOT be marked resolved.

#### Scenario: suspend_author_7d suspends the post's author
- **GIVEN** a `moderation_queue` row for a `post` authored by user `A` (not banned, not deleted)
- **WHEN** the queue row is resolved with `resolution=suspend_author_7d`
- **THEN** user `A` SHALL have `is_banned = TRUE` AND `suspended_until` ≈ NOW() + 7 days AND an `account_action_applied` notification SHALL be inserted for `A`
- **AND** the queue row SHALL be `resolved` with `resolution = suspend_author_7d` AND exactly one `moderation_queue_resolved` audit row SHALL be written

#### Scenario: ban_author permanently bans the resolved author
- **GIVEN** an owner/admin session AND a `moderation_queue` row whose resolved author is user `A` (not banned)
- **WHEN** the queue row is resolved with `resolution=ban_author`
- **THEN** user `A` SHALL have `is_banned = TRUE` AND `suspended_until = NULL` (permanent) AND an `account_action_applied` notification SHALL be inserted for `A`

#### Scenario: shadow_ban_author sets the shadow-ban flag
- **GIVEN** a `moderation_queue` row whose resolved author is user `A` (`is_shadow_banned = FALSE`)
- **WHEN** the queue row is resolved with `resolution=shadow_ban_author`
- **THEN** user `A` SHALL have `is_shadow_banned = TRUE`

#### Scenario: A hard-deleted target whose author cannot be resolved is rejected
- **GIVEN** a `moderation_queue` row whose target row has been hard-deleted (no resolvable author)
- **WHEN** the queue row is resolved with an author resolution (e.g. `ban_author`)
- **THEN** the resolution SHALL be rejected with a message AND no `users` mutation AND no queue mutation SHALL occur

#### Scenario: suspend on an already-permanently-banned author is rejected without resolving the queue
- **GIVEN** a `moderation_queue` row whose resolved author is already permanently banned (`is_banned = TRUE`, `suspended_until = NULL`)
- **WHEN** the queue row is resolved with `resolution=suspend_author_7d`
- **THEN** the suspend SHALL be rejected (no downgrade) AND the queue row SHALL remain `pending` AND no audit row SHALL be written

### Requirement: ban_author is restricted to the owner/admin tier

Issuing a permanent ban via `resolution=ban_author` SHALL require the owner or admin role, mirroring the existing rule that lifting a permanent ban is owner/admin-only (`admin-user-moderation`). An admin whose role is `moderator` (otherwise write-capable) SHALL be rejected when selecting `ban_author`, with no `users` mutation and no queue mutation. The tier check SHALL be enforced inside the resolution transaction so the rejection writes nothing.

#### Scenario: A moderator cannot ban via resolution
- **GIVEN** an authenticated `moderator`-role admin with a valid CSRF token AND a `pending` queue row
- **WHEN** the client sends `POST /admin/moderation-queue/{id}/resolve` with `resolution=ban_author`
- **THEN** the request SHALL be rejected AND user state SHALL be unchanged (`is_banned` not set) AND the queue row SHALL remain `pending` AND no audit row SHALL be written

#### Scenario: An owner or admin can ban via resolution
- **GIVEN** an authenticated `admin`-role session with a valid CSRF token AND a `pending` queue row whose resolved author is not banned
- **WHEN** the client sends `resolution=ban_author`
- **THEN** the author SHALL be permanently banned AND the queue row SHALL be `resolved`

### Requirement: shadow_ban_author writes no user-facing notification (stealth invariant)

Because a shadow ban is, by design, invisible to the affected user, resolving with `resolution=shadow_ban_author` SHALL NOT insert any `notifications` row for the author (no `account_action_applied`, no other type). The action SHALL still write its `admin_actions_log` row for accountability. By contrast, `suspend_author_7d` and `ban_author` SHALL insert the sanitized `account_action_applied` notification.

#### Scenario: Shadow-ban inserts no notification
- **GIVEN** a `moderation_queue` row whose resolved author is user `A` AND a known count of `A`'s `notifications` rows
- **WHEN** the queue row is resolved with `resolution=shadow_ban_author`
- **THEN** `A`'s `notifications` row count SHALL be unchanged (no notification inserted) AND `A`'s `is_shadow_banned` SHALL be `TRUE` AND a `moderation_queue_resolved` audit row SHALL be written

#### Scenario: Suspend inserts the account_action_applied notification
- **GIVEN** a `moderation_queue` row whose resolved author is user `A`
- **WHEN** the queue row is resolved with `resolution=suspend_author_7d`
- **THEN** exactly one `account_action_applied` notification SHALL be inserted for `A`

### Requirement: Resolution writes are session-, CSRF-, and write-role-gated

Both resolution endpoints SHALL enforce, in order, the `admin-login` session gate, then CSRF validation, then the role gate — mirroring `admin-user-moderation`. An unauthenticated (or expired/revoked/idle-timed-out) request SHALL redirect 302 to `/admin/login` and write nothing. A request whose CSRF token is missing or invalid SHALL return 403, emit the `admin_csrf_violation` audit entry, and perform no write. An authenticated admin whose role is not write-capable (`AdminRoleGate.requireWriteRole`) SHALL be rejected with no write. CSRF SHALL be validated BEFORE the role gate.

#### Scenario: Unauthenticated resolution request redirects and writes nothing
- **WHEN** a client sends `POST /admin/reports/{id}/resolve` (or `POST /admin/moderation-queue/{id}/resolve`) with no valid `__Host-admin_session` cookie
- **THEN** the response status SHALL be 302 with `Location: /admin/login` AND no `reports` / `moderation_queue` / `users` / `admin_actions_log` row SHALL be written

#### Scenario: Missing or invalid CSRF token is rejected and audited
- **GIVEN** an authenticated admin session
- **WHEN** the client sends a resolution `POST` without a valid CSRF token
- **THEN** the response status SHALL be 403 AND an `admin_csrf_violation` audit entry SHALL be recorded AND no resolution/enforcement write SHALL occur

#### Scenario: CSRF is validated before the role gate
- **GIVEN** an authenticated admin whose role is NOT write-capable (`read_only`)
- **WHEN** the client sends a resolution `POST` WITHOUT a valid CSRF token
- **THEN** the rejection SHALL be the CSRF rejection (403 + `admin_csrf_violation`), demonstrating CSRF is evaluated before the role gate, AND no write SHALL occur

#### Scenario: A non-write-role admin is gated
- **GIVEN** an authenticated `read_only` admin AND a valid CSRF token
- **WHEN** the client sends a resolution `POST`
- **THEN** the request SHALL be rejected by the role gate AND no `reports` / `moderation_queue` / `users` row SHALL be mutated

### Requirement: Enforcement, resolution, and audit are atomic

For every resolution, the enforcement write(s), the `reports`/`moderation_queue` status write, and the `admin_actions_log` row (plus any `account_action_applied` notification) SHALL commit or roll back together in one transaction. There SHALL be no observable partial state — no enforcement applied without its audit row, no queue marked `resolved` without the enforcement, and no audit row without the state change.

#### Scenario: An audit-write failure rolls back the enforcement and the resolution
- **GIVEN** a `pending` `moderation_queue` row for a `post` whose `is_auto_hidden = TRUE`, AND the audit insert is made to fail (fault injection, as in the `admin-user-moderation` rollback tests)
- **WHEN** the queue row is resolved with `resolution=keep`
- **THEN** the transaction SHALL roll back: the post's `is_auto_hidden` SHALL remain `TRUE`, the queue row SHALL remain `pending`, and no `admin_actions_log` row SHALL be written

### Requirement: Malformed, out-of-scope, and repeat resolution inputs are tolerated and idempotent

The resolution endpoints SHALL tolerate bad input without a 5xx and SHALL be idempotent. A malformed path `{id}` (not a UUID) SHALL return 400 with no write. A `decision`/`resolution` value outside the accepted allowlist SHALL be rejected via a server-side check BEFORE any DB write (never relying on a DB `CHECK` to throw, which would surface as a 5xx): this includes out-of-enum garbage AND the in-enum-but-out-of-scope values `delete`, `accept_flagged_username`, `reject_flagged_username`. Re-resolving an already-resolved (non-`pending`) row SHALL be a safe no-op: the conditional `UPDATE … WHERE status = 'pending'` affects zero rows, so no enforcement is performed, no status changes, no duplicate `admin_actions_log` row is written, and no error is returned (this also serializes the two-admins-resolve-the-same-row race — the loser is a no-op).

#### Scenario: Malformed id yields 400 with no write
- **WHEN** an authenticated write-role admin sends `POST /admin/reports/not-a-uuid/resolve` with a valid CSRF token
- **THEN** the response status SHALL be 400 AND no `reports` / `moderation_queue` / `users` / `admin_actions_log` row SHALL be written

#### Scenario: Out-of-scope resolution value is rejected before any DB write
- **GIVEN** a `moderation_queue` row
- **WHEN** an authenticated write-role admin sends `POST /admin/moderation-queue/{id}/resolve` with `resolution=delete` (or `accept_flagged_username`, or a non-enum garbage value) and a valid CSRF token
- **THEN** the value SHALL be rejected by the server-side allowlist with no `moderation_queue`/`users` mutation and no `admin_actions_log` row AND the response SHALL NOT be a 5xx

#### Scenario: Re-resolving an already-resolved row is a safe no-op
- **GIVEN** a `moderation_queue` row already in `status = 'resolved'` with a recorded `resolution`, and a known `admin_actions_log` count
- **WHEN** an authenticated write-role admin sends `POST /admin/moderation-queue/{id}/resolve` again with a valid CSRF token
- **THEN** no enforcement SHALL be re-performed AND no second `admin_actions_log` row SHALL be written (count unchanged) AND the existing `resolution`/`resolved_by` SHALL be unchanged AND no error SHALL be returned

### Requirement: Resolving a queue item does not cascade to sibling reports

Resolving a `moderation_queue` item SHALL NOT auto-transition the `reports` rows that share the same `(target_type, target_id)` — those reports' `status` SHALL remain `pending` until each is explicitly resolved via the report-status endpoint. (Auto-cascade is deliberately deferred; this negative guard documents the v1 behavior so a follow-up has an explicit requirement to MODIFY.)

#### Scenario: Sibling reports stay pending after a queue item is resolved
- **GIVEN** a `moderation_queue` row (`trigger = auto_hide_3_reports`) for a `post` target AND three `pending` `reports` rows for that same `(target_type, target_id)`
- **WHEN** the queue row is resolved with `resolution=hide`
- **THEN** the queue row SHALL become `resolved` AND all three `reports` rows SHALL remain `status = pending`

### Requirement: In-row resolution controls render escaped, HTMX-partial with a no-JS fallback

The report-queue table SHALL render in-row resolution controls — a per-row form carrying the session CSRF token plus a `decision`/`resolution` selector that posts to the resolution endpoint. Because the resolution actions take effect immediately (Suspend/Ban/Shadow-ban are destructive user-state changes), the controls SHALL make the destructive, immediate nature clear (e.g. a confirm affordance / explicit labels) rather than implying a deferred or record-only decision. Every dynamic value rendered into the controls SHALL be HTML-escaped. An `HX-Request: true` request SHALL return only the swappable table fragment; a plain `GET` SHALL return the full page. A successful resolution SHALL re-render the re-queried table fragment for an HTMX request, or 303-redirect back to the (filter-preserving) queue for the no-JS path.

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
