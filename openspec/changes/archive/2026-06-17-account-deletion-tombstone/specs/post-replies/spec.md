## ADDED Requirements

### Requirement: Replies are retained (not removed) under the account-deletion tombstone model

The earlier `post_replies` schema note — "the tombstone + hard-delete worker (separate future change) is responsible for removing replies before the author" — is **superseded** by `account-deletion-tombstone` (this is that change). The hard-delete worker **tombstones** the author's `users` row (an `UPDATE` setting `deleted_at` + nulling PII; NOT a row-delete), so the `post_replies.author_id ON DELETE RESTRICT` FK is **never triggered** and the author's replies MUST be **RETAINED**, rendered anonymized as "Akun Dihapus" (per `account-hard-delete-worker` + `docs/06` § Account Deletion). The existing "author_id RESTRICT blocks user delete with live replies" scenario remains valid as a guard against an accidental raw `DELETE FROM users` — a path the tombstone worker does not exercise.

#### Scenario: A tombstoned author's replies are retained, not deleted
- **WHEN** the hard-delete worker tombstones a user who authored replies
- **THEN** those `post_replies` rows still exist (none removed) AND the `author_id RESTRICT` FK was never triggered (the worker `UPDATE`d the user, it did not row-delete them)

#### Scenario: A tombstoned replier's reply is retained but filtered from the public reply list
- **WHEN** a user who authored a reply on another user's post is hard-deleted, and a viewer loads that post's reply list
- **THEN** the reply ROW is retained (not deleted — the `RESTRICT` FK never fired) but it is filtered from the public reply list by the unchanged `visible_users` contributor-filter, exactly as a shadow-banned replier's reply is (design D10; surfacing tombstoned repliers anonymized is a deferred follow-up)
