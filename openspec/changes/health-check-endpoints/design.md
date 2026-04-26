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

**Cloud Run probe traffic bypass** is handled via D10 (`User-Agent: ^(GoogleHC|kube-probe)/` regex match → skip rate-limit). The earlier "127.0.0.1 loopback isolation" assumption was wrong (Cloud Run probes do NOT see loopback as the source IP — see D10 rationale); the User-Agent bypass replaces it as the canonical mechanism.

**Alternatives considered**:
- Separate buckets for `/live` and `/ready` — rejected as unnecessary (anti-scrape intent is the same).
- Skip rate-limit when `RateLimiter` is `NoOpRateLimiter` (dev/test) — already implicit: `NoOpRateLimiter` always admits, so dev/test traffic is naturally unlimited.

### D6: Fixed error vocabulary `"timeout" | "connection_refused" | "dns_failure" | "tls_failure" | "unknown"`

**Decision**: When a probe fails, the `error` field in the response is exactly one of: `"timeout"`, `"connection_refused"`, `"dns_failure"`, `"tls_failure"`, or `"unknown"`. No other values, no exception messages, no stack traces.

**Rationale**: Anti-info-leak — exception messages can contain internal hostnames, file paths, or stack traces that reveal infrastructure topology to an unauthenticated caller. Five-state classification is sufficient for ops triage:
- `"timeout"` — the dep is reachable but slow (latency / load issue / HikariCP pool exhaustion).
- `"connection_refused"` — the dep is reachable at the network layer but the TCP connection is refused (port closed, firewall, service down).
- `"dns_failure"` — the dep hostname cannot be resolved (misconfiguration, DNS outage).
- `"tls_failure"` — TLS handshake failure (cert expiry, cert pinning mismatch, TLS version incompatibility).
- `"unknown"` — anything else, escape hatch.

