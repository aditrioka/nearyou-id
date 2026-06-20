## Context

The referral funnel's first stage shipped in `referral-ticket-creation` (V23, [PR #327](https://github.com/aditrioka/nearyou-id/pull/327)): `POST /signup` inserts one `referral_tickets` row per eligible invitee in `status = 'pending_activity'`, `expires_at = created_at + 14 days`. The `granted` / `expired` status values are already reserved in the table's `CHECK`, and two indexes already exist for the worker (`referral_tickets_pending_idx` partial on `status='pending_activity'`; `referral_tickets_inviter_status_idx` on `(inviter_user_id, status)`). Nothing advances a ticket out of `pending_activity` today.

The subscription side shipped in `subscription-billing-webhook` (V21): `POST /internal/revenuecat-webhook` is the authoritative writer of `users.subscription_status` ∈ {`free`, `premium_active`, `premium_billing_retry`} and records the `subscription_events` ledger (`source` already accepts `'referral'`; `event_type` already accepts `'grant'`). That spec has an explicit **"Referral GRANT handling is deferred"** requirement that no-ops `GRANT` events and states the future referral change MUST MODIFY it.

This change is the second stage: the worker that runs the activity gate, the `granted_entitlements` ledger, and the grant dispatch. Canonical behavior: `docs/01-Business.md` §137–143 (Granted Entitlement Stacking) + §195–241 (Referral System); `docs/05-Implementation.md` §1180–1188 (names this exact future change); `docs/08-Roadmap-Risk.md` Phase 4 #9/#21/#23.

