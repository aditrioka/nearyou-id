# post-replies Specification

## Purpose
TBD - created by archiving change post-replies-v8. Update Purpose after archive.
## Requirements
### Requirement: post_replies table created via Flyway V8

A migration `V8__post_replies.sql` SHALL create the `post_replies` table verbatim from `docs/05-Implementation.md` §716–729 with columns:
- `id UUID PRIMARY KEY DEFAULT gen_random_uuid()`
- `post_id UUID NOT NULL REFERENCES posts(id) ON DELETE CASCADE`
- `author_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT`
- `content VARCHAR(280) NOT NULL`
- `is_auto_hidden BOOLEAN NOT NULL DEFAULT FALSE`
- `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`
- `updated_at TIMESTAMPTZ` (nullable)
- `deleted_at TIMESTAMPTZ` (nullable)

Plus two partial indexes filtered by `deleted_at IS NULL`:
- `post_replies_post_idx ON post_replies(post_id, created_at DESC) WHERE deleted_at IS NULL`
- `post_replies_author_idx ON post_replies(author_id, created_at DESC) WHERE deleted_at IS NULL`

`author_id RESTRICT` MUST mirror V4 `posts.author_id`: a bare `DELETE FROM users` MUST fail while any non-tombstoned `post_replies` row references that user. The tombstone + hard-delete worker (separate future change) is responsible for removing replies before the author; V8 does NOT change that worker.

#### Scenario: Migration runs cleanly from V7
- **WHEN** Flyway runs `V8__post_replies.sql` against a DB at V7
- **THEN** the migration succeeds AND `flyway_schema_history` records V8

#### Scenario: All canonical columns present
- **WHEN** querying `information_schema.columns WHERE table_name = 'post_replies'`
- **THEN** every column above is present with its documented type, nullability, and default

#### Scenario: Both partial indexes exist with deleted_at IS NULL predicate
- **WHEN** querying `pg_indexes WHERE tablename = 'post_replies'`
- **THEN** the result contains `post_replies_post_idx` AND `post_replies_author_idx` AND each definition contains `WHERE (deleted_at IS NULL)`

#### Scenario: author_id RESTRICT blocks user delete with live replies
- **WHEN** a `users` row has at least one non-tombstoned `post_replies` row AND a direct `DELETE FROM users WHERE id = ?` is attempted (no `posts` rows present to independently block)
- **THEN** the DELETE fails with a foreign-key violation (SQLSTATE `23503`)

#### Scenario: post_id CASCADE removes replies on post delete
- **WHEN** a `posts` row is hard-deleted
- **THEN** every `post_replies` row referencing that post via `post_id` is cascade-deleted in the same transaction

### Requirement: POST /api/v1/posts/{post_id}/replies endpoint exists

A Ktor route SHALL be registered at `POST /api/v1/posts/{post_id}/replies`. The route MUST require Bearer JWT authentication via the existing `auth-jwt` plugin; an unauthenticated request MUST receive HTTP 401 with error code `unauthenticated`. The route handler MUST live under `backend/ktor/src/main/kotlin/id/nearyou/app/engagement/`.

#### Scenario: Unauthenticated rejected
- **WHEN** `POST /api/v1/posts/{uuid}/replies` is called with no `Authorization` header
- **THEN** the response is HTTP 401 with `error.code = "unauthenticated"`

#### Scenario: Authenticated routed to handler
- **WHEN** the same request is made with a valid Bearer JWT
- **THEN** the request reaches the handler (HTTP status is not 401)

### Requirement: POST replies — UUID path validation

The route SHALL parse `{post_id}` via `UUID.fromString(...)`. If the value is not a valid UUID, the route MUST return HTTP 400 with error code `invalid_uuid`. The UUID check MUST run before any repository call, any body parsing, and any DB query.

#### Scenario: Non-UUID path returns 400
- **WHEN** the route is called with `{post_id} = "not-a-uuid"`
- **THEN** the response is HTTP 400 with `error.code = "invalid_uuid"` AND no DB query executes

### Requirement: POST replies — content length guard (1–280)

