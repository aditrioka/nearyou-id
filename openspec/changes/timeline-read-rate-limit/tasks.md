## 1. Foundation — DTO + ratelimiter scaffold

- [ ] 1.1 Add an `Upsell` DTO (`backend/ktor/src/main/kotlin/id/nearyou/app/timeline/Upsell.kt`) with optional `soft: Boolean? = null` and `hard: Boolean? = null` fields. Use `kotlinx.serialization` with omit-on-null serialization (`@EncodeDefault(EncodeDefault.Mode.NEVER)`) so a `null`-valued field is omitted from the serialized JSON entirely.
- [ ] 1.2 Extend the existing timeline response DTOs (`NearbyTimelineResponse`, `FollowingTimelineResponse`, `GlobalTimelineResponse`) to carry an optional `upsell: Upsell? = null` field with the same omit-on-null serialization. Verify the existing tests still pass (the field is additive).
- [ ] 1.3 Create `TimelineReadRateLimiter` class (`backend/ktor/src/main/kotlin/id/nearyou/app/timeline/TimelineReadRateLimiter.kt`) wrapping `RateLimiter` from `:core:domain`. Constructor takes `RateLimiter` + a logger. Public methods: `preCheck(viewer: UserPrincipal, sanitizedSessionId: String): PreCheckOutcome` and `postIncrement(viewer: UserPrincipal, sanitizedSessionId: String, postCount: Int)`.
- [ ] 1.4 `PreCheckOutcome` is a sealed interface: `Admit(softCapReached: Boolean)` (the request proceeds; `softCapReached` mirrors the session-pre-check `RateLimited` outcome and drives `upsell.soft`) and `HardCapped` (the request returns the empty + `upsell.hard = true` body).
- [ ] 1.5 Premium short-circuit inside `preCheck`: if `viewer.subscriptionStatus IN ("premium_active", "premium_billing_retry")`, return `Admit(softCapReached = false)` immediately with zero Redis calls. Add a structural test fixture verifying the no-Redis path via a recording `RateLimiter` spy.
- [ ] 1.6 `postIncrement` short-circuits identically for Premium (no Redis calls).
- [ ] 1.7 Wire `TimelineReadRateLimiter` into the Koin module for `:backend:ktor` (single-instance binding scoped per request not required — the limiter is stateless above the Redis seam).

## 2. X-Session-Id validation + sanitization

- [ ] 2.1 Add a `SessionIdSanitizer` helper (or a private function on `TimelineReadRateLimiter`) that takes the raw `X-Session-Id` header value (nullable) and returns a sanitized session-id. Behavior: if value is null or empty → `"no-session"`. If value matches `^[A-Za-z0-9-]{1,64}$` → return value unchanged. Else → `"no-session"`. The regex is case-sensitive.
- [ ] 2.2 Add a unit test for `SessionIdSanitizer` covering: null → `no-session`, empty string → `no-session`, valid UUIDv4 → unchanged, length-65 string → `no-session`, value containing `}` → `no-session`, value containing `{` → `no-session`, value containing `:` → `no-session`, value containing whitespace → `no-session`, value containing control char → `no-session`, value of length 64 (exact boundary) → unchanged.
- [ ] 2.3 The validated session-id is passed into key construction; raw header values MUST NOT bypass the sanitizer.

## 3. Hash-tag key construction

- [ ] 3.1 Implement key constructor helpers in `TimelineReadRateLimiter`: `private fun rollingKey(userId: UUID): String = "{scope:rate_timeline_rolling}:{user:$userId}"` and `private fun sessionKey(userId: UUID, sanitizedSessionId: String): String = "{scope:rate_timeline_session}:{session:${userId}__$sanitizedSessionId}"`.
- [ ] 3.2 Verify the keys conform to `RedisHashTagRule` strict regex `^\{scope:[^{}]+}:\{[^{}:]+:[^{}]+}$` by adding two `RedisHashTagRuleTest` fixtures: one literal `"{scope:rate_timeline_rolling}:{user:U}"` (does NOT fire) and one literal `"{scope:rate_timeline_session}:{session:U__SID}"` (does NOT fire). These guard against future rule tightening regressing the timeline keys.
- [ ] 3.3 Add a unit test asserting Lettuce CRC16 slot equivalence between the two hash-tag pairs (`{scope:rate_timeline_rolling}` vs `{user:U}`) and (`{scope:rate_timeline_session}` vs `{session:U__SID}`) for representative UUIDs — verifies cluster-safe co-location on Upstash production.

