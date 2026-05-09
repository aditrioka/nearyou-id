## ADDED Requirements

### Requirement: Per-user rolling 150-posts-per-hour hard cap on the three authenticated timeline endpoints

`GET /api/v1/timeline/{nearby,following,global}` SHALL enforce a per-user rolling **hard** rate limit of 150 successful post-deliveries per 1-hour window for Free-tier callers, and unlimited for Premium-tier callers. The cap aggregates across ALL THREE timeline endpoints into a single per-user bucket: a user reading 30 posts × 5 pages on Nearby = 150 posts → cap reached; equivalently, mixed reads totaling 150 across Nearby + Following + Global also reach the cap.

The check runs via `RateLimiter.tryAcquire(userId, key, capacity = 150, ttl = Duration.ofHours(1))` against a Redis-backed counter keyed `{scope:rate_timeline_rolling}:{user:<user_id>}`. The TTL MUST be `Duration.ofHours(1)` directly — `computeTTLToNextReset` MUST NOT be used (the rolling window is hourly, not staggered-daily). The `RateLimitTtlRule` Detekt rule (per `rate-limit-infrastructure/spec.md`) does NOT fire on this key string because the literal contains no `_day}` substring.

Tier gating reads `users.subscription_status` from the auth-time `viewer` principal populated by the `auth-jwt` plugin (per the `auth-jwt` capability — the principal already carries `subscriptionStatus`, `userId`, and `isShadowBanned`). Both `premium_active` and `premium_billing_retry` MUST skip the rolling limiter entirely (no Redis call). Only `free` is subject to the cap. The handler MUST NOT issue a fresh `SELECT subscription_status FROM users` before the limiter.

The rolling limiter MUST run BEFORE the timeline DB query. On `RateLimited`, the response is HTTP **200** (NOT 429) with body `{ "posts": [], "next_cursor": null, "upsell": { "hard": true } }`. The `Retry-After` header MUST NOT be set (the soft "you've hit your hourly limit" UX is preferred over a hard error).

The bucket SHALL be incremented as follows on every Free-tier request that the rolling pre-check admitted:

1. The pre-check itself consumes 1 slot (one `tryAcquire` call returning `Allowed`).
2. After the timeline DB query returns `N` posts (`0 ≤ N ≤ 30`), the handler issues `(N - 1).coerceAtLeast(0)` additional `tryAcquire` calls — i.e., zero additional calls if `N ≤ 1`, else `N - 1` parallel best-effort calls. Each call succeeds while the bucket has room; once the bucket reaches capacity 150, subsequent calls return `RateLimited` and add nothing.

The total slots consumed on a single admitted request is `1 + (number of best-effort calls that returned Allowed)` — at most `1 + (N - 1) = N`, possibly less if the bucket fills mid-batch. Worst-case over-admission for one request is bounded by `N - 1 ≤ 29` (the per-page cap) — a user at slot 121/150 reading 30 posts ends with bucket = 150, exactly at cap, with the user's NEXT request hard-capped. Concurrent same-user requests amplify this bound by parallelism (see § "Concurrent same-user request bound" below).

#### Scenario: 5 page-of-30 reads in an hour (Free) succeed
- **WHEN** Free-tier caller A successfully reads 5 timeline pages of 30 posts each (= 150 posts) within a 60-minute window across any combination of Nearby/Following/Global, with `X-Session-Id: SID` set on every request
- **THEN** all 5 responses are HTTP 200 with `posts.length = 30` AND no `upsell.hard` flag on those 5 responses

#### Scenario: 6th read in same hour rate-limited (Free)
- **WHEN** Free-tier caller A has 150 successful post-deliveries in the last hour (rolling bucket at 150/150) AND attempts a 6th timeline read on any of the three endpoints
- **THEN** the response is HTTP 200 with `posts = []` AND `next_cursor = null` AND `upsell.hard = true` AND no `Retry-After` header AND no Postgres `posts` SELECT was executed

#### Scenario: Premium user not gated by rolling cap
- **WHEN** caller A has `users.subscription_status = 'premium_active'` AND attempts a 200th post-delivery (across any timeline) in a single hour
- **THEN** the response is HTTP 200 with `posts.length > 0` (DB-permitting) AND no `upsell` field in the response AND no rolling-bucket Redis call was issued for A on this request