The request body SHALL be JSON of shape `{ "content": string }`. The `content` field MUST be non-null, MUST be ≥ 1 character (trimmed), and MUST be ≤ 280 characters. Violations MUST yield HTTP 400 with error code `invalid_request`. The 280-character cap MUST match the `post_replies.content VARCHAR(280)` column length (defense-in-depth with the content-length middleware in Phase 1 item 20).

#### Scenario: Empty content rejected
- **WHEN** the body is `{ "content": "" }` or `{ "content": "   " }`
- **THEN** the response is HTTP 400 with `error.code = "invalid_request"`

#### Scenario: 281-character content rejected
- **WHEN** the body is `{ "content": "<281 chars>" }`
- **THEN** the response is HTTP 400 with `error.code = "invalid_request"` AND no DB INSERT executes

#### Scenario: Missing content field rejected
- **WHEN** the body is `{}` or `{ "content": null }`
- **THEN** the response is HTTP 400 with `error.code = "invalid_request"`

### Requirement: POST replies — post visibility resolution

Before INSERTing a reply, the service SHALL resolve the parent post via the same visibility pattern used by `LikeService.resolveVisiblePost`: `SELECT p.id FROM visible_posts p WHERE p.id = :post_id AND p.author_user_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = :caller) AND p.author_user_id NOT IN (SELECT blocker_id FROM user_blocks WHERE blocked_id = :caller) LIMIT 1`. If the SELECT returns no row, the service MUST throw `PostNotFoundException` and the route MUST return HTTP 404 with error code `post_not_found`. The response body MUST be the constant `{ "error": { "code": "post_not_found" } }` with no additional fields — MUST NOT distinguish "missing" vs "soft-deleted" vs "caller-blocked-author" vs "author-blocked-caller" vs "post is auto-hidden". The route MUST NOT return HTTP 403.

#### Scenario: Missing post returns opaque 404
- **WHEN** `{post_id}` is a well-formed UUID that does not exist in `posts`
- **THEN** the response is HTTP 404 with body `{ "error": { "code": "post_not_found" } }`

#### Scenario: Soft-deleted post returns opaque 404
- **WHEN** the parent post has `deleted_at IS NOT NULL`
- **THEN** the response is HTTP 404 with the same opaque `post_not_found` code (identical to the missing-post response)

#### Scenario: Auto-hidden post returns opaque 404
- **WHEN** the parent post has `is_auto_hidden = TRUE` (filtered by `visible_posts`)
- **THEN** the response is HTTP 404 with `post_not_found` — indistinguishable from the missing-post response

#### Scenario: Caller blocked post author returns opaque 404
- **WHEN** a `user_blocks` row `(blocker_id = caller, blocked_id = post_author)` exists
- **THEN** the response is HTTP 404 with `post_not_found` — NOT HTTP 403 (no block-state leak)

#### Scenario: Post author blocked caller returns opaque 404
- **WHEN** a `user_blocks` row `(blocker_id = post_author, blocked_id = caller)` exists
- **THEN** the response is HTTP 404 with `post_not_found` — NOT HTTP 403

### Requirement: POST replies — INSERT and success response

On successful visibility resolution, the service SHALL `INSERT INTO post_replies (post_id, author_id, content) VALUES (:post_id, :caller, :content)` and return the inserted row. The endpoint MUST respond HTTP 201 with body:

```json
{
  "id": "<uuid>",
  "post_id": "<uuid>",
  "author_id": "<uuid>",
  "content": "<string>",
  "is_auto_hidden": false,
  "created_at": "<ISO-8601 UTC>",
  "updated_at": null,
  "deleted_at": null
}
```

`is_auto_hidden` MUST be `false` on a fresh INSERT (column default). `updated_at` and `deleted_at` MUST be `null` on a fresh INSERT. `author_id` MUST be the caller's UUID (derived from the JWT `sub`, not the request body).

#### Scenario: Happy path returns 201 with full reply
- **WHEN** a visible post exists AND the caller sends `{ "content": "nice post" }`
- **THEN** the response is HTTP 201 AND the body contains `id`, `post_id`, `author_id = caller`, `content = "nice post"`, `is_auto_hidden = false`, `created_at` non-null, `updated_at = null`, `deleted_at = null`

