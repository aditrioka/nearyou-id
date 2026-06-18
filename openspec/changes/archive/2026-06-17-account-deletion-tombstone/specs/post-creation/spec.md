## MODIFIED Requirements

### Requirement: author_id FK is RESTRICT (not cascade)

The `posts.author_id` foreign key to `users(id)` MUST use `ON DELETE RESTRICT` per `docs/05-Implementation.md § Posts Schema`. The endpoint relies on the authenticated principal's `userId` being a live (non-deleted) `users` row.

The earlier assumption that "the tombstone / hard-delete worker is responsible for deleting post rows before the author" is **superseded** by the `account-deletion-tombstone` change: the hard-delete worker **tombstones** the user row (an `UPDATE` setting `deleted_at` + nulling PII), it does NOT row-delete the user. Therefore this `ON DELETE RESTRICT` FK is **never triggered** by the worker, and the user's posts are **retained**, rendered anonymized as "Akun Dihapus" (per `account-hard-delete-worker` + `docs/06` § Account Deletion). The RESTRICT FK still guards against an accidental raw `DELETE FROM users` while posts exist (the scenario below).

#### Scenario: Bare user delete blocked
- **WHEN** an authenticated user has an existing post AND a direct `DELETE FROM users WHERE id = <that user>` is attempted in the integration test
- **THEN** the DELETE fails with SQLSTATE `23503` (foreign-key violation) — the tombstone worker does not exercise this path (it `UPDATE`s, never row-deletes)
