# rate-limit-infrastructure Specification

## Purpose

The rate-limit-infrastructure capability provides the shared Redis-backed primitives every per-endpoint rate limit reuses: the `:infra:redis` Lettuce client, the `RateLimiter` interface with daily-WIB-stagger and rolling-hour windowing strategies, the `computeTTLToNextReset(userId)` helper that distributes daily-cap reset across 00:00–01:00 WIB so traffic is not synchronized at midnight, and the `releaseMostRecent` escape hatch for idempotent no-op paths. The `RateLimitTtlRule` and `RedisHashTagRule` Detekt rules pin the conventions at every call site — a daily limiter must call `computeTTLToNextReset`, and every rate-limit Redis key must include a `{scope:<value>}` hash tag for cluster-safe multi-key ops.
## Requirements
### Requirement: `:infra:redis` module exists and exposes a Lettuce-backed client

A Gradle module `:infra:redis` SHALL exist with a `commonMain` source set (JVM target). The module MUST add Lettuce (group `io.lettuce`, artifact `lettuce-core`) as a dependency declared in `gradle/libs.versions.toml`. The module MUST expose a Koin module that provides a singleton `RedisClient` (Lettuce `io.lettuce.core.RedisClient`) configured from the URL resolved via `secretKey(env, "redis-url")` through the existing `SecretResolver` chain.

The module MUST NOT be imported from `:core:domain` or `:core:data` — only `:backend:ktor` and `:lint:detekt-rules` (test fixtures) may depend on it. No vendor SDK leaks: the `RateLimiter` interface (next requirement) is the only seam into business code.

#### Scenario: Module exists with Lettuce dependency
- **WHEN** reading `gradle/libs.versions.toml`
- **THEN** there is a single entry pinning `lettuce-core` to a specific version

#### Scenario: Backend depends on `:infra:redis`
- **WHEN** reading `backend/ktor/build.gradle.kts`
- **THEN** `dependencies { ... }` contains an entry referencing `:infra:redis`

#### Scenario: Core does NOT depend on `:infra:redis`
- **WHEN** reading `core/domain/build.gradle.kts` AND `core/data/build.gradle.kts`
- **THEN** neither declares a dependency on `:infra:redis`

#### Scenario: Redis URL resolved via SecretResolver
- **WHEN** the `:infra:redis` Koin module instantiates the `RedisClient` with `KTOR_ENV=staging`
- **THEN** the URL passed to `RedisClient.create(...)` is the value returned by `secretKey("staging", "redis-url")` (e.g. `staging-redis-url` slot in GCP Secret Manager)

### Requirement: `RateLimiter` interface in `:core:domain`

An interface `id.nearyou.app.core.domain.ratelimit.RateLimiter` SHALL exist in `:core:domain`. The interface MUST define exactly three methods:

```kotlin
fun tryAcquire(userId: UUID, key: String, capacity: Int, ttl: Duration): Outcome
fun tryAcquireByKey(key: String, capacity: Int, ttl: Duration): Outcome
fun releaseMostRecent(userId: UUID, key: String)
```

`Outcome` MUST be declared as a sealed interface in Kotlin with exactly two implementing data classes (mirroring V9 `ReportRateLimiter.Outcome` byte-for-byte — see `backend/ktor/src/main/kotlin/id/nearyou/app/moderation/ReportRateLimiter.kt:90-94`):

```kotlin
sealed interface Outcome {
    data class Allowed(val remaining: Int) : Outcome
    data class RateLimited(val retryAfterSeconds: Long) : Outcome
}
```

- `Allowed.remaining` is the number of slots left in the window AFTER consumption.
- `RateLimited.retryAfterSeconds` is the seconds until the oldest counted event ages out, suitable for a `Retry-After` HTTP header. MUST be coerced to at least 1.

The `key` parameter is the full hash-tag-formatted Redis key constructed by the caller (the limiter does not synthesize it). The `ttl` parameter is the Redis key TTL — daily callers pass `computeTTLToNextReset(userId)`; hourly callers pass `Duration.ofHours(1)` directly; sub-hourly anti-scrape callers (e.g., `/health` 60-second window) pass the window duration directly.

`tryAcquire` MUST be atomic across concurrent calls with the same `userId + key`: two simultaneous requests at slot `capacity` MUST NOT both observe `Allowed`. The `userId` parameter feeds into per-user telemetry (logs, metrics) and serves as the input to `computeTTLToNextReset(userId)` when the caller is a daily-cap site.

`tryAcquireByKey` MUST be atomic across concurrent calls with the same `key`: two simultaneous requests at slot `capacity` MUST NOT both observe `Allowed`. It is the axis-agnostic counterpart of `tryAcquire` for non-user-keyed buckets (IP, geocell, fingerprint, global circuit-breaker, etc.). It MUST share the **identical** Redis-side Lua sliding-window implementation as `tryAcquire` — no separate script, no behavioral divergence — only the call-site contract differs (no `userId`, no WIB-stagger TTL inference). Telemetry MUST tag the call with the `key` rather than a `userId`.

