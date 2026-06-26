## ADDED Requirements

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

The `embedded_post_snapshot` SHALL be bounded by a schema CHECK (`octet_length(embedded_post_snapshot::text) < 4096`, shipped in V37) so an oversized snapshot can never be persisted or broadcast, keeping the Realtime broadcast payload within Supabase Realtime's per-message size limit. The application SHALL build snapshots that comfortably fit this bound (a 280-char post plus metadata).

#### Scenario: Oversized snapshot is rejected by the CHECK
- **WHEN** an INSERT carries an `embedded_post_snapshot` whose `octet_length(::text)` is ≥ 4096
- **THEN** Postgres rejects the INSERT with a CHECK constraint violation

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
