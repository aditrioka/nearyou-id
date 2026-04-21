## MODIFIED Requirements

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
