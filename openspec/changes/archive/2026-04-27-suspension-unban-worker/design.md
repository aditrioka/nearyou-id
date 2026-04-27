## Context

[`docs/06-Security-Privacy.md:287-294`](docs/06-Security-Privacy.md) defines two distinct admin actions on a single `users` row:

- **7-day suspension**: `UPDATE users SET is_banned = TRUE, suspended_until = NOW() + INTERVAL '7 days', token_version = token_version + 1`. Daily worker flips back when the window elapses.
- **Permanent ban**: same but `suspended_until = NULL`. No automatic unban.

The `age-gate` change (V3) shipped the `users.suspended_until TIMESTAMPTZ` column + the partial index `users_suspended_idx ON users(suspended_until) WHERE suspended_until IS NOT NULL` ([`docs/05-Implementation.md:226,242`](docs/05-Implementation.md)). The application-side suspend / permanent-ban writers do not exist yet (they'll ship with the Admin Panel in Phase 3.5), but the schema is ready.

[`docs/05-Implementation.md:349-363`](docs/05-Implementation.md) provides the canonical SQL for the worker; [`docs/06-Security-Privacy.md:413-451`](docs/06-Security-Privacy.md) defines the OIDC auth contract for `/internal/*` endpoints.

The just-shipped [`health-check-endpoints`](openspec/changes/archive/2026-04-27-health-check-endpoints/) change established two relevant precedents:
- **Module-boundary discipline**: the "no vendor SDK outside `:infra:*`" invariant means the OIDC verifier must live in `:infra:oidc` (or similar), not in `:backend:ktor`.
- **Fail-fast boot**: missing required config (`SUPABASE_URL`) throws at boot rather than silently failing requests later. The same pattern applies to `INTERNAL_OIDC_AUDIENCE`.

This change is the first `/internal/*` route in the codebase. There is no existing OIDC verification surface to reuse.

**Critical reconciliation finding** (caught during Phase B step 7): the canonical `admin_actions_log` table — referenced by [`docs/05-Implementation.md:363`](docs/05-Implementation.md) ("Audit log inserted per unban") — has not shipped yet. Neither has `admin_users`. Both are deferred to Phase 3.5 (the Admin Panel build). [`backend/ktor/src/main/resources/db/migration/V9__reports_moderation.sql`](backend/ktor/src/main/resources/db/migration/V9__reports_moderation.sql) explicitly carries the deferral note on `reports.reviewed_by` and `moderation_queue.reviewed_by`: *"FK to admin_users(id) ON DELETE SET NULL — deferred to the Phase 3.5 admin-users migration."* This change therefore CANNOT write `admin_actions_log` rows — the table doesn't exist. Audit-row writes are scope-deferred to a follow-up change that lands after Phase 3.5; tracked in `FOLLOW_UPS.md`. See D3 below.

Stakeholders:
- Cloud Scheduler (the only legitimate caller of `/internal/unban-worker`)
- Solo operator (debugging "why is user X still suspended past their 7 days?" via the audit log)
- Admin Panel (Phase 3.5) — will read the same `admin_actions_log` rows from the Moderation Actions Log UI

Constraints:
- "No vendor SDK outside `:infra:*`": the OIDC verifier (Auth0 `jwks-rsa` + `java-jwt` per D10) must be encapsulated behind a `:core:domain` interface.
- `secretKey(env, name)` helper required for any GCP Secret Manager reads (lint rule per [`openspec/project.md`](openspec/project.md)). Note: `INTERNAL_OIDC_AUDIENCE` is a public Cloud Run service URL, NOT secret material, so it is read via plain Ktor config rather than `secretKey()` — see D10 + spec scenarios.
- Direct `UPDATE users SET ...` is allowed in this surface (worker writes `is_banned` + `suspended_until`, not `username` or `private_profile_opt_in`, so neither lint rule fires).
- WIB-stagger / hash-tag conventions do not apply here — this is not a rate-limited endpoint.

## Goals / Non-Goals

**Goals:**

- Implement the canonical UPDATE in a single-statement transaction with the exact `WHERE` predicate from [`docs/05-Implementation.md:357-361`](docs/05-Implementation.md). Return the affected row count to the caller.
- Implement Google-OIDC bearer-token verification as a Ktor plugin mounted on the `/internal/*` route subtree. Verify signature against Google JWKS, audience claim against configured value, expiry against current time. 401 on any failure.
- Emit a structured INFO log per worker run (`event="suspension_unban_applied"`, `unbanned_count`, capped list of affected user IDs) so OTel + Cloud Logging carry an operational trail until `admin_actions_log` lands in Phase 3.5.
- Document Cloud Scheduler config (cron, target URL, OIDC service account, retry policy) so staging + prod deploys are reproducible.
- Composability: the `internal-endpoint-auth` plugin must be mountable per-route or per-subtree, so future endpoints with vendor-specific auth (`/internal/revenuecat-webhook` Bearer + HMAC; `/internal/csam-webhook` admin-triggered + HMAC) can opt out cleanly.

**Non-Goals:**

- The Admin Panel write paths that *create* time-bound suspensions — those ship in Phase 3.5 with the rest of `admin-panel`. This change only handles the elapse path.
- The privacy-flip worker, hard-delete worker, and other `/internal/*` consumers — they reuse this change's `internal-endpoint-auth` capability but ship in separate Phase 4 / 3.5 changes.
- Cloud Run ingress restriction (Pre-Phase 1 #39: Cloud Armor + CF allowlist). Operational deployment concern; the OIDC plugin is the token-level layer.
- Production deploy workflow. Same staging-first pattern as `health-check-endpoints`; production analog is a future change.
- Notifications on unban. Recommended NOT to send (see D5); explicit non-goal for this change. If reversed, would be a follow-up change touching the `in-app-notifications` capability.
- A "force unban now" admin action. Different surface; admin-side override path that ships with the Admin Panel.
- Rate-limiting `/internal/*` endpoints. The OIDC token + GCP IAM Cloud-Scheduler-only-invoke is the auth model; a misbehaving caller is by definition the legitimate Cloud Scheduler service-account, not abuse traffic.

## Decisions

### D1: Single-statement transactional UPDATE — verbatim from canonical docs

**Decision**: The worker runs the canonical SQL from [`docs/05-Implementation.md:353-361`](docs/05-Implementation.md) byte-for-byte:

```sql
UPDATE users
SET is_banned = FALSE,
    suspended_until = NULL
WHERE is_banned = TRUE
  AND suspended_until IS NOT NULL
  AND suspended_until <= NOW()
  AND deleted_at IS NULL
RETURNING id;
```

(`RETURNING id` added to surface the affected user IDs for audit-log writes; otherwise verbatim.) Wraps in a single transaction. Reads `unbanned_count = result.size`.

**Rationale**: Three predicate conjuncts express three orthogonal eligibility rules:
- `is_banned = TRUE` filters out users who were never banned (defensive — the partial index already means we only see rows where `suspended_until IS NOT NULL`, but the predicate makes the intent explicit and protects against a future where `suspended_until` is reused for non-ban semantics).
- `suspended_until IS NOT NULL` excludes permanent bans.
- `suspended_until <= NOW()` is the actual elapse trigger.
- `deleted_at IS NULL` excludes soft-deleted accounts (can't unban a tombstoned user).

The partial index `users_suspended_idx` makes this an index scan, not a full table scan.

**Alternatives considered**:
- Two-step: SELECT eligible IDs, then UPDATE per-ID — rejected (more code, more round-trips, no benefit).
- Add a `LIMIT 1000` to bound batch size — rejected for MVP (suspension volume is administrator-driven, not user-driven; expected throughput is < 100 unbans/day even at peak).

**Sub-decision: `users.token_version` is NOT bumped on unban.** [`docs/05-Implementation.md:199`](docs/05-Implementation.md) prescribes that the suspend write path increments `token_version` AND deletes refresh tokens. By the time the worker fires, the suspended user has zero live tokens (already deleted at suspend time) and `token_version` is already at its post-suspend value. When the user signs back in after their suspension elapses, they obtain a brand-new JWT with the current `token_version` and the auth middleware reads `users.is_banned = FALSE` from the now-flipped row. There is no residual cached principal state to invalidate. The canonical SQL in [`docs/05-Implementation.md:353-361`](docs/05-Implementation.md) deliberately omits `token_version` from the UPDATE; this design follows. (Adding the bump anyway is harmless but pointless; keeping it out matches canonical docs and saves an `UPDATE users` write.)

### D2: OIDC verification via Google JWKS — interface in `:core:domain`, impl in `:infra:oidc`

**Decision**: Introduce `OidcTokenVerifier` interface in `:core:domain` (or the closest existing equivalent module, see Open Questions Q1) with one method:

```kotlin
interface OidcTokenVerifier {
    suspend fun verify(token: String): VerifiedClaims  // throws on failure
}
```

Concrete `GoogleOidcTokenVerifier` lives in `:infra:oidc`, fetches JWKS from `https://www.googleapis.com/oauth2/v3/certs` (cached via `JwkProvider` with rotation-aware TTL), verifies signature + `exp` + `iat`, and asserts `aud` claim equals the configured `INTERNAL_OIDC_AUDIENCE`.

The Ktor plugin `InternalEndpointAuth` lives in `:backend:ktor`, depends only on the `OidcTokenVerifier` interface, parses the `Authorization: Bearer ...` header, and either passes through (200 path) or short-circuits with 401 (any failure).

**Rationale**: Mirrors the established `RateLimiter` (interface in `:core:domain`, Lettuce impl in `:infra:redis`) and `RedisProbe` / `SupabaseRealtimeProbe` (interfaces in `:core:domain`, impls in infra) precedents from `like-rate-limit` and `health-check-endpoints`. The "no vendor SDK outside `:infra:*`" invariant prohibits `:backend:ktor` from importing JWKS / nimbus-jose-jwt directly.

**Alternatives considered**:
- Inline JWKS fetch + verify in the Ktor plugin — rejected by the invariant.
- Use Ktor's built-in `jwt()` Auth feature — viable, but couples our verifier identity to Ktor's plugin lifecycle and complicates the unit-test surface (we'd need a Ktor test app to exercise the verifier in isolation). Wrapping in our own `OidcTokenVerifier` interface with a Ktor plugin façade gives us pure JVM unit tests for the verification logic.
- Reuse the existing Supabase JWT verifier — rejected: Supabase tokens are HS256 (shared-secret); Google OIDC tokens are RS256 (asymmetric). Different verifier, different key source.

### D3: Audit logging deferred to a Phase 3.5 follow-up — structured INFO log in the interim

**Decision**: This change does NOT write `admin_actions_log` rows. The target table does not exist yet (deferred to Phase 3.5 per V9's explicit comments and per the project phase plan). Instead, on every worker run, emit one structured INFO log event with shape:

```
event="suspension_unban_applied"
unbanned_count=<int>
unbanned_user_ids=[<up-to-50-uuids>]
duration_ms=<long>
```

The `unbanned_user_ids` array is capped at the first 50 entries to bound log-line size in pathological data scenarios; a separate metric (Sentry counter or equivalent) tracks the absolute `unbanned_count` for completeness. The raw token bytes from the OIDC verification path are never logged.

**Rationale**: The earlier draft (rejected during Phase B step 7 reconciliation) proposed seeding a sentinel `system` admin user so audit rows could be written against a NOT-NULL `admin_actions_log.admin_id` FK. That approach is invalidated by reality: **`admin_users` and `admin_actions_log` haven't shipped yet** ([`backend/ktor/src/main/resources/db/migration/V9__reports_moderation.sql:20,73,111`](backend/ktor/src/main/resources/db/migration/V9__reports_moderation.sql) carries explicit "deferred to the Phase 3.5 admin-users migration" comments on the analogous `reviewed_by` columns). The correct phase boundary is:

- **This change (Phase 1)**: ship the worker + OIDC + structured logs. Operational visibility comes from Cloud Logging + Sentry breadcrumbs, which is the same trail used for every other backend operational signal today (no `admin_actions_log` consumer exists yet).
- **Phase 3.5 admin-panel build**: ships `admin_users` + `admin_actions_log` schema + the Admin Panel UI. At that point a follow-up change wires audit-row writes through this worker (and through every other `/internal/*` worker shipped in the interim — privacy-flip, hard-delete, etc.), backed by a seeded `system` admin user as originally envisioned.

This boundary matches the project's incremental phase discipline and avoids pulling Phase 3.5 schema into a Phase 1 worker change.

**Alternatives considered**:

- **Build `admin_users` + `admin_actions_log` schema in this change**: significant scope expansion. Pulls 4+ tables (`admin_users`, `admin_actions_log`, plus the `admin_sessions` and `admin_webauthn_*` companions implied by an actually-bootable schema) into a worker change. The Admin Panel UI still doesn't ship until Phase 3.5, so the audit trail is write-only until then. Rejected as scope creep that doesn't match the project's "thin slices" cadence (the recent `fcm-token-registration`, `health-check-endpoints`, and other Phase 1 closeouts each shipped one or two tightly-scoped capabilities, not multi-table schema dumps).

- **Add a minimal one-table `system_actions_log` now**, distinct from `admin_actions_log`, that the worker writes to and that gets folded into `admin_actions_log` at Phase 3.5. Plausible but introduces a non-canonical table name + a future merge migration. The structured-log path achieves the same operational visibility without the schema cost.

- **Original D3 (seed `system` admin user)**: invalidated by reality — `admin_users` does not exist. Reinstate at Phase 3.5 follow-up.

**Follow-up entry in `FOLLOW_UPS.md`**: `suspension-unban-worker-audit-log-after-phase-3.5` — when `admin_users` + `admin_actions_log` ship, file a follow-up change that adds the audit-row write to this worker (and applies the same pattern to every other `/internal/*` worker shipped in the interim).

### D4: Schedule = daily 04:00 WIB via Cloud Scheduler

**Decision**: Cloud Scheduler cron `0 21 * * *` UTC = `0 4 * * *` WIB (UTC+7), invoking `POST ${SERVICE_URL}/internal/unban-worker` with an OIDC token bound to a dedicated service account (`unban-worker-scheduler@<project>.iam.gserviceaccount.com`). Retry policy: 3 attempts, exponential backoff with min 30s and max 5min. Job names: `nearyou-unban-worker-staging` and `nearyou-unban-worker-prod`.

**Rationale**: Verbatim from [`docs/05-Implementation.md:351`](docs/05-Implementation.md) ("Cloud Scheduler daily at 04:00 WIB"). Off-peak hour — minimal observable latency impact on real users; predictable from an admin-trail perspective ("suspensions elapse at 04:00 WIB"). 04:00 WIB is also the project's chosen WIB-staggered reset hour for daily caps (rate-limit-infrastructure), keeping conceptually-related daily cron jobs co-located.

The retry policy is appropriate for an idempotent operation — re-running the same SQL after a partial failure is safe because the WHERE predicate naturally filters already-processed rows.

**Alternatives considered**:
- Hourly cron — rejected: no UX promise tied to hourly granularity; daily aligns with the docs and reduces noise.
- Triggered by user request (lazy unban on next sign-in attempt) — rejected: violates the docs' "daily worker" contract; would also require lazy-check infrastructure in the auth middleware that doesn't exist.

### D5: No notification on unban — recommended path

**Decision**: The worker does NOT insert a `notifications` row when a user is unbanned. The user's next sign-in attempt or post action succeeds, which is the natural in-band signal.

**Rationale**: Three options per the proposal:
- **(A) Reuse `account_action_applied`** with `body_data = {action_type: "unban_applied", reason: "suspension_elapsed"}`. The UX copy ([`docs/03-UX-Design.md:184`](docs/03-UX-Design.md): "Akun kamu menerima tindakan moderasi") is generic and does not match a positive-restoration event. Risks user confusion ("did something else just happen?").
- **(B) Skip the notification** (this decision).
- **(C) Add a new notification type** `account_action_lifted` with new UX copy. Requires updating the `notifications` schema CHECK enum, the `body_data` catalog in `docs/05-Implementation.md`, and the UX copy in `docs/03-UX-Design.md`. Spec amendment to the `in-app-notifications` capability. Worth the cost only if user research shows confusion.

**Decision: (B).** Most users with a 7-day suspension are aware of the suspension period (the original suspension notification told them); the lapse is implicit. Adding a notification type is a non-trivial cross-cutting change; doing so without product evidence is over-engineering.

**Reconsider** post-launch if support tickets show user confusion about unban timing. A follow-up change adding `account_action_lifted` would be small and well-scoped.

### D6: Endpoint method = POST, response shape = `{"unbanned_count": N}` with HTTP 200

**Decision**: `POST /internal/unban-worker`. Response: HTTP 200 with body `{"unbanned_count": N}` for any `N >= 0`. Errors (DB unreachable, transaction failed): HTTP 500 with body `{"error": "<sanitized-classification>"}` matching the `health-check` D6 vocabulary (`"timeout"`, `"connection_refused"`, `"unknown"`, etc.).

**Rationale**: POST is conventional for "do work" RPC endpoints and matches Cloud Scheduler defaults. Returning 200 with `unbanned_count: 0` (instead of 204) lets the operator confirm the worker ran successfully even when there were no eligible rows — useful signal during incident triage. Sanitized error classifications match the `health-check` precedent and avoid info-leak.

**Alternatives considered**:
- GET — rejected: violates HTTP semantics for a state-mutating call.
- 204 No Content for empty runs — rejected: ops dashboards parse `unbanned_count` even when 0 to confirm the cron fired.

### D7: Idempotency without explicit deduplication

**Decision**: The worker has no idempotency key, no run-id tracking, no `worker_runs` table. Re-running it within the same minute is safe because the second run's `WHERE` predicate naturally returns zero rows (the first run set `is_banned = FALSE` + `suspended_until = NULL`, removing those rows from the eligibility set).

**Rationale**: The UPDATE is naturally idempotent at the row level. No two concurrent runs can double-process the same row because PostgreSQL row locks serialize the conflicting UPDATE statements; the second one's `WHERE` clause sees the post-first-run state and skips.

The Cloud Scheduler retry policy (D4) explicitly relies on this — a retry after a network blip re-runs the SQL safely.

**Concurrent dual-writer race (admin manual unban + worker fires simultaneously).** The Phase 3.5 Admin Panel will eventually expose a manual unban path. Two cases under concurrent execution:
- **Admin commits first.** The worker's WHERE-clause includes `is_banned = TRUE`; the admin's commit set `is_banned = FALSE`, so the worker's predicate filters that row out. No double-flip.
- **Worker commits first.** Admin's UPDATE on the same row finds `is_banned` already `FALSE` and `suspended_until` already `NULL`. If the admin's UPDATE is shaped as a no-op-on-already-clear-state, no harm. If admin additionally bumps `token_version` (likely), the bump is benign and applied once.

In neither case does the row end up double-flipped or in an inconsistent state. The Phase 3.5 admin path's exact SQL shape will be its own design concern; this design ensures the worker side is safe regardless.

**Soft-delete-mid-elapse race.** Theoretical: a user is hard-deleted (admin path) at the exact moment the worker reads them from the WHERE-scan but before the UPDATE writes. Postgres MVCC + the row-lock serialization make this safe — the worker's UPDATE either commits before the delete (no harm; row-then-deleted) or finds the row gone and skips (no harm; predicate-fails). Not separately tested because the safety is a Postgres invariant, not application logic.

**Alternatives considered**:
- Acquire a `pg_advisory_lock` for the duration of the worker — rejected: at most one Cloud Scheduler invocation runs at a time per cron job, and even hypothetical overlap is safe due to row-level locking. Adding the advisory lock is solving a non-problem.
- Track runs in a `worker_runs` table — rejected: telemetry concern, not correctness. OTel + Cloud Logging cover this.

### D8: Replay protection within `exp` window relies on GCP-IAM ingress, not on the OIDC plugin

**Decision**: The OIDC plugin does NOT track `jti` claims and does NOT detect token replay within the token's `exp` window (Google OIDC tokens typically have a ~1-hour `exp`). An attacker who somehow captured a valid OIDC token (e.g., through an egress-log compromise of the staging Cloud Scheduler invocation logs) could in principle replay it for the lifetime of `exp`.

The defense-in-depth layer for replay protection is the **GCP IAM Cloud-Scheduler-only-invoke** binding ([`docs/06-Security-Privacy.md:451`](docs/06-Security-Privacy.md): *"Defense in depth: network-level (GCP IAM Cloud Scheduler-only invoke) + token-level (OIDC verify origin)."*). The Cloud Run service is bound to allow only the dedicated Cloud Scheduler service account to invoke it, so a stolen-token replay from outside that service account never reaches the OIDC plugin in the first place.

**Rationale**: `jti` tracking would require persistent state (Redis or DB) for every successful invocation across `exp` window, plus a cleanup worker — significant ongoing cost for a marginal defense in a system already protected by GCP IAM. The token-level layer is intentionally focused on origin verification, not replay prevention.

This is documented as an **explicit non-goal** so a future maintainer doesn't add `jti` tracking under the false premise that it's a missing requirement.

### D9: Per-endpoint authorization is NOT enforced beyond `aud` matching

**Decision**: The OIDC plugin verifies `aud` (the deployed Cloud Run service URL) but does NOT verify a per-endpoint scope claim. Once Phase 3.5 / Phase 4 surface multiple `/internal/*` workers (privacy-flip, hard-delete, etc.), they all share the same audience by Cloud Scheduler convention. A misconfigured Cloud Scheduler job for the `/internal/privacy-flip-worker` calling `/internal/unban-worker` with a valid OIDC token whose `aud` matches the Cloud Run service URL would pass OIDC verification.

**Rationale**: The handler at each `/internal/*` route is responsible for being safe-to-call by any legitimate Cloud Scheduler peer. Per-endpoint scope claims would require either (a) custom JWT minting (Cloud Scheduler doesn't natively support it) or (b) a service-account-per-endpoint pattern (operational complexity). For Phase 1, OIDC origin verification at the audience level is the proportionate trust model.

**Consequence for spec authors**: future workers MUST be designed such that an accidental cross-endpoint invocation is at worst a no-op or an audited mis-fire, never a privilege escalation. The unban worker satisfies this trivially (its only effect is flipping suspensions, which a misconfigured Cloud Scheduler peer wouldn't trigger because the WHERE-clause is bounded).

### D10: New `:infra:oidc` module + JWKS lib choice (promoted from earlier Open Question Q1)

**Decision**: Create a new `:infra:oidc` module to host `GoogleOidcTokenVerifier`. None of the existing `:infra:*` modules (`:infra:redis`, `:infra:resend`, `:infra:sentry`, `:infra:amplitude`, `:infra:remote-config`, `:infra:postgres-neon`, `:infra:attestation`, `:infra:otel`) is a natural home; folding the verifier into one of them would bloat its surface and confuse the per-vendor-concern module pattern.

For the JWKS / JWT library, choose `com.auth0:jwks-rsa` + `com.auth0:java-jwt`. Rationale:
- Both libs are mainstream, MIT-licensed, well-maintained.
- `JwkProvider` builder pattern (`cached(rateLimited(jwkProviderFor(googleJwksUrl)))`) gives rotation-aware refresh out of the box, matching the spec's requirement.
- Mocking `JwkProvider` in unit tests is straightforward (one interface, one method).
- `nimbus-jose-jwt` is a viable alternative but its `JWKSource` abstraction is heavier; preferred only if the project's auth-jwt module already depends on it (verify during implementation; if so, switch).

Pin the chosen versions in [`gradle/libs.versions.toml`](gradle/libs.versions.toml) per [`docs/09-Versions.md`](docs/09-Versions.md) policy and document the choice in that file.

### D11: Plugin mount = `/internal/*` subtree, vendor webhooks under sibling auth (promoted from earlier Open Question Q2)

**Decision**: Mount `InternalEndpointAuth` at the route-subtree level: `route("/internal") { install(InternalEndpointAuth) { ... }; ... }`. Vendor-webhook endpoints (`/internal/revenuecat-webhook` Bearer + HMAC; `/internal/csam-webhook` admin-triggered + HMAC) live under a parallel `route(...)` block that does NOT install the OIDC plugin and instead installs its own vendor-specific auth.

**Rationale**: Default-secure shape — every new `/internal/*` route added in a future change inherits OIDC verification automatically. Forgetting to install auth on a new endpoint would be impossible because the endpoint inherits the subtree's auth installation. Per-route plugin installation (the rejected alternative) is more explicit but creates a forgetting-failure surface that defeats the purpose.

Vendor webhooks are spelled out in the spec scenario "Vendor-webhook route does NOT inherit OIDC" so the implementation can choose the cleanest opt-out shape (separate sibling block under `/internal/`, distinct path under `/internal-vendor/`, etc.) as long as the contract holds.

## Risks / Trade-offs

| Risk | Mitigation |
|------|------------|
| **OIDC verification stale-cache window during Google JWKS rotation** could reject legitimate Cloud Scheduler tokens for the duration of the cache TTL | `JwkProvider` (Auth0) caches with rotation-aware refresh: on a token whose `kid` is not in cache, force-refresh once before rejecting. Standard library behavior; documented in the integration test. |
| **`INTERNAL_OIDC_AUDIENCE` misconfiguration silently rejects all probes** until ops notices the cron history shows red | Boot-time fail-fast on missing/blank config (D2 follow-through). Cloud Scheduler integration test in staging on first deploy verifies green path before any production deploy of this surface. |
| **Concurrent Cloud Scheduler retry due to network blip** could in theory hit the worker twice | D7 covers this — naturally idempotent. Even if the audit-row insert succeeds on the first run but the response is lost (Cloud Scheduler retries), the second run's UPDATE returns zero rows so no duplicate audit entries are written. |
| **No persistent audit row until Phase 3.5** — Cloud Logging retention (typically 30 days on a default GCP Logging project) is shorter than `admin_actions_log`'s eventual 1-year retention. If a dispute arises about an unban that happened >30 days ago and the Phase 3.5 audit table has not yet shipped to capture it, the operator has only Sentry breadcrumbs to fall back on. | Acceptable trade for the phase boundary. (a) Phase 3.5 is on the near-term roadmap (Weeks 11–13 per `docs/08-Roadmap-Risk.md`), so the no-audit window is bounded. (b) The worker's UPDATE is itself observable in DB-level logs and Sentry breadcrumbs survive longer than Cloud Logging by default. (c) Any specific suspension whose audit story matters — e.g., a high-profile account — would already be admin-side traced through the original suspend action's path, not just the unban. |
| **Future inconsistency**: when Phase 3.5 ships and the audit-write follow-up lands, only NEW unbans get audit rows — historical worker runs from Phase 1 are absent | Acceptable. The historical record lives in Cloud Logging for the typical retention window and does not need to be back-filled into `admin_actions_log`. The follow-up change explicitly notes "audit rows from forward-going runs only; pre-Phase-3.5 unbans visible in Cloud Logging archive." |
| **Worker runtime unbounded if pathological data**: if a misconfiguration ever set `suspended_until = '1970-01-01'` for thousands of users, the worker would unban them all in one transaction | The transaction takes a row-level lock per affected row plus the UPDATE write — at 1000 rows the latency is < 100ms on a healthy Postgres. At 100k rows the transaction would still be sub-second. Cloud Scheduler request timeout is 30 minutes; well-bounded. If volume ever justified batching, add `LIMIT` + repeat-until-zero in a follow-up. |
| **Hidden coupling to the `users.deleted_at` semantic**: if the soft-delete tombstone process ever changes (e.g., `deleted_at` becomes a status enum), the WHERE clause silently breaks | The canonical SQL is documented in `docs/05-Implementation.md` and tracked under the same authority as the `users` schema. A future schema change touching `deleted_at` semantics would require updating both that doc section and this worker's spec — caught at code-review time. |
| **OIDC verifier dep introduces new transitive deps** (Auth0 `jwks-rsa` or nimbus-jose-jwt) on the JVM classpath | Both libs are mainstream, MIT/Apache licensed, well-maintained. Pin in `gradle/libs.versions.toml` per the version-pinning policy in `docs/09-Versions.md`; document the choice. |
| **Retry-after-success log loss**: a worker run commits the UPDATE successfully and emits the structured INFO event, but the response is lost in transit; Cloud Scheduler retries; the second invocation finds zero eligible rows and emits `unbanned_count=0`. If the FIRST invocation's INFO event was also lost (extremely unlikely, but possible during a transient Cloud Logging ingestion blip), the audit trail for the actually-flipped users is gone — only the Cloud Run access log retains the request itself. | Acceptable for Phase 1. (a) Cloud Logging ingestion is durable enough that compound-failure (log lost AND response lost on the same run) is very rare. (b) Cloud Run access logs separately record the request URL + status code + latency, providing a fallback signal. (c) The Phase 3.5 audit-row backfill (per `FOLLOW_UPS.md`) closes this hole by writing audit rows in the same transaction as the UPDATE, where the audit row commits or doesn't commit alongside the row flip — at which point Cloud Logging is no longer the sole record. |

## Migration Plan

Additive change (two new capabilities, no schema). Code-only — `git revert` of the squash-merge cleanly removes the route + plugin + verifier interface and impl. The Cloud Scheduler job is operational state and can be deleted manually if the change is reverted.

Deployment sequence:
1. Land the implementation + tests on this PR (CI green: ktlint + Detekt + JVM tests).
2. Bind the `INTERNAL_OIDC_AUDIENCE` env var (value = the deployed Cloud Run service URL, e.g., `https://api-staging.nearyou.id`) on staging Cloud Run via plain `--set-env-vars=` — NOT `--update-secrets=`, since the audience is a public Cloud Run URL and not secret material. No GCP Secret Manager slot required.
3. Create the staging Cloud Scheduler job (`nearyou-unban-worker-staging`) before merging — it can target the staging URL but get 401 until the code ships, harmless.
4. Deploy to staging via existing `deploy-staging.yml`.
5. Smoke test:
   - Insert a synthetic test user with `is_banned = TRUE, suspended_until = NOW() - INTERVAL '1 hour'` via the staging psql Cloud Run job pattern (per `FOLLOW_UPS.md` § `extract-staging-psql-helper-script`).
   - Manually trigger the staging Cloud Scheduler job from the GCP console.
   - Verify `unbanned_count: 1` response and the test user has `is_banned = FALSE, suspended_until = NULL`.
   - Verify the structured INFO log event in Cloud Logging with `event="suspension_unban_applied"`, `unbanned_count=1`, `unbanned_user_ids` containing the test user's UUID.
   - Negative test: synthetic user with `suspended_until = NOW() + INTERVAL '1 hour'` (future window) — manually trigger, verify row untouched.
   - Negative test: 401 on missing/forged OIDC token (curl without OIDC, curl with arbitrary string).
   - Cleanup: delete the synthetic test user via the same staging psql pattern.
6. Archive change.
7. Production Cloud Scheduler job + production deploy: separate change cycle (no `deploy-prod.yml` exists yet).

## Reconciliation notes

Reconciliation against canonical docs surfaced one critical scope finding (resolved by deferring audit logging) and two minor terminology alignments:

1. **`admin_actions_log` does not exist yet** (CRITICAL — caught during Phase B step 7). [`docs/05-Implementation.md:363`](docs/05-Implementation.md) prescribes "Audit log inserted per unban", but no Flyway migration has shipped `admin_actions_log` or `admin_users`. [`backend/ktor/src/main/resources/db/migration/V9__reports_moderation.sql:20,73,111`](backend/ktor/src/main/resources/db/migration/V9__reports_moderation.sql) carries explicit deferral comments on `reports.reviewed_by` and `moderation_queue.reviewed_by`: *"FK to admin_users(id) ON DELETE SET NULL — deferred to the Phase 3.5 admin-users migration."* The docs describe the eventual end-state (post-Phase-3.5); the proposal is being shipped now. D3 resolves this by emitting structured logs in the interim and tracking the audit-row backfill in `FOLLOW_UPS.md` for a Phase 3.5 follow-up. **No canonical-doc edit needed** — the docs are not wrong, they describe end-state. The follow-up entry makes the gap discoverable for the next maintainer.

2. **Schedule expression**: docs say "daily at 04:00 WIB"; cron expressions are conventionally written in UTC for Cloud Scheduler. `0 4 * * *` in WIB = `0 21 * * *` in UTC (the *previous day* in UTC). The spec scenarios pin the WIB phrasing; the implementation uses UTC for the literal cron; tasks.md documents both for the deploy step.

3. **Module placement of `OidcTokenVerifier`**: [`openspec/project.md`](openspec/project.md) module structure has `:core:domain` for cross-cutting interfaces, `:infra:*` for vendor adapters. Rate limiting precedent (`RateLimiter` interface in `:core:domain`) confirms the pattern. The interface lands in `:core:domain`; the impl lands in a new `:infra:oidc` module. Module-creation instructions in tasks.md.

## Open Questions

None. The two questions in the initial draft (`:infra:oidc` module placement, subtree-vs-per-route plugin mount) have been promoted to **D10** and **D11** above respectively, since both had a clear recommendation and no genuine ambiguity that required pre-implementation user buy-in.
