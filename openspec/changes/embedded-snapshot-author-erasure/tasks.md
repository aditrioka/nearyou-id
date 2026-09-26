## 1. Schema (V38)

- [ ] 1.1 Add `backend/ktor/src/main/resources/db/migration/V38__chat_embedded_post_author_erasure.sql` (re-verify V38 is still the next free version against latest `origin/main` + open PRs before committing — parallel-session collision rule): `ALTER TABLE chat_messages ADD COLUMN IF NOT EXISTS embedded_post_author_id UUID REFERENCES users(id) ON DELETE SET NULL`; `COMMENT ON COLUMN` describing the erasure-only purpose; backfill `embedded_post_author_id = posts.author_id` via `embedded_post_id` (guarded `embedded_post_author_id IS NULL`); retro-scrub snapshots whose linked author has `deleted_at IS NOT NULL` (`snapshot || jsonb_build_object('authorUsername', u.username, 'authorDisplayName', u.display_name)`); `CREATE INDEX IF NOT EXISTS chat_messages_embedded_post_author_idx … WHERE embedded_post_author_id IS NOT NULL` (NOW()-free). Every statement idempotent so the smoke test can re-execute the file bytes.

## 2. Share-time linkage + race guard

- [ ] 2.1 `ResolvedEmbeddedPost` gains `authorId: UUID`; `JdbcEmbeddedPostResolver` projects `p.author_id` in both UNION arms + the outer SELECT; amend both KDocs (author UUID is projected for the erasure-linkage column only, never into the snapshot).
- [ ] 2.2 `EmbeddedPostData` gains `authorId`; `ChatService.sendMessage` passes `resolved.authorId`; `ChatRepository.insertChatMessage` writes `embedded_post_author_id` (NULL for plain messages); RETURNING column list unchanged.
- [ ] 2.3 `ChatRepository` send transaction: after the INSERT of an embed row, lock the author row (`SELECT username, display_name, deleted_at FROM users WHERE id = ? FOR SHARE` — by id only, `deleted_at` checked in Kotlin) and, when tombstoned, overwrite the row's identity keys (`UPDATE … RETURNING embedded_post_snapshot`), replacing the returned `ChatMessageRow.embeddedPostSnapshot` so the 201 response + after-commit broadcast carry the scrubbed identity. Annotate `@AllowMissingBlockJoin` with the erasure-check reason; comment the lock-serialization argument (design D3).

## 3. Tombstone scrub leg

- [ ] 3.1 `AccountHardDeleteWorker`: tombstone `UPDATE users … RETURNING username, display_name`; zero rows → existing moot path unchanged (no scrub); otherwise run `UPDATE chat_messages SET embedded_post_snapshot = embedded_post_snapshot || jsonb_build_object('authorUsername', ?, 'authorDisplayName', ?) WHERE embedded_post_author_id = ? AND embedded_post_snapshot IS NOT NULL` in the same transaction, before the `deletion_log` insert. Update the class KDoc step list.

## 4. Tests

