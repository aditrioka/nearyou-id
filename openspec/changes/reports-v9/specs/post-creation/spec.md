## ADDED Requirements

### Requirement: posts.is_auto_hidden actively written by the V9 reports auto-hide path

V4 introduced `posts.is_auto_hidden BOOLEAN NOT NULL DEFAULT FALSE` and V4's `visible_posts` view filters `WHERE is_auto_hidden = FALSE`. Until V9 no code path flipped the column. V9 introduces the FIRST writer of this column: the reports auto-hide path (see `reports` capability) SHALL execute `UPDATE posts SET is_auto_hidden = TRUE WHERE id = :target_id AND deleted_at IS NULL` when 3 distinct reporters aged > 7 days have reported the post.

The V4 contract — every `SELECT ... FROM visible_posts` query skips rows where `is_auto_hidden = TRUE` — MUST continue to hold, so flipping the column automatically hides the post from every authenticated timeline (`nearby-timeline`, `following-timeline`, future Global timeline) and from search. The `posts` table, the `visible_posts` view definition, and the V4 migration remain unchanged.

The UPDATE MUST be idempotent: running it on an already-hidden post MUST be a no-op (the `SET is_auto_hidden = TRUE` reassignment to the same value is a no-op in Postgres; the WHERE clause's `deleted_at IS NULL` guard prevents an accidental flip on a tombstoned post).

#### Scenario: Auto-hide flip hides post from nearby timeline immediately
- **WHEN** a post is visible in `GET /api/v1/timeline/nearby` AND the reports auto-hide path flips `is_auto_hidden = TRUE`
- **THEN** the next call to `GET /api/v1/timeline/nearby` with the same parameters does NOT return that post (the `visible_posts` view excludes it, no additional read-path change is needed)

#### Scenario: Auto-hide flip hides post from following timeline immediately
- **WHEN** a post is visible in `GET /api/v1/timeline/following` AND the reports auto-hide path flips `is_auto_hidden = TRUE`
- **THEN** the next call to `GET /api/v1/timeline/following` does NOT return that post

#### Scenario: Author still sees their own auto-hidden post on retrieval (if applicable)
- **WHEN** a post is auto-hidden AND the author queries an own-content path that does NOT go through `visible_posts` (e.g., future profile "my posts" endpoint)
- **THEN** the post is still retrievable via that own-content path (the column flip does not soft-delete; it only affects `visible_posts` consumers)

#### Scenario: UPDATE on already-hidden post is a no-op
- **WHEN** the reports auto-hide path runs its UPDATE on a post whose `is_auto_hidden` is already TRUE
- **THEN** the UPDATE affects 0 additional state (no row churn, no trigger cascade)

#### Scenario: UPDATE skips soft-deleted post
- **WHEN** the reports auto-hide path runs its UPDATE on a post whose `deleted_at IS NOT NULL`
- **THEN** the UPDATE affects 0 rows (the WHERE clause excludes soft-deleted targets; the associated `moderation_queue` row is still inserted per the `moderation-queue` capability)

### Requirement: POST /api/v1/posts behavior unchanged by V9

V9 MUST NOT alter the shape, auth, validation, or response of `POST /api/v1/posts`. The column `is_auto_hidden` defaults to `FALSE` on INSERT and stays FALSE until the reports auto-hide path (or a future admin action) flips it.

#### Scenario: New post starts with is_auto_hidden = FALSE
- **WHEN** a successful `POST /api/v1/posts` completes (post-V9)
- **THEN** the inserted row's `is_auto_hidden` column equals `FALSE`

#### Scenario: Response body shape unchanged
- **WHEN** parsing the 201 body as JSON (post-V9)
- **THEN** the key set still equals `{ "id", "content", "latitude", "longitude", "distance_m", "created_at" }` (no `is_auto_hidden` leak)