#### Scenario: Premium billing retry still treated as Premium
- **WHEN** caller A has `users.subscription_status = 'premium_billing_retry'` AND attempts a 200th post-delivery in a single hour
- **THEN** the response is HTTP 200 with `posts.length > 0` (DB-permitting) AND no `upsell` field in the response AND no rolling-bucket Redis call was issued

#### Scenario: Cross-endpoint accumulation hits cap with 30-page reads
- **WHEN** Free-tier caller A reads 2 pages on Nearby (30 + 30 = 60 posts) + 2 pages on Following (30 + 30 = 60 posts) + 1 page on Global (30 posts) within one hour (150 total) AND then attempts ANY of the three endpoints with a 6th request
- **THEN** the 6th response is HTTP 200 with `posts = []` AND `upsell.hard = true` (the per-user rolling bucket aggregates across all three endpoints)

#### Scenario: Rolling key uses canonical hash-tag format
- **WHEN** the rolling limiter runs against Redis for caller `A` with uuid `U`
- **THEN** the key used is exactly `"{scope:rate_timeline_rolling}:{user:U}"` AND `RedisHashTagRule` does NOT fire on this string literal

#### Scenario: Rolling limiter runs before Postgres timeline query
- **WHEN** Free-tier caller A is at slot 150/150 AND issues `GET /api/v1/timeline/nearby?lat=-6.2&lng=106.8&radius_m=1000`
- **THEN** the response is HTTP 200 with `posts = []` + `upsell.hard = true` AND zero `posts` SELECT statements were issued to Postgres for the request

#### Scenario: Rolling cap restores after window ages out
- **WHEN** Free-tier caller A's oldest counted post-delivery is 60 minutes 1 second old AND A attempts a fresh timeline read
- **THEN** the response is HTTP 200 with `posts.length > 0` (DB-permitting) AND no `upsell.hard` flag (the oldest entry was pruned by the Lettuce-backed Lua sliding-window prune step; remaining count is 149, leaving room for at least 1 slot)

#### Scenario: Hard-cap response omits upsell.soft
- **WHEN** Free-tier caller A is at the rolling cap (150/150)
- **THEN** the response body's `upsell` object has `hard: true` AND does NOT carry `soft` (the rolling pre-check short-circuits before the session pre-check is consulted, so `softCapReached` is unobserved and the soft flag is omitted)

### Requirement: Per-user 50-posts-per-session soft cap with non-blocking upsell flag

`GET /api/v1/timeline/{nearby,following,global}` SHALL enforce a per-user **soft** rate limit of 50 successful post-deliveries per session for Free-tier callers, and unlimited for Premium-tier callers. The "session" is identified by the `X-Session-Id` request header (see § "X-Session-Id header validation" below). The soft cap is **non-blocking**: the response carries `upsell.soft = true` to drive a mobile UX banner once the per-session bucket reaches its capacity ceiling, but post counts continue to be returned in full (subject only to the rolling hard cap from the previous requirement).

The check runs via `RateLimiter.tryAcquire(userId, key, capacity = 50, ttl = Duration.ofHours(1))` against a Redis-backed counter keyed `{scope:rate_timeline_session}:{session:<user_id>__<session_id>}`. The TTL MUST be `Duration.ofHours(1)` (not WIB-staggered). The `<user_id>__<session_id>` composite value (joined by exactly two underscores) is the partition axis; both hash tags conform to the `{scope:<value>}:{<axis>:<value>}` standard enforced by `RedisHashTagRule`. The shipped `RedisRateLimiter` Lua sliding-window guarantees the bucket size is always `≤ capacity = 50` — once the 50th entry is admitted, subsequent `tryAcquire` calls return `RateLimited` and add nothing.

Premium tier (`subscription_status IN ('premium_active', 'premium_billing_retry')`) MUST skip the session limiter entirely (no Redis call). Only `free` is subject to the soft cap.