**Standards conformance (docs/11 §3 — backend).** This is a backend-only change; it builds on the **backend layering** Pattern-Registry pattern (route → service → repository; JDBC discipline, no ORM; one transaction per logical write) and the established **`/internal/*` OIDC worker** pattern (`privacy-flip-worker` PR #321; `GoogleOidcTokenVerifier` in `:infra:oidc`). The outbound RevenueCat REST client lives in `:infra:revenuecat-api` behind an interface (no vendor surface escapes `:infra:*`). No new Pattern-Registry pattern is introduced, so no docs/11 amendment is required.

## Goals / Non-Goals

**Goals:**
- Advance `pending_activity` tickets: expire stale ones; flip activity-passing ones to `granted`.
- Grant 1 week of Premium per successful referral — invitee every time, inviter exactly once at the 5th — with DB-level idempotency and a DB-level inviter lifetime cap.
- Make the RevenueCat webhook's `GRANT` branch real (record `source='referral'` events + apply the entitlement).
- Be safe to run concurrently / repeatedly (Cloud Scheduler at-least-once delivery): no double grants.

**Non-Goals:**
- The login-days, app-sessions, 90-day-windowed device-fingerprint, IP-subnet (/24), and recently-seen-identifier gate legs — **no durable data source exists** (refresh-token rotation deletes login history; `session_start` is a consent-gated Amplitude client event). Captured as explicit negative-guard requirements; a follow-up MODIFIES them once login-history tracking lands.
- Building that login-history tracking infrastructure.
- Any mobile surface (the invite-code entry in Settings already exists from ticket creation; grant outcomes surface through the existing premium-status read path).
- Mandatory-production webhook signing, the grace-elapse worker (owned elsewhere).

## Decisions

### D1 — `/internal/referral-activity-check`, daily, OIDC-gated, set-based scan
A single daily Cloud-Scheduler-invoked endpoint under the existing `/internal/*` mandatory-OIDC middleware (Google OIDC ID-token via `GoogleOidcTokenVerifier`), mirroring `privacy-flip-worker`. It runs two passes over `referral_tickets WHERE status='pending_activity'` (served by `referral_tickets_pending_idx`):
1. **Expire**: `WHERE expires_at < NOW()` → `status='expired'` (no grant). Set-based `UPDATE`.
2. **Evaluate**: remaining rows → run the activity gate (D5); on pass, `status='granted'` + dispatch grants (D3).

*Alternative considered:* event-driven grant at the moment the invitee's 2nd post lands. Rejected — couples the post path to referral logic, can't re-evaluate expiry/inviter-ban cleanly, and the 14-day window is naturally a daily-cron shape. The worker is the docs-named design (docs/08 #23).

*Audit trail:* the worker deliberately writes **no** per-grant `admin_actions_log` row (unlike the `privacy-flip-worker` precedent it otherwise mirrors). A referral grant is a system *reward*, already ledgered in `granted_entitlements` + `subscription_events(source='referral')`, not an admin action *against* a user — so the moderation-shaped audit trail does not apply; the two ledgers are the authoritative record.

### D2 — `granted_entitlements` ledger is the idempotency + lifetime-cap authority (V29)
```
granted_entitlements (
  id                 UUID PK DEFAULT gen_random_uuid(),
  referral_ticket_id UUID NOT NULL REFERENCES referral_tickets(id) ON DELETE CASCADE,
  user_id            UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,   -- recipient
  grant_role         VARCHAR(16) NOT NULL CHECK (grant_role IN ('invitee','inviter')),
  entitlement_start  TIMESTAMPTZ NOT NULL,
  entitlement_end    TIMESTAMPTZ NOT NULL,
  dedup_key          TEXT NOT NULL,           -- RevenueCat-call idempotency token
  created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (referral_ticket_id, user_id)        -- docs/01 §143: guards invitee grant + dup-5th attempts
);
CREATE UNIQUE INDEX granted_entitlements_inviter_once_idx
  ON granted_entitlements (user_id) WHERE grant_role = 'inviter';   -- DB-level lifetime cap
CREATE UNIQUE INDEX granted_entitlements_dedup_key_idx ON granted_entitlements (dedup_key);
```
- **Invitee cap**: `UNIQUE(referral_ticket_id, user_id)` — one invitee grant per ticket; `INSERT ... ON CONFLICT DO NOTHING` makes the worker idempotent (docs/01 §143).
- **Inviter lifetime cap**: enforced **two ways** (belt + suspenders, both DB-level) — the partial-unique index above (one `grant_role='inviter'` row per recipient, ever) **and** the existing `users.inviter_reward_claimed_at` sentinel (V2), set in the same transaction as the inviter grant. The sentinel is the fast pre-check; the partial index is the structural guarantee against a concurrent double-fire.
- **No `NOW()` in any index predicate** (partial-index immutability invariant) — the only partial predicate is the constant `grant_role='inviter'`.
- FK `ON DELETE CASCADE` on both refs mirrors `referral_tickets` (a grant for a hard-deleted user/ticket is meaningless).

### D3 — Grant via the RevenueCat **v1** Promotional Entitlements API; stack the duration **in our code**
The grant mechanism is RevenueCat's **v1** promotional-entitlement grant (`POST /v1/subscribers/{app_user_id}/entitlements/{entitlement_id}/promotional`, `app_user_id = users.id`, `entitlement_id = 'premium'`), authenticated with the project **secret API key** read via `secretKey(env, name)`.

*Apply-time module correction (operator-approved 2026-06-20):* the client lives in a **new JVM module `:infra:revenuecat-api`**, NOT the existing `:infra:revenuecat` — that one is a mobile-only KMP module (the #309 paywall SDK; `androidTarget` + iOS, no JVM target), so the JVM backend cannot depend on it, and the `VendorSdkLeakageScan` rule forbids the client living in `:backend:ktor`. The new module mirrors `:infra:cloud-vision` exactly (interface + live impl + fail-soft NoOp + raw Ktor client), the canonical backend-outbound-vendor-REST precedent. The apply-phase v1 endpoint re-check (tasks 1.1) confirmed the shape below.

**Why v1, not v2** *(verified 2026-06-20 via dated WebSearch — substrate re-check)*: the v2 `…/actions/grant_entitlement` action "grants an entitlement **unless one already exists**" and treats a grant within 2 h of an active expiry as a duplicate that **won't extend** — so docs/01 §139's "native RC GRANT stacking (extend by 1 week)" does **not** hold on v2, and RevenueCat's own docs note v2 "is under development and does not yet cover all v1 use cases." v1 is the mature promotional-entitlement surface. Sources: [RevenueCat v2 grant endpoint](https://www.revenuecat.com/v2.3/reference/grant-a-promotional-entitlement), [Promotional Subscription Extensions](https://www.revenuecat.com/docs/guides/promotional-subscription-extensions), [API v2 status](https://www.revenuecat.com/docs/api-v2).

**Stacking is computed by us, not delegated to RC's native behavior** (more robust than relying on RC's near-duplicate heuristic): the new entitlement end is `max(current_entitlement_end, NOW()) + INTERVAL '7 days'` — extend-if-active, fresh-1-week-if-free/lapsed (docs/01 §139–140) — passed as the grant's absolute `end_time_ms`. The recipient's `current_entitlement_end` is read from their latest effective `subscription_events` / status. A unique `dedup_key` (`<referral_ticket_id>:<grant_role>`) is sent so a retried RC call is a no-op on RevenueCat's side too.

*Alternative considered:* the worker writes `subscription_status` directly + records `subscription_events(source='referral')` without calling RC. Rejected — RevenueCat is the entitlement source of truth; a DB-only grant would desync from the client's `CustomerInfo` fetch (the app would not see Premium). docs/01 §204 mandates the RC API.

### D4 — The webhook `GRANT` echo remains the `subscription_status` writer
The worker calls the RC promotional API and writes the `granted_entitlements` ledger + flips the ticket; it does **not** write `subscription_status`. RevenueCat then fires a `GRANT` webhook, and the now-un-deferred handler (MODIFY of `subscription-billing-webhook`) records `subscription_events(source='referral', event_type='grant')` and applies `subscription_status='premium_active'` — preserving that spec's invariant that *the webhook is the authoritative writer of `subscription_status`*. The handler stays idempotent via the existing `revenuecat_event_id UNIQUE` guard.

*Trade-off:* a small eventual-consistency window between the worker's RC call and the echo (see Risks). Accepted — it matches the existing paid-purchase path's webhook-authoritative model exactly.

### D5 — Activity gate ships only the durable legs; the rest are explicit negative guards
**Implemented (durable):** invitee has **≥ 2 posts** authored within `[ticket.created_at, NOW()]` (counted from the `posts` table — own-content read, no shadow-ban/block join needed for a self count) **AND** `ticket.expires_at >= NOW()` **AND** the inviter is in good standing — **`is_banned = FALSE` AND `is_shadow_banned = FALSE`** (docs/01 §233: "inviter ban — *shadow or hard* — voids all pending tickets"; both are distinct V2 `users` columns, so a ticket whose inviter is banned by either flag is flipped to `expired`, never granted).

**Deferred (negative-guard requirements):** the ≥ 3-login-days and ≥ 5-app-sessions engagement legs (docs/01 §212) and the **90-day-windowed device-fingerprint** + IP-subnet (/24) + recently-seen-identifier anti-collision legs (docs/01 §213 item 3 — the exact three legs docs/05 §1186 enumerates as deferred). Rationale: no durable server-side source today (docs/05 §1186). The residual abuse exposure is bounded by the **already-shipped** signup-time `device_fingerprint_hash` collision check — note this is an *exact-equality, point-in-time* check, NOT the deferred 90-day-windowed historical variant — plus the per-inviter ≤ 3/7-day burst limit, and each grant is worth only 1 week of Premium. Captured as spec requirements so a follow-up has a positive target to MODIFY (the `feedback_defer_as_explicit_requirement` pattern).

**Inviter-eligibility legs deliberately NOT re-evaluated at grant time:** docs/01 §213 item 4 (**account age > 30 days**) is enforced at ticket-creation and is monotonic (age only increases), so re-checking adds nothing; the **exact-equality fingerprint** check (item 3, point-in-time) likewise already ran at signup. Only the ban-status legs (`is_banned` / `is_shadow_banned`) are re-checked by the gate, because they are the only inviter-eligibility signals that can flip between signup and the grant run.

### D6 — Inviter 5th-referral counting
"Successful referral" = a ticket reaching `status='granted'`. After a ticket is granted, count `referral_tickets WHERE inviter_user_id = ? AND status='granted'` (served by `referral_tickets_inviter_status_idx`). When that count reaches exactly **5** AND `users.inviter_reward_claimed_at IS NULL`, fire the inviter grant (role=`inviter`, tied to this 5th ticket) and set `inviter_reward_claimed_at = NOW()` in the same transaction. Counts 1–4 grant nothing to the inviter; 6+ reward only the invitee (the sentinel + partial index both block any second inviter grant). Within-worker ordering: process the invitee grant first (flip to `granted`), then re-count for the milestone — so the 5th ticket's own transition is included in the count.

## Risks / Trade-offs

- **RC `GRANT` webhook echo never arrives / is delayed** → `subscription_status` stays stale though `granted_entitlements` shows the grant. *Mitigation:* RevenueCat retries webhook delivery; the ledger is the source of truth for "did we grant"; a reconciliation/backfill check can compare `granted_entitlements` to `subscription_events(source='referral')` (flagged as a follow-up, not built here). The window matches the existing paid-path model.
- **v1 promotional-grant API shape differs from the design at implement time** → grant calls fail. *Mitigation:* the RC client is fenced behind an interface in `:infra:revenuecat-api` with a fail-soft no-op when the API key is unset (the `NoOpImageModerator` precedent), so the worker degrades to ledger-only without 500ing; an apply-phase dated re-check (tasks 1.x) confirms the exact v1 endpoint/auth/duration shape before wiring.
- **Worker partial failure mid-batch** (grant N succeeds, N+1's RC call throws) → some tickets granted, some still pending. *Mitigation:* per-ticket transactions (not one batch transaction); `ON CONFLICT DO NOTHING` makes the next run re-process the stragglers safely. The worker returns a summary; a non-zero failure count is logged, not fatal.
- **Clock/window skew on the 14-day expiry** → a ticket expiring mid-run. *Mitigation:* expiry and evaluation both read `NOW()` once per ticket; an expired-but-active-enough ticket simply waits for tomorrow's run if it crosses the boundary — no correctness issue (grants are not time-critical).
- **Inviter ban after grant** → already-granted Premium not clawed back. *Accepted* — docs/01 §233 voids only *pending* tickets; retroactive claw-back is out of scope.

## Migration Plan

1. **V29** `granted_entitlements` migration (forward-only; new table + indexes, no backfill — there are no historical grants). Reuses existing `referral_tickets`/`users` columns.
2. Ship the worker mounted but inert until Cloud Scheduler is wired — it is safe to deploy ahead of the schedule (it only runs when invoked, and an empty/all-pending scan is a no-op for non-activity tickets).
3. MODIFY the webhook `GRANT` branch (no schema change — `subscription_events` already supports it).
4. **Rollback**: the worker is additive; disabling the Cloud Scheduler job halts all grants with no data corruption. The V29 table is orphaned-but-harmless if the worker is reverted. `subscription_status` is never written by this change directly, so a revert cannot leave a user wrongly Premium beyond what RC already granted.
5. **Prod provisioning** (post-squash, non-blocking): create the Cloud Scheduler job + the `revenuecat-secret-api-key` Secret Manager slot (staging slot for the branch smoke).

## Open Questions

- **Exact RevenueCat v1 promotional-grant request shape** — `duration` enum (`"weekly"`) vs absolute `end_time_ms`; whether v1 natively extends an active promo or whether our computed absolute end is required. To be confirmed by the apply-phase dated re-check against RevenueCat's live v1 reference before the client is wired; if v1 cannot express our extend-by-7-days semantics, fall back to computing the absolute end and passing `end_time_ms`. Does not block proposal review (the interface seam is stable; only the `:infra:revenuecat-api` impl detail is open).
- **`subscription_events` entitlement window on the `GRANT` echo** — confirm RevenueCat's `GRANT` webhook payload carries the promo `entitlement_start`/`end` we set, so the handler records them faithfully rather than recomputing. Apply-phase verification.
