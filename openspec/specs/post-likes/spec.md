# post-likes Specification

## Purpose

Post likes: schema, idempotent like/unlike endpoints, shadow-ban-aware count, and the 404-on-invisibility contract that matches the timelines.
## Requirements
### Requirement: post_likes table created via Flyway V7

A migration `V7__post_likes.sql` SHALL create the `post_likes` table with columns:
- `post_id UUID NOT NULL REFERENCES posts(id) ON DELETE CASCADE`
- `user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE`
- `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`
- `PRIMARY KEY (post_id, user_id)`

The migration MUST also create both directional indexes:
- `post_likes_user_idx ON post_likes (user_id, created_at DESC)` — supports "which posts has X liked (newest first)".
- `post_likes_post_idx ON post_likes (post_id, created_at DESC)` — supports "who has liked this post (newest first)".

The column names (`post_id`, `user_id`) MUST match `docs/05-Implementation.md` §694–702 verbatim — no renames. The V7 header MUST document the schema-level FK dependency on V3 (`users`) AND V4 (`posts`), note that there is NO runtime coupling like V6's mutual-block read, and list the consumers (like service writes, likes-count endpoint, both timelines' `liked_by_viewer` LEFT JOIN).

#### Scenario: Migration runs cleanly from V6
- **WHEN** Flyway runs `V7__post_likes.sql` against a database at V6
- **THEN** the migration succeeds AND `flyway_schema_history` records V7

#### Scenario: All canonical columns present
- **WHEN** querying `information_schema.columns WHERE table_name = 'post_likes'`
- **THEN** the columns `post_id`, `user_id`, `created_at` are present with the documented types and nullability

#### Scenario: Primary key rejects duplicate
- **WHEN** two INSERTs share the same `(post_id, user_id)`
- **THEN** the second INSERT fails with a unique-constraint violation (SQLSTATE `23505`)

#### Scenario: Both directional indexes exist
- **WHEN** querying `pg_indexes WHERE tablename = 'post_likes'`
- **THEN** the result contains an index whose definition orders by `user_id` then `created_at DESC` AND an index whose definition orders by `post_id` then `created_at DESC`

#### Scenario: Cascade on post hard-delete
- **WHEN** a `posts` row referenced as `post_id` is hard-deleted
- **THEN** all `post_likes` rows where `post_id = <that post>` are cascade-deleted

#### Scenario: Cascade on user hard-delete
- **WHEN** a `users` row referenced as `user_id` is hard-deleted
- **THEN** all `post_likes` rows where `user_id = <that user>` are cascade-deleted

### Requirement: POST /api/v1/posts/{post_id}/like creates a like (idempotent)

A Ktor route SHALL be registered at `POST /api/v1/posts/{post_id}/like` requiring Bearer JWT auth via the existing `auth-jwt` plugin. On success, the endpoint MUST insert `(post_id = path-post_id, user_id = caller)` via `INSERT ... ON CONFLICT (post_id, user_id) DO NOTHING` and return HTTP 204 with no body. Re-liking an already-liked post MUST also return 204 (no error).

#### Scenario: First like returns 204
- **WHEN** caller A likes a visible post P for the first time
- **THEN** the response is HTTP 204 AND a `post_likes` row `(P, A, ...)` exists

#### Scenario: Re-like idempotent
- **WHEN** caller A likes post P AND then immediately likes P again
- **THEN** both responses are HTTP 204 AND there is exactly one `post_likes` row `(P, A, ...)`

#### Scenario: Unauthenticated rejected
- **WHEN** the request lacks a valid JWT
- **THEN** the response is HTTP 401 with `error.code = "unauthenticated"`

### Requirement: Invalid UUID in path rejected with 400

The route handler SHALL reject requests where the path `post_id` segment is not a syntactically valid UUID with HTTP 400 and error code `invalid_uuid`. This check MUST run before any DB read.

#### Scenario: Non-UUID path rejected
- **WHEN** the request path is `POST /api/v1/posts/not-a-uuid/like`
- **THEN** the response is HTTP 400 with `error.code = "invalid_uuid"` AND no DB statement is executed

### Requirement: 404 on missing, soft-deleted, or block-hidden post (no 403 leak)

Before inserting, the route handler SHALL resolve the target post via a SELECT from `visible_posts` that includes BOTH bidirectional `user_blocks` NOT-IN subqueries against the caller:

