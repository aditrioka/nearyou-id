## ADDED Requirements

### Requirement: First real migration V2 lands

The Flyway migration pipeline SHALL exercise an actual schema migration named `V2__auth_foundation.sql`. After this change `flywayMigrate` against a fresh DB MUST result in `flyway_schema_history` containing both V1 (placeholder) and V2 records.

#### Scenario: V2 records on fresh migrate
- **WHEN** running `flywayMigrate` against an empty Postgres
- **THEN** `flyway_schema_history` contains a row with `version = '2'` and `success = TRUE`

#### Scenario: V2 idempotent on second run
- **WHEN** `flywayMigrate` is run a second time against the same database
- **THEN** the run is a no-op (no new history rows; no errors)
