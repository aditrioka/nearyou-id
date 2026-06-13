## 1. Schema

- [ ] 1.1 Add the `subscription_events` Flyway migration — table + the two indexes (`subscription_events_user_idx`, `subscription_events_source_idx`) verbatim per `docs/05` § Subscription Event-Level Tracking (event_type/source CHECKs, `revenuecat_event_id TEXT UNIQUE`, entitlement/amount/platform fields). Assign the **next free V-number at apply time** (current ceiling V20; coordinate with in-flight #290 which likely takes V21 — this touches a disjoint table so V21-or-V22 is fine).
- [ ] 1.2 Confirm the migration applies cleanly against a fresh PostGIS container (fresh-DB-containers gate pattern) and leaves the pre-existing V2 `users.subscription_status` column + `users_subscription_idx` untouched (no `users` migration in this change).

## 2. Data layer

- [ ] 2.1 `SubscriptionEventRepository` — `INSERT … ON CONFLICT (revenuecat_event_id) DO NOTHING` returning whether a new row was inserted (the not-a-duplicate signal); plus a typed accessor for the MRR query shape (`source = 'paid' AND event_type IN ('initial_purchase','renewal')`). On the bounded JDBC dispatcher.
- [ ] 2.2 Request DTOs (`@Serializable`, parsed with the shared `AppJson`, `ignoreUnknownKeys`) for the RevenueCat webhook envelope: event `type`, `app_user_id` (= `users.id`), event `id` (→ `revenuecat_event_id`), `reason` (for `EXPIRATION`), entitlement start/end, `store`/platform, price/amount. DTOs live with the route; map to domain in the service.

## 3. Service (state machine)

- [ ] 3.1 `SubscriptionService.process(event)` — one transaction per event: idempotency gate first (skip + signal duplicate when the event row already exists), then map event type → transition: `INITIAL_PURCHASE`/`RENEWAL` → `premium_active`; `BILLING_ISSUE` → `premium_billing_retry` (+ 7-day `grace_end_at`); `EXPIRATION` → `free`; `CANCELLATION` → no status change. Write the `subscription_events` row in the same transaction (`UPDATE users SET subscription_status` by id — privileged billing write; annotate `@AllowMissingBlockJoin` only if a lint rule flags a by-id `users` access).
- [ ] 3.2 Edge/deferred branches: orphan `app_user_id` (no matching user) → log WARN + `200`, no write; unknown event type → log + `200` ignored; `GRANT` → log + `200`, **no** status change and **no** entitlement (deferred-referral guard). `EXPIRATION` does **not** set `users.privacy_flip_scheduled_at` (deferred-privacy-flip guard); no time-based grace-elapse downgrade (deferred grace-state-worker guard).
- [ ] 3.3 Notifications via the existing `NotificationEmitter`: `subscription_billing_issue` with `body_data = {grace_end_at}` on `BILLING_ISSUE`; `subscription_expired` with `body_data = {}` on `EXPIRATION`; none on `CANCELLATION` (deferred — no catalog type). Confirm both types are in the V10 `notifications.type` CHECK (they are).

## 4. Route + vendor auth

- [ ] 4.1 `RevenueCatWebhookRoutes` — mount `POST /internal/revenuecat-webhook` via its own `routing {}` block, a SIBLING of (NOT under) the OIDC-gated `route("/internal")` node, mirroring `appleS2SRoutes` (heed the `UnbanWorkerRoute.kt` caution: Ktor merges identical path segments, so the gate must not be installed on the shared `/internal` node). Read the raw body for HMAC before deserialization.
- [ ] 4.2 Vendor auth: mandatory `Authorization: Bearer` compared constant-time (`MessageDigest.isEqual`) against `revenuecat-webhook-secret`; conditional `X-RevenueCat-Signature` HMAC-SHA256 over the raw body vs `revenuecat-webhook-hmac-secret` when the header is present. Both via `secretKey(env, name)`. Auth failure → `401` with a sanitized body + a WARN security-event log (no secret/token/signature echo).
- [ ] 4.3 DI wiring (`Application.kt`): construct + mount the route and bind `SubscriptionService` + `SubscriptionEventRepository`; verify via a test that a valid OIDC token alone yields `401` (proves the route is NOT under the OIDC gate).

## 5. Tests (docs/11 §5 DoD; kotest `@Tags("database")`, test pool `autoClose(hikari())` size 2)

- [ ] 5.1 Auth: missing Bearer → 401; wrong Bearer → 401; HMAC mismatch (signature header present) → 401; **valid OIDC token alone → 401** (fulfills the `internal-endpoint-auth` "Vendor-webhook route does NOT inherit OIDC" scenario for this route); correct Bearer (+ valid HMAC) → admitted.
- [ ] 5.2 Idempotency / envelope: first delivery records one row + applies status; duplicate `revenuecat_event_id` → `200` duplicate, single row, no re-apply; malformed body → `400`, no writes; orphan `app_user_id` → `200`, no writes; unknown event type → `200`, ignored.
- [ ] 5.3 State machine: `initial_purchase` → `premium_active` + event; `renewal` → `premium_active` + event; `billing_issue` → `premium_billing_retry` + `subscription_billing_issue` notification (`grace_end_at`) + access retained; `expiration` → `free` + `subscription_expired` notification; `cancellation` → status unchanged + event + no notification.
- [ ] 5.4 Deferred guards: `GRANT` → no status change, no entitlement, `200`; `EXPIRATION` on a `private_profile_opt_in = TRUE` user → `free` AND `privacy_flip_scheduled_at` stays `NULL`; a `premium_billing_retry` user past the grace window with no `EXPIRATION` → NOT auto-downgraded.
- [ ] 5.5 Analytics: the MRR query (`source = 'paid' AND event_type IN ('initial_purchase','renewal')`) returns only the paid purchase/renewal rows after a mixed-event run.

## 6. Reconciliation, gates & DoD

- [x] 6.1 File a `follow-up` GitHub issue: `docs/05:1179` mis-attributes `subscription_events` to "(V9)"; this change is its actual first creator. Do NOT rewrite the doc in this change. — **Done during proposal B.3: [#295](https://github.com/aditrioka/nearyou-id/issues/295).**
- [ ] 6.2 Full local gate green before push: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` (fresh DB containers per the full-gate pattern).
- [ ] 6.3 Pre-archive staging branch deploy + smoke (runtime-impacting backend, DoD item 4): unauthenticated `POST /internal/revenuecat-webhook` → `401` (route mounted + vendor-gated + migration applied). Record the evidence in the PR body.
- [ ] 6.4 PR title/body refreshed at the implementation boundary (`feat(backend): …`); spec + change archived via `/opsx:archive` after CI green.