```
SELECT p.id
FROM visible_posts p
WHERE p.id = :post_id
  AND p.author_user_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = :viewer)
  AND p.author_user_id NOT IN (SELECT blocker_id FROM user_blocks WHERE blocked_id = :viewer)
LIMIT 1
```

If the SELECT returns zero rows, the response MUST be HTTP 404 with error code `post_not_found`. This single error code MUST cover all three cases: post does not exist, post was soft-deleted (and thus excluded by `visible_posts`), and post author has blocked the caller OR the caller has blocked the post author. The endpoint MUST NOT return HTTP 403 under any circumstance and MUST NOT leak which of the three cases applied.

Both block-exclusion subqueries MUST be present simultaneously so `BlockExclusionJoinRule` passes on the like-service source.

#### Scenario: Unknown post id
- **WHEN** caller A likes a UUID that does not exist in `posts`
- **THEN** the response is HTTP 404 with `error.code = "post_not_found"`

#### Scenario: Soft-deleted post
- **WHEN** caller A likes a post that has `is_auto_hidden = TRUE` (excluded by `visible_posts`)
- **THEN** the response is HTTP 404 with `error.code = "post_not_found"` AND no `post_likes` row is inserted

#### Scenario: Caller has blocked post author
- **WHEN** caller A has a `user_blocks` row `(A, B)` AND tries to like a post whose `author_user_id = B`
- **THEN** the response is HTTP 404 with `error.code = "post_not_found"` AND no `post_likes` row is inserted

#### Scenario: Post author has blocked caller
- **WHEN** author B has a `user_blocks` row `(B, A)` AND caller A tries to like a post whose `author_user_id = B`
- **THEN** the response is HTTP 404 with `error.code = "post_not_found"` AND no `post_likes` row is inserted

#### Scenario: 404 response body identical across cases
- **WHEN** the 404 is returned for any of {missing post, soft-deleted post, caller-blocked-author, author-blocked-caller}
- **THEN** the response body is the constant JSON envelope `{ "error": { "code": "post_not_found" } }` with no additional fields

### Requirement: DELETE /api/v1/posts/{post_id}/like removes a like (idempotent)

A Ktor route SHALL be registered at `DELETE /api/v1/posts/{post_id}/like` requiring Bearer JWT auth. The endpoint MUST execute `DELETE FROM post_likes WHERE post_id = :path_post_id AND user_id = :caller` and return HTTP 204 regardless of whether a row was deleted. The endpoint MUST NOT perform a preliminary `visible_posts` SELECT or block-exclusion check — the caller is always allowed to remove their own like row, and the PK scopes the DELETE to `user_id = :caller`.

#### Scenario: Existing like removed
- **WHEN** caller A has previously liked post P AND now calls `DELETE /api/v1/posts/P/like`
- **THEN** the response is HTTP 204 AND no `post_likes` row `(P, A, ...)` exists

#### Scenario: No-op delete still 204
- **WHEN** caller A has not liked P AND calls `DELETE /api/v1/posts/P/like`
- **THEN** the response is HTTP 204 (no error, no 404)

#### Scenario: Delete after author block still 204
- **WHEN** caller A previously liked post P (author = B) AND B has since blocked A AND A calls `DELETE /api/v1/posts/P/like`
- **THEN** the response is HTTP 204 AND no `post_likes` row `(P, A, ...)` exists (the caller can always remove their own like regardless of current block state)

#### Scenario: Invalid UUID rejected
- **WHEN** the request path is `DELETE /api/v1/posts/not-a-uuid/like`
- **THEN** the response is HTTP 400 with `error.code = "invalid_uuid"` AND no DB statement is executed

#### Scenario: Unauthenticated rejected
- **WHEN** the request lacks a valid JWT
- **THEN** the response is HTTP 401 with `error.code = "unauthenticated"`

### Requirement: GET /api/v1/posts/{post_id}/likes/count returns shadow-ban-filtered count

A Ktor route SHALL be registered at `GET /api/v1/posts/{post_id}/likes/count` requiring Bearer JWT auth. The endpoint MUST compute the count via:

```
SELECT COUNT(*)
FROM post_likes pl
JOIN visible_users vu ON vu.id = pl.user_id
WHERE pl.post_id = :post_id
```

Shadow-banned likers MUST be excluded from the count (that is the purpose of the `visible_users` JOIN, per `docs/05-Implementation.md` §705). The response shape MUST be HTTP 200 with body `{ "count": <non-negative integer> }`.