#### Scenario: author_id comes from JWT, not body
- **WHEN** the body includes `{ "content": "hi", "author_id": "<different uuid>" }` (rogue client trying to spoof)
- **THEN** the INSERT uses the JWT `sub` UUID and ignores the body's `author_id`

### Requirement: GET /api/v1/posts/{post_id}/replies endpoint exists

A Ktor route SHALL be registered at `GET /api/v1/posts/{post_id}/replies`. The route MUST require Bearer JWT authentication; an unauthenticated request MUST receive HTTP 401 with `error.code = "unauthenticated"`. The UUID path-param MUST be validated exactly like the POST route (HTTP 400 `invalid_uuid` on a non-UUID value). The route MUST accept an optional `cursor` query parameter; a malformed cursor MUST yield HTTP 400 `invalid_cursor`.

#### Scenario: Unauthenticated rejected
- **WHEN** `GET /api/v1/posts/{uuid}/replies` is called with no `Authorization` header
- **THEN** the response is HTTP 401 with `error.code = "unauthenticated"`

#### Scenario: Malformed cursor rejected
- **WHEN** `cursor=not-a-base64-json`
- **THEN** the response is HTTP 400 with `error.code = "invalid_cursor"`

### Requirement: GET replies — parent-post visibility resolution

Before listing replies, the service SHALL resolve the parent post using the SAME visibility query as POST replies (`visible_posts` + bidirectional `user_blocks` NOT-IN). If the parent post is invisible to the caller (missing / soft-deleted / auto-hidden / caller-blocked-author / author-blocked-caller), the endpoint MUST return HTTP 404 with body `{ "error": { "code": "post_not_found" } }` — the same opaque envelope used by POST. The endpoint MUST NOT return an empty reply list in place of 404 when the parent is invisible.

#### Scenario: Invisible parent returns 404, not empty list
- **WHEN** the parent post is soft-deleted OR auto-hidden OR subject to a bidirectional block with the caller
- **THEN** the response is HTTP 404 with `post_not_found` — NOT HTTP 200 with `replies = []`

### Requirement: GET replies — canonical query with block exclusion and auto-hidden filter

The data query SHALL read from `post_replies` JOINed to `visible_users` (shadow-ban exclusion on the reply author) AND SHALL apply bidirectional `user_blocks` NOT-IN on `author_id`:
- `author_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = :caller)` (viewer-blocked reply authors hidden)
- `author_id NOT IN (SELECT blocker_id FROM user_blocks WHERE blocked_id = :caller)` (authors-who-blocked-viewer hidden)

The query MUST also filter:
- `deleted_at IS NULL` (soft-deleted replies hidden)
- `(is_auto_hidden = FALSE OR author_id = :caller)` (author still sees their own auto-hidden replies; everyone else does not)

Both `user_blocks` NOT-IN subqueries MUST be present simultaneously so `BlockExclusionJoinRule` passes on the Kotlin string literal.

#### Scenario: Soft-deleted reply excluded for everyone
- **WHEN** a reply has `deleted_at IS NOT NULL`
- **THEN** that reply does NOT appear in any caller's response (including the author's)

#### Scenario: Shadow-banned reply author excluded via visible_users JOIN
- **WHEN** a reply's author has `is_shadow_banned = TRUE`
- **THEN** that reply does NOT appear in the response (filtered by the `JOIN visible_users`)

#### Scenario: Viewer-blocked reply author excluded
- **WHEN** the caller has a `user_blocks` row `(blocker_id = caller, blocked_id = X)` AND X has a reply to the parent post
- **THEN** X's reply does NOT appear in the response

#### Scenario: Reply-author-blocks-viewer excluded
- **WHEN** a `user_blocks` row `(blocker_id = X, blocked_id = caller)` exists AND X has a reply to the parent post
- **THEN** X's reply does NOT appear in the response

#### Scenario: Auto-hidden reply visible to its author
- **WHEN** reply R has `is_auto_hidden = TRUE` AND the caller is R's author
- **THEN** R appears in the response

