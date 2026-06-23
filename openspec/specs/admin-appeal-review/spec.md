# admin-appeal-review Specification

## Purpose
The admin half of the appeal loop: the appeals-review queue (`GET /admin/appeals`, full `appeal_text` inline) plus approve (→ unban, reusing the shipped unban path; restorative, not rate-limited) and reject actions, each behind the owner/admin gate + CSRF and writing one immutable `appeal_approved` / `appeal_rejected` audit row.
## Requirements
### Requirement: Admin appeals-review queue

The admin panel SHALL expose `GET /admin/appeals` rendering a paginated list of `pending` appeals (each row showing the appellant, `action_type`, the full `appeal_text` inline, and `created_at`). Because `appeal_text` is bounded at 1000 characters by the V34 `CHECK`, the full text renders inline in each row and no separate per-appeal detail view is required. The route MUST be behind the existing admin authentication (owner/admin role) and follow the panel's Pebble + HTMX idioms with a no-JS fallback. The surface ships unstyled (its mockup-board frame is the documented "sole known gap" per `docs/11` §3.6; behavior ships now, styling lands with a later admin design-foundation pass).

#### Scenario: Pending queue lists awaiting appeals
- **GIVEN** two appeals exist with `status = 'pending'` and one with `status = 'approved'`
- **WHEN** an authenticated admin requests `GET /admin/appeals`
- **THEN** the response lists the two `pending` appeals and excludes the `approved` one

#### Scenario: Unauthenticated access is rejected
- **WHEN** an unauthenticated request hits `GET /admin/appeals`
- **THEN** it is redirected/denied by the admin auth gate (no appeal data is served)

### Requirement: Approve appeal reuses the unban path

Approving an appeal SHALL, in a single transaction guarded by `WHERE status = 'pending'`: (a) clear the moderation action on the appellant via the canonical unban statement (`UPDATE users SET is_banned = FALSE, suspended_until = NULL`), and (b) transition the appeal to `status = 'approved'`, setting `reviewed_by` and `reviewed_at`. The action MUST require a valid CSRF token and be idempotent (re-issuing on an already-approved appeal is a no-op). Approving is **restorative** (it lifts a ban) and is therefore NOT counted by the `admin-destructive-action-rate-limit` (which gates *destructive* admin throughput); the owner/admin auth gate, CSRF, and immutable audit are the controls.

#### Scenario: Approving a suspended user's appeal lifts the suspension
- **GIVEN** appellant A has `is_banned = TRUE`, `suspended_until > NOW()`, and a `pending` appeal
- **WHEN** an admin approves the appeal with a valid CSRF token
- **THEN** A's `users` row has `is_banned = FALSE` and `suspended_until = NULL`, and the appeal is `approved` with `reviewed_by`/`reviewed_at` set

#### Scenario: Approving a permanently-banned user's appeal lifts the ban
- **GIVEN** appellant B has `is_banned = TRUE`, `suspended_until IS NULL` (permanent ban), and a `pending` appeal
- **WHEN** an admin approves the appeal
- **THEN** B's `users` row has `is_banned = FALSE` (the unban statement's `suspended_until = NULL` is a harmless no-op here) and the appeal is `approved`

#### Scenario: Re-approving is idempotent
- **GIVEN** an appeal already has `status = 'approved'`
- **WHEN** an admin issues the approve action again
- **THEN** the guarded update affects no rows and the appellant's unban state is unchanged (no error, no double-effect)

#### Scenario: Approve concurrent with the daily unban worker is safe
- **GIVEN** appellant A's suspension window has elapsed and the daily `suspension-unban-worker` has already set `is_banned = FALSE` while A's appeal is still `pending`
- **WHEN** an admin approves the appeal
- **THEN** the guarded transition still moves the appeal `pending → approved` (the `is_banned = FALSE` update is a no-op) with no error or double-unban

### Requirement: Reject appeal records the decision

Rejecting an appeal SHALL transition it to `status = 'rejected'` (guarded `WHERE status = 'pending'`), set `reviewed_by`/`reviewed_at`, optionally persist a `decision_reason` (≤1000 chars), and MUST NOT alter the appellant's moderation state (the ban/suspension stands). The action requires a valid CSRF token; it alters no user state and is therefore NOT counted by the `admin-destructive-action-rate-limit`.

#### Scenario: Rejecting leaves the moderation action in place
- **GIVEN** appellant A is suspended with a `pending` appeal
- **WHEN** an admin rejects the appeal with an optional `decision_reason`
- **THEN** the appeal is `rejected` with the reason stored, and A's `is_banned`/`suspended_until` are unchanged

### Requirement: Appeal decisions write immutable audit rows

Each approve or reject SHALL write exactly one `admin_actions_log` row with a new action type (`appeal_approved` or `appeal_rejected`), the acting admin, the target user, and the appeal id, within the same transaction as the decision. The `admin_actions_log` immutability invariant (UPDATE/DELETE revoked at the `admin_app` role) applies unchanged.

#### Scenario: Approve writes one appeal_approved audit row
- **WHEN** an admin approves an appeal
- **THEN** exactly one `admin_actions_log` row with action type `appeal_approved` is written, referencing the acting admin and the appellant

#### Scenario: Reject writes one appeal_rejected audit row
- **WHEN** an admin rejects an appeal
- **THEN** exactly one `admin_actions_log` row with action type `appeal_rejected` is written

#### Scenario: Idempotent re-approve writes no additional audit row
- **GIVEN** an appeal already has `status = 'approved'` (its `appeal_approved` audit row already written)
- **WHEN** an admin issues the approve action again (the guarded update affects no rows)
- **THEN** no additional `admin_actions_log` row is written (the audit write is inside the same guarded transaction, so a no-op decision logs nothing)

