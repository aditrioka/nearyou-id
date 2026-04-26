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

**Out of scope** (explicit non-goals):
- Cloud Run probe config for production (no prod deploy workflow exists yet; deferred).
- External uptime monitoring (UptimeRobot, Cloudflare Health Checks) — referenced in [`docs/05-Implementation.md:2046`](docs/05-Implementation.md) but is an ops-side concern.
- Granular Redis cluster slot probing (single-node Upstash for staging/prod).
- Auth-gated `/health/detail` admin endpoint (build SHA, migration version) — a future addition, not required for Cloud Run liveness/readiness.

## Capabilities

### New Capabilities
- `health-check`: `/health/live` and `/health/ready` HTTP endpoints exposing process liveness and dependency readiness for Cloud Run probes and external uptime monitors. Defines per-dependency probe contract (Postgres / Redis / Supabase Realtime), parallel execution, response shape, anti-scrape rate-limiting, and Cloud Run probe wiring.

### Modified Capabilities
- `rate-limit-infrastructure`: extends the `RateLimiter` interface with a `tryAcquireByKey(key, capacity, ttl)` overload for non-user-keyed buckets (IP, geocell, fingerprint, global circuit-breaker). The new method shares the **identical** Lua sliding-window script as the existing `tryAcquire(userId, key, ...)` — only the call-site contract differs (no `userId`, no WIB-stagger inference, no telemetry user-tag). Decided over the alternative "sentinel zero UUID" workaround because (a) `/health/*` is the canary call site but [Phase 1 task #25](docs/08-Roadmap-Risk.md) (guest pre-issuance IP+fingerprint limits) and Layer 4 per-area limits are also IP-keyed, (b) sentinel UUIDs are invisible tech-debt that calcify, (c) the amendment is small (interface gains one method, impl is a thin Kotlin overload — Lua script unchanged).

## Impact

- **Code**:
  - `backend/ktor/src/main/kotlin/id/nearyou/app/health/HealthRoutes.kt` — replace single-probe `/health/ready` with the three-way parallel probe; add request-IP-keyed rate-limit gate.
  - `backend/ktor/src/main/kotlin/id/nearyou/app/Application.kt` — Koin wiring for `RedisProbe`, `SupabaseRealtimeProbe`, and the rate-limited health route.
  - `core/domain/` — new `RedisProbe` and `SupabaseRealtimeProbe` interfaces.
  - `infra/redis/` — new `LettuceRedisProbe` impl.
  - `infra/` (or new module if needed) — `KtorSupabaseRealtimeProbe` impl reusing the existing `HttpClient`.
  - `backend/ktor/src/test/kotlin/id/nearyou/app/health/HealthRoutesTest.kt` — expanded coverage for the new shape, all probe-failure permutations, rate-limit 429.
- **CI/CD**:
  - `.github/workflows/deploy-staging.yml` — add `--readiness-probe`/`--liveness-probe` (or equivalent `--container-probe` flags) to the Cloud Run deploy step.
- **Configuration**:
  - New env config `auth.supabaseUrl` (or reuse the existing Supabase config — TBD in design.md) for the Realtime probe URL.
- **Dependencies**: none new. Reuses existing Lettuce + Ktor `HttpClient(CIO)`.
- **Observability**: probe latencies surfaced in the `checks` array enable Grafana dashboards / Sentry alert rules without new instrumentation. The 60 req/min limit is best-effort and uses existing rate-limit telemetry.
- **Risk**: low. Both endpoints are read-only and the probes use short timeouts, so a misbehaving probe cannot cascade into request-handling latency. The largest blast-radius vector is a Cloud Run probe misconfiguration that flaps the service — mitigated by sane initial-delay / failure-threshold defaults baked into the deploy workflow.
