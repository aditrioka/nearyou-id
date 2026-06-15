## ADDED Requirements

### Requirement: Authenticated GET /admin/subscriptions/grace lists users in the billing-retry grace window

The system SHALL serve an authenticated admin page at `GET /admin/subscriptions/grace` (admin board frame 18) rendering a table of every `users` row with `subscription_status = 'premium_billing_retry' AND deleted_at IS NULL` — the RevenueCat billing-retry grace population. The query SHALL be served by the existing V2 partial index `users_subscription_idx` (`ON users (subscription_status) WHERE deleted_at IS NULL`) with no new migration. Each row SHALL carry the user's identity (username, with a PREMIUM badge and a deep-link to `/admin/users?q=<username>`), the billing **store/platform**, a **retry-since** timestamp reflecting when the current billing-retry window began, and the **latest RevenueCat webhook** (event type + timestamp). Store/platform, retry-since, and latest-webhook SHALL be sourced from `subscription_events` (V21) via a LEFT JOIN to the user's most recent event, so that a billing-retry user with **no** `subscription_events` row still renders (those columns shown empty) — the surface is empty-state tolerant and never drops or 5xx's on missing event data. Any authenticated admin role (read need not be `owner`/`admin`) SHALL be able to view the page.

#### Scenario: Billing-retry users are listed

- **WHEN** an authenticated admin opens `GET /admin/subscriptions/grace` and two users have `subscription_status = 'premium_billing_retry'` (not soft-deleted)
- **THEN** the response SHALL list both users with their username, store/platform, retry-since timestamp, and latest webhook event sourced from `subscription_events`

#### Scenario: Active and soft-deleted non-grace users are excluded

- **WHEN** the page is rendered AND there exist users with `subscription_status = 'premium_active'`, users with `subscription_status = 'free'`, and a soft-deleted (`deleted_at IS NOT NULL`) user who is in `premium_billing_retry`
- **THEN** none of those users SHALL appear — only non-deleted `premium_billing_retry` users are listed

#### Scenario: A billing-retry user with no event rows still renders

- **WHEN** a `premium_billing_retry` user has zero `subscription_events` rows
- **THEN** the user SHALL still appear in the table with the store/platform, retry-since, and latest-webhook cells shown empty (no row is dropped and no error is raised)

#### Scenario: A non-admin (no admin session) is denied

- **WHEN** an unauthenticated request hits `GET /admin/subscriptions/grace`
- **THEN** the system SHALL redirect to the admin login (no grace data is disclosed)

### Requirement: The grace list is keyset-paginated, filterable, and summarized by count

The list SHALL be keyset-paginated over a stable ordering key (newest-retry-first) and SHALL accept composable, optional filters: `q` (an exact case-insensitive username match OR an exact user UUID match) and `store` (the billing platform). Filters SHALL compose (applying both narrows to their intersection) and an absent filter SHALL NOT constrain the result. The page SHALL render a count summary of the total users currently in the billing-retry window (the operational signal — a spike indicates a billing or webhook problem). The page SHALL provide an HTMX-driven render AND a plain-`GET` (no-JS) fallback that produces the same data.

#### Scenario: The q filter narrows to a single user by username

- **WHEN** the admin loads `GET /admin/subscriptions/grace?q=rina.sore` and `rina.sore` is in the billing-retry window
- **THEN** only `rina.sore` SHALL be listed

#### Scenario: Filters compose

- **WHEN** the admin loads `GET /admin/subscriptions/grace?store=app_store` AND the billing-retry population spans both App Store and Play Billing users
- **THEN** only the App Store billing-retry users SHALL be listed

#### Scenario: The count summary reflects the full billing-retry population

- **WHEN** 7 users are in `premium_billing_retry` and the admin views the page with no filter
- **THEN** the count summary SHALL report 7 (independent of the per-page keyset limit)

#### Scenario: Pagination does not drop or duplicate rows

