# Backend Bootstrap

## Purpose

Defines the Ktor application skeleton on `:backend:ktor` — which plugins must be installed at startup, the StatusPages 5xx envelope contract, the Koin DI mounting point, the content-length guard registry, and the health-check endpoints. Feature work plugs into the module function defined here.

See `docs/04-Architecture.md § Health Check Endpoints` for the ultimate `/health/ready` design (real dependency probes are added when each dependency is wired).
## Requirements
### Requirement: Application entry point with mandatory plugins

The Ktor `Application` module in `:backend:ktor` SHALL install the following plugins at startup: `ContentNegotiation` (with kotlinx.serialization JSON), `StatusPages`, `CallLogging`, and Koin DI.

#### Scenario: Plugins installed
- **WHEN** the Ktor server starts via `./gradlew :backend:ktor:run`
- **THEN** the startup log shows installation of `ContentNegotiation`, `StatusPages`, `CallLogging`, and Koin (no exception)

#### Scenario: JSON content negotiation works
- **WHEN** a route returns a `@Serializable` Kotlin data class
- **THEN** the response `Content-Type` is `application/json` and the body is valid JSON

### Requirement: StatusPages 5xx envelope

The `StatusPages` plugin SHALL convert any uncaught exception into an HTTP 500 with a JSON body of shape `{ "error": { "code": "<string>", "message": "<string>" } }`. 4xx responses MUST pass through unchanged (Ktor defaults).

#### Scenario: Uncaught exception
- **WHEN** a route throws an exception not handled by per-route logic and a request hits it
- **THEN** the response is HTTP 500 with body `{ "error": { "code": "...", "message": "..." } }` (`code` non-empty, `message` non-empty)

### Requirement: Koin module installed

A Koin module SHALL be installed at startup. Subsequent feature changes register their bindings into it. As of the auth-foundation change the module MUST register at least a `javax.sql.DataSource` (HikariCP) singleton configured from environment variables `DB_URL` / `DB_USER` / `DB_PASSWORD` (max pool size 20).

#### Scenario: Koin available for injection
- **WHEN** the application has started and `application.koin` is referenced
- **THEN** a non-null `Koin` instance is returned

#### Scenario: DataSource resolvable
- **WHEN** the application has started and `org.koin.ktor.ext.inject<DataSource>()` is called
- **THEN** a non-null `HikariDataSource` is returned

### Requirement: Liveness endpoint

`GET /health/live` SHALL return HTTP 200 unconditionally with a stable, parseable body. It MUST NOT perform any I/O.

#### Scenario: Always 200
- **WHEN** any client (authenticated or not) sends `GET /health/live`
- **THEN** the response is HTTP 200

#### Scenario: No I/O
- **WHEN** all configured downstream dependencies (Postgres, Redis, etc.) are unreachable
- **THEN** `/health/live` still returns HTTP 200

### Requirement: Readiness endpoint

`GET /health/ready` SHALL run a real Postgres probe: `SELECT 1` via the application's HikariCP pool with a 500 ms timeout. On success it returns HTTP 200 with `{ "status": "ok" }`. On timeout or query failure it returns HTTP 503 with `{ "status": "degraded" }`. The endpoint MUST remain public (no auth) and is documented as subject to a 60 req/min/IP rate limit (rate-limiter installation deferred). Future deps (Redis, Supabase Realtime HTTP probe) are added by their respective changes via parallel `async { ... }` checks per `docs/04-Architecture.md § Health Check Endpoints`.

#### Scenario: Postgres reachable
- **WHEN** the local Postgres pool returns within 500 ms
- **THEN** the response is HTTP 200 with body `{ "status": "ok" }`

#### Scenario: Postgres unreachable
- **WHEN** the Postgres pool query exceeds 500 ms or raises an exception
- **THEN** the response is HTTP 503 with body `{ "status": "degraded" }`

#### Scenario: No auth required
- **WHEN** the request carries no Authorization header
- **THEN** the request is not rejected with 401/403

### Requirement: Application.module function exists

`Application.kt` MUST contain a `fun Application.module()` that wires all required plugins above. The wizard-generated empty stub is not acceptable.

#### Scenario: Module function defined
- **WHEN** searching `backend/ktor/src/main/kotlin/` for `fun Application.module(`
- **THEN** at least one definition is found

### Requirement: Auth plugin installed

`Application.module()` SHALL install Ktor's `Authentication` plugin and register at least one JWT verifier configured for RS256 against the public key matching the configured `kid`. Routes that require auth MUST be wrapped in `authenticate { ... }` and MUST set a `UserPrincipal(userId, tokenVersion)` on the call when verification + token-version check succeed.

#### Scenario: Plugin installed
- **WHEN** the server starts
- **THEN** the startup log shows installation of `Authentication` (or equivalent log line) without exception

#### Scenario: Authenticated route gets principal
- **WHEN** a request with a valid access token reaches a route inside `authenticate { ... }`
- **THEN** `call.principal<UserPrincipal>()` returns a non-null principal whose `userId` matches the token's `sub`

### Requirement: ContentLengthGuard middleware with per-route limits registry

`Application.module()` SHALL install a `ContentLengthGuard` Ktor plugin that consults a registry mapping a route key (e.g., `"post.content"`) to a maximum code-point length. This change MUST register exactly one entry — `"post.content" → 280` — and wire the guard to the `POST /api/v1/posts` route. Future content-bearing endpoints register their own entries without modifying the plugin.

The guard MUST:
1. NFKC-normalize the incoming string.
2. Trim leading/trailing whitespace.
3. Reject empty (post-trim) with HTTP 400 code `content_empty`.
4. Reject code-point length > the registered limit with HTTP 400 code `content_too_long`.

#### Scenario: Plugin installed at startup
- **WHEN** the server starts
- **THEN** the startup log shows installation of `ContentLengthGuard` (or equivalent log line) without exception

#### Scenario: post.content limit registered
- **WHEN** inspecting the registry after startup
- **THEN** the registry contains an entry `("post.content", 280)`

#### Scenario: Registry-driven enforcement on post creation
- **WHEN** a `POST /api/v1/posts` request carries a 281-code-point `content`
- **THEN** the guard rejects with HTTP 400 `content_too_long` before the route handler runs

### Requirement: Post creation route wired in Application.module()

`Application.module()` SHALL register the `POST /api/v1/posts` route inside `authenticate { ... }` so the Ktor `Authentication` plugin runs before the route handler.

#### Scenario: Route is authenticated
- **WHEN** `POST /api/v1/posts` is hit without an `Authorization` header
- **THEN** the response is HTTP 401 (from the `Authentication` plugin, not the route handler)

