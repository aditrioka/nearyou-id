## MODIFIED Requirements

### Requirement: Readiness endpoint stub

`GET /health/ready` SHALL exist. As of this change it MUST run a real Postgres probe: `SELECT 1` via the application's HikariCP pool with a 500 ms timeout. On success it returns HTTP 200 with `{ "status": "ok" }`. On timeout or query failure it returns HTTP 503 with `{ "status": "degraded" }`. The endpoint MUST remain public (no auth) and is documented as subject to a 60 req/min/IP rate limit (rate-limiter installation deferred).

#### Scenario: Postgres reachable
- **WHEN** the local Postgres pool returns within 500 ms
- **THEN** the response is HTTP 200 with body `{ "status": "ok" }`

#### Scenario: Postgres unreachable
- **WHEN** the Postgres pool query exceeds 500 ms or raises an exception
- **THEN** the response is HTTP 503 with body `{ "status": "degraded" }`

#### Scenario: No auth required
- **WHEN** the request carries no Authorization header
- **THEN** the request is not rejected with 401/403

## ADDED Requirements

### Requirement: Auth plugin installed

`Application.module()` SHALL install Ktor's `Authentication` plugin and register at least one JWT verifier configured for RS256 against the public key matching the configured `kid`. Routes that require auth MUST be wrapped in `authenticate { ... }` and MUST set a `UserPrincipal(userId, tokenVersion)` on the call when verification + token-version check succeed.

#### Scenario: Plugin installed
- **WHEN** the server starts
- **THEN** the startup log shows installation of `Authentication` (or equivalent log line) without exception

#### Scenario: Authenticated route gets principal
- **WHEN** a request with a valid access token reaches a route inside `authenticate { ... }`
- **THEN** `call.principal<UserPrincipal>()` returns a non-null principal whose `userId` matches the token's `sub`

### Requirement: HikariCP DataSource registered with Koin

A `DataSource` (HikariCP) SHALL be registered as a Koin singleton on application start, configured from environment variables (`DB_URL`, `DB_USER`, `DB_PASSWORD`, max pool size 20).

#### Scenario: DataSource resolvable
- **WHEN** the application has started and `org.koin.ktor.ext.inject<DataSource>()` is called
- **THEN** a non-null `HikariDataSource` is returned