- [ ] 4.1 `MigrationV38SmokeTest` (new, `@Tags("database")`, per-test cleanup — it seeds posts): column is nullable `uuid`, FK → `users` with `confdeltype = 'n'` + `convalidated`, `chat_messages_embedded_post_author_idx` exists with a `NOW()`-free `IS NOT NULL` predicate (`chat-conversations` FK scenario + `chat-embedded-posts` "Column, FK, and partial index exist").
- [ ] 4.2 `MigrationV38SmokeTest`: seed a pre-V38-shaped embed row (`embedded_post_author_id` NULL), re-execute the V38 file bytes → linkage backfilled ("V38 backfills linkage").
- [ ] 4.3 `MigrationV38SmokeTest`: seed an already-tombstoned author with an un-scrubbed snapshot + a live-author canary row, re-execute V38 → tombstoned snapshot scrubbed, canary byte-identical ("V38 retro-scrubs").
- [ ] 4.4 `ChatEmbeddedPostSendTest`: embed send records `embedded_post_author_id = author`; snapshot JSON / 201 body / broadcast payload carry no author UUID; snapshot author fields equal the live identity; a plain message leaves the column NULL ("Embed send records the post author", "Plain message leaves the linkage NULL", "Live author is not scrubbed by the re-check").
- [ ] 4.5 `ChatEmbeddedPostSendTest`: worker (`execute()`) tombstones author A with two embed rows → both scrubbed to the tombstoned `username` / `Akun Dihapus`, other keys byte-identical, key set unchanged, rows retained, `deletion_log` + `executed_at` committed ("Snapshot identity is scrubbed…", "Tombstone scrubs … in the same transaction").
- [ ] 4.6 `ChatEmbeddedPostSendTest`: hard-delete the source post, then tombstone → still scrubbed ("Scrub survives a source-post hard-delete").
- [ ] 4.7 `ChatEmbeddedPostSendTest`: admin-redacted embed row (seed an `admin_users` row for the FK) → scrubbed ("Admin-redacted embed rows are scrubbed too").
- [ ] 4.8 `ChatEmbeddedPostSendTest`: A sent an embed of B's post + a separate embed of C's post; tombstone A (no posts of their own shared) → both snapshots + A's sent row unchanged ("Other authors' snapshots are untouched", "A user with no shared posts …").
- [ ] 4.9 `ChatEmbeddedPostSendTest`: after tombstone, recipient `GET /chat/{id}/messages` snapshot shows the tombstoned identity and A's original handle/display name appear nowhere in the body ("Recipient's message list shows the anonymized card").
- [ ] 4.10 `ChatEmbeddedPostSendTest`: `apple_s2s_account_delete` row via `executeImmediate` → scrubbed ("Apple S2S immediate deletion scrubs snapshots").
- [ ] 4.11 `ChatEmbeddedPostSendTest`: fault-inject a failure after the scrub (a test-scoped `deletion_log` BEFORE INSERT trigger raising only for the seeded user id, dropped in `finally`) → snapshot still carries the original identity, request stays due ("A failed tombstone leaves snapshots untouched").
- [ ] 4.12 `ChatEmbeddedPostSendTest`: resolver wrapper tombstones the author (via `executeImmediate`) after resolving and before the INSERT → persisted AND 201-response snapshot carry the tombstoned identity ("Tombstone commits between resolve and INSERT").
- [ ] 4.13 `ChatEmbeddedPostSendTest`: pause the REAL `ChatRepository.sendMessage` transaction after its INSERT + re-check (via `afterInsertHookInTx` + a latch); run the worker for A concurrently, wait until its backend is lock-waiting (`pg_stat_activity.wait_event_type = 'Lock'`, out-of-pool poll), release → after the worker completes the row is scrubbed ("Tombstone blocked behind an in-flight send").

## 5. Docs

- [ ] 5.1 `docs/05-Implementation.md` § Chat Message Schema: add the `embedded_post_author_id` column + partial index and a sentence on its erasure-only purpose.
- [ ] 5.2 `docs/06-Security-Privacy.md` § Account Deletion: list embedded-post snapshots under Anonymize/Tombstone (author identity scrubbed at rest on hard-delete; content retained).
- [ ] 5.3 `docs/05` § deletion worker summary + `docs/04` § Post-restore reconciliation script: add the snapshot author re-scrub (a restored pre-deletion dump would otherwise resurrect the identity in snapshots). `docs/02` § Embedded Post Behavior already specifies the "Akun Dihapus" author label for a tombstoned author — this change fulfils it (no edit).

## 6. Verification + delivery

- [ ] 6.1 Full pre-push gate on fresh DB containers: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` (+ the `database`-tagged specs touched here run green; CI-equivalent `!network` lane per docs/13).
- [ ] 6.2 Manual runtime verification (verify-loop §A): boot the local backend against the V38 DB, share a post via the real send route, hit the worker, and show the stored snapshot before/after (psql evidence in the PR body).
- [ ] 6.3 PR body current (`Closes #425`); at archive, amend the `chat-embedded-posts` Purpose sentence "The snapshot is immutable" to note the author-erasure exception, and confirm no `TBD - created by archiving` Purpose was introduced.
