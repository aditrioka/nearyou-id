## 1. Pre-flight reconciliation

- [ ] 1.1 Re-verify the canonical schema sections at [`docs/05-Implementation.md:639-690`](../../../docs/05-Implementation.md) (admin tables) and [`docs/05-Implementation.md:1189-1205`](../../../docs/05-Implementation.md) (admin_actions_log) — diff against the spec column lists; surface any drift via amendment commit
- [ ] 1.2 Confirm the three deferred-FK marker comments still exist at [`V9:71-73`](../../../backend/ktor/src/main/resources/db/migration/V9__reports_moderation.sql:71), [`V9:108-111`](../../../backend/ktor/src/main/resources/db/migration/V9__reports_moderation.sql:108), and [`V15:96-99`](../../../backend/ktor/src/main/resources/db/migration/V15__chat_foundation.sql:96)
- [ ] 1.3 Verify the integration-test Postgres does NOT provision the `admin_app` role (`grep -rin "admin_app\|CREATE ROLE" dev/ docker-compose*.yml backend/ktor/src/main/resources/db/`); if the role IS present locally, surface to the user before proceeding (D4 assumes role absence)
- [ ] 1.4 Confirm all three operational-table columns are 100% NULL today via a one-off staging query (verifies VALIDATE CONSTRAINT will be constant-time): `SELECT count(*) FROM reports WHERE reviewed_by IS NOT NULL UNION ALL SELECT count(*) FROM moderation_queue WHERE resolved_by IS NOT NULL UNION ALL SELECT count(*) FROM chat_messages WHERE redacted_by IS NOT NULL`. All three MUST return 0.
- [ ] 1.5 Verify no Detekt rule targets admin tables today: `grep -rin "admin_users\|admin_sessions\|admin_actions_log\|RawFromAdminTables\|csrf_token_hash" lint/detekt-rules/src/main/kotlin/`. Expected: empty output. If a rule IS present, allowlist V16 in its exemption pattern.
- [ ] 1.6 After authoring V16 (per § 2), verify V16 contains no `auth.*` or `realtime.*` schema references: `grep -i "auth\.\|realtime\." backend/ktor/src/main/resources/db/migration/V16__admin_users.sql` returns empty. Confirms `dev/supabase-parity-init.sql` needs no parity-init updates for this migration.

## 2. Author V16 migration

- [ ] 2.1 Create `backend/ktor/src/main/resources/db/migration/V16__admin_users.sql`
- [ ] 2.2 Add file header comment block (matches V9 / V15 style): purpose, change reference (`admin-schema-bootstrap`), and the two canonical docs references (`docs/05-Implementation.md:639-690` + `docs/05-Implementation.md:1189-1205`)
- [ ] 2.3 `CREATE TABLE admin_users` with the 10-column shape per spec Requirement 1
- [ ] 2.4 `CREATE TABLE admin_webauthn_credentials` with the 8-column shape per spec Requirement 2 (FK CASCADE on admin_users)
- [ ] 2.5 `CREATE TABLE admin_sessions` with the 10-column shape per spec Requirement 3 (`csrf_token_hash TEXT NOT NULL`, `expires_at NOT NULL`, `ip INET NOT NULL`)
- [ ] 2.6 `CREATE INDEX admin_sessions_admin_idx` and `CREATE INDEX admin_sessions_active_idx ... WHERE revoked_at IS NULL`
- [ ] 2.7 `CREATE TABLE admin_webauthn_challenges` with the 7-column shape per spec Requirement 4 (nullable `admin_id`, `ceremony NOT NULL` with CHECK, `expires_at NOT NULL`)
- [ ] 2.8 `CREATE INDEX admin_webauthn_challenges_admin_idx` and `CREATE INDEX admin_webauthn_challenges_cleanup_idx ... WHERE consumed_at IS NULL`
- [ ] 2.9 `CREATE TABLE admin_actions_log` with the 11-column shape per spec Requirement 5 (admin_id NOT NULL, no explicit ON DELETE clause per D3)
- [ ] 2.10 `CREATE INDEX admin_actions_admin_idx`, `CREATE INDEX admin_actions_target_idx`, `CREATE INDEX admin_actions_type_idx`
- [ ] 2.11 Add `COMMENT ON COLUMN` statements documenting `admin_users.password_hash` as Argon2id and `admin_users.totp_secret_encrypted` as AES-256 (key in GCP Secret Manager) — preserves the docs' encoding contract at the schema layer

