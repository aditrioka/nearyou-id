## ADDED Requirements

### Requirement: Hard-delete scrubs the departing author's identity from embedded-post snapshots

In the SAME per-row transaction as the tombstone (after the `users` tombstone `UPDATE`, before the `deletion_log` insert and the `executed_at` stamp), the worker SHALL overwrite `authorUsername` and `authorDisplayName` in the `embedded_post_snapshot` of every `chat_messages` row whose `embedded_post_author_id` is the tombstoned user, using the values the tombstone just wrote (`users.username` = `deleted_user_…`, `users.display_name` = `Akun Dihapus`, read back from the tombstone `UPDATE … RETURNING` — not re-derived). All other snapshot keys and all other `chat_messages` columns SHALL be left unchanged, and no `chat_messages` row SHALL be deleted (chat is retained, per the retained-content requirement). The scrub SHALL NOT run for a mooted row (the per-user idempotency guard: when the tombstone `UPDATE` matches zero rows because the user is already tombstoned, the row is only stamped executed), and it inherits the transaction's all-or-nothing rollback. Because the Apple S2S immediate path (`executeImmediate`) runs the same per-row path, it scrubs identically.

#### Scenario: Tombstone scrubs the author's embedded snapshots in the same transaction
- **WHEN** the worker hard-deletes a user whose posts were shared into chats (two embed rows linked to them)
- **THEN** afterward both rows' snapshots carry `authorUsername` = the tombstoned `users.username` and `authorDisplayName = 'Akun Dihapus'`, both rows still exist, and the `deletion_log` row + `executed_at` stamp are committed with it

#### Scenario: A failed tombstone leaves snapshots untouched
- **WHEN** the per-row transaction fails after the scrub statement ran (e.g. a later statement errors)
- **THEN** the snapshots still carry the original identity (rolled back with the tombstone) and the request stays due

#### Scenario: Apple S2S immediate deletion scrubs snapshots
- **WHEN** an `apple_s2s_account_delete` row is executed via `executeImmediate` for an author whose post was shared into a chat
- **THEN** that embed row's snapshot identity is scrubbed exactly as on the daily worker path

#### Scenario: A user with no shared posts is tombstoned without touching chat rows
- **WHEN** the worker hard-deletes a user no `chat_messages.embedded_post_author_id` references
- **THEN** the tombstone succeeds and no `chat_messages` row changes
