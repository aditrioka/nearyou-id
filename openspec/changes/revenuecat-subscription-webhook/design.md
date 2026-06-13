## Context

RevenueCat is the subscription source of truth (`docs/01` § Payment Stack): its SDK wraps Google Play Billing + StoreKit, and it POSTs server-to-server webhooks on every billing event. The backend side is DESIGN-only today (`docs/05` § "RevenueCat Webhook — DESIGN"): `POST /internal/revenuecat-webhook` is not mounted, and the `subscription_events` table does not exist (despite `docs/05:1179` mis-attributing it to "(V9)" — it is created here). `users.subscription_status` already exists (V2, `CHECK IN ('free','premium_active','premium_billing_retry')`) and is already read by premium-gating (`premium-search`), but nothing writes it from a real billing source.

The closest existing precedent is `appleS2SRoutes` (`backend/ktor/.../auth/routes/AppleS2SRoutes.kt`): a vendor-auth webhook on `/internal/*` that authenticates with its own scheme and is mounted **outside** the OIDC `InternalEndpointAuth` gate. The `internal-endpoint-auth` spec already sanctions this for `/internal/revenuecat-webhook` by name (its "Vendor-webhook route does NOT inherit OIDC" scenario), so this change implements a pre-approved seam rather than introducing a new one.

## Goals / Non-Goals

**Goals:**
- Make Premium status real: drive `users.subscription_status` from authenticated RevenueCat billing events.
- Durable, exactly-once event processing with an event-level audit trail (`subscription_events`) for revenue analytics.
- Reuse the existing vendor-webhook + backend-layering patterns — no new architectural patterns.

**Non-Goals (each captured as an explicit deferral requirement in the spec):**
- Referral `GRANT` entitlement stacking (needs `granted_entitlements` / `referral_tickets` — future referral change).
- The 72h privacy-flip scheduling + its acting worker (future privacy-flip-worker change).
- The time-based grace-elapse auto-downgrade worker (future grace-state worker; here only an `EXPIRATION` event downgrades).
- The `CANCELLATION` confirmation notification (no catalog type yet).
- Network-layer IP allowlist (Cloud Armor / operator infra, not app code).
- The RevenueCat mobile KMP SDK + dashboard product setup (mobile + operator).

## Standards conformance (docs/11 Pattern Registry — anti-patchwork)

All patterns are REUSED; this change introduces **no new pattern** and therefore needs **no `docs/11` amendment**.

