## Context

`post-likes` (V7) shipped without the canonical Free 10/day + 500/hour-burst like cap from [`docs/01-Business.md:55`](../../../docs/01-Business.md), [`docs/02-Product.md:223`](../../../docs/02-Product.md), and [`docs/05-Implementation.md:1733`](../../../docs/05-Implementation.md) Layer 2. The infrastructure that the cap depends on — Phase 1 item 24 in [`docs/08-Roadmap-Risk.md`](../../../docs/08-Roadmap-Risk.md), which calls for "4-layer rate limiting + WIB stagger in Redis TTL from Day 1 + `computeTTLToNextReset(user_id)` shared function + CI lint + hash tag key format standard" — also hasn't shipped yet.

V9 reports (`reports/spec.md` § Rate limit 10 submissions per hour per user) hit the same gap: it shipped a rate limiter but with no Redis client on the JVM classpath, so [`backend/ktor/src/main/kotlin/id/nearyou/app/moderation/ReportRateLimiter.kt:13-19`](../../../backend/ktor/src/main/kotlin/id/nearyou/app/moderation/ReportRateLimiter.kt) carries an explicit "deferred to a separate change" comment and runs entirely in-process via `ConcurrentHashMap`. The hash-tag key shape (`{scope:rate_report}:{user:<uuid>}`) and the 409-release-most-recent contract were both designed in V9 specifically so that the eventual port to Redis would be a behind-the-interface swap. This change executes that port.

The first user-facing consumer of the new infra is the like cap. Reply 20/day, post 10/day, search 60/hour, follow 50/hour, block 30/hour, chat 50/day, and username availability probe 3/day all reuse the same primitives but ship as separate changes.

## Goals / Non-Goals

**Goals:**

- Ship a Redis-backed `RateLimiter` interface that supports both **daily-with-WIB-stagger** and **rolling-hour** windows, returns `Allowed(remaining)` / `RateLimited(retryAfterSeconds)`, and exposes a `releaseMostRecent(userId)` escape hatch (V9 precedent).
- Ship `computeTTLToNextReset(userId): Duration` as the single canonical WIB-stagger helper, in `:core:domain` (no Redis import), implementing the formula in [`docs/05-Implementation.md:1751-1755`](../../../docs/05-Implementation.md): `offset_seconds = hash(user_id) % 3600`.
- Apply daily 10/day Free + rolling 500/hour burst (both tiers) to `POST /api/v1/posts/{post_id}/like`, with 429 + `Retry-After` on limit hit, idempotent re-like releasing the slot, and `premium_like_cap_override` Remote Config flag honored server-side.
- Add two Detekt rules (`RateLimitTtlRule`, `RedisHashTagRule`) so the conventions stick across future call sites (the four already-planned changes).
- Port the V9 `ReportRateLimiter` from in-process to the new Redis-backed interface with **zero client-visible behavior change** — the V9 test suite is the regression gate.

**Non-Goals:**

- Reply / post / search / follow / block / chat / username-probe rate limits — separate changes consume this infra; not in scope here.
- Layer 1 guest pre-issuance rate limits (attestation-gated) — separate Phase 1 item, separate change, separate keys.
- Layer 4 per-area anti-spam limit (50 posts in 1 km / 1 hour) — separate change tied to post creation.
- Mobile-side UX copy for the 429 response — out of scope; mobile already handles 429 generically (timeline rate-limit screen). A dedicated like-button copy update can ship later.
- New DB migrations — none required. Rate-limit state lives entirely in Redis.

## Decisions

### Decision 1 — Redis client: Lettuce (sync API), wrapped via `:infra:redis`

**Choice.** Add Lettuce 6.x to `gradle/libs.versions.toml`; create a new module `:infra:redis` that exposes a Koin-injectable `RedisClient` configured from the existing `secretKey(env, "redis-url")` resolution path. Use the **synchronous** `RedisCommands` API (not async/reactive) for now — Ktor request handlers run in coroutine scope; a single `withContext(Dispatchers.IO)` boundary at the limiter implementation suffices and keeps the interface identical to the in-process V9 limiter.

**Why.** Lettuce is the mainstream Netty-based JVM Redis client (vs Jedis), supports cluster + Upstash flat mode, and is the standard pick in modern Ktor stacks. Sync API keeps the call shape identical to V9 — the V9 test suite is the regression gate, so minimizing diff matters. Async can be revisited if a benchmark shows it matters.

**Alternatives considered.**

