## ADDED Requirements

### Requirement: Backend error reporting via a vendor-fenced bootstrap
`:backend:ktor` SHALL report errors to Sentry through a vendor-SDK-free entrypoint `SentryBootstrap.start(env, dsn, release)` defined in a new JVM module `:infra:sentry-jvm`. The Sentry Java SDK (`io.sentry:sentry`, `io.sentry:sentry-logback`) MUST be confined to `:infra:sentry-jvm` and depended on with `implementation` scope so it never reaches `:backend:ktor`'s compile classpath (invariant #16). `:infra:sentry-jvm` is distinct from the mobile-only KMP `:infra:sentry`.

#### Scenario: Vendor SDK is fenced inside :infra:sentry-jvm
- **WHEN** the vendor-SDK-leakage scan runs over `core/domain/src`, `core/data/src`, and `backend/ktor/src`
- **THEN** no `io.sentry.` import is found — the `io.sentry.` prefix is part of the server-side forbidden-prefix list

#### Scenario: Bootstrap is called once at startup
- **WHEN** `Application.module()` runs
- **THEN** it calls `SentryBootstrap.start` immediately after the OpenTelemetry bootstrap, passing the `ktor.environment` value, the DSN resolved from the `sentry-backend-dsn` secret, and the `K_REVISION` environment value as the release

### Requirement: DSN-gated, idempotent, exception-safe initialization
`SentryBootstrap.start` SHALL initialize the Sentry SDK and attach a logback `SentryAppender` to the root logger only when the DSN is non-blank. A missing or blank DSN MUST be a no-op that emits one INFO line `event=sentry_disabled reason=dsn_missing` and leaves the SDK uninitialized. An SDK initialization failure (any `Throwable`, e.g. a malformed DSN or a linkage error) MUST NOT crash startup: it emits `event=sentry_disabled reason=init_failed` and leaves reporting off. A repeated call in the same JVM MUST NOT attach a second appender. The DSN is resolved by the unprefixed logical name `sentry-backend-dsn` (env var `SENTRY_BACKEND_DSN`); the deploy maps it from the env-namespaced Secret Manager slot (`staging-sentry-backend-dsn` in staging).

#### Scenario: Blank DSN no-ops safely
- **WHEN** `SentryBootstrap.start` is called with a null or blank DSN
- **THEN** it returns `false`, the SDK is not enabled, no Sentry appender is attached to the root logger, and one `event=sentry_disabled reason=dsn_missing` INFO line is logged

#### Scenario: Malformed DSN does not crash startup
- **WHEN** `SentryBootstrap.start` is called with a DSN the SDK rejects
- **THEN** it returns `false` without throwing, no Sentry appender is attached, and an `event=sentry_disabled reason=init_failed` INFO line is logged

#### Scenario: Valid DSN enables reporting
- **WHEN** `SentryBootstrap.start` is called with a valid DSN
- **THEN** it returns `true`, the SDK is enabled with the given `environment` and `release`, and exactly one appender named `SENTRY` is attached to the root logger

#### Scenario: Repeated start does not double-attach
- **WHEN** `SentryBootstrap.start` is called a second time in the same JVM after reporting is enabled
- **THEN** the root logger still carries exactly one `SENTRY` appender

#### Scenario: Staging deploy without the slot stays healthy
- **WHEN** the backend is deployed to staging before the operator provisions `staging-sentry-backend-dsn`
- **THEN** startup logs `event=sentry_disabled reason=dsn_missing` and `/health/ready` still answers 200

### Requirement: ERROR log events and uncaught exceptions are captured
With reporting enabled, every logback event at level `ERROR` or above SHALL be sent to Sentry as an event, carrying the attached throwable when present. Events below `ERROR` MUST NOT be sent as events. Other backend specs' observability wording maps onto this: a "Sentry ERROR" is a log line at `ERROR` and is forwarded; a "Sentry WARN", "Sentry INFO", or "Sentry breadcrumb" is the structured log line at that level in Cloud Logging and is NOT forwarded. Unhandled route exceptions MUST be captured through the existing StatusPages `Throwable` handler's `event=unhandled_exception` ERROR line (no per-call-site capture code). Exceptions that escape a non-request thread MUST be captured by the SDK's uncaught-exception handler, which MUST still print the stack trace to stderr (Cloud Logging keeps it) and MUST cap its blocking flush at 2 seconds.

