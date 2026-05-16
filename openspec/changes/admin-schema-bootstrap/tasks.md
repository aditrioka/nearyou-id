## 1. Pre-flight reconciliation

- [ ] 1.1 Re-verify the canonical schema sections at [`docs/05-Implementation.md:639-690`](../../../docs/05-Implementation.md) (admin tables) and [`docs/05-Implementation.md:1189-1205`](../../../docs/05-Implementation.md) (admin_actions_log) — diff against the spec column lists; surface any drift via amendment commit
- [ ] 1.2 Confirm the three deferred-FK marker comments still exist at [`V9:71-73`](../../../backend/ktor/src/main/resources/db/migration/V9__reports_moderation.sql:71), [`V9:108-111`](../../../backend/ktor/src/main/resources/db/migration/V9__reports_moderation.sql:108), and [`V15:96-99`](../../../backend/ktor/src/main/resources/db/migration/V15__chat_foundation.sql:96)
- [ ] 1.3 Verify the integration-test Postgres does NOT provision the `admin_app` role (`grep -rin "admin_app\|CREATE ROLE" dev/ docker-compose*.yml backend/ktor/src/main/resources/db/`); if the role IS present locally, surface to the user before proceeding (D4 assumes role absence)
- [ ] 1.4 Confirm all three operational-table columns are 100% NULL today via a one-off staging query (verifies VALIDATE CONSTRAINT will be constant-time): `SELECT count(*) FROM reports WHERE reviewed_by IS NOT NULL UNION ALL SELECT count(*) FROM moderation_queue WHERE resolved_by IS NOT NULL UNION ALL SELECT count(*) FROM chat_messages WHERE redacted_by IS NOT NULL`. All three MUST return 0.

## 2. Author V16 migration

- [ ] 2.1 Create `backend/ktor/src/main/resources/db/migration/V16__admin_users.sql`
- [ ] 2.2 Add file header comment block (matches V9 / V15 style): purpose, change reference (`admin-schema-bootstrap`), and the two canonical docs references (`docs/05-Implementation.md:639-690` + `docs/05-Implementation.md:1189-1205`)
- [ ] 2.3 `CREATE TABLE admin_users` with the 10-column shape per spec Requirement 1
- [ ] 2.4 `CREATE TABLE admin_webauthn_credentials` with the 8-column shape per spec Requirement 2 (FK CASCADE on admin_users)
- [ ] 2.5 `CREATE TABLE admin_sessions` with the 10-column shape per spec Requirement 3 (`csrf_token_hash TEXT NOT NULL`)
- [ ] 2.6 `CREATE INDEX admin_sessions_admin_idx` and `CREATE INDEX admin_sessions_active_idx ... WHERE revoked_at IS NULL`
- [ ] 2.7 `CREATE TABLE admin_webauthn_challenges` with the 7-column shape per spec Requirement 4 (nullable `admin_id`, ceremony CHECK)
- [ ] 2.8 `CREATE INDEX admin_webauthn_challenges_admin_idx` and `CREATE INDEX admin_webauthn_challenges_cleanup_idx ... WHERE consumed_at IS NULL`
- [ ] 2.9 `CREATE TABLE admin_actions_log` with the 11-column shape per spec Requirement 5 (admin_id NOT NULL, no explicit ON DELETE clause per D3)
- [ ] 2.10 `CREATE INDEX admin_actions_admin_idx`, `CREATE INDEX admin_actions_target_idx`, `CREATE INDEX admin_actions_type_idx`
- [ ] 2.11 Add `COMMENT ON COLUMN` statements documenting `admin_users.password_hash` as Argon2id and `admin_users.totp_secret_encrypted` as AES-256 (key in GCP Secret Manager) — preserves the docs' encoding contract at the schema layer

## 3. Author FK backfills (3 operational-table columns)

