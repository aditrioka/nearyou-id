## 1. Foundation — DTO + ratelimiter scaffold

- [ ] 1.1 Add an `Upsell` DTO (`backend/ktor/src/main/kotlin/id/nearyou/app/timeline/Upsell.kt`) with optional `soft: Boolean? = null` and `hard: Boolean? = null` fields. Use `kotlinx.serialization` with `JsonElement` strict-omit (`@EncodeDefault(EncodeDefault.Mode.NEVER)`) so a `null`-valued field is omitted from the serialized JSON entirely.
- [ ] 1.2 Extend the existing timeline response DTOs (`NearbyTimelineResponse`, `FollowingTimelineResponse`, `GlobalTimelineResponse`) to carry an optional `upsell: Upsell? = null` field with the same omit-on-null serialization. Verify the existing tests still pass (the field is additive).
- [ ] 1.3 Create `TimelineReadRateLimiter` class (`backend/ktor/src/main/kotlin/id/nearyou/app/timeline/TimelineReadRateLimiter.kt`) wrapping `RateLimiter` from `:core:domain`. Constructor takes `RateLimiter` + a logger. Public methods: `preCheck(viewer: UserPrincipal, sessionId: String?): PreCheckOutcome` and `postIncrement(viewer: UserPrincipal, sessionId: String?, postCount: Int)`.
- [ ] 1.4 `PreCheckOutcome` is a sealed interface: `Admit(softCapReached: Boolean)` (the request proceeds; `softCapReached` mirrors the post-increment session-bucket-saturation state for the response `upsell.soft` flag) and `HardCapped` (the request returns the empty + `upsell.hard = true` body).
- [ ] 1.5 Premium short-circuit inside `preCheck`: if `viewer.subscriptionStatus IN ("premium_active", "premium_billing_retry")`, return `Admit(softCapReached = false)` immediately with zero Redis calls. Add a structural test fixture verifying the no-Redis path via a recording `RateLimiter` spy.
- [ ] 1.6 `postIncrement` short-circuits identically for Premium (no Redis calls).
- [ ] 1.7 Wire `TimelineReadRateLimiter` into the Koin module for `:backend:ktor` (single-instance binding scoped per request not required — the limiter is stateless above the Redis seam).

## 2. Hash-tag key construction

- [ ] 2.1 Implement key constructor helpers in `TimelineReadRateLimiter`: `private fun rollingKey(userId: UUID): String = "{scope:rate_timeline_rolling}:{user:$userId}"` and `private fun sessionKey(userId: UUID, sessionId: String?): String = "{scope:rate_timeline_session}:{user:$userId}:${sessionId ?: "no-session"}"`.
- [ ] 2.2 Verify the keys conform to `RedisHashTagRule` by adding a unit-level assertion (the rule fires on non-conforming literals; passing `./gradlew :lint:detekt-rules:test :backend:ktor:detekt` is the structural verification).
- [ ] 2.3 Add a unit test asserting Lettuce CRC16 slot equivalence between the two hash-tag pairs (`{scope:rate_timeline_rolling}` vs `{user:U}`) and (`{scope:rate_timeline_session}` vs `{user:U}`) for representative UUIDs — verifies cluster-safe co-location on Upstash production.

## 3. Pre-check + post-increment logic

- [ ] 3.1 `preCheck` invokes `tryAcquire(viewer.userId, rollingKey(viewer.userId), capacity = 150, ttl = Duration.ofHours(1))` first. If `RateLimited`, return `HardCapped` (do NOT consult the session bucket — the response is going to be empty regardless; saves one Redis round-trip).
- [ ] 3.2 If rolling pre-check returned `Allowed`, invoke `tryAcquire(viewer.userId, sessionKey(viewer.userId, sid), capacity = 50, ttl = Duration.ofHours(1))`. The outcome is recorded but NEVER blocks: `Allowed` → `softCapReached = false`; `RateLimited` → `softCapReached = true`. Return `Admit(softCapReached)`.
- [ ] 3.3 `postIncrement(viewer, sid, postCount)` issues `(postCount - 1)` parallel `tryAcquire` calls on each of the rolling and session buckets (total `2 * (postCount - 1)`) via `coroutineScope { (1 until postCount).map { async(Dispatchers.IO) { ... } }.awaitAll() }`. The outcome of each call is ignored. Skip entirely if `postCount <= 1` (1 post = 1 slot already consumed at pre-check; zero or one post means no extra increments needed).
- [ ] 3.4 Premium short-circuit re-asserted in `postIncrement`: if Premium, do NOTHING (no Redis calls).
- [ ] 3.5 Add a unit test verifying the post-increment loop count: a `postCount = 30` invocation issues exactly 29 rolling-tryAcquire + 29 session-tryAcquire calls (= 58 calls total).

