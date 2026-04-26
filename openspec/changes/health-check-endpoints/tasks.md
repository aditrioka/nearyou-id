## 1. Domain interfaces (health probes)

- [ ] 1.1 Add `id.nearyou.app.core.domain.health.ProbeResult` data class in `core/domain/src/.../health/ProbeResult.kt` (`ok: Boolean`, `latencyMs: Long`, `error: String? = null`)
- [ ] 1.2 Add `id.nearyou.app.core.domain.health.RedisProbe` interface (`suspend fun ping(timeout: Duration): ProbeResult`)
- [ ] 1.3 Add `id.nearyou.app.core.domain.health.SupabaseRealtimeProbe` interface (`suspend fun ping(timeout: Duration): ProbeResult`)
- [ ] 1.4 Confirm `core/domain/src/**` contains zero `import io.lettuce` or `import io.ktor.client` references after this change (lint-style grep check)

## 1b. `RateLimiter.tryAcquireByKey` overload (rate-limit-infrastructure MODIFIED)

- [ ] 1b.1 Edit `core/domain/src/.../ratelimit/RateLimiter.kt`: add `fun tryAcquireByKey(key: String, capacity: Int, ttl: Duration): Outcome` alongside the existing `tryAcquire`
- [ ] 1b.2 Update KDoc on the interface clarifying the user-keyed vs key-axis split (per-user telemetry + WIB-stagger inference vs key-only telemetry, no stagger inference)
- [ ] 1b.3 Implement `tryAcquireByKey` in `RedisRateLimiter` (`infra/redis/.../RedisRateLimiter.kt`) as a thin overload that delegates to the same internal `EVALSHA <scriptSha>` invocation as `tryAcquire` — ZERO Lua-script change; only the Kotlin signature differs (no `userId` arg, no `userId` log field)
- [ ] 1b.4 Implement `tryAcquireByKey` in `InMemoryRateLimiter` (test double in `:core:domain` per `like-rate-limit` precedent) so unit tests can exercise the new method without Redis
- [ ] 1b.5 Implement `tryAcquireByKey` in `NoOpRateLimiter` (`:infra:redis`) to mirror its `tryAcquire` always-admit behavior
- [ ] 1b.6 Add unit tests under `:infra:redis` exercising: 60 concurrent `tryAcquireByKey` calls vs an empty bucket admit exactly 60, the 61st returns `RateLimited`; `tryAcquireByKey` and `tryAcquire` invocations against the same key produce a single shared bucket (NOT two parallel buckets) — verifies the SHA1-equality invariant from the spec
- [ ] 1b.7 Add a unit-level assertion that the `EVALSHA scriptSha` constant referenced by both methods is equal (loaded once at construction and reused — verifies "same Lua script" spec scenario)
- [ ] 1b.8 Verify telemetry: `tryAcquireByKey` log entries include `key=<key>` and do NOT include `user_id` (lint-grep on the log output structure)

## 2. Lettuce Redis probe in `:infra:redis`

- [ ] 2.1 Add `id.nearyou.app.infra.redis.LettuceRedisProbe` implementing `RedisProbe`, taking the existing Lettuce `RedisClient` Koin singleton via constructor
- [ ] 2.2 Implement `ping(timeout)` using `withTimeoutOrNull(timeout) { withContext(Dispatchers.IO) { connection.sync().ping() } }`; map `null` → `ProbeResult(ok=false, error="timeout")`
- [ ] 2.3 Map `RedisException` / `RedisConnectionException` → `error="connection_refused"`; any other throwable → `error="unknown"`; capture elapsed-ms via `System.nanoTime()` deltas
- [ ] 2.4 Wire `LettuceRedisProbe` into the existing `:infra:redis` Koin module (factory function alongside `redisRateLimiterFromUrl`)
- [ ] 2.5 Unit tests in `infra/redis/src/test/...`: success path; timeout path; `RedisConnectionException` path; verify connection reuse across two `ping` calls

## 3. Supabase Realtime probe (Ktor `HttpClient(CIO)`-backed)

