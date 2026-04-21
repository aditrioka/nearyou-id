# following-timeline Specification

## Purpose
TBD - created by archiving change following-timeline-with-follow-cascade. Update Purpose after archive.
## Requirements
### Requirement: GET /api/v1/timeline/following endpoint exists

A Ktor route SHALL be registered at `GET /api/v1/timeline/following`. The route MUST require Bearer JWT authentication via the existing `auth-jwt` plugin; an unauthenticated request MUST receive HTTP 401 with error code `unauthenticated`. The route handler MUST live under `backend/ktor/src/main/kotlin/id/nearyou/app/timeline/` alongside `NearbyTimelineService`.

#### Scenario: Unauthenticated rejected
- **WHEN** `GET /api/v1/timeline/following` is called with no `Authorization` header
- **THEN** the response is HTTP 401 with `error.code = "unauthenticated"`

#### Scenario: Authenticated routed to handler
- **WHEN** the same request is made with a valid Bearer JWT
- **THEN** the request reaches the handler (HTTP status is not 401)

### Requirement: Query parameters

The endpoint SHALL accept only `cursor` as an optional query parameter. No `lat`/`lng`/`radius_m` parameters are accepted; the endpoint is purely chronological-over-follows. Unknown query parameters MUST be ignored (not rejected).

#### Scenario: No geo params required
- **WHEN** the request supplies no query params
- **THEN** the response is HTTP 200 with the first page of the caller's Following timeline

#### Scenario: Malformed cursor rejected
- **WHEN** `cursor=not-a-base64-json`
- **THEN** the response is HTTP 400 with `error.code = "invalid_cursor"`

### Requirement: Canonical query joins visible_posts, follows, and excludes blocks bidirectionally

The endpoint's data query SHALL be the canonical Following query from `docs/05-Implementation.md` §1057–1067: `FROM visible_posts` (NOT `FROM posts`), with `author_user_id IN (SELECT followee_id FROM follows WHERE follower_id = :viewer)`, AND two NOT-IN subqueries excluding `user_blocks` rows in BOTH directions:
- `author_user_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = :viewer)`
- `author_user_id NOT IN (SELECT blocker_id FROM user_blocks WHERE blocked_id = :viewer)`

Both block-exclusion subqueries MUST be present simultaneously so `BlockExclusionJoinRule` passes.

As of V7, the query SHALL additionally `LEFT JOIN post_likes pl ON pl.post_id = p.id AND pl.user_id = :viewer` and project `(pl.user_id IS NOT NULL) AS liked_by_viewer` into the result set. The LEFT JOIN is PK-scoped (`post_likes_pk = (post_id, user_id)`), so at most one `post_likes` row matches per primary-query row — the join MUST NOT fan out rows, MUST NOT alter `COUNT(*)` over the result, MUST NOT appear in the `ORDER BY`, and MUST NOT appear in the keyset predicate `(p.created_at, p.id) < (:c, :i)`. Both `user_blocks` NOT-IN subqueries MUST remain present so `BlockExclusionJoinRule` continues to pass on the updated query literal.

#### Scenario: Post from a non-followed author excluded
- **WHEN** a post within the last day has `author_user_id = X` AND the caller does NOT follow X
- **THEN** that post does NOT appear in the response

#### Scenario: Auto-hidden followed-author post excluded
- **WHEN** the caller follows X AND X has a post with `is_auto_hidden = TRUE`
- **THEN** that post does NOT appear in the response (enforced by the `visible_posts` view filter)

#### Scenario: Viewer-blocked-author followed post excluded
- **WHEN** the caller follows X AND the caller has a `user_blocks` row `(caller, X)`
- **THEN** X's posts do NOT appear in the response (even though the follow row still exists)

#### Scenario: Author-blocked-by-viewer followed post excluded
- **WHEN** the caller follows X AND there is a `user_blocks` row `(X, caller)`
- **THEN** X's posts do NOT appear in the response

#### Scenario: LEFT JOIN post_likes does not alter row count
- **WHEN** 20 followed-author posts are eligible for the caller AND the caller has liked 6 of them
- **THEN** the query returns exactly 20 rows (not 26; the LEFT JOIN is at-most-one via PK on `(post_id, user_id)`)

### Requirement: Keyset pagination on (created_at DESC, id DESC)

The endpoint SHALL paginate via keyset on `(created_at DESC, id DESC)` using the `posts_timeline_cursor_idx` index from `post-creation`. The cursor parameter is the SAME base64url-encoded JSON `{"c":"<created_at ISO-8601>","i":"<post UUID>"}` used by `nearby-timeline`. The endpoint MUST NOT use SQL `OFFSET`.

#### Scenario: First page no cursor
- **WHEN** the request has no `cursor`
- **THEN** the SQL query has no `(created_at, id) <` clause AND returns the most recent followed-author posts

#### Scenario: Subsequent page with cursor
- **WHEN** the response on page 1 contains `next_cursor = "<token>"` AND the next request supplies `cursor=<token>`
- **THEN** the SQL query includes `(created_at, id) < (cursor.c, cursor.i)` AND no row from page 1 appears in page 2