#### Scenario: Auto-hidden reply hidden from non-authors
- **WHEN** reply R has `is_auto_hidden = TRUE` AND the caller is NOT R's author
- **THEN** R does NOT appear in the response

### Requirement: GET replies — keyset pagination on (created_at DESC, id DESC)

The endpoint SHALL paginate via keyset on `(created_at DESC, id DESC)` using `post_replies_post_idx` (partial, `deleted_at IS NULL`). The cursor format MUST be the SAME base64url-encoded JSON `{"c":"<created_at ISO-8601>","i":"<reply UUID>"}` used by the timelines. The endpoint MUST NOT use SQL `OFFSET`.

#### Scenario: First page no cursor
- **WHEN** the request has no `cursor`
- **THEN** the SQL query has no `(created_at, id) <` clause AND returns the most recent replies (`ORDER BY created_at DESC, id DESC`)

#### Scenario: Subsequent page with cursor
- **WHEN** the response on page 1 contains `next_cursor = "<token>"` AND the next request supplies `cursor=<token>`
- **THEN** the SQL query includes `(created_at, id) < (cursor.c, cursor.i)` AND no row from page 1 appears in page 2

### Requirement: GET replies — per-page cap of 30

The endpoint SHALL `LIMIT` the SQL query to `31` (page-size 30 plus one probe row). The response `replies` array MUST contain at most 30 elements. The probe row, if present, MUST NOT appear in the response and MUST seed `next_cursor`.

#### Scenario: At most 30 replies in response
- **WHEN** the parent post has 100 visible replies for the caller
- **THEN** `response.replies.length <= 30`

#### Scenario: next_cursor present when more exist
- **WHEN** >30 replies are visible AND the response contains 30 replies
- **THEN** `response.next_cursor` is a non-null base64url string

#### Scenario: next_cursor null on last page
- **WHEN** the response contains <30 replies (or exactly 30 with no further matches)
- **THEN** `response.next_cursor` is `null`

### Requirement: GET replies — response shape

A successful response SHALL be HTTP 200 with body:

```json
{
  "replies": [
    {
      "id": "<uuid>",
      "post_id": "<uuid>",
      "author_id": "<uuid>",
      "content": "<string>",
      "is_auto_hidden": <boolean>,
      "created_at": "<ISO-8601 UTC>",
      "updated_at": "<ISO-8601 UTC or null>",
      "deleted_at": null
    }
  ],
  "next_cursor": "<string or null>"
}
```

`deleted_at` MUST always be `null` in the response (soft-deleted rows are excluded upstream by the query). `is_auto_hidden` MUST reflect the stored column value verbatim — the author's response MAY contain rows with `is_auto_hidden = true`; other viewers will never see such rows (filtered by the query).

#### Scenario: deleted_at always null in response
- **WHEN** any reply appears in the response
- **THEN** its `deleted_at` field is JSON `null`

#### Scenario: Author sees is_auto_hidden = true
- **WHEN** reply R has `is_auto_hidden = TRUE` AND the caller is R's author
- **THEN** R appears in the response with `is_auto_hidden = true`

### Requirement: DELETE /api/v1/posts/{post_id}/replies/{reply_id} endpoint exists

A Ktor route SHALL be registered at `DELETE /api/v1/posts/{post_id}/replies/{reply_id}`. The route MUST require Bearer JWT authentication; an unauthenticated request MUST receive HTTP 401 `unauthenticated`. Both UUID path-params MUST be validated; either being non-UUID MUST yield HTTP 400 `invalid_uuid`.

#### Scenario: Unauthenticated rejected
- **WHEN** the DELETE route is called with no `Authorization` header
- **THEN** the response is HTTP 401 with `error.code = "unauthenticated"`

#### Scenario: Non-UUID reply_id returns 400
- **WHEN** `{reply_id} = "not-a-uuid"`
- **THEN** the response is HTTP 400 with `error.code = "invalid_uuid"` AND no DB query executes

### Requirement: DELETE replies — author-only soft-delete, idempotent 204

