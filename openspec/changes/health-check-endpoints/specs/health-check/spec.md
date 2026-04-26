## ADDED Requirements

### Requirement: `/health/live` endpoint returns 200 unconditionally

The Ktor backend SHALL expose `GET /health/live` as a public, unauthenticated, no-body endpoint that returns `200 OK` whenever the HTTP server is accepting connections. The handler MUST NOT perform any dependency probe, MUST NOT touch the data source, Redis, or any external HTTP service, and MUST NOT block on I/O. Its purpose is the Cloud Run liveness probe — if the process is running and the Ktor router can dispatch a request, this endpoint MUST succeed.

`/health/live` MUST be exempt from authentication middleware. It MUST be subject to the same anti-scrape rate limit as `/health/ready` (see the rate-limit requirement below) so that it cannot be abused as a per-IP keepalive.

#### Scenario: Liveness probe always returns 200
- **WHEN** `GET /health/live` is requested
- **THEN** the response status is `200 OK` AND the response body is empty (or a trivial constant text such as `OK`)

#### Scenario: Liveness probe is unauthenticated
- **WHEN** `GET /health/live` is requested without an `Authorization` header
- **THEN** the response status is `200 OK` (no `401` from auth middleware)

#### Scenario: Liveness probe does not probe dependencies
- **WHEN** Postgres, Redis, AND Supabase Realtime are all unreachable AND `GET /health/live` is requested
- **THEN** the response status is `200 OK` (the handler MUST NOT depend on any external dependency)

### Requirement: `/health/ready` endpoint runs three parallel dependency probes

The Ktor backend SHALL expose `GET /health/ready` as a public, unauthenticated endpoint that probes all three runtime dependencies in parallel and returns a JSON readiness snapshot. The handler MUST execute the three probes concurrently using `coroutineScope { listOf(async { postgres }, async { redis }, async { supabaseRealtime }).awaitAll() }` (or an equivalent structured-concurrency primitive) so that total latency approximates the slowest probe rather than the sum of all three.

Per-probe timeouts:
- **Postgres**: 500 ms. Probe is `SELECT 1` over an idle connection from the HikariCP pool.
- **Redis**: 200 ms. Probe is `PING` via the Lettuce client.
- **Supabase Realtime**: 500 ms. Probe is `GET ${SUPABASE_URL}/rest/v1/` over the existing `HttpClient(CIO)` instance, expecting any response status (including `401` / `404`) — TLS-handshake completion plus an HTTP response is sufficient liveness signal because Supabase Realtime and REST share the same edge.

The whole `/health/ready` handler MUST respect a 2-second outer cap regardless of per-probe timeouts (defense-in-depth against a probe that hangs past its individual timeout). When the outer cap fires, any unfinished probe is recorded as `ok=false` with `error="timeout"` in the response.

A probe is `ok=true` when the underlying call returns successfully within its timeout. Any exception, timeout, or non-completion within the per-probe budget MUST be recorded as `ok=false`. Stack traces and full exception messages MUST NOT be included in the response — only a short, fixed-vocabulary `error` string (e.g., `"timeout"`, `"connection_refused"`, `"unknown"`).

The endpoint MUST be exempt from authentication middleware.

#### Scenario: All three dependencies reachable
- **WHEN** Postgres, Redis, AND Supabase Realtime all respond within their per-probe timeouts AND `GET /health/ready` is requested
- **THEN** the response status is `200 OK`

#### Scenario: Postgres probe fails
- **WHEN** the Postgres `SELECT 1` probe times out (>500 ms) OR throws an exception AND `GET /health/ready` is requested
- **THEN** the response status is `503 Service Unavailable`

#### Scenario: Redis probe fails
- **WHEN** the Redis `PING` probe times out (>200 ms) OR the Lettuce client raises a connection error AND `GET /health/ready` is requested
- **THEN** the response status is `503 Service Unavailable`

#### Scenario: Supabase Realtime probe fails
- **WHEN** the Supabase Realtime HTTP probe times out (>500 ms) OR the connection is refused AND `GET /health/ready` is requested
- **THEN** the response status is `503 Service Unavailable`

#### Scenario: Probes run in parallel, not sequentially
- **WHEN** Postgres responds in 400 ms, Redis responds in 150 ms, AND Supabase Realtime responds in 400 ms
- **THEN** the total `/health/ready` response latency is below 700 ms (i.e., approximately `max(400, 150, 400) + handler-overhead`, NOT `400 + 150 + 400 = 950 ms`)

#### Scenario: Outer 2-second cap fires when a probe hangs
- **WHEN** any probe hangs past its individual timeout AND continues to hang past 2 seconds total
- **THEN** the response status is `503` AND the response is returned within 2.5 seconds of request receipt (outer cap + small handler overhead)

