## Why

The mobile app ships to production blind to crashes: `:infra:sentry` is the only module flagged **SCAFFOLD NEXT** in `project.md`, and `DiagnosticSink`'s default sink literally drops in release builds *"until a Sentry/OTel sink lands."* With the mobile-first critical path demo-complete (the `project.md` flip trigger has fired) and `crash` consent already captured by the shipped analytics-consent screen, crash + error reporting is launch-critical observability with a reserved seam already waiting for it.

## What Changes

- Add a new KMP module **`:infra:sentry`** exposing a vendor-SDK-free **`CrashReporter` interface** in commonMain (`init` / `captureException` / `setUser` / `clearUser` / `addBreadcrumb` / `close`) with Android + iOS actuals binding the **Sentry Kotlin Multiplatform SDK** (`getsentry/sentry-kotlin-multiplatform`). The vendor SDK is fenced inside `:infra:sentry` (invariant #16) and `implementation`-scoped so it never reaches `:mobile:app`'s compile classpath (mirrors the `:infra:supabase-realtime` seam, docs/11 §2.6).
- Initialize crash reporting at app startup with a **flavor-aware DSN + environment** (`dev` / `staging` / `production`), bound through the existing Koin platform modules; tag every event with release + environment.
- **Consent-gate** per docs/06 § Analytics & Tracking Consent → Enforcement: `crash` defaults ON (opt-out); on decline, `CrashReporter.close()` for the session; re-enable on re-consent. Consumes the `crash` flag already persisted by `consent/ConsentFlow.kt` — no new consent plumbing.
- **Correlate** crashes to the signed-in user via `setUser` (the JWT `sub`) on auth, `clearUser` on logout.
- **Scrub PII**: init with `sendDefaultPii = false` (no client-IP `{{auto}}` inference, no `server_name`) plus a `beforeSend` hook mirroring the coordinate-free discipline (the `CoordinateMaskingLogger` helper is `private` to `:mobile:app` — pattern-reuse, not an import; cf. `:infra:otel`'s `ForbiddenAttributeStripper`): no IP, coordinates, tokens, or post bodies in crash payloads.
- Land a real Sentry breadcrumb sink into the **existing `DiagnosticSink` Koin seam**, replacing `ConsoleDiagnosticSink`'s release drop-in (anti-patchwork: reuse the reserved seam, do not fork a parallel diagnostics path).
- Add the **symbol/mapping upload** Gradle config (Android ProGuard mapping + `uploadSentrySymbols`; iOS dSYM build phase). **BREAKING for CI only — partial:** the `.github/workflows/**` half is agent-hook-blocked, so this change ships only the Gradle-side config; the workflow YAML is handed to the operator (documented in tasks.md).
- **Deferred, captured as explicit spec requirements (not just prose):** backend Sentry-Java error capture is **out of scope** (keeps this change off `:backend:ktor`, where #291/#292 are in flight; the backend already has OTel for traces); iOS dSYM Fastlane automation stays out unless a build phase proves insufficient.

## Capabilities

### New Capabilities
- `mobile-crash-reporting`: Consent-gated, PII-scrubbed mobile crash + error reporting on Android + iOS via the `:infra:sentry` `CrashReporter` interface — startup init with flavor-aware DSN/environment, user correlation on auth, breadcrumb capture through the existing `DiagnosticSink` seam, and the explicit out-of-scope guard for backend error capture.

### Modified Capabilities
<!-- None. The `crash` consent flag is already captured + persisted by the shipped analytics-consent capability; this change consumes it without altering consent-capability requirements. Keeping zero modified specs also avoids archive-time conflicts on shared capability specs with concurrent sessions. -->

## Impact

- **New module**: `:infra:sentry` (KMP: commonMain interface + androidMain/iosMain actuals + commonTest). Touches `settings.gradle.kts`, `gradle/libs.versions.toml` (new Sentry KMP SDK pin → triggers the pre-implementation library re-check at `/opsx:apply`), `dev/module-descriptions.txt` + `dev/scripts/sync-readme.sh --write` (README module list).
- **`:mobile:app`**: Koin wiring (`di/MobileModule.kt`, androidMain/iosMain `di/PlatformModule.kt`), startup init (`App.kt` + `MainActivity.kt` / iOS entry), `DiagnosticSink` binding swap, consent-gate hook, `setUser`/`clearUser` on the auth path, `beforeSend` scrubbing.
- **No Flyway migration. No `:backend:ktor` changes.** Parallel-safe against all in-flight branches.
- **CI**: symbol-upload Gradle config in-change; workflow YAML deferred to the operator (hook-blocked).
- **Risk/contingency**: Sentry KMP SDK is pre-1.0 (v0.26.0) and "not fully tested for CMP" for advanced features (crash/error capture — our scope — is supported). Open Decision #17 (docs/08) governs the fallback: if the KMP SDK proves unstable, swap the `:infra:sentry` impl to a Crashlytics-Android + Sentry-Cocoa-iOS dual pipeline behind the same `CrashReporter` interface.
