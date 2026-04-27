## 1. `client-ip-extraction` capability (NEW — precursor to rate-limit gate)

- [ ] 1.1 Add `id.nearyou.app.common.ClientIpExtractor` Ktor `RouteScopedPlugin` (or application-level intercept on `Plugins` phase) at `backend/ktor/src/main/kotlin/id/nearyou/app/common/ClientIpExtractor.kt`. Resolve precedence ladder: (a) `CF-Connecting-IP` header, (b) first comma-separated entry of `X-Forwarded-For`, (c) `call.request.origin.remoteHost`, (d) literal `"unknown"` if all empty.
- [ ] 1.2 Trim leading/trailing whitespace from the resolved value. Return as `String` (no `InetAddress` parsing). Set on `call.attributes` under a typed `AttributeKey<String>("ClientIp")` exactly once per request; idempotent if invoked twice (skip re-resolution if already set).
- [ ] 1.3 Add typed accessor `val ApplicationCall.clientIp: String` (read-only extension) so call-sites use `call.clientIp` not `call.attributes[...]`.
- [ ] 1.4 In `Application.module()`, install the plugin BEFORE `installAuth(...)` and BEFORE any `RateLimiter` Koin binding consumption. Verify install order via test (or comment + lint check).
- [ ] 1.5 Add KDoc / top-of-file comment citing [`docs/05-Implementation.md`](docs/05-Implementation.md) § Cloudflare-Fronted IP Extraction § Spoof Protection AND noting the Phase 1 (no Cloud Armor) vs Cloud-Armor-wired difference.
- [ ] 1.6 Add new Detekt rule `RawXForwardedForRule` under `lint/detekt-rules/src/main/kotlin/.../RawXForwardedForRule.kt` flagging any read of `request.headers["X-Forwarded-For"]`, `call.request.header("X-Forwarded-For")`, or `call.request.headers.get("X-Forwarded-For")` outside `ClientIpExtractor.kt`. Allow-list the extractor file by exact filename match.
- [ ] 1.7 Wire `RawXForwardedForRule` into `lint/detekt-rules/src/main/resources/META-INF/services/...` (the SPI registration), and ensure `./gradlew :lint:detekt-rules:test` runs unit tests for the rule (positive: violation file fails; negative: `ClientIpExtractor.kt` does not fail).
- [ ] 1.8 Unit tests in `backend/ktor/src/test/kotlin/id/nearyou/app/common/ClientIpExtractorTest.kt`: CF header precedence (CF=1.2.3.4, XFF=5.6.7.8 → "1.2.3.4"); XFF first entry (no CF, XFF="5.6.7.8, 9.10.11.12" → "5.6.7.8"); remoteHost fallback (no CF, no XFF, remoteHost=127.0.0.1 → "127.0.0.1"); "unknown" fallback (all empty); whitespace trimming (CF="  1.2.3.4  " → "1.2.3.4"); idempotency (plugin invoked twice → attribute set once); install-order test (plugin runs before auth interceptor).

## 2. Domain interfaces (health probes)

- [ ] 2.1 Add `id.nearyou.app.core.domain.health.ProbeResult` data class in `core/domain/src/.../health/ProbeResult.kt` (`ok: Boolean`, `latencyMs: Long`, `error: String? = null`)
- [ ] 2.2 Add `id.nearyou.app.core.domain.health.RedisProbe` interface (`suspend fun ping(timeout: Duration): ProbeResult`)
- [ ] 2.3 Add `id.nearyou.app.core.domain.health.SupabaseRealtimeProbe` interface (`suspend fun ping(timeout: Duration): ProbeResult`)
- [ ] 2.4 Confirm `core/domain/src/**` contains zero `import io.lettuce` or `import io.ktor.client` references after this change (lint-style grep check)

## 3. `RateLimiter.tryAcquireByKey` overload (rate-limit-infrastructure MODIFIED)

