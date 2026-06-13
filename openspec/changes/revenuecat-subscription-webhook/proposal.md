## Why

Premium-gated features already **read** `users.subscription_status` (e.g. `premium-search`), but no code **writes** it from a real billing source — the RevenueCat webhook is DESIGN-only (`docs/05` § "RevenueCat Webhook — DESIGN": endpoint not mounted, `subscription_events` table absent). Mobile-first is complete (the `project.md` flip trigger fired), so Phase 4 (premium/payment) is the roadmap-order next priority. This webhook is the foundational first slice of Phase 4: every downstream subscription behavior (privacy-flip worker, grace-state worker, referral grants) depends on it existing first.

## What Changes

- **New `subscription_events` table** (canonical shape from `docs/05` § Subscription Event-Level Tracking) — the event-level audit trail that revenue analytics require (a user-level flag loses transition information). One Flyway migration; the V-number is assigned at `/opsx:apply` time (touches a table disjoint from in-flight PR #290's `chat_messages` migration, so sequence-agnostic).
- **New endpoint `POST /internal/revenuecat-webhook`** — a vendor-auth webhook (RevenueCat calls it from the internet), mounted **outside** the OIDC `InternalEndpointAuth` gate, mirroring the existing `appleS2SRoutes` vendor-webhook precedent. Authenticates via `Authorization: Bearer` (constant-time compare against `revenuecat-webhook-secret`) + `X-RevenueCat-Signature` HMAC-SHA256 (against `revenuecat-webhook-hmac-secret`); both secret slots are already reserved (`docs/05:22`) and read via the `secretKey(env, name)` helper. Auth failure → `401` + a WARN security-event log feeding the existing "RevenueCat webhook signature fail rate" anomaly alert.
- **Idempotency** via `subscription_events.revenuecat_event_id UNIQUE` (`INSERT … ON CONFLICT DO NOTHING`; a re-delivered event returns `200` without re-applying state — financial events must be exactly-once).
- **3-state subscription status machine (PAID path)** writing `users.subscription_status` (column exists since V2): `INITIAL_PURCHASE`/`RENEWAL` → `premium_active`; `BILLING_ISSUE` → `premium_billing_retry` (access remains) + 7-day grace + `subscription_billing_issue` notification; `EXPIRATION` (grace elapsed) → `free` + `subscription_expired` notification; `CANCELLATION` → no status change (stays `premium_active` until the period ends).
- **Explicitly deferred** (each captured as a spec requirement with a negative-guard scenario so the follow-up has a concrete MODIFY target): the referral `GRANT` path; the 72h privacy-flip coupling; the time-based grace-elapse downgrade worker; the `CANCELLATION` confirmation notification (no catalog type yet); the **mandatory-production-signing** hardening (Bearer-only is accepted until the operator confirms RevenueCat dashboard signing — enforcing it prematurely would reject every live webhook); the network-layer IP allowlist (Cloud Armor / operator infra).

## Capabilities

### New Capabilities
- `subscription-billing-webhook`: inbound RevenueCat billing-event processing — vendor (Bearer + HMAC) authentication on `POST /internal/revenuecat-webhook`, idempotent event ingestion into `subscription_events`, the paid-path subscription-status state machine over `users.subscription_status`, and the billing/expiry notification writes.

### Modified Capabilities
<!-- None. `internal-endpoint-auth` already specifies the vendor-webhook opt-out AND names `/internal/revenuecat-webhook` by name in its "Vendor-webhook route does NOT inherit OIDC" scenario — this change fulfills that existing scenario rather than modifying the spec. `users.subscription_status` (V2) and the `subscription_billing_issue`/`subscription_expired` notification types (V10 catalog) already exist; this change is their first writer, which is new behavior owned by the new capability, not a requirement change to those specs. -->

## Impact

- **New code** (`:backend:ktor`): `RevenueCatWebhookRoutes` (thin route, own auth) → `SubscriptionService` (state machine + transaction) → `SubscriptionEventRepository` + the existing `UserRepository` + `NotificationEmitter`. No new module; no RevenueCat SDK on the backend (inbound webhook parses JSON directly — the `:infra:revenuecat` module stays DESIGN).
- **Schema**: one new table (`subscription_events`) + two indexes; one Flyway migration. No change to `users` schema (column pre-exists).
- **Secrets**: consumes two already-reserved GCP Secret Manager slots (`revenuecat-webhook-secret`, `revenuecat-webhook-hmac-secret`; `staging-` mirrored). Slot **values** are operator setup; the code builds and tests with synthetic payloads + test secrets, no live RevenueCat account required.
- **Specs**: fulfills the pre-written `internal-endpoint-auth` vendor-opt-out scenario for this route (a test lands here; no spec edit).
- **Docs reconciliation**: (1) `docs/05` § Referral System mis-attributes `subscription_events` to "(V9)"; this change is its actual first creator — corrected via `follow-up` [#295](https://github.com/aditrioka/nearyou-id/issues/295) (no doc rewrite here). (2) `docs/08` § Phase 4's `CANCELLATION` "confirmation notification" has no matching `notifications.type` catalog value (V10 catalog), so the cancellation notification is deferred (a guard requirement), not shipped here.