The session limiter MUST run AFTER the rolling pre-check (per § "Limiter ordering") and only when the rolling pre-check returned `Allowed`. The session pre-check ALWAYS admits the request even on `RateLimited` outcome from `tryAcquire`. The `softCapReached` signal is set when the session pre-check returns `RateLimited` — i.e., the bucket was at 50/50 capacity at the time of the call — and that signal drives `upsell.soft = true` on the response.

Bucket increment matches the rolling bucket: 1 slot consumed at pre-check + `(N - 1).coerceAtLeast(0)` best-effort additional `tryAcquire` calls after the response is built. The Lua sliding-window enforces capacity, so bucket size is always `≤ 50` regardless of how many post-increment calls are issued.

#### Scenario: First 49 reads in a session — no upsell.soft flag
- **WHEN** Free-tier caller A with `X-Session-Id: SID` (UUID-format) has issued requests totaling 48 posts AND issues a timeline read returning 1 post (49th post-delivery total in the session)
- **THEN** the response is HTTP 200 with `posts.length = 1` AND the `upsell` field is absent OR `upsell.soft` is false (session bucket has 49 entries; pre-check returned Allowed)

#### Scenario: 50-post boundary admits — upsell.soft NOT yet set
- **WHEN** Free-tier caller A with `X-Session-Id: SID` has 49 entries in the session bucket AND issues a timeline read returning 1 post (50th delivery)
- **THEN** the response is HTTP 200 with `posts.length = 1` AND `upsell.soft` is NOT set to true (session pre-check returned Allowed: 49 < 50; the post-increment skipped because `N - 1 = 0`; bucket sits at 50/50 after this request)

#### Scenario: 51st post-delivery triggers upsell.soft (bucket at 50/50)
- **WHEN** Free-tier caller A with `X-Session-Id: SID` has 50 entries in the session bucket (filled by an earlier request that ended with bucket at 50) AND issues a timeline read returning 1 post (51st delivery)
- **THEN** the response is HTTP 200 with `posts.length = 1` AND `upsell.soft = true` AND `upsell.hard` is NOT set (rolling cap not reached) AND the session bucket size remains 50 (the pre-check returned RateLimited; no entry added)

#### Scenario: Premium user not gated by session cap
- **WHEN** caller A has `subscription_status = 'premium_active'` AND has read 100 posts in the current session
- **THEN** every response is HTTP 200 with `posts.length > 0` AND no `upsell.soft` flag AND no session-bucket Redis call was issued

#### Scenario: Session key uses canonical hash-tag format
- **WHEN** the session limiter runs for caller `A` (uuid `U`) with `X-Session-Id: SID`
- **THEN** the key used is exactly `"{scope:rate_timeline_session}:{session:U__SID}"` AND `RedisHashTagRule` does NOT fire on this string literal AND the strict regex `^\{scope:[^{}]+}:\{[^{}:]+:[^{}]+}$` matches it

#### Scenario: Session bucket per-session — independent buckets
- **WHEN** Free-tier caller A reads enough posts to fill the session bucket under `X-Session-Id: SID-1` (i.e., bucket reaches 50/50 with `upsell.soft` set on the next read) AND then reads 1 post under `X-Session-Id: SID-2`
- **THEN** the SID-2 response does NOT carry `upsell.soft = true` (the SID-2 session bucket is independent of SID-1; only the rolling cap aggregates across sessions)

#### Scenario: Soft cap is non-blocking — posts still returned past the soft threshold
- **WHEN** Free-tier caller A with `X-Session-Id: SID` has 50 entries in the session bucket (soft cap reached) AND has 80 entries in the rolling bucket (well below the 150 hard cap) AND issues a timeline read
- **THEN** the response is HTTP 200 with `posts.length > 0` (DB-permitting) AND `upsell.soft = true` AND the timeline DB query DID execute

#### Scenario: Cross-tab same-user multi-session accumulation
- **WHEN** Free-tier caller A reads 30 posts under SID-1 (Nearby) + 30 under SID-2 (Following) + 30 under SID-3 (Global) + 30 under SID-4 (Nearby) + 30 under SID-5 (Following) (5 sessions × 30 posts = 150 rolling-bucket entries) AND then issues a 6th request under any session-id
- **THEN** the 6th response is HTTP 200 with `posts = []` AND `upsell.hard = true` (the rolling cap is reached even though no individual session bucket exceeded 30/50)

