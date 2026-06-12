# nearby-timeline (delta)

## MODIFIED Requirements

### Requirement: Canonical query joins visible_posts and excludes blocks bidirectionally

The endpoint's data query SHALL be the canonical Nearby query from `docs/05-Implementation.md` § Timeline Implementation. As of `shadow-ban-feed-self-visibility`, the canonical shape is a viewer-aware `UNION ALL` of two arms inside a derived table, with the V7/V8 projection joins applied OUTSIDE the union:

- **Visible arm** (everyone else's posts): `FROM visible_posts` (NOT `FROM posts`) restricted to `p.author_id <> :viewer`, with `ST_DWithin(display_location, ST_MakePoint(:lng, :lat)::geography, :radius_m)`, AND two NOT-IN subqueries excluding `user_blocks` rows in BOTH directions:
  - `author_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = :viewer)` (viewer-blocked authors hidden)
  - `author_id NOT IN (SELECT blocker_id FROM user_blocks WHERE blocked_id = :viewer)` (authors-who-blocked-viewer hidden)
- **Self arm** (the viewer's own posts — the docs/05 own-content exception applied at the feed layer): `FROM posts` with `p.author_id = :viewer AND p.deleted_at IS NULL` and the SAME `ST_DWithin` spatial filter on `display_location`. The self arm MUST NOT filter `is_auto_hidden` (the author sees their own auto-hidden posts — the same author bypass the reply-list query has carried since V8) and MUST NOT filter the author's `users.is_shadow_banned` (self-visibility for shadow-banned authors is the purpose of the arm). The self arm MUST keep `deleted_at IS NULL` — the author does NOT regain visibility of their own soft-deleted posts. The self arm carries NO `user_blocks` subqueries: it only ever returns rows authored by the viewer, and self-blocks are impossible (`user_blocks` CHECK).
- The two arms MUST be disjoint (`author_id <> :viewer` on the visible arm) so `UNION ALL` cannot duplicate a row. Each arm MUST carry its own keyset predicate `(p.created_at, p.id) < (:c, :i)` (omitted on the first page), its own `ORDER BY p.created_at DESC, p.id DESC`, and its own `LIMIT 31`, so each arm remains independently index-serviceable (`posts_nearby_cursor_idx` / `posts_timeline_cursor_idx` for the visible arm; `posts_author_idx` for the self arm) with top-N early exit. The outer query re-sorts the merged arms by `(created_at DESC, id DESC)` and applies the final `LIMIT 31`.

The V7 `LEFT JOIN post_likes pl ON pl.post_id = p.id AND pl.user_id = :viewer` projecting `(pl.user_id IS NOT NULL) AS liked_by_viewer` SHALL be applied to the union's result. The LEFT JOIN is PK-scoped (`post_likes_pk = (post_id, user_id)`), so at most one `post_likes` row matches per row — the join MUST NOT fan out rows, MUST NOT alter `COUNT(*)` over the result, MUST NOT appear in the outer `ORDER BY`, and MUST NOT appear in either arm's keyset predicate.

The V8 `LEFT JOIN LATERAL (SELECT COUNT(*) AS n FROM post_replies pr JOIN visible_users vu ON vu.id = pr.author_id WHERE pr.post_id = p.id AND pr.deleted_at IS NULL) c ON TRUE` projecting `c.n AS reply_count` SHALL be applied to the union's result. The LATERAL sub-scalar evaluates to exactly one row per outer row — the join MUST NOT fan out rows, MUST NOT alter `COUNT(*)` over the result, MUST NOT appear in the outer `ORDER BY`, and MUST NOT appear in either arm's keyset predicate. The counter MUST JOIN `visible_users` on the reply's `author_id` so shadow-banned repliers do NOT inflate the counter — INCLUDING on the viewer's own self-arm rows (the counter stays viewer-independent; per-viewer counts would leak block/ban state, the same privacy tradeoff documented for `likes/count` in V7). The counter MUST filter `pr.deleted_at IS NULL`. The counter MUST NOT apply viewer-block exclusion on `pr.author_id`.

Author display identity (`author_username` / `author_display_name`, added by `mobile-timeline-card-redesign`) SHALL be projected per arm: the visible arm joins `visible_users u ON u.id = p.author_id` (the shadow-ban-safe view — NEVER raw `users` on that arm); the self arm joins raw `users u ON u.id = p.author_id`, which is the only place raw `users` may appear (the row set is already pinned to `author_id = :viewer`, so the join can only surface the authenticated caller's own identity). Both joins are PK-equality INNER JOINs that MUST NOT change either arm's result row set, MUST NOT appear in the `ORDER BY`, and MUST NOT appear in the keyset predicates.

The visible arm's two `user_blocks` NOT-IN subqueries MUST remain present simultaneously so `BlockExclusionJoinRule` continues to pass on the updated query literal. The literal's raw `FROM posts` / `JOIN users` (self arm only) MUST be allowlisted via `@AllowRawPostsRead("<reason>")` on the SQL-holding declaration — the `RawFromPostsRule` rule itself MUST NOT be modified or weakened.

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
- **WHEN** caller A has `is_shadow_banned = TRUE` AND 35 posts are eligible for A's Nearby feed (a mix of A's own posts and other visible authors' posts interleaved by `created_at` across the page-30 boundary)
- **THEN** page 1 returns 30 rows + `next_cursor`, page 2 returns the remaining 5, no row appears twice, and no eligible row is skipped

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
- **THEN** the visible arm's author-identity join references `visible_users` AND the only raw `users` reference is the self arm's identity join (scoped to `author_id = :viewer`), covered by an `@AllowRawPostsRead` annotation on the SQL-holding declaration
