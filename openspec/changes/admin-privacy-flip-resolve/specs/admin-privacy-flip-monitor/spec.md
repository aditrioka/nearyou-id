## RENAMED Requirements

- FROM: `### Requirement: The capability adds only read routes; mutation methods are unmapped`
- TO: `### Requirement: The collection path is read-only; mutation is confined to the resolve sub-route`

## MODIFIED Requirements

### Requirement: The collection path is read-only; mutation is confined to the resolve sub-route

The bare collection path `/admin/privacy-flips` SHALL expose only `GET`. It SHALL NOT wire any `POST`, `PUT`, `PATCH`, or `DELETE` handler. Serving it, with any filter or cursor parameters, SHALL write no `admin_actions_log` row and mutate no `users` row. All mutation on this capability SHALL be confined to the per-user resolve sub-route `POST /admin/privacy-flips/{user_id}/resolve`, governed by the resolve requirements ADDED by `admin-privacy-flip-resolve`. The listing itself remains strictly read-only. This supersedes the prior "no clear / expedite control" guard: the out-of-band worker-fix escalation path is replaced by the audited in-panel resolve, and tracking issue [#277](https://github.com/aditrioka/nearyou-id/issues/277) is resolved.

#### Scenario: POST on the bare collection path is not wired

- **GIVEN** an authenticated session (so the request passes the auth gate)
- **WHEN** the client sends `POST /admin/privacy-flips` (the bare collection path, not a `/{user_id}/resolve` sub-route)
- **THEN** the response status SHALL be 405 Method Not Allowed (only `GET` is wired on the collection path; mutation is served by the resolve sub-route)

#### Scenario: Serving the monitor writes no audit row and mutates nothing

- **GIVEN** an authenticated session AND a known count N of `users` rows with `privacy_flip_scheduled_at IS NOT NULL` AND a known count M of rows in `admin_actions_log`
- **WHEN** the client sends `GET /admin/privacy-flips` (one or more times, with and without filters)
- **THEN** the count of `users` rows with a pending flip SHALL remain N (no flip cleared or scheduled)
- **AND** the count of rows in `admin_actions_log` SHALL remain M (viewing the monitor is not an auditable action, so no insert occurs)

#### Scenario: The resolve control is wired (no longer escalated out-of-band)

- **GIVEN** an authenticated owner/admin session AND an OVERDUE pending-flip row
- **WHEN** `GET /admin/privacy-flips` is served
- **THEN** that OVERDUE row SHALL present a resolve control (inverting the prior "no clear / expedite control is wired")
- **AND** a `POST /admin/privacy-flips/{user_id}/resolve` route SHALL be wired to resolve it (per the resolve requirements below)

## ADDED Requirements

### Requirement: The resolve endpoint settles one OVERDUE row by subscription state and writes one audit row

The system SHALL serve `POST /admin/privacy-flips/{user_id}/resolve` as an authenticated route wired INSIDE the `authenticate(ADMIN_AUTH_NAME)` block. The success path requires a valid session, a valid CSRF token, an owner/admin role, and a non-blank reason. On that path it SHALL, in ONE database transaction:

1. **Lock and re-check.** Lock the target `users` row and re-read its `private_profile_opt_in`, `privacy_flip_scheduled_at`, and `subscription_status` inside that transaction.
2. **Branch on the re-read `subscription_status`:**
   - When the user is NOT premium (`subscription_status = 'free'`), it SHALL **apply** the worker's flip: set `private_profile_opt_in = FALSE` AND `privacy_flip_scheduled_at = NULL`. The resolution is `applied`.
   - When the user IS premium (`subscription_status IN ('premium_active', 'premium_billing_retry')`), it SHALL **clear the schedule only**: set `privacy_flip_scheduled_at = NULL` and leave `private_profile_opt_in` unchanged. A paying user's privacy opt-in is never reset. The resolution is `cleared`.
3. **Audit.** Write exactly one `admin_actions_log` row:
   - `action_type = 'privacy_flip_resolved'`, `target_type = 'user'`, `target_id = {user_id}`;
   - the acting admin's id and the admin-supplied `reason`;
   - `before_state` = `{private_profile_opt_in, privacy_flip_scheduled_at, subscription_status}` as read under the lock;
   - `after_state` = `{private_profile_opt_in, privacy_flip_scheduled_at: null, subscription_status, resolution}`.

The `subscription_status` itself SHALL NOT change. The `private_profile_opt_in = FALSE` write SHALL carry the `// @allow-privacy-write: admin_resolve` allowlist annotation declared in `openspec/project.md` § Coding Conventions. Soft-deleted users follow the same branch rule.

#### Scenario: Resolving a non-premium OVERDUE row applies the flip

- **GIVEN** an authenticated owner/admin session with a valid CSRF token AND a user with `subscription_status = 'free'`, `private_profile_opt_in = TRUE`, and an elapsed `privacy_flip_scheduled_at`
- **WHEN** the client sends `POST /admin/privacy-flips/{user_id}/resolve` with a non-blank `reason`
- **THEN** that user's `private_profile_opt_in` SHALL be `FALSE` AND `privacy_flip_scheduled_at` SHALL be `NULL`
- **AND** exactly one `admin_actions_log` row SHALL be written with `action_type = 'privacy_flip_resolved'`, `target_type = 'user'`, `target_id = {user_id}`, the acting admin's id, the supplied reason, and `after_state.resolution = "applied"`

#### Scenario: Resolving a re-subscribed (premium_active) OVERDUE row clears the schedule only

- **GIVEN** an authenticated owner/admin session with a valid CSRF token AND a user with `subscription_status = 'premium_active'`, `private_profile_opt_in = TRUE`, and an elapsed `privacy_flip_scheduled_at` (the webhook missed clearing it)
- **WHEN** the client sends the resolve `POST` with a non-blank `reason`
- **THEN** that user's `privacy_flip_scheduled_at` SHALL be `NULL` AND `private_profile_opt_in` SHALL remain `TRUE` (the paying user's profile is not made public)
- **AND** exactly one `privacy_flip_resolved` audit row SHALL be written with `after_state.resolution = "cleared"`

