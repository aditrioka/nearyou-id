# Migration Pipeline

## Purpose

Defines the Flyway migration scaffold on `:backend:ktor`, the file-naming convention for migrations, the env-namespaced secret-resolution helper used to derive DB connection secret keys, and the smoke-test harness pattern for migrations.

See `docs/04-Architecture.md § Flyway Migration Deployment` for the production deployment design (Cloud Run Jobs `nearyou-migrate-*`, deferred to a future change).
## Requirements
### Requirement: Flyway plugin wired to backend module

The Flyway Gradle plugin SHALL be applied to `:backend:ktor` so that `./gradlew :backend:ktor:flywayMigrate` is a valid task. The plugin's `url`/`user`/`password` config MUST be sourced from environment variables (`DB_URL`, `DB_USER`, `DB_PASSWORD`) — no hardcoded credentials.

#### Scenario: Task discoverable
- **WHEN** running `./gradlew :backend:ktor:tasks --group=flyway`
- **THEN** the output lists at least `flywayMigrate`, `flywayInfo`, `flywayValidate`

#### Scenario: No hardcoded URL
- **WHEN** searching `backend/ktor/build.gradle.kts` for `jdbc:postgresql://`
- **THEN** zero matches are found

### Requirement: Migration directory and placeholder

The migration directory SHALL be `backend/ktor/src/main/resources/db/migration/` (Flyway default). It MUST contain a `V1__init.sql` placeholder whose body is a no-op SQL statement (e.g. `SELECT 1;`) and whose header comment explicitly states it is intentional and that real schema starts at `V2`.

#### Scenario: Directory exists
- **WHEN** listing `backend/ktor/src/main/resources/db/migration/`
- **THEN** the directory exists and contains `V1__init.sql`

#### Scenario: Placeholder content
- **WHEN** reading `V1__init.sql`
- **THEN** the file contains a comment marking it a placeholder and contains no `CREATE`, `ALTER`, or `DROP` statements

### Requirement: secretKey helper for env-namespaced secrets

A function `secretKey(env: String, name: String): String` SHALL exist in `:backend:ktor` returning the namespaced secret name. For `env == "staging"` it MUST return `"staging-$name"`; otherwise it MUST return `name` unchanged.

#### Scenario: Staging prefix applied
- **WHEN** calling `secretKey("staging", "admin-app-db-connection-string")`
- **THEN** the result is `"staging-admin-app-db-connection-string"`

#### Scenario: Production unchanged
- **WHEN** calling `secretKey("production", "admin-app-db-connection-string")`
- **THEN** the result is `"admin-app-db-connection-string"`

#### Scenario: Unit test exists
- **WHEN** running `./gradlew :backend:ktor:test`
- **THEN** at least one test exercises both branches of `secretKey`

### Requirement: KTOR_ENV drives environment selection

Ktor application config SHALL read the current environment from the `KTOR_ENV` environment variable via HOCON substitution (`${?KTOR_ENV}`) in `application.conf`. The application MUST default to `"production"` when `KTOR_ENV` is unset (fail-safe), per the Architecture doc's Config Separation Pattern.

#### Scenario: HOCON references KTOR_ENV
- **WHEN** reading `backend/ktor/src/main/resources/application.conf`
- **THEN** the `ktor.environment` key uses `${?KTOR_ENV}` substitution

### Requirement: First real migration V2 lands

The Flyway migration pipeline SHALL exercise an actual schema migration named `V2__auth_foundation.sql`. After auth-foundation, `flywayMigrate` against a fresh DB MUST result in `flyway_schema_history` containing both V1 (placeholder) and V2 records.

#### Scenario: V2 records on fresh migrate
- **WHEN** running `flywayMigrate` against an empty Postgres
- **THEN** `flyway_schema_history` contains a row with `version = '2'` and `success = TRUE`

#### Scenario: V2 idempotent on second run
- **WHEN** `flywayMigrate` is run a second time against the same database
- **THEN** the run is a no-op (no new history rows; no errors)

### Requirement: V4 migration lands

The Flyway migration pipeline SHALL now carry an actual migration `V4__post_creation.sql`. Running `flywayMigrate` against a database at V3 MUST advance it to V4 and record a successful row in `flyway_schema_history`.

#### Scenario: V4 records on migrate
- **WHEN** running `flywayMigrate` against a database at V3
- **THEN** `flyway_schema_history` contains a row with `version = '4'` and `success = TRUE`

#### Scenario: V4 idempotent on second run
- **WHEN** `flywayMigrate` is run a second time against a database already at V4
- **THEN** the run is a no-op (no new history rows; no errors)

### Requirement: Migration smoke test asserts GIST indexes

The migration smoke-test harness SHALL include an assertion pattern for GIST indexes that future PostGIS-dependent migrations can reuse. Specifically `MigrationV4SmokeTest` MUST verify that `posts_display_location_idx` and `posts_actual_location_idx` use the `gist` access method (via `pg_indexes` or `pg_index` join on `pg_am`).

#### Scenario: Index access method is gist
- **WHEN** `MigrationV4SmokeTest` asserts on `posts_display_location_idx`
- **THEN** the assertion reads the access method for that index and verifies it equals `gist`

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
