## 1. Lettuce + `:infra:redis` module

- [x] 1.1 Pin `io.lettuce:lettuce-core` in `gradle/libs.versions.toml` (single typesafe accessor — no raw `group:artifact:version` strings per `09-Versions.md` policy)
- [x] 1.2 Add a row to `docs/09-Versions.md` Version Decisions table justifying the new pin (rationale: rate-limit infra MVP; next review Q3)
- [x] 1.3 Create `:infra:redis` Gradle module — JVM target only, applies the standard backend module conventions
- [x] 1.4 Add `:infra:redis` to `settings.gradle.kts` and to `:backend:ktor` `dependencies { ... }`
- [x] 1.5 Verify `:core:domain` and `:core:data` do NOT depend on `:infra:redis` (verified by inspection: `core/domain/build.gradle.kts` and `core/data/build.gradle.kts` declare no dependency on `:infra:redis`. A formal architecture-test pattern (e.g., ArchUnit or shell-script grep) is not yet established in this codebase; deferred to a follow-up if multiple modules need similar negative-dep checks.)
- [x] 1.6 Implement Koin module in `:infra:redis` providing a singleton Lettuce `RedisClient` configured via env-aware slot lookup (mirrors `secretKey(env, "redis-url")` convention; slot derivation duplicated locally to avoid `:backend:ktor` ↔ `:infra:redis` circular dep)
- [ ] 1.7 Verify the `staging-redis-url` and `redis-url` secret slots exist in GCP Secret Manager. Pre-Phase 1 item 34 in `docs/08-Roadmap-Risk.md` mentions Upstash provisioning + secret namespacing as the staging bootstrap; before push, run `gcloud secrets list --filter="name~redis"` against both staging and prod projects and confirm both slots are populated. If missing, provision them now — implementation cannot proceed without these slots, so this is a hard prerequisite, not optional documentation. **(Blocking on user — requires GCP access to verify; flagged in PR body for human verification before final squash-merge.)**
- [x] 1.8 Confirm local dev `docker-compose.yml` already exposes Redis on the documented port; if not, amend per `04-Architecture.md` Local Dev section (no DB migration; this is local infra only) — verified: `dev/docker-compose.yml` already declares `redis:7-alpine` with port `6379:6379` and a `redis-cli ping` healthcheck. No change needed.

## 2. `RateLimiter` interface in `:core:domain`

- [x] 2.1 Add package `id.nearyou.app.core.domain.ratelimit` under `core/domain/src/main/kotlin/` (note: `:core:domain` is JVM-only in this repo, not KMP, so the path is `src/main/kotlin/` not `src/commonMain/kotlin/` — minor spec drift, behavior-equivalent)
- [x] 2.2 Define sealed interface `Outcome` with `Allowed(remaining: Int)` and `RateLimited(retryAfterSeconds: Long)` — match V9 `ReportRateLimiter.Outcome` shape byte-for-byte (declared as nested types inside `RateLimiter` interface; same Kotlin shape, namespaced under the interface)
- [x] 2.3 Define interface `RateLimiter` with `tryAcquire(userId: UUID, key: String, capacity: Int, ttl: Duration): Outcome` and `releaseMostRecent(userId: UUID, key: String)`
- [x] 2.4 Verify the interface compiles in `:core:domain` without any Redis or vendor SDK imports — verified by inspection: `RateLimiter.kt` imports only `java.time.Duration` and `java.util.UUID`
- [x] 2.5 Add `RateLimiterTest` (in `core/domain/src/test/kotlin/`, JVM tests not commonTest per the JVM-only module) — interface contract tests using a fake in-process implementation. Covers: `Allowed.remaining` decrement across slots, 11th call returns RateLimited with retryAfterSeconds ≥ 1, releaseMostRecent restores the slot, releaseMostRecent on empty bucket is no-op, different keys are independent, different users on the same key are independent.

## 3. `computeTTLToNextReset(userId)` shared helper