### Requirement: `X-Session-Id` header validation and `no-session` fallback

If the request supplies an `X-Session-Id` header, the value SHALL be validated to match the regex `^[A-Za-z0-9-]{1,64}$` (UUID-friendly: alphanumeric and hyphens only, length 1-64 inclusive). A missing header OR a header value that fails validation SHALL be substituted with the literal string `no-session`; the resulting session bucket key is `{scope:rate_timeline_session}:{session:<user_id>__no-session}`. The endpoint MUST NOT reject the request with HTTP 400 for a missing or malformed `X-Session-Id`.

The validation MUST happen BEFORE the session bucket key is constructed (so a malicious value cannot inject `{`, `}`, `:`, or other Redis-key-structural characters into the rendered key). All bytes of the validated value flow into the partition axis of the key; the implementation MUST NOT rely on Redis to sanitize the key.

The `no-session` fallback bucket is a single per-user bucket aggregating ALL no-session reads in the hour. Soft cap still applies: 50 no-session reads triggers `upsell.soft = true` on the 51st delivery.

#### Scenario: Missing X-Session-Id falls back to no-session bucket
- **WHEN** Free-tier caller A reads a timeline endpoint with NO `X-Session-Id` header
- **THEN** the response is HTTP 200 (NOT 400) AND the session-bucket key used is exactly `"{scope:rate_timeline_session}:{session:<user_id>__no-session}"`

#### Scenario: Malformed X-Session-Id falls back to no-session bucket
- **WHEN** Free-tier caller A sets `X-Session-Id: }malicious{user:victim}` (header value containing brace characters AND a colon, failing the validation regex)
- **THEN** the response is HTTP 200 (NOT 400) AND the session-bucket key used is exactly `"{scope:rate_timeline_session}:{session:<user_id>__no-session}"` (the malformed value is rejected and substituted with `no-session`)

#### Scenario: Oversized X-Session-Id falls back to no-session bucket
- **WHEN** Free-tier caller A sets `X-Session-Id` to a value of length 65 or more (failing the upper-bound validation)
- **THEN** the response is HTTP 200 AND the session-bucket key used contains the substring `__no-session`

#### Scenario: UUID-shaped X-Session-Id is accepted unchanged
- **WHEN** Free-tier caller A sets `X-Session-Id: 4f8b9c1e-2d3a-4b5c-9d1e-7f8a9b0c1d2e` (a valid UUIDv4 with hyphens, alphanumeric, length 36)
- **THEN** the value is admitted unchanged AND the session-bucket key used is exactly `"{scope:rate_timeline_session}:{session:<user_id>__4f8b9c1e-2d3a-4b5c-9d1e-7f8a9b0c1d2e}"`

#### Scenario: Multiple no-session reads accumulate in the fallback bucket
- **WHEN** Free-tier caller A makes timeline reads totaling 50 post-deliveries, all without `X-Session-Id`, then attempts a 51st delivery
- **THEN** the 51st response carries `upsell.soft = true` (the fallback bucket reached its 50/50 cap on the prior request set)

#### Scenario: Mixed valid-SID and no-session requests are bucketed independently
- **WHEN** Free-tier caller A reads enough to fill the bucket under `X-Session-Id: SID-1` (= 50 entries) AND then reads 1 post with no header
- **THEN** the SID-1 bucket is at 50/50 AND the no-session bucket holds at most 1 entry (independent of SID-1) AND the no-session response does NOT carry `upsell.soft = true`

### Requirement: Limiter ordering and pre-execution before DB

The timeline route handler SHALL run, in this exact order, on every authenticated `GET /api/v1/timeline/{nearby,following,global}`:

