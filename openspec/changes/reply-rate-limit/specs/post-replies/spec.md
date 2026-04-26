## ADDED Requirements

### Requirement: Daily rate limit — 20/day Free, unlimited Premium, with WIB stagger

`POST /api/v1/posts/{post_id}/replies` SHALL enforce a per-user **daily** rate limit of 20 successful reply INSERTs for Free-tier callers and unlimited for Premium-tier callers. The check runs via `RateLimiter.tryAcquire(userId, key, capacity, ttl)` against a Redis-backed counter keyed `{scope:rate_reply_day}:{user:<user_id>}`.

The TTL MUST be supplied via `computeTTLToNextReset(userId)` so the per-user offset distributes resets across `00:00–01:00 WIB` (per [`docs/05-Implementation.md`](../../../docs/05-Implementation.md) §1751-1755). Hardcoding `Duration.ofDays(1)` or any other fixed duration at this call site is a `RateLimitTtlRule` Detekt violation.

Tier gating reads `users.subscription_status` (V3 schema column, three-state enum). Both `premium_active` and `premium_billing_retry` (the 7-day grace state) MUST skip the daily limiter entirely. Only `free` (and any future state mapping to free) is subject to the cap.

**Read-site constraint.** Tier MUST be read from the request-scope `viewer` principal populated by the auth-jwt plugin (which loads `subscription_status` alongside the user identity for every authenticated request — added by `like-rate-limit` task 6.1.1; tracked as `auth-jwt` spec debt in `FOLLOW_UPS.md` since the field is not yet documented in any spec). The reply handler MUST NOT issue a fresh `SELECT subscription_status FROM users WHERE id = :caller` before the limiter; doing so would violate the "limiter runs before any DB read" guarantee. If the auth principal does not carry `subscription_status`, that's a defect in the auth path and MUST be fixed there, not worked around by adding a DB read in this handler.

**Defect-mode behavior.** If `viewer.subscriptionStatus` is unexpectedly null, the handler MUST treat the caller as Free (fail-closed against accidental Premium-tier escalation) and apply the cap. This is a defensive guardrail; the underlying defect MUST still be fixed in the auth-jwt path.

The daily limiter MUST run BEFORE any DB read (specifically, before the V8 `visible_posts` resolution SELECT and the `post_replies` INSERT) AND BEFORE the V8 content-length guard (so a Free attacker spamming oversized payloads still consumes slots and hits 429 at the cap, rather than burning unlimited "invalid_request" responses). On `RateLimited`, the response is HTTP 429 with body `{ "error": { "code": "rate_limited" } }` and a `Retry-After` header set to the seconds returned by the limiter (≥ 1).

The daily limiter MUST count successful slot acquisitions (i.e., requests that pass auth + UUID validation), not net replies. A reply that the user later soft-deletes via `DELETE /api/v1/posts/{post_id}/replies/{reply_id}` MUST NOT release its slot — the V8 idempotent-204 DELETE path is independent of rate-limit state and never decrements the daily counter.

#### Scenario: 20 replies within a day succeed
- **WHEN** Free-tier caller A successfully POSTs 20 distinct reply INSERTs within a single WIB day (each on a visible post, with valid 1–280 char content)
- **THEN** all 20 responses are HTTP 201

#### Scenario: 21st reply in same day rate-limited
- **WHEN** Free-tier caller A has 20 successful replies in the current WIB day AND attempts a 21st
- **THEN** the response is HTTP 429 with `error.code = "rate_limited"` AND a `Retry-After` header carrying a positive integer AND no `post_replies` row is inserted AND no V10 `notifications` row is inserted

#### Scenario: Retry-After approximates seconds to next reset
- **WHEN** the 21st request is rejected at WIB time `T`
- **THEN** the `Retry-After` value is approximately the number of seconds from `T` to (next 00:00 WIB) + (`hash(A.id) % 3600` seconds), within ±5 seconds (CI runner clock + Redis `TIME` skew tolerance, matches the like-rate-limit precedent)

#### Scenario: Premium user not gated by daily cap
- **WHEN** caller A has `users.subscription_status = 'premium_active'` AND attempts a 50th reply in a single WIB day
- **THEN** the response is HTTP 201 AND no daily-limiter check increments a counter for A (the daily limiter MUST be skipped, not consulted-and-overridden)

