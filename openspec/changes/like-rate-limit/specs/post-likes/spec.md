## ADDED Requirements

### Requirement: Daily rate limit — 10/day Free, unlimited Premium, with WIB stagger

`POST /api/v1/posts/{post_id}/like` SHALL enforce a per-user **daily** rate limit of 10 successful likes for Free-tier callers and unlimited for Premium-tier callers. The check runs via `RateLimiter.tryAcquire(userId, key, capacity, ttl)` against a Redis-backed counter keyed `{scope:rate_like_day}:{user:<user_id>}`.

The TTL MUST be supplied via `computeTTLToNextReset(userId)` so the per-user offset distributes resets across `00:00–01:00 WIB` (per [`docs/05-Implementation.md`](../../../docs/05-Implementation.md) §1751-1755). Hardcoding `Duration.ofDays(1)` or any other fixed duration at this call site is a `RateLimitTtlRule` Detekt violation.

Tier gating reads `users.subscription_status` (V3 schema column, three-state enum). Both `premium_active` and `premium_billing_retry` (the 7-day grace state) MUST skip the daily limiter entirely. Only `free` (and any future state mapping to free) is subject to the cap.

**Read-site constraint.** Tier MUST be read from the request-scope `viewer` principal populated by the auth-jwt plugin (which already loads `subscription_status` alongside the user identity for every authenticated request — see `auth-session` capability). The like handler MUST NOT issue a fresh `SELECT subscription_status FROM users WHERE id = :caller` before the limiter; doing so would violate the "limiter runs before any DB read" guarantee. If the auth principal does not carry `subscription_status`, that's a defect in the auth path and MUST be fixed there, not worked around by adding a DB read in this handler.

The daily limiter MUST run BEFORE any DB read (specifically, before the `visible_posts` resolution SELECT and the `post_likes` INSERT). On `RateLimited`, the response is HTTP 429 with body `{ "error": { "code": "rate_limited" } }` and a `Retry-After` header set to the seconds returned by the limiter (≥ 1).

The daily limiter MUST count INSERTs only — `DELETE /api/v1/posts/{post_id}/like` (unlike) MUST NOT consume a slot.

#### Scenario: 10 likes within a day succeed
- **WHEN** Free-tier caller A successfully likes 10 distinct visible posts within a single WIB day
- **THEN** all 10 responses are HTTP 204

#### Scenario: 11th like in same day rate-limited
- **WHEN** Free-tier caller A has 10 successful likes in the current WIB day AND attempts an 11th
- **THEN** the response is HTTP 429 with `error.code = "rate_limited"` AND a `Retry-After` header carrying a positive integer AND no `post_likes` row is inserted

#### Scenario: Retry-After approximates seconds to next reset
- **WHEN** the 11th request is rejected at WIB time `T`
- **THEN** the `Retry-After` value is approximately the number of seconds from `T` to (next 00:00 WIB) + (`hash(A.id) % 3600` seconds), within ±5 seconds (tolerance widened from ±2s to absorb CI runner clock + Redis `TIME` skew)

#### Scenario: Premium user not gated by daily cap
- **WHEN** caller A has `users.subscription_status = 'premium_active'` AND attempts a 25th like in a single WIB day
- **THEN** the response is HTTP 204 AND no daily-limiter check increments a counter for A (the daily limiter MUST be skipped, not consulted-and-overridden)

#### Scenario: Premium billing retry still treated as Premium
- **WHEN** caller A has `users.subscription_status = 'premium_billing_retry'` AND attempts a 15th like in a single WIB day
- **THEN** the response is HTTP 204 AND the daily limiter is skipped

#### Scenario: Daily key uses hash-tag format
- **WHEN** the daily limiter check runs against Redis for caller `A` (uuid `U`)
- **THEN** the key used is exactly `"{scope:rate_like_day}:{user:U}"`

