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
