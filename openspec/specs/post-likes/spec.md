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