- [ ] 3.1 `ALTER TABLE reports ADD CONSTRAINT reports_reviewed_by_fkey FOREIGN KEY (reviewed_by) REFERENCES admin_users(id) ON DELETE SET NULL NOT VALID` followed by `ALTER TABLE reports VALIDATE CONSTRAINT reports_reviewed_by_fkey`
- [ ] 3.2 `ALTER TABLE moderation_queue ADD CONSTRAINT moderation_queue_resolved_by_fkey ... NOT VALID` + `VALIDATE CONSTRAINT`
- [ ] 3.3 `ALTER TABLE chat_messages ADD CONSTRAINT chat_messages_redacted_by_fkey ... NOT VALID` + `VALIDATE CONSTRAINT`
- [ ] 3.4 Replace the three `COMMENT ON COLUMN` texts on `reports.reviewed_by`, `moderation_queue.resolved_by`, `chat_messages.redacted_by` — remove the "deferred to the Phase 3.5 admin-users migration" substring, replace with a description of the now-shipped FK relationship (per spec Requirement 6 scenario "Deferred-comment text is replaced")

## 4. Author schema-level Kotest tests

- [ ] 4.1 Create `backend/ktor/src/test/kotlin/id/nearyou/app/admin/AdminSchemaSpec.kt` (Kotest spec; package matches the existing `admin` package)
- [ ] 4.2 Test fixture: helper extension(s) for querying `information_schema.columns`, `information_schema.table_constraints`, and `pg_indexes` against the post-migration DB; reuse the project's existing JDBC-in-test conventions
- [ ] 4.3 Scenario coverage for spec Requirement 1 (admin_users): column shape, role CHECK rejection, email UNIQUE rejection, webauthn_enrolled DEFAULT FALSE, is_active DEFAULT TRUE
- [ ] 4.4 Scenario coverage for spec Requirement 2 (admin_webauthn_credentials): column shape, credential_id UNIQUE rejection, CASCADE on admin deletion
- [ ] 4.5 Scenario coverage for spec Requirement 3 (admin_sessions): column shape, csrf_token_hash NOT NULL rejection, session_token_hash UNIQUE rejection, partial-active index existence with predicate, both required indexes
- [ ] 4.6 Scenario coverage for spec Requirement 4 (admin_webauthn_challenges): column shape (incl. admin_id nullability), ceremony CHECK rejection, cleanup partial index existence with predicate, admin_id NULL accepted for registration pre-binding
- [ ] 4.7 Scenario coverage for spec Requirement 5 (admin_actions_log): column shape, three secondary indexes, admin_id NOT NULL rejection, NO ACTION default rejects orphaning admin DELETE (scenario "NO ACTION default rejects orphaning admin deletion")
- [ ] 4.8 Scenario coverage for spec Requirement 6 (FK backfills): three FK constraints exist + validated; SET NULL behavior documented (note the application-layer flag-flip vs hard-delete distinction); deferred-comment text removed
- [ ] 4.9 Scenario coverage for spec Requirement 7 (no GRANT/REVOKE in migration file): assert `grep -i 'grant\|revoke'` against `V16__admin_users.sql` returns empty
- [ ] 4.10 Scenario coverage for spec Requirement 8 (no seed rows): `SELECT count(*) FROM admin_users` and `SELECT count(*) FROM admin_actions_log` both 0
- [ ] 4.11 Scenario coverage for spec Requirement 9 (no RLS enablement): `pg_class.relrowsecurity` is FALSE for all five admin tables

## 5. Local verification

