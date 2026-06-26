## Context

`login-history-tracking` (V35, #387) shipped `login_events` — an append-only, per-user, security-purpose record of every sign-in / refresh carrying `occurred_at`, `ip`, a generated `ip_subnet_24` (`network(set_masklen(ip,24))`), `device_fingerprint_hash`, and `identifier_hash`, with a `(user_id, occurred_at DESC)` index. Its stated purpose is to feed anti-abuse legs. The first such leg the roadmap calls out — `docs/05-Implementation.md` § Anomaly Detection Metrics ("same `sub` issued from >5 geographic locations in 1h"; roadmap echoes at `docs/08` Phase 1 #29 "Anomaly detection metrics" + Phase 3.5 #19 "Anomaly detection dashboards") — is **not yet built**. (`docs/08` Phase 4 #17 "rolling 30-day baseline per user" is the distinct *image-delivery* anomaly leg, deferred here.) Note `docs/05:30` frames this metric's output as a Sentry + Slack *alert*; this change instead writes the docs/05:530-sanctioned durable `moderation_queue` `anomaly_detection` row (a reviewable/actionable surface) and defers the real-time alert hook. Meanwhile `moderation_queue` already reserves `trigger='anomaly_detection'` and `target_type='user'` in its CHECK constraints with a `UNIQUE(target_type, target_id, trigger)` for idempotency and a `notes TEXT` column — i.e. the durable output surface is already in place. This change adds the missing detector.

Current admin consumption of anomaly signals: **none yet.** `/admin/reports` (`admin-report-queue`) lists the `reports` table and surfaces `moderation_queue` only as per-report *context* (LATERAL join + a `trigger` filter) — a `moderation_queue` anomaly row with no user-submitted `reports` row would not appear there. The `admin-operational-dashboard` "anomaly spike-alert banner" is in that spec's explicitly-deferred widget cluster (its line-132 deferred-follow-up). So the durable signal currently has no dedicated read surface (handled below as a declared docs/12 §3 deferral).

## Goals / Non-Goals

**Goals:**
- Detect per-user login-source spread (distinct `/24` subnets > 5 in a trailing hour) over `login_events`.
- Record each detection as an idempotent, non-PII `moderation_queue` row (`target_type='user'`, `trigger='anomaly_detection'`).
- Ship via the established OIDC-gated internal-worker pattern with zero schema change and zero new substrate.
- Be fail-soft per user and leak no PII.

**Non-Goals:**
- The other docs/05 anomaly metrics (JWT-verify fail-rate, >50 realtime channels/5 min, RevenueCat signature fail-rate), the image-delivery / Phase 4 #17 rolling-baseline anomaly (feature-flag-gated off), and the username-flagged anomaly-score increment — all explicitly deferred.
- The Sentry + Slack alert *delivery* docs/05:30 frames this metric with — deferred; the durable `moderation_queue` row is the MVP review surface, the alert hook is a fast-follow.
- A dedicated admin anomaly-review UI — deferred (docs/12 §3), the durable signal is produced now.
- Device-fingerprint-spread as a second detection axis — a noted future extension, not in this change.
- Any real-time / inline-on-auth detection — this is a periodic batch sweep (Cloud Scheduler), matching the existing worker cadence.

## Standards conformance (docs/11 § Pattern Registry)

This change builds **only on already-registered patterns; it introduces NO new pattern** (so no docs/11 amendment task):
- **Backend layering** — Route → Service → Repository with JDBC discipline. The detection query lives in a Repository (parameterized `PreparedStatement`, no string interpolation); the sweep orchestration in a Service; the HTTP surface in a thin Route.
- **OIDC-gated internal worker** — mirrors `RetentionCleanupRoutes` / `privacyFlipWorkerRoute` / `accountHardDeleteWorkerRoute`: `route("/login-anomaly-check") { install(InternalEndpointAuth){ verifier = oidcVerifier }; post { … } }` mounted under the parent `route("/internal")`, gate on the worker's **own** node (the `InternalRoutingIsolationTest`-guarded rule), shared `classifyHandlerError` for the sanitized `500`.
- **`internal-endpoint-auth`** OIDC verification (the sanctioned gate for `/internal/*`).
- No second networking/data/HTTP pattern is introduced.

## Cross-layer scope (docs/12-Integration-Contracts.md)

- **Backend (in scope):** the detection worker + the durable `moderation_queue` signal. This is the full backend wire contract for the capability.
- **Admin read surface (declared-deferred, docs/12 §3):** a UI listing user-target `anomaly_detection` rows is deferred to a mockup-board-governed admin change; the spec carries an explicit "admin anomaly-review surface is deferred" requirement (positive: the durable signal is produced; negative-guard: this change adds no admin HTML/Pebble route and writes no `reports` row) + a tracked `follow-up`. The `admin-operational-dashboard` anomaly-spike-banner follow-up is the natural consumer and now gains a real data source.
- **Mobile:** not applicable (internal anti-abuse capability; nothing user-facing).

This is **not** an undeclared single-layer slice — the consuming admin layer is named and deferred with a guard, per docs/12 §3.

## Decisions

### Decision 1 — Detection axis: distinct `ip_subnet_24` count, not precise geo-IP
docs/05 phrases the rule as ">5 geographic locations in 1h", but `login_events` deliberately stores no precise geo-IP — only the `/24` subnet (a privacy-minimized coarse network location, purpose-built for these legs). We map "geographic location" → distinct `ip_subnet_24`. **Threshold: strictly > 5 distinct non-NULL subnets** (a flag fires at 6+); **window: trailing 1 hour.** Both are named constants/config (tunable without rewriting the query). NULL subnets are excluded from the distinct count.
- *Alternative considered:* integrating a geo-IP database for true geographic spread — rejected: adds an external dataset + dependency + drift risk for marginal precision over the `/24` proxy, and contradicts the data-minimization rationale that made `login_events` store only the subnet.
- *Alternative considered:* device-fingerprint-spread as the primary axis — deferred as a secondary axis; subnet-spread maps directly to the canonical docs/05 rule.

### Decision 2 — Output: reuse `moderation_queue` (zero migration)
Record each detection as a `moderation_queue` row (`target_type='user'`, `target_id=user_id`, `trigger='anomaly_detection'`, `status='pending'`, non-PII `notes`). Both enum values are already valid → **no Flyway migration**, so the footprint is pure `:backend:ktor` Kotlin and parallel-merge-safe against the migration-bumping in-flight changes (referral, data-export). Idempotency via the existing `UNIQUE(target_type, target_id, trigger)` → `ON CONFLICT DO NOTHING`.
- *Alternative considered:* a new `security_anomaly_events` table — rejected: adds a migration (version-collision risk with parallel sessions), a new read surface, and a second moderation ledger, for no benefit over the table the admin moderation loop already consumes. `moderation_queue` is the canonical anti-abuse ledger and the reserved `anomaly_detection` trigger is exactly this.

### Decision 3 — Periodic batch worker, not inline-on-auth
Detection runs as a Cloud Scheduler-driven `POST /internal/login-anomaly-check` sweep, matching the cadence of the existing `/internal/cleanup`, privacy-flip, and hard-delete workers — rather than an inline check on every sign-in (which would add latency to the hot auth path and re-scan the window on every event). Fail-soft per user so one user's transient error never aborts the sweep.
- *Alternative considered:* inline detection in the auth route after recording the login event — rejected: couples anti-abuse scanning to the latency-sensitive auth path and duplicates work; the batch sweep is cheaper and matches the established worker pattern.

### Decision 4 — `notes` carries only a non-PII aggregate
`moderation_queue.notes` gets a short descriptor (distinct-subnet count + window), never an IP / subnet value / identifier hash. The only PII-adjacent value leaving the worker is `user_id` (written as `target_id`, already a non-secret primary key). Logs are PII-free on both the success and error paths.

## Risks / Trade-offs

- **Re-detection after resolution is a no-op.** `UNIQUE(target_type, target_id, trigger)` + `ON CONFLICT DO NOTHING` means once a user has *any* `anomaly_detection` row (even one an admin already `resolved`), a later sweep will not enqueue a new one. → *Mitigation/accepted:* the original row is the durable record of the event; the moderator resolution closes the loop; re-arming after resolution (e.g. clearing or versioning the row) is a deliberately deferred enhancement (tracked follow-up). This matches the existing `moderation_queue` "one row per item+trigger" semantics and is acceptable for the launch security bar.
- **`/24` is a coarse location proxy.** Mobile carriers / CGNAT can place legitimate users on several subnets in an hour; the threshold (>5) is set well above casual movement and is tunable. → *Mitigation:* the signal is advisory (a moderator-review queue row), not an automated punitive action — no account is suspended/banned by this worker. False positives cost a moderator glance, not a user lockout.
- **No admin read surface yet.** The signal is queryable but has no dedicated UI this change. → *Mitigation:* declared-deferred per docs/12 §3 with a follow-up; the signal is durable and feeds the already-tracked operational-dashboard anomaly-banner follow-up.
- **Sweep cost on a large `login_events`.** The windowed `GROUP BY user_id HAVING COUNT(DISTINCT ip_subnet_24) > N` over the trailing hour is served by the `(user_id, occurred_at DESC)` index and bounded by the 90-day retention; the trailing-1h filter keeps the scanned set small. → *Mitigation:* parameterized window bound; no new index needed.

## Migration Plan

- **Schema:** none (zero migration).
- **Deploy:** standard squash-merge → staging auto-deploy. Pre-archive, smoke the OIDC-gated worker against a manual staging branch deploy (expect `401` without a valid OIDC bearer; `200` with one).
- **Ops (operator task, surfaced in Preflight):** create a Cloud Scheduler job invoking `POST /internal/login-anomaly-check` with an OIDC-authenticated identity for the configured internal audience (mirroring the existing `/internal/cleanup` schedule). Not a code task; tracked as a human-required Preflight item.
- **Rollback:** disable the Scheduler job; the endpoint is otherwise inert (idempotent, additive-only writes). No data migration to reverse.

## Open Questions

- Final threshold/window tuning (>5 / 1h) — the docs value is the starting point; left as a named constant so it can be tuned from operational data without a code-shape change. (Not blocking; the docs-specified default is used.)
