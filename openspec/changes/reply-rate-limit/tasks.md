## 1. Discovery & infra verification

- [x] 1.1 Verified during `/next-change` reconciliation pass: Free 20/day, Premium unlimited, no burst — consistent across `01-Business.md:53`, `02-Product.md:224`, `05-Implementation.md:1740`.
- [x] 1.2 Verified `openspec/specs/rate-limit-infrastructure/spec.md` confirms the `RateLimiter` interface contract (`tryAcquire(userId, key, capacity, ttl): Outcome`, `releaseMostRecent(userId, key)`) and `computeTTLToNextReset` placement in `id.nearyou.app.core.domain.ratelimit`.
- [x] 1.3 Verified V8 ordering by reading `ReplyRoutes.kt` + `ReplyService.kt`: current sequence is auth → UUID → JSON parse → content guard → `service.post()` (which does visibility resolve + INSERT + V10 emit in TX → returns row). New ordering inserts the rate limiter BETWEEN UUID validation AND JSON body parse.
- [x] 1.4 Verified: `:infra:redis` module exists (per like-rate-limit shipped). `.github/workflows/ci.yml:67-68` runs `redis:7-alpine` service container with `REDIS_URL=redis://localhost:6379`. No new CI infra needed.
- [x] 1.5 Verified: `UserPrincipal.subscriptionStatus: String` exists at `backend/ktor/src/main/kotlin/id/nearyou/app/auth/AuthPlugin.kt:34`. RevenueCat webhook handler **does not yet exist in the backend** (Phase 4 work per `docs/08-Roadmap-Risk.md`); the JWT TTL window concern raised during proposal review is therefore moot until Phase 4 lands the handler. Filed in `FOLLOW_UPS.md` (`auth-jwt-spec-debt-userprincipal-subscription-status` — extended scope to track RevenueCat-handler-token-version-bump as a Phase 4 sub-action).
- [x] 1.6 **Deferred to manual user verification.** Mobile reply-button 429 handling cannot be reliably checked from this scope (Compose Multiplatform UI code). The backend 429 contract is correct regardless; the generic timeline rate-limit screen catches uncaught 429s. Filed in commit body as a known unverified-at-merge item.

## 2. `premium_reply_cap_override` Remote Config flag plumbing

- [x] 2.1 Added `PREMIUM_REPLY_CAP_OVERRIDE_KEY` constant to `ReplyService` companion object (mirrors `LikeService.PREMIUM_LIKE_CAP_OVERRIDE_KEY` placement; the `RemoteConfig` interface itself is key-agnostic — `getLong(key)` accepts any string, so no interface-level change is needed).
- [x] 2.2 Verified: `RemoteConfig.getLong("premium_reply_cap_override")` resolves through the same code path as the like flag (the `RemoteConfig` interface is key-agnostic). `StubRemoteConfig` returns null for any key, so the unset-fallback path is exercised by default in dev/test.
- [x] 2.3 Skipped — the existing `RemoteConfig` KDoc already names `premium_like_cap_override` as "the first consumer" of the abstraction; future consumers (this change is the second) don't need separate per-key comments. The mirror semantics are documented in `ReplyService.resolveDailyCap` KDoc.

## 3. Apply rate limit to `POST /api/v1/posts/{post_id}/replies`

