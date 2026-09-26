# Tasks: backend-sentry-error-capture

## 1. Module + dependencies

- [x] 1.1 `gradle/libs.versions.toml`: `sentry-java = "8.58.0"` + `sentry-java-core` (`io.sentry:sentry`) + `sentry-java-logback` library entries sharing one `version.ref` (no BOM needed for two artifacts; pre-implementation dated re-check recorded in design.md)
- [x] 1.2 New JVM module `infra/sentry-jvm` (`nearyou.kotlin.jvm` + `nearyou.detekt` + `java-test-fixtures`); Sentry deps `implementation`-scoped, logback + slf4j
- [x] 1.3 `settings.gradle.kts` unconditional `include(":infra:sentry-jvm")`; `Dockerfile` COPY for `build.gradle.kts` + `src`; `bash dev/scripts/check-dockerfile-module-copies.sh` green
- [x] 1.4 `dev/module-descriptions.txt` line + `dev/scripts/sync-readme.sh --write`

## 2. Infra — SentryBootstrap + PiiScrubber

- [x] 2.1 `PiiScrubber` (internal): coordinate pair, bearer, JWT, email, IPv4, IPv6 (≥4 groups), Postgres `Key (…)=(…)`; KDoc cross-links the mobile `:infra:sentry` sibling (design D5)
- [x] 2.2 `SentryBootstrap.start(env, dsn, release): Boolean` — blank DSN → `event=sentry_disabled reason=dsn_missing`; init failure → `reason=init_failed`; idempotent on the root logger's `SENTRY` appender; options per design D6 (`sendDefaultPii=false`, `attachServerName=false`, `contextTags=[call_id]`, in-app `id.nearyou`, no traces sample rate, `beforeSend` scrub + null user); appender `minimumEventLevel=ERROR`, `minimumBreadcrumbLevel=OFF`; `event=sentry_enabled` INFO line
- [x] 2.3 Test fixture `SentryEventRecorder` (testFixtures): starts the bootstrap with a recording transport; exposes vendor-free `CapturedSentryEvent` (level, message, exception types/values, tags, environment, release, hasUser, serverName, breadcrumb count); `close()` detaches the appender + closes the SDK

## 3. Backend wiring + lint

- [x] 3.1 `Application.module()`: `SentryBootstrap.start(env = otelEnv, dsn = otelSecrets.resolve("sentry-backend-dsn"), release = System.getenv("K_REVISION"))` right after OTel bootstrap
- [x] 3.2 `backend/ktor/build.gradle.kts`: `implementation(projects.infra.sentryJvm)` + `testImplementation(testFixtures(projects.infra.sentryJvm))`
- [x] 3.3 `VendorSdkLeakageScanTest`: add `io.sentry.` to the server-side prefix list (+ KDoc)

## 4. Tests

- [x] 4.1 Infra `SentryBootstrapTest`: blank/null DSN no-op (returns false, SDK disabled, no appender, INFO line); malformed DSN → false + `reason=init_failed`, no throw; valid DSN → enabled + one `SENTRY` appender; repeated start → still one appender; tracing not enabled
- [x] 4.2 Infra capture tests via `SentryEventRecorder`: ERROR with throwable → event with exception; ERROR without throwable → event level error; WARN/INFO → no event; uncaught exception on a background thread → event; environment + release set; MDC `call_id` → tag; no user / no server_name / no breadcrumbs
- [x] 4.3 Infra scrubbing tests: message with coordinate / bearer / JWT / email / IPv4 / IPv6 → `[redacted]` on the wire; exception value with email + `Key (email)=(…)` → `[redacted]`; `PiiScrubber` unit cases incl. `HH:MM:SS` and UUID not redacted
- [x] 4.4 Backend `SentryErrorCaptureTest`: route throw through `installAppStatusPages()` + CallId/CallLogging(`callIdMdc("call_id")`) → client gets the `internal_error` 500 AND one event with the exception type, `event=unhandled_exception` message, and `call_id` tag = the sent `X-Request-Id`
- [x] 4.5 `:lint:detekt-rules:test` green with the extended prefix list
- [x] 4.6 Review round (independent sub-agent): drop raw `message.params`; `printUncaughtStackTrace=true` + `flushTimeoutMillis=2000`; catch `Throwable`; scrub `Failing row contains (…)`, WKT `POINT(…)`, labeled `lat`/`lng`; correct the appender re-init rationale; define the "Sentry WARN" vocabulary (design D9) — with tests (raw-args, stderr trace, flush timeout, new scrub cases)
- [x] 4.7 `HealthRoutesTest`: real `module()` boot — no DSN → `event=sentry_disabled reason=dsn_missing` + `/health/live` 200; `SENTRY_BACKEND_DSN` (via the real `EnvVarSecretResolver`) → `event=sentry_enabled` (catches a #381-class wrong secret name)

## 5. Deploy + docs

- [x] 5.1 `deploy-staging.yml`: add `SENTRY_BACKEND_DSN=staging-sentry-backend-dsn:latest` to the *pending* `--set-secrets` comment list (not the live list — #381)
- [x] 5.2 `dev/docs/sentry-symbol-upload.md`: backend DSN section (Sentry project, slot, SA access, move mapping live, verify `event=sentry_enabled` + a test 500)
- [x] 5.3 docs/04 module table + § Sentry KMP backend line + the §Stack row ("Sentry KMP SDK (unified Android + iOS + backend)" → KMP mobile / Java backend); docs/05 § Observability "Sentry KMP unified for backend + mobile" wording; `openspec/project.md` stack row + module table (`:infra:sentry-jvm`, and correct the stale `:infra:sentry` "SCAFFOLD NEXT" row); docs/09 pin row for `sentry-java`
- [x] 5.4 At archive: fix the stale "Backend (Sentry-Java) error capture is explicitly out of scope" sentence in `openspec/specs/mobile-crash-reporting/spec.md` § Purpose (the REMOVED delta doesn't touch Purpose)

## 6. Verify + ship

- [x] 6.1 Gate: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test :infra:sentry-jvm:test` green; `check-dockerfile-module-copies.sh` green
- [x] 6.2 Staging branch deploy smoke (no slot yet): `event=sentry_disabled reason=dsn_missing` in logs (the 3.1 wiring is now also unit-tested by 4.7), `/health/ready` 200
- [ ] 6.3 Operator task (preflight): create the Sentry backend project + `staging-sentry-backend-dsn` slot + SA access, move the mapping live, confirm a staging 500 appears in Sentry with `call_id`, `environment=staging`, and no PII — **not done pre-merge (operator choice 2026-09-26); tracked in [#506](https://github.com/aditrioka/nearyou-id/issues/506)**. Smoke evidence for 6.2: run 36256322663 → revision `nearyou-backend-staging-00380-ql4` logged `event=sentry_disabled reason=dsn_missing`; `/health/live` + `/health/ready` 200
- [x] 6.4 PR title/body current; `Closes #316`