The convention for IP-keyed callers (the first such call site is the `health-check` capability) is the hash-tag key shape `{scope:<role>}:{ip:<hashed-ip>}` constructed by the caller from `IpHasher.hash(clientIp)` (per the project critical invariant: `clientIp` is read from the `CF-Connecting-IP` request-context value, never `X-Forwarded-For`; per the privacy posture: raw IP values MUST NOT appear in Lua keys — they leak into Tempo `db.statement` span attributes and the `key=` structured-log field). `IpHasher` is the sanctioned anonymization helper exported from `:infra:otel` (sibling of `UserIdHasher`, same shape: first 16 hex of `SHA-256(ip.toByteArray(StandardCharsets.UTF_8))`).

`releaseMostRecent` MUST pop the most-recently-added entry for `userId + key`. No-op if the bucket is empty (defensive — callers only invoke it after a successful `tryAcquire`). A `releaseMostRecent` overload for `tryAcquireByKey` is intentionally NOT introduced in this MODIFIED iteration: the only `tryAcquireByKey` call site at the time of this change (`/health/*` anti-scrape) does not exhibit the idempotent-re-action pattern (likes/posts re-application) that `releaseMostRecent` exists to support. A future change MAY add `releaseMostRecentByKey` if a key-axis call site needs the same idempotent-release affordance.

#### Scenario: Interface lives in :core:domain
- **WHEN** running `find core/domain/src/commonMain -name 'RateLimiter.kt'`
- **THEN** exactly one file is found AND its package is `id.nearyou.app.core.domain.ratelimit`

#### Scenario: Outcome shape matches V9 ReportRateLimiter precedent
- **WHEN** comparing `Outcome.Allowed`, `Outcome.RateLimited` field names
- **THEN** they match `id.nearyou.app.moderation.ReportRateLimiter.Outcome.Allowed.remaining` and `Outcome.RateLimited.retryAfterSeconds` from V9 byte-for-byte

#### Scenario: Concurrent tryAcquire at capacity boundary
- **WHEN** ten concurrent calls (issued via `runBlocking { (1..10).map { async(Dispatchers.IO) { tryAcquire(userId, key, capacity = 10, ttl = ...) } }.awaitAll() }` — real parallel coroutines, NOT a sequential loop) execute against an empty bucket
- **THEN** exactly ten observe `Allowed` AND any 11th concurrent call observes `RateLimited`

#### Scenario: Concurrent tryAcquireByKey at capacity boundary
- **WHEN** sixty concurrent calls (real parallel coroutines, not a sequential loop) execute against an empty bucket via `tryAcquireByKey(key = "{scope:health}:{ip:abc123def4567890}", capacity = 60, ttl = Duration.ofSeconds(60))`
- **THEN** exactly sixty observe `Allowed` AND any 61st concurrent call observes `RateLimited`

#### Scenario: tryAcquireByKey shares Lua script with tryAcquire
- **WHEN** comparing the Redis-side Lua script invoked by `tryAcquireByKey` vs `tryAcquire` in the `:infra:redis` `RedisRateLimiter` implementation
- **THEN** the SHA1 hash of the script bytes is identical (the implementations MUST converge on the same `EVALSHA` cache slot — divergence is a spec violation)

#### Scenario: tryAcquireByKey omits userId from telemetry
- **WHEN** `tryAcquireByKey(key = "{scope:health}:{ip:abc123def4567890}", capacity = 60, ttl = Duration.ofSeconds(60))` is invoked AND a structured log is emitted
- **THEN** the log entry includes `key=<key>` AND does NOT include a `user_id` field (and MUST NOT log a sentinel UUID like `00000000-0000-0000-0000-000000000000` — sentinel-UUID values are a code smell and forbidden)

#### Scenario: IP-axis key shape uses hashed IP, never raw
- **WHEN** any IP-axis caller (e.g., the `health-check` capability) constructs a Redis key for `tryAcquireByKey`
- **THEN** the `<addr>` segment in `{scope:<role>}:{ip:<addr>}` MUST be the result of `IpHasher.hash(clientIp)` (16-hex truncated SHA-256, exported from `:infra:otel`) AND MUST NOT be the raw IPv4 dotted-quad or IPv6 colon-delimited literal AND any literal matching the regex `\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b` or containing `::` between brace segments is a spec violation AND any structured log field carrying an IP-derived value alongside the rate-limit `key=<key>` field (per the telemetry log mandate above) MUST contain only the hashed form — emitting a `key.ip = <raw>` alias or any sibling log field with the raw IP literal is forbidden by the same posture

#### Scenario: releaseMostRecent on empty bucket is a no-op
- **WHEN** `releaseMostRecent(userId, key)` is called against a key that has never been written OR a key whose entries have all aged out and been pruned
- **THEN** the call returns without error AND the bucket size remains 0 AND no Redis error is propagated to the caller (defensive contract — the only legitimate caller is the no-op-idempotent path which only invokes after a successful `tryAcquire`, but the implementation MUST still tolerate the empty-bucket case for future call sites)

#### Scenario: releaseMostRecent reverses the most recent slot
- **WHEN** a caller observes `Allowed` then calls `releaseMostRecent` immediately AND then `tryAcquire` is called again with the same `(userId, key)`
- **THEN** the second `tryAcquire` succeeds (the first slot was returned)

### Requirement: Redis-backed `RateLimiter` implementation via Lua sliding window

