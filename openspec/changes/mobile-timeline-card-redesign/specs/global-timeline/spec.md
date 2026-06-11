# global-timeline — Delta Specification

## MODIFIED Requirements

### Requirement: Canonical query runs FROM visible_posts with bidirectional block exclusion

The endpoint's data query SHALL be `FROM visible_posts` (NOT `FROM posts`), with NO `follows` filter (Global is chronological over every visible author), NO `ST_DWithin` / `ST_Distance`, and two NOT-IN subqueries excluding `user_blocks` rows in BOTH directions:
- `author_user_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = :viewer)`
- `author_user_id NOT IN (SELECT blocker_id FROM user_blocks WHERE blocked_id = :viewer)`

Both block-exclusion subqueries MUST be present simultaneously so `BlockExclusionJoinRule` passes on the new query literal.

The query SHALL carry the V7 `LEFT JOIN post_likes pl ON pl.post_id = p.id AND pl.user_id = :viewer` projecting `(pl.user_id IS NOT NULL) AS liked_by_viewer`, and the V8 `LEFT JOIN LATERAL (SELECT COUNT(*) AS n FROM post_replies pr JOIN visible_users vu ON vu.id = pr.author_id WHERE pr.post_id = p.id AND pr.deleted_at IS NULL) c ON TRUE` projecting `c.n AS reply_count`. Both joins MUST uphold the cardinality invariants already enforced on Nearby and Following (at most one `post_likes` row per outer row via PK; exactly one LATERAL scalar row per outer row; neither join appears in `ORDER BY` or the keyset predicate).

The reply counter MUST filter shadow-banned repliers via `JOIN visible_users` and tombstoned replies via `pr.deleted_at IS NULL`, and MUST NOT apply viewer-block exclusion to the counter (same privacy tradeoff as Nearby and Following).

As of `mobile-timeline-card-redesign`, the query SHALL additionally `JOIN visible_users u ON u.id = p.author_id` (the shadow-ban-safe view — NEVER raw `users`) and project `u.username AS author_username, u.display_name AS author_display_name` into the result set. The join is a PK-equality INNER JOIN and MUST NOT change the result row set (`visible_posts` already excludes posts whose author is shadow-banned or deleted); it MUST NOT appear in the `ORDER BY` or the keyset predicate.

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

#### Scenario: Author-identity JOIN does not alter row count
- **WHEN** 35 visible posts exist for a viewer
- **THEN** the query returns exactly 35 rows after adding `JOIN visible_users u ON u.id = p.author_id`

#### Scenario: Identity is sourced via visible_users, never raw users
- **WHEN** inspecting the Global SQL literal in `JdbcPostsGlobalRepository`
- **THEN** the author-identity join references `visible_users` (NOT a raw `users` table reference)

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
      "city_name": "<string>",
      "createdAt": "<ISO-8601 UTC>",
      "liked_by_viewer": <boolean>,
      "reply_count": <integer>
    }
  ],
  "nextCursor": "<string or null>"
}
```

The shape is identical to Nearby **minus** `distanceM` and **plus** `city_name`. The `latitude`/`longitude` fields MUST be derived from `display_location` (NOT `actual_location`). Global MUST NOT include a `distanceM` field at all (neither as `null` nor as a number) — it is a chronological feed with no reference point.

The `city_name` field MUST be a JSON string and MUST be present on EVERY post in the response (never omitted). It MUST equal `posts.city_name` as populated by the `posts_set_city_tg` trigger (see `region-polygons` capability). If the underlying DB value is NULL (legacy pre-trigger post or polygon-coverage gap), the field MUST serialize as the empty string `""`, never as JSON `null` and never omitted.

The `liked_by_viewer` and `reply_count` fields MUST behave exactly as in the Nearby and Following specs: `liked_by_viewer` is derived from the V7 LEFT JOIN, `reply_count` from the V8 LEFT JOIN LATERAL, both always present and never null.

The `authorUsername` and `authorDisplayName` fields (added by `mobile-timeline-card-redesign`) MUST be JSON strings present on EVERY post in the response (never omitted, never null — the V2 columns are NOT NULL). Their wire names are declared EXPLICITLY as bare camelCase `authorUsername` / `authorDisplayName` (no `@SerialName`), following the shipped identity-field precedent (`authorUserId`; `username`/`displayName` in `UserProfileRoutes.kt`) — NOT snake_case. They MUST equal the post author's `visible_users.username` / `visible_users.display_name` values.

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

#### Scenario: authorUsername and authorDisplayName present on every post with exact camelCase keys
- **WHEN** the response contains any number of posts
- **THEN** every post object contains the keys `authorUsername` and `authorDisplayName` with non-null JSON string values AND contains NO `author_username` / `author_display_name` snake_case variants

#### Scenario: Author identity values match the author's row
- **WHEN** a post P in the response was authored by a user with `username = "dewi.kuliner"`, `display_name = "Dewi Lestari"`
- **THEN** the response item for P has `authorUsername = "dewi.kuliner"` AND `authorDisplayName = "Dewi Lestari"`
