## Context

`chat-embedded-posts` persists `chat_messages.embedded_post_snapshot` — a coordinate-free JSONB with the exact key set `{authorUsername, authorDisplayName, content, cityName, createdAt, editedAt}` — built from the sender's visibility-resolved view of the post (`JdbcEmbeddedPostResolver`) before the send transaction opens (`ChatService.sendMessage`). The snapshot deliberately carries no author UUID, and `embedded_post_id` is `ON DELETE SET NULL` so the card survives a source-post hard-delete (design D1 of that change).

`account-hard-delete-worker` (`AccountHardDeleteWorker.processOne`) tombstones a user in one transaction per due `deletion_requests` row: `UPDATE users SET deleted_at, display_name = 'Akun Dihapus', username = 'deleted_user_' || left(id::text, 8), …` (PII erased, row retained), explicit cascade-DELETEs, `deletion_log` insert, `executed_at` stamp. Authored content is retained and anonymizes *through the join* to the tombstoned `users` row — which works for every live surface but not for the snapshot, whose identity is a frozen copy. The same `processOne` path serves the daily scheduler and the Apple S2S `account-delete` immediate call (`executeImmediate`).

The gap was shipped as an explicit deferred requirement (`chat-embedded-posts` § "Embedded snapshots are NOT re-anonymized on author account-tombstone (deferred)", follow-up #425) so this change MODIFIES it.

## Goals / Non-Goals

**Goals:**
- After a user is tombstoned, no stored `embedded_post_snapshot` of a post they authored carries their pre-deletion handle or display name — including rows shared before this change shipped, admin-redacted rows, and a share racing the tombstone.
- The scrub is atomic with the tombstone (same transaction; inherits the worker's all-or-nothing, idempotent, per-row-isolated semantics).
- The erasure stays correct after the planned soft-deleted-post purge (`docs/06` § Retention) nulls `embedded_post_id`.
- Zero wire-contract change; zero mobile/admin code change.

**Non-Goals:**
- Scrubbing snapshot `content` / `cityName` / timestamps. Authored content is RETAINED anonymized per `docs/06` § Account Deletion (posts themselves stay visible as "Akun Dihapus"); the snapshot follows the same rule — only identity is erased.
- Rewriting already-delivered Realtime broadcasts / FCM payloads (ephemeral, already on the recipient's device — same posture as every other tombstone surface).
- #347 (account-deletion tombstone concurrency tests + PII review) — deliberately sequenced after this change; not folded in.
- Implementing the soft-deleted-post purge itself.

## Decisions

### D1 — Scrub-at-rest in the tombstone transaction (not render-time substitution)

The worker overwrites the two identity keys in place: `embedded_post_snapshot || jsonb_build_object('authorUsername', <tombstoned username>, 'authorDisplayName', <tombstoned display_name>)`. The values come from `RETURNING username, display_name` on the existing tombstone `UPDATE users` — one source of truth for the placeholder, so the scrubbed card is byte-identical to how `single-post-read` renders the tombstoned author (`Akun Dihapus` / `@deleted_user_<id8>`).

*Alternative — render-time substitution* (message list / broadcast / admin views check the author's `deleted_at` and substitute): rejected. The real identity would stay in the table, in backups, in the admin chat-redaction view, and in any future reader that forgets the substitution; UU PDP erasure means the data is gone, not hidden. It also needs the same author linkage (D2) plus logic in every reader instead of one statement in one writer.

*Alternative — a hard-coded placeholder in the scrub SQL*: rejected; it would fork the tombstone formula (`'deleted_user_' || left(id::text, 8)`) into a second place that can drift.

### D2 — Dedicated `embedded_post_author_id` linkage column (not a join through `posts`)

`V38` adds `chat_messages.embedded_post_author_id UUID NULL REFERENCES users(id) ON DELETE SET NULL` + `CREATE INDEX chat_messages_embedded_post_author_idx ON chat_messages (embedded_post_author_id) WHERE embedded_post_author_id IS NOT NULL` (`NOW()`-free partial index; embeds are a small fraction of chat rows). The send path writes it from the resolver, which now projects `p.author_id` (into `ResolvedEmbeddedPost.authorId` → `EmbeddedPostData.authorId`); it is never serialized into the snapshot, the 201 response, or the broadcast.

*Alternative — link via `embedded_post_id → posts.author_id`* (no schema change): rejected. `embedded_post_id` is `ON DELETE SET NULL`; once the soft-deleted-post purge (`docs/06` § Retention: "Soft-deleted post (author) — 30 days then hard delete") ships, a post the author soft-deleted and that was purged before they tombstone their account leaves a snapshot that can no longer be linked to them — a silent, permanent erasure miss. It would also put a raw `FROM posts` read into the worker (`RawFromPostsRule` + `BlockExclusionJoinRule` annotations for a non-visibility read).

*Alternative — add `authorId` inside the snapshot JSON*: rejected; the snapshot's key set is spec'd EXACTLY and ships to clients — the author UUID would go on the wire for no product reason.

`ON DELETE SET NULL` (not RESTRICT/CASCADE): `users` rows are tombstoned, never row-deleted, so the action only matters for an accidental raw row-delete, where preserving the chat row matches every other `chat_messages` FK posture.

### D3 — Close the share-vs-tombstone race with the FK lock + a post-insert re-check

The snapshot is built from a resolver read that happens *before* the send transaction. Interleaving: resolver reads live author → worker tombstones + scrubs + commits → send INSERTs the stale snapshot → nothing ever scrubs it. The worker cannot see an uncommitted INSERT, so only the send side can close it.

Mechanism: the new FK's INSERT-time check takes `FOR KEY SHARE` on the author's `users` row; the tombstone `UPDATE` changes `username` (a `UNIQUE` column), which takes `FOR UPDATE` — the two conflict, so the transactions serialize on that row. After the INSERT, still inside the send transaction, `ChatRepository` runs one re-check: `UPDATE chat_messages cm SET embedded_post_snapshot = cm.embedded_post_snapshot || jsonb_build_object('authorUsername', u.username, 'authorDisplayName', u.display_name) FROM users u WHERE cm.id = ? AND u.id = cm.embedded_post_author_id AND u.deleted_at IS NOT NULL RETURNING cm.embedded_post_snapshot`.
- Send locks first → the tombstone waits for the send to commit → the worker's scrub statement (a fresh READ COMMITTED snapshot) sees the committed row and scrubs it.
- Tombstone locks first → the send's FK check waits for the worker to commit → the re-check statement (a fresh snapshot) sees `deleted_at` and scrubs; the returned snapshot replaces the row's so the 201 response + the after-commit broadcast carry the anonymized identity.
- Non-overlapping → whichever runs second handles it.

The re-check runs only for embed sends (one PK-keyed statement). It reads raw `users` for the tombstone state, so the function carries `@AllowMissingBlockJoin` (an erasure check on the message's own linked author, not a visibility read).

*Alternative — accept the race* (millisecond window, daily worker): rejected for an erasure-critical path — a lost race leaves real identity permanently and silently, and closing it is one statement.

*Alternative — resolve the snapshot inside the send transaction with `SELECT … FOR KEY SHARE`*: equivalent correctness but moves JSON building into the repository and restructures `ChatService`; the post-insert re-check keeps the shipped service shape.

### D4 — V38 backfills linkage and retro-scrubs already-tombstoned authors

`V38` (1) `UPDATE chat_messages cm SET embedded_post_author_id = p.author_id FROM posts p WHERE p.id = cm.embedded_post_id`, then (2) scrubs snapshots whose linked author already has `deleted_at IS NOT NULL` (users tombstoned between the `chat-embedded-posts` ship and this change). Rows whose source post was already hard-deleted before V38 (`embedded_post_id IS NULL`) cannot be backfilled — no production post hard-delete path exists before V38 (verified: the only `DELETE FROM posts` sites are test fixtures), so that set is empty by construction.

### Standards conformance (docs/11)

- **Backend layering / data layer**: raw JDBC in the existing repository/worker classes (`ChatRepository`, `AccountHardDeleteWorker`, `JdbcEmbeddedPostResolver`); no new repository, service, or pattern. The worker leg is one more statement in the existing per-row transaction, next to the cascade DELETEs.
- **Schema**: Flyway migration with a `NOW()`-free partial index (partial-index invariant); FK `ON DELETE SET NULL` like the other `chat_messages` history-preserving FKs.
- **Lint invariants**: the worker scrub is `UPDATE chat_messages … WHERE embedded_post_author_id = ?` (no `FROM`/`JOIN` on a protected table); the send-side re-check reads raw `users` and is annotated `@AllowMissingBlockJoin` with its reason; no `username` write (the `@allow-username-write` allowlist is untouched — the snapshot copy is JSONB, not `users.username`); `ContentWriteRequiresModerationRule` is unaffected (no `INSERT … content` change beyond an extra non-content column).
- No Pattern-Registry deviation → no docs/11 amendment.

### Cross-layer scope (docs/12)

- **Backend**: full — schema, write path, erasure leg, race guard.
- **Mobile**: no change required. `EmbeddedPostCard` renders `snapshot.authorDisplayName` / `"@${snapshot.authorUsername}"` verbatim, so a scrubbed card reads "Akun Dihapus" / "@deleted_user_…" — the same identity the live post card shows for a tombstoned author. Tapping still re-resolves the live post (which surfaces anonymized per V28).
- **Admin**: no change required. The chat-redaction view reads the stored snapshot, which is now scrubbed; admins see the tombstoned identity, consistent with the tombstoned `users` row.
- No layer is deferred.

## Risks / Trade-offs

- [Scrub grows a snapshot past the 4096-byte CHECK and rolls back the whole tombstone, leaving the deletion permanently due] → impossible by construction: the replacement values are ≤ 21 bytes (`deleted_user_` + 8 hex) and 12 bytes (`Akun Dihapus`) against a snapshot whose content is ≤ 280 chars; worst-case growth is ~30 bytes on a snapshot far below the cap. A failure would still be per-row isolated and logged (`account_hard_delete_row_failed`), not silent.
- [Worker transaction now touches `chat_messages` rows → longer row-lock hold] → bounded by the author's shared-post count; indexed lookup; runs in the existing per-row transaction.
- [The snapshot is no longer strictly immutable] → the mutation is limited to two identity keys, only on author erasure; the `chat-embedded-posts` Purpose line is amended at archive to say so.
- [Pre-V38 unlinkable rows] → empty set by construction (D4).

## Migration Plan

1. Deploy V38 (additive: nullable column, partial index, backfill + retro-scrub). Flyway runs at boot before serving; the backfill is a single set-based UPDATE over embed rows only.
2. The new code writes `embedded_post_author_id` on every embed send and scrubs on tombstone.
3. Rollback: the column is nullable and ignored by the previous build's explicit-column SQL, so the previous build runs against the V38 schema. Scrubbed snapshots are intentionally not restorable (erasure).

## Open Questions

None.
