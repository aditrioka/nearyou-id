# post-replies (delta)

## MODIFIED Requirements

### Requirement: POST replies — post visibility resolution

Before INSERTing a reply, the service SHALL resolve the parent post via the same visibility pattern used by `LikeService.resolveVisiblePost` — as of `shadow-ban-feed-self-visibility`, the viewer-aware two-arm shape (kept shape-identical between the like and reply repositories):

```
SELECT p.id
FROM visible_posts p
WHERE p.id = :post_id
  AND p.author_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = :caller)
  AND p.author_id NOT IN (SELECT blocker_id FROM user_blocks WHERE blocked_id = :caller)
UNION ALL
SELECT p.id
FROM posts p
WHERE p.id = :post_id
  AND p.author_id = :caller
  AND p.deleted_at IS NULL
LIMIT 1
```

The self arm grants the CALLER-AS-AUTHOR visibility of their own parent post regardless of shadow-ban or auto-hide state, but NOT of their own soft-deleted posts. It carries no `user_blocks` subqueries (self-blocks are impossible); the raw `FROM posts` is allowlisted via `@AllowRawPostsRead("<reason>")` on the SQL-holding declaration — `RawFromPostsRule` itself MUST NOT be weakened.

If the SELECT returns no row, the service MUST throw `PostNotFoundException` and the route MUST return HTTP 404 with error code `post_not_found`. The response body MUST be the constant `{ "error": { "code": "post_not_found" } }` with no additional fields — MUST NOT distinguish "missing" vs "soft-deleted" vs "caller-blocked-author" vs "author-blocked-caller" vs "post is auto-hidden" vs "post author is shadow-banned" (the last two apply to non-author callers only). The route MUST NOT return HTTP 403.

#### Scenario: Missing post returns opaque 404
- **WHEN** `{post_id}` is a well-formed UUID that does not exist in `posts`
- **THEN** the response is HTTP 404 with body `{ "error": { "code": "post_not_found" } }`

#### Scenario: Soft-deleted post returns opaque 404
- **WHEN** the parent post has `deleted_at IS NOT NULL` (even when the caller is its author)
- **THEN** the response is HTTP 404 with the same opaque `post_not_found` code (identical to the missing-post response)

#### Scenario: Auto-hidden post returns opaque 404 for non-authors
- **WHEN** the parent post has `is_auto_hidden = TRUE` (filtered by `visible_posts`) AND the caller is NOT its author
- **THEN** the response is HTTP 404 with `post_not_found` — indistinguishable from the missing-post response

#### Scenario: Shadow-banned-author post returns opaque 404 for non-authors
- **WHEN** the parent post's author has `is_shadow_banned = TRUE` AND the caller is NOT its author
- **THEN** the response is HTTP 404 with `post_not_found` — indistinguishable from the missing-post response

#### Scenario: Shadow-banned author can reply to their own post
- **WHEN** caller A has `is_shadow_banned = TRUE` AND posts a valid reply to their OWN non-deleted post
- **THEN** the response is HTTP 201 and the reply row is inserted (self arm resolves the parent)

#### Scenario: Author can reply to their own auto-hidden post
- **WHEN** caller A's own parent post has `is_auto_hidden = TRUE` AND A posts a valid reply to it
- **THEN** the response is HTTP 201 (auto-hide is transparent to the author)

#### Scenario: Caller blocked post author returns opaque 404
- **WHEN** a `user_blocks` row `(blocker_id = caller, blocked_id = post_author)` exists
- **THEN** the response is HTTP 404 with `post_not_found` — NOT HTTP 403 (no block-state leak)

#### Scenario: Post author blocked caller returns opaque 404
- **WHEN** a `user_blocks` row `(blocker_id = post_author, blocked_id = caller)` exists
- **THEN** the response is HTTP 404 with `post_not_found` — NOT HTTP 403

### Requirement: GET replies — parent-post visibility resolution