The DELETE endpoint SHALL execute `UPDATE post_replies SET deleted_at = NOW() WHERE id = :reply_id AND author_id = :caller AND deleted_at IS NULL` and MUST return HTTP 204 with an empty body regardless of the UPDATE's affected-row count. The endpoint MUST NOT return HTTP 403 or HTTP 404. "Not yours to delete" MUST be INDISTINGUISHABLE from "already tombstoned" and from "never existed" — all three yield the same 204.

The endpoint MUST NEVER execute a `DELETE FROM post_replies` (hard delete). Replies are soft-delete only per `docs/05-Implementation.md:734`.

The endpoint MUST NOT perform a preliminary visibility resolution on the parent post — the caller deleting their own reply is independent of the post's current visibility to the caller.

#### Scenario: Author soft-deletes own reply
- **WHEN** the caller is reply R's author AND R has `deleted_at IS NULL`
- **THEN** the response is HTTP 204 AND `post_replies.deleted_at` for R is set to a non-null timestamp

#### Scenario: Non-author DELETE returns opaque 204
- **WHEN** the caller is NOT reply R's author
- **THEN** the response is HTTP 204 AND R's `deleted_at` is UNCHANGED — NOT HTTP 403

#### Scenario: Second DELETE on already-tombstoned reply is idempotent
- **WHEN** the caller (who is the author) DELETEs the same reply a second time
- **THEN** the response is HTTP 204 AND the stored `deleted_at` is UNCHANGED (the `deleted_at IS NULL` guard prevents a second overwrite)

#### Scenario: DELETE on nonexistent reply returns 204
- **WHEN** `{reply_id}` is a well-formed UUID that does not exist in `post_replies`
- **THEN** the response is HTTP 204 — NOT HTTP 404

#### Scenario: No hard delete ever
- **WHEN** searching the service implementation for `DELETE FROM post_replies`
- **THEN** zero matches are found

### Requirement: Integration test coverage

`ReplyServiceTest` (tagged `database`) SHALL cover, at minimum, these scenarios end-to-end against a Postgres test DB:
1. POST happy path: visible post, 280-char content, HTTP 201 with full reply JSON, row exists with `is_auto_hidden = FALSE, deleted_at = NULL`.
2. POST `invalid_uuid` on non-UUID path.
3. POST `invalid_request` on empty / whitespace-only / 281-char / missing / null `content`.
4. POST `post_not_found` on missing / soft-deleted / auto-hidden / caller-blocked-author / author-blocked-caller parent (five sub-cases, all returning the same opaque error envelope).
5. POST `author_id` derived from JWT, not body.
6. GET happy path: parent post exists, three replies visible, ordered `created_at DESC, id DESC`.
7. GET `post_not_found` on invisible parent (same five sub-cases as POST).
8. GET cursor pagination: 35 visible replies → page 1 returns 30 + cursor → page 2 returns 5, no overlap.
9. GET excludes soft-deleted replies (for all viewers including author).
10. GET excludes replies from shadow-banned authors (via `visible_users` JOIN).
11. GET excludes replies from blocked authors (both directions).
12. GET shows `is_auto_hidden = TRUE` reply to its author only; hidden from other viewers.
13. GET response `deleted_at` always `null`.
14. DELETE author happy path: `deleted_at` set, HTTP 204.
15. DELETE non-author: HTTP 204 AND row UNCHANGED.
16. DELETE already-tombstoned reply: HTTP 204 AND stored `deleted_at` UNCHANGED.
17. DELETE on nonexistent `reply_id`: HTTP 204.
18. DELETE never executes a hard DELETE (grep assertion on service source).
19. Auth required: HTTP 401 without JWT on all three endpoints.

#### Scenario: Test class exists
- **WHEN** running `./gradlew :backend:ktor:test --tests '*ReplyServiceTest*'`
- **THEN** the class is discovered AND every numbered scenario above corresponds to at least one `@Test` method

### Requirement: post_replies.is_auto_hidden actively written by the V9 reports auto-hide path

