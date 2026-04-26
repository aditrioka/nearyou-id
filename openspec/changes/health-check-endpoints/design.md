## Context

The Ktor backend at [`backend/ktor/src/main/kotlin/id/nearyou/app/health/HealthRoutes.kt`](backend/ktor/src/main/kotlin/id/nearyou/app/health/HealthRoutes.kt) ships partial health-check scaffolding: `/health/live` returns `200` unconditionally, and `/health/ready` runs a single Postgres `SELECT 1` with a 500ms `withTimeoutOrNull`. There is no OpenSpec capability documenting it. The canonical contract lives in:

- [`docs/04-Architecture.md:154-168`](docs/04-Architecture.md) — three-way parallel probe + 60 req/min/IP + >99.9% green target.
- [`docs/05-Implementation.md:1958-1982`](docs/05-Implementation.md) — kotlin sketch using `coroutineScope { listOf(async { ... }, ... ).awaitAll() }`.
- [`docs/08-Roadmap-Risk.md`](docs/08-Roadmap-Risk.md) Phase 1 task #19 — explicitly calls out "Health check endpoints (`/health/live`, `/health/ready`) + Cloud Run probe config".

The recent staging deployment work (PR [#53](https://github.com/aditrioka/nearyou-id/pull/53)) exposed the cost of unverified runtime dependencies: the `like-rate-limit` smoke test caught Redis URL misconfiguration only after merge, requiring three follow-up PRs ([#42](https://github.com/aditrioka/nearyou-id/pull/42) / [#43](https://github.com/aditrioka/nearyou-id/pull/43) / [#44](https://github.com/aditrioka/nearyou-id/pull/44)) to ship a working binary to staging. A real `/health/ready` that probes all three runtime dependencies would have caught the Redis misconfiguration as a 503 from the readiness probe at deploy time, before traffic was routed.

Stakeholders:
- Cloud Run probe consumer (deploy infrastructure)
- External uptime monitor (out of scope here, will consume the same endpoint)
- Solo operator (debugging "is staging healthy?" without SSH'ing into the container)

Constraints:
- "No vendor SDK outside `:infra:*`" critical invariant — `:backend:ktor` cannot import `io.lettuce.core.*`.
- `clientIp` MUST come from the `CF-Connecting-IP` request-context value (CI lint forbids raw `X-Forwarded-For`).
- Hash-tag Redis keys for cluster-safety: `{scope:<value>}:{<axis>:<value>}`.
- Staging Redis falls back to `NoOpRateLimiter` when `REDIS_URL` is unset (per [`Application.kt:313-336`](backend/ktor/src/main/kotlin/id/nearyou/app/Application.kt)) — the health-check rate-limit MUST gracefully degrade in this case (no boot failure).

## Goals / Non-Goals

**Goals:**
- Implement the canonical three-way probe with parallel execution, deterministic response shape, and the 60 req/min/IP rate-limit per [`docs/04-Architecture.md:154-168`](docs/04-Architecture.md).
- Wire Cloud Run readiness + liveness probes in the staging deploy workflow so dependency misconfiguration manifests as a probe failure at deploy time rather than at smoke test.
- Preserve the "no vendor SDK outside `:infra:*`" invariant by introducing `RedisProbe` / `SupabaseRealtimeProbe` interfaces in `:core:domain`.
- Stable contract for future external uptime monitors: a `200` body of `{status, checks[]}` is enough to power a Grafana dashboard or PagerDuty alert without bespoke parsing.

**Non-Goals:**
- Production deploy workflow probe config (no `deploy-prod.yml` exists yet — handed off to whichever change introduces production deployment).
- External uptime monitor wiring (UptimeRobot / Cloudflare Health Checks) — ops-side concern.
- Granular Redis cluster slot probing (single-node Upstash for staging/prod).
- Auth-gated `/health/detail` (build SHA, migration version) — useful future addition, not required for Cloud Run probes.
- Any change to the `rate-limit-infrastructure` spec — this change reuses, doesn't amend.

## Decisions

### D1: Parallel probe via `coroutineScope { ... awaitAll() }` rather than `supervisorScope` or sequential

**Decision**: Use `coroutineScope { listOf(async { p() }, async { r() }, async { s() }).awaitAll() }`.

**Rationale**: This matches the canonical kotlin sketch in [`docs/05-Implementation.md:1968-1972`](docs/05-Implementation.md). All three probes are independent and idempotent; `coroutineScope` cancels siblings if one throws (which is fine because each probe must catch its own exceptions before returning a `ProbeResult` — exceptions never escape the `async` block). `supervisorScope` would let one probe throw without canceling the others, but since each probe MUST catch internally and return a `ProbeResult` regardless, the difference is moot — `coroutineScope` is simpler and matches the docs sketch byte-for-byte.

**Alternatives considered**: Sequential await — rejected because total latency becomes `sum(timeouts)` ≈ 1.2s instead of `max(timeouts)` ≈ 500ms, blowing the 2-second outer cap and impacting the >99.9% green target.

### D2: `RedisProbe` / `SupabaseRealtimeProbe` interfaces in `:core:domain`

**Decision**: Introduce two new interfaces (`RedisProbe`, `SupabaseRealtimeProbe`) in `:core:domain` with concrete `LettuceRedisProbe` and `KtorSupabaseRealtimeProbe` implementations in `:infra:redis` and the backend respectively.

**Rationale**: The "no vendor SDK outside `:infra:*`" invariant prohibits `:backend:ktor` from importing `io.lettuce.core.*`. The same pattern is already established for `RateLimiter` (interface in `:core:domain`, Lettuce-backed impl in `:infra:redis`); reusing it keeps the wiring shape consistent with the precedent set by [`like-rate-limit`](openspec/changes/archive/2026-04-25-like-rate-limit/).

The Postgres probe stays inline with `dataSource.connection.use { ... }` because there is no vendor SDK to abstract — JDBC + Hikari is the standard Java path and is already exposed across the backend.

**Alternatives considered**:
- Inline Lettuce import in `HealthRoutes.kt` — rejected by the lint rule + invariant.
- Probe in `:infra:redis` calling Ktor `respond` — couples HTTP response shape to the infrastructure module, violates module boundaries.

### D3: Supabase Realtime probe uses `${SUPABASE_URL}/rest/v1/`, accepts any HTTP response as `ok=true`

**Decision**: Probe `GET ${SUPABASE_URL}/rest/v1/` over the existing `HttpClient(CIO)` instance. Treat `200`, `401`, `404`, or any other HTTP response as `ok=true`; only TLS handshake failure, DNS failure, connection refused, or timeout count as `ok=false`.

**Rationale**: Supabase Realtime exposes WSS at the same hostname as the REST API and TLS terminates at the same edge. A successful HTTP response (regardless of status) proves the network path, DNS, TLS, and the upstream service are alive. The REST surface returns `404` at the bare `/rest/v1/` path without authentication, which is fine — we are not asserting authorization, just liveness.

**Alternatives considered**:
- Probe the WSS handshake directly — rejected because it requires a WebSocket client, more complex code, and a TLS upgrade that can flap independently of the underlying service.
- Probe the Postgres connection through Supabase's pooler — already covered by the Postgres probe; redundant.

### D4: Outer 2-second cap as belt-and-suspenders

**Decision**: Wrap the `coroutineScope { ... awaitAll() }` in `withTimeoutOrNull(Duration.ofSeconds(2))`. If it fires, return `503` with all incomplete probes marked `ok=false, error="timeout"`.

**Rationale**: Per-probe timeouts are 200 / 500 / 500 ms summing to 1.2s worst case, but `withTimeoutOrNull` inside an `async` block is honored cooperatively — a probe blocked on a non-cancellable JNI call (e.g., a JDBC connection acquire that doesn't respect coroutine cancellation) could exceed its individual timeout. The outer cap is an unconditional second line of defense that bounds total latency at 2s + handler overhead, matching [`docs/04-Architecture.md:158`](docs/04-Architecture.md) ("200 if all dependencies reachable within 2s, else 503").

### D5: Rate-limit key shape `{scope:health}:{ip:<addr>}` shared between `/live` and `/ready`

**Decision**: Both endpoints share a single rate-limit bucket keyed by IP. Hash-tag format `{scope:health}:{ip:<addr>}`. 60-second window, 60-request capacity.

**Rationale**: The 60 req/min limit is anti-scrape, not per-endpoint quota. A scraper hitting `/live` 60 times then `/ready` 60 times in the same minute is just as abusive as hitting one endpoint 120 times. Sharing the bucket simplifies the spec and matches the intent ("anti-scrape on the health namespace").

The hash-tag standard (`{scope:<value>}:{<axis>:<value>}`) is enforced by `RedisHashTagRule` Detekt lint per [`openspec/specs/rate-limit-infrastructure/spec.md`](openspec/specs/rate-limit-infrastructure/spec.md).

**Cloud Run probe traffic** hits `127.0.0.1` (loopback) when the native probe runs inside the container, so the probe's `clientIp` resolves to the container address and uses a different bucket than real client traffic. No explicit bypass logic is needed.

**Alternatives considered**:
- Separate buckets for `/live` and `/ready` — rejected as unnecessary (anti-scrape intent is the same).
- Skip rate-limit when `RateLimiter` is `NoOpRateLimiter` (dev/test) — already implicit: `NoOpRateLimiter` always admits, so dev/test traffic is naturally unlimited.

### D6: Fixed error vocabulary `"timeout" | "connection_refused" | "unknown"`

**Decision**: When a probe fails, the `error` field in the response is exactly one of: `"timeout"`, `"connection_refused"`, or `"unknown"`. No other values, no exception messages, no stack traces.

**Rationale**: Anti-info-leak — exception messages can contain internal hostnames, file paths, or stack traces that reveal infrastructure topology to an unauthenticated caller. Three-state classification is sufficient for ops triage:
- `"timeout"` — the dep is reachable but slow (latency / load issue).
- `"connection_refused"` — the dep is unreachable (DNS / firewall / down).
- `"unknown"` — anything else, escape hatch.

The original exception MUST be logged at WARN with full context for operator debugging — only the response is sanitized.

**Alternatives considered**:
- Free-form `error` string from exception — rejected (info-leak risk).
- Numeric error codes — rejected (less ergonomic for ops dashboards; strings are self-describing).

### D7: `auth.supabaseUrl` config required at boot; fail-fast if absent

**Decision**: The Supabase REST URL MUST be supplied via Ktor environment config (e.g., `auth.supabaseUrl`). Application startup MUST throw if the config is absent or blank, the same pattern as `auth.supabaseJwtSecret` ([`Application.kt:244-246`](backend/ktor/src/main/kotlin/id/nearyou/app/Application.kt)).

**Rationale**: A missing-config silent fallback would mean `/health/ready` returns `503` perpetually in any environment that forgot to set the URL — confusing and slow to diagnose. Fail-fast at boot makes the misconfiguration immediately visible in deploy logs.

The new env var convention: `SUPABASE_URL` (resolved through `EnvVarSecretResolver` chain — same pattern as every other staging/prod secret).

## Risks / Trade-offs

| Risk | Mitigation |
|------|------------|
| Cloud Run probe misconfiguration causes the new revision to fail readiness, blocking traffic | Sane defaults baked into deploy workflow: `failure-threshold = 3`, `period = 10s`, so brief startup blips don't block. Tested in staging before any prod analog ships. |
| Outer 2-second cap masks a slow-but-recovering probe (probe responds at 1.9s but is reported `ok=false`) | Per-probe timeouts (200/500/500ms) bound the visible latency; the outer cap only fires if a probe ignores its individual timeout (e.g., JDBC blocking call). Acceptable trade — a probe that misses its 500ms budget is already a problem. |
| Rate limit at 60 req/min/IP could throttle a noisy uptime monitor | External monitors (UptimeRobot / Cloudflare Health Checks) typically poll at 1-min intervals → 1 req/min, well under the cap. If a future monitor needs sub-minute granularity, a separate `/internal/health/ready` with OIDC auth + no rate-limit can be added. |
| `NoOpRateLimiter` in dev/test means the rate-limit cannot be exercised in unit tests | Tests use `InMemoryRateLimiter` (already exists per `like-rate-limit` precedent) for the rate-limit scenarios. The `NoOpRateLimiter` path is exercised in production-like integration tests and the staging smoke. |
| Probe load on Postgres / Redis at 6 req/min (Cloud Run readiness period 10s) for the lifetime of a revision | `SELECT 1` and `PING` are sub-millisecond; aggregate cost negligible. Negative case verified by Phase 2 benchmark (target p95 <200ms timeline). |
| Supabase probe at 6 req/min adds outbound HTTP traffic to a tier-limited service | Free Supabase tier limits API requests at thousands/day; 6 req/min × 1440 min = 8640 req/day, well below tier limits. |
| Probe traffic counted against Cloudflare per-IP rate-limit when probe goes through the edge (e.g., external uptime monitor probing `api-staging.nearyou.id/health/ready`) | External probes are by design real client IPs; the 60 req/min cap is intentional. A high-frequency external probe is itself an anti-scrape concern, not a "must let everything through" case. |

## Migration Plan

This is an additive change (new capability, new endpoint behavior on an existing partial implementation). No data migration. No breaking API change. Rollback is `git revert` of the single squash-merge commit; the previous partial probe continues working.

Deployment sequence:
1. Land the implementation + tests (CI green).
2. Deploy to staging via the updated `deploy-staging.yml` (Cloud Run probes wired in the same change).
3. Smoke test: `curl https://api-staging.nearyou.id/health/ready` returns `200` with all three checks `ok=true`. Manually flip Redis off (e.g., scramble the URL) and confirm `503` + `redis.ok=false`.
4. Archive change.

Production deployment is a separate change cycle (no `deploy-prod.yml` exists yet).

## Reconciliation notes

Reconciliation against canonical docs surfaced two minor terminology divergences resolved in this proposal:

1. **`status` field**: [`docs/05-Implementation.md:1974`](docs/05-Implementation.md) uses `"status": "ready"` for the green case (matching the endpoint name `/health/ready`); the existing partial implementation at [`HealthRoutes.kt:29`](backend/ktor/src/main/kotlin/id/nearyou/app/health/HealthRoutes.kt) uses `"status": "ok"`. This proposal aligns with canonical docs (`"ready"`); the implementation will be updated as part of section 4 of `tasks.md`.

2. **Cloud Run probe vocabulary**: [`docs/04-Architecture.md:166`](docs/04-Architecture.md) uses Kubernetes "readiness probe" terminology, but Cloud Run does not implement a readiness probe — its `--startup-probe` flag gates traffic during boot in the same role. This proposal uses Cloud Run-native terminology (`startup-probe` + `liveness-probe`) in the spec and tasks while explicitly noting the docs use K8s vocabulary. A docs cleanup follow-up entry is added to `FOLLOW_UPS.md` to amend [`docs/04-Architecture.md:166`](docs/04-Architecture.md) once this change ships.

3. **Rate-limit framing**: this endpoint is a per-IP cap (Layer-1-style usage of an unauthenticated public endpoint) implemented over the Layer-2 `RateLimiter` infrastructure originally introduced for per-user limits in `like-rate-limit`. The infrastructure is reused unchanged; only the call-site convention is novel. See decision **D8** below for the open question on whether to amend `rate-limit-infrastructure` spec to formalize the IP-keyed convention.

### D8: IP-keyed rate-limit convention — RESOLVED to (B)

The `RateLimiter.tryAcquire(userId: UUID, key: String, capacity: Int, ttl: Duration): Outcome` signature was designed for user-axis limits — the `userId` parameter feeds `computeTTLToNextReset(userId)` for WIB stagger and is part of telemetry. For an IP-only bucket, no userId exists. Two options were considered:

- **(A) Sentinel zero UUID** (`00000000-0000-0000-0000-000000000000`) passed as `userId`; IP lives in the `key`. No spec amendment.
- **(B) Amend `rate-limit-infrastructure`** with a `tryAcquireByKey(key, capacity, ttl)` overload (MODIFIED delta).

**Decision: (B).** Rationale:

1. **Health is the canary, not the only IP-only call site.** [Phase 1 task #25](docs/08-Roadmap-Risk.md) (guest pre-issuance: IP + fingerprint + global circuit breaker) is also IP-keyed. Layer 4 per-area limits will follow the same pattern. Choosing (A) here would lock in the sentinel-UUID hack across 3+ future call sites.
2. **Sentinel UUIDs are invisible tech debt.** A future maintainer reading `tryAcquire(ZERO_UUID, key, ...)` has to context-switch to figure out the convention. `tryAcquireByKey` is self-documenting.
3. **The amendment is small.** The Lua script is **unchanged** — it already keys on the supplied `key`, not on `userId`. The interface gains one method; the impl is a thin Kotlin overload that delegates to the same `EVALSHA` cache slot. Existing call sites (likes, replies, reports, search) are zero-touch.
4. **Engineering principle: don't ship invisible debt to save 2-3 hours.** The cost of (A) is paid by every future maintainer; the cost of (B) is paid once.

The MODIFIED delta lives at [`specs/rate-limit-infrastructure/spec.md`](specs/rate-limit-infrastructure/spec.md) in this change. Spec scenarios pin the same-Lua-script invariant via SHA1 equality so future divergence between the two methods is caught at test time.

## Open Questions

None. D8 resolved (above). Reconciliation against [`docs/04-Architecture.md:154-168`](docs/04-Architecture.md), [`docs/05-Implementation.md:1958-1982`](docs/05-Implementation.md), [`docs/08-Roadmap-Risk.md`](docs/08-Roadmap-Risk.md) Phase 1 #19, and [`openspec/specs/rate-limit-infrastructure/spec.md`](openspec/specs/rate-limit-infrastructure/spec.md) confirms timeout budgets, probe targets, response shape, rate-limit value, key shape, and the new method's Lua-script contract.
