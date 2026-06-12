# global-timeline (delta)

## MODIFIED Requirements

### Requirement: Canonical query runs FROM visible_posts with bidirectional block exclusion

The endpoint's data query SHALL be the canonical Global query from `docs/05-Implementation.md` § Timeline Implementation. As of `shadow-ban-feed-self-visibility`, the canonical shape is a viewer-aware `UNION ALL` of two arms inside a derived table, with the V7/V8 projection joins applied OUTSIDE the union. There is NO `follows` filter (Global is chronological over every visible author) and NO `ST_DWithin` / `ST_Distance` in either arm.

- **Visible arm** (everyone else's posts): `FROM visible_posts` (NOT `FROM posts`) restricted to `p.author_id <> :viewer`, with two NOT-IN subqueries excluding `user_blocks` rows in BOTH directions:
  - `author_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = :viewer)`
  - `author_id NOT IN (SELECT blocker_id FROM user_blocks WHERE blocked_id = :viewer)`
- **Self arm** (the viewer's own posts — the docs/05 own-content exception applied at the feed layer): `FROM posts` with `p.author_id = :viewer AND p.deleted_at IS NULL`. The self arm MUST NOT filter `is_auto_hidden` (the author sees their own auto-hidden posts) and MUST NOT filter the author's `users.is_shadow_banned` (self-visibility for shadow-banned authors is the purpose of the arm). The self arm MUST keep `deleted_at IS NULL` — the author does NOT regain visibility of their own soft-deleted posts. The self arm carries NO `user_blocks` subqueries: it only ever returns rows authored by the viewer, and self-blocks are impossible (`user_blocks` CHECK).
- The two arms MUST be disjoint (`author_id <> :viewer` on the visible arm) so `UNION ALL` cannot duplicate a row. Each arm MUST carry its own keyset predicate `(p.created_at, p.id) < (:c, :i)` (omitted on the first page), its own `ORDER BY p.created_at DESC, p.id DESC`, and its own `LIMIT 31`, so each arm remains independently index-serviceable (`posts_timeline_cursor_idx` for the visible arm; `posts_author_idx` for the self arm) with top-N early exit. The outer query re-sorts the merged arms by `(created_at DESC, id DESC)` and applies the final `LIMIT 31`.

The visible arm's two block-exclusion subqueries MUST be present simultaneously so `BlockExclusionJoinRule` passes on the query literal. The literal's raw `FROM posts` / `JOIN users` (self arm only) MUST be allowlisted via `@AllowRawPostsRead("<reason>")` on the SQL-holding declaration — the `RawFromPostsRule` rule itself MUST NOT be modified or weakened.

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
- **WHEN** caller A has `is_shadow_banned = TRUE` AND 35 posts are eligible for A's Global feed (a mix of A's own posts and other visible authors' posts interleaved by `created_at` across the page-30 boundary)
- **THEN** page 1 returns 30 rows + `next_cursor`, page 2 returns the remaining 5, no row appears twice, and no eligible row is skipped

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

#### Scenario: Author-identity JOIN does not alter row count
- **WHEN** 35 visible posts exist for a viewer
- **THEN** the query returns exactly 35 rows after the per-arm author-identity joins

#### Scenario: Identity is sourced via visible_users on the visible arm; raw users only on the self arm
- **WHEN** inspecting the Global SQL literal in `JdbcPostsGlobalRepository`
- **THEN** the visible arm's author-identity join references `visible_users` AND the only raw `users` reference is the self arm's identity join (scoped to `author_id = :viewer`), covered by an `@AllowRawPostsRead` annotation on the SQL-holding declaration