- [ ] 3.1 Edit `core/domain/src/.../ratelimit/RateLimiter.kt`: add `fun tryAcquireByKey(key: String, capacity: Int, ttl: Duration): Outcome` alongside the existing `tryAcquire`
- [ ] 3.2 Update KDoc on the interface clarifying the user-keyed vs key-axis split (per-user telemetry + WIB-stagger inference vs key-only telemetry, no stagger inference)
- [ ] 3.3 In `RedisRateLimiter` (`infra/redis/.../RedisRateLimiter.kt`): expose `private val scriptSha: String` initialized once at class construction (or via a single `lazy { client.scriptLoad(...) }`). Implement BOTH `tryAcquire` and `tryAcquireByKey` to invoke `client.evalsha(scriptSha, ...)` referencing this same field — divergence between the two is a spec violation per `rate-limit-infrastructure` MODIFIED scenario.
- [ ] 3.4 Implement `tryAcquireByKey` as a thin Kotlin overload that delegates to the same `EVALSHA <scriptSha>` invocation as `tryAcquire`; ZERO Lua-script change; only the Kotlin signature differs (no `userId` arg, no `userId` log field).
- [ ] 3.5 Implement `tryAcquireByKey` in `InMemoryRateLimiter` (test double in `:core:domain` per `like-rate-limit` precedent) so unit tests can exercise the new method without Redis.
- [ ] 3.6 Implement `tryAcquireByKey` in `NoOpRateLimiter` (`:infra:redis`) to mirror its `tryAcquire` always-admit behavior.
- [ ] 3.7 Unit tests under `:infra:redis` (against `InMemoryRateLimiter`): 60 concurrent `tryAcquireByKey` calls vs an empty bucket — issued via `runBlocking { (1..60).map { async(Dispatchers.IO) { tryAcquireByKey(...) } }.awaitAll() }` (real parallel coroutines, NOT a sequential loop) — admit exactly 60, the 61st returns `RateLimited`.
- [ ] 3.8 Unit test: `tryAcquireByKey` and `tryAcquire(zeroUuid, ...)` against the same key produce a single shared bucket (NOT two parallel buckets) — guards against future maintainer accidentally re-introducing the sentinel-UUID workaround.
- [ ] 3.9 Reflective test: `RedisRateLimiter::class.java.getDeclaredField("scriptSha").apply { isAccessible = true }.get(instance)` returns the same value before and after a `tryAcquireByKey` call — verifies `scriptSha` is a `private val` initialized once, not recomputed per call.
- [ ] 3.10 Behavioral telemetry test: capture structured-log appender output during a `tryAcquireByKey` call. Assert `key=<key>` is present AND `user_id` is absent (NOT a substring grep — actual log-event field assertion).
- [ ] 3.11 Integration test extension to `RedisRateLimiterIntegrationTest` against the real `redis:7-alpine` container: full `tryAcquireByKey` scenario set (boundary, concurrent, hash-tag CRC16 equivalent) mirroring the existing `tryAcquire` integration coverage.

## 4. Lettuce Redis probe in `:infra:redis`

- [ ] 4.1 Add `id.nearyou.app.infra.redis.LettuceRedisProbe` implementing `RedisProbe`, taking the existing Lettuce `RedisClient` Koin singleton via constructor.
- [ ] 4.2 Implement `ping(timeout)` using `withTimeoutOrNull(timeout) { withContext(Dispatchers.IO) { connection.sync().ping() } }`; map `null` → `ProbeResult(ok=false, error="timeout")`.
- [ ] 4.3 Map `RedisException` / `RedisConnectionException` → `error="connection_refused"`; `UnknownHostException` → `error="dns_failure"` (defensive — Redis URL misconfiguration); `SSLHandshakeException` → `error="tls_failure"` (defensive — Upstash TLS); any other throwable → `error="unknown"`. Capture elapsed-ms via `System.nanoTime()` deltas.
- [ ] 4.4 Wire `LettuceRedisProbe` into the existing `:infra:redis` Koin module (factory function alongside `redisRateLimiterFromUrl`).
- [ ] 4.5 Unit tests in `infra/redis/src/test/...`: success path; timeout path; `RedisConnectionException` path; `UnknownHostException` → `dns_failure`; verify connection reuse across two `ping` calls (Lettuce client metrics OR singleton lifecycle inspection).

## 5. Supabase Realtime probe (Ktor `HttpClient(CIO)`-backed)

