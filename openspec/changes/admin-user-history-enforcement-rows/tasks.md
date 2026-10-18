## 1. Schema (design D3)

- [ ] 1.1 Add `backend/ktor/src/main/resources/db/migration/V41__admin_actions_log_after_user_idx.sql`. It holds a header comment (purpose, #496, the V17 precedent, why a partial index and why the `IS NOT NULL` predicate is planner-implied, no `NOW()`, no `CONCURRENTLY`) and `CREATE INDEX IF NOT EXISTS admin_actions_after_user_idx ON admin_actions_log ((after_state ->> 'user_id')) WHERE (after_state ->> 'user_id') IS NOT NULL`. Re-confirm V41 is still free (`ls backend/ktor/src/main/resources/db/migration | sort -V | tail -1` against latest `origin/main` + open PRs) right before every push.

## 2. Audit writers (design D1, D2)

- [ ] 2.1 `ReportResolutionRepository.resolveQueueItem`: the three author-resolution effects add `user_id` = `authorId.toString()`; `keep`/`hide` stay `user_id`-free. Update the class/function KDoc (D9 after_state now names the author).
- [ ] 2.2 `AppealReviewRepository.reject`: `after_state` adds `user_id` = `locked.userId.toString()` (approve already does). Update the KDoc.
- [ ] 2.3 `AdminAuditLogger`: KDoc only. `logModerationQueueResolved` / `logAppealRejected` document `after_state.user_id`, plus a one-line note on the convention (the key names the affected account and feeds the profile history). No signature change.

## 3. History read (design D3)

- [ ] 3.1 `UserProfileRepository.loadAdminActionHistory`: predicate `(l.target_type = 'user' AND l.target_id = ?) OR (l.after_state ->> 'user_id') = ?`, both bound to `userId.toString()`; expose the SQL as a constant so the EXPLAIN test plans the exact production text. Update the KDoc (class + function).
- [ ] 3.2 `AdminUserModerationRoute.buildHistory` KDoc: the action source now includes `after_state.user_id` rows (no code change).

## 4. Admin UI (design D5)

- [ ] 4.1 `templates/admin/user-profile.peb` history chip: `moderation_queue_resolved` → `err`, `appeal_approved` → `ok`; update the template comment. Consult the admin board frame 6 History card (render + `dev/scripts/mockup-measure.sh nearyou-admin-mockup.html 6`); no layout change.

## 5. Tests (one per spec'd scenario)

- [ ] 5.1 `MigrationV41SmokeTest` (DB):
  - the V41 `flyway_schema_history` row is `success`;
  - `admin_actions_after_user_idx` exists on `admin_actions_log` with the `(after_state ->> 'user_id')` expression and the `IS NOT NULL` predicate (`pg_indexes.indexdef`);
  - the SQL is additive (no DROP/UPDATE/DELETE).
- [ ] 5.2 `ReportResolutionRepositoryTest` (DB):
  - `suspend_author_7d`, `ban_author`, and `shadow_ban_author` each record `after_state.user_id` = the resolved author (post target);
  - a `user`-target `shadow_ban_author` records that user;
  - `hide` (and `keep`) `after_state` has no `user_id` key.
- [ ] 5.3 `AppealReviewTest` (DB): the approve audit row has `target_type = 'appeal'`, `target_id` = the appeal, and `after_state.user_id` = the appellant; the reject audit row has the same shape (new); idempotent re-approve unchanged.
- [ ] 5.4 `AdminUserModerationRouteTest` (DB, profile GET), driven through the REAL writers (`ReportResolutionRepository` / `AppealReviewRepository`):
  - queue `ban_author` → `moderation_queue_resolved` + `ban_author` in the author's profile;
  - appeal approve → `appeal_approved`;
  - appeal reject → `appeal_rejected`;
  - a hand-inserted `deletion_request_expedited` row with `after_state.user_id` appears exactly once;
  - queue `hide` on the user's post → no `moderation_queue_resolved`;
  - user B's queue ban + appeal approval → absent from A's profile.
- [ ] 5.5 Index-served (DB): with `SET enable_seqscan = off`, EXPLAIN the production history SQL (bound to a seeded user) → no `Seq Scan on admin_actions_log`; mirror the `SuspensionUnbanWorkerTest` 9.8 structure.
- [ ] 5.6 Existing exact-shape assertions on `moderation_queue_resolved` / `appeal_rejected` `after_state` (if any) updated for the new key.

## 6. Docs (design D7)

- [ ] 6.1 `docs/05-Implementation.md` § Admin Actions Log Schema: add the shipped V17 `admin_actions_created_idx` + the V41 `admin_actions_after_user_idx`, plus a one-line `after_state.user_id` convention note (writers: queue author resolutions, appeal approve/reject, deletion expedite; consumer: the admin profile history).

## 7. Verification & lifecycle

- [ ] 7.1 Gate: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test --no-daemon` against a fresh throwaway Postgres (docs/13 §5; AxonFlow loopback override only with operator approval).
- [ ] 7.2 verify-loop (local admin panel): seed an author + post + pending queue row and a banned appellant + pending appeal; resolve `ban_author` and approve/reject through the panel; screenshot `/admin/users/{id}` history for both users, compared against board frame 6; evidence in the PR body.
- [ ] 7.3 Pre-archive staging branch deploy + smoke:
  - V41 `flyway_schema_history` row is `success`;
  - `admin_actions_after_user_idx` exists with the expected predicate (read-only Supabase MCP);
  - `/health/ready` returns 200;
  - the unauthenticated `/admin/users/{id}` returns 302.
- [ ] 7.4 PR title/body current at each phase boundary; body carries `Closes #496`.
