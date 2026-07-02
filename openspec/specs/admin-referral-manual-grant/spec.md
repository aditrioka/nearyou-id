# admin-referral-manual-grant Specification

## Purpose
The admin "Referral Manual Grant Path" (admin board frame 19, `GET/POST /admin/referral-grants`) — the support-desk remedy for when the automated referral activity-gate (`referral-grant-worker`) false-negatives a legitimate referral, resolving [Open Decision #15](../../../docs/08-Roadmap-Risk.md) ("include a minimal path in Phase 3.5 — critical for user trust"). A CSRF- and owner/admin-role-gated, rate-limited (10/admin/hour, distinct restorative counter), audit-logged surface that looks up a user's premium + referral context and issues a manual 1-week promotional Premium grant through the existing `ReferralEntitlementGranter` RevenueCat port, plus a keyset-paginated read viewer of past manual grants. The authoritative record is one immutable `admin_actions_log` row (`action_type = 'referral_manual_grant'`); Premium activation and the `subscription_events` row stay owned by the existing GRANT webhook echo, and the path deliberately never writes `granted_entitlements` — a support-desk remedy, not a referral-system action (*"bukan aksi sistem"*), so it counts against neither the inviter's 5-referral lifetime track nor the lifetime inviter reward.
## Requirements
### Requirement: The admin panel exposes a Referral Manual Grant surface at `/admin/referral-grants`

The admin panel SHALL expose `GET /admin/referral-grants` (the lookup + past-grants viewer) and `POST /admin/referral-grants` (the grant write action) under the admin route subtree, gated by the established admin authentication (valid `__Host-admin_session`). Both routes MUST require an authenticated admin session; an unauthenticated request MUST be rejected (redirect to login or 401/403 per the admin auth convention) before any handler logic runs.

#### Scenario: Unauthenticated access is rejected
- **WHEN** a request without a valid admin session hits `GET /admin/referral-grants` or `POST /admin/referral-grants`
- **THEN** the admin auth gate rejects it before any lookup, dispatch, or write occurs

#### Scenario: The surface is reachable by an authenticated admin
- **WHEN** an authenticated admin opens `GET /admin/referral-grants`
- **THEN** the page renders the user-lookup form and the past-manual-grants viewer

### Requirement: An admin can look up a user and see premium and referral context before granting

`GET /admin/referral-grants` SHALL accept an optional `q` parameter (username or user UUID) and, when present, resolve the target user and render their current premium / `subscription_status` plus referral context, so the admin can verify the support claim before granting. The lookup MUST be an admin-module read (the admin module is exempt from the `visible_*`/block-join product-path rules) and MUST HTML-escape all rendered user-supplied values.

#### Scenario: Lookup resolves a user and shows status
- **WHEN** an admin submits `q` matching an existing user
- **THEN** the page shows that user's handle, current `subscription_status`, and referral context, with the grant form enabled for that user

#### Scenario: Lookup of an unknown identifier shows an empty result
- **WHEN** an admin submits `q` that matches no user
- **THEN** the page shows a no-match state and the grant form is not enabled for any user

#### Scenario: A soft-deleted user's context renders without a grant form
- **WHEN** an admin submits `q` matching a soft-deleted (tombstoned) user
- **THEN** the context panel renders with a deleted indicator and the grant form is not enabled

### Requirement: A manual grant dispatches a 1-week promotional Premium entitlement through the RevenueCat port

`POST /admin/referral-grants` SHALL issue a manual Premium grant by dispatching through the existing `ReferralEntitlementGranter` port (the `:infra:revenuecat-api` RC v1 promotional-entitlement client). The dispatched grant MUST use absolute-expiry stacking math `endTimeMs = GREATEST(current_entitlement_end, NOW()) + 7 days` (extend-if-active, fresh-if-free) and the configured `premium` entitlement id. `:backend:ktor` MUST depend only on the `ReferralEntitlementGranter` interface and MUST NOT import any RevenueCat or HTTP-client symbol.

#### Scenario: A grant for a free user dispatches a fresh 7-day entitlement
- **WHEN** an admin grants a user with no active entitlement
- **THEN** the port is dispatched with `endTimeMs` ≈ `NOW() + 7 days`

#### Scenario: A grant for an active-premium user extends by 7 days
- **WHEN** an admin grants a user whose entitlement currently ends in the future
- **THEN** the port is dispatched with `endTimeMs` = `current_entitlement_end + 7 days` (stacked, not reset)

#### Scenario: Dispatch fails soft when RevenueCat is unconfigured
- **WHEN** the `ReferralEntitlementGranter` reports `isConfigured() == false` (no RC secret key)
- **THEN** the RC call is skipped, the action still writes its audit row, and the response states the dispatch was skipped (RevenueCat not configured) — the handler never throws

#### Scenario: Dispatch failure (RevenueCat rejected or errored) is surfaced, not swallowed
- **WHEN** the granter returns `GrantResult.Failed` (RC configured but the promotional call was rejected or errored)
- **THEN** the audit row is still written (the admin's attempt is recorded), Premium does not activate, the rate-limit still counts the attempt, and the response surfaces the failure to the admin with retry guidance — the handler never throws

#### Scenario: A soft-deleted user cannot be granted
- **WHEN** an admin submits a grant for a soft-deleted (tombstoned) user
- **THEN** the grant is rejected with no RC dispatch and no audit-row write — a promotional grant to a tombstoned account is a support-desk mistake, not a remedy

### Requirement: Every manual grant writes exactly one immutable audit-log row as the authoritative record

Whenever the grant proceeds past pre-flight validation (auth, CSRF, role, non-empty reason, rate-limit) to the dispatch step, it SHALL write exactly one `admin_actions_log` row with `action_type = 'referral_manual_grant'`, `target_type = 'user'`, `target_id` = the grantee, `admin_id` = the acting admin, the required `reason`, and before/after subscription snapshots — regardless of the dispatch outcome (`Dispatched`, `NotConfigured`, or `Failed`). This audit row is the authoritative "a human performed this grant" record. Immutability is enforced by the operational `admin_app` role grant (provisioned out-of-Flyway per V16, consistent with every other admin audit write) — no `UPDATE`/`DELETE` for `admin_app`; the `admin_id` FK to `admin_users` is intentionally **not** `ON DELETE SET NULL` (the audit log blocks admin hard-delete, unlike the operational tables where the SET-NULL invariant applies).

#### Scenario: A grant records one audit row
- **WHEN** an admin completes a grant for a user with reason "support ticket #1234"
- **THEN** exactly one `admin_actions_log` row is written with `action_type = 'referral_manual_grant'`, the grantee as `target_id`, and the reason persisted

#### Scenario: A grant requires a non-empty reason
- **WHEN** an admin submits the grant form with an empty reason
- **THEN** the grant is rejected, no RC dispatch occurs, and no audit row is written

### Requirement: The manual grant never writes `granted_entitlements`

The manual grant SHALL NOT insert into or modify `granted_entitlements`. It is a support-desk remedy, not a referral-system action, and MUST NOT count against the inviter's 5-referral lifetime track or the single lifetime inviter reward.

#### Scenario: No referral-ledger row is created
- **WHEN** an admin completes a manual grant
- **THEN** no `granted_entitlements` row is inserted and the user's inviter lifetime-cap accounting is unchanged

### Requirement: Premium activation and the `subscription_events` row stay owned by the webhook echo

The manual-grant path SHALL NOT write `users.subscription_status` or `subscription_events` directly. Premium activation and the corresponding `subscription_events` row MUST remain owned by the existing RevenueCat GRANT webhook echo (`SubscriptionService`), preserving the shipped ownership invariant. The admin path's responsibility ends at dispatching the RC grant and writing its audit row.

#### Scenario: The admin path does not write subscription state directly
- **WHEN** an admin completes a manual grant
- **THEN** no `subscription_events` row and no `users.subscription_status` update originate from the admin handler; the GRANT webhook echo is the sole writer of both

### Requirement: Manual grants are rate-limited on a distinct restorative per-admin cap

The grant action SHALL be rate-limited per acting admin (default 10 grants/hour) on a counter sourced from `admin_actions_log` and **independent** of the 20/hour destructive-action budget and the other distinct admin caps. When an admin is at or over the cap, the grant MUST be rejected with no RC dispatch and no audit-row write.

#### Scenario: Grants within the cap succeed
- **WHEN** an admin has performed fewer than the cap of manual grants in the trailing hour
- **THEN** a further grant is permitted

#### Scenario: Over-cap grants are rejected with no side effects
- **WHEN** an admin is at or over the per-hour manual-grant cap
- **THEN** the grant is rejected, no RC dispatch occurs, and no `admin_actions_log` row is written

### Requirement: The grant write is CSRF-protected and role-gated to owner/admin

`POST /admin/referral-grants` SHALL require a valid `X-CSRF-Token` matching the session's `csrf_token_hash` and SHALL require `role IN ('owner','admin')`. A missing or mismatched CSRF token MUST return 403 and write an `admin_csrf_violation` audit entry; a read-only admin role MUST be rejected for the write.

#### Scenario: Missing or mismatched CSRF token is rejected
- **WHEN** a `POST /admin/referral-grants` arrives without a matching `X-CSRF-Token`
- **THEN** it returns 403, records `admin_csrf_violation`, and performs no grant

#### Scenario: A read-only admin cannot grant
- **WHEN** an admin whose role is not `owner` or `admin` submits a grant
- **THEN** the grant is rejected and no RC dispatch or audit-row write occurs

### Requirement: The surface lists past manual grants from the audit log

`GET /admin/referral-grants` SHALL render a keyset-paginated, newest-first list of prior manual grants read from `admin_actions_log WHERE action_type = 'referral_manual_grant'`, joined to the acting admin and the grantee (tolerating a soft-deleted/tombstoned grantee). The list MUST support composable `q` (grantee username/UUID) and UTC-date-range filters, MUST HTML-escape all rendered values, and MUST degrade to a plain-`GET` render when HTMX is absent.

#### Scenario: The viewer paginates past grants newest-first
- **WHEN** an admin opens the surface with prior manual grants present
- **THEN** they are listed newest-first with keyset pagination, each row showing grantee, acting admin, reason, and timestamp

#### Scenario: The viewer renders without HTMX
- **WHEN** the page is fetched by a plain `GET` (no HTMX)
- **THEN** the list still renders server-side with escaped values