#### Scenario: Premium billing retry still treated as Premium
- **WHEN** caller A has `users.subscription_status = 'premium_billing_retry'` AND attempts a 40th reply in a single WIB day
- **THEN** the response is HTTP 201 AND the daily limiter is skipped (40 chosen distinct from the 30 used in override scenarios and the 50 used in `premium_active`, so test fixtures don't accidentally exercise multiple code paths together)

#### Scenario: Daily key uses hash-tag format
- **WHEN** the daily limiter check runs against Redis for caller `A` (uuid `U`)
- **THEN** the key used is exactly `"{scope:rate_reply_day}:{user:U}"`

#### Scenario: Soft-delete does not release a daily slot
- **WHEN** within the same WIB day Free-tier caller A executes this sequence: (1) POSTs 5 successful replies (bucket grows 0 → 5), (2) DELETEs one of those replies via `DELETE /api/v1/posts/{post_id}/replies/{reply_id}` (HTTP 204, idempotent; bucket stays at 5 — DELETE does NOT release), (3) POSTs 15 more successful replies (bucket grows 5 → 20), (4) POSTs a 21st reply attempt
- **THEN** the 21st POST is rejected with HTTP 429 `error.code = "rate_limited"` AND the daily bucket size remains at 20 (the 21st acquisition was rejected, so no slot was added) AND the soft-deleted reply's slot was never released — proving DELETE does not refund the cap and a user cannot "make room" by deleting old replies

#### Scenario: Null subscriptionStatus on viewer principal treated as Free (defensive)
- **WHEN** the auth-jwt plugin populates `viewer` with `subscriptionStatus = null` (auth-path defect) AND Free-equivalent caller A attempts a 21st reply in a day
- **THEN** the response is HTTP 429 (the handler MUST fall through to the Free-tier path, NOT skip the limiter). The implementation MUST also slf4j-WARN-log the defect (so the auth-path bug surfaces in monitoring), but the integration-test assertion is response status only — logging behavior is implementation-tested via service-level unit tests (mirrors the malformed-flag scenario hedge).

#### Scenario: Daily limiter runs before visible_posts resolution
- **WHEN** Free-tier caller A is at slot 21 AND attempts `POST /replies` on a post that ALSO does not exist in `visible_posts`
- **THEN** the response is HTTP 429 with `error.code = "rate_limited"` (NOT 404 `post_not_found`) AND no `visible_posts` SELECT was executed

#### Scenario: Daily limiter runs before content-length guard
- **WHEN** Free-tier caller A is at slot 21 AND POSTs a reply with 281-character content
- **THEN** the response is HTTP 429 with `error.code = "rate_limited"` (NOT 400 `invalid_request`) AND no JSON body parsing or content-length validation occurred

#### Scenario: WIB day rollover restores the cap
- **WHEN** Free-tier caller A is at slot 20 at WIB `23:59:00` AND a reply is attempted at WIB `00:00:01 + (hash(A.id) % 3600)` seconds the next day
- **THEN** the response is HTTP 201 (the user-specific reset window has passed AND old entries are pruned)

### Requirement: `premium_reply_cap_override` Firebase Remote Config flag

The reply service SHALL read the Firebase Remote Config flag `premium_reply_cap_override` on every `POST /api/v1/posts/{post_id}/replies` request from a Free-tier caller (Premium calls skip the daily limiter entirely, so the flag is irrelevant for them).

When the flag is unset, malformed, ≤ 0, or unavailable due to Remote Config error: the daily cap is `20` (the canonical default). When the flag is set to a positive integer N: the daily cap is `N`. The flag MUST mirror the `premium_like_cap_override` Decision 28 contract from `like-rate-limit` byte-for-byte (default-fallback shape, request-time read, mid-day flip-binds-immediately behavior).

#### Scenario: Flag unset uses 20 default
- **WHEN** `premium_reply_cap_override` is unset in Remote Config AND Free caller A attempts a 21st reply in a day
- **THEN** the response is HTTP 429 (daily cap = 20)

#### Scenario: Flag = 30 raises the cap
- **WHEN** `premium_reply_cap_override = 30` AND Free caller A has 30 successful replies today AND attempts a 31st reply
- **THEN** the 30th reply (within the override) returned HTTP 201 AND the 31st returns HTTP 429 with `error.code = "rate_limited"`

#### Scenario: Flag = 5 lowers the cap mid-day
- **WHEN** Free caller A has 7 successful replies today (under the original 20 cap) AND `premium_reply_cap_override = 5` is set AND A attempts an 8th reply
- **THEN** the response is HTTP 429 (the override applies at request time; users above the new cap are immediately rate-limited)

#### Scenario: Flag = 0 falls back to default
- **WHEN** `premium_reply_cap_override = 0` (invalid) AND Free caller A attempts a 21st reply in a day
- **THEN** the response is HTTP 429 (the cap remains 20, not 0)

#### Scenario: Flag malformed (non-integer string) falls back to default
- **WHEN** `premium_reply_cap_override = "thirty"` (or any non-integer-parseable value returned by the Remote Config client) AND Free caller A attempts a 21st reply in a day
- **THEN** the response is HTTP 429 (the cap remains 20). The implementation MUST also slf4j-WARN-log the malformed value so ops can detect the misconfiguration, but the integration-test assertion is the response status only (logging behavior is implementation-tested via service-level unit tests, not via a `ListAppender` in the HTTP-level test class)

#### Scenario: Flag oversized integer (above any sane cap) falls back to default
- **WHEN** `premium_reply_cap_override = Long.MAX_VALUE` or any positive value above 10,000 (clearly absurd) AND Free caller A attempts a 21st reply
- **THEN** the response is HTTP 429 (the cap is clamped to default 20). The upper-bound clamp prevents accidental cap removal via a typo (e.g., `2000000000` instead of `20`). Implementations MAY pick any specific clamp threshold ≥ 1000 (no abuse signal supports a Free user posting >1000 replies/day). The implementation MUST also slf4j-WARN-log the clamped value, but the integration-test assertion is response status only (mirrors the malformed-flag scenario hedge).

#### Scenario: Remote Config network failure falls back to default
- **WHEN** the Remote Config SDK throws an `IOException` (or any error) when the reply handler attempts to read `premium_reply_cap_override` AND Free caller A attempts a 21st reply in a day
- **THEN** the response is HTTP 429 (the cap defaults to 20) AND the request does NOT 5xx — Remote Config errors MUST NOT propagate into a user-facing 5xx since the safe default is conservative (the canonical 20/day cap)

#### Scenario: Flag does NOT affect Premium
- **WHEN** `premium_reply_cap_override = 5` AND Premium caller A attempts an 8th reply
- **THEN** the response is HTTP 201 (Premium skips the daily limiter entirely)

### Requirement: Limiter ordering and pre-DB execution

The reply service SHALL run, in this exact order, on every `POST /api/v1/posts/{post_id}/replies`:

1. Auth (existing `auth-jwt` plugin).
2. Path UUID validation (existing — 400 `invalid_uuid` on malformed path).
3. **Daily rate limiter** (`{scope:rate_reply_day}:{user:<uuid>}`) — Free only; Premium skips. On `RateLimited`: 429 + `Retry-After` + STOP.
4. JSON body parsing + V8 content-length guard (existing — 400 `invalid_request` on empty / whitespace-only / >280 / missing / null `content`).
5. V8 visibility resolution: `visible_posts` SELECT with bidirectional `user_blocks` exclusion (existing — 404 `post_not_found` on miss). 404 path does NOT release the limiter slot.
6. V8 `INSERT INTO post_replies (post_id, author_id, content)` and V10 notification emit in the same DB transaction.
7. Return HTTP 201 with the V8 response shape.

Steps 1–3 MUST run before any DB query. Steps 1–3 MUST NOT execute a DB statement. The reply handler MUST NOT call `RateLimiter.releaseMostRecent` on any path — every successful slot acquisition is permanent (there is no idempotent re-action path on `post_replies` analogous to the `INSERT ... ON CONFLICT DO NOTHING` no-op on `post_likes`).

_Note: the "limiter runs before visible_posts SELECT" and "limiter runs before content-length guard" assertions live in the Daily rate limit requirement above — its dedicated short-circuit scenarios cover both orderings. This requirement focuses on auth/UUID short-circuits BEFORE the limiter and slot-consumption rules AFTER the limiter._

#### Scenario: Auth failure short-circuits before limiter
- **WHEN** the request lacks a valid JWT
- **THEN** the response is HTTP 401 AND no limiter check runs (no Redis round-trip for auth-rejected requests)

#### Scenario: Invalid UUID short-circuits before limiter
- **WHEN** the request path is `POST /api/v1/posts/not-a-uuid/replies`
- **THEN** the response is HTTP 400 with `error.code = "invalid_uuid"` AND no limiter check runs

#### Scenario: 404 post_not_found consumes a slot
- **WHEN** Free caller A has 5 successful replies today AND POSTs `/replies` on a non-existent post UUID with valid content
- **THEN** the response is HTTP 404 with `error.code = "post_not_found"` AND the daily bucket size is 6 (the slot was consumed before the 404 was decided) AND the limiter MUST NOT call `releaseMostRecent`

#### Scenario: 400 invalid_request post-limiter consumes a slot
- **WHEN** Free caller A has 5 successful replies today AND POSTs `/replies` with empty content `{ "content": "" }` AND the post_id is a valid UUID
- **THEN** the response is HTTP 400 with `error.code = "invalid_request"` AND the daily bucket size is 6 (the slot was consumed because the limiter ran before the content guard) AND the limiter MUST NOT call `releaseMostRecent`

#### Scenario: Successful new reply consumes a slot
- **WHEN** Free caller A has 5 successful replies today AND POSTs a fresh reply on a visible post with valid content
- **THEN** the response is HTTP 201 AND the daily bucket size is 6

#### Scenario: V10 emit suppression on rate-limited request
- **WHEN** Free caller A is at slot 21 AND POSTs a valid reply on a post authored by a third party (parent author B)
- **THEN** the response is HTTP 429 AND zero `notifications` rows are inserted for B (the limiter rejects the request before the V10 emit pipeline runs); a test asserts this via INSERT-row-count snapshot on the `notifications` table around the request

#### Scenario: Transaction rollback (V10 emit failure) does NOT release the slot
- **WHEN** Free caller A is at slot 5 AND POSTs a valid reply AND the V10 notification emit fails (e.g., parent post author hard-deleted between visibility-resolution and emit) AND the encompassing DB transaction rolls back
- **THEN** zero `post_replies` rows persist (V8 transaction-rollback contract per `post-replies/spec.md` § "Notification INSERT failure rolls back the reply") AND the daily bucket size is 6 (the slot remains consumed; `releaseMostRecent` MUST NOT be called on the rollback path) AND the limiter response was `Allowed`, so a regression that adds a release on rollback would be caught by this scenario

### Requirement: DELETE /api/v1/posts/{post_id}/replies/{reply_id} is NOT rate-limited

The V8 author-only idempotent-204 soft-delete contract (see `post-replies` § "DELETE replies — author-only soft-delete, idempotent 204") MUST be preserved verbatim. The DELETE endpoint MUST NOT call `RateLimiter.tryAcquire` and MUST NOT consult any rate-limit state. A user at the daily reply cap of 20/20 MUST still be able to soft-delete their own replies.

The DELETE endpoint MUST also NOT release a previously-consumed daily slot (matches the "Soft-delete does not release a daily slot" scenario in the daily-cap requirement above).

#### Scenario: DELETE works when caller is at the daily cap
- **WHEN** Free caller A has 20/20 daily slots consumed AND DELETEs one of their own replies
- **THEN** the response is HTTP 204 AND no rate-limiter check occurred AND the daily bucket size is unchanged at 20 (DELETE does not release)

#### Scenario: DELETE on non-author still returns 204 regardless of rate-limit state
- **WHEN** Free caller A is at slot 21 AND DELETEs a reply that A does NOT own
- **THEN** the response is HTTP 204 (V8 opaque-204 contract) AND no rate-limiter check occurred — the DELETE path is independent of rate-limit state

### Requirement: GET /api/v1/posts/{post_id}/replies is NOT rate-limited at the per-endpoint layer

The V8 GET reply-list endpoint (see `post-replies` § "GET /api/v1/posts/{post_id}/replies endpoint exists" and the keyset-pagination requirement) MUST NOT call `RateLimiter.tryAcquire`. Per-endpoint read-side rate limiting on GET `/replies` is explicitly deferred (matches the `like-rate-limit` precedent which also did not rate-limit GET `/likes`); read-side throttling lives at the timeline session/hourly layer per [`docs/05-Implementation.md`](../../../docs/05-Implementation.md) (50/session soft + 150/hour hard).

#### Scenario: GET unaffected by daily reply cap
- **WHEN** Free caller A is at slot 21 AND GETs `/api/v1/posts/{post_id}/replies`
- **THEN** the response is HTTP 200 with the V8 keyset-paginated response shape AND no rate-limiter check occurred

### Requirement: Integration test coverage — reply rate limit

`ReplyRateLimitTest` (tagged `database`, backed by `InMemoryRateLimiter` extracted in `like-rate-limit` task 7.1 — Lua-level correctness is exercised separately by `:infra:redis`'s `RedisRateLimiterIntegrationTest`) SHALL exist alongside the existing `ReplyServiceTest` and cover, at minimum:

1. 20 replies in a day succeed for Free user.
2. 21st reply in the same day rate-limited; 429 + `Retry-After` + no `post_replies` row inserted AND zero `notifications` rows inserted (verified via INSERT-row-count snapshot before/after the request).
3. `Retry-After` value within ±5s of expected (`now` frozen via `AtomicReference<Instant>` clock injected into `InMemoryRateLimiter`).
4. Premium user (status `premium_active`) skips the daily limiter — 50 replies in a day all succeed (chosen distinct from the 30 used in scenario 6 to avoid test-data ambiguity).
5. Premium billing retry status (`premium_billing_retry`) also skips the daily limiter.
6. `premium_reply_cap_override` raises the cap to 30 for a Free user (31st rejected after a successful 30th).
7. `premium_reply_cap_override` lowers the cap mid-day; user previously at 7 is rejected at 8.
8. `premium_reply_cap_override = 0` falls back to default 20.
9. `premium_reply_cap_override = "thirty"` (malformed non-integer) falls back to default 20. The slf4j WARN log is implementation-detail; the integration test asserts response status only.
10. Remote Config network failure (SDK throws) falls back to default 20; no 5xx propagated.
11. 404 `post_not_found` consumes a slot (does NOT release): a request to a non-existent post UUID at slot 5 leaves the daily bucket at size 6.
12. 400 `invalid_request` on empty / whitespace / 281-char content consumes a slot when caller passed auth + UUID validation: leaves the daily bucket at size 6.
13. Daily limit hit short-circuits before the V8 content-length guard: at slot 21, POSTing 281-char content returns HTTP 429 (NOT HTTP 400) — behavioral proof that the limiter ran before content validation.
14. Daily limit hit short-circuits before `visible_posts` SELECT: at slot 21, POSTing to a non-existent post UUID returns HTTP 429 (NOT HTTP 404) — behavioral proof that the limiter ran before visibility resolution. (No DB-statement spy required; the 429-vs-404 dichotomy is the assertion.)
15. Hash-tag key shape verified: daily key = `{scope:rate_reply_day}:{user:<uuid>}` (via a `SpyRateLimiter` test double that captures `tryAcquire` keys).
16. WIB rollover: at the per-user reset moment the cap restores (frozen `AtomicReference<Instant>` clock advanced past `computeTTLToNextReset(userId)` + 1s).
17. Soft-delete does NOT release a daily slot: POST 5 successful replies → DELETE 1 (bucket stays at 5) → POST 15 more (bucket reaches 20) → 21st POST attempt rejected with HTTP 429 → bucket stays at 20.
18. DELETE works when caller is at the daily cap (20/20 cap, DELETE returns 204, no limiter consulted, bucket unchanged).
19. GET unaffected by the daily cap (caller at 21/20 still gets HTTP 200 from the V8 GET endpoint).
20. Tier (`subscription_status`) is read from the auth-time `viewer` principal: a `SpyRateLimiter` confirms `tryAcquire` was invoked AND the response is HTTP 201 — combined with scenario 14 (limiter-before-visible_posts), this proves no DB read sits between auth and limiter.
21. The reply handler MUST NOT call `RateLimiter.releaseMostRecent` on any code path — assert via `SpyRateLimiter` that no `releaseMostRecent` invocation occurs across the full scenario set above.
22. Null `subscriptionStatus` on the viewer principal is treated as Free (defensive guardrail) — limiter applied, 21st request rejected with HTTP 429.
23. V10 transaction rollback does NOT release the slot: simulate the parent-post-hard-deleted-between-visibility-and-emit edge case, verify zero `post_replies` rows persisted AND the daily bucket size still grew by 1 (no `releaseMostRecent` was called).
24. `premium_reply_cap_override = Long.MAX_VALUE` (or any value > 10,000) clamps to default 20.

#### Scenario: Test class discoverable
- **WHEN** running `./gradlew :backend:ktor:test --tests '*ReplyRateLimitTest*'`
- **THEN** the class is discovered AND every numbered scenario above corresponds to at least one `@Test` method