#### Scenario: No stack traces leaked in error field
- **WHEN** any probe fails with an exception whose message contains a stack trace, file path, or sensitive infrastructure detail AND `GET /health/ready` is requested
- **THEN** the response `checks[].error` field is one of a fixed short vocabulary (`"timeout"`, `"connection_refused"`, `"unknown"`) AND does NOT contain the original exception message

### Requirement: `/health/ready` response shape

The `/health/ready` response body SHALL be a JSON object with exactly two top-level fields:

```json
{
  "status": "ready" | "degraded",
  "checks": [
    { "name": "postgres",          "ok": true,  "latency_ms": 42 },
    { "name": "redis",             "ok": true,  "latency_ms": 7 },
    { "name": "supabase_realtime", "ok": false, "latency_ms": 503, "error": "timeout" }
  ]
}
```

- `status` is `"ready"` when every `checks[].ok == true`, otherwise `"degraded"`. The literal string `"ready"` (matching the endpoint name `/health/ready`) is canonical per [`docs/05-Implementation.md:1974`](docs/05-Implementation.md); using `"ok"` is a spec violation.
- `checks` is an array of exactly three entries, in fixed deterministic order: `postgres`, `redis`, `supabase_realtime`. The order MUST NOT depend on probe completion order.
- Each `checks[]` entry has `name` (one of `postgres` / `redis` / `supabase_realtime`), `ok` (boolean), and `latency_ms` (long; elapsed time in milliseconds for the probe).
- `error` is OPTIONAL and present only when `ok == false`. Value MUST be one of a short fixed vocabulary: `"timeout"`, `"connection_refused"`, or `"unknown"` (catch-all). Anything else is a spec violation.

HTTP status alignment: `200 OK` when `status == "ready"`, `503 Service Unavailable` when `status == "degraded"`.

#### Scenario: All-green response shape
- **WHEN** all three probes succeed AND `GET /health/ready` is requested
- **THEN** the response body parses as JSON with `status == "ready"` AND `checks` is an array of length 3 AND `checks[0].name == "postgres"` AND `checks[1].name == "redis"` AND `checks[2].name == "supabase_realtime"` AND every `checks[].ok == true` AND no `checks[].error` field is present

#### Scenario: Degraded response shape
- **WHEN** Redis probe fails AND the other two succeed AND `GET /health/ready` is requested
- **THEN** the response body has `status == "degraded"` AND `checks[1].ok == false` AND `checks[1].error` is one of `"timeout"` / `"connection_refused"` / `"unknown"` AND `checks[0].ok == true` AND `checks[2].ok == true`

#### Scenario: Deterministic checks ordering
- **WHEN** Redis responds in 5 ms, Postgres responds in 400 ms, AND Supabase Realtime responds in 350 ms (so completion order is Redis → Supabase → Postgres)
- **THEN** the response `checks` array is still ordered `postgres`, `redis`, `supabase_realtime` (declaration order, NOT completion order)

### Requirement: `/health/*` rate-limit at 60 req/min per IP

