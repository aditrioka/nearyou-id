## ADDED Requirements

### Requirement: V9 migration lands

The Flyway migration pipeline SHALL now carry an actual migration `V9__reports_moderation.sql`. Running `flywayMigrate` against a database at V8 MUST advance it to V9 and record a successful row in `flyway_schema_history`.

#### Scenario: V9 records on migrate
- **WHEN** running `flywayMigrate` against a database at V8
- **THEN** `flyway_schema_history` contains a row with `version = '9'` and `success = TRUE`

#### Scenario: V9 idempotent on second run
- **WHEN** `flywayMigrate` is run a second time against a database already at V9
- **THEN** the run is a no-op (no new history rows; no errors)

#### Scenario: Cold migrate V1→V9 succeeds
- **WHEN** `flywayMigrate` runs against an empty Postgres with V1–V9 migrations all present
- **THEN** all 9 versions apply successfully in order AND `flyway_schema_history` contains rows for every version from `'1'` through `'9'`

### Requirement: V9 establishes fifth distinct dependency shape — schema-only single-side CASCADE + deferred-FK columns

V9 SHALL depend on V3 (`users`) at the schema level — `reports.reporter_id` FK references `users(id) ON DELETE CASCADE`. Unlike V5 / V6 / V7 which had joint V3+V4 dependencies, V9 references ONLY V3 (the `target_id UUID` column in `reports` and `moderation_queue` is polymorphic across `posts` / `post_replies` / `users` / `chat_messages` and MUST NOT have a foreign key constraint — the polymorphism is enforced at the application layer).

V9 additionally introduces the FIRST deferred-FK pattern in the pipeline: two columns (`reports.reviewed_by` and `moderation_queue.resolved_by`) are declared as plain `UUID` without a constraint, with `COMMENT ON COLUMN` recording the deferred FK target. This establishes the convention for any future column that must reference a downstream-introduced table: ship the column, document the deferred target in a database comment, add the constraint via `ALTER TABLE ... ADD CONSTRAINT ... NOT VALID` + `VALIDATE CONSTRAINT` in the downstream migration.

The V9 migration file MUST document in its header comment:
- (a) single-side schema dependency on V3 `users` via `reports.reporter_id` CASCADE,
- (b) polymorphic `target_id` column intentionally without FK — application-layer resolution per `target_type`,
- (c) deferred-FK pattern for `reviewed_by` and `resolved_by` (targeting the future `admin_users` table) with a reference to the Phase 3.5 admin-users migration convention,
- (d) the V9-era consumers: the `ReportService` POST endpoint, the transactional auto-hide path (UPDATE on `posts` / `post_replies` + INSERT into `moderation_queue`), and the `RawFromPostsRule` exception-list entry for `ReportService` target-existence readers.

The five dependency shapes in the pipeline after V9 are:
- V5: first joint schema dependency (`user_blocks` references both V3 `users` and V4 `posts`; both-side CASCADE).
- V6: first schema-plus-runtime dependency (schema on V3, runtime on V5 via mutual-block read + follow-cascade DELETE; both-side CASCADE).
- V7: first schema-only joint dependency with explicit no-runtime-coupling (schema on V3+V4; both-side CASCADE).
- V8: schema-only joint dependency on V3+V4 with asymmetric delete rules — first new `RESTRICT`-side FK on `users(id)` since V4.
- **V9**: schema-only single-side CASCADE dependency on V3 `users` + polymorphic `target_id` without FK + first deferred-FK columns (targeting the not-yet-shipped `admin_users` table). V9 has NO runtime coupling to any earlier migration — the `ReportService` and the transactional auto-hide + queue-insert paths are introduced in the same deploy as V9 and depend only on V9 being present.

#### Scenario: V9 header documents single-side schema dependency
- **WHEN** reading the top of `V9__reports_moderation.sql`
- **THEN** the file contains a comment identifying V3 (`users`) as the only schema dependency AND a comment noting that `target_id` is intentionally polymorphic without a FK AND a comment noting no runtime coupling to any earlier migration

#### Scenario: V9 header documents deferred-FK pattern
- **WHEN** reading the top of `V9__reports_moderation.sql`
- **THEN** the file contains a comment explaining that `reports.reviewed_by` and `moderation_queue.resolved_by` are plain UUID columns whose FK to `admin_users(id) ON DELETE SET NULL` is deferred to the Phase 3.5 admin-users migration

#### Scenario: V9 header lists V9-era consumers
- **WHEN** reading the top of `V9__reports_moderation.sql`
- **THEN** the file contains a comment listing `ReportService` POST endpoint, the transactional auto-hide path on `posts` / `post_replies`, and the `moderation_queue` `auto_hide_3_reports` writer as V9-era consumers

#### Scenario: V9 header references docs §745–816 as canonical source
- **WHEN** reading the top of `V9__reports_moderation.sql`
- **THEN** the file contains a comment noting that the DDL is aligned with the canonical form at `docs/05-Implementation.md` §745–816

### Requirement: MigrationV9SmokeTest covers schema, constraints, indexes, deferred-FK columns, and CASCADE

A test class `MigrationV9SmokeTest` (tagged `database`) SHALL verify, against a fresh Postgres test database with V1–V9 applied:
1. Both tables (`reports`, `moderation_queue`) exist with the full documented column set (name, type, nullability, default).
2. All 8 CHECK constraints exist and enforce their documented enum sets — `reports.target_type` (4 values), `reports.reason_category` (8 values), `reports.status` (3 values), `moderation_queue.target_type` (4 values), `moderation_queue.trigger` (7 values), `moderation_queue.status` (2 values), `moderation_queue.resolution` (8 values + NULL allowed), `reports` / `moderation_queue` `priority` default.
3. `reports` has the UNIQUE `(reporter_id, target_type, target_id)` constraint enforced (second INSERT with same tuple fails SQLSTATE `23505`).
4. `moderation_queue` has the UNIQUE `(target_type, target_id, trigger)` constraint enforced.
5. All 5 indexes exist with their documented column orders (`reports_status_idx`, `reports_target_idx`, `reports_reporter_idx`, `moderation_queue_status_idx`, `moderation_queue_target_idx`).
6. `reports.reporter_id → users(id)` FK has `delete_rule = 'CASCADE'` (via `information_schema.referential_constraints`).
7. `reports.reviewed_by` and `moderation_queue.resolved_by` have NO foreign-key constraint (both zero rows in `information_schema.referential_constraints`).
8. `reports.reviewed_by` and `moderation_queue.resolved_by` carry a `pg_description` comment mentioning `admin_users` and the phrase `deferred`.
9. `reports.target_id` and `moderation_queue.target_id` have NO foreign-key constraint (polymorphic columns).
10. `reports.reporter_id` CASCADE: creating a user + report then hard-deleting the user (with no other RESTRICT FKs blocking) removes the associated `reports` row in the same transaction.
11. Applying V9 against a database at V8 advances to V9; applying V9 against V9 is a no-op.
12. Cold migrate V1→V9 applies cleanly on an empty Postgres.

#### Scenario: All invariants asserted
- **WHEN** running `./gradlew :backend:ktor:test --tests '*MigrationV9SmokeTest*'`
- **THEN** the class is discovered AND assertions for each numbered invariant are present and pass
