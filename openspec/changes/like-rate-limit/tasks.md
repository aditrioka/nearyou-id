## 1. Lettuce + `:infra:redis` module

- [ ] 1.1 Pin `io.lettuce:lettuce-core` in `gradle/libs.versions.toml` (single typesafe accessor — no raw `group:artifact:version` strings per `09-Versions.md` policy)
- [ ] 1.2 Add a row to `docs/09-Versions.md` Version Decisions table justifying the new pin (rationale: rate-limit infra MVP; next review Q3)
- [ ] 1.3 Create `:infra:redis` Gradle module — JVM target only, applies the standard backend module conventions
- [ ] 1.4 Add `:infra:redis` to `settings.gradle.kts` and to `:backend:ktor` `dependencies { ... }`
- [ ] 1.5 Verify `:core:domain` and `:core:data` do NOT depend on `:infra:redis` (negative-test in `BuildSanityTest` mirroring existing module-isolation patterns)
- [ ] 1.6 Implement Koin module in `:infra:redis` providing a singleton Lettuce `RedisClient` configured via `secretKey(env, "redis-url")`
- [ ] 1.7 Provision the `staging-redis-url` and `redis-url` secret slots in GCP Secret Manager (or document that they were already provisioned in Pre-Phase 1; cross-link `docs/04-Architecture.md` Deployment > Secret Manager namespace)
- [ ] 1.8 Confirm local dev `docker-compose.yml` already exposes Redis on the documented port; if not, amend per `04-Architecture.md` Local Dev section (no DB migration; this is local infra only)

## 2. `RateLimiter` interface in `:core:domain`

- [ ] 2.1 Add package `id.nearyou.app.core.domain.ratelimit` under `core/domain/src/commonMain/kotlin/`
- [ ] 2.2 Define sealed interface `Outcome` with `Allowed(remaining: Int)` and `RateLimited(retryAfterSeconds: Long)` — match V9 `ReportRateLimiter.Outcome` shape byte-for-byte
- [ ] 2.3 Define interface `RateLimiter` with `tryAcquire(userId: UUID, key: String, capacity: Int, ttl: Duration): Outcome` and `releaseMostRecent(userId: UUID, key: String)`
- [ ] 2.4 Verify the interface compiles in `:core:domain` without any Redis or vendor SDK imports
- [ ] 2.5 Add `RateLimiterTest` (commonTest) — interface contract tests using a fake in-process implementation; the contract MUST exactly mirror what V9's existing `ReportRateLimiter` test suite asserts

## 3. `computeTTLToNextReset(userId)` shared helper

- [ ] 3.1 Add `computeTTLToNextReset(userId: UUID, now: Instant = Instant.now()): Duration` in `id.nearyou.app.core.domain.ratelimit`
- [ ] 3.2 Implement: `offset_seconds = abs(userId.hashCode()) % 3600`; compute next 00:00 in `Asia/Jakarta` at-or-after `now`; return `Duration.between(now, next_midnight + offset_seconds)`. If `now` is exactly midnight WIB → use `now + 24h` as the base.
- [ ] 3.3 Add `ComputeTtlToNextResetTest` (commonTest) covering: same user same offset across calls, different users different offsets at high probability (1000 random pairs), offset bounded `[0, 3600)` at fixed `now = 2026-01-01T00:00:00Z`, midnight-crossing rollover, deterministic pure function
- [ ] 3.4 Verify the helper has zero file/network/DB I/O — only `java.time` + the userId argument

## 4. Redis-backed `RateLimiter` implementation

- [ ] 4.1 Implement `RedisRateLimiter` in `:infra:redis/src/main/kotlin/` against the `RateLimiter` interface
- [ ] 4.2 Embed Lua script: `ZREMRANGEBYSCORE` (prune) → `ZCARD` (count) → branch (oldest via `ZRANGE 0 0 WITHSCORES` + `Retry-After` math, OR `ZADD now jti` + `PEXPIRE ttl`)
- [ ] 4.3 Wrap the Lua call in `withContext(Dispatchers.IO)` so the Ktor coroutine dispatcher stays unblocked
- [ ] 4.4 Use a per-call `UUID.randomUUID().toString()` as the sorted-set member to avoid same-millisecond collisions
- [ ] 4.5 Implement `releaseMostRecent` as `ZPOPMAX key 1` (atomic, single-call)
- [ ] 4.6 Wire the `RedisRateLimiter` as the production `RateLimiter` Koin binding in `:backend:ktor` Application setup
- [ ] 4.7 Add `redis:7-alpine` service container to `.github/workflows/ci.yml` `test` job (mirroring the `postgis/postgis:16-3.4` setup)
- [ ] 4.8 Boot Redis once per test JVM via `KotestProjectConfig.beforeProject()` (mirroring the existing Flyway-bootstrap pattern); inject the URL into `RedisRateLimiter` via Koin override
- [ ] 4.9 Implement `RedisRateLimiterIntegrationTest` (`:infra:redis/src/test/kotlin/`, tagged `database`) covering: empty-bucket admit-then-reject, Retry-After math ±2s, releaseMostRecent restores slot, 10-thread concurrent capacity-boundary test, old-entry pruning, hash-tag co-location (CRC16 slot equivalence)

