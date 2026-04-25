## ADDED Requirements

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

An interface `id.nearyou.app.core.domain.ratelimit.RateLimiter` SHALL exist in `:core:domain`. The interface MUST define exactly two methods:

```kotlin
fun tryAcquire(userId: UUID, key: String, capacity: Int, ttl: Duration): Outcome
fun releaseMostRecent(userId: UUID, key: String)
```

`Outcome` MUST be a sealed interface with two data classes:
- `Allowed(remaining: Int)` — `remaining` is the number of slots left in the window AFTER consumption.
- `RateLimited(retryAfterSeconds: Long)` — `retryAfterSeconds` is the seconds until the oldest counted event ages out, suitable for a `Retry-After` HTTP header. MUST be coerced to at least 1.

The `key` parameter is the full hash-tag-formatted Redis key constructed by the caller (the limiter does not synthesize it). The `ttl` parameter is the Redis key TTL — daily callers pass `computeTTLToNextReset(userId)`; hourly callers pass `Duration.ofHours(1)` directly.

`tryAcquire` MUST be atomic across concurrent calls with the same `userId + key`: two simultaneous requests at slot `capacity` MUST NOT both observe `Allowed`.

`releaseMostRecent` MUST pop the most-recently-added entry for `userId + key`. No-op if the bucket is empty (defensive — callers only invoke it after a successful `tryAcquire`).

#### Scenario: Interface lives in :core:domain
- **WHEN** running `find core/domain/src/commonMain -name 'RateLimiter.kt'`
- **THEN** exactly one file is found AND its package is `id.nearyou.app.core.domain.ratelimit`

#### Scenario: Outcome shape matches V9 ReportRateLimiter precedent
- **WHEN** comparing `Outcome.Allowed`, `Outcome.RateLimited` field names
- **THEN** they match `id.nearyou.app.moderation.ReportRateLimiter.Outcome.Allowed.remaining` and `Outcome.RateLimited.retryAfterSeconds` from V9 byte-for-byte

#### Scenario: Concurrent tryAcquire at capacity boundary
- **WHEN** ten concurrent threads call `tryAcquire(userId, key, capacity = 10, ttl = ...)` against an empty bucket
- **THEN** exactly ten observe `Allowed` AND any 11th concurrent call observes `RateLimited`

#### Scenario: releaseMostRecent reverses the most recent slot
- **WHEN** a caller observes `Allowed` then calls `releaseMostRecent` immediately AND then `tryAcquire` is called again with the same `(userId, key)`
- **THEN** the second `tryAcquire` succeeds (the first slot was returned)

### Requirement: Redis-backed `RateLimiter` implementation via Lua sliding window

The `:infra:redis` module SHALL provide a Redis-backed implementation of `RateLimiter` that uses a single Lua script (atomic on the Redis side) to:

1. `ZREMRANGEBYSCORE key 0 (now_ms - window_ms)` — prune entries older than the window. `window_ms` is derived from the supplied `ttl` (daily) or fixed at 3600000 (hourly).
2. `ZCARD key` — count remaining entries.
3. If count `>= capacity`: read the oldest score via `ZRANGE key 0 0 WITHSCORES`, compute `retry_after_seconds = ceil((oldest_ms + window_ms - now_ms) / 1000)` coerced to `>= 1`, return `RateLimited(retry_after_seconds)`. Do NOT add an entry.
4. Else: `ZADD key now_ms <unique-jti>`, `PEXPIRE key ttl_ms`, return `Allowed(capacity - count - 1)`.

`releaseMostRecent` MUST be implemented as `ZPOPMAX key 1` (atomic).

The implementation MUST run the Lua call inside `withContext(Dispatchers.IO)` to keep the Ktor coroutine dispatcher unblocked.

The unique-jti added to the sorted set MUST be a per-call random nonce (e.g., `UUID.randomUUID().toString()`) so that two simultaneous `ZADD` calls with the same `now_ms` from the same user do not collide on the sorted-set member key.

#### Scenario: Empty bucket admits up to capacity then rejects
- **WHEN** `capacity = 10` AND `tryAcquire` is called sequentially `12` times within `window_ms`
- **THEN** the first 10 return `Allowed` AND the 11th and 12th return `RateLimited` AND the 11th's `retryAfterSeconds` is approximately `window_seconds - elapsed_since_first`

#### Scenario: Old entries pruned on next call
- **WHEN** the bucket has 10 entries all older than `window_ms` AND `tryAcquire` is called once
- **THEN** the call returns `Allowed(remaining = 9)` AND the bucket size after the call is exactly 1

#### Scenario: Hash-tag key format honored end-to-end
- **WHEN** `tryAcquire(userId = U, key = "{scope:rate_like_day}:{user:U}", ...)` is called against an Upstash cluster instance
- **THEN** the Lua script executes successfully (no `CROSSSLOT` error) AND both segments resolve to the same Redis Cluster slot

#### Scenario: ZPOPMAX restores the slot for releaseMostRecent
- **WHEN** the bucket holds three entries at scores `t1 < t2 < t3` AND `releaseMostRecent` is called
- **THEN** the entry with score `t3` is removed AND the bucket size is 2

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
- **WHEN** `computeTTLToNextReset(U1, now)` and `computeTTLToNextReset(U2, now)` are called for two distinct random `UUID` values at the same `now`
- **THEN** for at least 999 of 1000 random pairs, the returned `Duration` values differ by a non-zero amount (`abs(U1.hashCode() % 3600) != abs(U2.hashCode() % 3600)`)

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
2. **Annotation bypass**: enclosing declaration (or any ancestor declaration) is annotated `@AllowDailyTtlOverride("<reason>")`. Short-name check only.

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

#### Scenario: @AllowDailyTtlOverride annotation passes
- **WHEN** a function annotated `@AllowDailyTtlOverride("benchmark fixture")` contains a daily-key call with hardcoded `ttl`
- **THEN** the rule does NOT fire on calls inside that function

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
2. **Annotation bypass**: enclosing declaration (or any ancestor declaration) is annotated `@AllowRawRedisKey("<reason>")`. Short-name check only.

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

#### Scenario: @AllowRawRedisKey annotation passes
- **WHEN** a function annotated `@AllowRawRedisKey("backfill cleanup")` contains `"rate:legacy:$x"`
- **THEN** the rule does NOT fire on literals inside that function

#### Scenario: Rule registered via NearYouRuleSetProvider
- **WHEN** reading `NearYouRuleSetProvider.instance(config)`
- **THEN** the returned `RuleSet` includes an instance of `RedisHashTagRule`

### Requirement: Test coverage — Redis-backed RateLimiter integration test

A test class `RedisRateLimiterIntegrationTest` (tagged `database`, runs against the CI `redis:7-alpine` service container) SHALL exist in `:infra:redis/src/test/kotlin/`. The test MUST cover, at minimum:

1. Empty-bucket admit-then-reject across the capacity boundary (capacity 10: 10 admit, 11th rate-limited).
2. `Retry-After` math reflects oldest counted submission within ±2 seconds of expected.
3. `releaseMostRecent` returns the slot (admit again succeeds after release).
4. Concurrent tryAcquire calls — 10 threads, capacity 10 against fresh bucket — exactly 10 admit and any 11th observes RateLimited.
5. Old entries pruned: bucket pre-loaded with 10 expired scores, fresh `tryAcquire` returns `Allowed(remaining = 9)`.
6. Hash-tag key format `{scope:test_scope}:{user:<uuid>}` accepted on a clustered Redis (or asserted equivalent: `getSlot(<segment1>) == getSlot(<segment2>)` via the Lettuce CRC16 helper for non-cluster local Redis).

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