The `:infra:redis` module SHALL provide a Redis-backed implementation of `RateLimiter` that uses a single Lua script (atomic on the Redis side) to:

1. `ZREMRANGEBYSCORE key 0 (now_ms - window_ms)` — prune entries older than the window. `window_ms` is always `ttl_ms` (the caller-supplied `ttl` converted to milliseconds): daily callers pass `computeTTLToNextReset(userId)`; hourly callers pass `Duration.ofHours(1)` directly; sub-hourly anti-scrape callers pass the window duration directly (e.g., the `/health` 60-second window passes `Duration.ofSeconds(60)`). There is no hardcoded `window_ms` in the script — it is always an `ARGV` value derived from the caller's `ttl`.
2. `ZCARD key` — count remaining entries.
3. If count `>= capacity`: read the oldest score via `ZRANGE key 0 0 WITHSCORES`, compute `retry_after_seconds = ceil((oldest_ms + window_ms - now_ms) / 1000)` coerced to `>= 1`, return `RateLimited(retry_after_seconds)`. Do NOT add an entry.
4. Else: `ZADD key now_ms <unique-jti>`, `PEXPIRE key ttl_ms`, return `Allowed(capacity - count - 1)`.

`releaseMostRecent` MUST be implemented as `ZPOPMAX key 1` (atomic).

The implementation MUST run the Lua call inside `withContext(Dispatchers.IO)` to keep the Ktor coroutine dispatcher unblocked.

The unique-jti added to the sorted set MUST be a per-call random nonce (e.g., `UUID.randomUUID().toString()`) so that two simultaneous `ZADD` calls with the same `now_ms` from the same user do not collide on the sorted-set member key.

Both `tryAcquire(userId, key, capacity, ttl)` and `tryAcquireByKey(key, capacity, ttl)` MUST invoke the **same** Lua script (same SHA1 hash, same `EVALSHA` cache slot). The only difference between the two methods is at the Kotlin call site: `tryAcquireByKey` does NOT take a `userId` parameter and MUST NOT log one. The `userId` is not consumed by the Lua script — only the `key`, `capacity`, `now_ms`, `window_ms`, and `ttl_ms` are passed as `KEYS[]`/`ARGV[]`. Therefore no script change is required to support the new method; the implementation is a thin Kotlin overload.

#### Scenario: Empty bucket admits up to capacity then rejects
- **WHEN** `capacity = 10` AND `tryAcquire` is called sequentially `12` times within `window_ms`
- **THEN** the first 10 return `Allowed` AND the 11th and 12th return `RateLimited` AND the 11th's `retryAfterSeconds` is approximately `window_seconds - elapsed_since_first`

#### Scenario: Old entries pruned on next call
- **WHEN** the bucket has 10 entries all older than `window_ms` AND `tryAcquire` is called once
- **THEN** the call returns `Allowed(remaining = 9)` AND the bucket size after the call is exactly 1

#### Scenario: Two same-millisecond inserts both land via random JTI
- **WHEN** two `tryAcquire` calls from the same `userId + key` execute via parallel coroutines such that both Lua scripts observe the same `now_ms` value
- **THEN** both `ZADD` operations succeed AND the bucket size after both calls is exactly 2 (the random per-call JTI prevents the sorted-set member key from colliding even when the score is identical)

#### Scenario: Bucket already over-capacity preserved on cap reduction
- **WHEN** the bucket holds 7 entries within the window AND a subsequent `tryAcquire` is issued with `capacity = 5` (e.g., `premium_like_cap_override` was lowered mid-window)
- **THEN** the call returns `RateLimited` (not `Allowed`) AND the bucket size after the call remains 7 (the script does NOT truncate excess entries — they age out naturally as the window rolls forward, and the user becomes admittable again only when the live count drops below the new capacity)

#### Scenario: Hash-tag key format honored end-to-end (CRC16 math equivalent)
- **WHEN** `tryAcquire(userId = U, key = "{scope:rate_like_day}:{user:U}", ...)` is called against the CI standalone `redis:7-alpine` test container
- **THEN** the Lua script executes successfully AND a unit-level assertion verifies `getSlot("scope:rate_like_day")` equals `getSlot("user:U")` via Lettuce's CRC16 helper. NOTE: standalone Redis cannot raise `CROSSSLOT` at runtime — that error is only producible against an actual cluster; the test exercises the CRC16 math invariant that would prevent the runtime error in production, not the runtime rejection itself. Real-cluster `CROSSSLOT` rejection is exercised only via staging/prod traffic.

#### Scenario: ZPOPMAX restores the slot for releaseMostRecent
- **WHEN** the bucket holds three entries at scores `t1 < t2 < t3` AND `releaseMostRecent` is called
- **THEN** the entry with score `t3` is removed AND the bucket size is 2

#### Scenario: tryAcquireByKey delegates to the same Lua script as tryAcquire
- **WHEN** `tryAcquireByKey(key = "{scope:health}:{ip:1.2.3.4}", capacity = 60, ttl = Duration.ofSeconds(60))` is invoked
- **THEN** the Redis `EVALSHA` invocation references the SAME `scriptSha` constant as the `tryAcquire` invocation (verified by reading `RedisRateLimiter.scriptSha` once at construction and asserting both methods route through it)

