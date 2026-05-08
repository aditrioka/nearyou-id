## ADDED Requirements

### Requirement: Redis-backed admit emits structured log on every Redis-evaluated outcome

The Redis-backed `RateLimiter` implementation in `:infra:redis` (per the parent capability's "Redis-backed `RateLimiter` implementation via Lua sliding window" requirement) SHALL emit a structured log entry on every admit attempt that successfully invokes the Lua sliding-window script — both `Allowed` and `RateLimited` outcomes — at level `DEBUG`. The log SHALL use the format:

```
event=rate_limit_check user_id={uuid} key={key} outcome=allowed remaining={int}
event=rate_limit_check user_id={uuid} key={key} outcome=rate_limited retry_after_seconds={int}
```

…when the admit was invoked via `tryAcquire(userId, ...)` (user-axis), AND the format:

```
event=rate_limit_check key={key} outcome=allowed remaining={int}
event=rate_limit_check key={key} outcome=rate_limited retry_after_seconds={int}
```

…when the admit was invoked via `tryAcquireByKey(key, ...)` (key-axis). The two formats MUST be selected at the call site via the same `if (telemetryUserId != null)` conditional that already routes the failure-path EVALSHA log between user-axis and key-axis variants in `RedisRateLimiter.admit()`. A future refactor that consolidates the two formats into a single statement with a placeholder eaten when null (e.g., `logger.debug("event=... user_id={} ...", telemetryUserId, ...)` invoked with `telemetryUserId = null`) is forbidden — that pattern silently emits the literal text `user_id=null` in the formatted message, which violates the key-axis anti-info-leak invariant the parent capability already enforces on the failure path.

The `outcome` field value MUST be exactly `allowed` or `rate_limited` (lowercase, snake-case). The `remaining` field appears only for `outcome=allowed` (matching the `Outcome.Allowed.remaining` payload). The `retry_after_seconds` field appears only for `outcome=rate_limited` (matching the `Outcome.RateLimited.retryAfterSeconds` payload, coerced to ≥ 1 per the parent capability's existing requirement).

The log level is `DEBUG` (not `INFO`) because the success-path log fires on every admit attempt — at production scale this is one log line per request that touches a rate-limited path. INFO-level emission would multiply Cloud Logging volume by the rate-limit cadence; DEBUG keeps the log opt-in for incident triage (operators raise the `id.nearyou.app.infra.redis.RedisRateLimiter` logger level via Logback config edit + redeploy when investigating a hot-key incident; there is no env-var-driven runtime override mechanism in the current `application.conf` / `logback.xml` shape).

The connection-failure path (`event=redis_connect_failed`) and the EVALSHA-failure path (`event=redis_acquire_failed`) at WARN level are unchanged. The parent capability's "tryAcquireByKey omits userId from telemetry" scenario continues to apply across all log emissions — this requirement makes its IF clause concrete in steady state (success-path) rather than vacuous as it was prior to this change.

**Fail-soft early-return is excluded by design.** When `sync()` returns null (Redis unreachable; the implementation's fail-soft path), `admit()` returns `Allowed(remaining = capacity)` without invoking the Lua script. The new `event=rate_limit_check` log MUST NOT fire on this path — the existing `event=redis_connect_failed` WARN log at the `sync()` failure site already carries the operator signal, and emitting `event=rate_limit_check` with synthetic `remaining=capacity` would be misleading (the bucket count is unknown when Redis is unreachable). This is why the requirement title scopes to "Redis-evaluated outcome" rather than "every outcome."

#### Scenario: Success-path log carries user_id only on user-axis call

- **WHEN** `tryAcquire(userId = U, key = "{scope:rate_test_day}:{user:U}", capacity = 10, ttl = computeTTLToNextReset(U))` is invoked against a fresh bucket AND the admit returns `Allowed` AND a Logback DEBUG-level appender attached to `RedisRateLimiter` captures the resulting log events
- **THEN** exactly one captured event has formatted message containing `event=rate_limit_check` AND `user_id=<U as string>` AND `key={scope:rate_test_day}:{user:U}` AND `outcome=allowed` AND `remaining=9` AND no captured event for that admit contains `event=redis_acquire_failed` or `event=redis_connect_failed` (positive verification of all expected fields, format-arg-ordering-resistant — a future maintainer who swaps `key` and `userId` in the call would fail the assertion because the swapped key value would not equal the literal expected `{scope:rate_test_day}:{user:U}` substring)

#### Scenario: Success-path log on key-axis call omits user_id

- **WHEN** `tryAcquireByKey(key = "{scope:health}:{ip:abc123def4567890}", capacity = 60, ttl = Duration.ofSeconds(60))` is invoked against a fresh bucket AND the admit returns `Allowed` AND a Logback DEBUG-level appender attached to `RedisRateLimiter` captures the resulting log events
- **THEN** exactly one captured event has formatted message containing `event=rate_limit_check` AND `key={scope:health}:{ip:abc123def4567890}` AND `outcome=allowed` AND `remaining=59` AND every captured DEBUG event from this admit MUST NOT contain `user_id=` (forEach-shape assertion mirroring [`RedisRateLimiterTelemetryTest:97-100`](../../../infra/redis/src/test/kotlin/id/nearyou/app/infra/redis/RedisRateLimiterTelemetryTest.kt) precedent — stronger than "exactly one event without user_id," catches future refactors that emit a second log line) AND every captured event MUST NOT contain `00000000-0000-0000-0000-000000000000` (sentinel-UUID anti-info-leak invariant)

#### Scenario: User-axis rate-limited path carries retry_after_seconds outcome

- **WHEN** `tryAcquire(userId = U, key = "{scope:rate_test_day}:{user:U}", capacity = 1, ttl = Duration.ofSeconds(60))` is invoked twice sequentially against a fresh bucket — the first admit returns `Allowed`, the second returns `RateLimited` — AND a Logback DEBUG-level appender attached to `RedisRateLimiter` captures the resulting log events
- **THEN** the captured event for the second admit has formatted message containing `event=rate_limit_check` AND `user_id=<U as string>` AND `key={scope:rate_test_day}:{user:U}` AND `outcome=rate_limited` AND `retry_after_seconds=<int ≥ 1>` AND the captured `retry_after_seconds` integer value matches the `Outcome.RateLimited.retryAfterSeconds` returned to the caller byte-for-byte (no ±tolerance — the value travels through the same admit() return statement as the log, so they cannot diverge)

#### Scenario: Key-axis rate-limited path omits user_id and carries retry_after_seconds

- **WHEN** `tryAcquireByKey(key = "{scope:health}:{ip:abc123def4567890}", capacity = 1, ttl = Duration.ofSeconds(60))` is invoked twice sequentially against a fresh bucket — the first admit returns `Allowed`, the second returns `RateLimited` — AND a Logback DEBUG-level appender attached to `RedisRateLimiter` captures the resulting log events
- **THEN** the captured event for the second admit has formatted message containing `event=rate_limit_check` AND `key={scope:health}:{ip:abc123def4567890}` AND `outcome=rate_limited` AND `retry_after_seconds=<int ≥ 1>` AND every captured DEBUG event from this admit MUST NOT contain `user_id=` (key-axis-rate-limited symmetry with the user-axis-rate-limited scenario above; canonical call site is the `health-check` capability's 60-req/min anti-scrape limiter on a hot IP)

#### Scenario: Fail-soft early-return path emits no rate_limit_check log

- **WHEN** `sync()` returns null (Redis unreachable) AND `tryAcquireByKey(key = "{scope:health}:{ip:abc123def4567890}", capacity = 60, ttl = Duration.ofSeconds(60))` is invoked AND a Logback DEBUG-level appender attached to `RedisRateLimiter` captures the resulting log events
- **THEN** the admit returns `Allowed(remaining = 60)` (the synthetic fail-soft outcome, equal to capacity) AND zero captured events contain `event=rate_limit_check` (the new log MUST NOT fire on the early-return path; the existing `event=redis_connect_failed` WARN log at the `sync()` failure site is the operator signal) AND at least one captured event contains `event=redis_connect_failed` (the existing fail-soft contract from the parent capability is preserved)

#### Scenario: DEBUG-filtered emission contract (log is opt-in, never load-bearing)

- **WHEN** the `id.nearyou.app.infra.redis.RedisRateLimiter` logger's effective level is INFO or higher (the new log entries are filtered out by the appender) AND `tryAcquireByKey(key = "{scope:test}:{user:U}", capacity = 10, ttl = Duration.ofSeconds(60))` is invoked
- **THEN** the admit returns `Allowed(remaining = 9)` (the call still succeeds; the runtime contract is unchanged regardless of log level) AND zero `event=rate_limit_check` entries are captured (the log is opt-in via DEBUG-level enablement; never load-bearing for runtime correctness; future logback-pin refactors that disable DEBUG emission MUST not break the admit contract)

#### Scenario: Source-level structural conditional enforces else-branch placement

- **WHEN** reading `infra/redis/src/main/kotlin/id/nearyou/app/infra/redis/RedisRateLimiter.kt`
- **THEN** the source contains a regex match for `if \(telemetryUserId !=\s*null\) \{[\s\S]*?event=rate_limit_check user_id=\{\} key=\{\} outcome=allowed[\s\S]*?\} else \{[\s\S]*?event=rate_limit_check key=\{\} outcome=allowed[\s\S]*?\}` (success-path branches structurally enforced — key-only format string MUST sit inside an `else` branch of the user-axis conditional, not appear after the conditional or stack alongside the user-axis line) AND analogous regex match for the `outcome=rate_limited retry_after_seconds=\{\}` shape — mirroring the existing failure-path conditional at lines 145-160 byte-for-byte. A consolidated single-format-string success-path log (without the if/else conditional), OR a copy-paste-error refactor that emits BOTH lines unconditionally with the user-axis line outside the conditional, is a spec violation regardless of runtime behavior because the structural test catches refactor-induced silent `user_id=null` leakage that runtime tests can miss when the appender's formatter eats literal-null output. The regex tolerance for `[\s\S]*?` between landmarks accommodates inline comments and reasonable formatting variations.

### Requirement: Test coverage — Redis-backed admit telemetry success-path

A test class `RedisRateLimiterTelemetryTest` SHALL exist at `infra/redis/src/test/kotlin/id/nearyou/app/infra/redis/RedisRateLimiterTelemetryTest.kt` (it already exists; this requirement extends its scenario enumeration with success-path coverage). The test class MUST cover, in addition to the existing fail-soft-connect-failure + structural-source-shape scenarios:

1. **User-axis success-path log**: connect to the CI `redis:7-alpine` service container (test tagged `database`; pre-clear via UUID-suffixed test key — MUST NOT use `FLUSHDB` since sibling integration tests in the same JVM share the Redis instance per the [`RedisRateLimiterIntegrationTest`](../../../infra/redis/src/test/kotlin/id/nearyou/app/infra/redis/RedisRateLimiterIntegrationTest.kt) `freshKey(scope)` precedent), attach a Logback `ListAppender<ILoggingEvent>` to `RedisRateLimiter` with level lowered to DEBUG inside a `try { ... } finally { ... }` block, invoke `tryAcquire(userId = U, key, capacity, ttl)` against a fresh bucket, assert exactly one captured DEBUG event with `event=rate_limit_check`, `user_id=<U>`, `key=<key>`, `outcome=allowed`, `remaining=<capacity-1>`.
2. **Key-axis success-path log MUST NOT carry user_id**: same context, invoke `tryAcquireByKey(key, capacity, ttl)`, assert via `forEach { line -> line.contains("user_id=") shouldBe false }` shape that EVERY captured DEBUG event omits `user_id=` AND none contain `00000000-0000-0000-0000-000000000000`.
3. **Rate-limited path log carries retry_after_seconds**: same context with `capacity = 1`, invoke `tryAcquire` twice sequentially, assert the second event captures `event=rate_limit_check`, `outcome=rate_limited`, `retry_after_seconds=<int ≥ 1>`. The captured `retry_after_seconds` value MUST match the `Outcome.RateLimited.retryAfterSeconds` byte-for-byte.
4. **Key-axis rate-limited test**: same context with `tryAcquireByKey(key, capacity = 1, ttl)` invoked twice; assert second event captures `event=rate_limit_check`, `key=<key>`, `outcome=rate_limited`, `retry_after_seconds=<int ≥ 1>`, AND every captured DEBUG event omits `user_id=`. (Symmetric with scenario 3 for the key-axis path; covers the canonical `/health` 60-req/min anti-scrape call site behavior at saturation.)
5. **Fail-soft early-return non-emission**: connect to an unreachable Redis (mirroring the existing `RedisRateLimiterTelemetryTest` precedent at line 42 `unreachableUrl = "redis://127.0.0.1:1"`), invoke `tryAcquireByKey(key, capacity, ttl)`, assert the captured DEBUG appender contains zero `event=rate_limit_check` entries AND at least one `event=redis_connect_failed` entry. Negative-emission contract: the new log MUST NOT fire on the early-return path.
6. **DEBUG-filtered emission contract**: connect to real Redis (database-tagged); attach the appender at INFO level (NOT DEBUG); invoke `tryAcquireByKey(key, capacity, ttl)`; assert the admit succeeds (returns `Allowed(remaining = capacity-1)`) AND zero `event=rate_limit_check` entries captured (proves the log is opt-in via DEBUG-level enablement, not load-bearing for runtime).
7. **Source-level structural extension**: extend the existing `"RedisRateLimiter source has admit-time log conditional on telemetryUserId"` test (currently asserts on the failure-path two-format-string shape) to ALSO assert (via regex `Regex.matches`) that the success-path conditional has the if/else shape with the user-axis format string inside the `if` branch and the key-axis format string inside the `else` branch — closes the copy-paste-error gap (the existing substring-only check would pass a refactor that stacks both lines outside the conditional). The existing failure-path assertions are preserved.

The test class's appender helper MUST restore the original logger level on teardown. Implementation pattern: save the level via `logger.level` BEFORE `setLevel(DEBUG)`, restore in the `finally` block. The restore MUST be exception-safe across all paths (test fixture init failure, assertion failure, test timeout) — wrap the `setLevel(originalLevel)` call in a nested `try { restoreLevel() } catch (Exception) { /* best-effort */ }` so a teardown error does not mask the original test failure. Cross-test pollution (DEBUG level leaking into other tests in the same JVM) is forbidden.

#### Scenario: All success-path telemetry test scenarios discoverable

- **WHEN** running `./gradlew :infra:redis:test --tests RedisRateLimiterTelemetryTest`
- **THEN** the test class is discovered AND each of the seven scenarios above (user-axis success, key-axis success without user_id, user-axis rate-limited, key-axis rate-limited, fail-soft non-emission, DEBUG-filtered non-emission, source-level structural extension) corresponds to at least one test method that asserts the documented invariant

#### Scenario: Cross-test pollution prevented via effectiveLevel comparison

- **WHEN** the success-path scenarios complete AND a subsequent assertion in the same `RedisRateLimiterTelemetryTest` spec reads the logger via `LoggerFactory.getLogger(RedisRateLimiter::class.java) as Logback.Logger` AND retrieves `logger.effectiveLevel` (NOT `logger.level` — `level` returns the configured level which may be `null` for inherited-from-root loggers; `effectiveLevel` always resolves to the parent's level via the Logback inheritance chain)
- **THEN** the effective level matches the value observed BEFORE any DEBUG-bumping scenario ran (typically TRACE inherited from `<root level="trace">` in `logback.xml`, or whatever `logback-test.xml` overrides for the test classpath) — confirming the appender helper's teardown restored the original level cleanly. The scenario MUST run AFTER all DEBUG-bumping scenarios via Kotest `IsolationMode.SingleInstance` (default for `StringSpec`) and explicit ordering — list this scenario LAST in the `StringSpec` block.

#### Scenario: Source-level structural assertion catches success-path conditional drop

- **WHEN** a hypothetical refactor consolidates the success-path two-format-string conditional into a single-format-string statement (e.g., `logger.debug("event=rate_limit_check user_id={} key={} outcome={} ...", telemetryUserId, key, outcome, ...)` invoked with `telemetryUserId = null` for key-axis calls), OR copy-pastes both format strings outside the if/else conditional so they fire unconditionally
- **THEN** the source-level structural test fails because the regex `if \(telemetryUserId !=\s*null\) \{[\s\S]*?event=rate_limit_check user_id=\{\} key=\{\} outcome=allowed[\s\S]*?\} else \{[\s\S]*?event=rate_limit_check key=\{\} outcome=allowed[\s\S]*?\}` no longer matches the source — preventing the silent `user_id=null` leak (single-format-string variant) AND the silent `user_id=` re-introduction-on-key-axis leak (unconditional-stacked variant) that runtime appender tests can miss when the appender's formatter eats literal-null output OR when the unconditional second emission creates two log entries whose user_id-absence assertion only inspects "exactly one event"
