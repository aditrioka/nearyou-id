## Why

`:backend:ktor` has OpenTelemetry tracing but **no error aggregation**: every unhandled 500 and every operator-actionable `log.error(...)` (moderation-list cascade exhausted, Layer-3 misconfig, worker failures) lands only as a Cloud Logging line nobody is alerted on. Several backend KDocs already describe their ERROR/WARN lines as "surfaced via SLF4J → logback → Sentry" — a pipeline that was never wired. `mobile-sentry-crash-reporting` (PR #299) shipped the mobile half and explicitly deferred the backend half to issue [#316](https://github.com/aditrioka/nearyou-id/issues/316) (spec `mobile-crash-reporting` § "Backend error capture is out of scope (deferred)"). docs/04 § Sentry KMP names the target: "backend errors via Sentry Java; unified dashboard for correlation". Closing it before launch means the first production 500 is an alert, not a log-search.

## What Changes

- **New JVM module `:infra:sentry-jvm`** fencing the Sentry Java SDK (`io.sentry:sentry` + `io.sentry:sentry-logback`, pinned `8.58.0`) per invariant #16. Separate from the mobile-only KMP `:infra:sentry` (Android-gated, no JVM target) — the `:infra:revenuecat` / `:infra:revenuecat-api` precedent.
- **`SentryBootstrap.start(env, dsn, release)`** — vendor-free entrypoint called once near the top of `Application.module()` (right after OTel). Blank/absent DSN is a safe no-op (one INFO line, no appender, no SDK init). Idempotent across repeated `module()` calls.
- **Capture path = logback `SentryAppender` on the root logger**: `ERROR` log events (including the StatusPages `event=unhandled_exception` 500 handler, which carries the stack trace) become Sentry events; the SDK's default uncaught-exception handler catches crashes on non-request threads. No per-call-site code changes — the existing ERROR lines are the contract.
- **PII posture mirrors mobile**: `sendDefaultPii = false`, no `server_name`, **no user identity attached** (docs/06 "backend skips error enrichment with user_id" satisfied by construction for every user, consented or not), log-derived breadcrumbs **off** (thread-local scopes on coroutine threads would mix unrelated requests), and a `beforeSend` scrubber over the message + exception values (coordinates, bearer tokens, JWTs, emails, IPs, Postgres `Key (…)=(…)` detail values).
- **Correlation**: the request `call_id` (CallId MDC) is promoted to a Sentry tag, so an event links to the full request trail in Cloud Logging; `environment` from `ktor.environment`; `release` from Cloud Run's `K_REVISION`. Sentry performance tracing stays **off** (OTel owns traces).
- **DSN via Secret Manager** slot `sentry-backend-dsn` (env var `SENTRY_BACKEND_DSN`, resolved with the unprefixed-name convention). Added to `deploy-staging.yml`'s *pending* `--set-secrets` list — mapping a slot that doesn't exist yet fails the deploy (#381), so the operator moves it live after provisioning.
- **Lint**: `VendorSdkLeakageScanTest` server-side prefix list gains `io.sentry.` (core/domain, core/data, backend/ktor).
- **Test fixture** `SentryEventRecorder` (java-test-fixtures, vendor-free return types) so `:backend:ktor` can assert end-to-end capture without importing `io.sentry`.
- Docs: module tables (docs/04, project.md, README via `dev/module-descriptions.txt`), docs/09 pin row, `dev/docs/sentry-symbol-upload.md` backend-DSN section, `Dockerfile` COPY lines for the new module.

## Capabilities

### New Capabilities
- `backend-error-reporting`: Sentry-Java error capture for `:backend:ktor` — vendor-fenced bootstrap, DSN-gated no-op, logback-ERROR + uncaught-exception capture, PII scrubbing, no user identity, `call_id` correlation, release/environment tagging.

### Modified Capabilities
- `mobile-crash-reporting`: REMOVE requirement "Backend error capture is out of scope (deferred)" — its "No Sentry in the backend" scenario becomes false by design; backend behavior is now owned by `backend-error-reporting`.

## Impact

- **New module**: `infra/sentry-jvm/` (+ `settings.gradle.kts` unconditional include, `Dockerfile` COPY ×2, `dev/module-descriptions.txt`).
- **Code**: `backend/ktor/.../Application.kt` (one bootstrap call), `backend/ktor/build.gradle.kts` (dep + test fixture), `lint/detekt-rules/.../VendorSdkLeakageScanTest.kt`.
- **Dependencies**: `gradle/libs.versions.toml` — `sentry-java = "8.58.0"` (`sentry` + `sentry-logback` on one version ref). Runtime-only for `:backend:ktor` (`implementation` in the infra module).
- **Ops (human-required)**: create the Sentry backend project/DSN, provision `staging-sentry-backend-dsn`, move the pending `--set-secrets` mapping live; production slot at prod cutover.
- **No schema, API, wire-contract, admin, or mobile change.** Operator-facing only (the Sentry dashboard).