Round-1 review surfaced that DNS and TLS failures are operationally distinct from "connection_refused" / "unknown" — collapsing them masks two distinct misconfigurations that ops dashboards need to surface separately. The vocabulary expanded from 3 to 5 values accordingly.

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
| Cloud Run probe misconfiguration causes the new revision to fail readiness, blocking traffic | Sane defaults baked into deploy workflow: `failureThreshold = 3`, `periodSeconds = 10`, so brief startup blips don't block. Tested in staging before any prod analog ships. |
| Outer 2-second cap masks a slow-but-recovering probe (probe responds at 1.9s but is reported `ok=false`) | Per-probe timeouts (200/500/500ms) bound the visible latency; the outer cap only fires if a probe ignores its individual timeout (e.g., JDBC blocking call). Acceptable trade — a probe that misses its 500ms budget is already a problem. |
| **HikariCP pool exhaustion during a saturation incident** — Postgres probe holds a connection slot that real traffic needs | The Postgres probe uses `dataSource.connection.use { ... }` so the connection is returned even if the timeout fires after acquire. If the pool is fully exhausted, `getConnection()` blocks; `withTimeoutOrNull(500ms)` catches this, but the underlying `getConnection()` is not always cancellable (HikariCP queues). The outer 2-second cap (D4) is the second line of defense. **Documented expected behavior**: pool exhaustion shows up as `/health/ready` returning `503` with `postgres.error="timeout"` — which is the correct signal (the dependency IS unhealthy from the application's perspective). |
| **`/health/live` rate-limit could trigger restart loop in pathological scenarios** | If a hot scraper IP collides with the Cloud Run probe's apparent source IP (mitigated by D10 `User-Agent` bypass — but if bypass is misconfigured) the liveness probe could get 429 → restart. Mitigations: (a) D10 `User-Agent` bypass, (b) Cloud Run liveness `failureThreshold=3` so a single 429 doesn't restart, (c) `NoOpRateLimiter` fallback in dev/test means the limiter never returns `RateLimited`. |
| **Rate-limit during real dependency outage**: external monitors + real users from the same IP could exhaust the 60/min cap | Documented trade-off in D11. Cloud Run probes bypass via `User-Agent` (D10). External monitors at typical 1-min cadence consume 1 req/min — far under cap. NAT-shared IPs during incident: degraded operator UX, not availability impact. |
| `NoOpRateLimiter` in dev/test means the rate-limit cannot be exercised in unit tests | Tests use `InMemoryRateLimiter` (already exists per `like-rate-limit` precedent) for the rate-limit scenarios. The `NoOpRateLimiter` path is exercised in production-like integration tests and the staging smoke. |
| **`ClientIpExtractor` plugin crashes the request pipeline** (new risk from co-shipping `client-ip-extraction`) | Plugin defensive: extraction failure returns the literal string `"unknown"`, never throws. Unit-tested across the precedence ladder (CF header / XFF first / remoteHost / fallback / whitespace) before any other plugin install order. |
| **Sentinel-UUID-via-IP-derivation regression** — a future maintainer could pass `UUID.nameUUIDFromBytes(ip.toByteArray())` to `tryAcquire` to bypass `tryAcquireByKey` | Spec scenario "tryAcquireByKey omits userId from telemetry" forbids the literal sentinel UUID. A future Detekt rule (logged in `FOLLOW_UPS.md`) firing on `tryAcquire(*, "{*ip:*}", ...)` would lock this in at lint time. Out of scope for this proposal. |
| Probe load on Postgres / Redis at 6 req/min (Cloud Run readiness period 10s) for the lifetime of a revision | `SELECT 1` and `PING` are sub-millisecond; aggregate cost negligible. Negative case verified by Phase 2 benchmark (target p95 <200ms timeline). |
| Supabase probe at 6 req/min adds outbound HTTP traffic to a tier-limited service | Free Supabase tier limits API requests at thousands/day; 6 req/min × 1440 min = 8640 req/day, well below tier limits. |
| **`status` field response-shape break**: existing partial impl returns `{"status":"ok"}`; this change canonicalizes to `{"status":"ready"}` per docs | Pre-launch — no production consumers. Documented explicitly in proposal so any external monitor wiring assumes the post-change shape. |
| **Lettuce sync vs async surface**: `LettuceRedisProbe` uses `connection.sync().ping()` dispatched on `Dispatchers.IO` rather than `RedisAsyncCommands.ping().await()` | Sync surface is simpler and matches the V11 `RedisRateLimiter` precedent. The blocking call is bounded by `withTimeoutOrNull` cooperatively + the outer 2-second cap as defense-in-depth (D4). Worth revisiting if benchmarks show probe latency variance — async would respect coroutine cancellation natively. |

## Migration Plan

This is an additive change (two new capabilities, modified behavior on an existing partial implementation). No data migration. **One non-breaking response-shape change**: `GET /health/ready` green response transitions from `{"status":"ok"}` (existing partial impl) to `{"status":"ready"}` (canonical per docs). Pre-launch, with no production consumers, this is acceptable; documenting explicitly so any new external monitor wiring assumes the post-change shape. Rollback is `git revert` of the single squash-merge commit; the previous partial probe (Postgres-only) continues working.

Deployment sequence:
1. Land the implementation + tests (CI green: ktlint + Detekt including the new `RawXForwardedForRule` + JVM tests).
2. Deploy to staging via the updated `deploy-staging.yml` (Cloud Run probes wired in the same change).
3. Smoke test:
   - `curl -i https://api-staging.nearyou.id/health/live` → `200`
   - `curl -s https://api-staging.nearyou.id/health/ready | jq` → `status: "ready"`, three checks with `ok: true`
   - Negative test: pause the staging Upstash database via the console (NOT scramble the env var, which would prevent boot per `Application.kt` fail-fast on `REDIS_URL`); redeploy is unnecessary — observed `/health/ready` returns `503` with `redis.ok=false`. Resume Upstash, confirm green again.
4. Archive change.

Production deployment is a separate change cycle (no `deploy-prod.yml` exists yet).

## Reconciliation notes

Reconciliation against canonical docs surfaced two minor terminology divergences resolved in this proposal:

1. **`status` field**: [`docs/05-Implementation.md:1974`](docs/05-Implementation.md) uses `"status": "ready"` for the green case (matching the endpoint name `/health/ready`); the existing partial implementation at [`HealthRoutes.kt:29`](backend/ktor/src/main/kotlin/id/nearyou/app/health/HealthRoutes.kt) uses `"status": "ok"`. This proposal aligns with canonical docs (`"ready"`); the implementation will be updated as part of section 6 of `tasks.md` (specifically task 6.7, the `HealthReadyResponse` data class).

2. **Cloud Run probe vocabulary**: [`docs/04-Architecture.md:166`](docs/04-Architecture.md) uses Kubernetes "readiness probe" terminology, but Cloud Run does not implement a readiness probe — its `--startup-probe` flag gates traffic during boot in the same role. This proposal uses Cloud Run-native terminology (`startup-probe` + `liveness-probe`) in the spec and tasks while explicitly noting the docs use K8s vocabulary. A docs cleanup follow-up entry is added to `FOLLOW_UPS.md` to amend [`docs/04-Architecture.md:166`](docs/04-Architecture.md) once this change ships.

3. **Rate-limit framing**: this endpoint is a per-IP cap (Layer-1-style usage of an unauthenticated public endpoint) implemented over the Layer-2 `RateLimiter` infrastructure originally introduced for per-user limits in `like-rate-limit`. The infrastructure is reused unchanged; only the call-site convention is novel. See decision **D8** below for the open question on whether to amend `rate-limit-infrastructure` spec to formalize the IP-keyed convention.

### D9: `client-ip-extraction` capability added in this change

**Decision**: Add `client-ip-extraction` as a NEW capability in this change rather than gating health-check on a precursor change.

**Rationale**: Round-1 review surfaced that the project's CLAUDE.md critical-invariant ("Client IP: read via the `clientIp` request-context value populated from `CF-Connecting-IP`. Direct `X-Forwarded-For` reads are forbidden") references infrastructure that **does not exist in the codebase yet**. A `grep -rn "clientIp\|CF-Connecting" backend/ core/` returns zero matches. The canonical contract lives in [`docs/05-Implementation.md`](docs/05-Implementation.md) § Cloudflare-Fronted IP Extraction with the precedence ladder fully specified, but the Ktor intercept that implements it has never been built. Health-check is the first call-site that requires it.

Three options were considered:
- **(A) Hand-roll a clientIp helper inline in the health module.** Solves health-check's needs but doesn't address the CLAUDE.md invariant for future call-sites (Phase 1 task #25 guest pre-issuance, Layer 4 per-area rate limits, audit logging in `admin_sessions.ip` / `admin_actions_log.ip`). Tech debt across 4+ future changes.
- **(B) Block this change on a precursor `client-ip-extraction` change.** Clean, but ships nothing now and forces a context-switch.
- **(C) Add `client-ip-extraction` as a co-shipped new capability in this change.**

**Decision: (C).** The middleware is small (a `RouteScopedPlugin` with a 3-step ladder + a `RawXForwardedForRule` Detekt rule). Co-shipping with health-check produces a coherent slice (clientIp middleware → clientIp consumer in the same PR) without dragging in unrelated callsites. Future changes that need `clientIp` (Phase 1 task #25, etc.) consume the capability without re-implementing.

The scope expansion is explicit and acknowledged in the proposal Capabilities section. Round-1 review feedback drove this decision; the alternative (hand-roll inline) was actively rejected as tech debt.

### D10: Cloud Run probe rate-limit bypass via `User-Agent`

**Decision**: Requests whose `User-Agent` header matches `^(GoogleHC|kube-probe)/` bypass the `/health/*` rate-limit check entirely.

**Rationale**: Round-1 review revealed that the earlier "Cloud Run loopback isolation" claim was wrong-as-stated. Cloud Run native HTTP probes do NOT see `127.0.0.1` as the request source — the container observes a Google-internal proxy address that is not a stable constant and that COULD collide with real client buckets. Without a deterministic bypass, a misconfiguration aligning the probe's apparent source IP with a hot scraper could produce a 429 → liveness-probe failure → unintended container restart loop.

`User-Agent` matching is the canonical bypass mechanism documented by Cloud Run / Kubernetes ([GoogleHC](https://cloud.google.com/load-balancing/docs/health-check-concepts#user-agent) / [kube-probe](https://kubernetes.io/docs/reference/access-authn-authz/extensible-admission-controllers/)) and is forgeable from outside, but the cost-of-forgery is exactly the rate-limit cap (60 req/min) — the same outcome a scraper achieves by IP rotation. For an unauthenticated public health endpoint, this trade-off is acceptable.

`kube-probe` is included for forward-compat: if the deployment ever migrates to GKE or self-hosted Kubernetes, no spec amendment is needed.

**Alternatives considered**:
- **Loopback IP isolation** (the round-0 design) — rejected: Cloud Run probes don't preserve `127.0.0.1`.
- **Internal `/health/internal/*` path with OIDC auth** — rejected: probes can't bring OIDC; would require platform-specific service-account token mounting that doesn't fit Cloud Run's probe model.
- **Header-based shared secret** — rejected: secrets in probe configs are awkward to rotate.

### D11: Rate-limit during dependency outage — accept the bucket-counts-everything trade-off

**Decision**: The 60 req/min/IP cap counts every request that reaches the rate-limit gate, regardless of whether the underlying probe ultimately returns `200` or `503`. No special-case logic for 5xx responses.

**Rationale**: Round-1 review flagged the risk that during a real outage, an external uptime monitor + real users hitting `/health/*` from the same IP could exhaust the bucket and get `429` instead of `503` — exactly the moment ops needs the diagnostic. Three options:
- **(A) Skip rate-limit on 5xx** — limiter is invoked BEFORE the response is computed, so this requires a `releaseOnFailure` pattern. `tryAcquireByKey` doesn't have a `releaseMostRecentByKey` counterpart (deliberately omitted in the rate-limit-infrastructure MODIFIED delta).
- **(B) Bump cap to 180/min or higher** — partial mitigation but doesn't address the root cause.
- **(C) Accept the trade-off; rely on the User-Agent bypass for probe traffic and document the math for external monitors.**

**Decision: (C).** The math validates the choice:
- Cloud Run native probes bypass via `User-Agent` (D10) — zero bucket consumption.
- External uptime monitors typically poll at 30-second to 1-minute intervals (1–2 req/min/IP). The cap is 60/min; even a hyper-aggressive 1-second polling rate hits the cap exactly with zero headroom, but no production monitoring pattern operates at 1-second granularity.
- NAT-shared IPs (corporate offices, mobile carriers) where dozens of users hit `/health/*` from a single IP during an outage: the 60/min cap may be tight, but this is degraded-operator-experience (refresh the dashboard 60×/min), not service-availability impact.

The trade-off is documented in the spec and surfaced via observability (Grafana dashboard on 429 rate against `/health/*` is recommended in `docs/07-Operations.md` follow-up).

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
