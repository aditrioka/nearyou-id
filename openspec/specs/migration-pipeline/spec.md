# Migration Pipeline

This spec defines the Flyway migration scaffold on `:backend:ktor`, the file-naming convention for migrations, and the env-namespaced secret-resolution helper used to derive DB connection secret keys.

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