#### Scenario: Unlike does not consume a daily slot
- **WHEN** Free-tier caller A has 9 successful likes today AND calls `DELETE /api/v1/posts/{post_id}/like` once AND then calls `POST /like` on a fresh post
- **THEN** the `POST /like` returns HTTP 204 AND the bucket holds 10 entries (the unlike did not deplete the cap)

#### Scenario: Daily limiter runs before visible_posts resolution
- **WHEN** Free-tier caller A is at slot 11 AND attempts `POST /like` on a post that ALSO does not exist in `visible_posts`
- **THEN** the response is HTTP 429 with `error.code = "rate_limited"` (NOT 404 `post_not_found`) AND no `visible_posts` SELECT was executed

#### Scenario: WIB day rollover restores the cap
- **WHEN** Free-tier caller A is at slot 10 at WIB `23:59:00` AND a like is attempted at WIB `00:00:01 + (hash(A.id) % 3600)` seconds the next day
- **THEN** the response is HTTP 204 (the user-specific reset window has passed AND old entries are pruned)

### Requirement: Burst rate limit — 500/hour for both tiers

`POST /api/v1/posts/{post_id}/like` SHALL enforce a per-user **burst** rate limit of 500 successful likes per rolling 1-hour window, applied to **both** Free and Premium tiers (per [`docs/02-Product.md:223`](../../../docs/02-Product.md): "Both tiers cap 500/hour burst (anti-bot)").

The check runs via `RateLimiter.tryAcquire(userId, key, capacity = 500, ttl = Duration.ofHours(1))` against a Redis-backed counter keyed `{scope:rate_like_burst}:{user:<user_id>}`. The TTL is fixed at 1 hour — `computeTTLToNextReset` MUST NOT be used here (the burst window is rolling, not staggered-daily).

The burst limiter MUST run AFTER the daily limiter (so a Free user at the 11th daily request gets 429-rate_limited from the daily limiter, not the burst). The burst limiter MUST also run BEFORE any DB read.

On `RateLimited`, the response is HTTP 429 with body `{ "error": { "code": "rate_limited" } }` and a `Retry-After` header set to the seconds returned by the burst limiter (≥ 1).

The burst limiter MUST count INSERTs only — unlike (`DELETE`) MUST NOT consume a slot.

#### Scenario: 500 likes within an hour succeed for Premium
- **WHEN** Premium caller A successfully likes 500 distinct visible posts within a 60-minute window
- **THEN** all 500 responses are HTTP 204

#### Scenario: 501st like in same hour rate-limited (Premium)
- **WHEN** Premium caller A has 500 successful likes in the last hour AND attempts a 501st
- **THEN** the response is HTTP 429 with `error.code = "rate_limited"` AND a `Retry-After` header carrying a positive integer AND no `post_likes` row is inserted

#### Scenario: 501st like in same hour rate-limited (Free, if cap allows)
- **WHEN** Free caller A has the override `premium_like_cap_override = 600` set AND has 500 successful likes in the last hour
- **THEN** a 501st like is rejected with HTTP 429 from the burst limiter (the daily cap was raised but the burst is unchanged)

#### Scenario: Burst key uses hash-tag format
- **WHEN** the burst limiter check runs against Redis for caller `A` (uuid `U`)
- **THEN** the key used is exactly `"{scope:rate_like_burst}:{user:U}"`