V8 introduced `post_replies.is_auto_hidden BOOLEAN NOT NULL DEFAULT FALSE` and V8's GET `/api/v1/posts/{post_id}/replies` endpoint already filters `is_auto_hidden = FALSE OR author_id = :viewer`. Until V9 no code path flipped the column. V9 introduces the FIRST writer: the reports auto-hide path (see `reports` capability) SHALL execute `UPDATE post_replies SET is_auto_hidden = TRUE WHERE id = :target_id AND deleted_at IS NULL` when 3 distinct reporters aged > 7 days have reported the reply (via `target_type = 'reply'`).

The V8 reply-read-path contract — `is_auto_hidden = FALSE OR author_id = :viewer` — MUST continue to hold. Flipping the column automatically hides the reply from every non-author viewer while keeping it visible to the author (the "author sees their own auto-hidden reply" behavior documented in `docs/05-Implementation.md:738` and at the V8 read-path spec is unchanged, but now observable in practice).

The UPDATE MUST be idempotent (same contract as the `posts` variant in `post-creation`): re-running on an already-hidden reply is a no-op; soft-deleted replies (`deleted_at IS NOT NULL`) are skipped by the WHERE clause but still produce a `moderation_queue` row.

#### Scenario: Auto-hide flip hides reply from non-authors
- **WHEN** a reply is visible in GET `/api/v1/posts/{post_id}/replies` to a non-author viewer AND the reports auto-hide path flips `post_replies.is_auto_hidden = TRUE`
- **THEN** the next call to GET `/replies` (same viewer, same cursor) does NOT return that reply

#### Scenario: Author still sees their own auto-hidden reply
- **WHEN** a reply is auto-hidden AND the author themselves calls GET `/api/v1/posts/{post_id}/replies` (with `author_id = :viewer`)
- **THEN** the reply IS returned in the list (the V8 `is_auto_hidden = FALSE OR author_id = :viewer` filter preserves author visibility)

#### Scenario: Reply-counter on timelines honors auto-hide (visible_users shadow-ban exclusion only)
- **WHEN** a reply is auto-hidden AND that reply's parent post is listed on the nearby or following timeline
- **THEN** the `reply_count` field on the parent post includes the auto-hidden reply (the V8 counter excludes only shadow-banned authors via `JOIN visible_users`, not auto-hidden replies); V9 does NOT change this — the counter privacy tradeoff documented in V8 remains in effect

#### Scenario: UPDATE on already-hidden reply is a no-op
- **WHEN** the reports auto-hide path runs its UPDATE on a reply whose `is_auto_hidden` is already TRUE
- **THEN** the UPDATE affects 0 additional state

#### Scenario: UPDATE skips soft-deleted reply
- **WHEN** the reports auto-hide path runs on a reply with `deleted_at IS NOT NULL`
- **THEN** the UPDATE affects 0 rows (the WHERE clause excludes soft-deleted targets; the `moderation_queue` row is still inserted)

### Requirement: POST / GET / DELETE reply endpoints unchanged by V9

V9 MUST NOT alter the shape, auth, validation, or response of `POST`/`GET`/`DELETE` on `/api/v1/posts/{post_id}/replies[/{reply_id}]`. V9 adds only a DB-level writer of `post_replies.is_auto_hidden`. The column default stays FALSE on reply creation; the V8 read-path filter stays structurally identical.

#### Scenario: New reply starts with is_auto_hidden = FALSE
- **WHEN** a successful POST `/api/v1/posts/{post_id}/replies` completes (post-V9)
- **THEN** the inserted row's `is_auto_hidden` column equals `FALSE`

#### Scenario: V8 auto-hide-with-author-bypass filter unchanged
- **WHEN** inspecting the GET `/replies` SQL (post-V9)
- **THEN** the WHERE clause still contains `(is_auto_hidden = FALSE OR author_id = :viewer)` (V9 does not alter this filter)

### Requirement: Successful reply emits a post_replied notification for the parent post author

V10 introduces the FIRST notification-write side-effect for replies. On a successful `POST /api/v1/posts/{post_id}/replies` that inserts a new row into `post_replies`, the `ReplyService` SHALL invoke `NotificationEmitter.emit(type = 'post_replied', recipient = parentPost.author_id, actor = caller, target_type = 'post', target_id = parentPost.id, body_data = {reply_id: <inserted reply uuid>, reply_excerpt: <first 80 code points of reply.content>})` in the SAME DB transaction as the `post_replies` INSERT.