- **WHEN** the billing-retry population exceeds one page and the admin requests the next page via the keyset cursor
- **THEN** the next page SHALL continue from the cursor with no row duplicated or skipped across the page boundary

### Requirement: The grace monitor enforces identity-only PII discipline and escapes user-controlled output

The surface SHALL expose only identity + billing-state fields (username, user id, store/platform, subscription event metadata, expedite-bookkeeping metadata). It SHALL NOT render location, email, date of birth, or any other sensitive PII. All user-controlled output (notably usernames and any stored reason/ticket text echoed back) SHALL be HTML-escaped in the rendered page to prevent injection.

#### Scenario: No sensitive PII is rendered

- **WHEN** the grace monitor renders a billing-retry user
- **THEN** the row SHALL contain identity + billing-state fields only AND SHALL NOT contain the user's location, email, or date of birth

#### Scenario: A username containing HTML metacharacters is escaped

- **WHEN** a listed user's username (or an echoed reason/ticket value) contains HTML metacharacters such as `<`, `>`, or `&`
- **THEN** the rendered output SHALL HTML-escape those characters rather than emitting raw markup

### Requirement: Manual expedite records an immutable bookkeeping action without changing entitlement

The system SHALL expose `POST /admin/subscriptions/grace/{user_id}/expedite` as a support-desk **bookkeeping** action. Expedite SHALL NOT mutate `users.subscription_status`, SHALL NOT grant or extend any entitlement, and SHALL NOT write any `subscription_events` row — RevenueCat remains the sole source of truth for entitlement and the `subscription_events` ledger stays free of synthetic events. A successful expedite SHALL write exactly one immutable `admin_actions_log` row with `action_type = 'subscription_grace_expedite'`, `target_type = 'user'`, `target_id = {user_id}`, the acting `admin_id`, a `reason` that incorporates a **required** support-ticket reference (the request SHALL be rejected if the ticket reference is absent or blank), and `before_state` / `after_state` snapshots in which `subscription_status` is identical (documenting that no entitlement changed). Each expedite is append-only — repeat expedites of the same user are permitted and each writes its own row (no entitlement dedup); the action never UPDATEs or DELETEs an existing `admin_actions_log` row.

#### Scenario: A successful expedite logs one row and leaves entitlement untouched

- **WHEN** an `owner`/`admin` submits `POST /admin/subscriptions/grace/{user_id}/expedite` for a billing-retry user with a valid CSRF token, a support-ticket reference, and a reason
- **THEN** exactly one `admin_actions_log` row SHALL be written with `action_type = 'subscription_grace_expedite'`, `target_id = {user_id}`, and matching `before_state`/`after_state` `subscription_status` AND the user's `subscription_status` SHALL remain `premium_billing_retry` (no entitlement change, no `subscription_events` row written)

#### Scenario: An expedite without a ticket reference is rejected

- **WHEN** an expedite is submitted with a missing or blank support-ticket reference
- **THEN** the request SHALL be rejected with no `admin_actions_log` row written and no mutation

#### Scenario: Repeat expedite of the same user appends a second row

- **WHEN** an admin expedites the same billing-retry user twice (each with a valid ticket reference)
- **THEN** two distinct append-only `admin_actions_log` rows SHALL exist for that user AND no prior row SHALL be UPDATEd or DELETEd

### Requirement: Manual expedite is role-gated to owner/admin and CSRF-protected

The expedite write SHALL be restricted to admins whose role is `owner` or `admin`; a read-only admin role SHALL NOT be able to expedite. Every expedite request SHALL carry an `X-CSRF-Token` matching the acting session's `admin_sessions.csrf_token_hash`; a missing or mismatched token SHALL return 403, perform no mutation, and write an `admin_csrf_violation` audit entry (no `subscription_grace_expedite` row is written for the rejected attempt).

#### Scenario: A read-only admin role cannot expedite

