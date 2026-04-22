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

