# visible-posts-view (delta)

## MODIFIED Requirements

### Requirement: View excludes shadow-banned and soft-deleted authors' posts

Rows whose author has `is_shadow_banned = TRUE` or `deleted_at IS NOT NULL`, and rows that are themselves soft-deleted (`posts.deleted_at IS NOT NULL`), MUST NOT appear in `visible_posts` (added by V20; per docs/06 § Shadow Ban, a shadow-banned author's content is "invisible to others"). The own-content exception — a shadow-banned user sees their OWN content normally — is NOT carried by this viewer-agnostic view (views take no viewer parameter). It is carried by the consuming layer:

- the Repository own-content paths that read raw `posts`/`users` (docs/05 § Own-content exception — e.g., the profile self read), and
- as of `shadow-ban-feed-self-visibility`, the viewer-aware own-content self arms in the Nearby/Global timeline queries and in the like/reply `resolveVisiblePost` resolution (each a `UNION ALL` arm over raw `posts` scoped to `author_id = :viewer AND deleted_at IS NULL`, allowlisted via `@AllowRawPostsRead`).

The view definition itself is unchanged by `shadow-ban-feed-self-visibility` — no migration; V20 remains the authoritative definition.

#### Scenario: Shadow-banning an author hides their posts live
- **WHEN** `users.is_shadow_banned` is flipped to `TRUE` for an author with existing posts
- **THEN** `SELECT 1 FROM visible_posts WHERE id = <their post>` returns zero rows WITHOUT a view refresh step

#### Scenario: Un-shadow-banning restores visibility live
- **WHEN** `users.is_shadow_banned` is flipped back to `FALSE`
- **THEN** the author's non-auto-hidden, non-deleted posts reappear in `visible_posts`

#### Scenario: Soft-deleted author's posts are excluded
- **WHEN** an author row has `deleted_at IS NOT NULL`
- **THEN** none of that author's posts appear in `visible_posts`

#### Scenario: Soft-deleted post is excluded
- **WHEN** a `posts` row has `deleted_at IS NOT NULL`
- **THEN** that row does not appear in `visible_posts`

#### Scenario: View stays viewer-agnostic after the self-visibility change
- **WHEN** querying `pg_views WHERE viewname = 'visible_posts'` after `shadow-ban-feed-self-visibility` ships
- **THEN** the definition is byte-equivalent to the V20 shape (no viewer parameter, no `current_setting`, no self-arm in the view)