- [ ] 3.1 Add `id.nearyou.app.health.KtorSupabaseRealtimeProbe` (or `id.nearyou.app.infra.supabase.KtorSupabaseRealtimeProbe` if a dedicated module is preferred) implementing `SupabaseRealtimeProbe`
- [ ] 3.2 Constructor takes the existing `HttpClient` instance + a `supabaseUrl: String` (resolved at boot)
- [ ] 3.3 Implement `ping(timeout)` issuing `GET ${supabaseUrl}/rest/v1/`; treat any HTTP response (including 401/404) as `ok=true`; only TLS / DNS / connection / timeout failures as `ok=false`
- [ ] 3.4 Add `auth.supabaseUrl` config property in `backend/ktor/src/main/resources/application.conf` (default placeholder; real values from env)
- [ ] 3.5 In `Application.kt`, resolve `auth.supabaseUrl` with `error("Missing required config auth.supabaseUrl (set SUPABASE_URL)")` if absent or blank — same shape as `auth.supabaseJwtSecret`
- [ ] 3.6 Plumb `SUPABASE_URL` env var into the Cloud Run staging deploy step in `.github/workflows/deploy-staging.yml`
- [ ] 3.7 Unit tests with a mock `HttpClient`: 200, 401, 404 → `ok=true`; timeout → `ok=false, error="timeout"`; connection refused → `error="connection_refused"`

## 4. `/health/ready` parallel probe handler

- [ ] 4.1 Refactor `HealthRoutes.kt` to inject `DataSource`, `RedisProbe`, `SupabaseRealtimeProbe`, `RateLimiter`, and a `clientIp` extractor function
- [ ] 4.2 Add per-probe timeout constants: `POSTGRES_PROBE_TIMEOUT_MS = 500L`, `REDIS_PROBE_TIMEOUT_MS = 200L`, `SUPABASE_PROBE_TIMEOUT_MS = 500L`, `READY_OUTER_CAP_MS = 2000L`
- [ ] 4.3 Implement Postgres probe inline (`SELECT 1` over a HikariCP-borrowed connection, wrapped in `withTimeoutOrNull(POSTGRES_PROBE_TIMEOUT_MS) { withContext(Dispatchers.IO) { ... } }`); produce a `ProbeResult` with the correct error vocabulary
- [ ] 4.4 Replace the single-probe `/health/ready` body with `withTimeoutOrNull(READY_OUTER_CAP_MS) { coroutineScope { listOf(async { postgres }, async { redis }, async { supabase }).awaitAll() } }`; on null (outer cap fired) return `503` with all incomplete probes flagged `ok=false, error="timeout"`
- [ ] 4.5 Build the response in deterministic order (`postgres`, `redis`, `supabase_realtime`) regardless of completion order
- [ ] 4.6 Define a `HealthCheck` serializable data class (`name: String, ok: Boolean, latencyMs: Long, error: String? = null`) and a `HealthReadyResponse` (`status: String, checks: List<HealthCheck>`)
- [ ] 4.7 Verify the serialized JSON omits `error` when null (use `explicitNulls = false` already configured in `Application.kt:131` — confirm)

## 5. Rate-limit gate at 60 req/min/IP

- [ ] 5.1 Add a `clientIp` extractor helper (read from the `clientIp` request-context value populated from `CF-Connecting-IP`); ensure the helper is consistent with the project critical invariant — direct `X-Forwarded-For` reads are forbidden
- [ ] 5.2 Add a `HealthRateLimiter` thin wrapper (or call the shared `RateLimiter` directly with a hardcoded key shape) that constructs the key as `{scope:health}:{ip:<addr>}` and calls `rateLimiter.tryAcquireByKey(key, capacity = 60, ttl = Duration.ofSeconds(60))` — uses the new key-axis overload from section 1b; MUST NOT pass a sentinel UUID through `tryAcquire`
- [ ] 5.3 Apply the rate-limit gate as a Ktor `interceptor` (or inline in each `get { ... }` handler) ahead of the response logic; on `RateLimited`, return `429` with `Retry-After: <seconds>` header
- [ ] 5.4 Confirm the rate limit is shared across `/health/live` and `/health/ready` (single bucket per IP)
- [ ] 5.5 Verify behavior when `RateLimiter` is `NoOpRateLimiter` (dev/test fallback) — both endpoints continue to admit unconditionally without errors

## 6. Wire Koin + route registration

