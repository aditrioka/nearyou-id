# global-timeline (delta)

## MODIFIED Requirements

### Requirement: Canonical query runs FROM visible_posts with bidirectional block exclusion

The endpoint's data query SHALL be the canonical Global query from `docs/05-Implementation.md` § Timeline Implementation. As of `shadow-ban-feed-self-visibility`, the canonical shape is a viewer-aware `UNION ALL` of two arms inside a derived table, with the V7/V8 projection joins applied OUTSIDE the union. There is NO `follows` filter (Global is chronological over every visible author) and NO `ST_DWithin` / `ST_Distance` in either arm.

- **Visible arm** (everyone else's posts): `FROM visible_posts` (NOT `FROM posts`) restricted to `p.author_id <> :viewer`, with two NOT-IN subqueries excluding `user_blocks` rows in BOTH directions:
  - `author_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = :viewer)`
  - `author_id NOT IN (SELECT blocker_id FROM user_blocks WHERE blocked_id = :viewer)`
- **Self arm** (the viewer's own posts — the docs/05 own-content exception applied at the feed layer): `FROM posts` with `p.author_id = :viewer AND p.deleted_at IS NULL`. The self arm MUST NOT filter `is_auto_hidden` (the author sees their own auto-hidden posts) and MUST NOT filter the author's `users.is_shadow_banned` (self-visibility for shadow-banned authors is the purpose of the arm). The self arm MUST keep `deleted_at IS NULL` — the author does NOT regain visibility of their own soft-deleted posts. The self arm carries NO `user_blocks` subqueries: it only ever returns rows authored by the viewer, and self-blocks are impossible (`user_blocks` CHECK).
- The two arms MUST be disjoint (`author_id <> :viewer` on the visible arm) so `UNION ALL` cannot duplicate a row. Each arm MUST be parenthesized and carry its own keyset predicate `(p.created_at, p.id) < (:c, :i)` (omitted on the first page), its own `ORDER BY p.created_at DESC, p.id DESC`, and its own `LIMIT 31` (the parameterized page-size + 1 probe limit), so each arm remains independently index-serviceable (`posts_timeline_cursor_idx` for the visible arm; `posts_author_idx` for the self arm) with top-N early exit. The outer query re-sorts the merged arms by `(created_at DESC, id DESC)` and applies the final `LIMIT 31`. The scalar projections (`lat`/`lng` via `ST_Y`/`ST_X`) SHALL be computed in the outer SELECT from the `display_location` column both arms project through.

The visible arm's two block-exclusion subqueries MUST be present simultaneously so `BlockExclusionJoinRule` passes on the query literal. Because that rule checks each string template in isolation (it merges only `+`-concatenation chains, not `buildString` appends), the self arm MUST live in the SAME string template as the visible arm's four lint tokens — conditional fragments (the keyset predicates) are interpolated into that single template rather than appended as separate literals. The literal's raw `FROM posts` / `JOIN users` (self arm only) MUST be allowlisted via `@AllowRawPostsRead("<reason>")` on the SQL-holding declaration — the `RawFromPostsRule` rule itself MUST NOT be modified or weakened, and no `@AllowMissingBlockJoin` is introduced.

The query SHALL carry the V7 `LEFT JOIN post_likes pl ON pl.post_id = p.id AND pl.user_id = :viewer` projecting `(pl.user_id IS NOT NULL) AS liked_by_viewer`, and the V8 `LEFT JOIN LATERAL (SELECT COUNT(*) AS n FROM post_replies pr JOIN visible_users vu ON vu.id = pr.author_id WHERE pr.post_id = p.id AND pr.deleted_at IS NULL) c ON TRUE` projecting `c.n AS reply_count`, both applied to the union's result. Both joins MUST uphold the cardinality invariants already enforced on Nearby and Following (at most one `post_likes` row per outer row via PK; exactly one LATERAL scalar row per outer row; neither join appears in the outer `ORDER BY` or either arm's keyset predicate).

The reply counter MUST filter shadow-banned repliers via `JOIN visible_users` and tombstoned replies via `pr.deleted_at IS NULL` — INCLUDING on the viewer's own self-arm rows (counts stay viewer-independent) — and MUST NOT apply viewer-block exclusion to the counter (same privacy tradeoff as Nearby and Following).

Author display identity (`author_username` / `author_display_name`, added by `mobile-timeline-card-redesign`) SHALL be projected per arm: the visible arm joins `visible_users u ON u.id = p.author_id` (the shadow-ban-safe view — NEVER raw `users` on that arm); the self arm joins raw `users u ON u.id = p.author_id`, which is the only place raw `users` may appear (the row set is pinned to `author_id = :viewer`). Both joins are PK-equality INNER JOINs that MUST NOT change either arm's result row set; they MUST NOT appear in the `ORDER BY` or the keyset predicates.

The query SHALL project `p.city_name` directly from each arm and MUST NOT perform any `ST_Contains`, `admin_regions` JOIN, or other spatial work at read time.

#### Scenario: Post from auto-hidden author excluded
- **WHEN** a post has `is_auto_hidden = TRUE` AND the caller is NOT its author
- **THEN** that post does NOT appear in the response (enforced by the `visible_posts` filter on the visible arm)

#### Scenario: Viewer-blocked author excluded
- **WHEN** the calling user has a `user_blocks` row `(blocker_id = caller, blocked_id = X)` AND X has a recent post
- **THEN** X's post does NOT appear in the response

#### Scenario: Viewer-blocked-by-author excluded
- **WHEN** there is a `user_blocks` row `(blocker_id = X, blocked_id = caller)` AND X has a recent post
- **THEN** X's post does NOT appear in the response

#### Scenario: Shadow-banned author sees their own post
- **WHEN** caller A has `is_shadow_banned = TRUE` AND A has a non-deleted post
- **THEN** A's post DOES appear in A's Global response (self arm), with `liked_by_viewer`, `reply_count`, `city_name`, `authorUsername`, and `authorDisplayName` populated like any other row

#### Scenario: Shadow-banned author's post stays hidden from other viewers
- **WHEN** author A has `is_shadow_banned = TRUE` AND caller B (`B ≠ A`, no block relation) requests Global
- **THEN** A's post does NOT appear in B's response

#### Scenario: Un-shadow-banning restores normal feed behavior
- **WHEN** A's `is_shadow_banned` is flipped back to `FALSE`
- **THEN** A's post appears in BOTH A's and B's Global responses with no further state change

#### Scenario: Author sees their own auto-hidden post
- **WHEN** caller A's own post has `is_auto_hidden = TRUE`
- **THEN** the post DOES appear in A's Global response AND does NOT appear in any other caller's response

#### Scenario: Author does NOT see their own soft-deleted post
- **WHEN** caller A's own post has `deleted_at IS NOT NULL`
- **THEN** the post does NOT appear in A's Global response (the self arm keeps `deleted_at IS NULL`)

#### Scenario: Cursor pagination correct with interleaved own-shadow-banned posts
- **WHEN** caller A has `is_shadow_banned = TRUE` AND 35 posts are eligible for A's Global feed (a mix of A's own posts and other visible authors' posts interleaved by `created_at` such that A's own posts land on BOTH sides of the page-30 boundary)
- **THEN** page 1 returns 30 rows + `next_cursor`, page 2 returns the remaining 5, no row appears twice, no eligible row is skipped, and A's own posts appear on both pages

