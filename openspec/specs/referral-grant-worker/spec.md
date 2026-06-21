# referral-grant-worker Specification

## Purpose

The referral-grant-worker capability is the second stage of the freemium referral funnel — it turns activity-passing referral tickets into Premium grants. A daily, OIDC-gated `POST /internal/referral-activity-check` worker scans `pending_activity` `referral_tickets` (created at signup by `referral-ticket-creation`) in three passes: expire stale tickets (and void those whose inviter is now banned), evaluate the durable activity gate (invitee authored ≥ 2 posts AND inviter in good standing) and grant on pass, then reconcile any grant whose prior-run RevenueCat dispatch failed. A successful ticket flips to `granted` and dispatches a 1-week promotional Premium entitlement via the RevenueCat v1 API (`:infra:revenuecat-api`): the invitee is rewarded once per their own ticket, and the inviter exactly once per lifetime at their 5th successful referral (enforced by the `users.inviter_reward_claimed_at` sentinel + the `granted_entitlements` partial-unique index). Grants stack (extend-if-active / fresh-if-not), are idempotent (`granted_entitlements` UNIQUE + `dedup_key`), and dispatch fails soft (ledger-only when unconfigured; reconciled when transiently failed). The login-history-dependent gate legs (≥ 3 login-days, ≥ 5 app-sessions, 90-day device-fingerprint / IP-subnet / recently-seen-identifier anti-collision) are deferred — no durable source exists yet. `users.subscription_status` is owned by the `subscription-billing-webhook` `GRANT` echo, never written by this worker.

## Requirements
### Requirement: Referral activity-check worker endpoint

The backend SHALL expose `POST /internal/referral-activity-check` under the mandatory `/internal/*` OIDC middleware (Google OIDC ID token verified by `GoogleOidcTokenVerifier` in `:infra:oidc`), invoked daily by Cloud Scheduler. A request lacking a valid Google OIDC bearer token MUST be rejected before any handler logic runs (the `internal-endpoint-auth` contract). On each invocation the worker SHALL process `referral_tickets` rows in `status = 'pending_activity'` (served by the existing `referral_tickets_pending_idx`) in three passes — expiry, activity evaluation, then reconciliation of grants whose prior-run RevenueCat dispatch failed — and return an HTTP `200` summary carrying the counts of tickets expired, voided, granted, left pending, and grants reconciled.

#### Scenario: Unauthenticated invocation is rejected
- **WHEN** `POST /internal/referral-activity-check` is called without a valid Google OIDC bearer token
- **THEN** the request is rejected by the `/internal/*` OIDC middleware before any ticket is read AND no ticket status changes

#### Scenario: Authenticated invocation scans pending tickets and returns a summary
- **WHEN** an OIDC-authenticated invocation runs against a set of `pending_activity` tickets
- **THEN** the worker evaluates each pending ticket AND returns `200` with a summary of how many were expired, voided, granted, left pending, and reconciled

### Requirement: Stale pending tickets expire without a grant

For each `pending_activity` ticket whose `expires_at < NOW()` (the 14-day window set at creation), the worker SHALL set `status = 'expired'` and SHALL NOT grant any entitlement or write any `granted_entitlements` row for it.

#### Scenario: Expired pending ticket becomes expired with no grant
- **WHEN** the worker processes a `pending_activity` ticket whose `expires_at` is in the past
- **THEN** the ticket's `status` becomes `'expired'` AND no `granted_entitlements` row is written AND no RevenueCat grant is dispatched

### Requirement: Activity gate evaluates the durable legs