- [x] 3.1 Injected `RateLimiter` + `RemoteConfig` (+ optional `clock` for tests) into `ReplyService`. Added `RateLimitOutcome` sealed type with `Allowed` / `RateLimited(retryAfterSeconds)`. The rate-limit gate is exposed as a separate `suspend fun tryRateLimit(userId, subscriptionStatus)` method (rather than embedded in `post()`) so the route can run the limiter BEFORE JSON body parse + content guard — this satisfies the spec's "limiter BEFORE body parse" ordering. The `post()` method itself stays non-suspend and unchanged in shape.
- [x] 3.2 `resolveDailyCap(userId)` reads `remoteConfig.getLong(PREMIUM_REPLY_CAP_OVERRIDE_KEY)` with try/catch. Unset / null / ≤ 0 / > 10,000 (anti-typo clamp per spec) / SDK error all coerce to default 20. WARN logs on each fallback; INFO log on the override-applied happy path. Mirrors `LikeService.resolveDailyCap` byte-for-byte (added the upper-bound clamp the spec scenarios mandate, distinct from likes).
- [x] 3.3 Daily-limiter call lives in `ReplyService.tryRateLimit`: skips entirely if `subscriptionStatus in PREMIUM_STATES`. Otherwise calls `tryAcquire(userId, "{scope:rate_reply_day}:{user:$userId}", cap, computeTTLToNextReset(userId, clock()))` inside `withContext(Dispatchers.IO)`. Returns `RateLimitOutcome.RateLimited` (mapped to 429 + `Retry-After` by the route) or `Allowed`.
- [x] 3.4 Ordering verified by reading `ReplyRoutes.post`: auth (`call.principal<UserPrincipal>()`) → UUID validation (`parseUuid`) → `service.tryRateLimit(userId, principal.subscriptionStatus)` → 429 short-circuit on `RateLimited` → `call.receive<ReplyCreateRequest>()` JSON parse → `contentGuard.enforce(REPLY_CONTENT_KEY, body.content)` → `service.post()` (which does `visible_posts` resolution + INSERT + V10 emit in one TX) → 201. The limiter MUST run before JSON parsing and content guard — confirmed.
- [x] 3.5 Verified `ReplyService.post()` does NOT call `RateLimiter.releaseMostRecent` anywhere. Added explicit KDoc note documenting the asymmetry with `LikeService.like()` (no `INSERT ... ON CONFLICT DO NOTHING` no-op equivalent on `post_replies` since every successful POST is a real new row).
- [x] 3.6 Audit-log line emitted at INFO from `resolveDailyCap` when cap is a non-default value: `event=remote_config_override_applied key=premium_reply_cap_override value=<cap> user_id=<uuid>`. Matches the like-flag audit-log shape byte-for-byte (verified by diffing `LikeService.resolveDailyCap`).
- [x] 3.7 `./gradlew :backend:ktor:detekt :lint:detekt-rules:test -PincludeMobile=false` — green. Both `RateLimitTtlRule` and `RedisHashTagRule` pass on the new call site (the `_day}` substring in `{scope:rate_reply_day}:{user:$userId}` matches `RateLimitTtlRule`'s daily-key pattern, and the strict hash-tag shape passes `RedisHashTagRule`). 0 detekt findings on the 47 backend kotlin files.
- [x] 3.8 V8 reply-endpoint contracts preserved: response shapes (POST 201 with `ReplyDto`, GET pagination, DELETE 204) unchanged at the route level. The V10 emit suppression rules (self / block / hard-delete-rollback) are inside `service.post()` which is byte-for-byte unchanged in body. `ReplyEndpointsTest` and `NotificationWritePathTest` updated to pass the new constructor args (`NoOpRateLimiter()` / existing `noopLimiter`); `./gradlew :backend:ktor:compileTestKotlin` green. Runtime verification (test pass byte-for-byte) lands at section 5.3 (CI run).

## 4. Tests for `POST /reply` rate limit

- [x] 4.1 Implemented `ReplyRateLimitTest` at `backend/ktor/src/test/kotlin/id/nearyou/app/engagement/ReplyRateLimitTest.kt`, tagged `database`. Backed by `InMemoryRateLimiter` (extracted in `like-rate-limit` task 7.1). Test fixtures (`SpyRateLimiterReply`, `FixedRemoteConfigReply`, `ThrowingRemoteConfigReply`, `NullRemoteConfigReply`) follow the like-rate-limit precedent with `Reply` suffix to avoid top-level-class redeclaration collisions across the two test files.
- [x] 4.2 All 24 scenarios from `specs/post-replies/spec.md` § "Integration test coverage — reply rate limit" implemented as Kotest `StringSpec` strings named "scenario 1 — …" through "scenario 24 — …". `./gradlew :backend:ktor:test --rerun-tasks "-Dkotest.filter.specs=*ReplyRateLimitTest"` → **24/24 green, 0 failures, 0 errors, 0 skipped**. The list:
   1. 20 replies in a day succeed for Free user.
   2. 21st reply rate-limited; 429 + Retry-After + no `post_replies` row + zero `notifications` rows (verified via INSERT-row-count snapshot).
   3. Retry-After ±5s of expected (frozen `AtomicReference<Instant>` clock).
   4. Premium (`premium_active`) skips the daily limiter — 50 replies succeed (50 chosen distinct from override-30 in scenario 6).
   5. Premium billing retry (`premium_billing_retry`) skips the daily limiter.
   6. `premium_reply_cap_override = 30` raises the cap; 31st rejected after a successful 30th.
   7. `premium_reply_cap_override = 5` lowers the cap mid-day; user previously at 7 is rejected at 8.
   8. `premium_reply_cap_override = 0` falls back to default 20.
   9. `premium_reply_cap_override = "thirty"` falls back to default 20 (assert response status only; WARN log is implementation-detail).
  10. Remote Config network failure (SDK throws) falls back to default 20; no 5xx propagated.
  11. 404 `post_not_found` consumes a slot (does NOT release).
  12. 400 `invalid_request` post-limiter consumes a slot.
  13. Daily limit hit short-circuits before content-length guard (281-char content at slot 21 → 429, NOT 400 — behavioral proof).
  14. Daily limit hit short-circuits before `visible_posts` SELECT (non-existent post UUID at slot 21 → 429, NOT 404 — behavioral proof; no DB-statement spy required).
  15. Hash-tag key shape: daily key = `{scope:rate_reply_day}:{user:<uuid>}` via a `SpyRateLimiter`.
  16. WIB rollover: at the per-user reset moment the cap restores (frozen `AtomicReference<Instant>` clock advanced past `computeTTLToNextReset(userId)` + 1s).
  17. Soft-delete does NOT release a daily slot: POST 5 → DELETE 1 (bucket stays at 5) → POST 15 more (bucket reaches 20) → 21st rejected with 429 → bucket stays at 20.
  18. DELETE works at the daily cap (20/20, DELETE returns 204, no limiter consulted).
  19. GET unaffected by the daily cap (caller at 21/20 still gets V8 200).
  20. Tier read from `viewer` principal: `SpyRateLimiter` confirms `tryAcquire` invoked AND HTTP 201 returned. Combined with scenario 14, proves no DB read between auth and limiter.
  21. The reply handler MUST NOT call `releaseMostRecent` — assert via `SpyRateLimiter` that `releaseMostRecent` is never invoked across all 24 scenarios.
  22. Null `subscriptionStatus` on viewer principal treated as Free — limiter applied, 21st request rejected.
  23. V10 transaction rollback does NOT release the slot — slot stays consumed when emit fails.
  24. `premium_reply_cap_override = Long.MAX_VALUE` (or > 10,000) clamps to default 20.
- [x] 4.3 Baseline preserved: `ReplyEndpointsTest` (V8 reply-endpoint contract) **24/24 green**, `NotificationWritePathTest` (V10 emit-coupling) **22/22 green** against the modified `ReplyService`. The new rate-limit scenarios live in `ReplyRateLimitTest`; the V8/V10 test classes were updated only to pass the new `rateLimiter` + `remoteConfig` constructor args (using `NoOpRateLimiter` + `StubRemoteConfig` test doubles).
- [x] 4.4 Pre-push gauntlet: `./gradlew ktlintCheck detekt :backend:ktor:test --rerun-tasks "-Dkotest.filter.specs=*ReplyRateLimitTest|*ReplyEndpointsTest|*NotificationWritePathTest" :lint:detekt-rules:test :infra:redis:test :core:domain:test -PincludeMobile=false` — **all green**. (Targeted Kotest filter rather than the full `:backend:ktor:test` to keep local cycle time ≤2 min; full suite runs on CI.)

## 5. Pre-push + CI verification

- [x] 5.1 `openspec validate reply-rate-limit --strict` — GREEN throughout (proposal phase + round-1/round-2 review fixes; no spec edits in feat phase).
- [x] 5.2 `./gradlew ktlintCheck detekt -PincludeMobile=false` — GREEN. Both `RateLimitTtlRule` and `RedisHashTagRule` produce 0 findings on the new `ReplyService` call site (the `_day}` substring + `{scope:rate_reply_day}:{user:<uuid>}` shape align with the rules' patterns).
- [x] 5.3 Local Kotest-filtered run: `ReplyRateLimitTest` 24/24, `ReplyEndpointsTest` 24/24, `NotificationWritePathTest` 22/22 — all green.
- [x] 5.4 CI green on HEAD `247fea0`: `lint` SUCCESS, `test` SUCCESS (24 new ReplyRateLimitTest scenarios + V8/V10 baselines all passing against the CI Postgres + Redis service containers), `migrate-supabase-parity` SUCCESS. (No `build` job — that was retired in PR #45 / commit `978acd1`; the build is now folded into the lint+test jobs.)

## 6. Smoke test against staging (post-CI-green)

- [x] 6.1 Created `dev/scripts/smoke-reply-rate-limit.sh` — adapted from the like precedent. Mints dev JWT via `mint-dev-jwt.sh`, fetches 21 visible posts from `/api/v1/timeline/global` (or accepts `--posts <comma-separated>` override), POSTs 21 replies with JSON body `{"content":"smoke reply <timestamp> #N"}`, asserts first 20 = 201 + 21st = 429 with `Retry-After` ≥ 60s + `error.code = "rate_limited"`. The ≥60s Retry-After lower-bound guards against fail-soft per task 6.4. Executable bit set; `bash -n` syntax check green.
- [ ] 6.2 Seed ≥21 visible posts authored by a *different* user (avoids self-reply edge cases on the global timeline).
- [ ] 6.3 Run the smoke against the latest staging revision after CI green. Confirm the 21st response carries `Retry-After` matching `computeTTLToNextReset(userId)` for that specific synthetic user (within ±5s).
- [ ] 6.4 If staging Redis is unreachable / fail-soft path triggers (per the lessons in `like-rate-limit` task 9.7), fix the underlying cause (TLS scheme, secret slot value, etc.) before declaring the smoke green — do NOT accept a "all 21 returned 201 because limiter fail-softed open" result as a successful smoke.

## 7. Doc sync (in the archive commit, not the feat commit)

- [ ] 7.1 Update [`docs/08-Roadmap-Risk.md`](../../../docs/08-Roadmap-Risk.md) Phase 2 item 3 — append a "Reply rate limit shipped" cross-reference covering the 20/day Free cap, `premium_reply_cap_override` flag, and the explicit "no burst" decision. Remove the "Reply rate limit (20/day Free) remains deferred" note.
- [ ] 7.2 Update [`docs/05-Implementation.md`](../../../docs/05-Implementation.md) § Rate Limiting Implementation — extend the "shipped" callout block (added by `like-rate-limit`) with the new reply-cap entry pointing at `openspec/specs/post-replies/spec.md`.
- [ ] 7.3 Verify [`docs/01-Business.md:53`](../../../docs/01-Business.md), [`docs/02-Product.md:224`](../../../docs/02-Product.md), and [`docs/05-Implementation.md:1740`](../../../docs/05-Implementation.md) Layer 2 row need NO edits — the canonical "Free 20/day, Premium unlimited" line already matches what's shipping. Confirm at archive time and document the no-op in the archive commit body.
- [ ] 7.4 Run `openspec archive reply-rate-limit --yes`. Expected output: `~ 1 modified` on `openspec/specs/post-replies/spec.md` (4 ADDED requirements appended). Per the one-PR-per-change convention codified in PR #38, this archive commit lands on the same `reply-rate-limit` branch as the proposal + feat commits — NOT a separate archive PR.
