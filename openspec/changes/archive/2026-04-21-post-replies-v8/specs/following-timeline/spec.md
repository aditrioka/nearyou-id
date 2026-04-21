## MODIFIED Requirements

### Requirement: Canonical query joins visible_posts, follows, and excludes blocks bidirectionally

The endpoint's data query SHALL be the canonical Following query from `docs/05-Implementation.md` §1057–1067: `FROM visible_posts` (NOT `FROM posts`), with `author_user_id IN (SELECT followee_id FROM follows WHERE follower_id = :viewer)`, AND two NOT-IN subqueries excluding `user_blocks` rows in BOTH directions:
- `author_user_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = :viewer)`
- `author_user_id NOT IN (SELECT blocker_id FROM user_blocks WHERE blocked_id = :viewer)`

Both block-exclusion subqueries MUST be present simultaneously so `BlockExclusionJoinRule` passes.

As of V7, the query SHALL additionally `LEFT JOIN post_likes pl ON pl.post_id = p.id AND pl.user_id = :viewer` and project `(pl.user_id IS NOT NULL) AS liked_by_viewer` into the result set. The LEFT JOIN is PK-scoped (`post_likes_pk = (post_id, user_id)`), so at most one `post_likes` row matches per primary-query row — the join MUST NOT fan out rows, MUST NOT alter `COUNT(*)` over the result, MUST NOT appear in the `ORDER BY`, and MUST NOT appear in the keyset predicate `(p.created_at, p.id) < (:c, :i)`.

As of V8, the query SHALL additionally include a `LEFT JOIN LATERAL (SELECT COUNT(*) AS n FROM post_replies pr JOIN visible_users vu ON vu.id = pr.author_id WHERE pr.post_id = p.id AND pr.deleted_at IS NULL) c ON TRUE` and project `c.n AS reply_count` into the result set. The LATERAL sub-scalar evaluates to exactly one row per primary-query row — the join MUST NOT fan out rows, MUST NOT alter `COUNT(*)` over the result, MUST NOT appear in the `ORDER BY`, and MUST NOT appear in the keyset predicate. The counter MUST JOIN `visible_users` on the reply's `author_id` so shadow-banned repliers do NOT inflate the counter (shadow-ban parity with `likes/count` and with the Nearby-timeline counter). The counter MUST filter `pr.deleted_at IS NULL` so tombstoned replies do NOT inflate the counter. The counter MUST NOT apply viewer-block exclusion on `pr.author_id` — a per-viewer count would leak block state, the same privacy tradeoff documented for `likes/count` in V7 and for the Nearby-timeline counter in V8.

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

### Requirement: Response shape (posts minus distance)

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
      "created_at": "<ISO-8601 UTC>",
      "liked_by_viewer": <boolean>,
      "reply_count": <integer>
    }
  ],
  "next_cursor": "<string or null>"
}
```

The response shape is IDENTICAL to `nearby-timeline` MINUS the `distance_m` field AND inclusive of both the `liked_by_viewer` field (added in V7) and the `reply_count` field (added in V8). The `latitude`/`longitude` fields MUST be derived from `display_location` (NOT `actual_location`), preserving the jitter invariant from `post-creation`.

The `liked_by_viewer` field MUST be a JSON Boolean and MUST be present on EVERY post in the response (never omitted, never null). It MUST be `true` if and only if a `post_likes` row exists with `(post_id = <that post's id>, user_id = <caller>)`; otherwise `false`. The value is derived from the `LEFT JOIN post_likes` in the canonical query.

The `reply_count` field (added in V8) MUST be a JSON integer ≥ 0 and MUST be present on EVERY post in the response (never omitted, never null). It MUST equal the count of `post_replies` rows for the post where the reply's author is shadow-ban-visible (`JOIN visible_users`) AND the reply is not soft-deleted (`deleted_at IS NULL`). Viewer-block exclusion is DELIBERATELY NOT applied to this counter (privacy tradeoff; per-viewer count would leak block state). The value is derived from the `LEFT JOIN LATERAL` sub-scalar in the canonical query.

#### Scenario: No distance_m field
- **WHEN** a post appears in the response
- **THEN** the post object does NOT contain the key `distance_m`

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

### Requirement: Integration test coverage

`FollowingTimelineServiceTest` (tagged `database`) SHALL cover, at minimum, end-to-end against a Postgres test DB:
1. Happy path: caller follows three authors with posts; all three appear ordered by `created_at DESC`.
2. Empty result: caller follows nobody → `posts = []`, `next_cursor = null`.
3. Cursor pagination: 35 eligible posts, page 1 returns 30 + cursor, page 2 returns 5, no overlap.
4. Auto-hidden exclusion: a followed-author post with `is_auto_hidden = TRUE` is excluded.
5. Bidirectional block exclusion (two sub-cases):
   - Caller follows X AND caller-blocked-X: X's posts hidden.
   - Caller follows X AND X-blocked-caller: X's posts hidden.
6. Non-followed-author posts excluded: a post from an author the caller does NOT follow is absent even if otherwise visible.
7. Response has no `distance_m` field.
8. Auth required: HTTP 401 without JWT.
9. Malformed cursor rejected: HTTP 400 `invalid_cursor`.
10. `liked_by_viewer = true` when caller has a `post_likes` row for the post.
11. `liked_by_viewer = false` when caller has no `post_likes` row for the post.
12. `liked_by_viewer` key present on every post (iterate response; assert key presence).
13. LEFT JOIN cardinality invariant with likes: 20 eligible posts with 6 liked → 20 returned, not 26.
14. `reply_count = 0` when a post has no replies.
15. `reply_count` = exact count of visible replies when a post has multiple replies.
16. `reply_count` excludes shadow-banned repliers (post has 3 replies, 1 by shadow-banned → `reply_count = 2`).
17. `reply_count` excludes soft-deleted replies (post has 5 replies, 2 tombstoned → `reply_count = 3`).
18. `reply_count` does NOT apply viewer-block exclusion (post has 3 visible replies, 1 by viewer-blocked user → `reply_count = 3`).
19. `reply_count` key present on every post (iterate response; assert key presence).
20. LEFT JOIN LATERAL cardinality invariant with replies: 20 eligible posts with 150 collective replies → 20 returned, not 150+20.

#### Scenario: Test class exists
- **WHEN** running `./gradlew :backend:ktor:test --tests '*FollowingTimelineServiceTest*'`
- **THEN** the class is discovered AND every numbered scenario above corresponds to at least one `@Test` method