## 3. Author FK backfills (3 operational-table columns)

- [ ] 3.1 `ALTER TABLE reports ADD CONSTRAINT reports_reviewed_by_fkey FOREIGN KEY (reviewed_by) REFERENCES admin_users(id) ON DELETE SET NULL NOT VALID` followed by `ALTER TABLE reports VALIDATE CONSTRAINT reports_reviewed_by_fkey`
- [ ] 3.2 `ALTER TABLE moderation_queue ADD CONSTRAINT moderation_queue_resolved_by_fkey FOREIGN KEY (resolved_by) REFERENCES admin_users(id) ON DELETE SET NULL NOT VALID` + `VALIDATE CONSTRAINT`
- [ ] 3.3 `ALTER TABLE chat_messages ADD CONSTRAINT chat_messages_redacted_by_fkey FOREIGN KEY (redacted_by) REFERENCES admin_users(id) ON DELETE SET NULL NOT VALID` + `VALIDATE CONSTRAINT`
- [ ] 3.4 Replace the three `COMMENT ON COLUMN` texts on `reports.reviewed_by`, `moderation_queue.resolved_by`, `chat_messages.redacted_by` — remove the "deferred to the Phase 3.5 admin-users migration" substring, replace with a description of the now-shipped FK relationship (must mention `admin_users(id)` AND `SET NULL`, per the three "deferred-comment text removed" scenarios in reports/, moderation-queue/, chat-conversations/ deltas)

## 4. Update pre-existing tests that assert the deferred state

These tests pre-date V16 and assert "no FK" / "deferred" against the three columns; V16 makes those assertions false. Each MUST be updated atomically with the V16 migration in the same commit (otherwise CI is red on first push).