#### Scenario: Unhandled route exception is reported
- **WHEN** a route throws an exception that reaches the StatusPages `Throwable` catch-all
- **THEN** the client receives the fixed `internal_error` 500 envelope AND one Sentry event is sent carrying the exception type and the `event=unhandled_exception method=… path=…` message

#### Scenario: ERROR log without a throwable is reported
- **WHEN** backend code logs at `ERROR` without an exception
- **THEN** one Sentry event is sent with the formatted message and level `error`

#### Scenario: WARN and INFO logs are not reported
- **WHEN** backend code logs at `WARN` or `INFO` — including lines older specs call a "Sentry WARN" (e.g. `event=layer3_dispatch_failed`)
- **THEN** no Sentry event is sent

#### Scenario: Uncaught exception on a background thread is reported
- **WHEN** an exception escapes a non-request thread with reporting enabled
- **THEN** one Sentry event is sent carrying that exception AND the stack trace is still printed to stderr

### Requirement: Backend events carry no user identity and are PII-scrubbed
Reporting SHALL initialize with `sendDefaultPii = false` and without `server_name`, and MUST NOT attach any user identity (no Sentry `user`, no user id, username, or email) — docs/06's "backend skips error enrichment with user_id" on crash-consent decline is satisfied by construction for every user. Log-derived breadcrumbs MUST be disabled (thread-local SDK scopes are shared across unrelated requests on coroutine threads). Raw log arguments (the message `params` the appender copies) MUST NOT leave the process — the scrubbed formatted message already carries them. A `beforeSend` scrubber SHALL redact coordinate pairs, labeled single coordinates (`lat=…`, `"lng":…`), WKT `POINT(…)`, bearer tokens, JWTs, email addresses, IPv4/IPv6 addresses, and Postgres detail echoes (`Key (…)=(…)` values and the whole `Failing row contains (…)` row) from the event message and every exception value before the event leaves the process. This mirrors the mobile `PiiScrubber` pattern (pattern reuse, not a cross-module import — the mobile module has no JVM target).

#### Scenario: Coordinates, tokens, and emails in a message are scrubbed
- **WHEN** an ERROR log message contains a coordinate pair, a bearer token, a JWT, an email address, or an IP address
- **THEN** the event that leaves the process carries `[redacted]` in place of each value

#### Scenario: Raw log arguments are not sent
- **WHEN** an ERROR is logged with arguments (e.g. `log.error("mail={}", email)`)
- **THEN** the event carries only the scrubbed formatted message and no message params

#### Scenario: Exception values are scrubbed
- **WHEN** a captured exception's message contains an email address, a Postgres `Key (email)=(…)` detail, or a `Failing row contains (…)` row echo
- **THEN** the event's exception value carries `[redacted]` in place of the value

#### Scenario: No user identity or host name is attached
- **WHEN** any backend event is sent
- **THEN** it carries no `user`, no `server_name`, and no breadcrumbs

### Requirement: Events are tagged for correlation, tracing stays with OpenTelemetry
Each event SHALL carry `environment` (the `ktor.environment` value) and `release` (Cloud Run `K_REVISION`, absent locally). The request correlation id (the CallId plugin's `call_id` MDC value) MUST be promoted to a Sentry tag `call_id` so an event links to its request trail in Cloud Logging. Stack frames under `id.nearyou` MUST be marked in-app. Sentry performance tracing MUST remain disabled — OpenTelemetry owns traces.

#### Scenario: A request error carries its call_id tag
- **WHEN** a route error is reported for a request whose `X-Request-Id` is `abc-123`
- **THEN** the Sentry event carries tag `call_id = abc-123`

#### Scenario: Environment and release are set
- **WHEN** reporting is enabled with environment `staging` and release `nearyou-backend-00042-xyz`
- **THEN** every event carries `environment = staging` and `release = nearyou-backend-00042-xyz`

#### Scenario: Sentry tracing is disabled
- **WHEN** reporting is enabled
- **THEN** the SDK's tracing is not enabled (no traces sample rate), so no Sentry transactions are produced
