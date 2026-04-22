## ADDED Requirements

### Requirement: post_replies.is_auto_hidden actively written by the V9 reports auto-hide path

V8 introduced `post_replies.is_auto_hidden BOOLEAN NOT NULL DEFAULT FALSE` and V8's GET `/api/v1/posts/{post_id}/replies` endpoint already filters `is_auto_hidden = FALSE OR author_id = :viewer`. Until V9 no code path flipped the column. V9 introduces the FIRST writer: the reports auto-hide path (see `reports` capability) SHALL execute `UPDATE post_replies SET is_auto_hidden = TRUE WHERE id = :target_id AND deleted_at IS NULL` when 3 distinct reporters aged > 7 days have reported the reply (via `target_type = 'reply'`).

The V8 reply-read-path contract — `is_auto_hidden = FALSE OR author_id = :viewer` — MUST continue to hold. Flipping the column automatically hides the reply from every non-author viewer while keeping it visible to the author (the "author sees their own auto-hidden reply" behavior documented in `docs/05-Implementation.md:738` and at the V8 read-path spec is unchanged, but now observable in practice).

The UPDATE MUST be idempotent (same contract as the `posts` variant in `post-creation`): re-running on an already-hidden reply is a no-op; soft-deleted replies (`deleted_at IS NOT NULL`) are skipped by the WHERE clause but still produce a `moderation_queue` row.

#### Scenario: Auto-hide flip hides reply from non-authors
- **WHEN** a reply is visible in GET `/api/v1/posts/{post_id}/replies` to a non-author viewer AND the reports auto-hide path flips `post_replies.is_auto_hidden = TRUE`
- **THEN** the next call to GET `/replies` (same viewer, same cursor) does NOT return that reply

#### Scenario: Author still sees their own auto-hidden reply
- **WHEN** a reply is auto-hidden AND the author themselves calls GET `/api/v1/posts/{post_id}/replies` (with `author_id = :viewer`)
- **THEN** the reply IS returned in the list (the V8 `is_auto_hidden = FALSE OR author_id = :viewer` filter preserves author visibility)

#### Scenario: Reply-counter on timelines honors auto-hide (visible_users shadow-ban exclusion only)
- **WHEN** a reply is auto-hidden AND that reply's parent post is listed on the nearby or following timeline
- **THEN** the `reply_count` field on the parent post includes the auto-hidden reply (the V8 counter excludes only shadow-banned authors via `JOIN visible_users`, not auto-hidden replies); V9 does NOT change this — the counter privacy tradeoff documented in V8 remains in effect

#### Scenario: UPDATE on already-hidden reply is a no-op
- **WHEN** the reports auto-hide path runs its UPDATE on a reply whose `is_auto_hidden` is already TRUE
- **THEN** the UPDATE affects 0 additional state

#### Scenario: UPDATE skips soft-deleted reply
- **WHEN** the reports auto-hide path runs on a reply with `deleted_at IS NOT NULL`
- **THEN** the UPDATE affects 0 rows (the WHERE clause excludes soft-deleted targets; the `moderation_queue` row is still inserted)

### Requirement: POST / GET / DELETE reply endpoints unchanged by V9

V9 MUST NOT alter the shape, auth, validation, or response of `POST`/`GET`/`DELETE` on `/api/v1/posts/{post_id}/replies[/{reply_id}]`. V9 adds only a DB-level writer of `post_replies.is_auto_hidden`. The column default stays FALSE on reply creation; the V8 read-path filter stays structurally identical.

#### Scenario: New reply starts with is_auto_hidden = FALSE
- **WHEN** a successful POST `/api/v1/posts/{post_id}/replies` completes (post-V9)
- **THEN** the inserted row's `is_auto_hidden` column equals `FALSE`

#### Scenario: V8 auto-hide-with-author-bypass filter unchanged
- **WHEN** inspecting the GET `/replies` SQL (post-V9)
- **THEN** the WHERE clause still contains `(is_auto_hidden = FALSE OR author_id = :viewer)` (V9 does not alter this filter)