#### Scenario: Burst Retry-After reflects oldest counted like
- **WHEN** the 501st request is rejected
- **THEN** the `Retry-After` value is approximately the number of seconds until the oldest counted like ages out of the 60-minute window, within ±5 seconds (CI tolerance per the daily limiter's matching scenario above)

#### Scenario: Burst applies to Free at the per-day cap intersection
- **WHEN** Free caller A is at the 10/day daily cap
- **THEN** the daily limiter rejects FIRST and the burst limiter is NOT consulted (no burst counter increment for the rejected request)

### Requirement: `premium_like_cap_override` Firebase Remote Config flag

The like service SHALL read the Firebase Remote Config flag `premium_like_cap_override` on every `POST /api/v1/posts/{post_id}/like` request from a Free-tier caller (Premium calls skip the daily limiter entirely, so the flag is irrelevant for them).

When the flag is unset, malformed, ≤ 0, or unavailable due to Remote Config error: the daily cap is `10` (the canonical default). When the flag is set to a positive integer N: the daily cap is `N`. The flag MUST NOT affect the burst limit (500/hour stays fixed for both tiers per the previous requirement).

The flag is intended for ops-side adjustment per Decision 28 in [`docs/08-Roadmap-Risk.md`](../../../docs/08-Roadmap-Risk.md): tightening on anti-abuse signals or loosening on D7 retention impact, both without a mobile release.

#### Scenario: Flag unset uses 10 default
- **WHEN** `premium_like_cap_override` is unset in Remote Config AND Free caller A attempts an 11th like in a day
- **THEN** the response is HTTP 429 (daily cap = 10)

#### Scenario: Flag = 20 raises the cap
- **WHEN** `premium_like_cap_override = 20` AND Free caller A has 20 successful likes today AND attempts a 21st like
- **THEN** the 20th like (within the override) returned HTTP 204 AND the 21st returns HTTP 429 with `error.code = "rate_limited"`

#### Scenario: Flag = 5 lowers the cap mid-day
- **WHEN** Free caller A has 7 successful likes today (under the original 10 cap) AND `premium_like_cap_override = 5` is set AND A attempts an 8th like
- **THEN** the response is HTTP 429 (the override applies at request time; users above the new cap are immediately rate-limited)

#### Scenario: Flag = 0 falls back to default
- **WHEN** `premium_like_cap_override = 0` (invalid) AND Free caller A attempts an 11th like in a day
- **THEN** the response is HTTP 429 (the cap remains 10, not 0)

#### Scenario: Flag malformed (non-integer string) falls back to default
- **WHEN** `premium_like_cap_override = "twenty"` (or any non-integer-parseable value returned by the Remote Config client) AND Free caller A attempts an 11th like in a day
- **THEN** the response is HTTP 429 (the cap remains 10) AND a Sentry WARN is logged identifying the malformed value (so ops can detect the misconfiguration)

#### Scenario: Remote Config network failure falls back to default
- **WHEN** the Remote Config SDK throws an `IOException` (or any error) when the like handler attempts to read `premium_like_cap_override` AND Free caller A attempts an 11th like in a day
- **THEN** the response is HTTP 429 (the cap defaults to 10) AND the request does NOT 5xx — Remote Config errors MUST NOT propagate into a user-facing 5xx since the safe default is conservative (the canonical 10/day cap)

#### Scenario: Flag does NOT affect Premium
- **WHEN** `premium_like_cap_override = 5` AND Premium caller A attempts an 8th like
- **THEN** the response is HTTP 204 (Premium skips the daily limiter entirely)

#### Scenario: Flag does NOT affect burst limit
- **WHEN** `premium_like_cap_override = 50` AND Free caller A has 50 successful likes today (newly within the override) AND has 500 likes in the last hour
- **THEN** the next like returns HTTP 429 from the burst limiter (the burst cap is unchanged at 500)

### Requirement: Idempotent re-like releases the slot

When `POST /api/v1/posts/{post_id}/like` resolves the `INSERT ... ON CONFLICT (post_id, user_id) DO NOTHING` and the INSERT affects **zero rows** (i.e., the caller had already liked the post), the handler MUST call `RateLimiter.releaseMostRecent` on **both** the daily and burst limiter keys before returning HTTP 204.

This mirrors the V9 reports 409-release precedent: a request that does not produce a state change MUST NOT consume a rate-limit slot.

The handler MUST NOT release on any other path:
- 401 unauthenticated → no slot consumed (auth runs before the limiter)
- 400 invalid_uuid → no slot consumed (UUID validation runs before the limiter)
- 404 post_not_found → slot WAS consumed (the limiter ran successfully; the 404 is a downstream business decision and counts toward the cap as anti-abuse) — DO NOT release
- 5xx server error → no release (operational risk; the slot is not the worst problem)
- successful new like (1 row INSERT'd) → no release; the slot stays consumed

#### Scenario: Re-like releases both limiter slots (Free)
- **WHEN** Free caller A has 5 successful likes today AND has already liked post P AND POSTs `/api/v1/posts/P/like` again
- **THEN** the response is HTTP 204 AND the `{scope:rate_like_day}:{user:A}` bucket size is 5 (unchanged) AND the `{scope:rate_like_burst}:{user:A}` bucket size is 5 (unchanged)

#### Scenario: Re-like releases burst slot only (Premium)
- **WHEN** Premium caller A (status `premium_active`) has 5 successful likes today AND has already liked post P AND POSTs `/api/v1/posts/P/like` again
- **THEN** the response is HTTP 204 AND the `{scope:rate_like_day}:{user:A}` bucket has never been written (size 0; the daily limiter was skipped per the Premium-tier-skip contract) AND the `{scope:rate_like_burst}:{user:A}` bucket size is 5 (unchanged — the burst slot was acquired and then released). The handler MUST call `releaseMostRecent` on the daily key as well; that call is a no-op on the empty bucket per the `RateLimiter` contract — verifying this scenario protects against a future regression where the handler skips the daily-release call for Premium and a non-empty daily bucket leaks slots.

#### Scenario: 404 post_not_found does NOT release
- **WHEN** Free caller A has 5 successful likes today AND POSTs `/like` on a non-existent post UUID
- **THEN** the response is HTTP 404 with `error.code = "post_not_found"` AND the daily bucket size is 6 (the slot was consumed before the 404 was decided) AND the burst bucket size is 6

#### Scenario: Successful new like does NOT release
- **WHEN** Free caller A has 5 successful likes today AND likes a fresh post P for the first time
- **THEN** the response is HTTP 204 AND the daily bucket size is 6 AND the burst bucket size is 6

#### Scenario: Re-like at the cap returns 204 without burning the cap
- **WHEN** Free caller A is at slot 10/10 daily AND re-likes a post they already liked
- **THEN** the response is HTTP 204 (because the request was decided as a no-op release before the next slot was needed) — verifying the limiter releases on the no-op path: bucket size stays 10

### Requirement: Limiter ordering and pre-DB execution

The like service SHALL run, in this exact order, on every `POST /api/v1/posts/{post_id}/like`:

1. Auth (existing `auth-jwt` plugin).
2. Path UUID validation (existing — 400 `invalid_uuid` on malformed path).
3. **Daily rate limiter** (`{scope:rate_like_day}:{user:<uuid>}`) — Free only; Premium skips. On `RateLimited`: 429 + Retry-After + STOP.
4. **Burst rate limiter** (`{scope:rate_like_burst}:{user:<uuid>}`) — both tiers. On `RateLimited`: 429 + Retry-After + STOP.
5. `visible_posts` resolution SELECT with bidirectional `user_blocks` exclusion (existing — 404 `post_not_found` on miss). 404 path does NOT release the limiter slots.
6. `INSERT ... ON CONFLICT DO NOTHING` into `post_likes`.
7. **If the INSERT affected 0 rows (re-like)**: call `releaseMostRecent` on both limiter keys.
8. Notification emit (V10 — only on transition from "not liked" to "liked"). The notification emit MUST NOT block the 204 response: it runs inside the same DB transaction as the `post_likes` INSERT (per the V10 strict-coupling contract), but the transaction commit MUST happen synchronously and the 204 MUST be returned as soon as the commit completes — any FCM push or downstream side-effect MUST be enqueued as fire-and-forget on the IO dispatcher, NOT awaited inline. Slow-emit attacker patterns (e.g., a Cloud Run thread held open by a long-tail FCM round-trip) MUST NOT consume request capacity.
9. Return 204.

Steps 3–4 MUST run before any DB query. The 401, 400, and rate-limit branches MUST NOT execute a DB statement.

#### Scenario: Auth failure short-circuits before limiters
- **WHEN** the request lacks a valid JWT
- **THEN** the response is HTTP 401 AND no limiter check runs (no Redis round-trip for auth-rejected requests)

#### Scenario: Invalid UUID short-circuits before limiters
- **WHEN** the request path is `POST /api/v1/posts/not-a-uuid/like`
- **THEN** the response is HTTP 400 with `error.code = "invalid_uuid"` AND no limiter check runs

#### Scenario: Daily limit hit before visible_posts query
- **WHEN** Free caller A is at slot 11 AND POSTs to a non-existent post UUID
- **THEN** the response is HTTP 429 (daily) AND no `visible_posts` SELECT was executed

#### Scenario: Burst limit hit before visible_posts query
- **WHEN** Premium caller A is at slot 501/hour AND POSTs to a non-existent post UUID
- **THEN** the response is HTTP 429 (burst) AND no `visible_posts` SELECT was executed

### Requirement: Integration test coverage — like rate limit

`LikeRateLimitTest` (tagged `database`, depending on the new CI Redis service container) SHALL exist alongside the existing `LikeEndpointsTest` and cover, at minimum:

1. 10 likes in a day succeed for Free user.
2. 11th like in the same day rate-limited; 429 + `Retry-After` + no row inserted.
3. `Retry-After` value within ±5s of expected (relative to `now`).
4. Premium user (status `premium_active`) skips the daily limiter — 25 likes in a day all succeed.
5. Premium billing retry status (`premium_billing_retry`) also skips the daily limiter.
6. 500/hour burst applies to Premium — 501st like rejected.
7. 500/hour burst applies to Free at the same threshold (with override raised to 600 to isolate the burst limiter).
8. `premium_like_cap_override` raises the cap to 20 for a Free user (21st rejected after a successful 20th).
9. `premium_like_cap_override` lowers the cap mid-day; user previously at 7 is rejected at 8.
10. `premium_like_cap_override = 0` falls back to default 10.
11. `premium_like_cap_override = "twenty"` (malformed non-integer) falls back to default 10 + Sentry WARN.
12. Remote Config network failure (SDK throws) falls back to default 10; no 5xx propagated.
13. Free re-like (already-liked post) returns 204 AND does NOT consume a daily or burst slot.
14. Premium re-like returns 204 AND the daily key remains empty (never written) AND the burst slot is released — protects against future regression where the handler skips the daily-release call for Premium.
15. 404 `post_not_found` consumes a slot (does NOT release).
16. Daily limit hit short-circuits before `visible_posts` SELECT (assert: hit a non-existent post UUID; expect 429 not 404).
17. Hash-tag key shape verified: daily key = `{scope:rate_like_day}:{user:<uuid>}`, burst = `{scope:rate_like_burst}:{user:<uuid>}`.
18. WIB rollover: at the per-user reset moment the cap restores.
19. Unlike does NOT consume any limiter slot.
20. Notification emit does not block the 204 response (assert: a like with a synthetically-slow downstream FCM stub still returns 204 within the request budget).
21. Tier (`subscription_status`) is read from the auth-time `viewer` principal, not via a DB SELECT — assert via a query-counter / Postgres statement-log spy that the like handler issues zero `users` SELECTs before the limiter check.

#### Scenario: Test class discoverable
- **WHEN** running `./gradlew :backend:ktor:test --tests '*LikeRateLimitTest*'`
- **THEN** the class is discovered AND every numbered scenario above corresponds to at least one `@Test` method
