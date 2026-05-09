## ADDED Requirements

### Requirement: Per-user rolling 150-posts-per-hour hard cap on the three authenticated timeline endpoints

`GET /api/v1/timeline/{nearby,following,global}` SHALL enforce a per-user rolling **hard** rate limit of 150 successful post-deliveries per 1-hour window for Free-tier callers, and unlimited for Premium-tier callers. The cap aggregates across ALL THREE timeline endpoints into a single per-user bucket: a user reading 50 posts on Nearby + 50 on Following + 50 on Global = 150 posts → cap reached.

The check runs via `RateLimiter.tryAcquire(userId, key, capacity = 150, ttl = Duration.ofHours(1))` against a Redis-backed counter keyed `{scope:rate_timeline_rolling}:{user:<user_id>}`. The TTL MUST be `Duration.ofHours(1)` directly — `computeTtlToNextReset` MUST NOT be used (the rolling window is hourly, not staggered-daily). The `RateLimitTtlRule` Detekt rule does NOT fire on this key (no `_day}` marker).

Tier gating reads `users.subscription_status` from the auth-time `viewer` principal populated by the `auth-jwt` plugin (the principal already carries `subscriptionStatus` per the `auth-jwt-principal-fields-documentation` capability). Both `premium_active` and `premium_billing_retry` MUST skip the rolling limiter entirely (no Redis call). Only `free` is subject to the cap. The handler MUST NOT issue a fresh `SELECT subscription_status FROM users` before the limiter.

The rolling limiter MUST run BEFORE the timeline DB query. On `RateLimited`, the response is HTTP **200** (NOT 429) with body `{ "posts": [], "next_cursor": null, "upsell": { "hard": true } }`. The `Retry-After` header MUST NOT be set (the soft "you've hit your hourly limit" UX is preferred over a hard error).

The bucket MUST be incremented by `min(N, remaining_capacity)` entries on a successful response, where `N` is the count of posts returned (`response.posts.length`). The pre-check at request time consumes 1 slot; the remaining `N - 1` slots are bumped via best-effort additional `tryAcquire` calls after the response is built. Over-admission is bounded by `N - 1` ≤ 29 per request (one per-page cap is 30); the user's NEXT request after over-admission is cap-blocked.

#### Scenario: 5 page-of-30 reads in an hour (Free) succeed
- **WHEN** Free-tier caller A successfully reads 5 timeline pages of 30 posts each (= 150 posts) within a 60-minute window across any combination of Nearby/Following/Global
- **THEN** all 5 responses are HTTP 200 with non-empty `posts` arrays AND no `upsell.hard` flag

#### Scenario: 6th read in same hour rate-limited (Free)
- **WHEN** Free-tier caller A has 150 successful post-deliveries in the last hour AND attempts a 6th timeline read
- **THEN** the response is HTTP 200 with `posts = []` AND `next_cursor = null` AND `upsell.hard = true` AND no `Retry-After` header AND no Postgres `posts` SELECT was executed

#### Scenario: Premium user not gated by rolling cap
- **WHEN** caller A has `users.subscription_status = 'premium_active'` AND attempts a 200th post-delivery (across any timeline) in a single hour
- **THEN** the response is HTTP 200 with `posts.length > 0` (DB-permitting) AND no `upsell.hard` flag AND no rolling-bucket Redis call was issued for A

#### Scenario: Premium billing retry still treated as Premium
- **WHEN** caller A has `users.subscription_status = 'premium_billing_retry'` AND attempts a 200th post-delivery in a single hour
- **THEN** the response is HTTP 200 with `posts.length > 0` (DB-permitting) AND no `upsell.hard` flag AND no rolling-bucket Redis call was issued

#### Scenario: Cross-endpoint accumulation hits cap
- **WHEN** Free-tier caller A reads 50 posts on Nearby + 50 on Following + 50 on Global within one hour AND then attempts ANY of the three endpoints
- **THEN** the next response is HTTP 200 with `posts = []` AND `upsell.hard = true` (the per-user bucket aggregates across all three)