#### Scenario: Non-followed author NOT excluded (Global has no follows filter)
- **WHEN** a post has `author_id = X` AND the caller does NOT follow X AND there is no `user_blocks` row between caller and X
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

#### Scenario: Reply counter stays viewer-independent on self rows
- **WHEN** shadow-banned caller A views their own post P in Global AND P has 2 visible-author replies plus 1 reply by A
- **THEN** the response item for P has `reply_count = 2` (the counter keeps the `visible_users` filter even for the author — counts are public and viewer-independent)

#### Scenario: Author-identity JOIN does not alter row count
- **WHEN** 35 visible posts exist for a viewer
- **THEN** the query returns exactly 35 rows after the per-arm author-identity joins

#### Scenario: Identity is sourced via visible_users on the visible arm; raw users only on the self arm
- **WHEN** inspecting the Global SQL literal in `JdbcPostsGlobalRepository`
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
      "city_name": "<string>",
      "createdAt": "<ISO-8601 UTC>",
      "liked_by_viewer": <boolean>,
      "reply_count": <integer>
    }
  ],
  "nextCursor": "<string or null>"
}
```

The shape is identical to Nearby **minus** `distanceM` and **plus** `city_name`. The `latitude`/`longitude` fields MUST be derived from `display_location` (NOT `actual_location`) — including on the viewer's own self-arm rows. Global MUST NOT include a `distanceM` field at all (neither as `null` nor as a number) — it is a chronological feed with no reference point. (The shape is UNCHANGED by `shadow-ban-feed-self-visibility` — self-arm rows serialize identically to visible-arm rows.)

The `city_name` field MUST be a JSON string and MUST be present on EVERY post in the response (never omitted). It MUST equal `posts.city_name` as populated by the `posts_set_city_tg` trigger (see `region-polygons` capability). If the underlying DB value is NULL (legacy pre-trigger post or polygon-coverage gap), the field MUST serialize as the empty string `""`, never as JSON `null` and never omitted.

The `liked_by_viewer` and `reply_count` fields MUST behave exactly as in the Nearby and Following specs: `liked_by_viewer` is derived from the V7 LEFT JOIN, `reply_count` from the V8 LEFT JOIN LATERAL, both always present and never null.

The `authorUsername` and `authorDisplayName` fields (added by `mobile-timeline-card-redesign`) MUST be JSON strings present on EVERY post in the response (never omitted, never null — the V2 columns are NOT NULL). Their wire names are declared EXPLICITLY as bare camelCase `authorUsername` / `authorDisplayName` (no `@SerialName`), following the shipped identity-field precedent (`authorUserId`; `username`/`displayName` in `UserProfileRoutes.kt`) — NOT snake_case. They MUST equal the post author's `users.username` / `users.display_name` values as projected by the canonical query's per-arm author-identity join (`visible_users` on the visible arm; raw `users` on the self arm, whose rows are always the authenticated caller's own — amended by `shadow-ban-feed-self-visibility`: self-arm rows of a shadow-banned author have no `visible_users` row, so the previous "MUST equal the `visible_users` values" wording is per-arm now).

#### Scenario: Coordinates from display_location
- **WHEN** a post in the response has database `display_location = POINT(106.8 -6.2)`
- **THEN** the response item has `latitude = -6.2` AND `longitude = 106.8`

#### Scenario: actual_location not exposed
- **WHEN** searching the response JSON for `actual_location` or any value matching the post's actual coordinates
- **THEN** no match is found

#### Scenario: Self-arm rows expose only the fuzzed location to their own author
- **WHEN** shadow-banned caller A's own post appears in A's Global response
- **THEN** its `latitude`/`longitude` derive from `display_location` exactly like every other row (no `actual_location` leak on the own-content path)

#### Scenario: Distance field absent under either casing
- **WHEN** the response contains any post
- **THEN** no post object contains a `distanceM` key NOR a `distance_m` key (neither as `null` nor as a number)

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

#### Scenario: Identity fields populated on a shadow-banned author's own rows
- **WHEN** shadow-banned caller A's own post appears in A's Global response
- **THEN** `authorUsername` / `authorDisplayName` carry A's `users` row values (non-null strings — sourced via the self arm's raw `users` join, since A has no `visible_users` row)

## ADDED Requirements

### Requirement: Integration test coverage extended for self-visibility

`GlobalTimelineServiceTest` SHALL add, at minimum, scenarios covering:
1. A shadow-banned author sees their own post; a second user does not see it; un-shadow-banning restores normal behavior for both viewers.
2. The author sees their own auto-hidden post; other viewers do not.
3. The author does NOT see their own soft-deleted post.
4. Cursor pagination with own-shadow-banned posts interleaved across the page-30 boundary (own posts on both pages; no duplicates, no gaps).
5. Self rows carry `liked_by_viewer` and `reply_count`; `reply_count` stays viewer-independent on self rows.
6. Literal inspection: the visible arm reads `FROM visible_posts`; the only raw `posts`/`users` references are the self arm's; the SQL-holding declaration carries `@AllowRawPostsRead`.

The pre-existing scenario enumerations (global-timeline + `timeline-read-rate-limit`) remain in force unchanged.

#### Scenario: Global test class covers self-visibility
- **WHEN** running `./gradlew :backend:ktor:test --tests '*GlobalTimelineServiceTest*'`
- **THEN** at least one test covers each of the six self-visibility items AND all pre-existing scenarios continue to pass
