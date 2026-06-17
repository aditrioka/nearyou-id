# account-hard-delete-worker Specification

## Purpose
The account-hard-delete-worker capability is the internal Cloud-Scheduler worker (`/internal/account-hard-delete-worker`, internal-endpoint OIDC + system-actor attribution) that executes due account deletions per `docs/06`'s **tombstone** model. Per due `deletion_requests` row, claimed with `FOR UPDATE SKIP LOCKED` and processed in its own transaction, it: tombstones the user (`deleted_at` + PII erasure — placeholder/sentinel for `NOT NULL` columns), cascade-DELETEs ephemeral/relational data (tokens, both-direction follows + blocks, FCM, addressed notifications), RETAINS authored content anonymized (posts/replies/likes/edits/chat/reports render "Akun Dihapus"), writes an append-only `deletion_log` row, and stamps `executed_at` — atomic, idempotent, and concurrency-safe. Tombstoned authors' posts surface anonymized across the feed surfaces; profile/search/metrics keep excluding them.
## Requirements
### Requirement: deletion_log schema (append-only)

Migration `V24__deletion_requests.sql` (alongside `deletion_requests`) SHALL create an append-only `deletion_log` table recording every executed hard-delete, so the Pre-Launch backup-restore reconciliation test ("no tombstoned user resurrected") has a queryable source of truth:

```sql
CREATE TABLE deletion_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    executed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    source VARCHAR(32) NOT NULL
);
```

`user_id` MUST NOT carry an FK to `users` (the row must survive a future true row-purge of the tombstoned user). The DB role grants SHALL make this table append-only at the application role (no UPDATE/DELETE) consistent with the audit-log posture. The canonical long-term home is R2 (7-year retention, `docs/06` § Retention) — that export is a deferred follow-up; the DB table is the in-scope cut.

#### Scenario: deletion_log exists with no FK on user_id
- **WHEN** the migration set is applied and `deletion_log` is inspected
- **THEN** the table exists with `id, user_id, executed_at, source` AND `user_id` has no foreign key to `users`

#### Scenario: deletion_log is append-only at the application role
- **WHEN** the application DB role attempts `UPDATE` or `DELETE` on `deletion_log`
- **THEN** the operation is denied (only `INSERT`/`SELECT` are granted), mirroring the `admin_actions_log` immutable-audit posture

### Requirement: Hard-delete worker scans and executes only due, un-cancelled, un-executed requests

An internal worker endpoint `/internal/account-hard-delete-worker` (triggered by Cloud Scheduler) SHALL select `deletion_requests` rows where `scheduled_hard_delete_at <= NOW() AND executed_at IS NULL AND cancelled_at IS NULL` (the `deletion_requests_scheduled_idx` predicate; the boundary is inclusive — a row whose `scheduled_hard_delete_at` exactly equals `NOW()` is due) and process each. A request that is cancelled, not yet due, or already executed MUST NOT be processed. The worker MUST be source-agnostic over the schedule (it processes any due row regardless of `source`). To make concurrent worker invocations safe, each row MUST be claimed with `SELECT … FOR UPDATE SKIP LOCKED` (or an equivalent atomic claim) so two overlapping runs never both process the same row (precedent: `SuspensionUnbanWorker` row-locking).

#### Scenario: Due un-cancelled request is executed
- **WHEN** the worker runs and a request has `scheduled_hard_delete_at` in the past, `cancelled_at IS NULL`, `executed_at IS NULL`
- **THEN** that request is processed (tombstone + cascade + anonymize + log) and its `executed_at` is set

#### Scenario: Cancelled request is skipped
- **WHEN** the worker runs and a due request has `cancelled_at IS NOT NULL`
- **THEN** the request is NOT processed AND the user row is untouched (no `deleted_at`, PII intact)

#### Scenario: Not-yet-due request is skipped
- **WHEN** the worker runs and a request has `scheduled_hard_delete_at` in the future
- **THEN** the request is NOT processed

#### Scenario: Exactly-at-deadline request is due
- **WHEN** the worker runs and a request has `scheduled_hard_delete_at` exactly equal to the worker's evaluation `NOW()`
- **THEN** the request IS processed (the `<= NOW()` boundary is inclusive)

### Requirement: Hard-delete tombstones the user row