1. Auth (existing `auth-jwt` plugin).
2. Path / query parameter validation (existing — invalid `cursor`, out-of-envelope, etc.).
3. **Premium short-circuit**: if `viewer.subscriptionStatus IN ('premium_active', 'premium_billing_retry')`, SKIP both rate-limit buckets entirely (no Redis calls). Proceed directly to step 7 (timeline DB query).
4. **`X-Session-Id` validation** (per § "X-Session-Id header validation"): produce a sanitized session-id (the original value if it passes the regex, else `no-session`).
5. **Rolling pre-check**: `tryAcquire(userId, "{scope:rate_timeline_rolling}:{user:U}", 150, Duration.ofHours(1))`. On `RateLimited`: return HTTP 200 with `posts=[]`, `next_cursor=null`, `upsell.hard=true`, STOP (do NOT execute the session pre-check, the DB query, or the post-increment).
6. **Session pre-check** (always admits, advisory only): `tryAcquire(userId, "{scope:rate_timeline_session}:{session:U__<sanitized_sid>}", 50, Duration.ofHours(1))`. The outcome is recorded as `softCapReached: Boolean = (outcome == RateLimited)`. The pre-check NEVER blocks the request.
7. Execute the timeline DB query → `posts: List<TimelinePost>` of length `N`.
8. **Post-increment** (skip if Premium per step 3): issue `(N - 1).coerceAtLeast(0)` additional best-effort `tryAcquire` calls in parallel coroutines on each of the rolling and session buckets. The outcome of these calls is ignored. On Premium, no post-increment occurs.
9. Build response: `posts`, `next_cursor`, and `upsell` object built per § "Upsell response object shape" (omit the entire `upsell` object if both flags are false).
10. Return HTTP 200.

Steps 5 + 6 MUST run BEFORE the timeline DB query (no Postgres round-trip on cap-hit). Steps 5 + 6 + 8 are SKIPPED entirely for Premium callers (no Redis calls at all).

The strict step ordering above means that on a hard-capped request (step 5 returns RateLimited), step 6 is NEVER reached and `softCapReached` is never observed for that request — so the response carries only `upsell.hard = true` (no `upsell.soft` field). This is intentional: a hard-capped request should not surface a soft-cap signal alongside since the user is already past the harder constraint.

#### Scenario: Auth failure short-circuits before limiters
- **WHEN** the request lacks a valid JWT
- **THEN** the response is HTTP 401 AND no rate-limit Redis round-trip is issued

#### Scenario: Invalid cursor short-circuits before limiters
- **WHEN** the request has `cursor=not-a-base64-json`
- **THEN** the response is HTTP 400 with `error.code = "invalid_cursor"` AND no rate-limit Redis round-trip is issued

#### Scenario: Premium short-circuits both pre-checks
- **WHEN** Premium caller A issues a timeline read
- **THEN** zero Redis calls are issued for either the rolling or session bucket on this request AND the response is HTTP 200 with the timeline content AND no `upsell` field

#### Scenario: Rolling pre-check at cap short-circuits before session pre-check and DB query
- **WHEN** Free caller A is at slot 150/150 rolling AND issues `GET /api/v1/timeline/global`
- **THEN** the response is HTTP 200 with `posts=[]` + `upsell={hard:true}` (no `soft` key) AND zero `posts` SELECT statements are issued to Postgres for this request AND the session pre-check (step 6) is NOT executed

#### Scenario: Soft pre-check at cap does NOT block
- **WHEN** Free caller A's session bucket is at 50/50 AND the rolling bucket holds 90/150 entries AND A issues a timeline read
- **THEN** the response is HTTP 200 with `posts.length > 0` (DB-permitting) AND `upsell.soft = true` AND the timeline DB query DID execute AND `upsell.hard` is not set

#### Scenario: Both pre-checks fire and admit on a fresh session
- **WHEN** Free caller A with `X-Session-Id: SID-fresh` (zero prior reads) issues a timeline read
- **THEN** both the rolling and session bucket each gain at least 1 entry AND the response is HTTP 200 with the timeline content AND no `upsell` field

#### Scenario: Stale principal across mid-flight subscription expiry admits as Premium for THIS request
- **WHEN** caller A's auth-time JWT carries `subscription_status = 'premium_active'` AND, between the JWT issuance and this request, A's subscription expired (DB row now `free`) AND A issues a timeline read
- **THEN** the response is HTTP 200 with no `upsell` field (the auth-time principal is the source of truth for THIS request, mirroring the `auth-jwt § Stale principal` semantics) AND on the NEXT JWT refresh A becomes Free and is subject to both caps from then on

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