#### Scenario: A billing-retry user is treated as premium

- **GIVEN** an authenticated owner/admin session with a valid CSRF token AND a user with `subscription_status = 'premium_billing_retry'`, `private_profile_opt_in = TRUE`, and an elapsed `privacy_flip_scheduled_at`
- **WHEN** the client sends the resolve `POST` with a non-blank `reason`
- **THEN** `privacy_flip_scheduled_at` SHALL be `NULL` AND `private_profile_opt_in` SHALL remain `TRUE`

#### Scenario: The audit row snapshots the locked before-state

- **GIVEN** an authenticated owner/admin session AND a `free` user with `private_profile_opt_in = TRUE` and an elapsed `privacy_flip_scheduled_at = D`
- **WHEN** the resolve succeeds
- **THEN** the audit row's `before_state` SHALL record `private_profile_opt_in = true`, `privacy_flip_scheduled_at = D`, and `subscription_status = "free"`
- **AND** its `after_state` SHALL record `private_profile_opt_in = false`, `privacy_flip_scheduled_at = null`, `subscription_status = "free"`, and `resolution = "applied"`

#### Scenario: A soft-deleted OVERDUE row can be resolved

- **GIVEN** an authenticated owner/admin session AND a `free` user with `deleted_at IS NOT NULL` and an elapsed `privacy_flip_scheduled_at` (left for the monitor by the worker's `deleted_at IS NULL` guard)
- **WHEN** the client sends the resolve `POST` with a non-blank `reason`
- **THEN** the flip SHALL be applied (`private_profile_opt_in = FALSE`, `privacy_flip_scheduled_at = NULL`) AND one `privacy_flip_resolved` audit row SHALL be written

### Requirement: The resolve action is session-, CSRF-, and owner/admin-role-gated, in order

The resolve endpoint SHALL enforce, in order, the `admin-login` session gate, then CSRF validation, then the owner/admin role gate:

- **Session.** An unauthenticated (or expired / revoked / idle-timed-out) request SHALL redirect 302 to `/admin/login` and write nothing.
- **CSRF.** A request whose CSRF token is missing or mismatched SHALL return 403, write an `admin_csrf_violation` audit row, and perform no resolve.
- **Role.** An authenticated admin whose role is NOT `owner` or `admin` (explicitly including `moderator` and `read_only`) SHALL receive 403 with no mutation and no `privacy_flip_resolved` audit row.

CSRF SHALL be validated BEFORE the role gate, so a violation is audited rather than masked by a role rejection. The role gate is intentionally stricter than the any-role READ view: the write touches the privacy flag.

#### Scenario: Unauthenticated resolve redirects and writes nothing

- **WHEN** a client sends `POST /admin/privacy-flips/{user_id}/resolve` with no valid `__Host-admin_session` cookie
- **THEN** the response status SHALL be 302 with `Location: /admin/login`
- **AND** the target's `privacy_flip_scheduled_at` SHALL be unchanged AND no `admin_actions_log` row SHALL be written

#### Scenario: Missing or invalid CSRF token is rejected and audited

- **GIVEN** an authenticated owner/admin session AND an OVERDUE row
- **WHEN** the client sends the resolve `POST` without a valid CSRF token
- **THEN** the response status SHALL be 403 AND an `admin_csrf_violation` audit row SHALL be recorded AND the row's `privacy_flip_scheduled_at` SHALL be unchanged AND no `privacy_flip_resolved` row SHALL be written

#### Scenario: CSRF is validated before the role gate

- **GIVEN** an authenticated `read_only` admin
- **WHEN** the client sends the resolve `POST` WITHOUT a valid CSRF token
- **THEN** the rejection SHALL be the CSRF rejection (403 + `admin_csrf_violation`) AND no write SHALL occur

#### Scenario: A moderator is rejected by the role gate

- **GIVEN** an authenticated `moderator` admin with a valid CSRF token AND an OVERDUE row
- **WHEN** the client sends the resolve `POST` with a non-blank reason
- **THEN** the response status SHALL be 403 AND the row's `privacy_flip_scheduled_at` SHALL be unchanged AND no `privacy_flip_resolved` row SHALL be written

#### Scenario: A read_only admin is rejected by the role gate

- **GIVEN** an authenticated `read_only` admin with a valid CSRF token AND an OVERDUE row
- **WHEN** the client sends the resolve `POST` with a non-blank reason
- **THEN** the response status SHALL be 403 AND no write SHALL occur

### Requirement: A resolve requires a non-blank, length-bounded reason and a well-formed id

The resolve endpoint SHALL require a `reason` form field that is non-blank after trimming. A missing, empty, or whitespace-only reason SHALL be rejected with 400, NO mutation, and NO audit row, validated before any DB write. An over-long reason SHALL be bounded server-side to 200 characters before it is stored, never a 5xx. A malformed path `{user_id}` (not a UUID) SHALL return 400 with no write.

#### Scenario: Blank reason is rejected with no write

- **GIVEN** an authenticated owner/admin session with a valid CSRF token AND an OVERDUE row
- **WHEN** the client sends the resolve `POST` with an empty or whitespace-only `reason`
- **THEN** the response status SHALL be 400 AND the row's `privacy_flip_scheduled_at` SHALL be unchanged AND no `privacy_flip_resolved` row SHALL be written

#### Scenario: Over-long reason is bounded, not errored

- **GIVEN** an authenticated owner/admin session with a valid CSRF token AND an OVERDUE row
- **WHEN** the client sends the resolve `POST` with a `reason` longer than 200 characters
- **THEN** the resolve SHALL succeed (not a 5xx) AND the stored audit `reason` SHALL be at most 200 characters

#### Scenario: Malformed id yields 400 with no write

- **GIVEN** an authenticated owner/admin session with a valid CSRF token
- **WHEN** the client sends `POST /admin/privacy-flips/not-a-uuid/resolve` with a non-blank reason
- **THEN** the response status SHALL be 400 AND no `privacy_flip_resolved` row SHALL be written

### Requirement: Only an OVERDUE row is resolvable; anything else is an idempotent no-op

The resolve SHALL apply only when, under the row lock, the target exists AND `privacy_flip_scheduled_at IS NOT NULL` AND `privacy_flip_scheduled_at` is at or before the request-scoped evaluation instant. That is the same OVERDUE predicate, closed on the OVERDUE side, that the monitor's classification uses. Each of the following SHALL perform NO mutation, write NO audit row, and render the monitor with an in-band "not resolvable" message (HTTP 200, never a 5xx):

- a still-IN_WINDOW row, because resolving early would cut a user's 72h grace short;
- a row with no pending flip (already resolved by another admin or by the worker);
- an unknown `user_id`.

An ineligible attempt SHALL NOT consume rate-limit quota.

#### Scenario: An IN_WINDOW row is not resolvable

- **GIVEN** an authenticated owner/admin session AND a user whose `privacy_flip_scheduled_at` is after the evaluation instant
- **WHEN** the client sends the resolve `POST` with a non-blank reason
- **THEN** the user's `private_profile_opt_in` and `privacy_flip_scheduled_at` SHALL be unchanged AND no `privacy_flip_resolved` row SHALL be written AND the response SHALL carry the "not resolvable" message (200)

#### Scenario: A flip due exactly at the evaluation instant is resolvable (boundary)

- **GIVEN** an authenticated owner/admin session AND a deterministic evaluation instant AND a `free` user whose `privacy_flip_scheduled_at` equals that instant exactly (classified OVERDUE by the monitor)
- **WHEN** the client sends the resolve `POST` with a non-blank reason
- **THEN** the resolve SHALL apply (the eligibility predicate is the monitor's at-or-before OVERDUE branch)

#### Scenario: Resolving an already-resolved row is a no-op

- **GIVEN** an authenticated owner/admin session AND a user whose OVERDUE flip has just been resolved (so `privacy_flip_scheduled_at IS NULL`)
- **WHEN** the client sends a second resolve `POST` for the same user
- **THEN** no mutation SHALL occur AND no second `privacy_flip_resolved` row SHALL be written AND the response SHALL carry the "not resolvable" message (200)

#### Scenario: An unknown user id is a no-op

- **GIVEN** an authenticated owner/admin session AND a well-formed `user_id` that matches no `users` row
- **WHEN** the client sends the resolve `POST` with a non-blank reason
- **THEN** no `admin_actions_log` row SHALL be written AND the response SHALL be 200 with the "not resolvable" message (not a 5xx)

### Requirement: The resolve action is rate-limited per admin at 10 per trailing hour

The system SHALL enforce a dedicated cap of **10 resolves per acting admin per trailing one-hour window**. The count comes from the immutable `admin_actions_log` audit trail (the audit trail is the rate-limit ledger): rows for the acting `admin_id` with `action_type = 'privacy_flip_resolved'` and `created_at > NOW() - INTERVAL '1 hour'`. The count SHALL be read inside the same transaction as the gated resolve. It is a soft abuse-prevention cap with ±1 concurrency tolerance and SHALL NOT take a `FOR UPDATE` lock on the ledger. Its behaviour:

- **At or over the cap:** an eligible attempt SHALL be rejected with NO mutation and NO audit row, surfaced as an in-band "quota exceeded" message (never a 5xx).
- **Per admin:** one admin reaching the cap SHALL NOT block another.
- **Independent of the destructive budget:** the counter is independent of the `admin-destructive-action-rate-limit` 20/hour destructive budget. A resolve neither consumes nor is blocked by it.
- **Quota display:** the monitor page SHALL display the acting owner/admin's trailing-hour usage against the cap.

#### Scenario: A resolve at the cap is rejected without effect

- **GIVEN** an authenticated owner/admin with exactly 10 `privacy_flip_resolved` rows in the trailing hour AND an OVERDUE row
- **WHEN** that admin attempts an 11th resolve with a valid CSRF token and non-blank reason
- **THEN** the response SHALL carry the "quota exceeded" message (not a 5xx) AND the row's `privacy_flip_scheduled_at` SHALL be unchanged AND no new `privacy_flip_resolved` row SHALL be written

#### Scenario: The counter is independent of the destructive budget

- **GIVEN** an authenticated owner/admin with 20 destructive-set audit rows (e.g. `user_suspended`) in the trailing hour AND 0 `privacy_flip_resolved` rows AND an OVERDUE row
- **WHEN** that admin performs a resolve
- **THEN** the resolve SHALL apply (the destructive budget does not gate it)

#### Scenario: The cap is per-admin

- **GIVEN** admin A with 10 `privacy_flip_resolved` rows in the trailing hour AND admin B with 0 AND an OVERDUE row
- **WHEN** admin B performs a resolve with a valid CSRF token and non-blank reason
- **THEN** admin B's resolve SHALL apply normally

#### Scenario: The cap counts only in-window resolve actions for the acting admin

- **GIVEN** an admin with, in the last hour, 2 `privacy_flip_resolved` rows AND 5 `subscription_grace_expedite` rows, plus 9 `privacy_flip_resolved` rows OLDER than one hour
- **WHEN** that admin's resolve count is computed
- **THEN** the count SHALL be 2

### Requirement: Resolve, audit, and rate-check are atomic

For every resolve, the `users` UPDATE, the `admin_actions_log` insert, and the in-transaction rate-count read SHALL commit or roll back together in one transaction. There SHALL be no observable partial state: no cleared schedule without its audit row, and no audit row without the UPDATE.

#### Scenario: An audit-write failure rolls back the resolve

- **GIVEN** a `free` OVERDUE user AND an audit write that fails inside the resolve transaction (fault injection)
- **WHEN** the resolve runs
- **THEN** the transaction SHALL roll back: `private_profile_opt_in` SHALL remain `TRUE` AND `privacy_flip_scheduled_at` SHALL remain set AND no `privacy_flip_resolved` row SHALL exist

### Requirement: The per-row resolve control renders on OVERDUE rows for owner/admin only, HTMX-partial with a no-JS fallback

The monitor table SHALL render a per-row resolve control only for sessions whose role is `owner` or `admin`, and only on rows classified OVERDUE. The control is a form carrying the session CSRF token, a required reason input, and a confirm affordance explaining both resolution branches; it posts to `POST /admin/privacy-flips/{user_id}/resolve`.

- **Who sees it.** A `moderator` or `read_only` session SHALL see no resolve control and no quota chip. The read view still renders for them, per the unchanged any-role read requirement. An IN_WINDOW row SHALL render no resolve control for any role.
- **Escaping.** Every dynamic value rendered into the control SHALL be HTML-escaped.
- **HTMX success.** An `HX-Request: true` resolve SHALL return the swappable `#privacy-flips-table` fragment, re-rendered with an in-band outcome message that names the resolution branch taken (applied vs cleared).
- **No-JS success.** A successful plain `POST` resolve SHALL 303-redirect back to `/admin/privacy-flips`.

#### Scenario: An OVERDUE row renders a resolve control for an owner session

- **GIVEN** an authenticated `owner` (or `admin`) session AND an OVERDUE row AND an IN_WINDOW row
- **WHEN** `GET /admin/privacy-flips` is served
- **THEN** the OVERDUE row SHALL render a resolve control (CSRF hidden field + reason input) posting to `/admin/privacy-flips/{that user_id}/resolve`
- **AND** the IN_WINDOW row SHALL render no resolve control

#### Scenario: No resolve control for a read_only or moderator session

- **GIVEN** an authenticated `read_only` (or `moderator`) session AND an OVERDUE row
- **WHEN** `GET /admin/privacy-flips` is served
- **THEN** the response status SHALL be 200 (the read view still renders) AND no resolve control SHALL be present for any row

#### Scenario: A successful HTMX resolve re-renders the fragment with the outcome

- **GIVEN** an authenticated owner/admin session submitting a resolve with header `HX-Request: true`, a valid CSRF token, and a non-blank reason for a `free` OVERDUE user
- **WHEN** the resolve succeeds
- **THEN** the response SHALL be the `#privacy-flips-table` fragment (no full-page layout) carrying an "applied" outcome message AND the resolved user SHALL no longer appear (it has no pending flip)

#### Scenario: A successful no-JS resolve redirects back to the monitor

- **GIVEN** an authenticated owner/admin submitting a plain `POST` (no `HX-Request` header) resolve with a valid CSRF token and non-blank reason
- **WHEN** the resolve succeeds
- **THEN** the response SHALL be a 303 redirect with `Location: /admin/privacy-flips`