## 4. Wire into Nearby route

- [ ] 4.1 Modify `NearbyTimelineRoute` (or wherever the existing `GET /api/v1/timeline/nearby` is registered) to inject `TimelineReadRateLimiter`.
- [ ] 4.2 Insert the pre-check call AFTER `auth-jwt` validation + cursor parsing but BEFORE the `NearbyTimelineService.fetchTimeline(...)` call.
- [ ] 4.3 On `HardCapped`: return HTTP 200 with body `NearbyTimelineResponse(posts = emptyList(), nextCursor = null, upsell = Upsell(hard = true))`. Do NOT execute the service call.
- [ ] 4.4 On `Admit`: execute the existing service call; build the response. After the response object is built but BEFORE returning, call `postIncrement(viewer, sid, response.posts.size)` and set `response.upsell` per the `Admit.softCapReached` flag (omit `upsell` entirely if both flags are false).
- [ ] 4.5 Verify the existing Nearby integration tests (`NearbyTimelineServiceTest`) still pass — none assert on the absence of an `upsell` field, so additive shape is OK.

## 5. Wire into Following route

- [ ] 5.1 Same as task 4 but for `FollowingTimelineRoute` / `FollowingTimelineService`.
- [ ] 5.2 On rolling `HardCapped` for Following: return `FollowingTimelineResponse(posts = emptyList(), nextCursor = null, upsell = Upsell(hard = true))` (note: Following's per-post shape lacks `distance_m`; the empty-posts response is identical regardless).
- [ ] 5.3 Verify `FollowingTimelineServiceTest` still passes.

## 6. Wire into Global route

- [ ] 6.1 Same as task 4 but for `GlobalTimelineRoute` / `GlobalTimelineService`.
- [ ] 6.2 On rolling `HardCapped`: return `GlobalTimelineResponse(posts = emptyList(), nextCursor = null, upsell = Upsell(hard = true))`. Confirm guest access remains NOT wired (the route stays authenticated-only per `global-timeline` § "Guest endpoint still NOT wired in this change" scenario).
- [ ] 6.3 Verify `GlobalTimelineServiceTest` still passes.

## 7. Integration test class — TimelineReadRateLimitTest

- [ ] 7.1 Create `backend/ktor/src/test/kotlin/id/nearyou/app/timeline/TimelineReadRateLimitTest.kt` tagged `database`. The class wires the full `Application.module()` against the Postgres + Redis service-container test environment; uses `testApplication { application { module() } }` per the existing chat / report rate-limit test pattern.
- [ ] 7.2 Helper: a fixture method `seedFreeUserWithBucketState(userId, rollingCount, sessionCount, sessionId)` that pre-loads the Redis buckets via direct Lua-script-equivalent ZADDs to set up the desired test state.
- [ ] 7.3 Helper: a Postgres statement-log spy or query-counter that records `posts` SELECTs issued during a request (verifies pre-check before-DB short-circuit).
- [ ] 7.4 Helper: a Redis-counter spy that records every `tryAcquire` call by key (verifies Premium short-circuit + key shape + post-increment count).
- [ ] 7.5 Implement scenario 1 (rolling cap basic): 5 reads of 30 posts each succeed; 6th returns empty + `upsell.hard = true`.
- [ ] 7.6 Implement scenario 2 (cross-endpoint accumulation): 50 on Nearby + 50 on Following + 50 on Global → 4th request capped.
- [ ] 7.7 Implement scenario 3 (200 not 429): assert HTTP status code is exactly 200 + no `Retry-After` header on hard-cap.
- [ ] 7.8 Implement scenario 4 (rolling aging): age the bucket via `ZADD` with old scores, fresh read succeeds.
- [ ] 7.9 Implement scenarios 5, 6, 7 (soft cap basic, non-blocking, per-session independence).
- [ ] 7.10 Implement scenarios 8, 9 (Premium rolling bypass; Premium billing retry bypass).
- [ ] 7.11 Implement scenario 10 (Premium short-circuits both buckets — Redis spy assertion).
- [ ] 7.12 Implement scenario 11 (tier from auth principal — query-counter assertion: zero `users` SELECTs in rate-limit pre-check path).
- [ ] 7.13 Implement scenario 12 (pre-check before DB on cap-hit — non-existent post UUID still returns empty + upsell.hard, zero `posts` SELECTs).
- [ ] 7.14 Implement scenario 13 (hash-tag key shapes verified via Redis-spy + CRC16 assertion).
- [ ] 7.15 Implement scenarios 14, 15, 16 (X-Session-Id missing fallback: succeeds, accumulates, mixed-bucket independence).
- [ ] 7.16 Implement scenarios 17, 18 (auth + cursor short-circuit before limiter — zero rate-limit Redis calls).
- [ ] 7.17 Implement scenario 19 (empty Following with zero follows still consumes 1 rolling slot via pre-check).
- [ ] 7.18 Implement scenarios 20, 21 (shadow-banned Free hits cap; shadow-banned Premium uncapped).
- [ ] 7.19 Implement scenarios 22, 23 (`upsell` omitted when both flags false; `upsell.hard` doesn't carry `soft`).
- [ ] 7.20 Implement scenario 24 (over-admission tolerance: caller at 149/150 reads 30 posts → succeeds; next request hard-capped).
- [ ] 7.21 Implement scenario 25 (cross-tab same-user multi-session accumulation: 3 sessions × 50 posts → rolling cap reached even though no session bucket reached 50).

## 8. Lint + ktlint + detekt verification

- [ ] 8.1 Run `./gradlew ktlintCheck detekt` locally and resolve any new findings introduced by the timeline-rate-limit code.
- [ ] 8.2 Confirm `RedisHashTagRule` does NOT fire on the new key construction sites (the keys conform to `{scope:<value>}:{<axis>:<value>}`).
- [ ] 8.3 Confirm `RateLimitTtlRule` does NOT fire on the new `tryAcquire` calls (no `_day}` substring in the keys; `Duration.ofHours(1)` is the TTL).
- [ ] 8.4 Confirm `BlockExclusionJoinRule` continues to pass on all three timeline route files (the rate-limit gate does not touch the canonical SQL queries; the existing rule applies to the unchanged service-layer queries).
- [ ] 8.5 Confirm `RawFromPostsRule` continues to pass (no raw `FROM posts` introduced).

## 9. Pre-archive smoke verification (defer to /opsx:apply Section 6)

- [ ] 9.1 (Deferred to `/opsx:apply` execution) Run `gh workflow run deploy-staging.yml --ref timeline-read-rate-limit` to deploy the change to staging on the branch.
- [ ] 9.2 (Deferred) Poll the deploy run via `gh run list -w deploy-staging.yml -b timeline-read-rate-limit -L 1 --json status,conclusion,databaseId` until status `completed` + conclusion `success`.
- [ ] 9.3 (Deferred) Author `dev/scripts/smoke-timeline-read-rate-limit.sh` that hits a staging-promoted Free user with 5+ timeline reads of 30 posts each (= 150+ posts), verifies the 6th response carries `upsell.hard = true`, AND a separate Premium-promoted account makes 5+ reads with no `upsell` field. The smoke script should clean up its Redis state on exit (Lettuce DEL on the test user's bucket keys).
- [ ] 9.4 (Deferred) Run the smoke script against the staging branch deploy; tick this section's tasks; proceed to `/opsx:archive`.

## 10. Documentation maintenance

- [ ] 10.1 Verify no canonical `docs/` amendments are needed — the proposal aligns with `docs/05-Implementation.md:799-806` + `:1159-1160` verbatim. No-op task: confirm by re-reading those line ranges post-implementation.
- [ ] 10.2 (Optional) If `docs/10-Setup-Checklist.md` has a Phase 1 #30 row marked deferred, tick it post-archive (mechanical doc-maintenance per `openspec/project.md` § Documentation Maintenance).
- [ ] 10.3 Confirm no FOLLOW_UPS.md entries are silently resolved by this change — none of the 22 open entries name timeline-read rate limiting; this is a fresh capability landing, not a follow-up promotion.