`upsell.soft = true` SHALL be set when the session pre-check (step 6 of § "Limiter ordering") returned `RateLimited` — i.e., the session bucket was at its 50/50 capacity at the time of the pre-check call.

`upsell.hard = true` SHALL be set when the rolling pre-check (step 5) returned `RateLimited` — i.e., the rolling bucket was at its 150/150 capacity at the time of the pre-check call. When `upsell.hard` is set, the `posts` array MUST be empty (`[]`), `next_cursor` MUST be `null`, and the session pre-check MUST NOT have been executed (so `upsell.soft` is omitted, never set).

The `upsell` object MUST NOT contain any other fields in this version of the spec — future expansion (e.g., a `Retry-After`-equivalent `seconds_to_reset` field) lands as a MODIFIED requirement in a follow-up change.

#### Scenario: No flags → upsell object omitted
- **WHEN** Free caller A is below both caps AND issues a timeline read
- **THEN** the response body does NOT contain an `upsell` key

#### Scenario: Soft cap reached → upsell.soft only
- **WHEN** Free caller A's session bucket is at 50/50 AND the rolling bucket holds 80/150 entries AND A issues a timeline read
- **THEN** the response body contains `upsell.soft = true` AND does NOT contain `upsell.hard` (or contains `upsell.hard = false` if implementation chooses explicit serialization)

#### Scenario: Hard cap reached → upsell.hard only with empty posts
- **WHEN** Free caller A's rolling bucket is at 150/150 AND A issues a timeline read
- **THEN** the response body contains `upsell.hard = true` AND `posts = []` AND `next_cursor = null` AND the response does NOT contain `upsell.soft` (the session pre-check was never executed per the limiter ordering)

#### Scenario: Upsell object has no extraneous fields
- **WHEN** the response contains an `upsell` object
- **THEN** the object's keys are a subset of `{soft, hard}` AND no other keys appear

### Requirement: Concurrent same-user request bound

The over-admission tolerance documented in the rolling-cap requirement (`N - 1 ≤ 29` posts per single request) SHALL be amplified by parallelism on concurrent same-user requests. With `K` concurrent timeline reads at slot `(150 - 1)`, all `K` rolling pre-checks may admit before any post-increment lands; total over-admit is bounded by `K × N` rather than `N`. This is acceptable for MVP volume and is bounded by the authenticated per-IP baseline (1000 req/min/IP per [`docs/05-Implementation.md:1142`](../../../docs/05-Implementation.md)) plus mobile clients' typical concurrency (≤ 3 simultaneous timeline reads across tabs).

The implementation MUST NOT introduce a separate request-level lock (no Redis SETNX, no advisory lock) to bound parallel admission — the bound is acceptable as documented and the additional locking would add latency on the hot path. Future re-evaluation lands as a follow-up MODIFICATION if production telemetry shows abuse.

#### Scenario: Three concurrent requests near cap each admit at pre-check
- **WHEN** Free-tier caller A is at slot 148/150 AND issues 3 simultaneous timeline reads (parallel coroutines, each returning 30 posts)
- **THEN** all 3 rolling pre-checks return `Allowed` (the bucket goes 148 → 149 → 150 across the 3 admitted slots, atomic per `tryAcquire`) AND the 3 responses each carry `posts.length = 30` AND the post-increments race for the remaining capacity AND the user's NEXT (4th) request is hard-capped at slot 150/150

#### Scenario: No additional request-level lock introduced
- **WHEN** searching the timeline route handler implementation for `SETNX`, `SET ... NX`, or `pg_advisory_xact_lock`
- **THEN** no such call is introduced by this capability (the parallel admission bound is the explicit, documented MVP tradeoff)

### Requirement: Shadow-banned reader's reads still consume bucket slots

A `is_shadow_banned = TRUE` user's timeline reads SHALL consume rate-limit bucket slots normally. The rate limit MUST NOT exempt shadow-banned readers; their visibility into other users' content is filtered through `visible_*` views (an orthogonal concern), but their own read budget is bounded identically to non-shadow-banned readers.

