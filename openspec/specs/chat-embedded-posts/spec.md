# chat-embedded-posts Specification

## Purpose
The backend send-path for sharing a post into a 1:1 DM. `POST /api/v1/chat/{conversation_id}/messages` accepts an optional `embedded_post_id`; the server resolves the post **for the sender as viewer** through the shipped visibility rules (the `visible_posts` shadow-ban view + the bidirectional `user_blocks` NOT-IN exclusion + auto-hide / soft-delete filters + the own-content self-arm), and a post the sender cannot see returns the project constant-404 (forbidden indistinguishable from absent). From a resolved post it builds a self-contained, coordinate-free `embedded_post_snapshot` (author handle + display name, `content`, `cityName`, `createdAt`, `editedAt` — never `latitude`/`longitude`/`display_location` or the author UUID, preserving the spatial-fuzzing invariant) and sets `embedded_post_edit_id` to the post's latest `post_edits.id` at share time (the version-at-share-time anchor). A schema CHECK bounds the snapshot so the populated Realtime broadcast payload stays within Supabase Realtime's per-message limit; V37 ships the previously-deferred `embedded_post_edit_id → post_edits(id) ON DELETE SET NULL` FK alongside that CHECK. The snapshot is immutable — it survives a later edit or hard-delete of the source post (the `embedded_post_id` FK is `ON DELETE SET NULL`) — with one exception: when the post's author tombstones their account, the snapshot's `authorUsername` / `authorDisplayName` are scrubbed at rest to the tombstoned identity, keyed on the share-time `embedded_post_author_id` linkage (V38 `embedded-snapshot-author-erasure`); a post-insert author re-check in the send transaction closes the share-vs-tombstone race.
## Requirements
### Requirement: A chat message MAY embed a post the sender can see

The chat send endpoint `POST /api/v1/chat/{conversation_id}/messages` SHALL accept an optional `embedded_post_id` (a post UUID) in addition to the existing `content`. When `embedded_post_id` is present the server SHALL build and persist an embedded-post snapshot (per the snapshot requirement below) and SHALL set the inserted row's `embedded_post_id` and `embedded_post_snapshot` (and `embedded_post_edit_id` per the version-anchor requirement). `content` SHALL be optional when `embedded_post_id` is present (the empty-message CHECK already accepts a snapshot-only row); a request carrying neither a non-empty `content` nor an `embedded_post_id` SHALL be rejected with `400`. The 2000-character content guard SHALL still apply when `content` is present. The send SHALL reuse the existing chat send transaction (INSERT `chat_messages` + UPDATE `conversations.last_message_at`) and the existing AFTER-commit `ChatRealtimeClient.publish(...)`.

#### Scenario: Embed-only message (no content) is accepted
- **GIVEN** an authenticated sender S in a conversation C who can see post P
- **WHEN** S sends `{ "embedded_post_id": "<P>" }` with no `content`
- **THEN** the response is `201`, the inserted `chat_messages` row has `content = NULL`, `embedded_post_id = P`, and a populated `embedded_post_snapshot`

#### Scenario: Content plus embed together is accepted
- **WHEN** S sends `{ "content": "lihat ini", "embedded_post_id": "<P>" }`
- **THEN** the response is `201` and the row carries both the `content` and the embed fields

#### Scenario: Neither content nor embed is rejected
- **WHEN** S sends a body with empty/absent `content` AND no `embedded_post_id`
- **THEN** the response is `400` and no row is persisted

#### Scenario: Over-length content with an embed is still rejected
- **WHEN** S sends `content` of 2001 characters together with a valid `embedded_post_id`
- **THEN** the response is `400` (content guard) and no row is persisted

### Requirement: The embedded post is resolved through the sender's visibility view

When `embedded_post_id` is present the server SHALL resolve the post **for the sender as viewer** using the shipped visibility rules: the bidirectional `user_blocks` NOT-IN join (`BlockExclusionJoinRule`), the `visible_posts` shadow-ban view, and the auto-hide / soft-delete filters. A post the sender cannot see SHALL be rejected with the project's constant-404 idiom — a blocked-author post, a shadow-banned-author post (where the sender is not the author), an auto-hidden post, a soft-deleted post, and a non-existent post-id SHALL all return the SAME `404` response so a forbidden post is indistinguishable from a non-existent one. The snapshot SHALL be built only from a post that passed this visibility resolution.

