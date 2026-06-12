# post-likes (delta)

## MODIFIED Requirements

### Requirement: 404 on missing, soft-deleted, or block-hidden post (no 403 leak)

Before inserting, the route handler SHALL resolve the target post via a viewer-aware SELECT: the existing `visible_posts` + bidirectional `user_blocks` resolution, UNION ALL'd with an own-content self arm (added by `shadow-ban-feed-self-visibility` — without it, V20 made `resolveVisiblePost` 404 a shadow-banned author on their OWN post, an instant ban-detectability oracle):

```
SELECT p.id
FROM visible_posts p
WHERE p.id = :post_id
  AND p.author_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = :viewer)
  AND p.author_id NOT IN (SELECT blocker_id FROM user_blocks WHERE blocked_id = :viewer)
UNION ALL
SELECT p.id
FROM posts p
WHERE p.id = :post_id
  AND p.author_id = :viewer
  AND p.deleted_at IS NULL
LIMIT 1
```

The self arm grants the CALLER-AS-AUTHOR visibility of their own post regardless of shadow-ban or auto-hide state, but NOT of their own soft-deleted posts (`deleted_at IS NULL` stays). The self arm carries no `user_blocks` subqueries (self-blocks are impossible); the raw `FROM posts` is allowlisted via `@AllowRawPostsRead("<reason>")` on the SQL-holding declaration — `RawFromPostsRule` itself MUST NOT be weakened.

If the SELECT returns zero rows, the response MUST be HTTP 404 with error code `post_not_found`. For a NON-author caller this single error code MUST cover all four invisibility causes: post does not exist, post was soft-deleted, post is hidden by `visible_posts` (auto-hidden OR author shadow-banned/soft-deleted — the fourth cause, added by V20), and a block exists in either direction between caller and author. For the post's AUTHOR the only 404 cause is missing/soft-deleted. The endpoint MUST NOT return HTTP 403 under any circumstance and MUST NOT leak which case applied.

Both block-exclusion subqueries MUST be present simultaneously so `BlockExclusionJoinRule` passes on the like-service source.

#### Scenario: Unknown post id
- **WHEN** caller A likes a UUID that does not exist in `posts`
- **THEN** the response is HTTP 404 with `error.code = "post_not_found"`

#### Scenario: Soft-deleted post
- **WHEN** caller A likes a post that has `is_auto_hidden = TRUE` (excluded by `visible_posts`) AND A is not its author
- **THEN** the response is HTTP 404 with `error.code = "post_not_found"` AND no `post_likes` row is inserted

#### Scenario: Caller has blocked post author
- **WHEN** caller A has a `user_blocks` row `(A, B)` AND tries to like a post whose `author_id = B`
- **THEN** the response is HTTP 404 with `error.code = "post_not_found"` AND no `post_likes` row is inserted

#### Scenario: Post author has blocked caller
- **WHEN** author B has a `user_blocks` row `(B, A)` AND caller A tries to like a post whose `author_id = B`
- **THEN** the response is HTTP 404 with `error.code = "post_not_found"` AND no `post_likes` row is inserted

#### Scenario: Shadow-banned-author post invisible to other callers
- **WHEN** author B has `is_shadow_banned = TRUE` AND caller A (`A ≠ B`) tries to like B's post
- **THEN** the response is HTTP 404 with `error.code = "post_not_found"` AND no `post_likes` row is inserted

#### Scenario: Shadow-banned author can like their own post
- **WHEN** caller A has `is_shadow_banned = TRUE` AND likes their OWN non-deleted post
- **THEN** the response is HTTP 204 AND the `post_likes` row is inserted (self arm resolves the post)

#### Scenario: Shadow-banned author can read their own like count
- **WHEN** caller A has `is_shadow_banned = TRUE` AND reads `GET /likes/count` for their OWN non-deleted post
- **THEN** the response is HTTP 200 (the count itself stays shadow-ban-filtered per the count requirement)

#### Scenario: Author still 404s on their own soft-deleted post
- **WHEN** caller A likes their OWN post that has `deleted_at IS NOT NULL`
- **THEN** the response is HTTP 404 with `error.code = "post_not_found"` (the self arm keeps `deleted_at IS NULL`)

#### Scenario: 404 response body identical across cases
- **WHEN** the 404 is returned for any of {missing post, soft-deleted post, auto-hidden post, shadow-banned-author post, caller-blocked-author, author-blocked-caller}
- **THEN** the response body is the constant JSON envelope `{ "error": { "code": "post_not_found" } }` with no additional fields
