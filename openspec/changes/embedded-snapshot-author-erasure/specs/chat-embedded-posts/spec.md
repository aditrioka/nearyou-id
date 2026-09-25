## RENAMED Requirements

- FROM: `### Requirement: Embedded snapshots are NOT re-anonymized on author account-tombstone (deferred)`
- TO: `### Requirement: Embedded snapshots are re-anonymized on author account-tombstone`

## MODIFIED Requirements

### Requirement: Embedded snapshots are re-anonymized on author account-tombstone

The `embedded_post_snapshot` is captured at share time and is otherwise immutable, but its author identity SHALL NOT outlive the author's account. When a post's author is tombstoned (account-deletion hard-delete, `account-hard-delete-worker` — both the daily worker and the Apple S2S immediate path), every `chat_messages` row whose `embedded_post_author_id` is that author SHALL have its snapshot's `authorUsername` and `authorDisplayName` overwritten **at rest** with the tombstoned `users` row's `username` (`deleted_user_…`) and `display_name` (`Akun Dihapus`) — the same identity the live `single-post-read` path renders for a tombstoned author. Every other snapshot key (`content`, `cityName`, `createdAt`, `editedAt`) SHALL be left unchanged (authored content is retained anonymized per `docs/06` § Account Deletion), and the key set SHALL stay exactly the spec'd set (no key added or removed). The scrub SHALL apply regardless of the row's `embedded_post_id` (NULL after a source-post hard-delete) and regardless of `redacted_at` (a redacted row's snapshot is retained at rest, so it is erased too). Snapshots of posts by OTHER authors — including posts the tombstoned user merely shared as a sender — SHALL NOT be touched. This fulfils the previously deferred requirement tracked by [#425](https://github.com/aditrioka/nearyou-id/issues/425).

#### Scenario: Snapshot identity is scrubbed when the author tombstones
- **GIVEN** an embed message whose snapshot captured author A's handle + display name
- **WHEN** A's account is hard-deleted by the account-hard-delete worker
- **THEN** the persisted snapshot's `authorUsername` equals A's tombstoned `users.username` (matching `^deleted_user_[0-9a-f]{8,}$`) AND `authorDisplayName` equals `Akun Dihapus` AND `content` / `cityName` / `createdAt` / `editedAt` are byte-identical to before AND the key set is unchanged

#### Scenario: Scrub survives a source-post hard-delete
- **GIVEN** an embed message of A's post P whose `embedded_post_id` was set to NULL by a hard-delete of P (snapshot retained, `embedded_post_author_id = A`)
- **WHEN** A's account is hard-deleted
- **THEN** the snapshot's author identity is scrubbed (the linkage is `embedded_post_author_id`, not `embedded_post_id`)

#### Scenario: Admin-redacted embed rows are scrubbed too
- **GIVEN** an embed message of A's post with `redacted_at` set
- **WHEN** A's account is hard-deleted
- **THEN** the retained snapshot's author identity is scrubbed

#### Scenario: Other authors' snapshots are untouched
- **GIVEN** user A (about to be deleted) SENT an embed of author B's post, and a separate message embeds a post by author C
- **WHEN** A's account is hard-deleted
- **THEN** both snapshots still carry B's and C's original identity (only snapshots whose `embedded_post_author_id = A` are scrubbed)

#### Scenario: Recipient's message list shows the anonymized card
- **GIVEN** recipient R has an embed message of A's post in a conversation
- **WHEN** A is hard-deleted and R reads `GET /api/v1/chat/{conversation_id}/messages`
- **THEN** the returned `embedded_post_snapshot` carries the tombstoned `authorUsername` and `authorDisplayName = "Akun Dihapus"` and no longer contains A's original handle or display name anywhere in the payload

## ADDED Requirements

### Requirement: The embed message records its post author for erasure linkage

Migration `V38__chat_embedded_post_author_erasure.sql` SHALL add `chat_messages.embedded_post_author_id UUID NULL REFERENCES users(id) ON DELETE SET NULL` and a partial index `chat_messages_embedded_post_author_idx ON chat_messages (embedded_post_author_id) WHERE embedded_post_author_id IS NOT NULL` (the predicate is `NOW()`-free). When the send path persists an embed message it SHALL set `embedded_post_author_id` to the resolved post's `author_id`; a plain (non-embed) message SHALL leave it NULL. The author UUID SHALL be used ONLY for this column — it SHALL NOT be added to `embedded_post_snapshot`, to the send response, to the message-list response, or to the Realtime broadcast payload (the snapshot's no-author-UUID rule is unchanged). The column survives a hard-delete of the source post (it does not depend on `embedded_post_id`).

