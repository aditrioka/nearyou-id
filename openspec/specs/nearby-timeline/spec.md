# nearby-timeline Specification

## Purpose

Defines the HTTP contract for `GET /api/v1/timeline/nearby` — the first read endpoint that surfaces visible, geographically-relevant posts to authenticated viewers. Specifies authentication, required query parameters, coordinate envelope and radius validation, the canonical SQL query (joining `visible_posts` with bidirectional `user_blocks` exclusion), keyset cursor format, server-side distance computation, per-page cap, response shape, and integration test coverage.

See `docs/05-Implementation.md § Timeline Implementation` for the canonical Nearby query and `docs/08-Roadmap-Risk.md` Phase 1 item 30 for cap rationale.
## Requirements
### Requirement: GET /api/v1/timeline/nearby endpoint exists

A Ktor route SHALL be registered at `GET /api/v1/timeline/nearby`. The route MUST require Bearer JWT authentication via the existing `auth-jwt` plugin; an unauthenticated request MUST receive HTTP 401 with error code `unauthenticated`. The route handler MUST live under `backend/ktor/src/main/kotlin/id/nearyou/app/timeline/`.

#### Scenario: Unauthenticated rejected
- **WHEN** `GET /api/v1/timeline/nearby?lat=-6.2&lng=106.8&radius_m=1000` is called with no `Authorization` header
- **THEN** the response is HTTP 401 with `error.code = "unauthenticated"`

#### Scenario: Authenticated routed to handler
- **WHEN** the same request is made with a valid Bearer JWT
- **THEN** the request reaches the handler (HTTP status is not 401)

### Requirement: Required query parameters

The endpoint SHALL require `lat`, `lng`, and `radius_m` as query parameters. Missing or non-numeric values MUST yield HTTP 400 with error code `invalid_request`. The `cursor` parameter is optional; if absent, the endpoint returns the first page.

#### Scenario: Missing lat rejected
- **WHEN** the request omits `lat`
- **THEN** the response is HTTP 400 with `error.code = "invalid_request"`

#### Scenario: Non-numeric radius rejected
- **WHEN** `radius_m=abc`
- **THEN** the response is HTTP 400 with `error.code = "invalid_request"`

### Requirement: Coordinate envelope check (reuses post-creation guard)

The endpoint SHALL reject requests where `lat` is outside `[-11.0, 6.5]` or `lng` is outside `[94.0, 142.0]` with HTTP 400 code `location_out_of_bounds`. This MUST use the same envelope and error code as `post-creation`'s envelope check.

#### Scenario: Out-of-envelope rejected
- **WHEN** the request has `lat=10.0, lng=120.0`
- **THEN** the response is HTTP 400 with `error.code = "location_out_of_bounds"` AND no DB query executes

### Requirement: Radius bounds

`radius_m` SHALL be validated to the inclusive integer range `[100, 50000]`. Out-of-range values MUST yield HTTP 400 with error code `radius_out_of_bounds`.

#### Scenario: Radius too small
- **WHEN** `radius_m=50`
- **THEN** the response is HTTP 400 with `error.code = "radius_out_of_bounds"`

#### Scenario: Radius too large
- **WHEN** `radius_m=100000`
- **THEN** the response is HTTP 400 with `error.code = "radius_out_of_bounds"`

#### Scenario: Boundary radius accepted
- **WHEN** `radius_m=100` or `radius_m=50000`
- **THEN** the request is not rejected for radius bounds

### Requirement: Canonical query joins visible_posts and excludes blocks bidirectionally

The endpoint's data query SHALL be the canonical Nearby query from `docs/05-Implementation.md` § Timeline Implementation: `FROM visible_posts` (NOT `FROM posts`), with `ST_DWithin(display_location, ST_MakePoint(:lng, :lat)::geography, :radius_m)`, AND two NOT-IN subqueries excluding `user_blocks` rows in BOTH directions:
- `author_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = :viewer)` (viewer-blocked authors hidden)
- `author_id NOT IN (SELECT blocker_id FROM user_blocks WHERE blocked_id = :viewer)` (authors-who-blocked-viewer hidden)

As of V7, the query SHALL additionally `LEFT JOIN post_likes pl ON pl.post_id = p.id AND pl.user_id = :viewer` and project `(pl.user_id IS NOT NULL) AS liked_by_viewer` into the result set. The LEFT JOIN is PK-scoped (`post_likes_pk = (post_id, user_id)`), so at most one `post_likes` row matches per primary-query row — the join MUST NOT fan out rows, MUST NOT alter `COUNT(*)` over the result, MUST NOT appear in the `ORDER BY`, and MUST NOT appear in the keyset predicate `(p.created_at, p.id) < (:c, :i)`.