For each due request, the worker SHALL, in one transaction, tombstone the user: set `users.deleted_at = NOW()` and **erase the PII set**. Because the shipped V2 schema makes some PII columns `NOT NULL` (and `date_of_birth` carries a `>= 18y` CHECK), erasure uses NULL for the nullable columns and a placeholder/sentinel for the `NOT NULL` ones (the canonical tombstone pattern — you cannot violate `NOT NULL`):
- NULL the nullable PII columns: `bio`, `google_id_hash`, `apple_id_hash`, `device_fingerprint_hash`, `email`.
- Set `display_name = 'Akun Dihapus'` (the `NOT NULL` real-name column → the canonical docs/06 placeholder; replacing the real name erases the PII AND makes every server-rendered surface show "Akun Dihapus" with no client logic).
- Set `date_of_birth` to a CHECK-satisfying sentinel `DATE '1900-01-01'` (the `NOT NULL` + `>= 18y` CHECK forbids NULL; the sentinel erases the real DOB).
- Reset the residual Apple-identity flag `apple_relay_email = FALSE` (part of the Apple cluster; `docs/06:325`'s list omits it but the shipped schema carries it).
- Rename `username = 'deleted_user_' || left(id::text, 8)` (collision-free, widened as needed to preserve the `username` UNIQUE constraint).

The `username` (and `display_name`) UPDATE site MUST carry the `// @allow-username-write: deletion` allowlist annotation (the username-write lint invariant) and MUST stay within the 60-char `username` ceiling. The user ROW is NOT row-deleted — it persists as a tombstone so retained content (posts/replies/chat) can anonymize against it.

#### Scenario: Tombstone erases the PII set
- **WHEN** the worker hard-deletes a user
- **THEN** afterward that `users` row has `deleted_at` set, `bio / google_id_hash / apple_id_hash / device_fingerprint_hash / email` all NULL, `display_name = 'Akun Dihapus'`, `date_of_birth = DATE '1900-01-01'`, `apple_relay_email = FALSE`, and `username` matching `^deleted_user_[0-9a-f]{8,}$` (the `deleted_user_` prefix + a unique id-derived suffix)

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

#### Scenario: A hard-deleted user's likes are retained
- **WHEN** a user who liked posts is hard-deleted
- **THEN** their `post_likes` rows still exist (retained, not cascade-deleted — the like row anchors to the tombstoned user)

#### Scenario: Submitted reports are retained
- **WHEN** the worker hard-deletes a user who had submitted reports
- **THEN** those `reports` rows still exist with `reporter_id` pointing at the now-tombstoned user (not cascade-deleted)

### Requirement: The tombstone model supersedes the earlier delete-then-row-delete assumption

This worker is the "tombstone / hard-delete worker (separate future change)" that several shipped specs anticipated, but it MUST implement the `docs/06` **tombstone** model, NOT the delete-then-row-delete model those specs assumed. The user `users` row MUST NOT be row-deleted (it is `UPDATE`d to a tombstone), so the content FKs — `posts.author_id` / `post_replies.author_id` (`ON DELETE RESTRICT`) and `post_likes.user_id` / `reports.reporter_id` (`ON DELETE CASCADE`) — are **never triggered** by this worker, and that content MUST be RETAINED anonymized. This SUPERSEDES the now-stale prose in `post-creation` ("worker … deleting post rows before the author"), `post-replies` ("worker … removing replies before the author"), and `reports`/`docs/05:508` ("on user hard-delete, submitted reports cascade"); the corresponding spec deltas in this change reconcile each, and `docs/05:508` is flagged for a doc-amend follow-up. The RESTRICT/CASCADE FKs remain in force as guards against an accidental raw row-delete (their existing FK-behavior scenarios are unchanged).

#### Scenario: No content FK is triggered by a tombstone
- **WHEN** the worker hard-deletes a user who has posts, replies, likes, and submitted reports
- **THEN** none of the `RESTRICT`/`CASCADE` content FKs fire (the user row was `UPDATE`d, not row-deleted) AND all that content is retained

### Requirement: Tombstoned authors' content surfaces in feeds rendered as "Akun Dihapus"

A tombstoned (hard-deleted) author's non-hidden, non-soft-deleted **posts** SHALL remain visible in the post-listing read surfaces — the Nearby, Following, and Global timelines and post detail — with the author identity rendered as the server-set placeholder (`display_name = 'Akun Dihapus'`, `username = deleted_user_…`; the identity join reads raw `users`, since `visible_posts` already excludes shadow-banned authors). The shadow-ban, post-soft-delete, and bidirectional-block predicates on those surfaces MUST continue to apply (`visible_posts` V24 is the view-level mechanism; the timeline + single-post identity join switches from `visible_users` to `users`). Profile read, search, and active-user metrics are deliberately NOT relaxed — a tombstoned user stays a `404` profile, non-discoverable in search, and uncounted as active. **Reply lists and reply/like counters are out of this surfacing's scope (design D10):** a tombstoned replier's reply is RETAINED in the DB but filtered from the public reply list + `reply_count` by the unchanged `visible_users` contributor-filter, exactly as a shadow-banned replier is — account deletion changes neither.

#### Scenario: A tombstoned author's post appears in each feed surface, anonymized
- **WHEN** an author with a visible post is hard-deleted, and a viewer loads the **Nearby**, **Following**, and **Global** timelines that would include that post
- **THEN** on each surface the post is present with the author identity anonymized (`display_name = 'Akun Dihapus'`, `username = deleted_user_…`) — rendered uniformly server-side (post detail is covered by `single-post-read`)

#### Scenario: A tombstoned sender's chat messages still render for the peer
- **WHEN** the sender of 1:1 messages is hard-deleted
- **THEN** the peer's message list still returns those messages (the sender is not row-deleted; the message-list query has no author-`deleted_at` exclusion), rendered with the sender anonymized

#### Scenario: A tombstoned conversation partner shows as Akun Dihapus
- **WHEN** a viewer's conversation partner is hard-deleted
- **THEN** the conversation list still shows the conversation with the partner display name as "Akun Dihapus" (the partner-list `LEFT JOIN visible_users` resolves to NULL → existing `COALESCE(…, 'Akun Dihapus')` — `visible_users` is intentionally unchanged, so this works with no new code)

#### Scenario: A shadow-banned-then-deleted author stays hidden (shadow-ban dominates, view AND raw feeds)
- **WHEN** an author who is shadow-banned (`is_shadow_banned = TRUE`) is also hard-deleted, on both the view-backed (Following) and raw-`posts` (Nearby/Global) feeds
- **THEN** their posts remain EXCLUDED on every surface (the shadow-ban predicate dominates over the tombstone-surfacing rule; deletion does not un-hide shadow-banned content)

#### Scenario: Relaxing the author-deletion exclusion does not weaken viewer block suppression
- **WHEN** viewer V has blocked a (non-deleted) author A, and a separate tombstoned author T also has posts in the same feed
- **THEN** A's posts stay suppressed for V (the bidirectional `user_blocks` NOT-IN join is intact) AND T's posts surface anonymized — the V24 author-deletion relaxation did not drop the block predicate

#### Scenario: The shadow-ban self-visibility arm is unaffected by the relaxation
- **WHEN** a shadow-banned (NOT deleted) author loads their own Nearby/Global feed after V24
- **THEN** they still see their own posts (the `shadow-ban-feed-self-visibility` UNION self-arm is byte-identical post-relaxation)

#### Scenario: A tombstoned replier is excluded from reply_count, like a shadow-banned one
- **WHEN** a post has 3 replies, one authored by a user who is then hard-deleted
- **THEN** the reply ROW is retained (not deleted) but the public `reply_count` is `2` — the `visible_users` contributor-filter excludes the tombstoned replier exactly as it excludes a shadow-banned replier (design D10); account deletion does not change the counter

#### Scenario: A tombstoned author's profile is still 404
- **WHEN** a viewer calls `GET /api/v1/users/{deleted_id}` for a hard-deleted user
- **THEN** the response is `404 user_not_found` (`visible_users` is unchanged; docs/06 permits the 404 form)

#### Scenario: A tombstoned user is not discoverable in search
- **WHEN** a search query that would have matched the user's former handle/identity runs after the user is hard-deleted
- **THEN** the tombstoned user is NOT returned (search reads `visible_users`, which is unchanged and still excludes `deleted_at IS NOT NULL`)

#### Scenario: A tombstoned user is not counted as active
- **WHEN** an active-user metric (e.g. the operational dashboard DAU/MAU, which filters `deleted_at IS NULL`) is computed after the user is hard-deleted
- **THEN** the tombstoned user is NOT counted (they are gone for "active user" purposes)

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

#### Scenario: Concurrent workers do not double-process a row
- **WHEN** two worker invocations run concurrently and both would select the same due row
- **THEN** exactly one claims and processes it (via `FOR UPDATE SKIP LOCKED`), exactly one `deletion_log` row is written, and the other invocation skips it (it observes the row locked then `executed_at` set) — no double-tombstone, no double cascade

#### Scenario: Cancel racing with execution resolves deterministically
- **WHEN** a `DELETE` cancel and a worker execution target the same row concurrently
- **THEN** exactly one wins: either the cancel commits first (the worker's `cancelled_at IS NULL` claim no longer matches → the row is skipped, no tombstone) or the worker commits first (the cancel then sees `executed_at IS NOT NULL` → rejected) — never a half-tombstoned-then-cancelled state

### Requirement: Hard-delete worker is internal-auth gated and audited via the system actor

The worker endpoint SHALL be mounted under the internal-endpoint-auth (OIDC) subtree — never reachable by a user JWT — mirroring the `suspension-unban-worker` / privacy-flip-worker precedent. A request without a valid internal OIDC token MUST be rejected. Because `deletion_log` carries no actor column, the "non-human principal" attribution lives in the **OIDC service-account identity on the request** (surfaced as the internal-endpoint OTel `service.account.id` trace attribute), not in a `deletion_log` row — the log records *what* (`user_id`, `source`) and the trace records *who* (the system service account). The worker MUST NOT write a human-admin attribution.

#### Scenario: Unauthenticated internal call is rejected
- **WHEN** `/internal/account-hard-delete-worker` is called without a valid internal OIDC token
- **THEN** the response is `401`/`403` and no deletion runs

#### Scenario: Executed deletions are attributed to the system service account, not a human
- **WHEN** the worker hard-deletes a user
- **THEN** the request is authenticated as the internal system service account (the OIDC principal / OTel `service.account.id`), and no human-admin actor is recorded for the deletion

