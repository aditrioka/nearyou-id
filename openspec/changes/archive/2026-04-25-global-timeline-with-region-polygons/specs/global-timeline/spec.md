## ADDED Requirements

### Requirement: GET /api/v1/timeline/global endpoint exists

A Ktor route SHALL be registered at `GET /api/v1/timeline/global`. The route MUST require Bearer JWT authentication via the existing `auth-jwt` plugin; an unauthenticated request MUST receive HTTP 401 with error code `unauthenticated`. The route handler MUST live under `backend/ktor/src/main/kotlin/id/nearyou/app/timeline/` alongside `NearbyTimelineService` and `FollowingTimelineService`. Guest access is explicitly NOT wired in this capability and lands with a separate rate-limit / guest-auth change.

#### Scenario: Unauthenticated rejected
- **WHEN** `GET /api/v1/timeline/global` is called with no `Authorization` header
- **THEN** the response is HTTP 401 with `error.code = "unauthenticated"`

#### Scenario: Authenticated routed to handler
- **WHEN** the same request is made with a valid Bearer JWT
- **THEN** the request reaches the handler (HTTP status is not 401)

### Requirement: Query parameters

The endpoint SHALL accept only `cursor` as an optional query parameter. No `lat`/`lng`/`radius_m` parameters are accepted; the endpoint is purely chronological-over-all-visible-authors. Unknown query parameters MUST be ignored (not rejected).

#### Scenario: No params returns first page
- **WHEN** the request supplies no query params
- **THEN** the response is HTTP 200 with the first page of Global posts

#### Scenario: Malformed cursor rejected
- **WHEN** `cursor=not-a-base64-json`
- **THEN** the response is HTTP 400 with `error.code = "invalid_cursor"`

### Requirement: Canonical query runs FROM visible_posts with bidirectional block exclusion

The endpoint's data query SHALL be `FROM visible_posts` (NOT `FROM posts`), with NO `follows` filter (Global is chronological over every visible author), NO `ST_DWithin` / `ST_Distance`, and two NOT-IN subqueries excluding `user_blocks` rows in BOTH directions:
- `author_user_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = :viewer)`
- `author_user_id NOT IN (SELECT blocker_id FROM user_blocks WHERE blocked_id = :viewer)`

Both block-exclusion subqueries MUST be present simultaneously so `BlockExclusionJoinRule` passes on the new query literal.

The query SHALL carry the V7 `LEFT JOIN post_likes pl ON pl.post_id = p.id AND pl.user_id = :viewer` projecting `(pl.user_id IS NOT NULL) AS liked_by_viewer`, and the V8 `LEFT JOIN LATERAL (SELECT COUNT(*) AS n FROM post_replies pr JOIN visible_users vu ON vu.id = pr.author_id WHERE pr.post_id = p.id AND pr.deleted_at IS NULL) c ON TRUE` projecting `c.n AS reply_count`. Both joins MUST uphold the cardinality invariants already enforced on Nearby and Following (at most one `post_likes` row per outer row via PK; exactly one LATERAL scalar row per outer row; neither join appears in `ORDER BY` or the keyset predicate).

The reply counter MUST filter shadow-banned repliers via `JOIN visible_users` and tombstoned replies via `pr.deleted_at IS NULL`, and MUST NOT apply viewer-block exclusion to the counter (same privacy tradeoff as Nearby and Following).

The query SHALL project `p.city_name` directly from `visible_posts` and MUST NOT perform any `ST_Contains`, `admin_regions` JOIN, or other spatial work at read time.

#### Scenario: Post from auto-hidden author excluded
- **WHEN** a post has `is_auto_hidden = TRUE`
- **THEN** that post does NOT appear in the response (enforced by the `visible_posts` filter)

#### Scenario: Viewer-blocked author excluded
- **WHEN** the calling user has a `user_blocks` row `(blocker_id = caller, blocked_id = X)` AND X has a recent post
- **THEN** X's post does NOT appear in the response

#### Scenario: Viewer-blocked-by-author excluded
- **WHEN** there is a `user_blocks` row `(blocker_id = X, blocked_id = caller)` AND X has a recent post
- **THEN** X's post does NOT appear in the response

#### Scenario: Non-followed author NOT excluded (Global has no follows filter)
- **WHEN** a post has `author_user_id = X` AND the caller does NOT follow X AND there is no `user_blocks` row between caller and X
- **THEN** X's post DOES appear in the response (Global surfaces every visible author)

#### Scenario: No admin_regions JOIN at read time
- **WHEN** inspecting the SQL issued by `GlobalTimelineService`
- **THEN** the SQL contains neither `admin_regions` as a table reference NOR `ST_Contains` as a function call

#### Scenario: LEFT JOIN post_likes does not alter row count
- **WHEN** 35 visible posts exist for a viewer who has liked 7 of them
- **THEN** the query returns exactly 35 rows

#### Scenario: Reply counter excludes shadow-banned repliers
- **WHEN** post P has 3 replies, 1 of which is by a `is_shadow_banned = TRUE` user
- **THEN** the response item for P has `reply_count = 2`

### Requirement: Keyset pagination on (created_at DESC, id DESC)