As of V8, the query SHALL additionally include a `LEFT JOIN LATERAL (SELECT COUNT(*) AS n FROM post_replies pr JOIN visible_users vu ON vu.id = pr.author_id WHERE pr.post_id = p.id AND pr.deleted_at IS NULL) c ON TRUE` and project `c.n AS reply_count` into the result set. The LATERAL sub-scalar evaluates to exactly one row per primary-query row — the join MUST NOT fan out rows, MUST NOT alter `COUNT(*)` over the result, MUST NOT appear in the `ORDER BY`, and MUST NOT appear in the keyset predicate. The counter MUST JOIN `visible_users` on the reply's `author_id` so shadow-banned repliers do NOT inflate the counter (shadow-ban parity with `likes/count`). The counter MUST filter `pr.deleted_at IS NULL` so tombstoned replies do NOT inflate the counter. The counter MUST NOT apply viewer-block exclusion on `pr.author_id` — a per-viewer count would leak block state, the same privacy tradeoff documented for `likes/count` in V7.

Both `user_blocks` NOT-IN subqueries (on the primary `FROM visible_posts` clause) MUST remain present simultaneously so `BlockExclusionJoinRule` continues to pass on the updated query literal. The V7 `LEFT JOIN post_likes` MUST remain unchanged.

#### Scenario: Auto-hidden post excluded
- **WHEN** a post within radius has `is_auto_hidden = TRUE`
- **THEN** that post does NOT appear in the response

#### Scenario: Viewer-blocked author excluded
- **WHEN** the calling user has a `user_blocks` row `(blocker_id = caller, blocked_id = X)` AND X has a post within radius
- **THEN** X's post does NOT appear in the response

#### Scenario: Viewer-blocked-by-author excluded
- **WHEN** there is a `user_blocks` row `(blocker_id = X, blocked_id = caller)` AND X has a post within radius
- **THEN** X's post does NOT appear in the response

#### Scenario: LEFT JOIN post_likes does not alter row count
- **WHEN** 35 visible posts exist for a viewer who has liked 7 of them
- **THEN** the query returns exactly 35 rows (not 42; the LEFT JOIN is at-most-one via PK on `(post_id, user_id)`)

#### Scenario: LEFT JOIN LATERAL reply counter does not alter row count
- **WHEN** 35 visible posts exist for a viewer AND those posts collectively have 200 non-tombstoned replies
- **THEN** the query returns exactly 35 rows (not 200 + 35; the LATERAL sub-scalar is one row per outer row)

#### Scenario: Reply counter excludes shadow-banned repliers
- **WHEN** post P has 3 replies, 1 of which is by a `is_shadow_banned = TRUE` user, and 2 are by visible users
- **THEN** the response item for P has `reply_count = 2`

#### Scenario: Reply counter excludes soft-deleted replies
- **WHEN** post P has 5 replies, 2 of which have `deleted_at IS NOT NULL`
- **THEN** the response item for P has `reply_count = 3`

#### Scenario: Reply counter does NOT apply viewer-block exclusion
- **WHEN** post P has 3 visible replies, 1 of which is by a user X blocked by the viewer (via `user_blocks`)
- **THEN** the response item for P has `reply_count = 3` (the blocked replier's row IS counted; the viewer simply does not see X's reply in the reply-list endpoint — the counter does not leak block state)

### Requirement: Keyset pagination on (created_at DESC, id DESC)

The endpoint SHALL paginate via keyset on `(created_at DESC, id DESC)` using the `posts_timeline_cursor_idx` index. The cursor parameter is a base64url-encoded JSON object `{"c":"<created_at ISO-8601>","i":"<post UUID>"}`. The endpoint MUST NOT use SQL `OFFSET`. A malformed cursor MUST yield HTTP 400 with error code `invalid_cursor`.

#### Scenario: First page no cursor
- **WHEN** the request has no `cursor`
- **THEN** the SQL query has no `(created_at, id) <` clause AND returns the most recent posts

#### Scenario: Subsequent page with cursor
- **WHEN** the response on page 1 contains `next_cursor = "<token>"` AND the next request supplies `cursor=<token>`
- **THEN** the SQL query includes `(created_at, id) < (cursor.c, cursor.i)` AND no row from page 1 appears in page 2

#### Scenario: Malformed cursor rejected
- **WHEN** `cursor=not-a-base64-json`
- **THEN** the response is HTTP 400 with `error.code = "invalid_cursor"`

### Requirement: Per-page cap of 30