### Requirement: `computeTTLToNextReset(userId)` shared helper

A pure function `id.nearyou.app.core.domain.ratelimit.computeTTLToNextReset(userId: UUID, now: Instant = Instant.now()): Duration` SHALL exist in `:core:domain`. The function MUST implement exactly the WIB-stagger formula in [`docs/05-Implementation.md`](../../../docs/05-Implementation.md) §1751-1755:

1. `offset_seconds = abs(userId.hashCode()) % 3600` — per-user offset in `[0, 3600)`.
2. Compute `next_midnight_wib`: the next `00:00:00` in `Asia/Jakarta` (WIB, UTC+7) AT or AFTER `now`. If `now` is exactly midnight WIB, `next_midnight_wib = now + 24h`.
3. Effective reset `= next_midnight_wib + Duration.ofSeconds(offset_seconds)`.
4. Return `Duration.between(now, effective_reset)`.

The function MUST NOT depend on `:infra:redis`. The `now` parameter MUST be supplied by tests to verify behavior across midnight WIB.

The returned `Duration` MUST be strictly positive.

#### Scenario: Helper lives in :core:domain
- **WHEN** running `find core/domain/src/commonMain -name 'ComputeTtlToNextReset*' -o -name '*ratelimit*'`
- **THEN** at least one file is found AND its package is `id.nearyou.app.core.domain.ratelimit`

#### Scenario: Same user same offset across calls
- **WHEN** `computeTTLToNextReset(U, now1)` and `computeTTLToNextReset(U, now2)` are called for the same `U` AND `now2` is exactly 1 second after `now1`
- **THEN** the returned `Duration` for `now2` is exactly 1 second SHORTER than for `now1` (the offset is stable per user)

#### Scenario: Different users different offsets (with high probability)
- **WHEN** `computeTTLToNextReset(U1, now)` and `computeTTLToNextReset(U2, now)` are called for two distinct random `UUID` values at the same `now`, sampled across **100,000** independent random pairs
- **THEN** for at least **99,500** of those 100,000 pairs (≥ 99.5%), the returned `Duration` values differ by a non-zero amount (`abs(U1.hashCode().toLong()) % 3600L != abs(U2.hashCode().toLong()) % 3600L`). Threshold derivation: with per-pair collision probability `1/3600` (the offset bucket from `ComputeTtlToNextReset.kt:36`), the expected collision count in 100,000 pairs is `λ ≈ 27.78` (`σ ≈ 5.27`); the "≤ 500 collisions" floor sits ~89σ above the mean — failure probability is effectively zero on any reasonable hashCode distribution.

#### Scenario: Offset bounded to `[0, 3600)` seconds
- **WHEN** `computeTTLToNextReset(U, now)` is called for any 1000 random `U` AND a fixed `now = 2026-01-01T00:00:00Z` (exact UTC midnight = 07:00 WIB)
- **THEN** every returned `Duration` is in the range `[17h, 17h + 1h)` — i.e., the gap between 07:00 WIB now and the next 00:00–01:00 WIB window

#### Scenario: Crossing midnight WIB
- **WHEN** `now = 2026-01-01T16:59:59Z` (= 23:59:59 WIB on Jan 1) AND user U has offset 0 seconds AND `computeTTLToNextReset(U, now)` is called THEN immediately again at `now = 2026-01-01T17:00:01Z` (= 00:00:01 WIB on Jan 2)
- **THEN** the first call returns `Duration.ofSeconds(1)` AND the second call returns approximately `Duration.ofHours(24).minusSeconds(1)` — the window has rolled over

#### Scenario: Pure function, no I/O
- **WHEN** searching the `computeTTLToNextReset` source for any direct file I/O, network, or DB call
- **THEN** none is found — the function uses only `java.time` APIs and the `userId` argument

### Requirement: Hash-tag key format standard for rate-limit keys

Every Redis key constructed for a rate-limit purpose SHALL use the hash-tag format `{scope:<scope_name>}:{user:<user_uuid>}` (or `{scope:<scope_name>}:{<other_axis>:<value>}` for non-per-user limits). Both segments MUST be wrapped in braces `{}`. The first segment names the rate-limit scope; the second names the partition axis.

Existing precedent (V9 `reports`): `{scope:rate_report}:{user:<uuid>}`.

New keys introduced by this change:
- `{scope:rate_like_day}:{user:<uuid>}` — daily 10/day Free like limiter.
- `{scope:rate_like_burst}:{user:<uuid>}` — rolling 500/hour burst (both tiers) like limiter.

The format ensures Upstash Redis Cluster co-locates the segments to the same slot, making Lua scripts and MULTI/EXEC operations safe across both segments.

#### Scenario: New like limiter keys conform
- **WHEN** the like service constructs the daily and burst keys for `userId = U`
- **THEN** the daily key equals exactly `"{scope:rate_like_day}:{user:U}"` AND the burst key equals exactly `"{scope:rate_like_burst}:{user:U}"`

#### Scenario: Hash-tag co-location verified end-to-end
- **WHEN** the like service issues both `tryAcquire` calls within the same request against an Upstash cluster
- **THEN** neither call returns a `CROSSSLOT` error AND both segments map to the same Redis Cluster slot