## 4. Pre-check + post-increment logic

- [ ] 4.1 `preCheck(viewer, sanitizedSessionId)` invokes `tryAcquire(viewer.userId, rollingKey(viewer.userId), capacity = 150, ttl = Duration.ofHours(1))` first. If `RateLimited`, return `HardCapped` (do NOT consult the session bucket — the response is going to be empty regardless; saves one Redis round-trip).
- [ ] 4.2 If rolling pre-check returned `Allowed`, invoke `tryAcquire(viewer.userId, sessionKey(viewer.userId, sanitizedSessionId), capacity = 50, ttl = Duration.ofHours(1))`. The outcome is recorded but NEVER blocks: `Allowed` → `softCapReached = false`; `RateLimited` → `softCapReached = true`. Return `Admit(softCapReached)`.
- [ ] 4.3 `postIncrement(viewer, sanitizedSessionId, postCount)` issues `(postCount - 1).coerceAtLeast(0)` parallel best-effort `tryAcquire` calls on each of the rolling and session buckets via `coroutineScope { (1 until postCount).map { async(Dispatchers.IO) { ... } }.awaitAll() }`. The outcome of each call is ignored. Skip entirely if `postCount <= 1` (1 post = 1 slot already consumed at pre-check; zero or one post means no extra increments needed).
- [ ] 4.4 Premium short-circuit re-asserted in `postIncrement`: if Premium, do NOTHING (no Redis calls).
- [ ] 4.5 Add a unit test verifying the post-increment loop count: a `postCount = 30` invocation issues exactly 29 rolling-`tryAcquire` + 29 session-`tryAcquire` calls (= 58 calls total). A `postCount = 0` invocation issues 0 calls. A `postCount = 1` invocation issues 0 additional calls (only the pre-check's 1 slot already counted).

## 5. Wire into Nearby route

- [ ] 5.1 Modify `NearbyTimelineRoute` (or wherever the existing `GET /api/v1/timeline/nearby` is registered) to inject `TimelineReadRateLimiter`.
- [ ] 5.2 Read the `X-Session-Id` header (nullable), pass through `SessionIdSanitizer` to obtain the sanitized session-id.
- [ ] 5.3 Insert the pre-check call AFTER `auth-jwt` validation + cursor parsing but BEFORE the `NearbyTimelineService.fetchTimeline(...)` call.
- [ ] 5.4 On `HardCapped`: return HTTP 200 with body `NearbyTimelineResponse(posts = emptyList(), nextCursor = null, upsell = Upsell(hard = true))`. Do NOT execute the service call.
- [ ] 5.5 On `Admit`: execute the existing service call; build the response. After the response object is built but BEFORE returning, call `postIncrement(viewer, sanitizedSessionId, response.posts.size)` and set `response.upsell` per the `Admit.softCapReached` flag (omit `upsell` entirely if both flags are false).
- [ ] 5.6 Verify the existing Nearby integration tests (`NearbyTimelineServiceTest`) still pass — none assert on the absence of an `upsell` field, so additive shape is OK.

## 6. Wire into Following route

- [ ] 6.1 Same as task 5 but for `FollowingTimelineRoute` / `FollowingTimelineService`.
- [ ] 6.2 On rolling `HardCapped` for Following: return `FollowingTimelineResponse(posts = emptyList(), nextCursor = null, upsell = Upsell(hard = true))` (note: Following's per-post shape lacks `distance_m`; the empty-posts response is identical regardless).
- [ ] 6.3 Verify `FollowingTimelineServiceTest` still passes.

## 7. Wire into Global route

- [ ] 7.1 Same as task 5 but for `GlobalTimelineRoute` / `GlobalTimelineService`.
- [ ] 7.2 On rolling `HardCapped`: return `GlobalTimelineResponse(posts = emptyList(), nextCursor = null, upsell = Upsell(hard = true))`. Confirm guest access remains NOT wired (the route stays authenticated-only per `global-timeline` § "Guest endpoint still NOT wired in this change" scenario).
- [ ] 7.3 Verify `GlobalTimelineServiceTest` still passes.

## 8. Integration test class — TimelineReadRateLimitTest

- [ ] 8.1 Create `backend/ktor/src/test/kotlin/id/nearyou/app/timeline/TimelineReadRateLimitTest.kt` tagged `database`. The class wires the full `Application.module()` against the Postgres + Redis service-container test environment via `testApplication { application { module() } }` (mirrors the existing `LikeRateLimitTest` / `ReplyRateLimitTest` / `ChatRateLimitTest` patterns at `backend/ktor/src/test/kotlin/id/nearyou/app/{engagement,chat,moderation}/*RateLimitTest.kt`).
- [ ] 8.2 Helper: `seedFreeUserWithBucketState(userId, sanitizedSessionId, rollingCount, sessionCount)` that pre-loads the Redis buckets via direct `ZADD` against the canonical key shapes. The fixture KDoc MUST note that this couples to the shipped Lua sliding-window's score semantics (`now_ms` score, `UUID.randomUUID()` member); a future change to the Lua script's score format triggers a fixture rebuild. Cap `sessionCount` parameter to `[0, 50]` and `rollingCount` to `[0, 150]` matching the production primitive's invariants.
- [ ] 8.3 Helper: `ageBucketEntries(key, count, ageMs)` that injects `count` ZSET entries with score `now_ms - ageMs` to simulate aged-out entries. Same KDoc warning as 8.2.
- [ ] 8.4 Helper: a Postgres statement-log spy or query-counter that records `posts` SELECTs issued during a request (verifies pre-check before-DB short-circuit).
- [ ] 8.5 Helper: a Redis-counter spy that records every `tryAcquire` call by key (verifies Premium short-circuit + key shape + post-increment count).
- [ ] 8.6 Implement scenario 1 (rolling cap basic): 5 reads of 30 posts each succeed; 6th returns empty + `upsell.hard = true` + HTTP 200.
- [ ] 8.7 Implement scenario 2 (cross-endpoint accumulation with realistic 30-page boundaries): 60 posts on Nearby (2 pages) + 60 on Following (2 pages) + 30 on Global (1 page) → 6th request capped.
- [ ] 8.8 Implement scenario 3 (200 not 429): assert HTTP status code is exactly 200 + no `Retry-After` header on hard-cap.
- [ ] 8.9 Implement scenario 4 (rolling aging via score-injection helper): pre-load bucket via `ageBucketEntries(key, 150, 3600001)`, fresh read succeeds with non-empty posts.
- [ ] 8.10 Implement scenarios 5, 6, 7 (49/50/51 boundary): 49th delivery → no soft; 50th delivery → no soft (bucket fills exactly to 50/50); 51st delivery → soft fires.
- [ ] 8.11 Implement scenarios 8, 9 (soft cap non-blocking; per-session independence).
- [ ] 8.12 Implement scenarios 10, 11, 12 (Premium bypass: rolling, billing retry, both buckets — Redis-counter spy assertion shows zero rate-limit Redis writes for Premium across multiple requests).
- [ ] 8.13 Implement scenario 13 (tier from auth principal — query-counter assertion: zero `users` SELECTs in rate-limit pre-check path).
- [ ] 8.14 Implement scenario 14 (pre-check before DB on cap-hit — non-existent post UUID still returns empty + upsell.hard, zero `posts` SELECTs).
- [ ] 8.15 Implement scenario 15 (hash-tag key shapes verified via Redis-spy + Lettuce `SlotHash.getSlot(key)` assertion + `RedisHashTagRule` strict-regex match assertion).
- [ ] 8.16 Implement scenarios 16, 17 (X-Session-Id missing + accumulating).
- [ ] 8.17 Implement scenarios 18, 19, 20 (X-Session-Id malformed-with-brace + oversized + UUID-shape acceptance).
- [ ] 8.18 Implement scenario 21 (mixed valid-SID + no-session bucket independence).
- [ ] 8.19 Implement scenarios 22, 23 (auth + cursor short-circuit before limiter — zero rate-limit Redis calls).
- [ ] 8.20 Implement scenario 24 (empty Following with zero follows — pre-check consumed exactly 1 slot, post-increment skipped because `(0-1).coerceAtLeast(0) = 0` calls).
- [ ] 8.21 Implement scenarios 25, 26 (shadow-banned Free hits cap; shadow-banned Premium uncapped).
- [ ] 8.22 Implement scenarios 27, 28 (`upsell` omitted when both flags false; `upsell.hard` doesn't carry `soft` because session pre-check was never executed).
- [ ] 8.23 Implement scenario 29 (single-request over-admission tolerance: caller at 121/150 reads 30 posts → succeeds; bucket reaches 150/150; next request hard-capped).
- [ ] 8.24 Implement scenario 30 (concurrent over-admission via parallel coroutines: 3 simultaneous requests at 148/150 → all 3 succeed; bucket ends at 150/150; next request hard-capped).
- [ ] 8.25 Implement scenario 31 (cross-tab same-user multi-session accumulation: 5 sessions × 30 posts → rolling cap reached even though no individual session bucket exceeded 30/50).
- [ ] 8.26 Implement scenario 32 (stale principal Premium bypass: auth-time `premium_active` even though DB row was downgraded mid-flight to `free` → bypasses both caps for the JWT lifetime).

## 9. Lint + ktlint + detekt verification

- [ ] 9.1 Run `./gradlew ktlintCheck detekt` locally and resolve any new findings introduced by the timeline-rate-limit code.
- [ ] 9.2 Confirm `RedisHashTagRule` does NOT fire on the new key construction sites (the keys conform to the strict `{scope:<value>}:{<axis>:<value>}` regex). Verified by tasks 3.2's positive fixtures.
- [ ] 9.3 Confirm `RateLimitTtlRule` does NOT fire on the new `tryAcquire` calls (no `_day}` substring in the keys; `Duration.ofHours(1)` is the TTL).
- [ ] 9.4 Confirm `BlockExclusionJoinRule` continues to pass on all three timeline route files (the rate-limit gate does not touch the canonical SQL queries; the existing rule applies to the unchanged service-layer queries).
- [ ] 9.5 Confirm `RawFromPostsRule` continues to pass (no raw `FROM posts` introduced).

## 10. Documentation maintenance

- [ ] 10.1 Verify no canonical `docs/` amendments are needed — the proposal aligns with `docs/05-Implementation.md:799-806` + `:1159-1160` semantically. The implementation renders the canonical informal `timeline_rolling:{user:U}` / `timeline_offset:{user:U}:<sid>` shapes in the strict hash-tag form per the `RedisHashTagRule` infrastructure standard, identical to how `like-rate-limit` rendered `{scope:rate_like_day}:{user:U}` despite informal docs references — no docs amendment needed.
- [ ] 10.2 (Optional) If `docs/10-Setup-Checklist.md` has a Phase 1 #30 row marked deferred, tick it post-archive (mechanical doc-maintenance per `openspec/project.md` § Documentation Maintenance).
- [ ] 10.3 Confirm no FOLLOW_UPS.md entries are silently resolved by this change — none of the 22 open entries name timeline-read rate limiting; this is a fresh capability landing, not a follow-up promotion.

> **Note on staging deploy + smoke verification**: pre-archive smoke is owned by `/opsx:apply` Section 6 (per [`openspec/project.md`](../../../openspec/project.md) § Staging deploy timing convention). It runs against a manual `deploy-staging.yml` branch deploy + a `dev/scripts/smoke-timeline-read-rate-limit.sh` helper. Do not duplicate the smoke checklist here — `/opsx:apply` will track those steps when the implementation phase begins.
