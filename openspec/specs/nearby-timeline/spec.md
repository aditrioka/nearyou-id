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

The endpoint's data query SHALL be the canonical Nearby query from `docs/05-Implementation.md` § Timeline Implementation. As of `shadow-ban-feed-self-visibility`, the canonical shape is a viewer-aware `UNION ALL` of two arms inside a derived table, with the V7/V8 projection joins applied OUTSIDE the union:

- **Visible arm** (everyone else's posts): `FROM visible_posts` (NOT `FROM posts`) restricted to `p.author_id <> :viewer`, with `ST_DWithin(display_location, ST_MakePoint(:lng, :lat)::geography, :radius_m)`, AND two NOT-IN subqueries excluding `user_blocks` rows in BOTH directions:
  - `author_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = :viewer)` (viewer-blocked authors hidden)
  - `author_id NOT IN (SELECT blocker_id FROM user_blocks WHERE blocked_id = :viewer)` (authors-who-blocked-viewer hidden)
- **Self arm** (the viewer's own posts — the docs/05 own-content exception applied at the feed layer): `FROM posts` with `p.author_id = :viewer AND p.deleted_at IS NULL` and the SAME `ST_DWithin` spatial filter on `display_location`. The self arm MUST NOT filter `is_auto_hidden` (the author sees their own auto-hidden posts — the same author bypass the reply-list query has carried since V8) and MUST NOT filter the author's `users.is_shadow_banned` (self-visibility for shadow-banned authors is the purpose of the arm). The self arm MUST keep `deleted_at IS NULL` — the author does NOT regain visibility of their own soft-deleted posts. The self arm carries NO `user_blocks` subqueries: it only ever returns rows authored by the viewer, and self-blocks are impossible (`user_blocks` CHECK).
- The two arms MUST be disjoint (`author_id <> :viewer` on the visible arm) so `UNION ALL` cannot duplicate a row. Each arm MUST be parenthesized and carry its own keyset predicate `(p.created_at, p.id) < (:c, :i)` (omitted on the first page), its own `ORDER BY p.created_at DESC, p.id DESC`, and its own `LIMIT 31` (the parameterized page-size + 1 probe limit), so each arm remains independently index-serviceable (the V4 partial indexes for the visible arm; `posts_author_idx` for the self arm) with top-N early exit. The outer query re-sorts the merged arms by `(created_at DESC, id DESC)` and applies the final `LIMIT 31`. The spatial scalar projections (`lat`/`lng` via `ST_Y`/`ST_X`, `distance_m` via `ST_Distance`) SHALL be computed in the outer SELECT from the `display_location` column both arms project through.

The V7 `LEFT JOIN post_likes pl ON pl.post_id = p.id AND pl.user_id = :viewer` projecting `(pl.user_id IS NOT NULL) AS liked_by_viewer` SHALL be applied to the union's result. The LEFT JOIN is PK-scoped (`post_likes_pk = (post_id, user_id)`), so at most one `post_likes` row matches per row — the join MUST NOT fan out rows, MUST NOT alter `COUNT(*)` over the result, MUST NOT appear in the outer `ORDER BY`, and MUST NOT appear in either arm's keyset predicate.

The V8 `LEFT JOIN LATERAL (SELECT COUNT(*) AS n FROM post_replies pr JOIN visible_users vu ON vu.id = pr.author_id WHERE pr.post_id = p.id AND pr.deleted_at IS NULL) c ON TRUE` projecting `c.n AS reply_count` SHALL be applied to the union's result. The LATERAL sub-scalar evaluates to exactly one row per outer row — the join MUST NOT fan out rows, MUST NOT alter `COUNT(*)` over the result, MUST NOT appear in the outer `ORDER BY`, and MUST NOT appear in either arm's keyset predicate. The counter MUST JOIN `visible_users` on the reply's `author_id` so shadow-banned repliers do NOT inflate the counter — INCLUDING on the viewer's own self-arm rows (the counter stays viewer-independent; per-viewer counts would leak block/ban state, the same privacy tradeoff documented for `likes/count` in V7). The counter MUST filter `pr.deleted_at IS NULL`. The counter MUST NOT apply viewer-block exclusion on `pr.author_id`.

Author display identity (`author_username` / `author_display_name`, added by `mobile-timeline-card-redesign`) SHALL be projected per arm: the visible arm joins `visible_users u ON u.id = p.author_id` (the shadow-ban-safe view — NEVER raw `users` on that arm); the self arm joins raw `users u ON u.id = p.author_id`, which is the only place raw `users` may appear (the row set is already pinned to `author_id = :viewer`, so the join can only surface the authenticated caller's own identity). Both joins are PK-equality INNER JOINs that MUST NOT change either arm's result row set, MUST NOT appear in the `ORDER BY`, and MUST NOT appear in the keyset predicates.

The visible arm's two `user_blocks` NOT-IN subqueries MUST remain present simultaneously so `BlockExclusionJoinRule` continues to pass on the updated query literal. Because that rule checks each string template in isolation (it merges only `+`-concatenation chains, not `buildString` appends), the self arm MUST live in the SAME string template as the visible arm's four lint tokens — conditional fragments (the keyset predicates) are interpolated into that single template rather than appended as separate literals. The literal's raw `FROM posts` / `JOIN users` (self arm only) MUST be allowlisted via `@AllowRawPostsRead("<reason>")` on the SQL-holding declaration — the `RawFromPostsRule` rule itself MUST NOT be modified or weakened, and no `@AllowMissingBlockJoin` is introduced.

#### Scenario: Auto-hidden post excluded
- **WHEN** a post within radius has `is_auto_hidden = TRUE` AND the caller is NOT its author
- **THEN** that post does NOT appear in the response

#### Scenario: Viewer-blocked author excluded
- **WHEN** the calling user has a `user_blocks` row `(blocker_id = caller, blocked_id = X)` AND X has a post within radius
- **THEN** X's post does NOT appear in the response

#### Scenario: Viewer-blocked-by-author excluded
- **WHEN** there is a `user_blocks` row `(blocker_id = X, blocked_id = caller)` AND X has a post within radius
- **THEN** X's post does NOT appear in the response

#### Scenario: Shadow-banned author sees their own post
- **WHEN** caller A has `is_shadow_banned = TRUE` AND A has a non-deleted post within radius
- **THEN** A's post DOES appear in A's Nearby response (self arm), with `liked_by_viewer`, `reply_count`, `city_name`, `authorUsername`, and `authorDisplayName` populated like any other row

#### Scenario: Shadow-banned author's post stays hidden from other viewers
- **WHEN** author A has `is_shadow_banned = TRUE` AND A has a post within radius AND caller B (`B ≠ A`, no block relation) requests Nearby
- **THEN** A's post does NOT appear in B's response

#### Scenario: Un-shadow-banning restores normal feed behavior
- **WHEN** A's `is_shadow_banned` is flipped back to `FALSE`
- **THEN** A's post appears in BOTH A's and B's Nearby responses with no further state change

#### Scenario: Author sees their own auto-hidden post
- **WHEN** caller A's own post within radius has `is_auto_hidden = TRUE`
- **THEN** the post DOES appear in A's Nearby response (auto-hide is transparent to the author — `post_auto_hidden` notification; reply-list author-bypass parity) AND does NOT appear in any other caller's response

#### Scenario: Author does NOT see their own soft-deleted post
- **WHEN** caller A's own post within radius has `deleted_at IS NOT NULL`
- **THEN** the post does NOT appear in A's Nearby response (the self arm keeps `deleted_at IS NULL`)

#### Scenario: Cursor pagination correct with interleaved own-shadow-banned posts
- **WHEN** caller A has `is_shadow_banned = TRUE` AND 35 posts are eligible for A's Nearby feed (a mix of A's own posts and other visible authors' posts interleaved by `created_at` such that A's own posts land on BOTH sides of the page-30 boundary)
- **THEN** page 1 returns 30 rows + `next_cursor`, page 2 returns the remaining 5, no row appears twice, no eligible row is skipped, and A's own posts appear on both pages

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

#### Scenario: Reply counter stays viewer-independent on self rows
- **WHEN** shadow-banned caller A views their own post P in Nearby AND P has 2 visible-author replies plus 1 reply by A
- **THEN** the response item for P has `reply_count = 2` (the counter keeps the `visible_users` filter even for the author — counts are public and viewer-independent)

#### Scenario: Author-identity JOIN does not alter row count
- **WHEN** 35 visible posts exist for a viewer
- **THEN** the query returns exactly 35 rows after the per-arm author-identity joins (PK-equality joins; the visible arm's author is guaranteed by `visible_posts`, the self arm's author is the authenticated caller)

#### Scenario: Author identity values come from the author's users row
- **WHEN** a visible post P was authored by a user whose `username = "raka.jkt"` and `display_name = "Raka Pratama"`
- **THEN** the query result row for P carries `author_username = "raka.jkt"` AND `author_display_name = "Raka Pratama"`

#### Scenario: Identity is sourced via visible_users on the visible arm; raw users only on the self arm
- **WHEN** inspecting the Nearby SQL literal in `JdbcPostsTimelineRepository`
- **THEN** the visible arm reads `FROM visible_posts` with its author-identity join referencing `visible_users`, AND the ONLY raw `posts` / raw `users` references in the literal are the self arm's (scoped to `author_id = :viewer`), covered by an `@AllowRawPostsRead` annotation on the SQL-holding declaration

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
      "authorUserId": "<uuid>",
      "authorUsername": "<string>",
      "authorDisplayName": "<string>",
      "content": "<string>",
      "latitude": <double>,
      "longitude": <double>,
      "distanceM": <double — present only when not hidden; omitted when the hide-distance rule applies>,
      "city_name": "<string>",
      "createdAt": "<ISO-8601 UTC>",
      "liked_by_viewer": <boolean>,
      "reply_count": <integer>
    }
  ],
  "nextCursor": "<string or null>"
}
```

(The example reflects the SHIPPED mixed-case wire of `TimelineRoutes.kt` — bare camelCase `authorUserId`/`authorUsername`/`authorDisplayName`/`distanceM`/`createdAt`/`nextCursor`; `@SerialName` snake_case `city_name`/`liked_by_viewer`/`reply_count`. `city_name` was added by V11 per § "Response projects city_name on every post as of V11" and is included here for example accuracy. The shape is UNCHANGED by `shadow-ban-feed-self-visibility` — self-arm rows serialize identically to visible-arm rows.)

The `latitude`/`longitude` fields MUST be derived from `display_location` (NOT `actual_location`) — including on the viewer's own self-arm rows (the author sees their own post at its fuzzed location). The `distanceM` field, WHEN PRESENT, MUST be the value computed by `ST_Distance(display_location, ST_MakePoint(:lng, :lat)::geography)` in the SQL query — server-computed, returned in raw meters. As of the `hide-distance` capability, `distanceM` is **conditionally present**: it MUST be OMITTED from a post's response object (via the app-wide `explicitNulls = false`; neither a number nor `null` on the wire) when the symmetric hide-distance rule applies to that (author, viewer) pair — i.e. when the post author's hide-distance preference is effective OR the requesting viewer's preference is effective (effectiveness = `hide_distance_opt_in = TRUE AND subscription_status IN ('premium_active','premium_billing_retry')`, per the `hide-distance` capability). Suppressing `distanceM` MUST NOT change which posts are returned, their order, the radius filter, or the `city_name` value — only the presence of the distance number. The canonical query is extended to project the **author's** effective-hide input from the existing per-arm author join (no new JOIN); the **viewer's** effective-hide is read from the auth principal (no per-request `users` SELECT in the timeline handler — preserving the `timeline-read-rate-limit` invariant), not from the query.

The `liked_by_viewer` field MUST be a JSON Boolean and MUST be present on EVERY post in the response (never omitted, never null). It MUST be `true` if and only if a `post_likes` row exists with `(post_id = <that post's id>, user_id = <caller>)`; otherwise `false`. The value is derived from the `LEFT JOIN post_likes` in the canonical query.

The `reply_count` field (added in V8) MUST be a JSON integer ≥ 0 and MUST be present on EVERY post in the response (never omitted, never null). It MUST equal the count of `post_replies` rows for the post where the reply's author is shadow-ban-visible (`JOIN visible_users`) AND the reply is not soft-deleted (`deleted_at IS NULL`). Viewer-block exclusion is DELIBERATELY NOT applied to this counter (privacy tradeoff; per-viewer count would leak block state). The value is derived from the `LEFT JOIN LATERAL` sub-scalar in the canonical query.

The `authorUsername` and `authorDisplayName` fields (added by `mobile-timeline-card-redesign`) MUST be JSON strings present on EVERY post in the response (never omitted, never null — `users.username` and `users.display_name` are NOT NULL since V2). Their wire names are declared EXPLICITLY as bare camelCase `authorUsername` / `authorDisplayName` (no `@SerialName`), following the shipped identity-field precedent (`authorUserId` in the timeline DTOs; `username`/`displayName` in `UserProfileRoutes.kt`) — NOT snake_case. They MUST equal the post author's `users.username` / `users.display_name` values as projected by the canonical query's per-arm author-identity join (`visible_users` on the visible arm; raw `users` on the self arm, whose rows are always the authenticated caller's own — amended by `shadow-ban-feed-self-visibility`: self-arm rows of a shadow-banned author have no `visible_users` row, so the previous "MUST equal the `visible_users` values" wording is per-arm now).

#### Scenario: Coordinates from display_location
- **WHEN** a post in the response has database `display_location = POINT(106.8 -6.2)`
- **THEN** the response item has `latitude = -6.2` AND `longitude = 106.8`

#### Scenario: actual_location not exposed
- **WHEN** searching the response JSON for `actual_location` or any value matching the post's actual coordinates
- **THEN** no match is found

#### Scenario: Self-arm rows expose only the fuzzed location to their own author
- **WHEN** shadow-banned caller A's own post appears in A's Nearby response
- **THEN** its `latitude`/`longitude` derive from `display_location` exactly like every other row (no `actual_location` leak on the own-content path), and `distanceM` (when present) likewise derives from `display_location`

#### Scenario: distanceM is raw meters when present
- **WHEN** the response contains a post whose distance is NOT hidden AND for which `ST_Distance(display_location, viewer_loc)` is approximately 1234.5 meters
- **THEN** the response field `distanceM` is approximately 1234.5 (NOT a formatted "1km" string)

#### Scenario: distanceM is omitted when the hide-distance rule applies
- **WHEN** a post's distance is suppressed for the requesting viewer (the author's hide-distance preference is effective, OR the viewer's preference is effective)
- **THEN** that post's response object contains NO `distanceM` key (neither a number nor `null`) AND still contains its `city_name`, `liked_by_viewer`, and `reply_count` fields unchanged

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

#### Scenario: authorUsername and authorDisplayName present on every post with exact camelCase keys
- **WHEN** the response contains any number of posts
- **THEN** every post object contains the keys `authorUsername` and `authorDisplayName` with non-null JSON string values AND contains NO `author_username` / `author_display_name` snake_case variants

#### Scenario: Author identity values match the author's row
- **WHEN** a post P in the response was authored by a user with `username = "raka.jkt"`, `display_name = "Raka Pratama"`
- **THEN** the response item for P has `authorUsername = "raka.jkt"` AND `authorDisplayName = "Raka Pratama"`

#### Scenario: Identity fields populated on a shadow-banned author's own rows
- **WHEN** shadow-banned caller A's own post appears in A's Nearby response
- **THEN** `authorUsername` / `authorDisplayName` carry A's `users` row values (non-null strings — sourced via the self arm's raw `users` join, since A has no `visible_users` row)

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

V11 MUST NOT remove, rename, or change the type of any existing Nearby response field (`id`, `author_user_id`, `content`, `latitude`, `longitude`, `distance_m`, `created_at`, `liked_by_viewer`, `reply_count`). The addition of `city_name` is the only response-shape change from V10 to V11 on the Nearby endpoint. As of the `hide-distance` capability, the `distanceM` field is no longer unconditionally present: it is RAW METERS when shown but OMITTED when the symmetric hide-distance rule applies (per the "Response shape" requirement). This is a deliberate, separately-specified change to the *presence* of `distanceM` ONLY — its type-when-present (raw-meters double), name, and the presence/type of every other field remain unchanged.

#### Scenario: distance_m present and raw meters when not hidden
- **WHEN** a post in a Nearby response has `ST_Distance(display_location, viewer_loc)` ≈ 1234.5 meters AND the hide-distance rule does NOT apply to it
- **THEN** `response.posts[i].distanceM ≈ 1234.5` (unchanged from V8; raw meters)

#### Scenario: distance_m omitted only via the hide-distance rule
- **WHEN** the hide-distance rule applies to a post for the requesting viewer
- **THEN** `distanceM` is absent on that post object (the only sanctioned reason for omission) AND every other field retains its V11 presence and type

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

### Requirement: Nearby route delegates read-rate-limit accounting to `timeline-read-rate-limit`

The `GET /api/v1/timeline/nearby` route handler SHALL delegate read-side rate-limit accounting (rolling 150-posts/hour hard cap + 50-posts/session soft cap, Free-tier only, Premium exempt) to the `timeline-read-rate-limit` capability per its full contract.

The route handler MUST:

- Run the rolling pre-check + session pre-check BEFORE the canonical Nearby SQL query (per `timeline-read-rate-limit` § "Limiter ordering and pre-execution before DB"). Pre-check key shapes are `{scope:rate_timeline_rolling}:{user:<user_id>}` and `{scope:rate_timeline_session}:{session:<user_id>__<sanitized_session_id>}`.
- On rolling-cap `RateLimited`: return HTTP 200 with `{ "posts": [], "nextCursor": null, "upsell": { "hard": true } }`. Do NOT execute the canonical Nearby SQL query (which is especially expensive for Nearby due to the PostGIS `ST_DWithin` + `ST_Distance` cost on `display_location`). The existing Nearby query, block-exclusion, and `liked_by_viewer` / `reply_count` / `city_name` projection requirements remain unchanged for the non-cap-hit path.
- On a successful response (rolling pre-check admitted, query executed, returning `N` posts where `0 ≤ N ≤ 30`): bump both buckets via `(N - 1).coerceAtLeast(0)` additional best-effort `tryAcquire` calls (1 already consumed at pre-check). Build the response per the existing Nearby response shape PLUS the optional `upsell` object per the `timeline-read-rate-limit` contract.
- Validate the `X-Session-Id` header per `timeline-read-rate-limit` § "X-Session-Id header validation"; substitute with `no-session` on missing or malformed values.
- For Premium callers (`subscription_status IN ('premium_active', 'premium_billing_retry')`): SKIP both pre-checks and post-increment entirely. Run the canonical Nearby query and respond per the existing shape; never include the `upsell` field.

The existing Nearby requirements ("Canonical query joins visible_posts and excludes blocks bidirectionally", "Keyset pagination on (created_at DESC, id DESC)", "Per-page cap of 30", "Response shape", "Response projects city_name on every post as of V11", and the V11-extended Integration test coverage requirement) remain unchanged. The rate-limit gate is a NEW pre-DB short-circuit; it does NOT alter the SQL query, the cursor format, or any of the V5–V11 invariants. The response post shape is unchanged by the rate-limit gate itself; the only per-post-field change anywhere on this endpoint is the conditional omission of `distanceM` under the `hide-distance` rule (per the "Response shape" requirement) — every other per-post field is unchanged.

#### Scenario: Free Nearby read at rolling cap returns empty + upsell.hard
- **WHEN** Free-tier caller A's rolling bucket holds 150 entries AND A issues `GET /api/v1/timeline/nearby?lat=-6.2&lng=106.8&radius_m=1000`
- **THEN** the response is HTTP 200 with body `{ "posts": [], "nextCursor": null, "upsell": { "hard": true } }` AND zero `posts` SELECTs were issued to Postgres for the request AND no PostGIS `ST_DWithin` execution

#### Scenario: Free Nearby read at session-soft-cap still returns posts
- **WHEN** Free-tier caller A's session bucket (under `X-Session-Id: SID`) is at 50/50 capacity AND the rolling bucket holds 80/150 entries AND A issues a Nearby read
- **THEN** the response is HTTP 200 with `posts.length > 0` (DB-permitting) AND `upsell.soft = true` AND the canonical Nearby SQL DID execute

#### Scenario: Premium Nearby read bypasses rate limit
- **WHEN** Premium caller A (`subscription_status = 'premium_active'`) issues a Nearby read after having read 500 posts in the last hour
- **THEN** the response is HTTP 200 with the Nearby content AND no `upsell` field AND zero rate-limit Redis calls were issued for this request (verified via Redis-counter spy)

#### Scenario: Nearby below caps — response shape unchanged
- **WHEN** Free caller A is below both caps AND issues a Nearby read returning 5 posts
- **THEN** the response body matches the existing Nearby response shape exactly (the `upsell` key is NOT present) AND all V5–V11 per-post fields are present (id, author_user_id, content, latitude, longitude, created_at, liked_by_viewer, reply_count, city_name) AND `distanceM` is present on each post UNLESS that post is subject to the `hide-distance` rule (per the "Response shape" requirement — the only sanctioned omission)

#### Scenario: Nearby empty radius result still consumes 1 rolling slot
- **WHEN** Free-tier caller A is at slot 0/150 rolling AND issues a Nearby read where the spatial filter returns zero posts (e.g., a remote ocean coordinate where no posts exist)
- **THEN** the response is HTTP 200 with `posts = []` AND `next_cursor = null` AND no `upsell` field (below caps) AND the rolling bucket holds exactly 1 entry after the response (the pre-check consumed 1 slot regardless of N=0; the post-increment is `(0-1).coerceAtLeast(0) = 0` — skipped)

#### Scenario: Nearby PostGIS query NOT executed on cap-hit
- **WHEN** Free-tier caller A's rolling bucket is at 150/150 AND A issues a Nearby read with valid `lat`/`lng`/`radius_m`
- **THEN** the response is HTTP 200 with `posts = []` + `upsell.hard = true` AND zero PostGIS function invocations are issued (no `ST_DWithin`, no `ST_Distance`)

#### Scenario: Nearby per-page cap and post-increment math
- **WHEN** Free-tier caller A is at slot 0/150 rolling AND issues a Nearby read returning the page-cap of 30 posts
- **THEN** the rolling bucket holds exactly 30 entries after the response (1 from pre-check + `(30-1) = 29` best-effort additional `tryAcquire` calls all admitted) AND the response body matches the existing Nearby response shape with 30 posts AND no `upsell` field

### Requirement: Integration test coverage extended for self-visibility

`NearbyTimelineServiceTest` SHALL add, at minimum, scenarios covering:
1. A shadow-banned author sees their own in-radius post; a second user does not see it; un-shadow-banning restores normal behavior for both viewers.
2. The author sees their own auto-hidden post; other viewers do not.
3. The author does NOT see their own soft-deleted post.
4. Cursor pagination with own-shadow-banned posts interleaved across the page-30 boundary (own posts on both pages; no duplicates, no gaps).
5. Self rows carry `liked_by_viewer` and `reply_count`; `reply_count` stays viewer-independent on self rows.
6. Literal inspection: the visible arm reads `FROM visible_posts`; the only raw `posts`/`users` references are the self arm's; the SQL-holding declaration carries `@AllowRawPostsRead`.

The pre-existing scenario enumerations (V5–V11 + `timeline-read-rate-limit`) remain in force unchanged.

#### Scenario: Nearby test class covers self-visibility
- **WHEN** running `./gradlew :backend:ktor:test --tests '*NearbyTimelineServiceTest*'`
- **THEN** at least one test covers each of the six self-visibility items AND all pre-existing scenarios continue to pass

