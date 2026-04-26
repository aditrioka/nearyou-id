## Why

Cloud Run liveness and readiness probes are the canonical way to detect a stuck process or a backend that has lost a critical dependency, but the Ktor service today only ships a partial `/health/ready` ([`HealthRoutes.kt`](backend/ktor/src/main/kotlin/id/nearyou/app/health/HealthRoutes.kt)) that probes Postgres alone. The canonical contract in [`docs/04-Architecture.md:154-168`](docs/04-Architecture.md) prescribes a parallel three-way probe (Postgres + Redis + Supabase Realtime) plus a 60 req/min/IP rate limit, and a >99.9% green target. With staging just shipped (PR [#53](https://github.com/aditrioka/nearyou-id/pull/53)) and the recent Redis deploy-time surprises (PRs [#42](https://github.com/aditrioka/nearyou-id/pull/42) / [#43](https://github.com/aditrioka/nearyou-id/pull/43) / [#44](https://github.com/aditrioka/nearyou-id/pull/44)), wiring real readiness probes into Cloud Run before the next change deploys is the cheapest way to catch a missing or misconfigured dependency at deploy time rather than at smoke-test time. This change formalizes the capability as an OpenSpec spec and completes the implementation.

## What Changes

- Add `/health/live` and `/health/ready` as a spec-driven capability (`health-check`) with the contract from [`docs/04-Architecture.md:154-168`](docs/04-Architecture.md) and [`docs/05-Implementation.md:1958-1982`](docs/05-Implementation.md).
- `/health/live` returns `200 OK` unconditionally (pure process-alive signal, no probes, no auth, no body).
- `/health/ready` runs Postgres + Redis + Supabase Realtime probes in parallel via `coroutineScope { listOf(async { ... }, async { ... }, async { ... }).awaitAll() }`. Per-probe timeouts: Postgres 500ms (`SELECT 1`), Redis 200ms (`PING`), Supabase Realtime 500ms (HTTP probe to `${SUPABASE_URL}/rest/v1/`). Outer 2-second cap on the whole handler per [`docs/04-Architecture.md:158`](docs/04-Architecture.md).
- Response shape: `200 {status: "ready", checks: [...]}` when all green, `503 {status: "degraded", checks: [...]}` when any probe fails or times out. Each `check` entry is `{name, ok, latency_ms, error?}` with `name` ∈ `{postgres, redis, supabase_realtime}` and `error` only present when `ok=false` (short reason string, no stack traces — anti-info-leak).
- Both endpoints are public (no auth) but rate-limited at 60 req/min per IP via the new `RateLimiter.tryAcquireByKey(key, capacity, ttl)` overload added to [`rate-limit-infrastructure`](openspec/specs/rate-limit-infrastructure/spec.md) (axis-agnostic counterpart of the existing user-keyed `tryAcquire`). Hash-tag key shape `{scope:health}:{ip:<addr>}` with 60-second window. Client IP read via `clientIp` request-context value (CF-Connecting-IP, per the project critical-invariant — direct `X-Forwarded-For` reads are forbidden).
- Add `RedisProbe` interface in `:core:domain` + `LettuceRedisProbe` impl in `:infra:redis` so `:backend:ktor` does not import `io.lettuce.core.*` (preserves the "no vendor SDK outside `:infra:*`" invariant).
- Add `SupabaseRealtimeProbe` interface in `:core:domain` + `KtorSupabaseRealtimeProbe` impl wired against the existing `HttpClient(CIO)` instance.
- Wire Cloud Run startup + liveness probes in [`.github/workflows/deploy-staging.yml`](.github/workflows/deploy-staging.yml) via `gcloud run deploy --startup-probe` and `--liveness-probe` flags. (Cloud Run does not have a "readiness probe" in the Kubernetes sense; its startup probe fills that role — gating traffic until the new revision is healthy. Canonical [`docs/04-Architecture.md:166`](docs/04-Architecture.md) uses K8s vocabulary; the Cloud Run-native flag mapping is documented in [design.md](design.md).) Production deploy workflow is out of scope for this change (file does not yet exist; will be wired by whichever change introduces production deployment).
- Tests in [`HealthRoutesTest.kt`](backend/ktor/src/test/kotlin/id/nearyou/app/health/HealthRoutesTest.kt) extended to cover all probe-failure permutations, the new response shape, deterministic `checks` ordering, and the rate-limit 429 behavior.

**Response-shape break note:** the existing partial implementation at [`HealthRoutes.kt:29`](backend/ktor/src/main/kotlin/id/nearyou/app/health/HealthRoutes.kt) returns `{"status": "ok"}` for green; this change aligns to canonical [`docs/05-Implementation.md:1974`](docs/05-Implementation.md) (`{"status": "ready"}`). Any external monitor currently grepping for `status=ok` will need to update — pre-launch this is acceptable; documenting it explicitly in the migration plan in `design.md` so the assumption is visible.

**Out of scope** (explicit non-goals):
- Cloud Run probe config for production (no prod deploy workflow exists yet; deferred).
- External uptime monitoring (UptimeRobot, Cloudflare Health Checks) — referenced in [`docs/05-Implementation.md:2046`](docs/05-Implementation.md) but is an ops-side concern.
- Granular Redis cluster slot probing (single-node Upstash for staging/prod).
- Auth-gated `/health/detail` admin endpoint (build SHA, migration version) — a future addition, not required for Cloud Run liveness/readiness.

## Capabilities

**New:**
- `health-check` — `/health/live` and `/health/ready` HTTP endpoints exposing process liveness and dependency readiness for Cloud Run probes and external uptime monitors. Defines per-dependency probe contract (Postgres / Redis / Supabase Realtime), parallel execution, response shape, anti-scrape rate-limiting, Cloud Run probe wiring, and the `User-Agent`-based probe-bypass for Cloud Run native probes.
- `client-ip-extraction` — Ktor `RouteScopedPlugin` (`ClientIpExtractor`) populating a `clientIp` request-context attribute via the canonical CF-Connecting-IP → XFF-first → remoteHost precedence ladder per [`docs/05-Implementation.md`](docs/05-Implementation.md) § Cloudflare-Fronted IP Extraction. Adds the `RawXForwardedForRule` Detekt rule enforcing the project critical-invariant that direct `X-Forwarded-For` reads are forbidden outside the extractor itself. Added in this change because the rate-limit gate hard-depends on `clientIp` and the middleware was canonicalized in docs but never shipped — health-check is the first call-site that requires it. Also unblocks Phase 1 task #25 (guest pre-issuance IP+fingerprint limits) which has the same dependency.

**Modified:**
- `rate-limit-infrastructure` — extends the `RateLimiter` interface with a `tryAcquireByKey(key, capacity, ttl)` overload for non-user-keyed buckets (IP, geocell, fingerprint, global circuit-breaker). The new method shares the **identical** Lua sliding-window script as the existing `tryAcquire(userId, key, ...)` — only the call-site contract differs (no `userId`, no WIB-stagger inference, no telemetry user-tag). Decided over the alternative "sentinel zero UUID" workaround because (a) `/health/*` is the canary call site but [Phase 1 task #25](docs/08-Roadmap-Risk.md) (guest pre-issuance IP+fingerprint limits) and Layer 4 per-area limits are also IP-keyed, (b) sentinel UUIDs are invisible tech-debt that calcify, (c) the amendment is small (interface gains one method, impl is a thin Kotlin overload — Lua script unchanged).

## Impact

- **Code**:
  - `backend/ktor/src/main/kotlin/id/nearyou/app/common/ClientIpExtractor.kt` — new Ktor `RouteScopedPlugin` populating the `clientIp` request-context value (CF-Connecting-IP → XFF-first → remoteHost ladder).
  - `backend/ktor/src/main/kotlin/id/nearyou/app/health/HealthRoutes.kt` — replace single-probe `/health/ready` with the three-way parallel probe; add request-IP-keyed rate-limit gate; add Cloud Run probe `User-Agent` bypass.
  - `backend/ktor/src/main/kotlin/id/nearyou/app/Application.kt` — install `ClientIpExtractor` plugin BEFORE auth; Koin wiring for `RedisProbe`, `SupabaseRealtimeProbe`, and the rate-limited health route.
  - `core/domain/` — new `RedisProbe`, `SupabaseRealtimeProbe`, and `ProbeResult` types in `id.nearyou.app.core.domain.health`; `RateLimiter.tryAcquireByKey(key, capacity, ttl)` overload added to `id.nearyou.app.core.domain.ratelimit.RateLimiter`.
  - `infra/redis/` — new `LettuceRedisProbe` impl; `RedisRateLimiter` gains a `tryAcquireByKey` overload that delegates to the same internal `EVALSHA <scriptSha>` slot as `tryAcquire` (no Lua script change).
  - `infra/` (or `backend/ktor/.../infra/supabase/`) — `KtorSupabaseRealtimeProbe` impl reusing the existing `HttpClient`.
  - `lint/detekt-rules/` — new `RawXForwardedForRule` enforcing the X-Forwarded-For invariant.
  - `backend/ktor/src/test/kotlin/id/nearyou/app/health/HealthRoutesTest.kt` — expanded coverage for the new shape, all probe-failure permutations, parallelism timing, outer-cap behavior, rate-limit 429, User-Agent bypass.
  - `backend/ktor/src/test/kotlin/id/nearyou/app/common/ClientIpExtractorTest.kt` — new test exercising the precedence ladder (CF header / XFF first / remoteHost / "unknown" fallback / whitespace trimming).
  - `infra/redis/src/test/...` — new tests exercising `tryAcquireByKey` (concurrent boundary, same-bucket-as-tryAcquire, telemetry omits user_id, integration test against real Redis container).
- **CI/CD**:
  - `.github/workflows/deploy-staging.yml` — add `--startup-probe` and `--liveness-probe` flags to the Cloud Run deploy step; inject `SUPABASE_URL` via `--set-env-vars`.
- **Configuration**:
  - New HOCON property `auth.supabaseUrl = ${?SUPABASE_URL}` in `application.conf`.
- **Dependencies**: none new. Reuses existing Lettuce + Ktor `HttpClient(CIO)`.
- **Observability**: probe latencies surfaced in the `checks` array enable Grafana dashboards / Sentry alert rules without new instrumentation. The 60 req/min limit is best-effort and uses existing rate-limit telemetry. Cloud Run probe failures will surface in Cloud Logging via the platform-level probe events.
- **Risk**: low-to-moderate (raised from "low" because of the scope expansion to include `client-ip-extraction`). Both endpoints are read-only and the probes use short timeouts, so a misbehaving probe cannot cascade into request-handling latency. The largest blast-radius vector is the new `ClientIpExtractor` plugin misbehaving and crashing the request pipeline — mitigated by the plugin's defensive `"unknown"` fallback (extraction failure does not throw) and by extensive unit tests covering the precedence ladder before any other plugin is installed.