#### Scenario: Sender shares a post they can see
- **GIVEN** post P is visible to sender S (not blocked, not shadow-banned-from-S, not hidden, not deleted)
- **WHEN** S sends with `embedded_post_id = P`
- **THEN** the response is `201` and a snapshot of P is persisted

#### Scenario: Blocked-author post is rejected as constant-404
- **GIVEN** a `user_blocks` row between S and post P's author (either direction)
- **WHEN** S sends with `embedded_post_id = P`
- **THEN** the response is `404` with the same body a non-existent post id returns; no row is persisted

#### Scenario: Shadow-banned-author post is rejected for a non-author sender
- **GIVEN** post P's author is shadow-banned and S is not the author
- **WHEN** S sends with `embedded_post_id = P`
- **THEN** the response is `404` (P is not in S's `visible_posts`); no row is persisted

#### Scenario: Shadow-banned author may share their OWN post
- **GIVEN** sender S is shadow-banned and S authored post P
- **WHEN** S sends with `embedded_post_id = P`
- **THEN** the resolution succeeds (the own-content arm of the shadow-ban visibility model — S sees their own posts) and a snapshot of P is persisted (the publish-side shadow-ban skip still suppresses the broadcast per the publish requirement)

#### Scenario: Non-existent and soft-deleted are indistinguishable
- **WHEN** S sends with an `embedded_post_id` that is a soft-deleted post OR a random non-existent UUID
- **THEN** both return the identical `404` response; no row is persisted

### Requirement: The embedded snapshot is self-contained and coordinate-free

The persisted `embedded_post_snapshot` JSONB SHALL contain only the display-safe projection mirroring `single-post-read`: the author handle, the author display name, the post `content`, the `cityName` label, the `createdAt` timestamp, and the `editedAt` timestamp (the `MAX(post_edits.edited_at)`-derived edited signal, absent when never edited). The snapshot SHALL NOT contain `latitude`, `longitude`, any raw coordinate, the author UUID, or any other field — preserving the spatial-fuzzing invariant (`display_location` only). The snapshot SHALL be self-contained so the card survives a later hard-delete of the source post: `embedded_post_id` is `ON DELETE SET NULL` and the empty-message CHECK keeps the snapshot-only row valid.

#### Scenario: Snapshot carries display fields and no coordinates
- **WHEN** a snapshot is serialized for post P
- **THEN** the JSONB contains `cityName`, the author handle + display name, `content`, and `createdAt`; AND it contains no key matching `latitude` / `longitude` / `lat` / `lng` and no author UUID

#### Scenario: Snapshot survives source-post hard-delete
- **GIVEN** an embed message persisted with a snapshot of post P
- **WHEN** post P is later hard-deleted
- **THEN** the `chat_messages` row's `embedded_post_id` is set to NULL by the FK, the `embedded_post_snapshot` remains intact, and the row stays valid under the empty-message CHECK

### Requirement: The embed message anchors the post's version at share time

When building an embed the server SHALL set `embedded_post_edit_id` to the source post's most recent `post_edits.id` at send time, or NULL when the post has never been edited. This anchor is the "version-at-share-time" handle the mobile thread compares against the live post to decide whether to show the edited-since-shared banner.

#### Scenario: Unedited post anchors to NULL
- **GIVEN** post P has no `post_edits` rows
- **WHEN** S shares P
- **THEN** the inserted row's `embedded_post_edit_id` is NULL

#### Scenario: Edited post anchors to the latest edit id
- **GIVEN** post P has one or more `post_edits` rows with the most recent being E
- **WHEN** S shares P
- **THEN** the inserted row's `embedded_post_edit_id` equals E

### Requirement: The embedded snapshot is size-bounded at the schema layer

The `embedded_post_snapshot` SHALL be bounded by a schema CHECK (`embedded_post_snapshot IS NULL OR octet_length(embedded_post_snapshot::text) < 4096`, shipped in V37 — the `IS NULL OR` guard matches the `chat-conversations` schema delta so a NULL snapshot trivially passes) so an oversized snapshot can never be persisted or broadcast, keeping the Realtime broadcast payload within Supabase Realtime's per-message size limit. The application SHALL build snapshots that comfortably fit this bound (a 280-char post plus metadata).

#### Scenario: Oversized snapshot is rejected by the CHECK
- **WHEN** an INSERT carries an `embedded_post_snapshot` whose `octet_length(::text)` is ≥ 4096
- **THEN** Postgres rejects the INSERT with a CHECK constraint violation

#### Scenario: NULL snapshot passes the size CHECK
- **WHEN** a plain (non-embed) message is INSERTed with `embedded_post_snapshot IS NULL`
- **THEN** the size CHECK is satisfied (the `IS NULL OR` guard short-circuits) and the row is persisted

### Requirement: The publish-side shadow-ban skip still governs embed messages

An embed message SHALL follow the existing publish-side shadow-ban skip: when the sender is shadow-banned the `chat_messages` row (including its embed fields) still persists, but `ChatRealtimeClient.publish(...)` SHALL NOT be invoked. A non-shadow-banned sender's embed message SHALL broadcast with the populated `embedded_*` fields (per the `chat-realtime-broadcast` payload schema).

#### Scenario: Shadow-banned sender's embed persists but does not broadcast
- **GIVEN** sender S is shadow-banned
- **WHEN** S sends an embed message
- **THEN** the row persists with its embed fields AND no Supabase Realtime broadcast is emitted

#### Scenario: Normal sender's embed broadcasts with populated fields
- **GIVEN** sender S is not shadow-banned
- **WHEN** S sends an embed message for post P
- **THEN** after the transaction commits the broadcast payload carries the populated `embedded_post_id`, `embedded_post_snapshot`, and `embedded_post_edit_id`

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

The embed snapshot is built from a read that precedes the send transaction, so a tombstone committing in between could otherwise leave a freshly inserted snapshot with the pre-deletion identity. Inside the send transaction, after the INSERT, the send path SHALL lock the linked author's `users` row (`FOR SHARE`, selected by id only) and, when that row's `deleted_at IS NOT NULL`, SHALL scrub the new row's snapshot identity (same overwrite as the tombstone requirement) before commit; the scrubbed snapshot SHALL be the one returned in the `201` response and published in the after-commit broadcast. The lock conflicts with the tombstone `UPDATE`'s row lock, which serializes the two transactions: if the send commits first, the worker's scrub (a later statement) sees the committed row; if the tombstone commits first, the send's lock waits for it and the re-check sees `deleted_at`.

#### Scenario: Tombstone commits between resolve and INSERT
- **GIVEN** S's embed of A's post resolved A's live identity, and A's tombstone transaction (tombstone + scrub) commits before S's INSERT
- **WHEN** S's send transaction inserts the row and commits
- **THEN** the persisted snapshot carries A's tombstoned identity AND the `201` response's snapshot carries the tombstoned identity

#### Scenario: Tombstone blocked behind an in-flight send
- **GIVEN** S's send transaction has inserted an embed of A's post and re-checked A (holding a share lock on A's `users` row) and not yet committed
- **WHEN** A's tombstone runs concurrently and S's transaction then commits
- **THEN** the tombstone waits for S's commit, and after it completes the persisted snapshot carries A's tombstoned identity

#### Scenario: Send waits behind an uncommitted tombstone
- **GIVEN** A's tombstone transaction holds A's `users` row (updated, uncommitted) when S's send transaction reaches the post-insert re-check
- **WHEN** the tombstone commits
- **THEN** S's re-check (which waited on the row lock) reads the tombstoned row and scrubs its own snapshot, and the returned snapshot carries the tombstoned identity

#### Scenario: Live author is not scrubbed by the re-check
- **GIVEN** author A is not tombstoned
- **WHEN** S shares A's post
- **THEN** the persisted snapshot carries A's live `username` / `display_name` unchanged