The route MUST run the same 404-resolution SELECT as `POST /like` BEFORE the count query — if the post is missing, soft-deleted, or block-hidden to the caller, the response MUST be HTTP 404 with error code `post_not_found`. The 404 cases and body format MUST be identical to `POST /like`.

#### Scenario: Count returns shadow-ban-filtered integer
- **WHEN** post P has 5 `post_likes` rows of which 2 reference shadow-banned users
- **THEN** the response is HTTP 200 with body `{ "count": 3 }`

#### Scenario: Count is zero when no likes
- **WHEN** post P has no `post_likes` rows AND is visible to the caller
- **THEN** the response is HTTP 200 with body `{ "count": 0 }`

#### Scenario: Block-hidden post returns 404
- **WHEN** caller A tries to read the count for a post whose author B is in a `user_blocks` row with A (either direction)
- **THEN** the response is HTTP 404 with `error.code = "post_not_found"`

#### Scenario: Missing post returns 404
- **WHEN** caller A tries to read the count for a UUID that does not exist in `posts`
- **THEN** the response is HTTP 404 with `error.code = "post_not_found"`

#### Scenario: Unauthenticated rejected
- **WHEN** the request lacks a valid JWT
- **THEN** the response is HTTP 401 with `error.code = "unauthenticated"`

### Requirement: Count endpoint does NOT apply viewer-block exclusion

The count endpoint MUST NOT apply bidirectional `user_blocks` NOT-IN subqueries against the liker set (i.e., MUST NOT exclude likes from users the caller has blocked or been blocked by — other than the post-author block which gates visibility of the post itself). The count value is a function of `(post_id, shadow-ban state)` only — NOT of the caller's block list. This is a deliberate privacy tradeoff: a per-viewer count would vary with the caller's `user_blocks` state and would enable a blocker to enumerate which of the likers they have blocked by diffing two counter responses.

The implementation MUST document this tradeoff in a comment at the count query's call site. If this decision is ever reversed, the `post-likes` spec MUST be updated first to document the new tradeoff.

#### Scenario: Blocked likers still contribute to count
- **WHEN** post P has 3 likes from users U1, U2, U3 (none shadow-banned) AND caller A has a `user_blocks` row `(A, U1)`
- **THEN** the response count is 3 (NOT 2) — A's block of U1 does NOT filter the count

#### Scenario: Count does not vary between viewers by block state
- **WHEN** two callers A and B read the count for the same visible post P AND A and B have different `user_blocks` lists
- **THEN** both responses contain the same count value

### Requirement: Integration test coverage

`LikeEndpointsTest` (tagged `database`) SHALL cover end-to-end against a Postgres test DB, at minimum:
1. First like returns 204 and creates a row.
2. Re-like is idempotent (204, still one row).
3. `POST /like` on a missing UUID: 404 `post_not_found`.
4. `POST /like` on a soft-deleted post: 404 `post_not_found`, no row inserted.
5. `POST /like` when caller has blocked the post author: 404 `post_not_found`, no row inserted.
6. `POST /like` when the post author has blocked the caller: 404 `post_not_found`, no row inserted.
7. 404 response body identical across all four invisibility cases.
8. `POST /like` on a non-UUID path: 400 `invalid_uuid`, no DB statement.
9. `DELETE /like` removes an existing row, returns 204.
10. `DELETE /like` is a no-op on missing row, returns 204.
11. `DELETE /like` succeeds even after the post author has blocked the caller (returns 204, row removed).
12. `DELETE /like` on a non-UUID path: 400 `invalid_uuid`.
13. `GET /likes/count` returns the shadow-ban-filtered count on a visible post.
14. `GET /likes/count` returns 0 on a visible post with no likes.
15. `GET /likes/count` returns 404 on a missing or block-hidden post.
16. `GET /likes/count` does NOT vary per-viewer by caller's `user_blocks` state (two callers with different block lists see the same number).
17. All three endpoints return 401 without JWT.

`MigrationV7SmokeTest` (tagged `database`) SHALL cover, against a fresh Postgres test database with V1–V7 applied:
1. Both directional indexes exist with the documented column orders (`post_likes_user_idx`, `post_likes_post_idx`).
2. The PRIMARY KEY `(post_id, user_id)` is enforced (duplicate INSERT fails with SQLSTATE `23505`).
3. The FK cascade behavior on `posts` hard-delete (post-side cascade).
4. The FK cascade behavior on `users` hard-delete (user-side cascade).
5. Applying V7 against a database at V6 advances to V7; applying V7 against V7 is a no-op.

