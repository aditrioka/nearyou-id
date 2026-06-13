## 1. Pre-implementation gates

- [x] 1.1 **Substrate re-check (MANDATORY before first feat commit).** Run a fresh date-anchored `WebSearch` on the Sentry Kotlin Multiplatform SDK (currency, stability, CMP crash/error support, latest version). Record `verified 2026-MM-DD: …` in the first feat commit body (project.md § pre-implementation library re-check). If a materially-better path surfaces, STOP and `AskUserQuestion` before pinning.
- [ ] 1.2 Verify the Open Decision #17 contingency (dual-pipeline fallback behind the same `CrashReporter` interface) is reflected in `design.md` Decision 3 — already written; confirm it survives review.
- [ ] 1.3 [operator input] Obtain per-flavor Sentry DSN values (`dev` / `staging` / `production`). Until provided, ship per-flavor placeholders; a blank DSN MUST no-op init. Never invent a DSN value.
- [ ] 1.4 File a `follow-up` GitHub issue (labels `follow-up`,`backend`) capturing **backend Sentry-Java error capture** as deferred scope (satisfies the spec "Backend error capture is out of scope" tracking scenario). File before archive.

## 2. `:infra:sentry` module scaffold

- [ ] 2.1 Create `infra/sentry/` KMP module + `build.gradle.kts` (targets: `androidTarget`, `iosArm64`, `iosX64`, `iosSimulatorArm64`; source sets commonMain/androidMain/iosMain/commonTest/iosTest). Pin the Sentry KMP SDK in `gradle/libs.versions.toml`; declare it `implementation`-scoped in this module only (invariant #16).
- [x] 2.2 Register `:infra:sentry` in `settings.gradle.kts`.
- [x] 2.3 Add a one-line description to `dev/module-descriptions.txt` and run `dev/scripts/sync-readme.sh --write` (README module list).

## 3. CrashReporter interface + models (commonMain)

- [x] 3.1 Define `CrashReporter` interface — `init(config)`, `captureException(t, context)`, `setUser(id)`, `clearUser()`, `addBreadcrumb(crumb)`, `close()` — vendor-free.
- [x] 3.2 Define vendor-free models: `CrashReporterConfig` (dsn, environment, release), `Breadcrumb`; a no-op fallback for blank-DSN / test paths.

## 4. Platform actuals (Sentry SDK, fenced)

- [ ] 4.1 androidMain `SentryCrashReporter` binding the Sentry Android SDK.
- [ ] 4.2 iosMain `SentryCrashReporter` binding the Sentry Cocoa SDK (mind K/N ObjC category imports — `import platform.<Fw>.<symbol>`; run `linkDebugFrameworkIosSimulatorArm64` locally).
- [ ] 4.3 Init with `sendDefaultPii = false` (no client-IP `{{auto}}` inference, no `server_name`/device-name); implement the `beforeSend` scrubbing hook (strip coordinates / tokens / free-text bodies), mirroring the coordinate-free discipline (`CoordinateMaskingLogger` is `private` to `:mobile:app` — reuse the pattern, do not import it across the module fence).

## 5. Mobile app wiring (`:mobile:app`)

- [ ] 5.1 Add a `:infra:sentry` dependency to `:mobile:app` (API/interface only — confirm the SDK is not on the app's compile classpath).
- [ ] 5.2 Bind `CrashReporter` in `di/MobileModule.kt`; provide platform actuals via androidMain/iosMain `di/PlatformModule.kt`.
- [ ] 5.3 Resolve flavor-aware DSN + `environment` + `release` from the `/config` seam + flavor source sets (`dev`/`staging`/`production`).
- [ ] 5.4 Initialize at startup before app code can crash (Android `Application.onCreate` / iOS entry); `App.kt` triggers it via Koin.
- [ ] 5.5 Consent gate (Decision 6): opt-out default ON; the consent settings toggle applies immediately (`close()` on decline, re-init on re-consent); cold-start reads last-known `crash` from the existing `ConsentSnapshotStore` (`data/consent`). Note the in-memory durability caveat — a decline survives process death only once #198 lands durable storage behind the same interface (consume that seam, do NOT fork a parallel store).
- [ ] 5.6 `setUser(jwtSubject)` on the auth success path; `clearUser()` on logout. Never attach username/email/display-name.
- [ ] 5.7 Swap the `DiagnosticSink` Koin binding to a Sentry breadcrumb sink, replacing `ConsoleDiagnosticSink`'s release drop-in (keep console output in debug). Do NOT fork a parallel diagnostics path.

## 6. Symbolication

- [ ] 6.1 Configure Android ProGuard/R8 mapping upload via the Sentry Gradle plugin.
- [ ] 6.2 Configure the iOS dSYM upload build phase / `sentry-cli upload-dif`.
- [ ] 6.3 [operator hand-off] The `.github/workflows/**` upload-step invocation + the `SENTRY_AUTH_TOKEN` repo secret are **agent-hook-blocked** — document the exact YAML + secret for the operator to apply. Reporting works (unsymbolicated) until this lands.

## 7. Tests (docs/11 §2.7)

- [ ] 7.1 commonTest (kotlin.test): consent gating — decline→`close()`, re-consent→re-init, cold-start-declined→no events — using a capturing fake `CrashReporter`.
- [ ] 7.2 commonTest: `beforeSend` PII scrubbing strips a coordinate and a token from an event/breadcrumb payload; assert `sendDefaultPii = false` is set so the client IP / `server_name` are not attached.
- [ ] 7.3 commonTest: `setUser(sub)` on auth and `clearUser()` on logout; assert no username/email is attached.
- [ ] 7.4 androidUnitTest (Koin-graph, mirroring the existing `di/DiagnosticSinkWiringTest`): `DiagnosticSink` → breadcrumb wiring records a diagnostic via the real (non-no-op) binding.
- [ ] 7.5 Koin resolution test mirroring the existing `*KoinResolutionTest` files — `CrashReporter` resolves from the graph.
- [ ] 7.6 iosTest smoke (kotlin.test, NOT Kotest on K/N) for the iosMain actual; run `:mobile:app:iosSimulatorArm64Test`.
- [ ] 7.7 **Extend** `lint/detekt-rules/.../VendorSdkLeakageScanTest.kt` (today it scans only `core/domain`,`core/data`,`backend/ktor` for supabase/lettuce/firebase — `mobile/app/src` and `io.sentry.` are NOT covered, so the current scan is inert for this change). Add a **Sentry-prefix-scoped** check that walks `mobile/app/src` (plus core/backend) for `import io.sentry.`; do NOT retroactively apply the server-side prefixes to `mobile/app/src` (legitimate mobile client SDK imports differ). Assert green — `:mobile:app` reaches Sentry only via the `:infra:sentry` interface.
- [ ] 7.8 commonTest: `captureException(throwable, context)` forwards the throwable + release/environment tags to the reporter (capturing fake from 7.1), covering the Android/iOS crash-capture spec scenarios at the wiring level. Note: the native auto-capture + OS crash layer itself is exercised by the verify-loop device bring-up (8.4), not unit-testable (docs/11 §5.1 — explicit residue, with operator buy-in via this note).

## 8. Verification & Definition of Done

- [ ] 8.1 `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` green locally.
- [ ] 8.2 `:mobile:app:testDevDebugUnitTest` + `:mobile:app:testDevReleaseUnitTest` green.
- [ ] 8.3 `:mobile:app:iosSimulatorArm64Test` (or at minimum `linkDebugFrameworkIosSimulatorArm64`) green locally.
- [ ] 8.4 **verify-loop bring-up** — observe startup init on a device surface (context-routed: cloud → `scripts/run_on_device.sh` / `device-run.yml`; local → emulator + iOS simulator); attach screenshot/console evidence to the PR body (docs/11 §5 DoD).

## 9. Docs, PR & lifecycle

- [ ] 9.1 File a `follow-up` issue to refresh the docs/04 § Sentry KMP `expect object SentryProvider` snippet to the §2.5 interface form (non-behavioral reconciliation; not a gate on this PR).
- [ ] 9.2 At the first feat commit, retitle the PR `feat(mobile): mobile crash reporting (Sentry KMP)` and refresh the body per the docs/11 phase-boundary rule.
- [ ] 9.3 At archive: `openspec validate --specs mobile-crash-reporting --strict` green; move the change under `archive/`.
