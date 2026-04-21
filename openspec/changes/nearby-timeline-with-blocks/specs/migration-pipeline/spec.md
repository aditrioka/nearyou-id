## ADDED Requirements

### Requirement: V5 migration lands

The Flyway migration pipeline SHALL now carry an actual migration `V5__user_blocks.sql`. Running `flywayMigrate` against a database at V4 MUST advance it to V5 and record a successful row in `flyway_schema_history`.

#### Scenario: V5 records on migrate
- **WHEN** running `flywayMigrate` against a database at V4
- **THEN** `flyway_schema_history` contains a row with `version = '5'` and `success = TRUE`

#### Scenario: V5 idempotent on second run
- **WHEN** `flywayMigrate` is run a second time against a database already at V5
- **THEN** the run is a no-op (no new history rows; no errors)

### Requirement: V5 establishes joint V3+V4 dependency precedent

V5 is the first migration that depends jointly on tables from two prior migrations: `users` (V3) for the FK targets and `posts`/`visible_posts` (V4) for the read-path consumers it enables. The `MigrationV5SmokeTest` SHALL verify that the migration runs cleanly only against a database that has both V3 and V4 applied. The migration file's header comment MUST document this joint dependency.

#### Scenario: V5 fails against pre-V3 baseline
- **WHEN** an attempt is made to apply V5 against a database that has only V1+V2 (no V3 users, no V4 posts)
- **THEN** Flyway either refuses (out-of-order) or the SQL fails on the missing `users` table reference

#### Scenario: V5 header comments the joint dependency
- **WHEN** reading the top of `V5__user_blocks.sql`
- **THEN** the file contains a comment referencing both V3 (`users`) and V4 (`posts`) as required preconditions

### Requirement: MigrationV5SmokeTest covers indexes, UNIQUE, CHECK, cascades

A test class `MigrationV5SmokeTest` (tagged `database`) SHALL verify, against a fresh Postgres+PostGIS test database with V1–V5 applied:
1. Both directional indexes exist with the documented column order.
2. The UNIQUE `(blocker_id, blocked_id)` constraint is enforced (duplicate INSERT fails).
3. The `CHECK (blocker_id <> blocked_id)` constraint is enforced (self-block INSERT fails).
4. The FK cascade behavior on user hard-delete works in BOTH directions.

#### Scenario: All four invariants asserted
- **WHEN** running `./gradlew :backend:ktor:test --tests '*MigrationV5SmokeTest*'`
- **THEN** the class is discovered AND assertions for each numbered invariant are present and pass