#### Scenario: Both test classes discoverable
- **WHEN** running `./gradlew :backend:ktor:test --tests '*LikeEndpointsTest*' --tests '*MigrationV7SmokeTest*'`
- **THEN** both classes are discovered AND every numbered scenario above corresponds to at least one `@Test` method

### Requirement: Successful like emits a post_liked notification for the post author

V10 introduces the FIRST notification-write side-effect for likes. On a successful `POST /api/v1/posts/{post_id}/like` whose `INSERT ... ON CONFLICT (post_id, user_id) DO NOTHING` actually inserts a new row (not a re-like of an already-liked post), the `LikeService` SHALL invoke `NotificationEmitter.emit(type = 'post_liked', recipient = post.author_id, actor = caller, target_type = 'post', target_id = post.id, body_data = {post_excerpt: <first 80 code points of post.content>})` in the SAME DB transaction as the `post_likes` INSERT.

The emit is subject to the `NotificationEmitter` suppression rules (see `in-app-notifications` capability):
- Self-action: if `post.author_id == caller`, no notification row is written.
- Block: if Alice (the post author) blocked Bob (the caller), or Bob blocked Alice, no notification row is written.

Re-liking an already-liked post (which today returns 204 via `ON CONFLICT DO NOTHING`) MUST NOT emit a notification — the emit only fires on the transition from "not liked" to "liked". Unlike (`DELETE /like`) MUST NOT emit a counter-notification (no `unliked` type exists in the V10 enum).

If the notification INSERT fails (e.g. recipient user hard-deleted between request and emit), the encompassing transaction rolls back; the `post_likes` INSERT does NOT persist. This matches the strict-coupling contract in the `in-app-notifications` capability.

Response shapes for `POST /like` and `DELETE /like` MUST NOT change. The `liked_by_viewer` field on timelines is also unchanged.

#### Scenario: Bob likes Alice's post produces post_liked notification for Alice
- **WHEN** Bob POSTs `/api/v1/posts/{alicePostId}/like` AND no block exists between Alice and Bob
- **THEN** HTTP 204 AND exactly one `notifications` row exists with `user_id = Alice.id, type = 'post_liked', actor_user_id = Bob.id, target_type = 'post', target_id = alicePostId, body_data.post_excerpt = <first 80 code points of post>`

#### Scenario: Self-like produces no notification
- **WHEN** Alice POSTs `/api/v1/posts/{alicePostId}/like` on her own post
- **THEN** HTTP 204 AND zero `notifications` rows are inserted

#### Scenario: Like from blocked user produces no notification (Alice blocked Bob)
- **WHEN** Alice has a `user_blocks` row `(blocker_id = Alice, blocked_id = Bob)` AND Bob POSTs `/api/v1/posts/{alicePostId}/like`
- **THEN** HTTP 204 AND zero `notifications` rows are inserted for Alice

#### Scenario: Like from blocked user produces no notification (Bob blocked Alice)
- **WHEN** Bob has a `user_blocks` row `(blocker_id = Bob, blocked_id = Alice)` AND Bob POSTs `/api/v1/posts/{alicePostId}/like`
- **THEN** HTTP 204 AND zero `notifications` rows are inserted for Alice

#### Scenario: Re-like does NOT emit duplicate notification
- **WHEN** Bob has already liked Alice's post (producing one notification) AND Bob POSTs `/api/v1/posts/{alicePostId}/like` again
- **THEN** HTTP 204 (idempotent) AND still exactly one `notifications` row for this (post, liker, recipient) combination

#### Scenario: Unlike does NOT emit a counter-notification
- **WHEN** Bob has liked Alice's post AND Bob DELETEs `/api/v1/posts/{alicePostId}/like`
- **THEN** HTTP 204 AND no new `notifications` row is inserted (the existing `post_liked` row is NOT deleted either — it remains in Alice's feed as a historical record)

#### Scenario: Notification INSERT failure rolls back the like
- **WHEN** Bob attempts to like Alice's post AND Alice is hard-deleted between the like validation and the notification INSERT (causing FK violation on `notifications.user_id`)
- **THEN** the transaction rolls back AND zero `post_likes` rows persist

#### Scenario: Like/Unlike response shapes unchanged
- **WHEN** inspecting the response bodies of `POST /like` and `DELETE /like` pre-V10 and post-V10
- **THEN** both remain HTTP 204 with no body (V10 does not alter the response contract)

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