### Requirement: `RateLimitTtlRule` Detekt rule fences daily-window TTL

The repo SHALL ship a custom Detekt rule `RateLimitTtlRule` under `lint/detekt-rules/src/main/kotlin/id/nearyou/lint/detekt/`. The rule MUST fire on any Kotlin call to `RateLimiter.tryAcquire(...)` where:

1. The `key` argument is a string template whose source contains either of the daily-window markers — substring `_day}` OR matches the regex `^\{scope:rate_[a-z_]+_day\}`.
2. AND the `ttl` argument is NOT a call to `computeTTLToNextReset(...)` (recognized by short-name match — both `id.nearyou.app.core.domain.ratelimit.computeTTLToNextReset` and any unqualified call named `computeTTLToNextReset` count).

The rule MUST NOT fire when the `ttl` argument is `computeTTLToNextReset(<anything>)`. The rule MUST NOT fire on hourly/burst keys (those whose key string does not contain `_day}`).

The rule MUST be registered in `NearYouRuleSetProvider` so `./gradlew detekt` picks it up.

Allowlist (rule does NOT fire even on a violating call site):
1. **Test fixtures**: file path contains `/src/test/`.
2. **Annotation bypass**: enclosing declaration (or any ancestor declaration) is annotated `@AllowDailyTtlOverride(reason: String)`. Short-name check only. **The rule MUST fire if the annotation has no argument or its argument resolves to an empty string** — silent bypass via `@AllowDailyTtlOverride()` or `@AllowDailyTtlOverride("")` is forbidden. The annotation is declared with a non-nullable `String` parameter so that empty-string is the only possible silent-bypass shape, and that shape is rule-enforced.

Both gates MUST support the detekt-test `lint(String)` synthetic-file harness via package-FQN fallback (mirror `BlockExclusionJoinRule.isAllowedPath`).

#### Scenario: Daily key with hardcoded ttl fires
- **WHEN** a non-test Kotlin file contains `tryAcquire(userId, "{scope:rate_like_day}:{user:$id}", capacity = 10, ttl = Duration.ofDays(1))`
- **THEN** the rule reports a code smell on that call

#### Scenario: Daily key with computeTTLToNextReset passes
- **WHEN** a non-test Kotlin file contains `tryAcquire(userId, "{scope:rate_like_day}:{user:$id}", capacity = 10, ttl = computeTTLToNextReset(userId))`
- **THEN** the rule does NOT fire

#### Scenario: Burst key with hardcoded ttl passes
- **WHEN** a non-test Kotlin file contains `tryAcquire(userId, "{scope:rate_like_burst}:{user:$id}", capacity = 500, ttl = Duration.ofHours(1))`
- **THEN** the rule does NOT fire (burst key has no `_day}` marker)

#### Scenario: Test file passes
- **WHEN** a file under `.../src/test/kotlin/.../*.kt` contains a daily-key call with hardcoded `Duration.ofDays(1)`
- **THEN** the rule does NOT fire

#### Scenario: @AllowDailyTtlOverride annotation with non-empty reason passes
- **WHEN** a function annotated `@AllowDailyTtlOverride("benchmark fixture")` contains a daily-key call with hardcoded `ttl`
- **THEN** the rule does NOT fire on calls inside that function

#### Scenario: @AllowDailyTtlOverride with empty-string reason still fires
- **WHEN** a function annotated `@AllowDailyTtlOverride("")` contains a daily-key call with hardcoded `ttl`
- **THEN** the rule reports a code smell on that call (empty-reason silent bypass is rejected)

#### Scenario: Synthetic-file-harness via package-FQN fallback
- **WHEN** the detekt-test `lint(String)` synthetic harness loads a fixture that has no `virtualFilePath` BUT whose package FQN is `id.nearyou.test.fixtures.<anything>` (recognized as a test fixture)
- **THEN** the rule does NOT fire on calls in that fixture (mirroring the `BlockExclusionJoinRule.isAllowedPath` package-FQN-fallback precedent)

#### Scenario: Rule registered via NearYouRuleSetProvider
- **WHEN** reading `NearYouRuleSetProvider.instance(config)`
- **THEN** the returned `RuleSet` includes an instance of `RateLimitTtlRule`

### Requirement: `RedisHashTagRule` Detekt rule fences rate-limit key shape

The repo SHALL ship a custom Detekt rule `RedisHashTagRule` under `lint/detekt-rules/src/main/kotlin/id/nearyou/lint/detekt/`. The rule MUST fire on any Kotlin string literal (`KtStringTemplateExpression`) whose source text matches the rate-limit-key pattern (case-sensitive):

- Starts with `rate:` (legacy non-hash-tagged form), OR
- Contains the substring `{scope:` AND the literal does NOT contain a balanced `}:{` separator between scope and partition segments.

The rule MUST NOT fire on string literals that fully conform: starts with `{scope:<scope_name>}:{<axis>:<value>}` (both segments wrapped in `{}` and separated by a single `:`).

The rule MUST be registered in `NearYouRuleSetProvider`.

