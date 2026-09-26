# Tasks: backend-sentry-error-capture

## 1. Module + dependencies

- [ ] 1.1 `gradle/libs.versions.toml`: `sentry-java = "8.58.0"` + `sentry-bom`, `sentry-core` (`io.sentry:sentry`), `sentry-logback` library entries (pre-implementation dated re-check recorded in design.md)
- [ ] 1.2 New JVM module `infra/sentry-jvm` (`nearyou.kotlin.jvm` + `nearyou.detekt` + `java-test-fixtures`); Sentry deps `implementation`-scoped (BOM platform), logback + slf4j
- [ ] 1.3 `settings.gradle.kts` unconditional `include(":infra:sentry-jvm")`; `Dockerfile` COPY for `build.gradle.kts` + `src`; `bash dev/scripts/check-dockerfile-module-copies.sh` green
- [ ] 1.4 `dev/module-descriptions.txt` line + `dev/scripts/sync-readme.sh --write`

## 2. Infra — SentryBootstrap + PiiScrubber

- [ ] 2.1 `PiiScrubber` (internal): coordinate pair, bearer, JWT, email, IPv4, IPv6 (≥4 groups), Postgres `Key (…)=(…)`; KDoc cross-links the mobile `:infra:sentry` sibling (design D5)
- [ ] 2.2 `SentryBootstrap.start(env, dsn, release): Boolean` — blank DSN → `event=sentry_disabled reason=dsn_missing`; init failure → `reason=init_failed`; idempotent on the root logger's `SENTRY` appender; options per design D6 (`sendDefaultPii=false`, `attachServerName=false`, `contextTags=[call_id]`, in-app `id.nearyou`, no traces sample rate, `beforeSend` scrub + null user); appender `minimumEventLevel=ERROR`, `minimumBreadcrumbLevel=OFF`; `event=sentry_enabled` INFO line
- [ ] 2.3 Test fixture `SentryEventRecorder` (testFixtures): starts the bootstrap with a recording transport; exposes vendor-free `CapturedSentryEvent` (level, message, exception types/values, tags, environment, release, hasUser, serverName, breadcrumb count); `close()` detaches the appender + closes the SDK

## 3. Backend wiring + lint

- [ ] 3.1 `Application.module()`: `SentryBootstrap.start(env = otelEnv, dsn = otelSecrets.resolve("sentry-backend-dsn"), release = System.getenv("K_REVISION"))` right after OTel bootstrap
- [ ] 3.2 `backend/ktor/build.gradle.kts`: `implementation(projects.infra.sentryJvm)` + `testImplementation(testFixtures(projects.infra.sentryJvm))`
- [ ] 3.3 `VendorSdkLeakageScanTest`: add `io.sentry.` to the server-side prefix list (+ KDoc)

## 4. Tests

- [ ] 4.1 Infra `SentryBootstrapTest`: blank/null DSN no-op (returns false, SDK disabled, no appender, INFO line); malformed DSN → false + `reason=init_failed`, no throw; valid DSN → enabled + one `SENTRY` appender; repeated start → still one appender; tracing not enabled
- [ ] 4.2 Infra capture tests via `SentryEventRecorder`: ERROR with throwable → event with exception; ERROR without throwable → event level error; WARN/INFO → no event; uncaught exception on a background thread → event; environment + release set; MDC `call_id` → tag; no user / no server_name / no breadcrumbs
- [ ] 4.3 Infra scrubbing tests: message with coordinate / bearer / JWT / email / IPv4 / IPv6 → `[redacted]` on the wire; exception value with email + `Key (email)=(…)` → `[redacted]`; `PiiScrubber` unit cases incl. `HH:MM:SS` and UUID not redacted
- [ ] 4.4 Backend `SentryErrorCaptureTest`: route throw through `installAppStatusPages()` + CallId/CallLogging(`callIdMdc("call_id")`) → client gets the `internal_error` 500 AND one event with the exception type, `event=unhandled_exception` message, and `call_id` tag = the sent `X-Request-Id`
- [ ] 4.5 `:lint:detekt-rules:test` green with the extended prefix list

## 5. Deploy + docs

- [ ] 5.1 `deploy-staging.yml`: add `SENTRY_BACKEND_DSN=staging-sentry-backend-dsn:latest` to the *pending* `--set-secrets` comment list (not the live list — #381)
- [ ] 5.2 `dev/docs/sentry-symbol-upload.md`: backend DSN section (Sentry project, slot, SA access, move mapping live, verify `event=sentry_enabled` + a test 500)
- [ ] 5.3 docs/04 module table + § Sentry KMP backend line + the §Stack row ("Sentry KMP SDK (unified Android + iOS + backend)" → KMP mobile / Java backend); docs/05 § Observability "Sentry KMP unified for backend + mobile" wording; `openspec/project.md` stack row + module table (`:infra:sentry-jvm`, and correct the stale `:infra:sentry` "SCAFFOLD NEXT" row); docs/09 pin row for `sentry-java`
- [ ] 5.4 At archive: fix the stale "Backend (Sentry-Java) error capture is explicitly out of scope" sentence in `openspec/specs/mobile-crash-reporting/spec.md` § Purpose (the REMOVED delta doesn't touch Purpose)

## 6. Verify + ship

- [ ] 6.1 Gate: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test :infra:sentry-jvm:test` green; `check-dockerfile-module-copies.sh` green
- [ ] 6.2 Staging branch deploy smoke (no slot yet): `event=sentry_disabled reason=dsn_missing` in logs (also proves the 3.1 `Application.module()` wiring — spec "Bootstrap is called once at startup"), `/health/ready` 200
- [ ] 6.3 Operator task (preflight): create the Sentry backend project + `staging-sentry-backend-dsn` slot + SA access, move the mapping live, confirm a staging 500 appears in Sentry with `call_id`, `environment=staging`, and no PII
- [ ] 6.4 PR title/body current; `Closes #316`