- [ ] 4.1 Update [`backend/ktor/src/test/kotlin/id/nearyou/app/infra/db/MigrationV9SmokeTest.kt:528`](../../../backend/ktor/src/test/kotlin/id/nearyou/app/infra/db/MigrationV9SmokeTest.kt) — the `deferred-FK columns have zero referential constraints` scenario. With V16, both `reports.reviewed_by` AND `moderation_queue.resolved_by` carry FKs. Either rename the test ("deferred-FK columns are validated post-V16") or delete it in favor of the per-column scenarios that ship via the `reports` and `moderation-queue` MODIFIED deltas. Recommended: delete from V9 smoke (now-stale assumption) and rely on V16's new schema-spec tests.
- [ ] 4.2 Update [`MigrationV9SmokeTest.kt:558`](../../../backend/ktor/src/test/kotlin/id/nearyou/app/infra/db/MigrationV9SmokeTest.kt) — `reviewed_by comment mentions admin_users AND deferred`. Replace the `comment.contains("deferred") shouldBe true` assertion with the post-V16 contract: comment must contain `admin_users` AND must NOT contain `deferred` (mirrors the `reports` MODIFIED-delta scenario)
- [ ] 4.3 Update [`MigrationV9SmokeTest.kt:578`](../../../backend/ktor/src/test/kotlin/id/nearyou/app/infra/db/MigrationV9SmokeTest.kt) — `resolved_by comment mentions admin_users AND deferred`. Same shape as 4.2 for `moderation_queue.resolved_by`
- [ ] 4.4 Update [`MigrationV15SmokeTest.kt:286`](../../../backend/ktor/src/test/kotlin/id/nearyou/app/infra/db/MigrationV15SmokeTest.kt) — `deferred-FK columns redacted_by + embedded_post_edit_id have no referential constraint`. Split: `redacted_by` now has a FK (V16); `embedded_post_edit_id` is STILL deferred. Rewrite the test to assert ONLY `embedded_post_edit_id` has no FK; `redacted_by` is covered by the new `chat-conversations` MODIFIED-delta scenarios.
- [ ] 4.5 Update [`MigrationV15SmokeTest.kt:309-326`](../../../backend/ktor/src/test/kotlin/id/nearyou/app/infra/db/MigrationV15SmokeTest.kt) — `deferred-FK columns carry COMMENT ON COLUMN markers`. The `redacted_by` half of this assertion becomes false post-V16; remove the `redacted_by` assertion or split into a `redacted_by` post-V16 assertion (comment mentions `admin_users` AND does NOT mention `deferred`) plus a still-deferred `embedded_post_edit_id` assertion. The `embedded_post_edit_id` block (lines 327+) stays as-is.
- [ ] 4.6 Update [`backend/ktor/src/test/kotlin/id/nearyou/app/chat/ChatMessageNotificationTest.kt:860`](../../../backend/ktor/src/test/kotlin/id/nearyou/app/chat/ChatMessageNotificationTest.kt) — currently `ps.setObject(1, sender)` writes a random user UUID into `redacted_by` with the inline comment `"any UUID — admin_users FK is deferred"`. Post-V16 this will fail with SQLState 23503 (FK violation). Two options: (a) seed a fixture `admin_users` row in the test setup and use its ID, or (b) set `redacted_by = NULL` (but this violates the redaction-atomicity CHECK which requires `redacted_at` AND `redacted_by` together). Option (a) is the correct fix — add a `before` block creating a fixture admin and using its UUID.

## 5. Author schema-level Kotest tests for V16