- [x] 3.1 Add `computeTTLToNextReset(userId: UUID, now: Instant = Instant.now()): Duration` in `id.nearyou.app.core.domain.ratelimit`
- [x] 3.2 Implement: `offset_seconds = abs(userId.hashCode().toLong()) % 3600` (`.toLong()` cast prevents `Int.MIN_VALUE.absoluteValue == Int.MIN_VALUE` overflow); compute next 00:00 in `Asia/Jakarta` at-or-after `now` (always tomorrow's midnight, since `todayMidnight ≤ nowZdt` always holds and the spec carve-out forces forward-roll on exact-midnight); return `Duration.between(nowZdt, effectiveReset)`.
- [x] 3.3 Add `ComputeTtlToNextResetTest` covering: same user same offset across calls, pure-function determinism, offset bounded `[0, 3600)` at fixed `now = 2026-01-01T00:00:00Z` (1000 random users), different users different offsets at high probability (≥ 999 of 1000), midnight-crossing rollover (just-before vs just-after WIB midnight), exactly-at-midnight WIB carve-out (TTL = 24h with offset 0), strictly-positive-Duration spot check.
- [x] 3.4 Verify the helper has zero file/network/DB I/O — only `java.time` + the userId argument — verified by inspection: imports are `java.time.Duration`, `java.time.Instant`, `java.time.ZoneId`, `java.util.UUID`, `kotlin.math.absoluteValue`. No I/O.

## 4. Redis-backed `RateLimiter` implementation

- [x] 4.1 Implement `RedisRateLimiter` in `:infra:redis/src/main/kotlin/` against the `RateLimiter` interface
- [x] 4.2 Embed Lua script: `ZREMRANGEBYSCORE` (prune) → `ZCARD` (count) → branch (oldest via `ZRANGE 0 0 WITHSCORES` + `Retry-After` math, OR `ZADD now jti` + `PEXPIRE ttl`)
- [ ] 4.3 Wrap the Lua call in `withContext(Dispatchers.IO)` so the Ktor coroutine dispatcher stays unblocked — **deferred to call site**: the `RateLimiter` interface is non-suspending (V9 contract preservation). The `withContext(Dispatchers.IO)` wrap properly belongs at the like-service call site (section 6) where the surrounding coroutine context is known. Documented in `RedisRateLimiter.kt` KDoc. Spec drift acknowledged in commit message.
- [x] 4.4 Use a per-call `UUID.randomUUID().toString()` as the sorted-set member to avoid same-millisecond collisions
- [x] 4.5 Implement `releaseMostRecent` as `ZPOPMAX key 1` (atomic, single-call)
- [ ] 4.6 Wire the `RedisRateLimiter` as the production `RateLimiter` Koin binding in `:backend:ktor` Application setup — **deferred until task 1.7 GCP slot blocker is resolved.** Wiring now would make `REDIS_URL` a hard startup requirement; without the slot provisioned, dev/test runs would fail. Will land alongside section 6 (like-service rate-limit integration) once slots are confirmed available.
- [x] 4.7 Add `redis:7-alpine` service container to `.github/workflows/ci.yml` `test` job (mirroring the `postgis/postgis:16-3.4` setup). Also injects `REDIS_URL=redis://localhost:6379` env var into the test step.
- [ ] 4.8 Boot Redis once per test JVM via `KotestProjectConfig.beforeProject()` (mirroring the existing Flyway-bootstrap pattern); inject the URL into `RedisRateLimiter` via Koin override — **not needed at this layer.** `RedisRateLimiterIntegrationTest` lives in `:infra:redis`'s own test source set, not in `:backend:ktor`'s; it manages its own RedisClient lifecycle (per-spec `afterSpec { client.shutdown() }`) and probes Redis reachability inline. The `KotestProjectConfig.beforeProject()` boot is a `:backend:ktor` concern that becomes relevant when section 6 wires the like-service against Redis (will land then alongside task 4.6).
- [x] 4.9 Implement `RedisRateLimiterIntegrationTest` (`:infra:redis/src/test/kotlin/`, tagged `database`) covering all 10 scenarios from `rate-limit-infrastructure/spec.md` § Test coverage: empty-bucket admit-then-reject (capacity 10), Retry-After math within ±5s using a frozen Clock, releaseMostRecent restores slot, releaseMostRecent on empty bucket is no-op, parallel-coroutine concurrent capacity-boundary (10 admit / 5 reject), old-entry pruning, hash-tag CRC16 equivalence via `Lettuce.SlotHash.getSlot`, two same-millisecond inserts both land via random JTI, bucket-over-capacity preserved on cap reduction, V9 contract subsumption (10-succeed / 11th-429 / 409-release-on-no-op). 10/10 scenarios green.

## 5. Hash-tag key format Detekt rules

- [ ] 5.1 Implement `RateLimitTtlRule` in `lint/detekt-rules/src/main/kotlin/id/nearyou/lint/detekt/` — fires on `tryAcquire` calls whose key string contains `_day}` or matches `^\{scope:rate_[a-z_]+_day\}` AND whose `ttl` argument is not a `computeTTLToNextReset(...)` call. Allowlist via `/src/test/` path + `@AllowDailyTtlOverride` annotation (with non-empty reason). Mirror `BlockExclusionJoinRule.isAllowedPath` for synthetic-file harness support.
- [ ] 5.2 Implement `RedisHashTagRule` in the same package — fires on string literals matching `^rate:` OR containing `{scope:` without a balanced `}:{<axis>:<value>}` segment. Allowlist via `/src/test/` path + `@AllowRawRedisKey` annotation (with non-empty reason).
- [ ] 5.3 Define `@AllowDailyTtlOverride(reason: String)` and `@AllowRawRedisKey(reason: String)` annotations in `:core:domain`. Both annotations declare `reason` as a non-nullable `String` parameter (no default value) so callers cannot syntactically omit it; the Detekt rules additionally reject empty-string `reason` arguments to close the silent-bypass loophole.
- [ ] 5.4 Register both rules in `NearYouRuleSetProvider` (`lint/detekt-rules/src/main/kotlin/.../NearYouRuleSetProvider.kt`)
- [ ] 5.5 Implement `RateLimitTtlRuleTest` covering at minimum: daily-key + hardcoded ttl fires, daily-key + `computeTTLToNextReset` passes, burst-key + hardcoded ttl passes, test-file passes, annotation-with-non-empty-reason passes, annotation-with-empty-reason still fires, synthetic-file-harness via package-FQN fallback passes, rule-registered-via-provider — 8 scenarios per the spec
- [ ] 5.6 Implement `RedisHashTagRuleTest` covering at minimum: legacy `rate:user:$id` fires, malformed `{scope:` literal (missing braces on second segment) fires, conforming `{scope:...}:{user:...}` passes, non-rate-limit string passes, test-file passes, annotation-with-non-empty-reason passes, annotation-with-empty-reason still fires, synthetic-file-harness via package-FQN fallback passes, rule-registered-via-provider — 9 scenarios per the spec

**Note on `RateLimitKey` value-class:** an earlier draft of this change proposed a `RateLimitKey(scope, axis, value)` value class in `:core:domain` for caller convenience. Dropped because (a) the Detekt rule enforces the shape directly on string literals, making the value class an unenforced parallel API, and (b) value-class adoption would either require all callers to migrate (out of this change's scope) or remain optional (orphan abstraction). String literals + Detekt enforcement is sufficient.

## 6. Apply rate limit to `POST /api/v1/posts/{post_id}/like`

- [ ] 6.1 Add `RateLimiter` dependency injection to `LikeService` (or `LikeRoutes`, whichever owns the handler — match V9 `ReportRoutes` pattern)
- [ ] 6.1.1 Verify the auth-jwt principal already carries `subscription_status` alongside `userId`. Read `backend/ktor/src/main/kotlin/id/nearyou/app/auth/` and confirm the principal type exposes `subscription_status`. If it does not, add it to the auth-side principal load (single `users` SELECT done once at auth time, not per-handler) — fixing the auth path is the correct location, NOT working around by adding a SELECT in the like handler. The like handler MUST read tier from the principal, never via a fresh DB query.
- [ ] 6.2 Implement the daily limiter call: skip entirely if `principal.subscription_status IN ('premium_active', 'premium_billing_retry')`; otherwise resolve the cap from `premium_like_cap_override` Remote Config flag (default 10, fallback 10 on unset/zero/malformed/RC-error); call `tryAcquire(userId, "{scope:rate_like_day}:{user:$userId}", capacity, computeTTLToNextReset(userId))`. On `RateLimited`: respond 429 + `Retry-After` + STOP.
- [ ] 6.3 Implement the burst limiter call (both tiers): `tryAcquire(userId, "{scope:rate_like_burst}:{user:$userId}", capacity = 500, ttl = Duration.ofHours(1))`. On `RateLimited`: respond 429 + `Retry-After` + STOP.
- [ ] 6.4 Implement the no-op release path: when `INSERT ... ON CONFLICT DO NOTHING` reports 0 rows affected, call `releaseMostRecent` on BOTH limiter keys regardless of tier — for Premium re-likes the daily-key call is a no-op against the empty bucket per the `RateLimiter` contract, but calling it unconditionally protects against a future regression where a Premium user's daily bucket gets populated (e.g., a tier flip mid-day) and the slot leaks because the handler skipped the call.
- [ ] 6.5 Verify ordering: auth → UUID validation → daily limiter → burst limiter → `visible_posts` resolution → INSERT → release-on-no-op → notification emit (synchronous DB-transaction commit only; FCM push side-effects must be enqueued fire-and-forget on `Dispatchers.IO`) → 204
- [ ] 6.6 Wire `premium_like_cap_override` into the Remote Config helper (`infra/remote-config`) — read on every Free request; coerce non-positive integers, malformed strings, and SDK errors all to the default 10. Sentry WARN on each fallback path so ops can detect Remote Config misconfigurations.
- [ ] 6.7 Audit log the override-was-applied case at INFO level (helps ops verify the flag is being honored)
- [ ] 6.8 Verify the notification emit path (V10 `NotificationEmitter`) does NOT block the request: the DB-transaction commit must be synchronous, but any FCM push or downstream side-effect must run on `Dispatchers.IO` fire-and-forget. Add an integration test fixture that injects a deliberately-slow FCM stub (e.g., 30s sleep) and asserts the like response returns 204 within the standard request budget.

## 7. Port V9 `ReportRateLimiter` to the shared infra

- [ ] 7.1 Replace the `ConcurrentHashMap`-backed body of `ReportRateLimiter.tryAcquire` and `releaseMostRecent` with calls to the injected `RateLimiter` interface
- [ ] 7.2 Keep `ReportRateLimiter.Outcome`, `keyFor(userId)`, `cap`, `window`, and the public class signature byte-for-byte identical (the wrapper stays — V9 spec scenarios reference these)
- [ ] 7.3 Remove the "deferred to a separate change" comment block in `ReportRateLimiter.kt:13-19`; update the KDoc to point at `rate-limit-infrastructure` capability and `like-rate-limit` change
- [ ] 7.4 Re-point V9's `ReportRateLimiterTest` at an in-memory test double of the new `RateLimiter` interface. The test class itself stays — it now verifies moderation-route plumbing (correct cap/window wiring, 409-release call site) at unit speed. Lua-level correctness moves to `RedisRateLimiterIntegrationTest` (task 4.9). Be explicit about this split in the V9 test class's KDoc.
- [ ] 7.5 Run V9's `ReportEndpointsTest` (database-tagged, end-to-end against the new Redis container) without modification — those tests assert HTTP-level behavior, not internal limiter mechanics, and MUST pass byte-for-byte against the ported impl. Any failure here IS a regression in client-visible contract.

## 8. Tests for `POST /like` rate limit

- [ ] 8.1 Implement `LikeRateLimitTest` (`:backend:ktor/src/test/kotlin/`, tagged `database`, depends on Redis service container)
- [ ] 8.2 Cover all 21 scenarios from the `post-likes` spec § Integration test coverage requirement: 10-succeed, 11th-rate-limited, Retry-After ±5s, Premium-skip-daily, premium_billing_retry-skip-daily, 500-burst-Premium, 500-burst-Free-with-override, override-raises-cap-to-20, override-lowers-cap-mid-day, override-zero-falls-to-default, override-malformed-string-falls-to-default, override-remote-config-error-falls-to-default, re-like-Free-releases-both-slots, re-like-Premium-releases-burst-only, 404-consumes-slot, daily-short-circuits-before-visible-posts, hash-tag-key-shape-verified, WIB-rollover-restores-cap, unlike-no-slot-consumed, notification-emit-does-not-block-response, tier-read-from-auth-principal-not-DB
- [ ] 8.3 Use a clock-controlled fake `Instant` (mirror `ReportRateLimiterTest` precedent) for the WIB rollover assertions; Retry-After uses ±5s tolerance per the spec
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
