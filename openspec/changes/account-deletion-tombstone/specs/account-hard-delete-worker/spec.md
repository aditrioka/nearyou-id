## ADDED Requirements

### Requirement: deletion_log schema (append-only)

Migration `V23__deletion_requests.sql` (alongside `deletion_requests`) SHALL create an append-only `deletion_log` table recording every executed hard-delete, so the Pre-Launch backup-restore reconciliation test ("no tombstoned user resurrected") has a queryable source of truth:

```sql
CREATE TABLE deletion_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    executed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    source VARCHAR(24) NOT NULL
);
```

`user_id` MUST NOT carry an FK to `users` (the row must survive a future true row-purge of the tombstoned user). The DB role grants SHALL make this table append-only at the application role (no UPDATE/DELETE) consistent with the audit-log posture. The canonical long-term home is R2 (7-year retention, `docs/06` § Retention) — that export is a deferred follow-up; the DB table is the in-scope cut.

#### Scenario: deletion_log exists and is append-only
- **WHEN** the migration set is applied and `deletion_log` is inspected
- **THEN** the table exists with `id, user_id, executed_at, source` AND `user_id` has no foreign key to `users`

### Requirement: Hard-delete worker scans and executes only due, un-cancelled, un-executed requests

An internal worker endpoint `/internal/account-hard-delete-worker` (triggered by Cloud Scheduler) SHALL select `deletion_requests` rows where `scheduled_hard_delete_at <= NOW() AND executed_at IS NULL AND cancelled_at IS NULL` (the `deletion_requests_scheduled_idx` predicate) and process each. A request that is cancelled, not yet due, or already executed MUST NOT be processed. The worker MUST be source-agnostic over the schedule (it processes any due row regardless of `source`).

#### Scenario: Due un-cancelled request is executed
- **WHEN** the worker runs and a request has `scheduled_hard_delete_at` in the past, `cancelled_at IS NULL`, `executed_at IS NULL`
- **THEN** that request is processed (tombstone + cascade + anonymize + log) and its `executed_at` is set

#### Scenario: Cancelled request is skipped
- **WHEN** the worker runs and a due request has `cancelled_at IS NOT NULL`
- **THEN** the request is NOT processed AND the user row is untouched (no `deleted_at`, PII intact)

#### Scenario: Not-yet-due request is skipped
- **WHEN** the worker runs and a request has `scheduled_hard_delete_at` in the future
- **THEN** the request is NOT processed

### Requirement: Hard-delete tombstones the user row

For each due request, the worker SHALL, in one transaction, tombstone the user: set `users.deleted_at = NOW()`; NULL the PII columns `display_name`, `bio`, `google_id_hash`, `apple_id_hash`, `device_fingerprint_hash`, `date_of_birth`, `email` (the exact shipped-schema PII set); and rename `username` to a collision-free `deleted_user_`-prefixed handle derived from the user id (e.g. `'deleted_user_' || left(id::text, 8)`, widened as needed to preserve the `username` UNIQUE constraint). The `username` UPDATE site MUST carry the `// @allow-username-write: deletion` allowlist annotation (the username-write lint invariant) and MUST stay within the 60-char schema ceiling. The user ROW is NOT row-deleted — it persists as a tombstone so retained content (posts/replies/chat) can anonymize against it.

#### Scenario: Tombstone nulls exactly the specified PII set
- **WHEN** the worker hard-deletes a user
- **THEN** afterward that `users` row has `deleted_at` set, `display_name / bio / google_id_hash / apple_id_hash / device_fingerprint_hash / date_of_birth / email` all NULL, and `username` matching `^deleted_user_[0-9a-f]{8,}$` (the `deleted_user_` prefix + a unique id-derived suffix)

#### Scenario: A tombstoned user cannot sign back in to the same account
- **WHEN** the (nulled) `google_id_hash` / `apple_id_hash` are searched for at a later sign-in
- **THEN** no account resolves (the identity hashes are gone) — consistent with Account-Recovery-None-by-design; a fresh sign-in creates a brand-new account

### Requirement: Hard-delete cascade-deletes ephemeral and relational data

In the same transaction the worker SHALL explicitly `DELETE` (the FK `ON DELETE CASCADE` does not fire because the `users` row is not row-deleted): the user's session and refresh tokens (all families → `refresh_tokens`), follow edges in BOTH directions (`follows`), `user_blocks` in BOTH directions, FCM tokens (`user_fcm_tokens`), and notifications addressed to the user (`notifications`). Deleting the token rows terminates any live session. Note: `docs/06` also lists "non-post location history" in the cascade set, but location-on-open is request-only / not persisted (`docs/03` § Location Permission + § Retention) — there is no such table in the current schema, so that item is a **no-op today**; if a location-history table is ever added it MUST join this cascade.

#### Scenario: Cascade tables are emptied for the deleted user
- **WHEN** the worker hard-deletes a user who had follows (both directions), blocks (both directions), FCM tokens, addressed notifications, and active sessions
- **THEN** afterward zero `follows`, `user_blocks`, `user_fcm_tokens`, addressed `notifications`, and session/refresh-token rows reference that user

#### Scenario: Blocks are deleted in both directions
- **WHEN** the deleted user had blocked user X and user Y had blocked the deleted user
- **THEN** both `user_blocks` rows are removed (the both-directions decision, design Q2 — accepted ghost-post edge case)

### Requirement: Hard-delete retains authored content, anonymized