- [ ] 5.1 Create `backend/ktor/src/test/kotlin/id/nearyou/app/infra/db/MigrationV16SmokeTest.kt` (Kotest spec; package matches V9/V15 smoke precedent, NOT the `admin/` package — schema smoke tests live under `infra/db/`)
- [ ] 5.2 Update [`backend/ktor/src/test/kotlin/.../KotestProjectConfig.kt`](../../../backend/ktor/src/test/kotlin/) docstring (which currently says "V1..V13") to reflect "V1..V16"; behavior is unchanged (the project config auto-discovers migrations via classpath)
- [ ] 5.3 Reuse the project's existing `information_schema.columns` / `pg_indexes` / `pg_constraint` / `pg_description` query helpers from V9/V15 smoke tests
- [ ] 5.4 Scenario coverage for spec Requirement 1 (admin_users): column shape, role CHECK rejection, email UNIQUE rejection, webauthn_enrolled DEFAULT FALSE, is_active DEFAULT TRUE, password_hash NOT NULL rejection, display_name NOT NULL rejection
- [ ] 5.5 Scenario coverage for spec Requirement 2 (admin_webauthn_credentials): column shape, credential_id UNIQUE rejection, CASCADE on admin deletion
- [ ] 5.6 Scenario coverage for spec Requirement 3 (admin_sessions): column shape, csrf_token_hash NOT NULL rejection, session_token_hash UNIQUE rejection, expires_at NOT NULL rejection, ip NOT NULL rejection, partial-active index existence with predicate, indexed column is `expires_at` (not another column), both required indexes
- [ ] 5.7 Scenario coverage for spec Requirement 4 (admin_webauthn_challenges): column shape (incl. admin_id nullability), ceremony CHECK rejection, ceremony NOT NULL rejection, expires_at NOT NULL rejection, cleanup partial index existence with predicate, admin_id NULL accepted for registration pre-binding
- [ ] 5.8 Scenario coverage for spec Requirement 5 (admin_actions_log): column shape, three secondary indexes, admin_id NOT NULL rejection, NO ACTION default rejects orphaning admin DELETE, admin_id FK rejects INSERT with bogus UUID
- [ ] 5.9 Scenario coverage for spec Requirement 6 (no GRANT/REVOKE in migration file): assert `grep -i 'grant\|revoke'` against `V16__admin_users.sql` returns empty
- [ ] 5.10 Scenario coverage for spec Requirement 7 (no seed rows): `SELECT count(*) FROM admin_users` and `SELECT count(*) FROM admin_actions_log` both 0
- [ ] 5.11 Scenario coverage for spec Requirement 8 (no RLS enablement): `pg_class.relrowsecurity` is FALSE for all five admin tables; ALSO verify `pg_policies` returns zero rows for the five admin tables (no policy DDL attached, even if not enabled)
- [ ] 5.12 Scenario coverage for the three MODIFIED-delta `*_fkey` scenarios (reports.reviewed_by, moderation_queue.resolved_by, chat_messages.redacted_by) — each: FK exists with `confdeltype = 'n'` (ON DELETE SET NULL) AND `convalidated = true` AND the column's `pg_description` no longer contains "deferred"
- [ ] 5.13 Scenario coverage for the SET NULL behavior on `reports.reviewed_by`: insert a fixture admin row with no audit-log references (so the schema-level FK doesn't block hard-delete), insert a fixture reports row referencing that admin, hard-DELETE the admin row, assert the reports row's `reviewed_by` is now NULL (matches the reports MODIFIED-delta "Admin row hard-DELETE SETs reports.reviewed_by to NULL" scenario)
- [ ] 5.14 Scenario coverage for the chat-conversations MODIFIED-delta additions: (a) `embedded_post_edit_id FK remains deferred post-V16` — assert `pg_constraint` query for chat_messages returns no FK row for that column (still deferred); (b) `redacted_by FK rejects INSERT with bogus admin_id` — assert INSERT with random UUID for `redacted_by` (plus `redacted_at` set to satisfy the atomicity CHECK) fails with SQLState 23503

## 6. Local verification

- [ ] 6.1 Run `openspec validate admin-schema-bootstrap --strict` — must pass
- [ ] 6.2 Run `./gradlew :backend:ktor:test --tests "*MigrationV16SmokeTest*"` against the local Postgres compose stack — all scenarios green
- [ ] 6.3 Run `./gradlew :backend:ktor:test --tests "*MigrationV9SmokeTest*" --tests "*MigrationV15SmokeTest*" --tests "*ChatMessageNotificationTest*"` — verify the updated pre-existing tests still pass with V16 in the migration set
- [ ] 6.4 Run the full pre-push verification: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` — full lane green (per `CLAUDE.md` § Delivery workflow rule "Pre-push verification")

## 7. Pre-archive staging smoke

- [ ] 7.1 Trigger manual staging deploy on the branch via `gh workflow run deploy-staging.yml --ref admin-schema-bootstrap`
- [ ] 7.2 Poll the deploy run status (`gh run watch` against the triggered run) until green
- [ ] 7.3 Connect to staging Supabase via psql (using the `flyway_migrator` connection string from GCP Secret Manager) and verify all five admin tables exist: `\dt admin_*` — expect 5 rows
- [ ] 7.4 Verify all three FK backfills landed validated: `SELECT conname, convalidated FROM pg_constraint WHERE conname IN ('reports_reviewed_by_fkey', 'moderation_queue_resolved_by_fkey', 'chat_messages_redacted_by_fkey')` — expect 3 rows with `convalidated = true`
- [ ] 7.5 Verify partial indexes exist with correct predicates: `SELECT indexname, indexdef FROM pg_indexes WHERE indexname IN ('admin_sessions_active_idx', 'admin_webauthn_challenges_cleanup_idx')` — both predicates present
- [ ] 7.6 Verify NO seed rows: `SELECT count(*) FROM admin_users; SELECT count(*) FROM admin_actions_log` — both return 0
- [ ] 7.7 **Apply staging-side `admin_app` REVOKE statements via Supabase Console BEFORE the squash-merge to `main`.** Execute against the staging Supabase project: `REVOKE UPDATE, DELETE ON admin_actions_log FROM admin_app`. Per design.md D4, the role-level REVOKE is operational (not Flyway-managed); applying it pre-merge means the immutability invariant is enforced from the moment any Admin #2 code lands. Record completion in the deployment runbook ([`docs/07-Operations.md`](../../../docs/07-Operations.md) § Data Access Pattern) — same SQL applied to production at production-bootstrap time.

## 8. Documentation sync

- [ ] 8.1 No `docs/` edits required (canonical sections describe the eventual end-state, which this change observes). Verify by re-reading [`docs/05-Implementation.md:636-703`](../../../docs/05-Implementation.md) and [`docs/05-Implementation.md:1184-1208`](../../../docs/05-Implementation.md) against the shipped V16 — surface any drift via amendment commit
- [ ] 8.2 No README update required (new modules count unchanged; root README module list is auto-generated from `settings.gradle.kts`, which V16 does not modify)
- [ ] 8.3 Update [`docs/09-Versions.md`](../../../docs/09-Versions.md) ONLY IF the migration introduces a new library pin (it should NOT — V16 is pure SQL with no library impact); confirm no entry needed
- [ ] 8.4 Update FOLLOW_UPS.md: the `suspension-unban-worker-audit-log-after-phase-3.5` entry's "When Phase 3.5 lands `admin_users` + `admin_actions_log` schema, file an OpenSpec change..." action item becomes immediately actionable post-archive — add a short cross-reference note ("Schema landed via `admin-schema-bootstrap`; ready to file `system-actor-and-worker-audit-rows`") to that entry but DO NOT delete it (the follow-up change still needs to ship)

## 9. PR + archive

- [ ] 9.1 At first feat commit: retitle PR via `gh pr edit <pr> --title 'feat(admin-schema): admin-schema-bootstrap'` and update body to reflect implementation-in-progress shape (per `openspec/project.md` § PR title and body MUST stay current at every phase boundary)
- [ ] 9.2 Pre-archive: rerun `openspec validate admin-schema-bootstrap --strict` after any spec amendments from review iteration
- [ ] 9.3 Run `openspec archive admin-schema-bootstrap` — moves change under `openspec/changes/archive/`, syncs `openspec/specs/admin-schema/spec.md` (new capability) AND `openspec/specs/reports/spec.md` + `openspec/specs/moderation-queue/spec.md` + `openspec/specs/chat-conversations/spec.md` (MODIFIED deltas merged in)
- [ ] 9.4 Verify the archive moved cleanly and the modified specs reflect the new shape (no "deferred" references remain on `reviewed_by` / `resolved_by` / `redacted_by`)
- [ ] 9.5 Update PR body to merge-ready shape (drop in-progress framing; list final test counts + capability deltas + the convention note `one-PR-per-change, single squash-merge produces one commit on main`)
- [ ] 9.6 Wait for CI green on the archive commit; squash-merge once all checks pass

## 10. Post-merge handoff

- [ ] 10.1 Post-merge staging smoke is automatic (staging auto-deploys on `main` push). Confirm via `gh run watch` against the `deploy-staging.yml` run triggered by the squash-merge commit; spot-check the same SQL queries from step 7.3-7.6 against `main`-deployed staging
- [ ] 10.2 In a follow-up session, open the `system-actor-and-worker-audit-rows` change to ship the sentinel `system` admin user + audit-row writes in `SuspensionUnbanWorker` per the unblocked FOLLOW_UPS entry
- [ ] 10.3 Once production is provisioned: apply the same `REVOKE UPDATE, DELETE ON admin_actions_log FROM admin_app` statement against production Supabase via Console; record in the operations runbook
