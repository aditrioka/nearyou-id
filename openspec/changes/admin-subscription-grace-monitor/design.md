## Context

The `revenuecat-subscription-webhook` change ([PR #291](https://github.com/aditrioka/nearyou-id/pull/291)) shipped the billing event path: on a `BILLING_ISSUE` event the handler sets `users.subscription_status = 'premium_billing_retry'` (the user keeps premium during a 7-day grace) and records a `subscription_events` row. There is no admin-facing view of that population. docs/07 § Core Features → Subscription Grace Monitor and admin mockup **frame 18** (`GET /admin/subscriptions/grace`, currently "Usulan") specify the surface: list the billing-retry users and offer a manual **expedite** control for support-desk triage.

Current admin building blocks this change consumes, all shipped:
- `users.subscription_status` + `users_subscription_idx` partial index (`ON users (subscription_status) WHERE deleted_at IS NULL`) — V2.
- `subscription_events` ledger (event_type / source / platform / created_at / entitlement_start|end) — V21, with `subscription_events_user_idx (user_id, created_at DESC)`.
- `admin_actions_log` — V16; `action_type VARCHAR(64)` with **no CHECK**, immutable at the `admin_app` role (no UPDATE/DELETE).
- The `admin-destructive-action-rate-limit` limiter mechanism (audit-trail-as-ledger, trailing-hour, soft cap, inline "quota exceeded") — shipped.
- The read-only-admin-viewer pattern shipped 4× (`admin-privacy-flip-monitor`, `admin-rejected-identifiers-viewer`, `admin-block-registry`, report-queue viewer): keyset pagination, composable filters, count summary, HTML-escaped HTMX + plain-`GET` fallback, identity-only PII.

## Goals / Non-Goals

**Goals:**
- A read surface listing `premium_billing_retry` users with store/platform, retry-since, latest webhook, and an already-expedited indicator — the 5th read-only-admin-viewer instance.
- A `POST .../expedite` **bookkeeping** action: audit-logged, CSRF-/role-/rate-limit-gated, that records a support-ticket resolution **without** touching entitlement.
- Zero schema migration; disjoint footprint from all in-flight changes.

**Non-Goals:**
- **No entitlement mutation.** Expedite does not grant, extend, downgrade, or end premium — RevenueCat stays the entitlement source of truth (mockup frame 18 banner is explicit). Any "force downgrade now" capability is explicitly out of scope and would be a separate, riskier decision.
- **No subscription grace-expiry worker.** The daily grace-state-machine cleanup worker (docs/08 Phase 4 item 5) is a separate backend change; this is the admin read/triage surface only.
- **No user notification** from expedite (the `subscription_billing_issue` notification is already emitted by the webhook; expedite is admin-internal).
- No new `subscription_events` rows (would pollute the MRR/ARR ledger — docs/01 § Subscription Analytics Integrity).

## Decisions

**D1 — Expedite is audit-log bookkeeping, not an entitlement change.** Per the canonical mockup banner ("bukan memberi premium gratis; entitlement tetap dari RevenueCat" — *not free premium; entitlement stays from RevenueCat*), expedite writes one `admin_actions_log` row (`action_type = 'subscription_grace_expedite'`) and mutates nothing else; `before_state`/`after_state` carry an identical `subscription_status` to make the no-op-on-entitlement explicit and testable. *Alternative considered — force-downgrade-to-free-now:* rejected. It would let an admin override RevenueCat's authoritative entitlement state (an integrity hole), contradicts the mockup, and risks cutting off a paid-up user mid-grace. The support need ("user paid but webhook is late") is served by *recording the resolution*, not by faking the outcome.

**D2 — Rate-limit reuses the destructive-limiter mechanism but on a distinct counter.** Expedite is gated at 20/hour/admin using the exact `admin-destructive-action-rate-limit` mechanics (immutable log as ledger, in-transaction soft count, inline "quota exceeded"), filtered to `action_type = 'subscription_grace_expedite'`. **Implementation:** a dedicated `GraceExpediteActionRateLimiter` mirroring the existing distinct-counter `ReservedUsernameActionRateLimiter` — **not** a direct reuse of `DestructiveActionRateLimiter`, whose count SQL is hardcoded to the destructive set. *Alternative A — fold expedite into the existing destructive set:* rejected — that set is deliberately "user-punitive / content-removal" (warn/suspend/ban/shadow-ban/redact); expedite is non-punitive bookkeeping, so folding it in pollutes that capability's clean semantics and would let support bookkeeping starve the moderation budget (and vice-versa). *Alternative B — no rate limit:* rejected — a phished admin session (TOTP is phishable) could spam the audit trail; a bound is cheap. The 20/hour value mirrors the destructive cap as a sensible support-desk-batch-friendly default and is tunable.

**D3 — Single keyset query, no N+1.** One SQL statement: `users` filtered to `premium_billing_retry AND deleted_at IS NULL` (index-served by `users_subscription_idx`), LEFT JOIN LATERAL the latest `subscription_events` row per user (store/platform + last webhook), LEFT JOIN LATERAL the latest `subscription_grace_expedite` `admin_actions_log` row per user (the handled indicator), keyset-ordered newest-retry-first. *Alternative — per-row follow-up queries:* rejected per docs/11 §3.2 JDBC discipline (the #1 backend perf rule). LEFT JOINs keep the surface empty-state tolerant (a billing-retry user with no events/expedites still renders).

**D4 — No migration.** All reads hit existing tables/indexes; the new `action_type` value needs no schema change (unconstrained VARCHAR). This keeps the change's Flyway footprint empty, so it squash-merges in parallel with any migration-bearing in-flight change without V-number contention.

**D5 — Read open to any admin role; write restricted to `owner`/`admin`.** Matches the monitor-read precedent (reads are broadly visible) and the chat-redaction write precedent (`role IN ('owner','admin')`). CSRF is enforced on the write only (reads are idempotent GETs), and is validated **before** the role gate (per the `AdminChatRedactionRoute` precedent) so a CSRF violation is audited as `admin_csrf_violation` rather than masked by a silent role-403.

**D7 — Expedite validates target-state.** Expedite is accepted only against a non-deleted `premium_billing_retry` user — the population the surface lists. A target in any other `subscription_status`, a soft-deleted user, or an unknown id is rejected with no mutation and no audit row, preventing bookkeeping noise (and a misleading before/after snapshot carrying a non-`premium_billing_retry` status) against a user the monitor never shows. *Surfaced by the proposal-review test-coverage lens.*

**D6 — `retry-since` derivation.** The "retry-since" timestamp reflects when the current billing-retry window began, derived from `subscription_events` as the earliest `billing_issue` event in the current retry streak (i.e. not preceded by a resolving `renewal`/`initial_purchase`); "latest webhook" is the most recent event regardless of type. Where the streak cannot be cleanly determined (sparse/legacy data), the surface falls back to the most recent `billing_issue` timestamp and never errors. Exact SQL finalized at apply; the spec only requires a sensible, empty-state-tolerant timestamp.

## Standards conformance

Builds on existing **docs/11-Engineering-Standards.md** Pattern-Registry patterns; introduces **no new pattern** (no docs/11 amendment required):
- **§3.1 Backend layering** — route → service → JDBC, no business logic in templates.
- **§3.2 JDBC & connection discipline** — single keyset query (D3), no N+1, parameterized.
- **§3.6 Admin panel UI (Pebble + HTMX)** — server-rendered Pebble template + HTMX partial + plain-`GET` fallback; vendored CSS; matches the read-only-admin-viewer pattern and the admin mockup board (frame 18) as the binding visual target.
- **Reused capabilities (declared, not re-implemented):** the read-only-admin-viewer pattern (`admin-privacy-flip-monitor` et al.) and the `admin-destructive-action-rate-limit` limiter mechanism (D2). Reuse, not a parallel pattern — the expedite limiter extends the audit-trail-as-ledger trailing-hour limiter with a distinct counter rather than inventing a second rate-limit mechanism.

Critical-invariant conformance: the admin module is exempt from the `visible_*` views, and the block-exclusion / raw-`FROM posts` lint rules do **not** fire on `FROM users` / `FROM subscription_events` (the shipped admin monitors — e.g. `AdminPrivacyFlipsRepository` — carry no such annotation), so no functional lint annotation is required for this read (only a descriptive KDoc note of the admin exemption); `admin_actions_log` writes are append-only (never UPDATE/DELETE); all user-controlled output is HTML-escaped and all filter inputs are bound as parameterized literals; no secret reads; no `:infra:*` vendor import.

## Risks / Trade-offs

- **Expedite misread as "give premium back"** → the action name, the mockup/spec banner, the identical before/after `subscription_status` snapshot, and the Non-Goals all make the bookkeeping-only semantics explicit; a test asserts `subscription_status` is unchanged after expedite.
- **`retry-since` ambiguity with multiple `billing_issue` events** → D6 documents a pragmatic streak rule with a safe fallback; the spec requires only a sensible empty-state-tolerant timestamp, so implementation latitude does not break the contract.
- **`subscription_events.platform` may be NULL/absent for some billing-retry users** → LEFT JOIN + empty-state rendering (the user still lists; the store cell is blank).
- **Soft cap ±1 under concurrency** → accepted, identical to the destructive limiter's documented tolerance; expedite is non-destructive bookkeeping so a rare ±1 overshoot is harmless.
- **Admin raw `users` / `subscription_events` read and the lint rules** → the block-exclusion / raw-`FROM posts` rules do not fire on these tables (the shipped admin monitors carry no such annotation — `AdminPrivacyFlipsRepository` precedent), so only a descriptive KDoc note of the admin exemption is needed; add a functional annotation only if a rule actually fires at build time (consistent with the Standards-conformance note above).

## Migration Plan

No database migration. Deploy ships with the `admin` service. Pre-archive staging smoke (per the one-PR convention): seed a `premium_billing_retry` user + a `billing_issue` `subscription_events` row on staging, confirm the row lists at `api-staging.nearyou.id/admin/subscriptions/grace`, exercise an expedite (assert one `subscription_grace_expedite` `admin_actions_log` row + unchanged `subscription_status`), and confirm a CSRF-less expedite is 403'd. Rollback is trivial (read-only + append-only audit; no schema or entitlement state to revert).

## Open Questions

- **Expedite cap value (20/hour):** proposed as a support-desk-batch-friendly default mirroring the destructive cap; flag for operator confirmation if a different bound is preferred. Non-blocking — tunable without a spec change.
- **Per-store filter vocabulary:** the `store`/`platform` filter values depend on what the webhook writes to `subscription_events.platform` (e.g. `app_store` / `play_store`); confirm the exact stored vocabulary at apply against the shipped webhook handler.