The emit is subject to the `NotificationEmitter` suppression rules (see `in-app-notifications` capability):
- Self-action: if `parentPost.author_id == caller` (replying to own post), no notification row is written.
- Block: if the parent post author and the replier have a block row in either direction, no notification row is written.

Note: `target_type = 'post'` (the parent post) rather than `'reply'`. The notification points the recipient to the post whose comment thread got a new reply; the `reply_id` inside `body_data` lets the client deep-link to the specific reply if desired. This is the documented event-catalog shape at `docs/05-Implementation.md:856`.

Reply deletion (`DELETE /api/v1/posts/{post_id}/replies/{reply_id}`) MUST NOT emit a counter-notification (no `reply_deleted` type in the enum). The previously-written `post_replied` notification MAY remain in the parent author's feed as a historical record; `target_id` continues to point to the parent post (still exists), though `body_data.reply_id` points to a now-soft-deleted reply. Client handling of stale `reply_id` deep-links is the mobile app's responsibility (redirect to parent post).

If the notification INSERT fails (e.g. parent post author hard-deleted between request and emit), the encompassing transaction rolls back; the `post_replies` INSERT does NOT persist.

The V8 reply endpoint response shapes (POST / GET / DELETE on `/replies`) MUST NOT change. The `reply_count` field on timelines is also unchanged.

#### Scenario: Bob replies to Alice's post produces post_replied notification for Alice
- **WHEN** Bob POSTs `/api/v1/posts/{alicePostId}/replies` with content "ayo ketemu" AND no block exists between Alice and Bob
- **THEN** HTTP 2xx (per V8 contract) AND exactly one `notifications` row exists with `user_id = Alice.id, type = 'post_replied', actor_user_id = Bob.id, target_type = 'post', target_id = alicePostId, body_data.reply_id = <new reply uuid>, body_data.reply_excerpt = "ayo ketemu"`

#### Scenario: Self-reply produces no notification
- **WHEN** Alice POSTs a reply on her own post
- **THEN** the reply is inserted AND zero `notifications` rows are inserted

#### Scenario: Reply from blocked user produces no notification (Alice blocked Bob)
- **WHEN** Alice has a `user_blocks` row `(blocker_id = Alice, blocked_id = Bob)` AND Bob POSTs a reply on Alice's post (the post is still resolvable via direct link / cache)
- **THEN** the reply-insertion path outcome follows the existing `post-replies` contract (block-aware or not per V8) AND IF a reply is inserted, zero `notifications` rows are inserted for Alice

#### Scenario: Reply from blocked user produces no notification (Bob blocked Alice)
- **WHEN** Bob has a `user_blocks` row `(blocker_id = Bob, blocked_id = Alice)` AND Bob successfully inserts a reply on Alice's post
- **THEN** zero `notifications` rows are inserted for Alice (bidirectional suppression)

#### Scenario: target_type is 'post' (parent) not 'reply'
- **WHEN** Bob replies to Alice's post
- **THEN** the emitted notification's `target_type = 'post'` AND `target_id = alicePostId` AND `body_data.reply_id = <the new reply uuid>`

#### Scenario: Reply soft-delete does NOT remove existing notification
- **WHEN** A `post_replied` notification exists for Alice (from Bob's reply) AND Bob soft-deletes the reply
- **THEN** the `notifications` row for Alice persists unchanged; no new counter-notification is emitted

#### Scenario: Notification INSERT failure rolls back the reply
- **WHEN** Bob attempts to reply to Alice's post AND Alice is hard-deleted between the reply validation and the notification INSERT
- **THEN** the transaction rolls back AND zero `post_replies` rows persist

#### Scenario: V8 reply endpoint response shapes unchanged
- **WHEN** inspecting the response bodies of `POST /replies`, `GET /replies`, `DELETE /replies/{id}` pre-V10 and post-V10
- **THEN** each matches the V8 contract byte-for-byte (V10 does not alter the reply endpoint response shapes)

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