Before listing replies, the service SHALL resolve the parent post using the SAME viewer-aware visibility query as POST replies (`visible_posts` + bidirectional `user_blocks`, UNION ALL'd with the own-content self arm). If the parent post is invisible to the caller (missing / soft-deleted / auto-hidden-for-non-authors / shadow-banned-author-for-non-authors / caller-blocked-author / author-blocked-caller), the endpoint MUST return HTTP 404 with body `{ "error": { "code": "post_not_found" } }` — the same opaque envelope used by POST. The endpoint MUST NOT return an empty reply list in place of 404 when the parent is invisible. The parent post's AUTHOR can always list replies on their own non-deleted post, including while shadow-banned or while the post is auto-hidden.

#### Scenario: Invisible parent returns 404, not empty list
- **WHEN** the parent post is soft-deleted OR (for a non-author caller) auto-hidden OR authored by a shadow-banned user OR subject to a bidirectional block with the caller
- **THEN** the response is HTTP 404 with `post_not_found` — NOT HTTP 200 with `replies = []`

#### Scenario: Shadow-banned author can read their own post's reply thread
- **WHEN** caller A has `is_shadow_banned = TRUE` AND requests `GET /api/v1/posts/{P}/replies` for their OWN non-deleted post P
- **THEN** the response is HTTP 200 with the reply list (NOT 404)

### Requirement: GET replies — canonical query with block exclusion and auto-hidden filter

The data query SHALL read from `post_replies` with a `LEFT JOIN visible_users vu ON vu.id = pr.author_id` and the author-bypass predicate `(vu.id IS NOT NULL OR pr.author_id = :caller)` (shadow-ban exclusion on the reply author, EXCEPT the caller's own replies — added by `shadow-ban-feed-self-visibility` so a shadow-banned user still sees their OWN replies in any thread they can read; for non-author rows the predicate is exactly the previous INNER JOIN semantics). The query SHALL apply bidirectional `user_blocks` NOT-IN on `author_id`:
- `author_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = :caller)` (viewer-blocked reply authors hidden)
- `author_id NOT IN (SELECT blocker_id FROM user_blocks WHERE blocked_id = :caller)` (authors-who-blocked-viewer hidden)

The query MUST also filter:
- `deleted_at IS NULL` (soft-deleted replies hidden, including from their author)
- `(is_auto_hidden = FALSE OR author_id = :caller)` (author still sees their own auto-hidden replies; everyone else does not)

Both `user_blocks` NOT-IN subqueries MUST be present simultaneously so `BlockExclusionJoinRule` passes on the Kotlin string literal.

#### Scenario: Soft-deleted reply excluded for everyone
- **WHEN** a reply has `deleted_at IS NOT NULL`
- **THEN** that reply does NOT appear in any caller's response (including the author's)

#### Scenario: Shadow-banned reply author excluded for other viewers
- **WHEN** a reply's author has `is_shadow_banned = TRUE` AND the caller is NOT that author
- **THEN** that reply does NOT appear in the response (filtered by the `visible_users` arm of the author-bypass predicate)

#### Scenario: Shadow-banned caller sees their own replies
- **WHEN** caller A has `is_shadow_banned = TRUE` AND A has a non-deleted reply on a parent post A can read
- **THEN** A's own reply DOES appear in A's response (author bypass), while any other caller's response omits it

#### Scenario: Viewer-blocked reply author excluded
- **WHEN** the caller has a `user_blocks` row `(blocker_id = caller, blocked_id = X)` AND X has a reply to the parent post
- **THEN** X's reply does NOT appear in the response

#### Scenario: Reply-author-blocks-viewer excluded
- **WHEN** a `user_blocks` row `(blocker_id = X, blocked_id = caller)` exists AND X has a reply to the parent post
- **THEN** X's reply does NOT appear in the response

#### Scenario: Auto-hidden reply visible to its author
- **WHEN** reply R has `is_auto_hidden = TRUE` AND the caller is R's author
- **THEN** R appears in the response

#### Scenario: Auto-hidden reply hidden from non-authors
- **WHEN** reply R has `is_auto_hidden = TRUE` AND the caller is NOT R's author
- **THEN** R does NOT appear in the response