Allowlist:
1. **Test fixtures**: file path contains `/src/test/`.
2. **Annotation bypass**: enclosing declaration (or any ancestor declaration) is annotated `@AllowRawRedisKey(reason: String)`. Short-name check only. **The rule MUST fire if the annotation has no argument or its argument resolves to an empty string** — silent bypass via `@AllowRawRedisKey()` or `@AllowRawRedisKey("")` is forbidden. Same enforcement shape as `@AllowDailyTtlOverride` above.

Both gates MUST support the detekt-test synthetic-file harness via package-FQN fallback.

#### Scenario: Legacy `rate:user:<id>` literal fires
- **WHEN** a non-test Kotlin file contains the string literal `"rate:user:$userId"`
- **THEN** the rule reports a code smell on that literal

#### Scenario: Malformed `{scope:` literal fires
- **WHEN** a non-test Kotlin file contains the string literal `"{scope:rate_like_day}:user:$id"` (second segment not in braces)
- **THEN** the rule fires

#### Scenario: Conforming literal passes
- **WHEN** a non-test Kotlin file contains the string literal `"{scope:rate_like_day}:{user:$id}"`
- **THEN** the rule does NOT fire

#### Scenario: Non-rate-limit string passes
- **WHEN** a non-test Kotlin file contains the string literal `"SELECT id FROM users WHERE id = ?"`
- **THEN** the rule does NOT fire

#### Scenario: Test file passes
- **WHEN** a file under `.../src/test/kotlin/.../*.kt` contains `"rate:legacy"`
- **THEN** the rule does NOT fire

#### Scenario: @AllowRawRedisKey annotation with non-empty reason passes
- **WHEN** a function annotated `@AllowRawRedisKey("backfill cleanup")` contains `"rate:legacy:$x"`
- **THEN** the rule does NOT fire on literals inside that function

#### Scenario: @AllowRawRedisKey with empty-string reason still fires
- **WHEN** a function annotated `@AllowRawRedisKey("")` contains `"rate:legacy:$x"`
- **THEN** the rule reports a code smell on that literal (empty-reason silent bypass is rejected)

#### Scenario: Synthetic-file-harness via package-FQN fallback
- **WHEN** the detekt-test `lint(String)` synthetic harness loads a fixture whose package FQN starts with `id.nearyou.test.fixtures.`
- **THEN** the rule does NOT fire on literals in that fixture (package-FQN-fallback precedent from `BlockExclusionJoinRule.isAllowedPath`)

#### Scenario: Rule registered via NearYouRuleSetProvider
- **WHEN** reading `NearYouRuleSetProvider.instance(config)`
- **THEN** the returned `RuleSet` includes an instance of `RedisHashTagRule`

### Requirement: Test coverage — Redis-backed RateLimiter integration test

A test class `RedisRateLimiterIntegrationTest` (tagged `database`, runs against the CI `redis:7-alpine` service container — note: standalone Redis, NOT cluster mode) SHALL exist in `:infra:redis/src/test/kotlin/`. The test MUST cover, at minimum:

1. Empty-bucket admit-then-reject across the capacity boundary (capacity 10: 10 admit, 11th rate-limited).
2. `Retry-After` math reflects oldest counted submission within ±5 seconds of expected (CI runner clock + Redis `TIME` skew can exceed 2s on shared infrastructure; the assertion tolerance is widened to absorb that without giving up the property check).
3. `releaseMostRecent` returns the slot (admit again succeeds after release).
4. `releaseMostRecent` on an empty bucket is a no-op (no exception, no error propagated, bucket size remains 0).
5. Concurrent tryAcquire calls — 10 actually-parallel coroutines via `runBlocking { (1..10).map { async(Dispatchers.IO) { ... } }.awaitAll() }`, capacity 10 against fresh bucket — exactly 10 admit and any 11th observes RateLimited.
6. Old entries pruned: bucket pre-loaded with 10 expired scores, fresh `tryAcquire` returns `Allowed(remaining = 9)`.
7. Hash-tag CRC16 equivalence: assert `Lettuce.crc16("scope:rate_test_day") == Lettuce.crc16("user:<uuid>")` for the test key shape `{scope:rate_test_day}:{user:<uuid>}` — verifies the math invariant that prevents `CROSSSLOT` in production cluster mode.
8. Two same-`now_ms` inserts both land — issue two parallel `tryAcquire` calls and assert bucket size is 2 even when both Lua executions observe the same millisecond timestamp.
9. Bucket already over-capacity preserved on cap reduction — pre-load 7 entries, call `tryAcquire(capacity = 5)`, assert RateLimited AND bucket size remains 7 (no silent truncation).
10. **V9 contract subsumption** — the test MUST replicate the `ReportRateLimiter`-flavored assertions from V9's `ReportRateLimiterTest` against the Redis-backed implementation: 10-succeed-window, 11th-rate-limited, Retry-After-reflects-oldest, 409-style release on empty-bucket. This explicitly lives in `RedisRateLimiterIntegrationTest` (not in V9's existing test class) because Lua's `redis.call('TIME')` does not honor V9's injected `clock: () -> Instant` seam — the V9 test class cannot be repointed at Redis without a clock-injection refactor that this change explicitly does not undertake.

A test class `ComputeTtlToNextResetTest` (`:core:domain/src/commonTest/`) SHALL exist and cover, at minimum:

1. Same user same offset across calls.
2. Different users different offsets at high probability.
3. Offset bounded to `[0, 3600)` seconds at a fixed `now`.
4. Crossing midnight WIB: TTL just before midnight + offset is small; TTL just after is approximately 24h - elapsed.
5. Pure function — repeated calls with same `(userId, now)` return byte-identical `Duration`.

A test class `RateLimitTtlRuleTest` and `RedisHashTagRuleTest` (`:lint:detekt-rules/src/test/`) SHALL exist with at least one positive-fail and one negative-pass fixture per Detekt rule, mirroring the `CoordinateJitterLintTest` precedent (17 scenarios) — minimum 6 scenarios per rule including allowlist-by-path, annotation bypass, conforming-passes, malformed-fires.

#### Scenario: All test classes discoverable
- **WHEN** running `./gradlew :infra:redis:test :core:domain:test :lint:detekt-rules:test`
- **THEN** `RedisRateLimiterIntegrationTest`, `ComputeTtlToNextResetTest`, `RateLimitTtlRuleTest`, and `RedisHashTagRuleTest` are all discovered AND every numbered scenario above corresponds to at least one `@Test` method

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

### Requirement: `OtelForbiddenAttributeRule` fences raw IP literal in `{ip:<value>}` rate-limit-key segments

The `OtelForbiddenAttributeRule` Detekt rule (shipped under the `observability-otel-foundation` capability — see [`observability-otel-foundation/spec.md`](../observability-otel-foundation/spec.md) § "`OtelForbiddenAttributeRule` fences forbidden span-attribute writes") SHALL ALSO fire on any Kotlin string literal containing an `{ip:<value>}` segment where `<value>` is none of: (a) exactly 16 lowercase hexadecimal characters (the canonical `IpHasher.hash` output shape), (b) a Kotlin template-string placeholder — simple-name `$<identifier>` OR block-form `${<expression>}` — whose runtime value the implementation cannot statically verify.

**The check is NOT scoped to `tryAcquireByKey(...)` call-context.** Rationale: the canonical production call site at [`backend/ktor/.../health/HealthRoutes.kt:166-170`](../../../../../backend/ktor/src/main/kotlin/id/nearyou/app/health/HealthRoutes.kt) hoists the literal `val key = "{scope:health}:{ip:$hashedIp}"` BEFORE passing it to `tryAcquireByKey`. The string-template-expression's PSI parent is `KtProperty`, NOT `KtCallExpression(tryAcquireByKey)`. A PSI parent-walk that requires `tryAcquireByKey` as the immediate enclosing call would produce ZERO findings against the real production codebase, defeating the rule's purpose. Firing on any `{ip:<value>}` literal anywhere is the correct enforcement boundary; the path-based allowlist (next requirement) handles test fixtures that legitimately need raw inputs.

**Functional contract** (the authoritative spec; implementation phase selects an equivalent regex / PSI logic):
1. **PASS** — `{ip:[0-9a-f]{16}}` (exactly 16 lowercase hex chars between the braces).
2. **PASS** — `{ip:$<identifier>}` (Kotlin simple-name template — the canonical production shape, e.g., `$hashedIp`).
3. **PASS** — `{ip:${<expression>}}` (Kotlin block-form template — e.g., `${IpHasher.hash(clientIp)}`).
4. **FIRE** — anything else: raw IPv4 dotted-quad (`{ip:1.2.3.4}`), IPv6 colon-delimited (`{ip:[2001:db8::1]}`), 15-hex / 17-hex / uppercase-hex / mixed-case / shapes containing colons or dots inside the value.

**Recommended regex** (illustrative; final shape selected at implementation time): `\{ip:(?![0-9a-f]{16}\})(?!\$)[^}]*\}` — fires when the value at the position after `{ip:` is NEITHER exactly 16 lowercase hex chars followed by `}` (negative lookahead 1, handling clause 1) NOR begins with `$` (negative lookahead 2, handling clauses 2 AND 3 — `$<identifier>` and `${<expression>}` both start with `$`).

#### Scenario: Raw IPv4 literal anywhere fires
- **WHEN** a non-allowlisted Kotlin file contains the literal `"{scope:health}:{ip:1.2.3.4}"` — bound to a `val`, passed as a method arg, embedded in a log message, anywhere
- **THEN** the rule fires on that literal

#### Scenario: Raw IPv6 literal anywhere fires
- **WHEN** a non-allowlisted Kotlin file contains the literal `"{scope:health}:{ip:[2001:db8::1]}"`
- **THEN** the rule fires

#### Scenario: Canonical 16-hex literal passes
- **WHEN** a non-allowlisted Kotlin file contains the literal `"{scope:health}:{ip:abcdef0123456789}"`
- **THEN** the rule does NOT fire on that literal

#### Scenario: Kotlin simple-name interpolation passes (canonical production shape)
- **WHEN** a non-allowlisted Kotlin file contains the literal `"{scope:health}:{ip:${'$'}hashedIp}"` where `hashedIp` is a Kotlin identifier — the canonical production shape at `HealthRoutes.kt:167`
- **THEN** the rule does NOT fire (the value between `{ip:` and `}` begins with `$` — passes per the functional contract's clause (2); the implementation cannot statically verify the identifier's runtime value, so trust the caller)

