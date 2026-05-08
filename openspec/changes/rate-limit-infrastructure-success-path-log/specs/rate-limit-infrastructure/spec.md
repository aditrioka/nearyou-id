## ADDED Requirements

### Requirement: Redis-backed admit emits structured log on every outcome

The Redis-backed `RateLimiter` implementation in `:infra:redis` (per the parent capability's "Redis-backed `RateLimiter` implementation via Lua sliding window" requirement) SHALL emit a structured log entry on every successful admit attempt — both `Allowed` and `RateLimited` outcomes — at level `DEBUG`. The log SHALL use the format:

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

The log level is `DEBUG` (not `INFO`) because the success-path log fires on every admit attempt — at production scale this is one log line per request that touches a rate-limited path. INFO-level emission would multiply Cloud Logging volume by the rate-limit cadence; DEBUG keeps the log opt-in for incident triage (operators raise the `id.nearyou.app.infra.redis.RedisRateLimiter` logger level via Logback config or env override when investigating a hot-key incident).

The connection-failure path (`event=redis_connect_failed`) and the EVALSHA-failure path (`event=redis_acquire_failed`) at WARN level are unchanged. The parent capability's "tryAcquireByKey omits userId from telemetry" scenario continues to apply across all log emissions — this requirement makes its IF clause concrete in steady state (success-path) rather than vacuous as it was prior to this change.

#### Scenario: Success-path log carries user_id only on user-axis call

- **WHEN** `tryAcquire(userId = U, key = "{scope:rate_like_day}:{user:U}", capacity = 10, ttl = computeTTLToNextReset(U))` is invoked against a fresh bucket AND the admit returns `Allowed` AND a Logback DEBUG-level appender attached to `RedisRateLimiter` captures the resulting log events
- **THEN** exactly one captured event has formatted message containing `event=rate_limit_check`, `user_id=<U as string>`, `key={scope:rate_like_day}:{user:U}`, `outcome=allowed`, `remaining=9` AND no captured event for that admit contains `event=redis_acquire_failed` or `event=redis_connect_failed`

#### Scenario: Success-path log on key-axis call omits user_id

- **WHEN** `tryAcquireByKey(key = "{scope:health}:{ip:abc123def4567890}", capacity = 60, ttl = Duration.ofSeconds(60))` is invoked against a fresh bucket AND the admit returns `Allowed` AND a Logback DEBUG-level appender attached to `RedisRateLimiter` captures the resulting log events
- **THEN** exactly one captured event has formatted message containing `event=rate_limit_check`, `key={scope:health}:{ip:abc123def4567890}`, `outcome=allowed`, `remaining=59` AND that event's formatted message MUST NOT contain `user_id=` AND MUST NOT contain `00000000-0000-0000-0000-000000000000` (sentinel-UUID anti-info-leak invariant)

#### Scenario: Rate-limited path log carries retry_after_seconds outcome

- **WHEN** `tryAcquire(userId = U, key = "{scope:rate_test_day}:{user:U}", capacity = 1, ttl = Duration.ofSeconds(60))` is invoked twice sequentially against a fresh bucket — the first admit returns `Allowed`, the second returns `RateLimited` — AND a Logback DEBUG-level appender attached to `RedisRateLimiter` captures the resulting log events
- **THEN** the captured event for the second admit has formatted message containing `event=rate_limit_check`, `user_id=<U as string>`, `key={scope:rate_test_day}:{user:U}`, `outcome=rate_limited`, `retry_after_seconds=<int >= 1>` AND the `retry_after_seconds` value matches the `Outcome.RateLimited.retryAfterSeconds` returned to the caller byte-for-byte

#### Scenario: Source-level structural conditional for success-path log

- **WHEN** reading `infra/redis/src/main/kotlin/id/nearyou/app/infra/redis/RedisRateLimiter.kt`
- **THEN** the source contains an `if (telemetryUserId != null)` (with optional whitespace variant `if (telemetryUserId !=null)`) branch in or adjacent to the success-path log emission AND two distinct format strings are present — one matching the literal `event=rate_limit_check user_id={} key={} outcome=` and one matching `event=rate_limit_check key={} outcome=` — mirroring the existing failure-path conditional at lines 145-160 byte-for-byte. A consolidated single-format-string success-path log (without the conditional) is a spec violation regardless of runtime behavior, because the structural test catches refactor-induced silent `user_id=null` leakage that runtime tests can miss when the appender filters the literal-`null` output

### Requirement: Test coverage — Redis-backed admit telemetry success-path

A test class `RedisRateLimiterTelemetryTest` SHALL exist at `infra/redis/src/test/kotlin/id/nearyou/app/infra/redis/RedisRateLimiterTelemetryTest.kt` (it already exists; this requirement extends its scenario enumeration with success-path coverage). The test class MUST cover, in addition to the existing fail-soft-connect-failure + structural-source-shape scenarios:

1. **User-axis success-path log**: connect to the CI `redis:7-alpine` service container (test tagged `database`), attach a Logback `ListAppender<ILoggingEvent>` to `RedisRateLimiter` with level lowered to DEBUG inside a `try { ... } finally { ... }` block, invoke `tryAcquire(userId = U, key, capacity, ttl)` against a fresh bucket, assert exactly one captured DEBUG event with `event=rate_limit_check`, `user_id=<U>`, `key=<key>`, `outcome=allowed`, `remaining=<capacity-1>`.
2. **Key-axis success-path log MUST NOT carry user_id**: same context, invoke `tryAcquireByKey(key, capacity, ttl)`, assert the captured DEBUG event's formatted message does NOT contain `user_id=` AND does NOT contain `00000000-0000-0000-0000-000000000000`.
3. **Rate-limited path log carries retry_after_seconds**: same context with `capacity = 1`, invoke `tryAcquire` twice sequentially, assert the second event captures `event=rate_limit_check`, `outcome=rate_limited`, `retry_after_seconds=<int >= 1>`. The captured `retry_after_seconds` value MUST match the `Outcome.RateLimited.retryAfterSeconds` byte-for-byte.
4. **Source-level structural extension**: extend the existing `"RedisRateLimiter source has admit-time log conditional on telemetryUserId"` test (currently asserts on the failure-path two-format-string shape) to ALSO assert on the success-path two-format-string shape — matching the literal `event=rate_limit_check user_id={} key={} outcome=` AND `event=rate_limit_check key={} outcome=` substrings. The existing failure-path assertion is preserved.

The test class's appender helper MUST restore the original logger level on teardown (the existing `captureWarnings` helper extension to `captureDebugAndWarnings` is the canonical mechanism). Cross-test pollution (DEBUG level leaking into other tests in the same JVM) is forbidden and the teardown MUST be exception-safe (`try { ... } finally { restoreLevel() }`).

#### Scenario: All success-path telemetry test scenarios discoverable

- **WHEN** running `./gradlew :infra:redis:test --tests RedisRateLimiterTelemetryTest`
- **THEN** the test class is discovered AND each of the four scenarios above (user-axis success, key-axis success without user_id, rate-limited path, source-level structural extension) corresponds to at least one test method that asserts the documented invariant

#### Scenario: Cross-test pollution prevented

- **WHEN** the success-path scenarios run AND a subsequent test in the same JVM (e.g., `RedisRateLimiterIntegrationTest`) reads the `RedisRateLimiter` logger's effective level
- **THEN** the effective level is the original level (typically INFO from `application.conf` HOCON or test config), not DEBUG — confirming the appender helper's teardown restored the original level cleanly

#### Scenario: Source-level structural assertion catches success-path conditional drop

- **WHEN** a hypothetical refactor consolidates the success-path two-format-string conditional into a single-format-string statement (e.g., `logger.debug("event=rate_limit_check user_id={} key={} outcome={} ...", telemetryUserId, key, outcome, ...)` invoked with `telemetryUserId = null` for key-axis calls)
- **THEN** the source-level structural test fails because the literal substring `event=rate_limit_check key={} outcome=` (without `user_id={}`) is no longer present in the source — preventing the silent `user_id=null` leak that runtime appender tests can miss when the appender's formatter eats literal-null output