## 5. Hash-tag key format helper + Detekt rules

- [ ] 5.1 Add a typed value-class `RateLimitKey(scope: String, axis: String, value: String)` in `:core:domain` whose `toString()` produces `{scope:<scope>}:{<axis>:<value>}` — caller convenience, NOT a hard requirement (the spec permits any string, the Detekt rule enforces the shape)
- [ ] 5.2 Implement `RateLimitTtlRule` in `lint/detekt-rules/src/main/kotlin/id/nearyou/lint/detekt/` — fires on `tryAcquire` calls whose key string contains `_day}` or matches `^\{scope:rate_[a-z_]+_day\}` AND whose `ttl` argument is not a `computeTTLToNextReset(...)` call. Allowlist via `/src/test/` path + `@AllowDailyTtlOverride` annotation. Mirror `BlockExclusionJoinRule.isAllowedPath` for synthetic-file harness support.
- [ ] 5.3 Implement `RedisHashTagRule` in the same package — fires on string literals matching `^rate:` OR containing `{scope:` without a balanced `}:{<axis>:<value>}` segment. Allowlist via `/src/test/` path + `@AllowRawRedisKey` annotation.
- [ ] 5.4 Define `@AllowDailyTtlOverride(reason: String)` and `@AllowRawRedisKey(reason: String)` annotations in `:core:domain`
- [ ] 5.5 Register both rules in `NearYouRuleSetProvider` (`lint/detekt-rules/src/main/kotlin/.../NearYouRuleSetProvider.kt`)
- [ ] 5.6 Implement `RateLimitTtlRuleTest` covering: daily-key + hardcoded ttl fires, daily-key + `computeTTLToNextReset` passes, burst-key + hardcoded ttl passes, test-file passes, annotation-bypass passes, rule-registered-via-provider — minimum 6 scenarios mirroring `CoordinateJitterLintTest`
- [ ] 5.7 Implement `RedisHashTagRuleTest` covering: legacy `rate:user:$id` fires, malformed `{scope:` literal fires, conforming literal passes, non-rate-limit string passes, test-file passes, annotation-bypass passes, rule-registered-via-provider — minimum 6 scenarios

## 6. Apply rate limit to `POST /api/v1/posts/{post_id}/like`

- [ ] 6.1 Add `RateLimiter` dependency injection to `LikeService` (or `LikeRoutes`, whichever owns the handler — match V9 `ReportRoutes` pattern)
- [ ] 6.2 Implement the daily limiter call: skip entirely if `users.subscription_status IN ('premium_active', 'premium_billing_retry')`; otherwise resolve the cap from `premium_like_cap_override` Remote Config flag (default 10, fallback 10 on unset/0/error); call `tryAcquire(userId, "{scope:rate_like_day}:{user:$userId}", capacity, computeTTLToNextReset(userId))`. On `RateLimited`: respond 429 + `Retry-After` + STOP.
- [ ] 6.3 Implement the burst limiter call (both tiers): `tryAcquire(userId, "{scope:rate_like_burst}:{user:$userId}", capacity = 500, ttl = Duration.ofHours(1))`. On `RateLimited`: respond 429 + `Retry-After` + STOP.
- [ ] 6.4 Implement the no-op release path: when `INSERT ... ON CONFLICT DO NOTHING` reports 0 rows affected, call `releaseMostRecent` on BOTH limiter keys (Free) or just the burst key (Premium) before returning 204
- [ ] 6.5 Verify ordering: auth → UUID validation → daily limiter → burst limiter → `visible_posts` resolution → INSERT → release-on-no-op → notification emit → 204
- [ ] 6.6 Wire `premium_like_cap_override` into the Remote Config helper (`infra/remote-config`) — read on every Free request; coerce non-positive integers to default 10
- [ ] 6.7 Audit log the override-was-applied case at INFO level (helps ops verify the flag is being honored)