In the same migration, existing embed rows SHALL be backfilled with `embedded_post_author_id = posts.author_id` via `embedded_post_id`, and every existing snapshot whose linked author is ALREADY tombstoned (`users.deleted_at IS NOT NULL`) SHALL be scrubbed exactly as the tombstone requirement prescribes, so authors deleted before this change are erased too.

#### Scenario: Embed send records the post author
- **GIVEN** sender S shares post P authored by A
- **WHEN** the embed message is persisted
- **THEN** the row's `embedded_post_author_id = A` AND the snapshot JSON, the `201` response body, and the broadcast payload contain no `embedded_post_author_id` / author UUID

#### Scenario: Plain message leaves the linkage NULL
- **WHEN** a text-only message (no `embedded_post_id`) is persisted
- **THEN** its `embedded_post_author_id IS NULL`

#### Scenario: Column, FK, and partial index exist after migration
- **WHEN** the migration set is applied and `chat_messages` is inspected
- **THEN** `embedded_post_author_id` exists as nullable `uuid` with a foreign key to `users(id)` whose delete rule is `SET NULL` AND the index `chat_messages_embedded_post_author_idx` exists with a `WHERE embedded_post_author_id IS NOT NULL` predicate containing no `NOW()`

#### Scenario: V38 backfills linkage for pre-existing embeds
- **GIVEN** an embed row persisted before V38 (column absent) referencing post P by author A
- **WHEN** V38 is applied
- **THEN** the row's `embedded_post_author_id = A`

#### Scenario: V38 retro-scrubs snapshots of already-tombstoned authors
- **GIVEN** before V38, an embed row of author A's post exists AND A is already tombstoned (`deleted_at` set) with the snapshot still carrying A's original identity
- **WHEN** V38 is applied
- **THEN** the snapshot's `authorUsername` / `authorDisplayName` equal A's tombstoned `users.username` / `display_name` AND other rows' snapshots are unchanged

### Requirement: A share racing the author's tombstone cannot persist a stale identity

The embed snapshot is built from a read that precedes the send transaction, so a tombstone committing in between could otherwise leave a freshly inserted snapshot with the pre-deletion identity. Inside the send transaction, after the INSERT, the send path SHALL re-check the linked author and, when `users.deleted_at IS NOT NULL`, SHALL scrub the new row's snapshot identity (same overwrite as the tombstone requirement) before commit; the scrubbed snapshot SHALL be the one returned in the `201` response and published in the after-commit broadcast. Correctness relies on the `embedded_post_author_id` FK check's `FOR KEY SHARE` lock on the author row conflicting with the tombstone `UPDATE`'s row lock, which serializes the two transactions: if the send commits first, the worker's scrub (a later statement) sees the committed row; if the tombstone commits first, the send's post-insert re-check sees `deleted_at`.

#### Scenario: Tombstone commits between resolve and INSERT
- **GIVEN** S's embed of A's post resolved A's live identity, and A's tombstone transaction (tombstone + scrub) commits before S's INSERT
- **WHEN** S's send transaction inserts the row and commits
- **THEN** the persisted snapshot carries A's tombstoned identity AND the `201` response's snapshot carries the tombstoned identity

#### Scenario: Tombstone blocked behind an in-flight send
- **GIVEN** S's send transaction has inserted an embed of A's post (holding the FK's key-share lock on A's `users` row) and not yet committed
- **WHEN** A's tombstone runs concurrently and S's transaction then commits
- **THEN** the tombstone waits for S's commit, and after it completes the persisted snapshot carries A's tombstoned identity

#### Scenario: Live author is not scrubbed by the re-check
- **GIVEN** author A is not tombstoned
- **WHEN** S shares A's post
- **THEN** the persisted snapshot carries A's live `username` / `display_name` unchanged