#### Scenario: Kotlin block-form interpolation passes
- **WHEN** a non-allowlisted Kotlin file contains the literal `"{scope:health}:{ip:${'$'}{IpHasher.hash(clientIp)}}"` (block-form template with the helper consumption inline)
- **THEN** the rule does NOT fire (the value between `{ip:` and `}` begins with `${'$'}{` — passes per the functional contract's clause (3))

#### Scenario: 15-hex value fires (off-canonical length)
- **WHEN** a non-allowlisted Kotlin file contains the literal `"{scope:health}:{ip:abcdef012345678}"` (15 hex chars — one short of canonical)
- **THEN** the rule fires (the canonical shape is EXACTLY 16 hex chars)

#### Scenario: 17-hex value fires (off-canonical length)
- **WHEN** a non-allowlisted Kotlin file contains the literal `"{scope:health}:{ip:abcdef01234567890}"` (17 hex chars)
- **THEN** the rule fires

#### Scenario: Uppercase-hex 16-char value fires (off-canonical case)
- **WHEN** a non-allowlisted Kotlin file contains the literal `"{scope:health}:{ip:ABCDEF0123456789}"`
- **THEN** the rule fires (canonical `IpHasher.hash` output is lowercase; uppercase indicates a different source)

#### Scenario: Non-IP-axis key passes (no-op for unrelated axes)
- **WHEN** a non-allowlisted Kotlin file contains the literal `"{scope:rate_like_day}:{user:${'$'}userId}"` — no `{ip:...}` segment present
- **THEN** the rule's IP-axis check does NOT fire on that literal

#### Scenario: Test-source allowlist applies
- **WHEN** a file under any `/src/test/` path (e.g., `infra/redis/src/test/kotlin/.../RedisRateLimiterTelemetryTest.kt`, `core/domain/src/test/kotlin/.../RateLimiterTest.kt`, `backend/ktor/src/test/kotlin/.../HealthRoutesScenariosTest.kt`) contains a fixture string `"{scope:health}:{ip:1.2.3.4}"` to verify the limiter's behavior on raw inputs OR a regex-string asserting the canonical shape `"""^\{scope:health\}:\{ip:[0-9a-f]{16}\}${'$'}"""`
- **THEN** the rule does NOT fire on those literals (the `/src/test/` path allowlist suppresses; tests of the limiter and the canonical-shape assertion legitimately need raw / regex-literal inputs)

### Requirement: Detekt test coverage for the IP-axis lint mode

The `OtelForbiddenAttributeLintTest` class shipped under the `observability-otel-foundation` capability (see [`observability-otel-foundation/spec.md`](../observability-otel-foundation/spec.md) § "Detekt test coverage for `OtelForbiddenAttributeRule`") SHALL include, in addition to the OTel attribute scenarios, the following IP-axis-mode test cases:

1. **Raw IPv4 anywhere fires**: fixture `val k = "{scope:health}:{ip:1.2.3.4}"` in a non-`/src/test/` path → rule fires.
2. **Raw IPv6 anywhere fires**: fixture with `{ip:[2001:db8::1]}` literal in non-test path → rule fires.
3. **Canonical 16-hex passes**: fixture with `{ip:abcdef0123456789}` literal → rule does NOT fire.
4. **Simple-name interpolation passes (canonical prod shape)**: fixture `val k = "{scope:health}:{ip:${'$'}hashedIp}"` → rule does NOT fire.
5. **Block-form interpolation passes**: fixture with `{ip:${'$'}{IpHasher.hash(clientIp)}}` → rule does NOT fire.
6. **15-hex fires**: fixture with `{ip:abcdef012345678}` → rule fires.
7. **17-hex fires**: fixture with `{ip:abcdef01234567890}` → rule fires.
8. **Uppercase-hex fires**: fixture with `{ip:ABCDEF0123456789}` → rule fires.
9. **Non-IP-axis (user-axis) passes**: fixture with `{scope:rate_like_day}:{user:${'$'}userId}` (no `{ip:...}` segment) → no fire on IP-axis check.
10. **Test-path allowlist for `/src/test/`**: fixture under simulated `/infra/redis/src/test/.../IpFixturesTest.kt` with raw `{ip:1.2.3.4}` → rule does NOT fire.
11. **Composition with `RedisHashTagRule` two-way**: a fixture `"rate:user:${'$'}userId"` (legacy non-hash-tagged, NO `{ip:...}` segment) → fires `RedisHashTagRule` but NOT IP-axis check; a fixture `"rate:health:{ip:1.2.3.4}"` (legacy prefix AND raw IP) → fires BOTH rules independently with no cross-suppression.
12. **Mode B fires on val-hoisted literal (not call-context-restricted)**: fixture `val k = "{scope:health}:{ip:1.2.3.4}"` (literal bound to a property, never passed anywhere) → rule fires (rule is NOT scoped to `tryAcquireByKey` call-context; firing on any `{ip:...}` literal is the canonical behavior per the functional contract above).

#### Scenario: Test class exists and IP-axis cases pass
- **WHEN** running `./gradlew :lint:detekt-rules:test`
- **THEN** `OtelForbiddenAttributeLintTest` is discovered AND every IP-axis-mode test case above is covered AND all cases pass

