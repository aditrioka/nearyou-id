## MODIFIED Requirements

### Requirement: Other-viewer reads resolve through visible_posts and collapse hidden posts to 404

For a read where the post's author is NOT the calling viewer, the post MUST be resolved through the `visible_posts` view (never a raw `FROM posts` business read outside the sanctioned own-content arm). A post that is soft-deleted (`posts.deleted_at IS NOT NULL`), whose author is shadow-banned (`is_shadow_banned = TRUE`), or that is content-moderation auto-hidden (`is_auto_hidden = TRUE`) MUST be unresolvable for other viewers and MUST produce `404 post_not_found`. An unknown post UUID MUST also produce `404 post_not_found`.

As of `account-deletion-tombstone` (V24), a post whose author is **tombstoned** (account hard-deleted: `users.deleted_at IS NOT NULL`, NOT shadow-banned) MUST instead resolve to `200` with the author identity **anonymized** (`authorDisplayName = "Akun Dihapus"`, `authorUsername` of the `deleted_user_…` form — the placeholder is set server-side on the tombstoned row). This is because `visible_posts` now surfaces tombstoned authors' non-soft-deleted, non-auto-hidden posts (per the `visible-posts-view` capability); the author's account deletion no longer hides their post (the previous "author … soft-deleted … MUST produce 404" clause is removed for the tombstone case). Soft-deleted POSTS, shadow-banned authors, and auto-hidden posts still collapse to `404`.

#### Scenario: Unknown post UUID
- **WHEN** an authenticated viewer calls `GET /api/v1/posts/<uuid that does not exist>`
- **THEN** the response is `404` with body `{"error":{"code":"post_not_found"}}`

#### Scenario: Soft-deleted post
- **WHEN** an authenticated viewer (not the author) calls `GET /api/v1/posts/{P}` where P has `deleted_at` set
- **THEN** the response is `404 post_not_found`

#### Scenario: Shadow-banned author hides the post from other viewers
- **WHEN** an authenticated viewer V calls `GET /api/v1/posts/{P}` where P's author (≠ V) is shadow-banned
- **THEN** the response is `404 post_not_found` (P is absent from `visible_posts`)

#### Scenario: Auto-hidden post
- **WHEN** an authenticated viewer (not the author) calls `GET /api/v1/posts/{P}` where P has `is_auto_hidden = TRUE`
- **THEN** the response is `404 post_not_found`

#### Scenario: Tombstoned author's post resolves to 200, anonymized
- **WHEN** an authenticated viewer V (≠ author) calls `GET /api/v1/posts/{P}` where P's author is hard-deleted (`users.deleted_at` set, `is_shadow_banned = FALSE`) and P is non-soft-deleted, non-auto-hidden, and not bidirectionally blocked
- **THEN** the response is `200` with `authorDisplayName = "Akun Dihapus"` and `authorUsername` matching `deleted_user_…` — the post is no longer hidden by the author's deletion
