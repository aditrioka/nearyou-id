## ADDED Requirements

### Requirement: Admin appeals-review queue

The admin panel SHALL expose `GET /admin/appeals` rendering a paginated list of `pending` appeals (each row showing the appellant, `action_type`, a truncated `appeal_text`, and `created_at`) plus a per-appeal detail view with the full `appeal_text`. The route MUST be behind the existing admin authentication (owner/admin role) and follow the panel's Pebble + HTMX idioms with a no-JS fallback. The surface ships unstyled (its mockup-board frame is the documented "sole known gap" per `docs/11` §3.6; behavior ships now, styling lands with a later admin design-foundation pass).

#### Scenario: Pending queue lists awaiting appeals
- **GIVEN** two appeals exist with `status = 'pending'` and one with `status = 'approved'`
- **WHEN** an authenticated admin requests `GET /admin/appeals`
- **THEN** the response lists the two `pending` appeals and excludes the `approved` one

#### Scenario: Unauthenticated access is rejected
- **WHEN** an unauthenticated request hits `GET /admin/appeals`
- **THEN** it is redirected/denied by the admin auth gate (no appeal data is served)

### Requirement: Approve appeal reuses the unban path

Approving an appeal SHALL, in a single transaction guarded by `WHERE status = 'pending'`: (a) clear the moderation action on the appellant via the canonical unban statement (`UPDATE users SET is_banned = FALSE, suspended_until = NULL`), and (b) transition the appeal to `status = 'approved'`, setting `reviewed_by` and `reviewed_at`. The action MUST require a valid CSRF token, be subject to the `admin-destructive-action-rate-limit`, and be idempotent (re-issuing on an already-approved appeal is a no-op).

#### Scenario: Approving a suspended user's appeal lifts the suspension
- **GIVEN** appellant A has `is_banned = TRUE`, `suspended_until > NOW()`, and a `pending` appeal
- **WHEN** an admin approves the appeal with a valid CSRF token
- **THEN** A's `users` row has `is_banned = FALSE` and `suspended_until = NULL`, and the appeal is `approved` with `reviewed_by`/`reviewed_at` set

#### Scenario: Re-approving is idempotent
- **GIVEN** an appeal already has `status = 'approved'`
- **WHEN** an admin issues the approve action again
- **THEN** the guarded update affects no rows and the appellant's unban state is unchanged (no error, no double-effect)

### Requirement: Reject appeal records the decision

Rejecting an appeal SHALL transition it to `status = 'rejected'` (guarded `WHERE status = 'pending'`), set `reviewed_by`/`reviewed_at`, optionally persist a `decision_reason` (≤1000 chars), and MUST NOT alter the appellant's moderation state (the ban/suspension stands). The action requires a valid CSRF token and is subject to the destructive-action rate-limit.

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