#### Scenario: Rolling key uses canonical hash-tag format
- **WHEN** the rolling limiter runs against Redis for caller `A` with uuid `U`
- **THEN** the key used is exactly `"{scope:rate_timeline_rolling}:{user:U}"`

#### Scenario: Rolling limiter runs before Postgres timeline query
- **WHEN** Free-tier caller A is at slot 150/150 AND issues `GET /api/v1/timeline/nearby?lat=-6.2&lng=106.8&radius_m=1000`
- **THEN** the response is HTTP 200 with `posts = []` + `upsell.hard = true` AND zero `posts` SELECT statements were issued to Postgres for the request

#### Scenario: Rolling cap restores after window ages out
- **WHEN** Free-tier caller A's oldest counted post-delivery is 60 minutes 1 second old AND A attempts a fresh timeline read
- **THEN** the response is HTTP 200 with `posts.length > 0` (DB-permitting) AND no `upsell.hard` flag (the oldest entry was pruned by the sliding-window logic; remaining count is 149, leaving room for 1+ slot)

#### Scenario: Hard-cap response omits upsell.soft
- **WHEN** Free-tier caller A is at the rolling cap (150/150)
- **THEN** the response body's `upsell` object has `hard: true` AND does NOT carry `soft: true` AND MAY omit the `soft` field entirely (additive shape — only `hard` is set on the cap-hit response)

### Requirement: Per-user 50-posts-per-session soft cap with non-blocking upsell flag

`GET /api/v1/timeline/{nearby,following,global}` SHALL enforce a per-user **soft** rate limit of 50 successful post-deliveries per session for Free-tier callers, and unlimited for Premium-tier callers. The "session" is identified by the `X-Session-Id` request header (a client-generated UUID per app foreground). The soft cap is **non-blocking**: when the count reaches 50, the response is unchanged in the `posts` field but carries `upsell.soft = true` to drive a mobile UX banner.

The check runs via `RateLimiter.tryAcquire(userId, key, capacity = 50, ttl = Duration.ofHours(1))` against a Redis-backed counter keyed `{scope:rate_timeline_session}:{user:<user_id>}:<session_id>`. The TTL MUST be `Duration.ofHours(1)` (not WIB-staggered).

Premium tier (`subscription_status IN ('premium_active', 'premium_billing_retry')`) MUST skip the session limiter entirely (no Redis call). Only `free` is subject to the soft cap.

The session limiter MUST run BEFORE the timeline DB query (parallel with the rolling pre-check). The session pre-check ALWAYS admits — even on `RateLimited` outcome from `tryAcquire`, the response proceeds normally. The flag `upsell.soft = true` is set on the response body when the post-increment session bucket count is `>= 50`.

Bucket increment matches the rolling bucket: 1 slot consumed at pre-check + `N - 1` best-effort slots after the response is built, where `N = response.posts.length`. Over-admission tolerance is identical to the rolling limiter (≤ 29 per request).

#### Scenario: First 50 reads in a session — no upsell.soft flag
- **WHEN** Free-tier caller A with `X-Session-Id: SID` has read 49 posts in the current session AND issues a timeline read returning 1 post
- **THEN** the response is HTTP 200 with `posts.length = 1` AND `upsell.soft` is NOT set to true (50 = not yet over the soft cap; the cap is "≥ 50 triggers", which fires on the NEXT request)

#### Scenario: 51st post in session triggers upsell.soft
- **WHEN** Free-tier caller A with `X-Session-Id: SID` has read 50 posts in the current session AND issues a timeline read returning 1 post
- **THEN** the response is HTTP 200 with `posts.length = 1` AND `upsell.soft = true` AND `upsell.hard` is not set (rolling cap not yet reached)

