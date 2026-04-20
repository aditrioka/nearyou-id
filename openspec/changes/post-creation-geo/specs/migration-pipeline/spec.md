## ADDED Requirements

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
