## MODIFIED Requirements

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

The convention for IP-keyed callers (the first such call site is the `health-check` capability) is the hash-tag key shape `{scope:<role>}:{ip:<addr>}` constructed by the caller from `clientIp` (per the project critical invariant: `clientIp` is read from the `CF-Connecting-IP` request-context value, never `X-Forwarded-For`).

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
- **WHEN** sixty concurrent calls (real parallel coroutines, not a sequential loop) execute against an empty bucket via `tryAcquireByKey(key = "{scope:health}:{ip:1.2.3.4}", capacity = 60, ttl = Duration.ofSeconds(60))`
- **THEN** exactly sixty observe `Allowed` AND any 61st concurrent call observes `RateLimited`

#### Scenario: tryAcquireByKey shares Lua script with tryAcquire
- **WHEN** comparing the Redis-side Lua script invoked by `tryAcquireByKey` vs `tryAcquire` in the `:infra:redis` `RedisRateLimiter` implementation
- **THEN** the SHA1 hash of the script bytes is identical (the implementations MUST converge on the same `EVALSHA` cache slot — divergence is a spec violation)

#### Scenario: tryAcquireByKey omits userId from telemetry
- **WHEN** `tryAcquireByKey(key = "{scope:health}:{ip:1.2.3.4}", capacity = 60, ttl = Duration.ofSeconds(60))` is invoked AND a structured log is emitted
- **THEN** the log entry includes `key=<key>` AND does NOT include a `user_id` field (and MUST NOT log a sentinel UUID like `00000000-0000-0000-0000-000000000000` — sentinel-UUID values are a code smell and forbidden)

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