#### Scenario: Premium user not gated by session cap
- **WHEN** caller A has `subscription_status = 'premium_active'` AND has read 100 posts in the current session
- **THEN** every response is HTTP 200 with `posts.length > 0` AND no `upsell.soft` flag AND no session-bucket Redis call was issued

#### Scenario: Session key uses canonical hash-tag format
- **WHEN** the session limiter runs for caller `A` (uuid `U`) with `X-Session-Id: SID`
- **THEN** the key used is exactly `"{scope:rate_timeline_session}:{user:U}:SID"`

#### Scenario: Session bucket per-session — independent buckets
- **WHEN** Free-tier caller A reads 50 posts under `X-Session-Id: SID-1` AND then reads 1 post under `X-Session-Id: SID-2`
- **THEN** the SID-2 response does NOT carry `upsell.soft = true` (the SID-2 session bucket is independent of SID-1; only the rolling cap aggregates across sessions)

#### Scenario: Soft cap is non-blocking — posts still returned
- **WHEN** Free-tier caller A with `X-Session-Id: SID` has read 75 posts in the current session AND issues a timeline read
- **THEN** the response is HTTP 200 with `posts.length > 0` (DB-permitting) AND `upsell.soft = true` AND the timeline DB query DID execute

#### Scenario: Both upsell flags can coexist (rare race)
- **WHEN** the session bucket count is `>= 50` AND the rolling pre-check returns `RateLimited` (race: rolling cap was crossed by a concurrent request between the session pre-check and the rolling pre-check)
- **THEN** the response is HTTP 200 with `posts = []` AND `upsell.hard = true` AND `upsell.soft = true` (both flags reflect the bucket state at the time of pre-check)

### Requirement: `X-Session-Id` missing falls back to a deterministic per-user no-session bucket

If the request does NOT carry an `X-Session-Id` header, the session bucket key SHALL fall back to `{scope:rate_timeline_session}:{user:<user_id>}:no-session`. Soft cap still applies (50 posts trigger `upsell.soft = true`); all of the user's no-session reads in that hour share the same fallback bucket. The endpoint MUST NOT reject the request with HTTP 400 for a missing `X-Session-Id`.

#### Scenario: Missing X-Session-Id falls back to no-session bucket
- **WHEN** Free-tier caller A reads a timeline endpoint with NO `X-Session-Id` header
- **THEN** the response is HTTP 200 (NOT 400) AND the session-bucket key used is exactly `"{scope:rate_timeline_session}:{user:U}:no-session"`

#### Scenario: Multiple no-session reads accumulate in the fallback bucket
- **WHEN** Free-tier caller A reads 50 posts across multiple requests, all without `X-Session-Id`
- **THEN** the 51st post-delivery from a no-session request triggers `upsell.soft = true`

#### Scenario: Mixed session-id and no-session requests are bucketed independently
- **WHEN** Free-tier caller A reads 30 posts under `X-Session-Id: SID-1` AND then 30 posts with NO header
- **THEN** the SID-1 bucket has 30 entries AND the no-session bucket has 30 entries AND neither has crossed the 50-soft-cap threshold

### Requirement: Limiter ordering and pre-execution before DB

The timeline route handler SHALL run, in this exact order, on every authenticated `GET /api/v1/timeline/{nearby,following,global}`:

1. Auth (existing `auth-jwt` plugin).
2. Path / query parameter validation (existing — invalid `cursor`, out-of-envelope, etc.).
3. **Premium short-circuit**: if `viewer.subscriptionStatus IN ('premium_active', 'premium_billing_retry')`, SKIP both rate-limit buckets entirely (no Redis calls). Proceed directly to step 6.
4. **Rolling pre-check**: `tryAcquire(userId, "{scope:rate_timeline_rolling}:{user:U}", 150, Duration.ofHours(1))`. On `RateLimited`: return HTTP 200 with `posts=[]`, `next_cursor=null`, `upsell.hard=true`, STOP (do NOT execute steps 5+).
5. **Session pre-check** (always admits, advisory only): `tryAcquire(userId, "{scope:rate_timeline_session}:{user:U}:<sid_or_no-session>", 50, Duration.ofHours(1))`. The outcome is recorded (the response will set `upsell.soft = true` if the bucket was at or above 50 at pre-check time) but DOES NOT block the request.
6. Execute the timeline DB query → `posts: List<TimelinePost>` of length `N`.
7. **Post-increment** (skip if Premium per step 3): issue `N - 1` additional best-effort `tryAcquire` calls in parallel coroutines on each of the rolling and session buckets. The outcome of these calls is ignored (the response is already shaped). On Premium, no post-increment occurs (both buckets are never touched).
8. Build response: `posts`, `next_cursor`, and `upsell` object (omitted entirely if both flags are false).
9. Return HTTP 200.

Steps 4 + 5 MUST run BEFORE the timeline DB query (no Postgres round-trip on cap-hit). Steps 4 + 5 + 7 are SKIPPED entirely for Premium callers.

#### Scenario: Auth failure short-circuits before limiters
- **WHEN** the request lacks a valid JWT
- **THEN** the response is HTTP 401 AND no rate-limit Redis round-trip is issued

#### Scenario: Invalid cursor short-circuits before limiters
- **WHEN** the request has `cursor=not-a-base64-json`
- **THEN** the response is HTTP 400 with `error.code = "invalid_cursor"` AND no rate-limit Redis round-trip is issued

#### Scenario: Premium short-circuits both pre-checks
- **WHEN** Premium caller A issues a timeline read
- **THEN** zero Redis calls are issued for either the rolling or session bucket on this request AND the response is HTTP 200 with the timeline content

#### Scenario: Rolling pre-check at cap short-circuits before DB query
- **WHEN** Free caller A is at slot 150/150 rolling AND issues `GET /api/v1/timeline/global`
- **THEN** the response is HTTP 200 with `posts=[]` + `upsell.hard=true` AND zero `posts` SELECT statements are issued to Postgres for this request AND the session pre-check (step 5) is NOT executed

#### Scenario: Soft pre-check at cap does NOT block
- **WHEN** Free caller A's session bucket holds 60 entries AND A issues a timeline read
- **THEN** the response is HTTP 200 with `posts.length > 0` (DB-permitting) AND `upsell.soft = true` AND the timeline DB query DID execute

#### Scenario: Both pre-checks fire and admit on a fresh session
- **WHEN** Free caller A with `X-Session-Id: SID-fresh` (zero prior reads) issues a timeline read
- **THEN** both the rolling and session bucket each gain at least 1 entry AND the response is HTTP 200 with the timeline content AND no `upsell` flags

### Requirement: Upsell response object shape

The response body for a successful HTTP 200 timeline read SHALL include an additional top-level `upsell` object whenever a soft- or hard-cap signal needs to be communicated. The object's keys are limited to two optional booleans:

```json
{
  "posts": [...],
  "next_cursor": "...",
  "upsell": {
    "soft": true,
    "hard": false
  }
}
```

When BOTH flags would be false (the common case — user is below all caps), the `upsell` object MUST be omitted entirely from the response. The shape MUST be additive — existing clients that don't read `upsell.*` continue to function unchanged.

`upsell.soft = true` SHALL be set when the session bucket holds `>= 50` entries at the time of pre-check.
`upsell.hard = true` SHALL be set when the rolling pre-check returned `RateLimited` (the request is at the hourly cap).

The `upsell` object MUST NOT contain any other fields in this version of the spec — future expansion (e.g., a `Retry-After`-equivalent `seconds_to_reset` field) lands as a MODIFIED requirement in a follow-up change.