## 7. Port V9 `ReportRateLimiter` to the shared infra

- [ ] 7.1 Replace the `ConcurrentHashMap`-backed body of `ReportRateLimiter.tryAcquire` and `releaseMostRecent` with calls to the injected `RateLimiter` interface
- [ ] 7.2 Keep `ReportRateLimiter.Outcome`, `keyFor(userId)`, `cap`, `window`, and the public class signature byte-for-byte identical (the wrapper stays — V9 spec scenarios reference these)
- [ ] 7.3 Remove the "deferred to a separate change" comment block in `ReportRateLimiter.kt:13-19`; update the KDoc to point at `rate-limit-infrastructure` capability and `like-rate-limit` change
- [ ] 7.4 Run the existing V9 `ReportEndpointsTest` and `ReportRateLimiterTest` suites without modification — they MUST pass against the ported implementation
- [ ] 7.5 If any V9 test fails: investigate the divergence; do NOT modify the V9 test to make it green (the port goal is byte-for-byte behavior preservation)

## 8. Tests for `POST /like` rate limit

- [ ] 8.1 Implement `LikeRateLimitTest` (`:backend:ktor/src/test/kotlin/`, tagged `database`, depends on Redis service container)
- [ ] 8.2 Cover all 16 scenarios from the `post-likes` spec § Integration test coverage requirement: 10-succeed, 11th-rate-limited, Retry-After ±2s, Premium-skip-daily, premium_billing_retry-skip-daily, 500-burst-Premium, 500-burst-Free-with-override, override-raises-cap-to-20, override-lowers-cap-mid-day, override-zero-falls-to-default, re-like-releases-both-slots, 404-consumes-slot, daily-short-circuits-before-visible-posts, hash-tag-key-shape-verified, WIB-rollover-restores-cap, unlike-no-slot-consumed
- [ ] 8.3 Use a clock-controlled fake `Instant` (mirror `ReportRateLimiterTest` precedent) for the WIB rollover and Retry-After ±2s assertions
- [ ] 8.4 Add a `LikeRateLimitTest` scenario asserting the daily key string is exactly `{scope:rate_like_day}:{user:<uuid>}` and the burst key is exactly `{scope:rate_like_burst}:{user:<uuid>}`
- [ ] 8.5 Run `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test :infra:redis:test :core:domain:test` locally — ALL must pass before push (per `CLAUDE.md` § Pre-push verification: ktlintCheck + detekt are both required)

## 9. Pre-push + CI verification

- [ ] 9.1 Run `openspec validate like-rate-limit --strict` — green
- [ ] 9.2 Run `./gradlew ktlintCheck detekt` — green (both new rules registered, both green on the codebase as ported)
- [ ] 9.3 Run `./gradlew :backend:ktor:test :infra:redis:test :core:domain:test :lint:detekt-rules:test` — green
- [ ] 9.4 Run `./gradlew :backend:ktor:test --tests '*ReportEndpointsTest*' --tests '*ReportRateLimiterTest*'` — V9 suite green against ported impl
- [ ] 9.5 Verify git tree shows expected files only (no `.gradle/`, no `.idea/`, no debug logs accidentally staged)
- [ ] 9.6 Push and confirm CI's `lint`, `build`, `test`, and `migrate-supabase-parity` jobs all green (Redis service container running)
- [ ] 9.7 Wait for staging deploy (`deploy-staging.yml`) green; smoke test: hit 10 likes from a Free synthetic account, expect 11th to return 429 + `Retry-After`

## 10. Doc sync (in archive PR, not feat PR)

- [ ] 10.1 Update `docs/08-Roadmap-Risk.md` Phase 1 item 24 — strike "deferred" or note: "infrastructure shipped via `like-rate-limit` change; daily-stagger helper, hash-tag standard, and two Detekt rules in place"
- [ ] 10.2 Update `docs/08-Roadmap-Risk.md` Phase 2 item 3 — note "Like rate limit + premium_like_cap_override flag shipped via `like-rate-limit` change. Reply rate limit remains deferred."
- [ ] 10.3 Update `docs/05-Implementation.md` § Rate Limiting Implementation — add a "shipped" cross-reference to the `rate-limit-infrastructure` capability + the `like-rate-limit` change
- [ ] 10.4 Update `docs/01-Business.md` like-cap row if needed (probably no edit required — the canonical 10/day Free + 500/hour cap is what shipped)
- [ ] 10.5 Run `openspec archive like-rate-limit` per `openspec/project.md` § Change Delivery Workflow: separate archive PR after feat PR merges + staging deploy green
