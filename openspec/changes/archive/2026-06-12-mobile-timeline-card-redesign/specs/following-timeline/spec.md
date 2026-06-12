# following-timeline — Delta Specification

## MODIFIED Requirements

### Requirement: Canonical query joins visible_posts, follows, and excludes blocks bidirectionally

The endpoint's data query SHALL be the canonical Following query from `docs/05-Implementation.md` § Timeline Implementation: `FROM visible_posts` (NOT `FROM posts`), with `author_id IN (SELECT followee_id FROM follows WHERE follower_id = :viewer)`, AND two NOT-IN subqueries excluding `user_blocks` rows in BOTH directions (column names normalized to the shipped/docs-05 `author_id` as part of this change's reconciliation):
- `author_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = :viewer)`
- `author_id NOT IN (SELECT blocker_id FROM user_blocks WHERE blocked_id = :viewer)`

Both block-exclusion subqueries MUST be present simultaneously so `BlockExclusionJoinRule` passes.

As of V7, the query SHALL additionally `LEFT JOIN post_likes pl ON pl.post_id = p.id AND pl.user_id = :viewer` and project `(pl.user_id IS NOT NULL) AS liked_by_viewer` into the result set. The LEFT JOIN is PK-scoped (`post_likes_pk = (post_id, user_id)`), so at most one `post_likes` row matches per primary-query row — the join MUST NOT fan out rows, MUST NOT alter `COUNT(*)` over the result, MUST NOT appear in the `ORDER BY`, and MUST NOT appear in the keyset predicate `(p.created_at, p.id) < (:c, :i)`.

As of V8, the query SHALL additionally include a `LEFT JOIN LATERAL (SELECT COUNT(*) AS n FROM post_replies pr JOIN visible_users vu ON vu.id = pr.author_id WHERE pr.post_id = p.id AND pr.deleted_at IS NULL) c ON TRUE` and project `c.n AS reply_count` into the result set. The LATERAL sub-scalar evaluates to exactly one row per primary-query row — the join MUST NOT fan out rows, MUST NOT alter `COUNT(*)` over the result, MUST NOT appear in the `ORDER BY`, and MUST NOT appear in the keyset predicate. The counter MUST JOIN `visible_users` on the reply's `author_id` so shadow-banned repliers do NOT inflate the counter (shadow-ban parity with `likes/count` and with the Nearby-timeline counter). The counter MUST filter `pr.deleted_at IS NULL` so tombstoned replies do NOT inflate the counter. The counter MUST NOT apply viewer-block exclusion on `pr.author_id` — a per-viewer count would leak block state, the same privacy tradeoff documented for `likes/count` in V7 and for the Nearby-timeline counter in V8.

As of `mobile-timeline-card-redesign`, the query SHALL additionally `JOIN visible_users u ON u.id = p.author_id` (the shadow-ban-safe view — NEVER raw `users`) and project `u.username AS author_username, u.display_name AS author_display_name` into the result set. The join is a PK-equality INNER JOIN and MUST NOT change the result row set (`visible_posts` already excludes posts whose author is shadow-banned or deleted); it MUST NOT appear in the `ORDER BY` or the keyset predicate. The mobile Following surface is still a deferred placeholder — the identity fields ship on this endpoint NOW so all three timeline responses share one DTO shape and the future `mobile-following-timeline-screen` change consumes them without a backend follow-up.

Both `user_blocks` NOT-IN subqueries (on the primary `FROM visible_posts` clause) MUST remain present simultaneously so `BlockExclusionJoinRule` continues to pass on the updated query literal. The V7 `LEFT JOIN post_likes` MUST remain unchanged.

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

#### Scenario: LEFT JOIN LATERAL reply counter does not alter row count
- **WHEN** 20 eligible followed-author posts exist AND those posts collectively have 150 non-tombstoned replies
- **THEN** the query returns exactly 20 rows (not 150 + 20; the LATERAL sub-scalar is one row per outer row)

#### Scenario: Reply counter excludes shadow-banned repliers
- **WHEN** post P has 3 replies, 1 of which is by a `is_shadow_banned = TRUE` user, and 2 are by visible users
- **THEN** the response item for P has `reply_count = 2`

#### Scenario: Reply counter excludes soft-deleted replies
- **WHEN** post P has 5 replies, 2 of which have `deleted_at IS NOT NULL`
- **THEN** the response item for P has `reply_count = 3`

#### Scenario: Reply counter does NOT apply viewer-block exclusion
- **WHEN** post P has 3 visible replies, 1 of which is by a user X blocked by the viewer (via `user_blocks`)
- **THEN** the response item for P has `reply_count = 3` (the blocked replier's row IS counted)

#### Scenario: Author-identity JOIN does not alter row count
- **WHEN** 20 eligible followed-author posts exist for the caller
- **THEN** the query returns exactly 20 rows after adding `JOIN visible_users u ON u.id = p.author_id`

#### Scenario: Identity is sourced via visible_users, never raw users
- **WHEN** inspecting the Following SQL literal in `JdbcPostsFollowingRepository`
- **THEN** the author-identity join references `visible_users` (NOT a raw `users` table reference)

### Requirement: Response shape (posts minus distance)

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

(The example reflects the SHIPPED mixed-case wire of `TimelineRoutes.kt`; `city_name` was added by V11 per § "Response projects city_name on every post as of V11" and is included here for example accuracy.)

The response shape is IDENTICAL to `nearby-timeline` MINUS the `distanceM` field AND inclusive of both the `liked_by_viewer` field (added in V7) and the `reply_count` field (added in V8). The `latitude`/`longitude` fields MUST be derived from `display_location` (NOT `actual_location`), preserving the jitter invariant from `post-creation`.

The `liked_by_viewer` field MUST be a JSON Boolean and MUST be present on EVERY post in the response (never omitted, never null). It MUST be `true` if and only if a `post_likes` row exists with `(post_id = <that post's id>, user_id = <caller>)`; otherwise `false`. The value is derived from the `LEFT JOIN post_likes` in the canonical query.

The `reply_count` field (added in V8) MUST be a JSON integer ≥ 0 and MUST be present on EVERY post in the response (never omitted, never null). It MUST equal the count of `post_replies` rows for the post where the reply's author is shadow-ban-visible (`JOIN visible_users`) AND the reply is not soft-deleted (`deleted_at IS NULL`). Viewer-block exclusion is DELIBERATELY NOT applied to this counter (privacy tradeoff; per-viewer count would leak block state). The value is derived from the `LEFT JOIN LATERAL` sub-scalar in the canonical query.

The `authorUsername` and `authorDisplayName` fields (added by `mobile-timeline-card-redesign`) MUST be JSON strings present on EVERY post in the response (never omitted, never null — the V2 columns are NOT NULL). Their wire names are declared EXPLICITLY as bare camelCase `authorUsername` / `authorDisplayName` (no `@SerialName`), following the shipped identity-field precedent (`authorUserId`; `username`/`displayName` in `UserProfileRoutes.kt`) — NOT snake_case. They MUST equal the post author's `visible_users.username` / `visible_users.display_name` values.

#### Scenario: No distance field under either casing
- **WHEN** a post appears in the response
- **THEN** the post object does NOT contain the key `distanceM` NOR the key `distance_m`

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
- **WHEN** a post P in the response was authored by a followed user with `username = "fajar.r"`, `display_name = "Fajar Ramadhan"`
- **THEN** the response item for P has `authorUsername = "fajar.r"` AND `authorDisplayName = "Fajar Ramadhan"`