#### Scenario: No flags → upsell object omitted
- **WHEN** Free caller A is below both caps AND issues a timeline read
- **THEN** the response body does NOT contain an `upsell` key

#### Scenario: Soft cap reached → upsell.soft only
- **WHEN** Free caller A's session bucket holds 60 entries AND the rolling bucket holds 80 entries AND A issues a timeline read
- **THEN** the response body contains `upsell.soft = true` AND does NOT contain `upsell.hard` (or contains `upsell.hard = false`)

#### Scenario: Hard cap reached → upsell.hard only with empty posts
- **WHEN** Free caller A's rolling bucket is at 150/150 AND A issues a timeline read
- **THEN** the response body contains `upsell.hard = true` AND `posts = []` AND `next_cursor = null`

#### Scenario: Upsell object has no extraneous fields
- **WHEN** the response contains an `upsell` object
- **THEN** the object's keys are a subset of `{soft, hard}` AND no other keys appear

### Requirement: Shadow-banned reader's reads still consume bucket slots

A `is_shadow_banned = TRUE` user's timeline reads SHALL consume rate-limit bucket slots normally. The rate limit MUST NOT exempt shadow-banned readers; their visibility into other users' content is filtered through `visible_*` views (an orthogonal concern), but their own read budget is bounded identically to non-shadow-banned readers.

#### Scenario: Shadow-banned Free user hits the rolling cap
- **WHEN** caller A has `is_shadow_banned = TRUE` AND `subscription_status = 'free'` AND has read 150 posts in the last hour AND attempts a 151st-post read
- **THEN** the response is HTTP 200 with `posts = []` AND `upsell.hard = true` (identical to a non-shadow-banned Free user at the cap)

#### Scenario: Shadow-banned Premium user is unbounded
- **WHEN** caller A has `is_shadow_banned = TRUE` AND `subscription_status = 'premium_active'` AND has read 200 posts in the last hour
- **THEN** the response is HTTP 200 with `posts.length > 0` (DB-permitting) AND no `upsell` flags (Premium bypass applies regardless of shadow-ban state)

### Requirement: Guest timeline read rate limit is explicitly NOT in scope

This capability SHALL only cover authenticated callers. Guest reads of `GET /api/v1/timeline/global` (the future-state guest-readable endpoint per [`docs/02-Product.md:181`](../../../docs/02-Product.md)) MUST NOT be subject to the requirements in this spec. Guest read rate limiting (`10 posts/session + 30 posts/hour`, keyed by guest JWT `jti`) SHALL land in a follow-up change once the Layer 1 attestation-gated guest-session JWT issuance work (Phase 1 item 25) ships. That follow-up change MUST MODIFY this capability to add the guest-axis bucket; until then, the existing global timeline endpoint is authenticated-only and this capability's requirements apply only to authenticated callers.

#### Scenario: Guest endpoint not yet wired — capability does not apply
- **WHEN** the codebase does not yet expose a guest-accessible variant of `GET /api/v1/timeline/global`
- **THEN** this capability's requirements MUST NOT add a guest-axis bucket; only the per-user buckets defined in the previous requirements apply

### Requirement: Integration test coverage

`TimelineReadRateLimitTest` (tagged `database`, depending on the CI Redis service container) SHALL exist alongside the existing timeline-service tests and cover, at minimum, end-to-end against a Postgres + Redis test environment:

1. **Rolling cap basic**: 5 page-of-30 reads on Nearby succeed (= 150 posts); 6th request returns `posts = []` + `upsell.hard = true` + HTTP 200.
2. **Rolling cap cross-endpoint**: 50 posts on Nearby + 50 on Following + 50 on Global → 4th request (any of the three) returns `posts = []` + `upsell.hard = true`.
3. **Rolling cap returns 200 not 429**: assert HTTP status code is exactly 200 (not 429) on hard-cap response AND no `Retry-After` header is set.
4. **Rolling cap aging**: 60 minutes after the oldest counted read, a fresh read succeeds with non-empty posts.
5. **Soft cap basic**: 51st-post read in a session (with `X-Session-Id: SID`) returns `upsell.soft = true` while still returning posts.
6. **Soft cap is non-blocking**: 75th-post read in a session returns `posts.length > 0` (DB-permitting) AND `upsell.soft = true`.
7. **Soft cap per-session independence**: read 50 posts under SID-1 + 1 post under SID-2 → SID-2 response does NOT carry `upsell.soft = true`.
8. **Premium rolling bypass**: `subscription_status = 'premium_active'` reads 200 posts in an hour; all responses HTTP 200 with no upsell flags; Redis-counter spy assertion shows zero rolling-bucket writes for that user.
9. **Premium billing retry bypass**: `subscription_status = 'premium_billing_retry'` matches Premium behavior.
10. **Premium short-circuits both buckets**: Redis-counter spy assertion shows zero session-bucket and zero rolling-bucket writes for a Premium caller.
11. **Tier read from auth principal**: query-counter spy assertion shows zero `users` SELECTs in the rate-limit pre-check path (tier comes from the auth-time principal).
12. **Pre-check before DB query**: a caller at the rolling cap who hits a non-existent post UUID still receives `posts = []` + `upsell.hard = true` (and the test asserts zero `posts` SELECTs were executed via a Postgres statement-log spy or query counter).
13. **Hash-tag key shapes**: rolling key matches exactly `{scope:rate_timeline_rolling}:{user:<uuid>}` AND session key matches exactly `{scope:rate_timeline_session}:{user:<uuid>}:<session_id>`. Verified via Lettuce CRC16 helper assertions or by inspecting the keys actually written to Redis.
14. **`X-Session-Id` missing fallback**: a request without the header succeeds; the session bucket key written is exactly `{scope:rate_timeline_session}:{user:<uuid>}:no-session`.
15. **`X-Session-Id` missing fallback accumulates**: 50 no-session reads → 51st triggers `upsell.soft = true`.
16. **Mixed session-id and no-session bucket independence**: 30 reads under SID-1 + 30 with no header → both buckets hold 30 entries; neither flagged.
17. **Auth failure short-circuit**: HTTP 401 without JWT; assert zero rate-limit Redis calls.
18. **Cursor invalid short-circuit**: HTTP 400 `invalid_cursor`; assert zero rate-limit Redis calls.
19. **Empty timeline (Following with zero follows)**: response has `posts = []` + the rolling pre-check still consumed 1 slot (verified via bucket inspection).
20. **Shadow-banned Free user hits cap**: `is_shadow_banned = TRUE` + `free` user at 150/150 → `posts = []` + `upsell.hard = true` (no shadow-ban exemption).
21. **Shadow-banned Premium user uncapped**: `is_shadow_banned = TRUE` + `premium_active` reads 200 posts → no upsell flags.
22. **`upsell` object omitted when both flags false**: response below both caps does NOT contain an `upsell` key.
23. **`upsell.hard` omits `soft`**: hard-cap response has `upsell = { hard: true }` (no `soft` key, or `soft: false` only if explicitly serialized — implementation chooses).
24. **Over-admission tolerance bound**: caller at 149/150 issues a request returning 30 posts → response succeeds; the NEXT request is hard-capped (verifying the over-admit bound is at most `N - 1 = 29` posts).
25. **Cross-tab same-user accumulation under multiple session ids**: 50 posts under SID-1 (Nearby) + 50 under SID-2 (Following) + 50 under SID-3 (Global) → rolling cap reached even though no session bucket itself reached 50 (verifies the session and rolling buckets are independent axes).

#### Scenario: Test class discoverable
- **WHEN** running `./gradlew :backend:ktor:test --tests '*TimelineReadRateLimitTest*'`
- **THEN** the class is discovered AND every numbered scenario above corresponds to at least one `@Test` method