- **Jedis**: thread-pool-per-connection legacy model; community shifted to Lettuce. Rejected.
- **Async/reactive Lettuce API**: cleaner with coroutines but the V9 limiter is sync, the report tests are sync, and we're explicitly trying to keep the port behind-the-interface. Defer.
- **Roll our own RESP client**: zero upside.

### Decision 2 — `RateLimiter` interface shape

**Choice.** Mirror the V9 `ReportRateLimiter.Outcome` sealed interface — `Allowed(remaining: Int)` + `RateLimited(retryAfterSeconds: Long)` — and keep `tryAcquire(userId, key, capacity, ttl)` + `releaseMostRecent(userId, key)` as the only methods. The `key` parameter is the full hash-tag-formatted Redis key built by the caller (so the limiter doesn't need to know about scope names). Daily callers pass `ttl = computeTTLToNextReset(userId)`; hourly callers pass `ttl = Duration.ofHours(1)`.

**Why.** `Outcome` shape is already battle-tested in V9 + has integration tests asserting `Allowed.remaining` and `RateLimited.retryAfterSeconds`. Keeping the shape lets the V9 port be a one-line swap of the implementation. The `releaseMostRecent` method is the no-op-idempotent escape hatch for both reports (409 duplicate) and likes (re-like that returns 204 with no row change).

**Alternatives considered.**

- **Per-window methods (`acquireDaily`, `acquireHourly`)**: adds API surface; the TTL parameter is the only difference. Rejected.
- **Token bucket vs sliding window**: V9 uses sliding window via Redis sorted set (`ZADD` timestamp + `ZREMRANGEBYSCORE` to prune + `ZCARD` to count); we keep that. Token bucket would conflict with the V9 contract on `Retry-After` math.

### Decision 3 — Redis sliding-window implementation: ZADD + ZREMRANGEBYSCORE + ZCARD in a Lua script

**Choice.** Implement `tryAcquire` as a single Lua script (atomic on the Redis side) that:

1. `ZREMRANGEBYSCORE key 0 (now - window_seconds * 1000)` — prune entries older than the window.
2. `ZCARD key` — count remaining entries.
3. If count `>= capacity`: read the oldest entry via `ZRANGE key 0 0 WITHSCORES`, compute `retry_after = (oldest + window_seconds * 1000 - now) / 1000`, return `RateLimited(retry_after)`.
4. Else: `ZADD key now <unique-jti>`, `EXPIRE key ttl_seconds`, return `Allowed(capacity - count - 1)`.

`releaseMostRecent` is `ZPOPMAX key 1`.

**Why.** Single round-trip to Redis, atomic across concurrent requests from the same user, exact match to V9's in-process semantics. Hash-tag-formatted key (`{scope:rate_like_day}:{user:<uuid>}`) ensures both segments map to the same Redis Cluster slot — required for Lua-script + multi-key operations on Upstash cluster mode (per [`docs/05-Implementation.md:1686`](../../../docs/05-Implementation.md)).

**Alternatives considered.**

- **`INCR + EXPIRE` (fixed-window counter)**: simpler but loses the "oldest entry timestamp" needed for accurate `Retry-After`. The V9 spec scenario "`Retry-After` reflects oldest counted submission" forbids this. Rejected.
- **Round-trip without Lua**: race window between the prune/count and the conditional ZADD lets a concurrent request slip past the cap. Rejected.

### Decision 4 — `computeTTLToNextReset` lives in `:core:domain`, not in `:infra:redis`

**Choice.** Add `id.nearyou.app.core.domain.ratelimit.computeTTLToNextReset(userId: UUID): Duration` in the existing `:core:domain` module. The function takes a `now` parameter for testability (default `Instant.now()`). The `:infra:redis` rate-limiter implementation accepts the resulting `Duration` as a parameter — it does not import the helper itself.

**Why.** The WIB-stagger formula is pure math and deserves to be testable without a Redis dependency. It's also called from the like service directly (which depends on `:core:domain`, not on `:infra:redis`) when the service constructs the `ttl` argument. The lint rule (Decision 6) needs to verify the helper is called at every daily-limit site, which is easier when the helper has a stable, importable FQN.

**Alternatives considered.**

- **Stick it inside `:infra:redis`**: forces every caller to import `:infra:redis`, which would be wrong — the like service should depend on the `RateLimiter` interface in `:core:domain`, not on the Redis impl module. Rejected.
- **Inline the math at every call site**: violates Phase 1 item 24's "centralize in shared function" requirement and defeats the lint rule. Rejected.

### Decision 5 — Free vs Premium gating reads `users.subscription_status`

**Choice.** The like service reads `users.subscription_status` (existing column from V3, three-state enum `free`/`premium_active`/`premium_billing_retry`) to decide whether to apply the daily cap. Both `premium_active` and `premium_billing_retry` (the 7-day grace state per [`docs/05-Implementation.md:1740`](../../../docs/05-Implementation.md)) skip the daily limiter entirely. The 500/hour burst limiter applies to **both** tiers per [`docs/02-Product.md:223`](../../../docs/02-Product.md).

**Why.** No new column. `subscription_status` is already kept up to date by the RevenueCat webhook handler (Phase 4 plan in [`docs/08-Roadmap-Risk.md`](../../../docs/08-Roadmap-Risk.md)). Reading it via the existing `users` query path keeps the like-service shape unchanged. Honoring `premium_billing_retry` matches the documented "Premium access REMAINS active" behavior during the 7-day grace.

**Alternatives considered.**

- **Read from RevenueCat directly**: synchronous external call on the hot path; no upside. Rejected.
- **Cache the entitlement in Redis**: premature; `users.subscription_status` is in the hot user row that's already typically loaded for auth.

### Decision 6 — Two Detekt rules: `RateLimitTtlRule` + `RedisHashTagRule`

**Choice.** Add both rules under `:lint:detekt-rules/src/main/kotlin/id/nearyou/lint/detekt/`, registered in `NearYouRuleSetProvider`. Pattern follows `CoordinateJitterRule` + `BlockExclusionJoinRule` precisely:

- **`RateLimitTtlRule`** — fires on any function call to `RateLimiter.tryAcquire(...)` whose `ttl` argument is **NOT** a call to `computeTTLToNextReset(...)` AND whose key argument matches the daily-key shape (key string contains `_day}` or starts with `{scope:rate_*_day}`). Allowlist via `/src/test/` path or `@AllowDailyTtlOverride("<reason>")` annotation on the enclosing declaration. Catches hardcoded `Duration.ofDays(1)`, `86400`, manual midnight math at daily-limit sites.
- **`RedisHashTagRule`** — fires on any Kotlin string literal (`KtStringTemplateExpression`) whose source matches `^rate:` or starts with `{scope:` AND does NOT contain a balanced `{scope:<value>}` segment. Allowlist via `/src/test/` path or `@AllowRawRedisKey("<reason>")` annotation.

Both rules MUST support detekt-test `lint(String)` synthetic-file harness via package-FQN fallback (mirror `BlockExclusionJoinRule.isAllowedPath`).

**Why.** The conventions only stick if a robot enforces them at every future call site. CoordinateJitterRule (PR #32) is the canonical precedent: an OpenSpec-shipped Kotlin Detekt rule with positive-fail fixtures and an annotation bypass. Two rules instead of one keeps each rule's logic narrowly scoped — easier to extend the daily-window key set later without touching hash-tag enforcement.

**Alternatives considered.**

- **One mega-rule**: harder to evolve, harder to allowlist independently. Rejected.
- **ktlint instead of Detekt**: Detekt is what this project uses for semantic lint (block-join, jitter); ktlint is style-only. Rejected.
- **CI grep**: fragile, no AST awareness. Rejected.

### Decision 7 — V9 `ReportRateLimiter` port: behind-the-interface swap, no public API change

**Choice.** Replace the body of `ReportRateLimiter.tryAcquire` / `releaseMostRecent` with calls to the new `RateLimiter` interface. The `Outcome` sealed interface, the `cap`/`window` constructor parameters, and the `keyFor(userId)` static helper all stay byte-identical. The V9 `ReportEndpointsTest` suite (database-tagged) MUST pass without modification — that's the regression gate.

The class itself is kept as a thin wrapper rather than deleted because callers reference its `Outcome` types and `keyFor` directly. Deletion would diff the V9 spec scenarios that were written against the wrapper (see `reports/spec.md` § "Redis key uses hash-tag format"). Wrapping is the minimum-diff port.

**Why.** Minimizes spec drift on `reports`. The behavior contract V9 designed (sliding window, hash-tag key, 409 release) was specifically structured so this swap would be invisible — proving that out is the point of the port.

**Alternatives considered.**

- **Delete `ReportRateLimiter`, inline at the route**: bigger diff to V9 spec scenarios + tests. Defer to a future cleanup if it makes sense post-port.

## Risks / Trade-offs

- **[Risk] Lettuce sync API blocks the Ktor coroutine dispatcher.** → Mitigation: every limiter call goes through `withContext(Dispatchers.IO)`. Benchmark in CI: assert P99 limiter call <5ms in the `LikeEndpointsTest` rate-limit cases; revisit async API if it regresses.
- **[Risk] Upstash cluster mode rejects the Lua script.** → Mitigation: hash tag `{scope:rate_like_day}:{user:<uuid>}` co-locates both segments to one slot; Lua sees both as one key for cluster purposes. The `RedisHashTagRule` Detekt rule prevents drift.
- **[Risk] `premium_like_cap_override` set to a value below the in-flight daily counter for a Free user mid-day.** → Mitigation: the override is read at request time, not at midnight; users whose counter already exceeds the new override start getting 429 immediately. Acceptable — the flag is for tightening (anti-abuse) or loosening (UX retention) and the abrupt cutoff is documented in Decision 28's intent.
- **[Risk] WIB-stagger drift if the Cloud Run instance clock skews.** → Mitigation: Cloud Run NTP is reliable; `computeTTLToNextReset` accepts `now` for tests so a fault-injection unit test can verify behavior across midnight WIB.
- **[Risk] V9 `ReportRateLimiter` test suite passes against the in-process impl but not against Redis** (e.g., test container missing). → Mitigation: the existing CI test job already runs PostgreSQL via `postgis/postgis` service container; add a `redis:7-alpine` service container alongside it (`docker-compose.yml` already declares one for dev), and have `KotestProjectConfig` boot it once per test JVM. The V9 test suite gets a one-line config change (Redis URL injected into the limiter constructor); no test code edits.
- **[Trade-off] Two limiters per like request (daily + burst) means two Redis round-trips per `POST /like`.** → Acceptable: each is single-Lua, sub-millisecond on Upstash; benchmark target `<10ms` total for the limiter pair. The 1000 req/min/IP Layer 1 baseline already gates abuse upstream.
- **[Trade-off] No DB-side counter for per-day usage.** → Intentional: Redis is authoritative for rate limits; the DB has no "likes today" column. If Redis goes down, the limiter fails-closed (return 503) — better than fail-open which would let the cap be bypassed.

## Migration Plan

This is **infra-additive** — no DB migration, no breaking API change.

**Steps:**

1. Land Lettuce dependency pin + `:infra:redis` module in a feat commit.
2. Land the `RateLimiter` interface, Redis impl, and `computeTTLToNextReset` helper in the same feat commit — they ship together or not at all.
3. Land the like-service rate-limit integration + `premium_like_cap_override` flag wiring + `LikeEndpointsTest` rate-limit scenarios.
4. Land the `ReportRateLimiter` port (zero V9 test changes).
5. Land both Detekt rules + their fixtures.

All five live in the same feat PR per the existing precedent (each prior change has been single-PR).

**Rollout:**

- Cloud Run deploy includes the new `KTOR_REDIS_URL` resolution (already provisioned in Pre-Phase 1; the `staging-redis-url` Secret Manager slot is documented in [`docs/04-Architecture.md`](../../../docs/04-Architecture.md) Deployment).
- Staging soak: 24 hours. Smoke test: a Free synthetic account hits 10 likes, 11th returns 429 with `Retry-After`, then waits the staggered window and the 12th passes.
- Prod ship via tag `v*`. The `premium_like_cap_override` flag stays unset at first (10/day Free baseline) — adjust if D7 metrics signal retention impact.

**Rollback:**

- The like cap is gated on the existence of the new infra. Revert is a clean PR revert: removing the limiter call from the like service immediately restores V7 behavior. The `:infra:redis` module can stay; nothing else depends on it yet beyond the V9 reports port. If the V9 port has issues, the wrapper class can re-instantiate the in-process `ConcurrentHashMap` impl as a one-line revert.

## Open Questions

- **Should the daily cap include unlikes?** Decision: NO. `DELETE /like` does not consume a slot — the cap counts INSERT events only. The 500/hour burst also counts INSERTs only. This matches the "10 likes per day" UX promise (users wouldn't expect un-liking a post to cost them a slot for re-liking later). Documented as a scenario in the post-likes spec.
- **Should the `premium_like_cap_override` apply to the burst limit?** Decision: NO. Burst is anti-bot and applies equally to both tiers per [`docs/02-Product.md:223`](../../../docs/02-Product.md). Override is for the daily Free cap only.
- **Pre-existing divergence found during scoping (NOT in scope here):** `reports/spec.md` shipped 10/hour but [`docs/05-Implementation.md:1742`](../../../docs/05-Implementation.md) Layer 2 says 20/hour. Documented in `FOLLOW_UPS.md` for separate reconciliation; this change does NOT silently adjust either side.