#### Scenario: Shadow-banned Free user hits the rolling cap
- **WHEN** caller A has `is_shadow_banned = TRUE` AND `subscription_status = 'free'` AND has read 150 posts in the last hour AND attempts a 151st-post read
- **THEN** the response is HTTP 200 with `posts = []` AND `upsell.hard = true` (identical to a non-shadow-banned Free user at the cap)

#### Scenario: Shadow-banned Premium user is unbounded
- **WHEN** caller A has `is_shadow_banned = TRUE` AND `subscription_status = 'premium_active'` AND has read 200 posts in the last hour
- **THEN** the response is HTTP 200 with `posts.length > 0` (DB-permitting) AND no `upsell` field (Premium bypass applies regardless of shadow-ban state)

### Requirement: Guest timeline read rate limit is explicitly NOT in scope

This capability SHALL only cover authenticated callers. Guest reads of `GET /api/v1/timeline/global` (the future-state guest-readable endpoint per [`docs/02-Product.md:181`](../../../docs/02-Product.md)) MUST NOT be subject to the requirements in this spec. Guest read rate limiting (`10 posts/session + 30 posts/hour`, keyed by guest JWT `jti`) SHALL land in a follow-up change once the Layer 1 attestation-gated guest-session JWT issuance work (Phase 1 item 25) ships. That follow-up change MUST MODIFY this capability to add the guest-axis bucket; until then, the existing global timeline endpoint is authenticated-only and this capability's requirements apply only to authenticated callers.

#### Scenario: Guest endpoint not yet wired — capability does not apply
- **WHEN** the codebase does not yet expose a guest-accessible variant of `GET /api/v1/timeline/global`
- **THEN** this capability's requirements MUST NOT add a guest-axis bucket; only the per-user buckets defined in the previous requirements apply

### Requirement: Integration test coverage

`TimelineReadRateLimitTest` (tagged `database`, depending on the CI Postgres + Redis service containers) SHALL exist alongside the existing timeline-service tests at `backend/ktor/src/test/kotlin/id/nearyou/app/timeline/TimelineReadRateLimitTest.kt` and cover, at minimum, end-to-end against a real Postgres + Redis test environment (uses the `testApplication { application { module() } }` pattern shared with `LikeRateLimitTest`, `ReplyRateLimitTest`, `ChatRateLimitTest`):