- **§3.1 Backend layering**: `RevenueCatWebhookRoutes` (thin — auth/parse/validate/respond, no SQL) → `SubscriptionService` (state machine + transaction boundary, never touches `ApplicationCall`) → `SubscriptionEventRepository` + the existing `UserRepository` + `NotificationEmitter`.
- **Vendor-webhook-auth seam**: mirrors `appleS2SRoutes` (own auth via `routing {}`, mounted outside the OIDC gate). Existing pattern — not a fork.
- **§3.2 JDBC discipline**: the bounded `Dispatchers.IO.limitedParallelism(poolSize)` dispatcher; one transaction per event; test pool `autoClose(hikari())` size 2 (CI connection budget, PR #157).
- **§3.3 HTTP/serialization**: the shared `AppJson` instance; `StatusPages` single error envelope.
- **Secrets invariant**: both slots read via `secretKey(env, name)` — no direct secret-name reads.

## Decisions

### D1 — Vendor auth: mandatory Bearer + conditional HMAC, both constant-time
`Authorization: Bearer <revenuecat-webhook-secret>` is mandatory and compared with `MessageDigest.isEqual` (constant-time). `X-RevenueCat-Signature` HMAC-SHA256 over the raw body is verified against `revenuecat-webhook-hmac-secret` **when the header is present** (RevenueCat signs only if signing is enabled in its dashboard). Rationale: Bearer is the always-on gate; HMAC is defense-in-depth that activates when the operator enables signing. **Alternative rejected**: the OIDC `InternalEndpointAuth` gate — RevenueCat cannot mint a Google-issued OIDC token, so OIDC is structurally wrong for this caller; the spec already carves the route out. Auth failures log a WARN security event (feeding the signature-fail anomaly alert, `docs/05:30`) and never echo secret material.

### D2 — Idempotency via DB `UNIQUE`, not in-memory dedup
Idempotency uses `subscription_events.revenuecat_event_id UNIQUE` (`INSERT … ON CONFLICT (revenuecat_event_id) DO NOTHING`; a 0-row insert means "already processed" → `200` duplicate, skip the state transition). **Alternative rejected**: the in-memory `InMemoryDedup` ring buffer that `appleS2SRoutes` uses — acceptable for Apple's idempotent relay-email toggles, but billing events are financial and must stay exactly-once across instance restarts and horizontal scale-out, which only a durable DB constraint guarantees.

### D3 — One transaction per event
The `subscription_events` insert, the `users.subscription_status` update, and any notification insert for a single event run in one transaction, so a crash mid-event cannot leave the status changed without its audit row (or vice-versa). The duplicate short-circuit happens first (the conflicting insert is the idempotency gate inside the transaction).

### D4 — `subscription_events` schema verbatim; migration number deferred to apply-time
The table is created exactly as `docs/05` § Subscription Event-Level Tracking specifies (columns, CHECKs, two indexes). The Flyway file number is assigned at `/opsx:apply` (current ceiling V20). In-flight PR #290 (`admin-chat-message-redaction`) will likely take V21 for a `chat_messages` migration; this migration touches a **disjoint** table, so it is sequence-agnostic — V21 if this lands first, V22 otherwise. No edit to the `users` migration (the column pre-exists at V2).

### D5 — `EXPIRATION` is the authoritative terminal downgrade signal
The webhook downgrades to `free` on an `EXPIRATION` event. The 7-day grace timer set on `BILLING_ISSUE` is surfaced (the `grace_end_at` in the notification) but the **time-based** auto-downgrade (a stuck `premium_billing_retry` user with no `EXPIRATION`) is the deferred grace-state worker's job — modeled as an explicit deferral so a later worker change has a clean seam. This keeps the webhook a pure event processor and avoids embedding a scheduler in a request handler.

### D6 — User resolution by `app_user_id = users.id`; orphan events are acked, not retried
The RevenueCat `app_user_id` is the `users.id` UUID (set at SDK init in the future mobile change). The handler resolves the user by id. If no user matches (orphan event — should be rare), the handler logs WARN and returns `200` (acknowledged) **without** a DB write — the `subscription_events.user_id` FK would reject an orphan row anyway, and returning non-2xx would make RevenueCat retry the unresolvable event indefinitely. The by-id `users` write (`UPDATE users SET subscription_status = ? WHERE id = ?`) is a privileged server-side billing write, not a viewer-scoped feed read, so the block-exclusion-join / shadow-ban-view invariants do not apply (precedent: the suspension/unban worker writes `users` directly). If a by-id `users` lookup trips `BlockExclusionJoinRule`, annotate it `@AllowMissingBlockJoin` with the billing-write rationale.

### D7 — No RevenueCat SDK on the backend
The inbound webhook deserializes the JSON envelope directly with `AppJson` — no RevenueCat server SDK is added, so the `:infra:revenuecat` module stays DESIGN. The vendor-SDK-leakage invariant is satisfied trivially (no SDK import). The mobile RevenueCat KMP SDK is a separate future change.

### D8 — HTTP response contract
`200` for processed, duplicate, orphan, and unknown-event-type (ignored, logged — mirrors `appleS2SRoutes`'s "ignored" branch) so RevenueCat marks delivery complete; `400` for a malformed body; `401` for any auth failure. Only auth failures and malformed bodies are non-2xx (and thus retried by RevenueCat).

## Risks / Trade-offs

- **HMAC is conditional on the header being present** → a misconfigured dashboard (signing off) leaves only Bearer. Mitigation: Bearer is mandatory and high-entropy; the operator runbook (Phase 4 deploy) MUST enable RevenueCat signing in production; the signature-fail alert surfaces tampering attempts.
- **Orphan `app_user_id` events are dropped (acked, not stored)** → if app-user-id wiring regresses, billing events could silently no-op. Mitigation: the orphan path logs WARN; the orphan rate is observable; app_user_id wiring is covered by the future mobile SDK change's tests.
- **`subscription_events` grows unbounded** → it is an append-only financial ledger; no purge in this change (unlike notifications' 90-day purge). Acceptable — event volume is low (one row per billing transition per user) and analytics need full history.
- **Doc divergence (`docs/05:1179` "(V9)")** → corrected out-of-band via a `follow-up` issue, not in this change (avoids scope creep into docs).

## Migration Plan

1. Add the `subscription_events` Flyway migration (next free V-number at apply-time).
2. Wire `RevenueCatWebhookRoutes` outside the OIDC `/internal` gate (mirror `appleS2SRoutes` mount); add `SubscriptionService` + `SubscriptionEventRepository`; bind via DI.
3. Read the two secret slots via `secretKey(env, name)`; the slots are already reserved — values are operator setup (synthetic test secrets suffice for the build + tests).
4. Deploy: this is a runtime-impacting backend change → pre-archive staging branch deploy + smoke (`docs/11` §5 DoD item 4). Smoke: an unauthenticated `POST /internal/revenuecat-webhook` returns `401` (route mounted + vendor-gated + migration applied).
5. **Rollback**: the route is additive and the table is new; reverting the deploy removes the endpoint with no data-model coupling to existing features (the pre-existing `subscription_status` column simply stops receiving writes).

## Open Questions

- None blocking. The `EXPIRATION`-grace interpretation (D5) follows `docs/01:124`'s "after 7 days **or** `EXPIRATION` with `BILLING_ERROR` reason → free": this change implements the event-driven half; the time-based half is the deferred grace-state worker. If review reads `docs/08:240`'s "check grace, downgrade if elapsed" as requiring an in-handler grace check, that is captured as the deferred grace-state requirement rather than embedded here.