The endpoint SHALL paginate via keyset on `(created_at DESC, id DESC)` using the existing `posts_timeline_cursor_idx` index. The cursor parameter is a base64url-encoded JSON object `{"c":"<created_at ISO-8601>","i":"<post UUID>"}`, identical in shape and codec to Nearby and Following. The endpoint MUST NOT use SQL `OFFSET`. A malformed cursor MUST yield HTTP 400 with error code `invalid_cursor`.

#### Scenario: First page no cursor
- **WHEN** the request has no `cursor`
- **THEN** the SQL has no `(p.created_at, p.id) <` clause AND returns the most recent posts

#### Scenario: Subsequent page with cursor
- **WHEN** the response on page 1 contains `next_cursor` AND the next request supplies that cursor
- **THEN** the SQL includes `(p.created_at, p.id) < (cursor.c, cursor.i)` AND no row from page 1 appears in page 2

#### Scenario: Malformed cursor rejected
- **WHEN** `cursor=not-a-base64-json`
- **THEN** the response is HTTP 400 with `error.code = "invalid_cursor"`

### Requirement: Per-page cap of 30

The endpoint SHALL `LIMIT` the SQL query to `31` (page-size 30 plus one probe row). The response `posts` array MUST contain at most 30 elements. The probe row, if present, MUST NOT appear in the response and MUST seed `next_cursor`. Redis-backed session/hour soft+hard caps are explicitly out of scope for this change.

#### Scenario: At most 30 posts in response
- **WHEN** there are 100 visible posts for a given viewer
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
      "city_name": "<string>",
      "created_at": "<ISO-8601 UTC>",
      "liked_by_viewer": <boolean>,
      "reply_count": <integer>
    }
  ],
  "next_cursor": "<string or null>"
}
```

The shape is identical to Nearby **minus** `distance_m` and **plus** `city_name`. The `latitude`/`longitude` fields MUST be derived from `display_location` (NOT `actual_location`). Global MUST NOT include a `distance_m` field at all (neither as `null` nor as a number) — it is a chronological feed with no reference point.

The `city_name` field MUST be a JSON string and MUST be present on EVERY post in the response (never omitted). It MUST equal `posts.city_name` as populated by the `posts_set_city_tg` trigger (see `region-polygons` capability). If the underlying DB value is NULL (legacy pre-trigger post or polygon-coverage gap), the field MUST serialize as the empty string `""`, never as JSON `null` and never omitted.

The `liked_by_viewer` and `reply_count` fields MUST behave exactly as in the Nearby and Following specs: `liked_by_viewer` is derived from the V7 LEFT JOIN, `reply_count` from the V8 LEFT JOIN LATERAL, both always present and never null.

#### Scenario: Coordinates from display_location
- **WHEN** a post in the response has database `display_location = POINT(106.8 -6.2)`
- **THEN** the response item has `latitude = -6.2` AND `longitude = 106.8`

#### Scenario: actual_location not exposed
- **WHEN** searching the response JSON for `actual_location` or any value matching the post's actual coordinates
- **THEN** no match is found

#### Scenario: distance_m field absent
- **WHEN** the response contains any post
- **THEN** no post object contains a `distance_m` key (neither as `null` nor as a number)

#### Scenario: city_name present and string-typed on every post
- **WHEN** the response contains any number of posts (including zero, one, or many)
- **THEN** every post object contains the key `city_name` with a JSON string value (never omitted, never `null`)

#### Scenario: city_name empty string when underlying row is NULL
- **WHEN** a post's `posts.city_name` column is NULL (legacy pre-trigger row OR polygon gap)
- **THEN** the response item for that post has `city_name = ""`

#### Scenario: city_name reflects trigger-populated value
- **WHEN** a post was created after the `posts_set_city_tg` trigger was deployed AND its `actual_location` fell inside the polygon named "Jakarta Selatan"
- **THEN** the response item has `city_name = "Jakarta Selatan"`

### Requirement: Integration test coverage

`GlobalTimelineServiceTest` (tagged `database`) SHALL cover, at minimum, these scenarios end-to-end against a Postgres+PostGIS test DB:
1. Happy path: three posts by three different authors with different city polygons, ordered by `created_at DESC`, each carrying the expected `city_name`.
2. Cursor pagination: 35 posts, page 1 returns 30 + cursor, page 2 returns 5, no overlap.
3. No follows filter: caller follows nobody; response still contains all visible posts.
4. Auto-hidden exclusion.
5. Bidirectional block exclusion (two sub-cases: viewer blocks author; author blocks viewer).
6. Auth required (HTTP 401 without JWT).
7. `liked_by_viewer = true` / `false` / present on every post (three scenarios).
8. LEFT JOIN cardinality invariant with likes.
9. `reply_count = 0` for a post with no replies.
10. `reply_count` excludes shadow-banned repliers.
11. `reply_count` excludes soft-deleted replies.
12. `reply_count` does NOT apply viewer-block exclusion.
13. `city_name` reflects trigger-populated value for a post inserted after V11.
14. `city_name = ""` for a legacy post whose `posts.city_name` column is NULL.
15. `city_name` present on every post (iterate response; assert key presence; assert type is string).
16. `distance_m` absent from every post in every response.
17. Malformed cursor → HTTP 400 `invalid_cursor`.

#### Scenario: Test class exists
- **WHEN** running `./gradlew :backend:ktor:test --tests '*GlobalTimelineServiceTest*'`
- **THEN** the class is discovered AND every scenario above corresponds to at least one `@Test` method