1. **Rolling cap basic**: 5 page-of-30 reads on Nearby succeed (= 150 posts); 6th request returns empty + `upsell.hard = true` + HTTP 200.
2. **Rolling cap cross-endpoint with realistic page boundaries**: 2 pages on Nearby (60 posts) + 2 pages on Following (60 posts) + 1 page on Global (30 posts) → 6th request capped.
3. **Rolling cap returns 200 not 429**: assert HTTP status code is exactly 200 (not 429) on hard-cap response AND no `Retry-After` header is set.
4. **Rolling cap aging via score-injection helper**: the test's `ageBucketEntries(key, count, ageMs)` helper directly seeds the underlying ZSET with old scores; a fresh read returns non-empty posts after the aged entries are pruned by the next `tryAcquire`.
5. **49-post boundary in session**: 49th delivery does NOT trigger `upsell.soft`.
6. **50-post boundary in session**: 50th delivery does NOT trigger `upsell.soft` (bucket fills to 50/50; pre-check still returned Allowed at 49 < 50).
7. **51st-post-delivery triggers `upsell.soft`**: 51st delivery returns `upsell.soft = true` AND `posts.length > 0`.
8. **Soft cap is non-blocking**: 51st-post-delivery returns posts in full despite `upsell.soft = true`.
9. **Soft cap per-session independence**: 50 reads under SID-1 (filling its bucket) + 1 read under SID-2 → SID-2 response does NOT carry `upsell.soft = true`.
10. **Premium rolling bypass**: `subscription_status = 'premium_active'` reads 200 posts in an hour; all responses HTTP 200 with no upsell flags; Redis-counter spy assertion shows zero rolling-bucket writes for that user.
11. **Premium billing retry bypass**: `subscription_status = 'premium_billing_retry'` matches Premium behavior.
12. **Premium short-circuits both buckets**: Redis-counter spy assertion shows zero session-bucket and zero rolling-bucket writes for a Premium caller across multiple requests.
13. **Tier read from auth principal**: query-counter spy assertion shows zero `users` SELECTs in the rate-limit pre-check path (tier comes from the auth-time principal).
14. **Pre-check before DB query**: a caller at the rolling cap who hits a non-existent post UUID still receives `posts = []` + `upsell.hard = true` (and the test asserts zero `posts` SELECTs were executed via a Postgres statement-log spy or query counter).
15. **Hash-tag key shapes**: rolling key matches exactly `{scope:rate_timeline_rolling}:{user:<uuid>}` AND session key matches exactly `{scope:rate_timeline_session}:{session:<uuid>__<session_id>}`. Verified via Lettuce `SlotHash.getSlot(key)` assertions and by inspecting the keys actually written to Redis. Both keys MUST pass the `RedisHashTagRule` strict regex.
16. **`X-Session-Id` missing fallback**: a request without the header succeeds; the session bucket key written is exactly `{scope:rate_timeline_session}:{session:<uuid>__no-session}`.
17. **`X-Session-Id` missing fallback accumulates**: 50 no-session post-deliveries → 51st triggers `upsell.soft = true`.
18. **`X-Session-Id` malformed (contains brace) falls back**: `X-Session-Id: }malicious{user:victim}` → key is `{scope:rate_timeline_session}:{session:<uuid>__no-session}` AND `RedisHashTagRule` does NOT fire on this rendered literal.
19. **`X-Session-Id` oversized (length 65) falls back**: 65-char value substituted with `no-session`.
20. **`X-Session-Id` UUID-shape accepted**: `4f8b9c1e-2d3a-4b5c-9d1e-7f8a9b0c1d2e` admitted unchanged, key shape verified.
21. **Mixed valid-SID and no-session bucket independence**: 50 reads under SID-1 fills its bucket + 1 read no-header → no-session bucket holds at most 1 entry; no `upsell.soft` on the no-header response.
22. **Auth failure short-circuit**: HTTP 401 without JWT; assert zero rate-limit Redis calls.
23. **Cursor invalid short-circuit**: HTTP 400 `invalid_cursor`; assert zero rate-limit Redis calls.
24. **Empty timeline (Following with zero follows)**: response has `posts = []` + the rolling pre-check still consumed exactly 1 slot (verified via bucket inspection: bucket size = 1 after the request, NOT 0 and NOT 2+; the post-increment skipped because `N - 1 = -1 → 0` calls).
25. **Shadow-banned Free user hits cap**: `is_shadow_banned = TRUE` + `free` user at 150/150 → `posts = []` + `upsell.hard = true` (no shadow-ban exemption).
26. **Shadow-banned Premium user uncapped**: `is_shadow_banned = TRUE` + `premium_active` reads 200 posts → no upsell flags.
27. **`upsell` object omitted when both flags false**: response below both caps does NOT contain an `upsell` key.
28. **`upsell.hard` omits `soft`**: hard-cap response has `upsell = { hard: true }` only — no `soft` key (the session pre-check was never executed).
29. **Over-admission single-request bound**: caller at 121/150 issues a request returning 30 posts → response succeeds; bucket ends at 150/150; the NEXT request is hard-capped (verifies the `N - 1 = 29` per-request bound).
30. **Concurrent over-admission bound (parallelism)**: 3 simultaneous requests by the same caller at 148/150 → all 3 succeed with 30 posts each; bucket ends at 150/150; the NEXT request is hard-capped.
31. **Cross-tab same-user multi-session accumulation**: 5 sessions × 30 posts each = 150 → 6th request hard-capped even though no individual session bucket exceeded 30/50.
32. **Stale principal Premium bypass**: caller's auth-time principal has `premium_active` AND DB row was downgraded mid-flight to `free` — the request uses the stale principal and bypasses both caps; on next JWT refresh, the user becomes subject to the caps.

#### Scenario: Test class discoverable
- **WHEN** running `./gradlew :backend:ktor:test --tests '*TimelineReadRateLimitTest*'`
- **THEN** the class is discovered AND every numbered scenario above corresponds to at least one `@Test` method