The endpoint SHALL `LIMIT` the SQL query to `31` (page-size 30 plus one probe row to detect a next page). The response `posts` array MUST contain at most 30 elements. The probe row, if present, MUST NOT appear in the response and MUST seed `next_cursor`.

#### Scenario: At most 30 posts in response
- **WHEN** there are 100 posts within radius for a given viewer
- **THEN** `response.posts.length <= 30`

#### Scenario: next_cursor present when more exist
- **WHEN** there are >30 matching posts AND the response contains 30 posts
- **THEN** `response.next_cursor` is a non-null base64url string

#### Scenario: next_cursor null on last page
- **WHEN** the response contains <30 posts (or exactly 30 with no further matches)
- **THEN** `response.next_cursor` is `null`

### Requirement: Response shape

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
      "distance_m": <double>,
      "created_at": "<ISO-8601 UTC>",
      "liked_by_viewer": <boolean>,
      "reply_count": <integer>
    }
  ],
  "next_cursor": "<string or null>"
}
```

The `latitude`/`longitude` fields MUST be derived from `display_location` (NOT `actual_location`). The `distance_m` field MUST be the value computed by `ST_Distance(display_location, ST_MakePoint(:lng, :lat)::geography)` in the SQL query — server-computed, returned in raw meters.

The `liked_by_viewer` field MUST be a JSON Boolean and MUST be present on EVERY post in the response (never omitted, never null). It MUST be `true` if and only if a `post_likes` row exists with `(post_id = <that post's id>, user_id = <caller>)`; otherwise `false`. The value is derived from the `LEFT JOIN post_likes` in the canonical query.

The `reply_count` field (added in V8) MUST be a JSON integer ≥ 0 and MUST be present on EVERY post in the response (never omitted, never null). It MUST equal the count of `post_replies` rows for the post where the reply's author is shadow-ban-visible (`JOIN visible_users`) AND the reply is not soft-deleted (`deleted_at IS NULL`). Viewer-block exclusion is DELIBERATELY NOT applied to this counter (privacy tradeoff; per-viewer count would leak block state). The value is derived from the `LEFT JOIN LATERAL` sub-scalar in the canonical query.

#### Scenario: Coordinates from display_location
- **WHEN** a post in the response has database `display_location = POINT(106.8 -6.2)`
- **THEN** the response item has `latitude = -6.2` AND `longitude = 106.8`

#### Scenario: actual_location not exposed
- **WHEN** searching the response JSON for `actual_location` or any value matching the post's actual coordinates
- **THEN** no match is found

#### Scenario: distance_m is raw meters
- **WHEN** the response contains a post for which `ST_Distance(display_location, viewer_loc)` is approximately 1234.5 meters
- **THEN** the response field `distance_m` is approximately 1234.5 (NOT a formatted "1km" string)

#### Scenario: liked_by_viewer true when caller has liked the post
- **WHEN** a post P is in the response AND a `post_likes` row `(P, caller)` exists
- **THEN** the response item for P has `liked_by_viewer = true`

#### Scenario: liked_by_viewer false when caller has not liked the post
- **WHEN** a post P is in the response AND no `post_likes` row `(P, caller)` exists
- **THEN** the response item for P has `liked_by_viewer = false`

#### Scenario: liked_by_viewer present on every post
- **WHEN** the response contains any number of posts (including zero, one, or many)
- **THEN** every post object in `response.posts` contains the key `liked_by_viewer` with a JSON Boolean value (never omitted, never `null`)

#### Scenario: reply_count is a non-negative JSON integer
- **WHEN** any post P is in the response
- **THEN** `P.reply_count` is a JSON number with no fractional component AND `P.reply_count >= 0`

#### Scenario: reply_count present on every post
- **WHEN** the response contains any number of posts (including zero, one, or many)
- **THEN** every post object in `response.posts` contains the key `reply_count` with a JSON integer value (never omitted, never `null`)

#### Scenario: reply_count = 0 for post with no replies
- **WHEN** a post P has zero `post_replies` rows
- **THEN** the response item for P has `reply_count = 0` (NOT omitted, NOT `null`)

### Requirement: Integration test coverage

`NearbyTimelineServiceTest` (tagged `database`) SHALL cover, at minimum, these scenarios end-to-end against a Postgres+PostGIS test DB:
1. Happy path: viewer at Jakarta, three posts within radius, ordered by `created_at DESC`.
2. Cursor pagination: 35 posts, page 1 returns 30 + cursor, page 2 returns 5, no overlap.
3. Radius filter: a post outside `radius_m` is excluded.
4. Auto-hidden exclusion: a post with `is_auto_hidden = TRUE` is excluded.
5. Bidirectional block exclusion (two sub-cases):
   - A blocked B (viewer = A): B's posts hidden.
   - B blocked A (viewer = A): B's posts hidden.
6. Out-of-envelope coordinates: HTTP 400 `location_out_of_bounds`.
7. Auth required: HTTP 401 without JWT.
8. `liked_by_viewer = true` when caller has a `post_likes` row for the post.
9. `liked_by_viewer = false` when caller has no `post_likes` row for the post.
10. `liked_by_viewer` key present on every post (iterate response; assert key presence).
11. LEFT JOIN cardinality invariant with likes: 35 visible posts with 7 liked → 35 returned, not 42.
12. `reply_count = 0` when a post has no replies.
13. `reply_count` = exact count of visible replies when a post has multiple replies.
14. `reply_count` excludes shadow-banned repliers (post has 3 replies, 1 by shadow-banned → `reply_count = 2`).
15. `reply_count` excludes soft-deleted replies (post has 5 replies, 2 tombstoned → `reply_count = 3`).
16. `reply_count` does NOT apply viewer-block exclusion (post has 3 visible replies, 1 by viewer-blocked user → `reply_count = 3`).
17. `reply_count` key present on every post (iterate response; assert key presence).
18. LEFT JOIN LATERAL cardinality invariant with replies: 35 visible posts with 200 collective replies → 35 returned, not 200+35.

#### Scenario: Test class exists
- **WHEN** running `./gradlew :backend:ktor:test --tests '*NearbyTimelineServiceTest*'`
- **THEN** the class is discovered AND every scenario above corresponds to at least one `@Test` method

### Requirement: Response projects city_name on every post as of V11

As of V11, the Nearby timeline response item SHALL include a `city_name` string field on every post, populated from the `posts.city_name` column that V11 adds. The field is additive — no existing field is removed or renamed. The field MUST be present on every post (never omitted); if the underlying DB value is NULL (legacy pre-trigger post or polygon-coverage gap), the field MUST serialize as the empty string `""` (never JSON `null`).

The Nearby canonical SQL (see existing `nearby-timeline` requirement "Canonical query joins visible_posts and excludes blocks bidirectionally") is extended to project `p.city_name` into the result set. No WHERE clause change, no ORDER BY change, no JOIN addition — the column is already visible through `visible_posts` as of V11.

#### Scenario: city_name key present on every Nearby post
- **WHEN** the Nearby response contains any number of posts (including zero, one, or many)
- **THEN** every post object in `response.posts` contains the key `city_name` with a JSON string value (never omitted, never `null`)

#### Scenario: city_name reflects trigger-populated value on Nearby
- **WHEN** a post was created after V11 with `actual_location` inside the "Surabaya" polygon AND the post appears in a Nearby response
- **THEN** the response item has `city_name = "Surabaya"`

#### Scenario: city_name empty string for legacy Nearby post
- **WHEN** a pre-V11 post whose `posts.city_name` column is NULL appears in a Nearby response
- **THEN** the response item has `city_name = ""`

### Requirement: Existing Nearby response fields unchanged

V11 MUST NOT remove, rename, or change the type of any existing Nearby response field (`id`, `author_user_id`, `content`, `latitude`, `longitude`, `distance_m`, `created_at`, `liked_by_viewer`, `reply_count`). The addition of `city_name` is the only response-shape change from V10 to V11 on the Nearby endpoint.

#### Scenario: distance_m still present and raw meters
- **WHEN** a post in a Nearby response has `ST_Distance(display_location, viewer_loc)` ≈ 1234.5 meters
- **THEN** `response.posts[i].distance_m ≈ 1234.5` (unchanged from V8)

#### Scenario: liked_by_viewer and reply_count still present
- **WHEN** the Nearby response contains any post
- **THEN** the post object contains `liked_by_viewer` (Boolean) AND `reply_count` (integer), both never omitted and never null (unchanged from V7/V8)

### Requirement: Integration test coverage extended for city_name

`NearbyTimelineServiceTest` SHALL add at minimum these scenarios:
1. `city_name` key present on every post in every response (assert key presence + type `string`).
2. `city_name` reflects trigger-populated value when the post's `actual_location` falls inside a seeded kabupaten/kota polygon.
3. `city_name = ""` when the underlying `posts.city_name` is NULL (legacy pre-V11 row OR polygon gap).

The existing 18 scenarios (V5–V8) remain in force unchanged.

#### Scenario: Nearby test class covers city_name
- **WHEN** running `./gradlew :backend:ktor:test --tests '*NearbyTimelineServiceTest*'`
- **THEN** at least one `@Test` covers each of the three new `city_name` scenarios AND all 18 pre-existing scenarios continue to pass