A not-yet-expired `pending_activity` ticket SHALL pass the activity gate when ALL implemented legs hold: the invitee has authored at least 2 posts within `[ticket.created_at, NOW()]` (counted from the invitee's own authored `posts` — an engagement signal, not a visibility-sensitive read), AND the inviter is in good standing — **neither hard-banned/suspended (`users.is_banned = FALSE`) nor shadow-banned (`users.is_shadow_banned = FALSE`)**, per docs/01 §233 ("inviter ban — shadow or hard — voids all pending tickets"). A ticket that has not yet reached the 2-post threshold SHALL remain `pending_activity` (re-evaluated on the next daily run until it passes or expires). A ticket whose inviter is banned by **either** flag SHALL be voided — set to the distinct terminal `status = 'voided'` (added by V33; separate from a TTL `'expired'` so analytics can tell a banned-inviter void from a 14-day lapse) with no grant.

#### Scenario: Invitee with two posts and an in-good-standing inviter passes
- **WHEN** the worker evaluates a non-expired ticket whose invitee has authored ≥ 2 posts since the ticket was created AND whose inviter is neither hard-banned nor shadow-banned
- **THEN** the ticket passes the activity gate and proceeds to the grant flow

#### Scenario: Invitee below the post threshold stays pending
- **WHEN** the worker evaluates a non-expired ticket whose invitee has authored fewer than 2 posts since ticket creation
- **THEN** the ticket's `status` remains `'pending_activity'` AND no grant is dispatched (it is re-evaluated on the next run)

#### Scenario: A hard-banned or shadow-banned inviter voids the ticket
- **WHEN** the worker evaluates a ticket whose inviter now has `is_banned = TRUE` OR `is_shadow_banned = TRUE`
- **THEN** the ticket's `status` becomes `'voided'` (the distinct terminal status, not a TTL `'expired'`) AND no grant is dispatched

### Requirement: A successful ticket grants the invitee one week of Premium

When a ticket passes the activity gate the worker SHALL set its `status = 'granted'` and insert a `granted_entitlements` row for the invitee (`grant_role = 'invitee'`, recipient = the invitee, with the computed 1-week window) atomically for the DB writes, and SHALL dispatch a RevenueCat promotional-entitlement grant for the invitee. The invitee grant fires for every successful ticket — exactly once per invitee, tied to their own registration ticket (never from referring others).

#### Scenario: Passing ticket flips to granted and grants the invitee
- **WHEN** a ticket passes the activity gate
- **THEN** its `status` becomes `'granted'` AND a `granted_entitlements` row with `grant_role = 'invitee'` for the invitee exists AND a RevenueCat promotional grant for the invitee is dispatched

### Requirement: Grant duration stacks — extend if active, fresh if not

For any grant (invitee or inviter), the worker SHALL compute the entitlement end as `GREATEST(recipient_current_entitlement_end, NOW()) + INTERVAL '7 days'`: if the recipient is currently `premium_active`, the grant SHALL extend their current period by 7 days; if the recipient is `free` or lapsed, the grant SHALL provide a fresh 7-day window starting now (docs/01 §139–140). The computed window is recorded in `granted_entitlements.entitlement_start` / `entitlement_end` and passed to the RevenueCat promotional grant.

#### Scenario: Active recipient's period is extended by a week
- **WHEN** a grant is computed for a recipient who is currently `premium_active` with an entitlement ending in the future
- **THEN** the granted `entitlement_end` is that future end plus 7 days (the existing period is extended, not replaced)

#### Scenario: Free recipient gets a fresh week
- **WHEN** a grant is computed for a recipient who is `free` or lapsed
- **THEN** the granted `entitlement_end` is `NOW() + 7 days` (a fresh 1-week window)

### Requirement: Invitee grant is idempotent — one per ticket

The invitee grant SHALL be guarded by the `granted_entitlements` `UNIQUE (referral_ticket_id, user_id)` constraint via `INSERT ... ON CONFLICT DO NOTHING`, and the RevenueCat call SHALL carry a stable `dedup_key` derived from the ticket and grant role. A re-run or concurrent invocation of the worker over an already-granted ticket SHALL create no second `granted_entitlements` row and SHALL NOT dispatch a duplicate effective grant.

#### Scenario: Re-processing a granted ticket creates no second grant
- **WHEN** the worker runs again over a ticket already in `status = 'granted'` with an existing invitee `granted_entitlements` row
- **THEN** no second `granted_entitlements` row is inserted (the `UNIQUE (referral_ticket_id, user_id)` conflict is swallowed) AND no duplicate grant is effected

#### Scenario: Concurrent worker runs grant at most once
- **WHEN** two worker invocations process the same passing ticket concurrently
- **THEN** exactly one invitee `granted_entitlements` row exists for that ticket (the UNIQUE constraint serializes the race)

### Requirement: Inviter reward fires exactly once at the 5th successful referral

After a ticket is set to `granted`, the worker SHALL count that inviter's `granted` tickets (served by the existing `referral_tickets_inviter_status_idx`). When that count equals exactly 5 AND `users.inviter_reward_claimed_at IS NULL`, the worker SHALL insert a `granted_entitlements` row (`grant_role = 'inviter'`, recipient = the inviter, tied to this 5th ticket), set `users.inviter_reward_claimed_at = NOW()`, and dispatch the inviter's 1-week promotional grant. Successful referrals 1–4 SHALL grant the inviter nothing; successful referrals 6 and beyond SHALL grant the inviter nothing (only the respective invitee is rewarded).

#### Scenario: The 5th successful referral grants the inviter once
- **WHEN** the worker grants a ticket that makes the inviter's `granted` ticket count reach exactly 5 AND `inviter_reward_claimed_at IS NULL`
- **THEN** a `granted_entitlements` row with `grant_role = 'inviter'` for the inviter exists AND `users.inviter_reward_claimed_at` is set AND a RevenueCat promotional grant for the inviter is dispatched

#### Scenario: Referrals one through four grant the inviter nothing
- **WHEN** the worker grants a ticket that makes the inviter's `granted` count 1, 2, 3, or 4
- **THEN** no inviter `granted_entitlements` row is written AND `users.inviter_reward_claimed_at` stays `NULL`

#### Scenario: The sixth and later successful referrals reward only the invitee
- **WHEN** the worker grants a ticket for an inviter whose `inviter_reward_claimed_at` is already set (6th successful referral or later)
- **THEN** the invitee is granted as normal AND no second inviter grant is written

### Requirement: Inviter lifetime single-grant is enforced at the database level

The inviter lifetime cap SHALL be enforced by BOTH the `users.inviter_reward_claimed_at` sentinel (checked before attempting any inviter grant) AND the partial-unique index `granted_entitlements_inviter_once_idx ON granted_entitlements (user_id) WHERE grant_role = 'inviter'`. Even a concurrent double-fire at the 5th-referral boundary SHALL yield at most one inviter-role grant per user across their lifetime.

#### Scenario: A user can never receive a second inviter-role grant
- **WHEN** a second `granted_entitlements` insert with `grant_role = 'inviter'` is attempted for a user who already has one
- **THEN** the `granted_entitlements_inviter_once_idx` partial-unique index rejects the insert

#### Scenario: Concurrent 5th-referral processing grants the inviter at most once
- **WHEN** two transactions concurrently attempt the inviter grant at the 5th-referral boundary
- **THEN** at most one inviter `granted_entitlements` row is committed (the partial-unique index serializes the race) AND `inviter_reward_claimed_at` ends set

### Requirement: granted_entitlements schema

A Flyway migration SHALL create the `granted_entitlements` table with: `id UUID PRIMARY KEY DEFAULT gen_random_uuid()`; `referral_ticket_id UUID NOT NULL REFERENCES referral_tickets(id) ON DELETE CASCADE`; `user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE` (the recipient); `grant_role VARCHAR(16) NOT NULL CHECK (grant_role IN ('invitee', 'inviter'))`; `entitlement_start TIMESTAMPTZ NOT NULL`; `entitlement_end TIMESTAMPTZ NOT NULL`; `dedup_key TEXT NOT NULL`; `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`. It SHALL enforce `UNIQUE (referral_ticket_id, user_id)`, a partial-unique index on `(user_id) WHERE grant_role = 'inviter'`, and a unique index on `dedup_key`. No index predicate may contain `NOW()` or any other volatile expression (partial-index immutability invariant). A later migration (V33) SHALL add a nullable `revenuecat_dispatched_at TIMESTAMPTZ` column — NULL until the RevenueCat promotional-grant call for that row succeeds — which drives the worker's reconcile pass.

#### Scenario: grant_role vocabulary is constrained
- **WHEN** a `granted_entitlements` row is inserted with a `grant_role` outside `('invitee', 'inviter')`
- **THEN** the CHECK constraint rejects the insert

#### Scenario: Inviter-once partial index predicate is immutable
- **WHEN** the migration creates the `granted_entitlements_inviter_once_idx` partial-unique index
- **THEN** its `WHERE` clause uses only the constant `grant_role = 'inviter'` predicate (no `NOW()` / volatile expression) so the migration applies cleanly

#### Scenario: User hard-delete cascades grants
- **WHEN** a user referenced as a `granted_entitlements` recipient (or the ticket's parties) is hard-deleted
- **THEN** the user's `granted_entitlements` rows are removed by the `ON DELETE CASCADE` FKs

#### Scenario: Dispatch-tracking column starts NULL
- **WHEN** a `granted_entitlements` row is first inserted (before its RevenueCat dispatch)
- **THEN** `revenuecat_dispatched_at` is NULL AND is stamped only after the grant is successfully dispatched

### Requirement: Grant dispatch goes through the :infra:revenuecat client and fails soft

The worker SHALL dispatch promotional-entitlement grants via a client in `:infra:revenuecat-api` (no RevenueCat vendor type imported in `:backend:ktor`); the RevenueCat secret API key SHALL be read only through the `secretKey(env, name)` helper. When the API key is unset (an un-provisioned environment), the client SHALL fail soft — the worker still writes the `granted_entitlements` ledger and flips the ticket to `granted`, logs the un-dispatched grant, and does not throw (the `NoOpImageModerator` precedent). On a successful dispatch the worker SHALL stamp `granted_entitlements.revenuecat_dispatched_at = NOW()`; a per-run reconcile pass SHALL re-dispatch grants still `revenuecat_dispatched_at IS NULL` (a prior run's dispatch failed after the ledger row committed), bounded per run and idempotent via RevenueCat + `dedup_key`, so a transient RevenueCat failure never silently loses an earned grant. The worker SHALL NOT write `users.subscription_status`; that column is applied by the `subscription-billing-webhook` capability's `GRANT` handler when RevenueCat echoes the grant.

#### Scenario: Grant is dispatched through the infra client
- **WHEN** a grant is effected with a configured RevenueCat API key
- **THEN** the promotional-entitlement grant is sent through the `:infra:revenuecat-api` client (no vendor type appears in `:backend:ktor`) AND the key was read via `secretKey(env, name)`

#### Scenario: Unset API key fails soft to ledger-only
- **WHEN** a grant is effected in an environment where the RevenueCat API key is unset
- **THEN** the `granted_entitlements` row is still written AND the ticket is still set to `granted` AND the worker logs the un-dispatched grant AND does not throw

#### Scenario: The worker does not write subscription_status
- **WHEN** the worker effects any grant
- **THEN** it does not modify `users.subscription_status` (that transition is owned by the webhook `GRANT` handler on RevenueCat's echo)

#### Scenario: A failed dispatch is retried by the reconcile pass
- **WHEN** a grant's `granted_entitlements` row is committed but its RevenueCat dispatch failed (`revenuecat_dispatched_at IS NULL`)
- **THEN** a later worker run's reconcile pass re-dispatches it AND, on success, stamps `revenuecat_dispatched_at` so it is not retried again

### Requirement: Deferred activity-gate legs are not evaluated

The worker SHALL NOT evaluate the ≥ 3-login-days engagement leg, the ≥ 5-app-sessions engagement leg (docs/01 §212), the 90-day-windowed device-fingerprint historical check, the IP-subnet (/24) overlap check, or the recently-seen Google/Apple identifier check (docs/01 §213 item 3 — the exact set docs/05 §1186 enumerates as deferred). These legs require durable login-history / fingerprint-history data that does not exist today (refresh-token rotation deletes login history; `session_start` is a consent-gated client analytics event). They are deferred to a follow-up change that ships that tracking and MODIFIES this requirement to add them. The implemented gate is posts + expiry + inviter-eligibility; the residual abuse surface is bounded by the **exact-equality** `device_fingerprint_hash` collision check already applied at signup (a point-in-time check — distinct from, and weaker than, the deferred 90-day-windowed historical variant) and the per-inviter ≤ 3/7-day burst limit, both already enforced by `referral-ticket-creation`.

#### Scenario: Login-day and app-session legs are not consulted
- **WHEN** the worker evaluates a ticket's activity gate
- **THEN** it does not read or require any login-day count or app-session count (those legs are deferred)

#### Scenario: Historical-fingerprint, IP-subnet, and identifier legs are not consulted
- **WHEN** the worker evaluates a ticket's anti-collision posture
- **THEN** it does not perform a 90-day-windowed device-fingerprint historical check, an IP-subnet (/24) overlap check, or a recently-seen-identifier check (those legs are deferred; only the signup-time exact-equality fingerprint check already ran)

