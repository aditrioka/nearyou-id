## Why

`chat-embedded-posts` (PR #423) freezes the post author's `authorUsername` + `authorDisplayName` into an immutable `chat_messages.embedded_post_snapshot` at share time. When that author later tombstones their account (account-deletion, UU PDP right-to-erasure), every live surface re-renders them as "Akun Dihapus" — but the frozen snapshot keeps the real handle + display name **at rest, forever**. That is an erasure-completeness gap, shipped as an explicit deferred requirement in `chat-embedded-posts` and tracked by follow-up [#425](https://github.com/aditrioka/nearyou-id/issues/425). This change closes it so the tombstone erases the author's identity from every stored copy the platform controls.

## What Changes

- **Scrub-at-rest on tombstone.** The `AccountHardDeleteWorker` tombstone transaction gains one leg: overwrite `authorUsername` / `authorDisplayName` in every `embedded_post_snapshot` of a post authored by the departing user with the tombstoned `users` values (`deleted_user_<id8>` / `Akun Dihapus`) — the exact identity every live surface already renders. Atomic with the tombstone (same transaction, same all-or-nothing + idempotency guarantees); applies to the daily worker AND the Apple S2S immediate path (both run the same per-row path). Admin-redacted rows are scrubbed too (their snapshot is retained at rest).
- **Purge-proof author linkage.** New column `chat_messages.embedded_post_author_id UUID NULL REFERENCES users(id) ON DELETE SET NULL` + a `NOW()`-free partial index, populated at share time from the resolved post. The existing `embedded_post_id` link is `ON DELETE SET NULL` and would be lost once the planned soft-deleted-post purge (`docs/06` § Retention: "Soft-deleted post — 30 days then hard delete") ships; the dedicated column keeps the snapshot erasable regardless of post lifecycle. The author UUID stays OUT of the snapshot JSON and off the wire.
- **Migration `V38`**: add the column + index, backfill it from `posts.author_id` for existing embed rows, and retro-scrub snapshots whose author was tombstoned before this change shipped (erasure is not only forward-looking).
- **Share-vs-tombstone race closed.** The send transaction re-checks the author after the INSERT under an explicit `FOR SHARE` lock on the author row (which serializes against the tombstone's row lock), scrubbing a snapshot built from a pre-tombstone read and returning the scrubbed snapshot to the 201 response + Realtime broadcast.
- **Spec MODIFY**: `chat-embedded-posts` "Embedded snapshots are NOT re-anonymized on author account-tombstone (deferred)" flips to the shipped behavior (the requirement was written so this change can MODIFY it); `account-hard-delete-worker` gains the snapshot-scrub leg.
- **Docs amend**: `docs/05` § Chat Message Schema (new column + index), `docs/06` § Account Deletion (snapshots listed under Anonymize/Tombstone).

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `chat-embedded-posts`: the deferred "snapshots are NOT re-anonymized on author tombstone" requirement becomes "snapshots ARE re-anonymized on author tombstone"; a new requirement records the share-time author linkage (`embedded_post_author_id`, V38) + its backfill/retro-scrub + the share-vs-tombstone race guard. The snapshot key set and its no-author-UUID rule are unchanged.
- `account-hard-delete-worker`: the tombstone transaction additionally scrubs the departing author's identity from every linked `embedded_post_snapshot`.
- `chat-conversations`: the "Conversation schema" requirement's `chat_messages` column + index enumeration gains `embedded_post_author_id` and its partial index (V38), with an FK-shape scenario — keeping the schema requirement verbatim-aligned with `docs/05`.

## Impact

- **Backend** (`:backend:ktor`, `:infra:supabase`): `AccountHardDeleteWorker` (tombstone `RETURNING` + one scrub `UPDATE`); `JdbcEmbeddedPostResolver` + `ResolvedEmbeddedPost` (project `author_id` — used for the column only, never serialized); `ChatService` / `EmbeddedPostData` / `ChatRepository.insertChatMessage` (write the column + post-insert re-check); new Flyway `V38__chat_embedded_post_author_erasure.sql`.
- **Wire contract**: unchanged — no new response/broadcast field; the snapshot key set is unchanged (values change only for tombstoned authors).
- **Mobile**: no code change — `EmbeddedPostCard` renders the snapshot's `authorDisplayName` / `@authorUsername` verbatim, so a scrubbed card shows "Akun Dihapus" / "@deleted_user_…", matching the live post card for a tombstoned author.
- **Admin**: no code change — admin chat-redaction reads the stored snapshot and sees the scrubbed identity after tombstone (consistent with the tombstoned `users` row).
- **Out of scope**: #347 (account-deletion-tombstone concurrency tests + PII review) is deliberately sequenced after this change and is NOT folded in.