- **WHEN** an admin whose role is not `owner`/`admin` submits an expedite
- **THEN** the request SHALL be rejected (forbidden) with no `admin_actions_log` `subscription_grace_expedite` row written and no mutation

#### Scenario: A CSRF-token mismatch is rejected and audited

- **WHEN** an `owner`/`admin` submits an expedite with a missing or mismatched `X-CSRF-Token`
- **THEN** the system SHALL return 403, perform no mutation, and write an `admin_csrf_violation` audit entry (and no `subscription_grace_expedite` row)

### Requirement: Manual expedite is rate-limited per admin via a distinct trailing-hour counter

The system SHALL cap expedite actions at **20 per acting admin per trailing one-hour window**. The cap SHALL reuse the `admin-destructive-action-rate-limit` mechanism — the immutable `admin_actions_log` is the rate-limit ledger, the count is taken inside the same JDBC transaction as the gated write (a soft cap with accepted ±1 concurrency tolerance, not a hard authorization boundary), and an at-or-over-cap attempt is surfaced as an inline "quota exceeded" state rather than a 5xx — but SHALL count on a **distinct** key: rows for the acting `admin_id` with `created_at > NOW() - INTERVAL '1 hour'` and `action_type = 'subscription_grace_expedite'`. Because expedite is non-punitive bookkeeping and is **outside** the destructive set, an expedite SHALL NOT count toward, consume, or be blocked by the 20/hour destructive-action budget, and a destructive action SHALL NOT count toward the expedite budget. An expedite rejected at the cap SHALL write no `admin_actions_log` row and perform no mutation.

#### Scenario: The 21st expedite in an hour is rejected without effect

- **GIVEN** an admin with exactly 20 `subscription_grace_expedite` rows in the trailing hour
- **WHEN** that admin attempts a 21st expedite with a valid CSRF token and ticket reference
- **THEN** the response SHALL surface a "quota exceeded" state (not a 5xx) AND no new `admin_actions_log` row SHALL be written AND `subscription_status` SHALL be unchanged

#### Scenario: Expedite and destructive budgets are independent

- **GIVEN** an admin with 20 destructive-action rows (suspend/ban/etc.) in the trailing hour and 0 `subscription_grace_expedite` rows
- **WHEN** that admin submits an expedite with a valid CSRF token and ticket reference
- **THEN** the expedite SHALL succeed (the exhausted destructive budget does not block it) AND it SHALL write one `subscription_grace_expedite` row without consuming the destructive budget

#### Scenario: The cap is per-admin

- **GIVEN** admin A with 20 `subscription_grace_expedite` rows in the trailing hour and admin B with 0
- **WHEN** admin B submits an expedite
- **THEN** admin B's expedite SHALL succeed (admin A's exhausted expedite quota does not block admin B)

### Requirement: The read surface indicates whether a billing-retry row was already expedited

For each listed billing-retry user, the page SHALL indicate whether a manual expedite has already been recorded, by surfacing the most recent `admin_actions_log` row with `action_type = 'subscription_grace_expedite'` and `target_id = {user_id}` (via a LEFT JOIN). When present, the indicator SHALL convey that the row was handled (e.g. the acting admin and timestamp) so the support desk does not duplicate a resolution; when absent, the row SHALL present the expedite control as available. This indicator is read-only and SHALL NOT itself write any audit row.

#### Scenario: A previously-expedited row shows the handled indicator

- **WHEN** a billing-retry user has a prior `subscription_grace_expedite` audit row and the page is rendered
- **THEN** that user's row SHALL display the already-expedited indicator (acting admin + timestamp) sourced from the latest matching `admin_actions_log` row

#### Scenario: A never-expedited row shows the expedite control as available

- **WHEN** a billing-retry user has no `subscription_grace_expedite` audit row
- **THEN** that user's row SHALL present the expedite action as available (no handled indicator) AND rendering the page SHALL write no `admin_actions_log` row
