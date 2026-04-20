# Local Dev Environment

Defines the Dockerized Postgres+PostGIS+Redis stack used for local development, the env-var conventions for backend connection + secrets, and the bootstrap helper scripts (RSA keypair, test-user seeding).

See `dev/README.md` for the day-to-day workflow.

## Requirements

### Requirement: Docker Compose for local services

A `dev/docker-compose.yml` SHALL exist defining at least two services: `postgres` (using `postgis/postgis:16-3.4`, exposed on host port 5433 to avoid colliding with native Postgres on 5432) and `redis` (using `redis:7-alpine`, port 6379). Container credentials and ports MUST be sourced from `dev/.env`. Container names MUST be namespaced (e.g. `nearyouid-dev-postgres`) to avoid collision with stale containers from other projects.

#### Scenario: Compose file present
- **WHEN** running `docker compose -f dev/docker-compose.yml config`
- **THEN** the command exits 0 and lists both `postgres` and `redis` services

#### Scenario: Postgres ready accepts connections
- **WHEN** running `docker compose -f dev/docker-compose.yml --env-file dev/.env up -d postgres` and waiting for healthcheck
- **THEN** a `psql -h localhost -p 5433` connection using the credentials in `dev/.env.example` succeeds

### Requirement: Env-var template committed; secrets gitignored

`dev/.env.example` SHALL be committed and list every required environment variable with safe placeholder values. `dev/.env` MUST be present in `.gitignore`.

#### Scenario: Template present
- **WHEN** listing `dev/`
- **THEN** `.env.example` is present and `.env` is not (or is gitignored)

#### Scenario: Required vars listed
- **WHEN** reading `dev/.env.example`
- **THEN** it includes at minimum `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_DB`, `KTOR_RSA_PRIVATE_KEY`, `SUPABASE_JWT_SECRET`, `GOOGLE_CLIENT_IDS`, `APPLE_BUNDLE_IDS`, `DB_URL`, `DB_USER`, `DB_PASSWORD`, `KTOR_ENV`

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

`dev/README.md` SHALL document: starting compose, running `dev/scripts/generate-rsa-keypair.sh` once, running `./gradlew :backend:ktor:flywayMigrate` (with the `--no-configuration-cache` workaround required by Flyway 11.x), seeding a test user, and running `./gradlew :backend:ktor:run`. The README MUST also explain the host-port-5433 choice, the `nearyouid-dev-*` container naming, and the `network` Kotest-tag exclusion in PR CI.

#### Scenario: README present and non-empty
- **WHEN** listing `dev/`
- **THEN** `README.md` is present and non-empty

#### Scenario: Workarounds documented
- **WHEN** reading `dev/README.md`
- **THEN** it mentions `--no-configuration-cache` (Flyway 11.x), port 5433, and the `nearyouid-dev-*` container prefix
