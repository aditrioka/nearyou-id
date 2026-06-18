## RENAMED Requirements

- FROM: `### Requirement: View excludes shadow-banned and soft-deleted authors' posts`
- TO: `### Requirement: View excludes shadow-banned authors and soft-deleted posts, but surfaces tombstoned authors' posts`

## MODIFIED Requirements

### Requirement: visible_posts view definition

Migration `V4__post_creation.sql` SHALL create a SQL view `visible_posts` in the same migration as the `posts` table (originally `SELECT * FROM posts WHERE is_auto_hidden = FALSE`). Migration `V20__visible_posts_shadow_ban_author.sql` redefined the view to add the author-side join predicates (`JOIN users`, `is_shadow_banned = FALSE`, `u.deleted_at IS NULL`) merged with the auto-hide + post-soft-delete filters. Migration `V28__visible_posts_surface_tombstoned_authors.sql` (renumbered above the freshly-merged migration cluster at rebase) SHALL redefine the view once more to **drop the author-side `u.deleted_at IS NULL` predicate only**, so that a tombstoned (account-deleted) author's posts surface in the view rendered anonymized, while the post-soft-delete (`p.deleted_at IS NULL`), auto-hide (`is_auto_hidden = FALSE`), and shadow-ban (`u.is_shadow_banned = FALSE`) predicates remain byte-identical (the `account-deletion-tombstone` change, design D1):

```sql
CREATE OR REPLACE VIEW visible_posts AS
SELECT p.*
FROM posts p
JOIN users u ON u.id = p.author_id
WHERE p.is_auto_hidden = FALSE
  AND p.deleted_at IS NULL
  AND u.is_shadow_banned = FALSE;
```

The `p.deleted_at IS NULL` predicate keeps the V4 partial cursor indexes (`WHERE deleted_at IS NULL`) eligible for view-backed timeline queries (finding 02-H1). The author-side join is retained (a tombstoned author's `users` row persists, so the `JOIN users` still matches), only the author-deletion *exclusion* is removed.

#### Scenario: View exists after V4
- **WHEN** querying `pg_views WHERE viewname = 'visible_posts'`
- **THEN** one row returns AND its `definition` reads from `posts` and contains `is_auto_hidden = FALSE` (post-V20, Postgres renders the source as `FROM (posts p JOIN users u ...)`)

#### Scenario: Post-V28 definition keeps shadow-ban + post-soft-delete but not the author-deletion exclusion
- **WHEN** querying `pg_views WHERE viewname = 'visible_posts'` on a database migrated to V28 or later
- **THEN** the `definition` contains `JOIN users`, `is_shadow_banned = FALSE`, and `p.deleted_at IS NULL` (the post-side soft-delete predicate) AND does NOT contain an author-side `u.deleted_at IS NULL` exclusion

#### Scenario: V20-era definition carried both deleted_at predicates (historical)
- **WHEN** querying `pg_views WHERE viewname = 'visible_posts'` on a database at exactly V20–V27 (before V28)
- **THEN** the `definition` contained both `p.deleted_at IS NULL` and `u.deleted_at IS NULL` (V28 later drops the author-side one)

### Requirement: View excludes shadow-banned authors and soft-deleted posts, but surfaces tombstoned authors' posts

Rows whose author has `is_shadow_banned = TRUE`, and rows that are themselves soft-deleted (`posts.deleted_at IS NOT NULL`), MUST NOT appear in `visible_posts`. As of V28 (`account-deletion-tombstone`), rows whose author is **tombstoned** (`users.deleted_at IS NOT NULL` — a hard-deleted account) MUST still APPEAR in `visible_posts` (rendered anonymized via the author's server-set `display_name = 'Akun Dihapus'` + `deleted_user_` handle), per `docs/06` § Account Deletion ("posts remain, author becomes Akun Dihapus"). This reverses the V20 author-soft-delete exclusion specifically for the account-deletion tombstone case; the shadow-ban exclusion is unchanged and dominates (a shadow-banned-then-deleted author stays hidden). The own-content exception — a shadow-banned user sees their OWN content — is carried by the consuming layer (the Repository own-content paths and the `shadow-ban-feed-self-visibility` viewer-aware self arms), unchanged here; a tombstoned user has no session to view anything.

#### Scenario: Shadow-banning an author hides their posts live
- **WHEN** `users.is_shadow_banned` is flipped to `TRUE` for an author with existing posts
- **THEN** `SELECT 1 FROM visible_posts WHERE id = <their post>` returns zero rows WITHOUT a view refresh step

#### Scenario: Un-shadow-banning restores visibility live
- **WHEN** `users.is_shadow_banned` is flipped back to `FALSE`
- **THEN** the author's non-auto-hidden, non-deleted posts reappear in `visible_posts`

#### Scenario: Tombstoned author's posts are surfaced (anonymized), not excluded
- **WHEN** an author row has `deleted_at IS NOT NULL` (hard-deleted) but `is_shadow_banned = FALSE`, and a non-auto-hidden, non-soft-deleted post of theirs
- **THEN** `SELECT 1 FROM visible_posts WHERE id = <that post>` returns one row (the post surfaces; the author's server-set `display_name = 'Akun Dihapus'` renders at the consuming layer)

#### Scenario: Shadow-banned AND tombstoned author stays hidden
- **WHEN** an author has BOTH `is_shadow_banned = TRUE` AND `deleted_at IS NOT NULL`
- **THEN** none of that author's posts appear in `visible_posts` (the shadow-ban exclusion dominates over the tombstone-surfacing rule)

#### Scenario: Soft-deleted post is excluded
- **WHEN** a `posts` row has `deleted_at IS NOT NULL`
- **THEN** that row does not appear in `visible_posts` (post-side soft-delete is unchanged by V28)

#### Scenario: View stays viewer-agnostic after the tombstone-surfacing change
- **WHEN** querying `pg_views WHERE viewname = 'visible_posts'` after V28 ships
- **THEN** the rendered definition carries `is_auto_hidden = FALSE`, `p.deleted_at IS NULL`, and `is_shadow_banned = FALSE`, omits the author-side `u.deleted_at` exclusion, AND contains no viewer-aware construct — no `UNION`, no `current_setting`, no self-arm (the self-visibility arm lives in the consuming queries, not the view)