### Requirement: Per-page cap of 30

The endpoint SHALL `LIMIT` the SQL query to `31` (page-size 30 plus one probe row to detect a next page). The response `posts` array MUST contain at most 30 elements. The probe row, if present, MUST NOT appear in the response and MUST seed `next_cursor`.

#### Scenario: At most 30 posts in response
- **WHEN** the caller follows authors who have produced 100 eligible posts
- **THEN** `response.posts.length <= 30`

#### Scenario: next_cursor present when more exist
- **WHEN** there are >30 eligible posts AND the response contains 30 posts
- **THEN** `response.next_cursor` is a non-null base64url string

#### Scenario: next_cursor null on last page
- **WHEN** the response contains <30 posts (or exactly 30 with no further matches)
- **THEN** `response.next_cursor` is `null`

#### Scenario: Empty set when caller follows nobody
- **WHEN** the caller has zero `follows` rows AND calls the endpoint
- **THEN** the response is HTTP 200 with `posts = []` AND `next_cursor = null`

### Requirement: Response shape (posts minus distance)

A successful response SHALL be HTTP 200 with body:

```json
{
  "posts": [
    {
      "id": "<uuid>",
      "author_user_id": "<uuid>",
      "content": "<string>",
      "latitude": <double>,
      "longitude": <double>,
      "created_at": "<ISO-8601 UTC>",
      "liked_by_viewer": <boolean>
    }
  ],
  "next_cursor": "<string or null>"
}
```

The response shape is IDENTICAL to `nearby-timeline` MINUS the `distance_m` field AND inclusive of the `liked_by_viewer` field added in V7. The `latitude`/`longitude` fields MUST be derived from `display_location` (NOT `actual_location`), preserving the jitter invariant from `post-creation`.

The `liked_by_viewer` field MUST be a JSON Boolean and MUST be present on EVERY post in the response (never omitted, never null). It MUST be `true` if and only if a `post_likes` row exists with `(post_id = <that post's id>, user_id = <caller>)`; otherwise `false`. The value is derived from the `LEFT JOIN post_likes` in the canonical query.

#### Scenario: No distance_m field
- **WHEN** a post appears in the response
- **THEN** the post object does NOT contain the key `distance_m`

#### Scenario: Coordinates from display_location
- **WHEN** a post in the response has database `display_location = POINT(106.8 -6.2)`
- **THEN** the response item has `latitude = -6.2` AND `longitude = 106.8`

#### Scenario: actual_location not exposed
- **WHEN** searching the response JSON for `actual_location` or any value matching the post's actual coordinates
- **THEN** no match is found

#### Scenario: liked_by_viewer true when caller has liked the post
- **WHEN** a post P is in the response AND a `post_likes` row `(P, caller)` exists
- **THEN** the response item for P has `liked_by_viewer = true`

#### Scenario: liked_by_viewer false when caller has not liked the post
- **WHEN** a post P is in the response AND no `post_likes` row `(P, caller)` exists
- **THEN** the response item for P has `liked_by_viewer = false`

#### Scenario: liked_by_viewer present on every post
- **WHEN** the response contains any number of posts (including zero, one, or many)
- **THEN** every post object in `response.posts` contains the key `liked_by_viewer` with a JSON Boolean value (never omitted, never `null`)

### Requirement: Integration test coverage

`FollowingTimelineServiceTest` (tagged `database`) SHALL cover, at minimum, end-to-end against a Postgres test DB:
1. Happy path: caller follows three authors with posts; all three appear ordered by `created_at DESC`.
2. Empty result: caller follows nobody → `posts = []`, `next_cursor = null`.
3. Cursor pagination: 35 eligible posts, page 1 returns 30 + cursor, page 2 returns 5, no overlap.
4. Auto-hidden exclusion: a followed-author post with `is_auto_hidden = TRUE` is excluded.
5. Bidirectional block exclusion (two sub-cases):
   - Caller follows X AND caller-blocked-X: X's posts hidden.
   - Caller follows X AND X-blocked-caller: X's posts hidden.
6. Non-followed-author posts excluded: a post from an author the caller does NOT follow is absent even if otherwise visible.
7. Response has no `distance_m` field.
8. Auth required: HTTP 401 without JWT.
9. Malformed cursor rejected: HTTP 400 `invalid_cursor`.
10. `liked_by_viewer = true` when caller has a `post_likes` row for the post.
11. `liked_by_viewer = false` when caller has no `post_likes` row for the post.
12. `liked_by_viewer` key present on every post (iterate response; assert key presence).
13. LEFT JOIN cardinality invariant: 20 eligible posts with 6 liked → 20 returned, not 26.

#### Scenario: Test class exists
- **WHEN** running `./gradlew :backend:ktor:test --tests '*FollowingTimelineServiceTest*'`
- **THEN** the class is discovered AND every numbered scenario above corresponds to at least one `@Test` method

