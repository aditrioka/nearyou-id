# Backend Bootstrap

This spec defines the Ktor application skeleton on `:backend:ktor` — which plugins must be installed at startup, the StatusPages 5xx envelope contract, the Koin DI mounting point, and the health-check endpoints. Feature work plugs into the module function defined here.

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

### Requirement: Koin module installed (empty)

A Koin module SHALL be installed at startup. It MAY be empty in this change; subsequent feature changes register their bindings into it.

#### Scenario: Koin available for injection
- **WHEN** the application has started and `application.koin` is referenced
- **THEN** a non-null `Koin` instance is returned

### Requirement: Liveness endpoint

`GET /health/live` SHALL return HTTP 200 unconditionally with a stable, parseable body. It MUST NOT perform any I/O.

#### Scenario: Always 200
- **WHEN** any client (authenticated or not) sends `GET /health/live`
- **THEN** the response is HTTP 200

#### Scenario: No I/O
- **WHEN** all configured downstream dependencies (Postgres, Redis, etc.) are unreachable
- **THEN** `/health/live` still returns HTTP 200

### Requirement: Readiness endpoint stub

`GET /health/ready` SHALL exist and return HTTP 200 in this change (real dependency probes are added when each dependency is wired). The endpoint MUST be public (no auth) and MUST be subject to the same 60 req/min/IP rate limit target as documented in `docs/04-Architecture.md § Health Check Endpoints` (rate limiter installation may be deferred to a later change but the endpoint is documented as rate-limited).

#### Scenario: Stub returns 200
- **WHEN** any client sends `GET /health/ready`
- **THEN** the response is HTTP 200 with a JSON body containing a `status` field

#### Scenario: No auth required
- **WHEN** the request carries no Authorization header
- **THEN** the request is not rejected with 401/403

### Requirement: Application.module function exists

`Application.kt` MUST contain a `fun Application.module()` that wires all required plugins above. The wizard-generated empty stub is not acceptable.

#### Scenario: Module function defined
- **WHEN** searching `backend/ktor/src/main/kotlin/` for `fun Application.module(`
- **THEN** at least one definition is found