The worker SHALL NOT delete the user's authored content rows: `posts` (and their location), `post_replies`, `post_likes`, `post_edits`, `chat_messages`, and `reports` submitted by the user. These rows persist; because the author/sender `users` row is now tombstoned (NULL `display_name`, `deleted_user_` handle), every surface renders the author/sender as "Akun Dihapus". `post_likes` rows are retained so like counts stay accurate. (This resolves the `docs/05:508` vs `docs/06:343` contradiction in favor of `docs/06`: with a tombstone — not a row-delete — `reports.reporter_id ON DELETE CASCADE` never fires, so submitted reports are retained for audit; `docs/05:508` is flagged stale for a doc-amend follow-up.)

#### Scenario: Authored content rows survive the hard-delete
- **WHEN** the worker hard-deletes a user who authored posts, replies, likes, post-edits, chat messages, and submitted reports
- **THEN** those rows still exist (none deleted), now associated with the tombstoned user

#### Scenario: Like counts are unchanged by the author's deletion
- **WHEN** a post had 5 likes, one of them from a user who is then hard-deleted
- **THEN** the post's like count remains 5 (the like row is retained)

#### Scenario: Submitted reports are retained
- **WHEN** the worker hard-deletes a user who had submitted reports
- **THEN** those `reports` rows still exist with `reporter_id` pointing at the now-tombstoned user (not cascade-deleted)

### Requirement: Tombstoned authors' content surfaces in feeds rendered as "Akun Dihapus"

A tombstoned (hard-deleted) author's non-hidden, non-soft-deleted posts SHALL remain visible in every post-listing read surface — Nearby, Following, and Global timelines, post detail, and reply lists — with the author identity rendered from the nulled `display_name` + `deleted_user_` handle (the client maps this to the user-facing "Akun Dihapus" string). The shadow-ban, post-soft-delete, and bidirectional-block predicates on those surfaces MUST continue to apply (see `visible-posts-view` for the view-level mechanism; the Nearby/Global raw-`posts` queries and post-detail/reply-list relax ONLY the author-side `deleted_at` predicate). Profile read, search, and active-user metrics are deliberately NOT relaxed — a tombstoned user stays a `404` profile, non-discoverable in search, and uncounted as active.

#### Scenario: A tombstoned author's post still appears in the Global timeline
- **WHEN** an author with a visible post is hard-deleted, and a viewer loads the Global timeline that would include that post
- **THEN** the post is present in the response with the author identity nulled (no `display_name`, `username` = `deleted_user_…`), so the client renders "Akun Dihapus"

#### Scenario: A shadow-banned-then-deleted author stays hidden
- **WHEN** an author who is shadow-banned (`is_shadow_banned = TRUE`) is also hard-deleted
- **THEN** their posts remain EXCLUDED from feeds (the shadow-ban predicate dominates; deletion does not un-hide shadow-banned content)

#### Scenario: A tombstoned author's profile is still 404
- **WHEN** a viewer calls `GET /api/v1/users/{deleted_id}` for a hard-deleted user
- **THEN** the response is `404 user_not_found` (`visible_users` is unchanged; docs/06 permits the 404 form)

### Requirement: Hard-delete writes a deletion-log row and marks the request executed, in one transaction

For each processed request the worker SHALL, in the SAME transaction as the tombstone/cascade/anonymize, insert a `deletion_log` row (`user_id`, `source`) and set `deletion_requests.executed_at = NOW()`. If the transaction rolls back, neither the tombstone nor the log nor the `executed_at` flip persists (all-or-nothing).

#### Scenario: A successful execution logs and stamps executed_at atomically
- **WHEN** the worker hard-deletes a user
- **THEN** exactly one `deletion_log` row exists for that `user_id` AND the corresponding `deletion_requests.executed_at` is set, both committed together

#### Scenario: A mid-execution failure leaves no partial tombstone
- **WHEN** the per-row transaction fails partway (e.g. a statement errors)
- **THEN** the user is NOT tombstoned, no `deletion_log` row is written, and `executed_at` stays NULL (the row remains due for the next run)

### Requirement: Hard-delete worker is idempotent and re-run safe

Re-running the worker MUST NOT re-process an already-executed request (the `executed_at IS NULL` scan predicate excludes it) and MUST NOT double-tombstone or resurrect any data. A crash mid-batch MUST leave already-committed rows done and not-yet-reached rows still due (per-row transactions isolate failures).

#### Scenario: Re-running the worker is a no-op for executed requests
- **WHEN** the worker runs twice over a request that was executed on the first run
- **THEN** the second run does not touch that user again (no second `deletion_log` row, no error)

#### Scenario: One failing row does not block the rest of the batch
- **WHEN** a batch has one row whose transaction fails and others that succeed
- **THEN** the succeeding rows are executed and logged AND the failing row stays due (`executed_at IS NULL`) for a later run

### Requirement: Hard-delete worker is internal-auth gated and audited via the system actor

The worker endpoint SHALL be mounted under the internal-endpoint-auth (OIDC) subtree — never reachable by a user JWT — mirroring the `suspension-unban-worker` / privacy-flip-worker precedent, and its actions SHALL be attributable to the system actor (so the audit trail records a non-human principal). A request without a valid internal OIDC token MUST be rejected.

#### Scenario: Unauthenticated internal call is rejected
- **WHEN** `/internal/account-hard-delete-worker` is called without a valid internal OIDC token
- **THEN** the response is `401`/`403` and no deletion runs

#### Scenario: Executed deletions are attributed to the system actor
- **WHEN** the worker hard-deletes a user
- **THEN** the audit/log attribution is the system actor (not a human admin or the deleted user)
