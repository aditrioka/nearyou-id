## Why

Referral ticket creation shipped (`referral-ticket-creation`, V23, [PR #327](https://github.com/aditrioka/nearyou-id/pull/327)): `POST /api/v1/auth/signup` now best-effort inserts `referral_tickets` rows in `pending_activity`. But **nothing processes them** — there is no activity-gate worker, no `granted_entitlements` table, and the subscription webhook's `GRANT` branch is an explicit no-op. Tickets accumulate inertly and pay out nothing, so the freemium referral growth loop is half-built. This change ships the second half — the activity-gate worker + entitlement grants — that `docs/05-Implementation.md` §1180–1188 names as "the future change", closing the functional gap.

## What Changes

- **NEW `granted_entitlements` table (V29)** — the per-grant ledger that also enforces idempotency and the inviter lifetime cap at the DB level.
- **NEW `/internal/referral-activity-check` worker** — a daily Cloud-Scheduler-invoked, OIDC-gated endpoint that scans `pending_activity` tickets, expires stale ones, runs the activity gate, flips successful tickets to `granted`, and dispatches grants. Reuses the `/internal/*` OIDC worker pattern (`privacy-flip-worker` [PR #321](https://github.com/aditrioka/nearyou-id/pull/321) precedent).
- **NEW grant dispatch via the RevenueCat Granted Entitlements API** — each grant is 1 week of Premium with native RevenueCat stacking (extend the current period by 1 week if the recipient is `premium_active`; a fresh 1-week trial if `free`/lapsed), recorded as a `subscription_events(source = 'referral', event_type = 'grant')` row.
- **Invitee reward**: one grant per invitee, tied to their own registration ticket. **Inviter reward**: exactly one per inviter lifetime, fired at the confirmed **5th** successful referral — enforced two ways (the existing `users.inviter_reward_claimed_at` sentinel + a `granted_entitlements` partial-unique index on `grant_role = 'inviter'`).
- **Activity gate ships the durable legs only**: invitee has ≥ 2 posts inside the ticket window AND the ticket is not expired AND the inviter is still eligible (not banned). **DEFERRED** (no durable data source exists yet, per docs/05 §1186): the ≥ 3-login-days and ≥ 5-app-sessions engagement legs (docs/01 §212) and the IP-subnet (/24) + recently-seen-identifier anti-collision legs (docs/01 §213 item 3). The residual abuse surface is bounded by the already-shipped signup-time device-fingerprint check + the per-inviter 3/week burst limit; grants are worth only 1 week of Premium.
- **MODIFIES** the subscription webhook's deferred `GRANT` handler into real `GRANT` handling, and flips/narrows the three `referral-ticket-creation` "out of scope" guards.

## Capabilities

### New Capabilities
- `referral-grant-worker`: the referral activity-gate worker, the `granted_entitlements` schema, the grant dispatch + 1-week stacking, the invitee per-ticket cap, the inviter lifetime single-grant at the 5th referral, worker idempotency, and explicit negative-guard requirements for the deferred gate legs.

### Modified Capabilities
- `subscription-billing-webhook`: flip the **"Referral GRANT handling is deferred"** requirement to real `GRANT` handling — record `source = 'referral'` grant events and apply the entitlement (premium activation + stacking).
- `referral-ticket-creation`: flip the **"Activity-gate worker is out of scope"** and **"Entitlement grants are out of scope"** guards (both now shipped here), and **narrow** the **"Richer anti-collision checks are out of scope"** guard to the still-deferred IP-subnet + recently-seen-identifier legs.

## Impact

- **`:backend:ktor`** — new referral worker route + service + repository (docs/11 §3 backend layering); MODIFY the RevenueCat webhook `GRANT` branch; the worker mounts under the existing `/internal/*` OIDC middleware.
- **`:infra:revenuecat`** — add the outbound Granted Entitlements API client (a new outbound surface on the existing module; no vendor SDK import escapes `:infra:*`).
- **DB** — one Flyway migration **V29** (`granted_entitlements` + `granted_entitlements_inviter_once_idx` partial-unique). Reuses `referral_tickets` (V23, whose `granted`/`expired` status values + scan indexes are already reserved), `subscription_events` (V21, `source` already accepts `'referral'`), and `users.inviter_reward_claimed_at` (V2 sentinel).
- **Secrets** — a RevenueCat REST API key slot, read via the `secretKey(env, name)` helper only.
- **Ops** — a new Cloud Scheduler job invoking `/internal/referral-activity-check` (prod provisioning task; does not block the squash-merge).
- **No mobile surface** — backend-only; nothing in `:mobile:app` changes.