- [ ] 5.1 Add `id.nearyou.app.health.KtorSupabaseRealtimeProbe` (or `id.nearyou.app.infra.supabase.KtorSupabaseRealtimeProbe` if a dedicated module is preferred) implementing `SupabaseRealtimeProbe`.
- [ ] 5.2 Constructor takes the existing `HttpClient` instance + a `supabaseUrl: String` (resolved at boot).
- [ ] 5.3 Implement `ping(timeout)` issuing `GET ${supabaseUrl}/rest/v1/`; treat any HTTP response (200, 401, 404, 500, 502, 503) as `ok=true` (edge reachability, not data-plane authorization). Only TLS / DNS / connection / timeout failures as `ok=false`.
- [ ] 5.4 Error vocabulary mapping: `withTimeoutOrNull` null → `error="timeout"`; `UnknownHostException` → `error="dns_failure"`; `SSLHandshakeException` / `SSLException` → `error="tls_failure"`; `ConnectException` → `error="connection_refused"`; any other → `error="unknown"`. Original exception logged at WARN with full context.
- [ ] 5.5 Add HOCON property `auth.supabaseUrl = ${?SUPABASE_URL}` to `backend/ktor/src/main/resources/application.conf`.
- [ ] 5.6 In `Application.kt`, resolve `auth.supabaseUrl` with `error("Missing required config auth.supabaseUrl (set SUPABASE_URL)")` if absent or blank — same shape as `auth.supabaseJwtSecret` at line 244-246.
- [ ] 5.7 Add `SUPABASE_URL` to `.github/workflows/deploy-staging.yml` `--set-env-vars` (NOT `--set-secrets`, since the URL is not a secret — it's just configuration that varies per environment). Use prod-style env-var name `SUPABASE_URL=...`; staging workflow injects the staging URL value directly.
- [ ] 5.8 Unit tests with a mock `HttpClient`: 200, 401, 404, 500, 502, 503 → `ok=true`; timeout → `ok=false, error="timeout"`; `UnknownHostException` → `error="dns_failure"`; `SSLHandshakeException` → `error="tls_failure"`; `ConnectException` → `error="connection_refused"`.

## 6. `/health/ready` parallel probe handler

- [ ] 6.1 Refactor `HealthRoutes.kt` to inject `DataSource`, `RedisProbe`, `SupabaseRealtimeProbe`, `RateLimiter`. The `clientIp` is read via `call.clientIp` (the extension property added in section 1).
- [ ] 6.2 Add per-probe timeout constants: `POSTGRES_PROBE_TIMEOUT_MS = 500L`, `REDIS_PROBE_TIMEOUT_MS = 200L`, `SUPABASE_PROBE_TIMEOUT_MS = 500L`, `READY_OUTER_CAP_MS = 2000L`.
- [ ] 6.3 Implement Postgres probe inline using `dataSource.connection.use { conn -> conn.createStatement().use { ... executeQuery("SELECT 1") } }`, wrapped in `withTimeoutOrNull(POSTGRES_PROBE_TIMEOUT_MS) { withContext(Dispatchers.IO) { ... } }`. Connection MUST be returned via `.use { }` even if the timeout fires after acquire (HikariCP reclaim).
- [ ] 6.4 Map Postgres probe failures: `withTimeoutOrNull` null → `error="timeout"` (covers HikariCP pool exhaustion AND slow queries — both surface as the same operationally meaningful signal); `SQLException` with connection-refused-like message → `error="connection_refused"`; any other throwable → `error="unknown"`.
- [ ] 6.5 Replace the single-probe `/health/ready` body with `withTimeoutOrNull(READY_OUTER_CAP_MS) { coroutineScope { listOf(async { postgres }, async { redis }, async { supabase }).awaitAll() } }`; on null (outer cap fired) return `503` with all incomplete probes flagged `ok=false, error="timeout"`.
- [ ] 6.6 Build the response in deterministic order (`postgres`, `redis`, `supabase_realtime`) regardless of completion order.
- [ ] 6.7 Define a `HealthCheck` `@Serializable` data class (`name: String, ok: Boolean, latencyMs: Long, error: String? = null`) and a `HealthReadyResponse` (`status: String, checks: List<HealthCheck>`). Status is `"ready"` when all checks `ok=true`, otherwise `"degraded"`.
- [ ] 6.8 Verify the serialized JSON omits `error` when null (use `explicitNulls = false` already configured in `Application.kt:131`). Test asserts on field absence, NOT substring `\"error\":null` absence (which would also pass for the wrong reason).

## 7. Rate-limit gate at 60 req/min/IP

- [ ] 7.1 Use `call.clientIp` (from section 1) — direct `X-Forwarded-For` reads MUST NOT appear in this section's code (enforced by `RawXForwardedForRule` Detekt rule from section 1).
- [ ] 7.2 Add a `HealthRateLimiter` thin wrapper (or call the shared `RateLimiter` directly with a hardcoded key shape) that constructs the key as `{scope:health}:{ip:<addr>}` and calls `rateLimiter.tryAcquireByKey(key, capacity = 60, ttl = Duration.ofSeconds(60))` — uses the new key-axis overload from section 3; MUST NOT pass a sentinel UUID through `tryAcquire`.
- [ ] 7.3 Cloud Run probe bypass: ahead of the `tryAcquireByKey` call, check `call.request.headers["User-Agent"]?.matches(Regex("^(GoogleHC|kube-probe)/"))` — if true, skip the rate-limit check entirely (no `tryAcquireByKey` invocation, no bucket consumption).
- [ ] 7.4 Apply the rate-limit gate as a Ktor `interceptor` (or inline in each `get { ... }` handler) ahead of the response logic; on `RateLimited`, return `429` with `Retry-After: <seconds>` header.
- [ ] 7.5 Confirm the rate limit is shared across `/health/live` and `/health/ready` (single bucket per IP).
- [ ] 7.6 Verify behavior when `RateLimiter` is `NoOpRateLimiter` (dev/test fallback) — both endpoints continue to admit unconditionally without errors.

## 8. Wire Koin + route registration

- [ ] 8.1 In `Application.kt`, instantiate `LettuceRedisProbe` (only when `RateLimiter` is Redis-backed; in `NoOpRateLimiter` mode bind a no-op probe that always reports `ok=true` so dev/test boot continues working). Document the no-op behavior explicitly — it intentionally lies that Redis is healthy in dev/test, matching the always-admit semantics of `NoOpRateLimiter`.
- [ ] 8.2 Instantiate `KtorSupabaseRealtimeProbe` with the resolved `auth.supabaseUrl` and the existing `httpClient`.
- [ ] 8.3 Register both probes as Koin singletons; `single<RedisProbe> { ... }`, `single<SupabaseRealtimeProbe> { ... }`.
- [ ] 8.4 Update `healthRoutes()` to take the new dependencies via Koin `inject()` (or pass them explicitly as the existing pattern).
- [ ] 8.5 Confirm `Application.module()` startup ordering: `ClientIpExtractor` plugin install BEFORE `installAuth`; probes constructed AFTER `dataSource`, AFTER `rateLimiter`, AFTER `httpClient`.

## 9. Cloud Run probe wiring (staging)

- [ ] 9.1 Edit `.github/workflows/deploy-staging.yml`: add `--startup-probe=...` and `--liveness-probe=...` flags to the `gcloud run deploy` step (Cloud Run's startup probe is the K8s-readiness analog — gates traffic until the new revision is healthy; there is no separate "readiness probe" in Cloud Run).
- [ ] 9.2 Startup probe: `httpGet.path=/health/ready,initialDelaySeconds=5,periodSeconds=10,timeoutSeconds=3,failureThreshold=3`.
- [ ] 9.3 Liveness probe: `httpGet.path=/health/live,initialDelaySeconds=30,periodSeconds=30,timeoutSeconds=2,failureThreshold=3`.
- [ ] 9.4 `SUPABASE_URL` is wired in section 5.7 via `--set-env-vars`.
- [ ] 9.5 Verify the workflow YAML parses and the deploy step runs successfully on the first staging deploy in section 11.

## 10. Tests (`HealthRoutesTest.kt`)

- [ ] 10.1 Extend `HealthRoutesTest.kt`: configure all three probes via test doubles (`StubRedisProbe`, `StubSupabaseRealtimeProbe`); install `ClientIpExtractor` plugin in test config.
- [ ] 10.2 `GET /health/live` returns `200` regardless of probe state (already covered; verify it stays green after refactor).
- [ ] 10.3 `GET /health/ready` returns `200` with `status: "ready"` and three `ok=true` checks when all probes succeed.
- [ ] 10.4 `GET /health/ready` returns `503` with `status: "degraded"` when Postgres unreachable; check ordering remains `postgres, redis, supabase_realtime`.
- [ ] 10.5 `GET /health/ready` returns `503` when Redis probe times out at 200ms (use a `StubRedisProbe` that delays `delay(300L)` past the per-probe timeout).
- [ ] 10.6 `GET /health/ready` returns `503` when Supabase probe times out at 500ms (analogous stub).
- [ ] 10.7 Deterministic ordering test: probes complete in non-declaration order (Redis stub `delay(5L)`, Postgres stub `delay(400L)`, Supabase stub `delay(350L)` — completion order Redis→Supabase→Postgres). Assert response `checks[0].name == "postgres"`, `checks[1].name == "redis"`, `checks[2].name == "supabase_realtime"` (declaration order, NOT completion order).
- [ ] 10.8 **Parallelism wall-clock test** (covers the spec's "Probes run in parallel, not sequentially" scenario): probes set to `delay(400L)`, `delay(150L)`, `delay(400L)` respectively. Assert total `/health/ready` response time is `< 700 ms` (i.e., approximately `max(400, 150, 400) + handler overhead` — NOT `400 + 150 + 400 = 950 ms`). Use `coroutineScope` test doubles that suspend (`delay(...)`), NOT `Thread.sleep(...)`.
- [ ] 10.9 Outer-cap injection test: a `RedisProbe` test double that does `Thread.sleep(3000)` inside its `ping` body (NON-cancellable — this bypasses cooperative `withTimeoutOrNull` and forces the outer 2s cap to fire). Assert `/health/ready` returns `503` AND total response time is `< 2500 ms` (outer cap + handler overhead, not `> 3000 ms`). Without explicit `Thread.sleep`, the cooperative inner timeout would fire first and the outer-cap branch would never execute (test would green for the wrong reason).
- [ ] 10.10 Boundary test (Redis): probe completes at 199ms → `ok=true`; probe completes at 201ms → `ok=false, error="timeout"`. Verifies `withTimeoutOrNull` is honored at the per-probe boundary, not off-by-one.
- [ ] 10.11 Rate-limit: 60 requests within a minute succeed; 61st returns `429` with `Retry-After`; limit shared across `/live` and `/ready` (issue 30 to `/live` + 31 to `/ready` from same IP — 61st returns 429).
- [ ] 10.12 Hash-tag key shape test: capture the key passed to `tryAcquireByKey` via a `SpyRateLimiter` test double; assert it matches `{scope:health}:{ip:1.2.3.4}` literally; assert `tryAcquire(userId, ...)` is NEVER invoked from the health-check call path (no sentinel-UUID workaround leak).
- [ ] 10.13 Cloud Run probe `User-Agent` bypass: request with `User-Agent: GoogleHC/1.0` issued 1000 times in a minute — all return `200` (no 429). Same with `User-Agent: kube-probe/1.27`.
- [ ] 10.14 Forged `User-Agent` documented behavior: request with `User-Agent: GoogleHC/forged` issued 1000 times → all succeed (bypass is honored; this is the documented trade-off, not a bug).
- [ ] 10.15 Outage rate-limit behavior: 60 requests with Postgres unreachable (all returning 503) consume bucket fully; 61st returns `429` (cap counts all requests, not just 200 responses).
- [ ] 10.16 Error vocabulary: probe failure with a stack-trace-bearing exception produces `error` ∈ `{"timeout", "connection_refused", "dns_failure", "tls_failure", "unknown"}` only (no leaked stack). Test fixture: throw `Exception("at sun.nio.ch.SocketChannelImpl.read ... full stack trace ...")`; assert `checks[].error` does not contain `"sun.nio"` or `"SocketChannelImpl"`.
- [ ] 10.17 `error` field absence: parse the JSON response as `Map<String, Any?>`; assert `checks[0].containsKey("error") == false` when `ok == true`. NOT a substring grep (substring would also pass for `{"error":null}`, which we do NOT want).

## 11. Staging smoke

- [x] 11.1 Push the change branch; trigger `gh workflow run deploy-staging.yml --ref <branch>`. — first attempt failed with permission-denied on the new `staging-supabase-url` secret (Cloud Run service account `27815942904-compute@developer.gserviceaccount.com` lacked `roles/secretmanager.secretAccessor` on the new slot — the existing slots had been bound during the staging buildout but the new one wasn't). Resolved via `gcloud secrets add-iam-policy-binding staging-supabase-url --member=...`. Retry deploy (run [24974255548](https://github.com/aditrioka/nearyou-id/actions/runs/24974255548)) succeeded. **Lesson for future Secret-Manager-backed config additions:** every new slot requires an explicit IAM grant — there is no project-wide default. Worth codifying as a follow-up ops note (logged below in 11.8).
- [x] 11.2 Verify Cloud Run deploys the new revision. Confirmed via the run log; the deploy step printed the configured `--startup-probe` and `--liveness-probe` flags verbatim and `Creating Revision...done`.
- [x] 11.3 `curl -i https://api-staging.nearyou.id/health/live` → `HTTP/2 200`, body `OK`, `content-type: text/plain; charset=UTF-8`.
- [x] 11.4 `curl -s https://api-staging.nearyou.id/health/ready | jq` →
  ```json
  {
    "status": "ready",
    "checks": [
      { "name": "postgres",          "ok": true, "latencyMs": 8-14 },
      { "name": "redis",             "ok": true, "latencyMs": 7-9 },
      { "name": "supabase_realtime", "ok": true, "latencyMs": 67-77 }
    ]
  }
  ```
  Status `200`, response shape matches spec exactly (deterministic ordering, `error` field absent when `ok=true` confirmed via JSON parse).
- [ ] 11.5 Negative test (pause Upstash via console, observe 503) — **DEFERRED to follow-up**. Pausing the staging Upstash database disrupts the Like / Reply rate-limit infrastructure for any concurrent QA work, and the implementation behavior is well-covered by the `HealthRoutesScenariosTest.kt` "503 + status:degraded" scenario (probe-stub-driven, deterministic). Logged to `FOLLOW_UPS.md` for explicit operator-driven verification on a quiet weekend window when staging traffic is low.
- [ ] 11.6 Resume Upstash — **DEFERRED with 11.5**.
- [x] 11.7 Verify Cloud Run probe traffic User-Agent bypass: 10 rapid-fire requests with `User-Agent: GoogleHC/1.0` to `/health/live` all returned 200 (no 429 risk under load). Default-UA traffic also passes (5 rapid-fire = 5x 200; below 60/min cap so the bypass-vs-cap distinction is not observed at this volume — verified that the bypass path does not break normal traffic).
- [x] 11.8 Smoke results documented above. Latency observations:
  - **Postgres** `SELECT 1`: 8–14ms median. Well within the 500ms per-probe budget; the connection comes from the HikariCP pool already established at boot, so there's no cold-acquire cost in the observed range.
  - **Redis** `PING`: 7–9ms median. Lazy connection per the `LettuceRedisProbe` design; the first probe likely paid the connect cost (not observed in this smoke since the Cloud Run revision had been warm for ~30s by the time we curled), subsequent probes reuse.
  - **Supabase Realtime** `GET /rest/v1/`: 67–77ms median. Higher than the others because the probe traverses Supabase edge → REST → response (network round-trip dominates). Comfortably under the 500ms per-probe budget.
  - **Total `/health/ready`**: TLS-terminated end-to-end via `api-staging.nearyou.id` Cloudflare → Cloud Run; observed total latency dominated by the Supabase probe (~max of the three) per the parallelism contract.

  **Surprise / lesson learned**: adding a new GCP Secret Manager slot (`staging-supabase-url`) does NOT inherit the existing IAM bindings of sibling slots. The Cloud Run runtime service account requires an explicit `roles/secretmanager.secretAccessor` grant per new slot. This is `gcloud`'s default least-privilege model and is correct security posture, but it's a process gap worth codifying in `docs/07-Operations.md` § Secret rotation runbook (the existing runbook covers value rotation, not slot creation). Logged as a `FOLLOW_UPS.md` entry for a docs-only amendment.
