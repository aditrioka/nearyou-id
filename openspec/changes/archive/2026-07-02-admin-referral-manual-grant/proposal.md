## Why

When the automated referral activity-gate (`referral-grant-worker`) false-negatives a legitimate referral — a real invited user whose activity legs the gate could not confirm, or an inviter whose 5th-referral milestone was mis-evaluated — there is today **no operator path to make the user whole**. Support tickets accumulate with no remedy. [Open Decision #15](../../../docs/08-Roadmap-Risk.md) resolves this: "include a minimal path in Phase 3.5 — critical for user trust." This is the last unbuilt piece of the referral funnel: the automated worker, ticket creation, and the grant-dispatch infrastructure all shipped; the mobile invite flow (#426) is in flight; only the admin support-desk remedy (admin board **frame 19**, [docs/07 § Referral Manual Grant Path](../../../docs/07-Operations.md)) remains.

## What Changes

- **New admin surface** `GET/POST /admin/referral-grants` (admin board frame 19, Pebble + HTMX) — a support-desk-only path to manually grant a Premium entitlement, explicitly "a support-desk remedy, not a referral-system action" (frame 19: *"bukan aksi sistem"*).
- **Lookup + context view** — admin searches a user by username or UUID and sees current premium / `subscription_status` plus referral context, to verify the support claim before granting.
- **Manual grant write action** — issues a 1-week promotional Premium grant:
  - Dispatch via the **existing** `ReferralEntitlementGranter` port (`:infra:revenuecat-api`) — its `GrantRequest` is referral-agnostic; fail-soft `NoOpReferralEntitlementGranter` when RevenueCat is unconfigured. Stacking math `endTimeMs = GREATEST(current_entitlement_end, NOW()) + 7d` (extend-if-active / fresh-if-free).
  - Writes **one immutable `admin_actions_log` row** (`action_type = 'referral_manual_grant'`) as the **authoritative manual-grant record**, CSRF- and `role IN ('owner','admin')`-gated, `hx-confirm` guard, required support-ticket reason.
  - The `subscription_events` ledger row + Premium activation are owned by the existing GRANT **webhook echo** (`SubscriptionService`, `source = 'referral'`), mirroring how `referral-grant-worker` already records grants — so this change preserves the "webhook echo owns `users.subscription_status`" invariant rather than writing it directly. (design.md **D3** weighs this against a direct `subscription_events(source='manual_admin')` write — the V21 schema reserves that vocab — and explains why the echo-mirroring path is the robust primary; **no migration** either way.)
  - Gated by a **new distinct restorative rate-limiter** (~10/admin/hour, sourced from the `admin_actions_log`-as-ledger; **NOT** the 20/hr destructive cap — granting is restorative, not user-punitive), mirroring the `GraceExpediteActionRateLimiter` / `DeletionQueueExpediteRateLimiter` precedent.
- **Deliberately does NOT write `granted_entitlements`** — keeping the manual grant counting against neither the inviter's 5-referral lifetime track nor the single lifetime inviter reward (docs/07 intent: "not a referral-system action").
- **Read viewer** — keyset-paginated list of past manual grants (the Nth instance of the read-only-admin-viewer pattern, cloning `SubscriptionGraceRepository` / `AdminPrivacyFlipsRepository` keyset + lenient parameterized filters).
- **Docs reconciliation** — docs/07 § Referral Manual Grant Path currently states the grant "writes `granted_entitlements` with `source='manual_admin'` + `grant_role='manual_admin'`"; this is **stale** against the shipped V32 `granted_entitlements` schema (no `source` column; `grant_role` CHECK allows only `'invitee'`/`'inviter'`; `referral_ticket_id NOT NULL` — no ticket exists for a support grant). Reconciled via a `follow-up` issue to describe the as-built mechanism (RC promotional grant + the GRANT webhook echo + an `admin_actions_log` audit row), not the stale `granted_entitlements` prose.

## Capabilities

### New Capabilities
- `admin-referral-manual-grant`: The admin "Referral Manual Grant Path" — a CSRF- and owner/admin-role-gated, rate-limited, audit-logged support-desk surface that issues a manual 1-week promotional Premium grant via the existing RevenueCat promotional-grant port, plus a read-only keyset-paginated viewer of past manual grants. The **authoritative record is one immutable `admin_actions_log` row**; Premium activation and the `subscription_events` row stay owned by the existing GRANT webhook echo (`source='referral'`). The admin path **never writes `subscription_events`, `users.subscription_status`, or `granted_entitlements`** directly (design.md **D3**).

### Modified Capabilities
None. The existing `subscription-billing-webhook` GRANT-echo handler is reused **unchanged** — it already records the `subscription_events` row + activates `users.subscription_status` for any RevenueCat promotional `GRANT` (the path the referral worker relies on), so a manual grant fires the identical echo with no requirement delta (design.md **D3**).

## Impact

- **New code** — `backend/ktor/.../admin/referralgrants/` (repository + routes + Pebble templates), a new `ReferralGrantActionRateLimiter` in `admin/ratelimit/`, an `AdminAuditLogger` method + an `admin_actions_log` `action_type = 'referral_manual_grant'`, route mounting in `AdminModule.kt` / `Application.kt`, and a sidebar/nav entry.
- **Reused infra** — `ReferralEntitlementGranter` (`:infra:revenuecat-api`) for RC dispatch; the existing `SubscriptionService` GRANT webhook echo for the `subscription_events` row + `users.subscription_status` activation; the admin auth/CSRF/role + read-only-viewer + audit-ledger-rate-limiter patterns.
- **No migration** — the grant reuses `admin_actions_log` (new `action_type` value, no schema change) + the V21 `subscription_events` vocab; nothing new is created.
- **Coordination with `subscription-billing-webhook`** — the RC promotional grant fires a `GRANT` webhook echo (which owns `users.subscription_status`). design.md must resolve double-counting / source-attribution so the admin path and the echo do not produce conflicting or duplicate `subscription_events` rows.
- **Operator dependency** — the real grant dispatch requires the RevenueCat secret API key slot to be configured; unconfigured environments fail soft (ledger row written, RC call skipped). Surfaced at preflight.
- **Docs** — `follow-up` issue to fix docs/07 § Referral Manual Grant Path (`granted_entitlements` → `subscription_events`).