- [ ] 5.1 Run `openspec validate admin-schema-bootstrap --strict` — must pass
- [ ] 5.2 Run `./gradlew :backend:ktor:test --tests "*AdminSchemaSpec*"` against the local Postgres compose stack — all scenarios green
- [ ] 5.3 Run the full pre-push verification: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` — full lane green (per `CLAUDE.md` § Delivery workflow rule "Pre-push verification")
- [ ] 5.4 Run `./gradlew :backend:ktor:test` and confirm existing tests touching `reports.reviewed_by` / `moderation_queue.resolved_by` / `chat_messages.redacted_by` still pass (rows are all NULL; backfill is non-disruptive)
- [ ] 5.5 Run `./gradlew migrate-supabase-parity-related-task` (or the CI equivalent locally) to confirm V16 applies cleanly against the Supabase-parity-init schema

## 6. Pre-archive staging smoke

- [ ] 6.1 Trigger manual staging deploy on the branch via `gh workflow run deploy-staging.yml --ref admin-schema-bootstrap`
- [ ] 6.2 Poll the deploy run status (`gh run watch` against the triggered run) until green
- [ ] 6.3 Connect to staging Supabase via psql (using the `flyway_migrator` connection string from GCP Secret Manager) and verify all five admin tables exist: `\dt admin_*` — expect 5 rows
- [ ] 6.4 Verify all three FK backfills landed validated: `SELECT conname, convalidated FROM pg_constraint WHERE conname IN ('reports_reviewed_by_fkey', 'moderation_queue_resolved_by_fkey', 'chat_messages_redacted_by_fkey')` — expect 3 rows with `convalidated = true`
- [ ] 6.5 Verify partial indexes exist with correct predicates: `SELECT indexname, indexdef FROM pg_indexes WHERE indexname IN ('admin_sessions_active_idx', 'admin_webauthn_challenges_cleanup_idx')` — both predicates present
- [ ] 6.6 Verify NO seed rows: `SELECT count(*) FROM admin_users; SELECT count(*) FROM admin_actions_log` — both return 0

## 7. Documentation sync

- [ ] 7.1 No `docs/` edits required (canonical sections describe the eventual end-state, which this change observes). Verify by re-reading [`docs/05-Implementation.md:636-703`](../../../docs/05-Implementation.md) and [`docs/05-Implementation.md:1184-1208`](../../../docs/05-Implementation.md) against the shipped V16 — surface any drift via amendment commit
- [ ] 7.2 No README update required (new modules count unchanged; root README module list is auto-generated from `settings.gradle.kts`, which V16 does not modify)
- [ ] 7.3 Update [`docs/09-Versions.md`](../../../docs/09-Versions.md) ONLY IF the migration introduces a new library pin (it should NOT — V16 is pure SQL with no library impact); confirm no entry needed
- [ ] 7.4 Update FOLLOW_UPS.md: the `suspension-unban-worker-audit-log-after-phase-3.5` entry's "When Phase 3.5 lands `admin_users` + `admin_actions_log` schema, file an OpenSpec change..." action item becomes immediately actionable post-archive — add a short cross-reference note ("Schema landed via `admin-schema-bootstrap`; ready to file `system-actor-and-worker-audit-rows`") to that entry but DO NOT delete it (the follow-up change still needs to ship)

## 8. PR + archive

- [ ] 8.1 At first feat commit: retitle PR via `gh pr edit <pr> --title 'feat(admin-schema): admin-schema-bootstrap'` and update body to reflect implementation-in-progress shape (per `openspec/project.md` § PR title and body MUST stay current at every phase boundary)
- [ ] 8.2 Pre-archive: rerun `openspec validate admin-schema-bootstrap --strict` after any spec amendments from review iteration
- [ ] 8.3 Run `openspec archive admin-schema-bootstrap` — moves change under `openspec/changes/archive/`, syncs `openspec/specs/admin-schema/spec.md`
- [ ] 8.4 Verify the archive moved cleanly: `ls openspec/changes/archive/2026-*-admin-schema-bootstrap/` returns 4 expected artifacts (proposal.md, design.md, tasks.md, specs/admin-schema/spec.md)
- [ ] 8.5 Update PR body to merge-ready shape (drop in-progress framing; list final test counts + capability deltas + the convention note `one-PR-per-change, single squash-merge produces one commit on main`)
- [ ] 8.6 Wait for CI green on the archive commit; squash-merge once all checks pass

## 9. Post-merge handoff

- [ ] 9.1 Post-merge staging smoke is automatic (staging auto-deploys on `main` push). Confirm via `gh run watch` against the `deploy-staging.yml` run triggered by the squash-merge commit; spot-check the same SQL queries from step 6.3-6.6 against `main`-deployed staging
- [ ] 9.2 In a follow-up session, open the `system-actor-and-worker-audit-rows` change to ship the sentinel `system` admin user + audit-row writes in `SuspensionUnbanWorker` per the unblocked FOLLOW_UPS entry
- [ ] 9.3 Mention to operations: the `admin_app` REVOKE statements (`REVOKE UPDATE, DELETE ON admin_actions_log FROM admin_app`) should be applied via Supabase Console for staging now (so the immutability invariant is enforced as the first admin code lands in Admin #2) and recorded in the deployment runbook
