## ADDED Requirements

### Requirement: Docker Compose for local services

A `dev/docker-compose.yml` SHALL exist defining at least two services: `postgres` (using `postgis/postgis:16-3.4` exposing 5432) and `redis` (using `redis:7-alpine` exposing 6379). Container credentials and ports MUST be sourced from `dev/.env`.

#### Scenario: Compose file present
- **WHEN** running `docker compose -f dev/docker-compose.yml config`
- **THEN** the command exits 0 and lists both `postgres` and `redis` services

#### Scenario: Postgres ready accepts connections
- **WHEN** running `docker compose -f dev/docker-compose.yml up -d postgres` and waiting for healthcheck
- **THEN** a `psql` connection using the credentials in `dev/.env.example` succeeds

### Requirement: Env-var template committed; secrets gitignored

`dev/.env.example` SHALL be committed and list every required environment variable with safe placeholder values. `dev/.env` MUST be present in `.gitignore`.

#### Scenario: Template present
- **WHEN** listing `dev/`
- **THEN** `.env.example` is present and `.env` is not (or is gitignored)

#### Scenario: Required vars listed
- **WHEN** reading `dev/.env.example`
- **THEN** it includes at minimum `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_DB`, `KTOR_RSA_PRIVATE_KEY`, `SUPABASE_JWT_SECRET`, `GOOGLE_CLIENT_IDS`, `APPLE_BUNDLE_IDS`

### Requirement: RSA keypair bootstrap script

A script `dev/scripts/generate-rsa-keypair.sh` SHALL generate a fresh 2048-bit RSA keypair, base64-encode the PEM-encoded private key, and print a ready-to-paste `KTOR_RSA_PRIVATE_KEY=...` line.

#### Scenario: Script runs
- **WHEN** executing the script in a working OpenSSL environment
- **THEN** stdout includes a line starting with `KTOR_RSA_PRIVATE_KEY=` followed by valid base64

### Requirement: Test-user seeding script

A script `dev/scripts/seed-test-user.sh` SHALL insert a row into `users` satisfying every NOT-NULL column, with parameters for `google_id_hash` and `apple_id_hash`. The script provides the dev workaround for the deferred signup endpoint.

#### Scenario: Seed runs
- **WHEN** the script is executed against a running local Postgres after V2 has been applied
- **THEN** a new `users` row exists with the specified hash and the script prints the new user's id

### Requirement: README documents the workflow

`dev/README.md` SHALL document: starting compose, running `dev/scripts/generate-rsa-keypair.sh` once, running `./gradlew :backend:ktor:flywayMigrate`, seeding a test user, and running `./gradlew :backend:ktor:run`.

#### Scenario: README present and non-empty
- **WHEN** listing `dev/`
- **THEN** `README.md` is present and non-empty