- [ ] 6.1 In `Application.kt`, instantiate `LettuceRedisProbe` (only when `RateLimiter` is Redis-backed; in `NoOpRateLimiter` mode bind a no-op probe that always reports `ok=true` to keep dev/test boot working)
- [ ] 6.2 Instantiate `KtorSupabaseRealtimeProbe` with the resolved `auth.supabaseUrl` and the existing `httpClient`
- [ ] 6.3 Register both probes as Koin singletons; `single<RedisProbe> { ... }`, `single<SupabaseRealtimeProbe> { ... }`
- [ ] 6.4 Update `healthRoutes()` to take the new dependencies via Koin `inject()` (or pass them explicitly as the existing pattern)
- [ ] 6.5 Confirm `Application.module()` startup ordering: probes constructed AFTER `dataSource`, AFTER `rateLimiter`, AFTER `httpClient`

## 7. Cloud Run probe wiring (staging)

- [ ] 7.1 Edit `.github/workflows/deploy-staging.yml`: add `--startup-probe=...` and `--liveness-probe=...` flags to the `gcloud run deploy` step (Cloud Run's startup probe is the K8s-readiness analog — gates traffic until the new revision is healthy; there is no separate "readiness probe" in Cloud Run)
- [ ] 7.2 Startup probe: `httpGet.path=/health/ready,initialDelaySeconds=5,periodSeconds=10,timeoutSeconds=3,failureThreshold=3`
- [ ] 7.3 Liveness probe: `httpGet.path=/health/live,initialDelaySeconds=30,periodSeconds=30,timeoutSeconds=2,failureThreshold=3`
- [ ] 7.4 Inject `SUPABASE_URL` env var via `--set-env-vars` (or via the existing secret-name pattern if Supabase URL is treated as a secret slot)
- [ ] 7.5 Verify the workflow YAML parses and the deploy step runs in dry-run mode (if `gcloud` supports it locally; otherwise verify on first staging deploy in section 9)

## 8. Tests

- [ ] 8.1 Extend `HealthRoutesTest.kt`: configure all three probes via test doubles
- [ ] 8.2 `GET /health/live` returns `200` regardless of probe state (already covered; verify it stays green after refactor)
- [ ] 8.3 `GET /health/ready` returns `200` with `status: "ready"` and three `ok=true` checks when all probes succeed
- [ ] 8.4 `GET /health/ready` returns `503` with `status: "degraded"` when Postgres unreachable; check ordering remains `postgres, redis, supabase_realtime`
- [ ] 8.5 `GET /health/ready` returns `503` when Redis probe times out at 200ms (use a `RedisProbe` test double that delays past timeout)
- [ ] 8.6 `GET /health/ready` returns `503` when Supabase probe times out at 500ms
- [ ] 8.7 `GET /health/ready` checks ordering is deterministic when probes complete in non-declaration order (use staggered probe delays)
- [ ] 8.8 `GET /health/ready` outer cap fires when a probe hangs past 2 seconds (verifies `withTimeoutOrNull(READY_OUTER_CAP_MS)`); response is returned within 2.5 seconds of request receipt
- [ ] 8.9 Rate-limit: 60 requests within a minute succeed; 61st returns `429` with `Retry-After`; limit is shared across `/live` and `/ready`
- [ ] 8.10 Error vocabulary: probe failure with a stack-trace-bearing exception produces `error` ∈ `{"timeout", "connection_refused", "unknown"}` only (no leaked stack)
- [ ] 8.11 `error` field is absent from JSON when `ok=true` (confirms `explicitNulls = false` honored)
- [ ] 8.12 Add CI assertion (or a quick grep in the workflow) that `core/domain/src/**` does not import `io.lettuce` or `io.ktor.client`

## 9. Staging smoke

- [ ] 9.1 Push the change branch; trigger `gh workflow run deploy-staging.yml --ref <branch>`
- [ ] 9.2 Verify Cloud Run deploys the new revision; confirm in Cloud Run Console that the readiness + liveness probes appear with the configured timing
- [ ] 9.3 `curl -i https://api-staging.nearyou.id/health/live` → `200`
- [ ] 9.4 `curl -s https://api-staging.nearyou.id/health/ready | jq` → `status: "ready"`, three checks with `ok: true`
- [ ] 9.5 Negative test: temporarily scramble `REDIS_URL` in staging (or pause the Upstash database), redeploy, and verify `/health/ready` returns `503` with `redis.ok=false` and the new revision does NOT take traffic (previous revision keeps serving)
- [ ] 9.6 Restore Redis URL, redeploy, confirm green again
- [ ] 9.7 Document the smoke results in this `tasks.md` (latency observations, any surprises) before archive
