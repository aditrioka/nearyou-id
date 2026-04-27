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
- "No vendor SDK outside `:infra:*`": the OIDC verifier (Auth0 `JwkProvider` + `nimbus-jose-jwt` or equivalent) must be encapsulated behind a `:core:domain` interface.
- `secretKey(env, name)` helper required for any GCP Secret Manager / env-var reads (lint rule per [`openspec/project.md`](openspec/project.md)).
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

**Alternatives considered**:
- Acquire a `pg_advisory_lock` for the duration of the worker — rejected: at most one Cloud Scheduler invocation runs at a time per cron job, and even hypothetical overlap is safe due to row-level locking. Adding the advisory lock is solving a non-problem.
- Track runs in a `worker_runs` table — rejected: telemetry concern, not correctness. OTel + Cloud Logging cover this.

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

## Migration Plan

Additive change (two new capabilities, no schema). Code-only — `git revert` of the squash-merge cleanly removes the route + plugin + verifier interface and impl. The Cloud Scheduler job + secret are operational state and can be deleted manually if the change is reverted.

Deployment sequence:
1. Land the implementation + tests on this PR (CI green: ktlint + Detekt + JVM tests).
2. Create the `internal-oidc-audience` secret slot in GCP Secret Manager (staging first) and grant the runtime service account `roles/secretmanager.secretAccessor` (per the gotcha pattern documented in `FOLLOW_UPS.md` § `gcp-secret-manager-iam-grant-on-new-slot`).
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

**Q1: Should `:infra:oidc` be a new module, or fold the impl into an existing `:infra:*`?**

Existing `:infra:*` modules (per `module-structure` capability spec): `:infra:redis`, `:infra:resend`, `:infra:sentry`, `:infra:amplitude`, `:infra:remote-config`, `:infra:postgres-neon`, `:infra:attestation`, `:infra:otel`. None of these is a natural home for OIDC token verification. New module `:infra:oidc` is the cleanest option, matches the module-per-vendor-concern pattern, and does not bloat any existing module's surface.

**Recommendation: new `:infra:oidc` module.** Confirm during implementation; minor decision, no spec impact. If review prefers folding into an existing module (e.g., `:infra:auth` if introduced later), the Ktor plugin and the verifier interface are both small enough to relocate without touching specs.

**Q2: Should the OIDC plugin be applied at the route-subtree level or per-route?**

Two viable shapes:

- **Subtree**: `route("/internal") { install(InternalEndpointAuth) { ... }; ... }`. Future endpoints inherit auth automatically. Vendor-webhook endpoints (`/internal/revenuecat-webhook`, `/internal/csam-webhook`) live OUTSIDE this subtree under their own auth: `route("/internal-vendor") { ... }` or by explicit per-route opt-out.
- **Per-route**: each `/internal/*` route explicitly installs the plugin. Forces every author to think about auth; harder to forget; more boilerplate.

**Recommendation: subtree, with vendor webhooks under a sibling `route("/internal")` block that explicitly removes the plugin or under a different parent.** The default-secure shape matches Ktor idiomatic plugin scoping. The spec scenarios pin "every route under `/internal/*` MUST verify a valid OIDC token unless explicitly mounted with vendor-specific auth" — the wording leaves room for either implementation shape but mandates the outcome.