Both `/health/live` and `/health/ready` SHALL be rate-limited at 60 requests per minute per client IP via the new `RateLimiter.tryAcquireByKey(key, capacity, ttl)` overload added to the `rate-limit-infrastructure` capability (see the MODIFIED delta in this change's `specs/rate-limit-infrastructure/spec.md`). The limiter is keyed on the client IP read from the `clientIp` request-context value (populated from the `CF-Connecting-IP` header per the project critical-invariant in [`CLAUDE.md`](CLAUDE.md) — direct reads of `X-Forwarded-For` are forbidden). The handler MUST NOT invoke the user-keyed `tryAcquire(userId, ...)` overload with a sentinel UUID — IP-axis call sites use `tryAcquireByKey` exclusively.

The Redis key shape MUST follow the hash-tag standard: `{scope:health}:{ip:<addr>}` with a 60-second window. The 60-request capacity applies to the union of `/health/live` and `/health/ready` requests from the same IP — both endpoints share the same bucket.

When the cap is exceeded, the response status MUST be `429 Too Many Requests` with a `Retry-After` header containing the seconds until the oldest counted request ages out (matching `Outcome.RateLimited.retryAfterSeconds` from `RateLimiter`).

The Cloud Run probe path SHALL bypass the rate limit by binding probes to the in-process URL (`http://127.0.0.1:8080/health/...` from inside the container) so that the probe traffic does not consume the per-IP bucket. (Cloud Run native probes hit the container directly without going through the Cloudflare edge, so `clientIp` for that request is the container's loopback address — naturally isolated from real client IPs and effectively unlimited at probe frequency.)

#### Scenario: 60th request within 60 seconds succeeds
- **WHEN** an IP issues 60 requests to `/health/live` within a 60-second window
- **THEN** all 60 responses are `200 OK`

#### Scenario: 61st request returns 429 with Retry-After
- **WHEN** an IP issues 61 requests to `/health/live` within a 60-second window
- **THEN** the 61st response is `429 Too Many Requests` AND the `Retry-After` header is present AND its value is a positive integer (seconds)

#### Scenario: live and ready share the same bucket
- **WHEN** an IP issues 30 requests to `/health/live` AND 31 requests to `/health/ready` within a 60-second window (61 total)
- **THEN** the 61st request (regardless of which endpoint) returns `429 Too Many Requests`

#### Scenario: Hash-tag key shape
- **WHEN** the rate limiter is invoked for IP `1.2.3.4` on `/health/live`
- **THEN** the Redis key passed to `tryAcquireByKey` matches the pattern `{scope:health}:{ip:1.2.3.4}` (Redis hash-tag standard, single-slot for cluster-safe MULTI ops) AND the user-keyed `tryAcquire(userId, ...)` overload is NOT invoked (no sentinel-UUID workaround anywhere in the call path)

#### Scenario: Cloud Run loopback probes are not rate-limited in practice
- **WHEN** the Cloud Run readiness probe targets `http://127.0.0.1:8080/health/ready` at a 6-per-minute cadence (period 10 s) AND a real client at IP `1.2.3.4` makes 30 `/health/ready` requests in the same minute
- **THEN** the loopback probe `clientIp` is the container's local address (e.g., `127.0.0.1`) AND the real client's bucket `{scope:health}:{ip:1.2.3.4}` has only 30 entries (the probe's bucket is separate by construction)

### Requirement: `RedisProbe` interface in `:core:domain`

An interface `id.nearyou.app.core.domain.health.RedisProbe` SHALL exist in `:core:domain`. The interface MUST declare exactly one method:

```kotlin
suspend fun ping(timeout: Duration): ProbeResult
```

`ProbeResult` MUST be declared as a data class in `id.nearyou.app.core.domain.health`:

```kotlin
data class ProbeResult(val ok: Boolean, val latencyMs: Long, val error: String? = null)
```

`error` is non-null only when `ok == false`, and its value MUST be one of `"timeout"`, `"connection_refused"`, or `"unknown"`.

The `RedisProbe` interface enables `:backend:ktor` to consume Redis liveness without importing `io.lettuce.core.*`, preserving the "no vendor SDK outside `:infra:*`" critical invariant from [`openspec/project.md`](openspec/project.md).

#### Scenario: Interface lives in :core:domain
- **WHEN** running `find core/domain/src -name 'RedisProbe.kt'`
- **THEN** exactly one file is found AND its package is `id.nearyou.app.core.domain.health`

#### Scenario: No Lettuce import in :core:domain
- **WHEN** searching `core/domain/src` for `import io.lettuce`
- **THEN** zero matches are found

### Requirement: `LettuceRedisProbe` implementation in `:infra:redis`

The `:infra:redis` module SHALL provide a `LettuceRedisProbe` class implementing `RedisProbe`. The implementation MUST use the existing Lettuce `RedisClient` Koin singleton (no new connection per probe) and issue `PING` via the sync command surface with the supplied timeout enforced through `withTimeoutOrNull(timeout)` (Kotlin coroutine timeout) wrapping the Lettuce call dispatched on `Dispatchers.IO`.

On timeout, the result MUST be `ProbeResult(ok = false, latencyMs = elapsedMs, error = "timeout")`. On `RedisException` or `RedisConnectionException`, the result MUST be `ProbeResult(ok = false, latencyMs = elapsedMs, error = "connection_refused")`. On any other throwable, `error = "unknown"`.

The probe MUST NOT eagerly create a new connection — it reuses the connection lazily established by the existing Koin module (per the lazy-connect fix shipped in PR [#44](https://github.com/aditrioka/nearyou-id/pull/44)).

#### Scenario: PING success
- **WHEN** Redis is reachable and `LettuceRedisProbe.ping(Duration.ofMillis(200))` is invoked
- **THEN** the result is `ProbeResult(ok = true, latencyMs = <elapsed>, error = null)` AND `latencyMs` is between 0 and 200

#### Scenario: PING timeout
- **WHEN** Redis is unreachable AND `LettuceRedisProbe.ping(Duration.ofMillis(200))` is invoked
- **THEN** the result is `ProbeResult(ok = false, latencyMs = >= 200, error = "timeout")`

#### Scenario: Reuses connection from Koin singleton
- **WHEN** `LettuceRedisProbe.ping(...)` is invoked twice in succession
- **THEN** Lettuce does NOT open a new TCP connection for the second invocation (verified by Lettuce client metrics OR by inspecting the singleton lifecycle)

### Requirement: `SupabaseRealtimeProbe` interface and Ktor implementation

An interface `id.nearyou.app.core.domain.health.SupabaseRealtimeProbe` SHALL exist in `:core:domain` with the same `suspend fun ping(timeout: Duration): ProbeResult` shape as `RedisProbe`. A `KtorSupabaseRealtimeProbe` implementation SHALL exist in the backend (or a new `:infra:supabase-probe` module if introduced) that issues `GET ${SUPABASE_URL}/rest/v1/` over the existing `HttpClient(CIO)` instance with `withTimeoutOrNull(timeout)` enforcing the budget.

Any HTTP response (including `401 Unauthorized` and `404 Not Found`) MUST be treated as `ok = true`. TLS-handshake completion plus an HTTP response is sufficient liveness signal because the Realtime WSS endpoint and the REST endpoint share the same edge — a successful HTTP response from `${SUPABASE_URL}/rest/v1/` proves Supabase is reachable.

`SUPABASE_URL` MUST be resolved from environment configuration (e.g., `auth.supabaseUrl`) and MUST NOT be hardcoded. If the configuration is absent at startup, the application MUST fail fast at boot rather than silently falling back to a probe that always reports `ok = false`.

On timeout, `error = "timeout"`. On connection refused or DNS failure, `error = "connection_refused"`. On any other throwable, `error = "unknown"`.

#### Scenario: REST endpoint reachable
- **WHEN** Supabase is reachable AND `KtorSupabaseRealtimeProbe.ping(Duration.ofMillis(500))` is invoked
- **THEN** the result is `ProbeResult(ok = true, latencyMs = <elapsed>, error = null)` regardless of whether the response status is `200`, `401`, or `404`

#### Scenario: TLS handshake fails
- **WHEN** Supabase host is unreachable AND `KtorSupabaseRealtimeProbe.ping(Duration.ofMillis(500))` is invoked
- **THEN** the result is `ProbeResult(ok = false, latencyMs = >= 500, error = "timeout")` OR `ProbeResult(ok = false, latencyMs = <elapsed>, error = "connection_refused")` depending on failure mode

#### Scenario: Configuration absent fails fast at boot
- **WHEN** the backend starts AND `auth.supabaseUrl` configuration property is absent or blank
- **THEN** the startup raises an error containing the missing-config name AND the process exits non-zero (does NOT boot with a degraded-by-default probe)

### Requirement: Cloud Run probe wiring in staging deploy workflow

The staging Cloud Run deploy workflow ([`.github/workflows/deploy-staging.yml`](.github/workflows/deploy-staging.yml)) SHALL configure both a startup probe and a liveness probe on the deployed Cloud Run service via the `gcloud run deploy` `--startup-probe` and `--liveness-probe` flags (or the equivalent service-manifest YAML). The probe configuration MUST be:

- **Startup probe** (gates traffic during boot — Cloud Run's analog to Kubernetes readiness): `httpGet.path = /health/ready` on the container port; `initialDelaySeconds = 5`, `periodSeconds = 10`, `timeoutSeconds = 3`, `failureThreshold = 3`.
- **Liveness probe** (continuous keepalive after startup): `httpGet.path = /health/live` on the container port; `initialDelaySeconds = 30`, `periodSeconds = 30`, `timeoutSeconds = 2`, `failureThreshold = 3`.

(Note: Cloud Run does not implement a "readiness probe" in the Kubernetes sense. The startup probe fills the same role — gating traffic to a new revision until the application is healthy. [`docs/04-Architecture.md:166`](docs/04-Architecture.md) uses Kubernetes "readiness probe" vocabulary; the Cloud Run-native equivalent is the startup probe targeting the same endpoint.)

The probe traffic targets the container's loopback address (Cloud Run's native probe behavior), which is naturally isolated from the per-IP rate-limit bucket (see the rate-limit requirement above).

Production deploy workflow wiring is OUT OF SCOPE for this change (no production deploy workflow exists yet at the time of this change).

#### Scenario: Startup probe configured
- **WHEN** reading [`.github/workflows/deploy-staging.yml`](.github/workflows/deploy-staging.yml)
- **THEN** the `gcloud run deploy` step (or equivalent) includes `--startup-probe` (or YAML equivalent) targeting `httpGet.path=/health/ready` with `periodSeconds=10` AND `failureThreshold=3`

#### Scenario: Liveness probe configured
- **WHEN** reading [`.github/workflows/deploy-staging.yml`](.github/workflows/deploy-staging.yml)
- **THEN** the `gcloud run deploy` step (or equivalent) includes `--liveness-probe` (or YAML equivalent) targeting `httpGet.path=/health/live` with `periodSeconds=30` AND `failureThreshold=3`

#### Scenario: Startup probe gates revision promotion
- **WHEN** a new staging revision deploys but `/health/ready` returns `503` for `failureThreshold` consecutive periods
- **THEN** Cloud Run does NOT route traffic to the new revision (the previous revision continues serving)
