# nearby-timeline (delta)

## MODIFIED Requirements

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
      "distanceM": <double>,
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

The `latitude`/`longitude` fields MUST be derived from `display_location` (NOT `actual_location`) — including on the viewer's own self-arm rows (the author sees their own post at its fuzzed location). The `distanceM` field MUST be the value computed by `ST_Distance(display_location, ST_MakePoint(:lng, :lat)::geography)` in the SQL query — server-computed, returned in raw meters.

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
- **THEN** its `latitude`/`longitude`/`distanceM` derive from `display_location` exactly like every other row (no `actual_location` leak on the own-content path)

#### Scenario: distanceM is raw meters
- **WHEN** the response contains a post for which `ST_Distance(display_location, viewer_loc)` is approximately 1234.5 meters
- **THEN** the response field `distanceM` is approximately 1234.5 (NOT a formatted "1km" string)

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

## ADDED Requirements

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
