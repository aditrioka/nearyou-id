## ADDED Requirements

### Requirement: Mobile crash and error reporting via a vendor-neutral interface
The mobile app SHALL report native crashes and unhandled exceptions on Android and iOS through a vendor-SDK-free `CrashReporter` interface defined in a new `:infra:sentry` KMP module. The Sentry Kotlin Multiplatform SDK MUST be confined to `:infra:sentry` and depended on with `implementation` scope so it never reaches `:mobile:app`'s compile classpath (invariant #16).

#### Scenario: Unhandled exception on Android is reported
- **WHEN** an unhandled exception propagates to the top of an Android thread with crash reporting active
- **THEN** the `CrashReporter` actual captures it and the Sentry SDK reports the event with release + environment tags

#### Scenario: Native crash on iOS is reported
- **WHEN** the iOS app experiences a native crash with crash reporting active
- **THEN** the iOS `CrashReporter` actual (Sentry Cocoa under the SDK) captures and reports it on next launch

#### Scenario: Vendor SDK is fenced inside :infra:sentry
- **WHEN** `:mobile:app` is compiled
- **THEN** the Sentry SDK is NOT on `:mobile:app`'s compile classpath, and `:mobile:app` references only the `CrashReporter` interface (verified by the vendor-SDK-leakage scan)

### Requirement: Flavor-aware initialization with release and environment tags
The app SHALL initialize crash reporting at startup before application code can crash, resolving `environment` from the build flavor (`dev` / `staging` / `production`) and `release` from the app version. A missing or blank DSN MUST safely no-op initialization rather than crash the app.

#### Scenario: Staging build initializes with the staging environment
- **WHEN** the app starts in the `staging` flavor
- **THEN** crash reporting initializes with `environment = staging` and `release` set to the app version

#### Scenario: Blank DSN no-ops safely
- **WHEN** the app starts with no DSN configured for the flavor
- **THEN** initialization is skipped without throwing, and the app continues normally

### Requirement: Consent gating honors the crash consent category (opt-out default)
Crash reporting SHALL be gated on `users.analytics_consent.crash`, which defaults ON (opt-out). The app MUST consume the `crash` flag already captured by the consent flow without adding new consent plumbing. On decline the app MUST stop reporting for the session; on re-consent it MUST resume.

#### Scenario: Default consent keeps reporting active
- **WHEN** the user has not declined crash consent
- **THEN** crash reporting is active (opt-out default ON)

#### Scenario: Declining crash consent stops reporting immediately
- **WHEN** the user turns the crash toggle OFF in consent settings
- **THEN** the app calls `CrashReporter.close()` for the session and no further crash events are sent

#### Scenario: Re-enabling crash consent resumes reporting
- **WHEN** the user turns the crash toggle back ON
- **THEN** the app re-initializes the `CrashReporter` and crash events are sent again

#### Scenario: Cold start honors last-known decline
- **WHEN** the app cold-starts and the last-known `crash` consent is declined
- **THEN** reporting initializes (opt-out default) and is closed on the first consent read, so no events are sent

### Requirement: User correlation uses only an opaque identifier
On authentication the app SHALL associate crash events with the signed-in user using only the opaque JWT `sub`; it MUST NOT send username, email, or display name. On logout the association MUST be cleared.

#### Scenario: Sign-in sets an opaque user id
- **WHEN** the user signs in
- **THEN** `CrashReporter.setUser` is called with the opaque JWT `sub` and no username/email/display-name is attached

#### Scenario: Logout clears the user
- **WHEN** the user logs out
- **THEN** `CrashReporter.clearUser` is called and subsequent events carry no user id

### Requirement: Crash payloads are scrubbed of PII
A `beforeSend` hook SHALL strip or redact coordinates, auth tokens, and free-text post/chat bodies from outgoing event payloads (message, breadcrumbs, extras), composing with the existing coordinate-free / `CoordinateMaskingLogger` discipline rather than a parallel redactor.

#### Scenario: An event carrying a coordinate is scrubbed
- **WHEN** an event or breadcrumb payload contains a coordinate or a token
- **THEN** `beforeSend` redacts it before the event leaves the device

### Requirement: Repository diagnostics surface as breadcrumbs through the existing sink
The change SHALL route repository diagnostics into Sentry breadcrumbs through the existing `DiagnosticSink` Koin seam, replacing `ConsoleDiagnosticSink`'s release drop-in. It MUST NOT introduce a parallel diagnostics path. Breadcrumb content MUST remain coordinate-free per the sink's contract.

#### Scenario: A repository diagnostic becomes a breadcrumb
- **WHEN** a repository logs a diagnostic (e.g., `nearby_network_error`) in a release build
- **THEN** it is recorded as a Sentry breadcrumb via the `DiagnosticSink` binding (no longer dropped)

#### Scenario: Breadcrumbs stay coordinate-free
- **WHEN** any diagnostic is surfaced as a breadcrumb
- **THEN** it carries only pre-redacted status/outcome strings, never a coordinate or token

### Requirement: Symbolication artifacts are configured; CI upload is operator-provisioned
The change SHALL add the Gradle-side configuration to produce and upload symbolication artifacts (Android ProGuard/R8 mapping; iOS dSYM). The CI workflow invocation and `SENTRY_AUTH_TOKEN` secret are operator-provisioned (agent-hook-blocked) and tracked as an explicit task. Crash reporting MUST function (unsymbolicated) before the operator wires the upload.

#### Scenario: Release build configures mapping/dSYM upload
- **WHEN** a release build runs with the operator's upload step + token present
- **THEN** the Sentry Gradle/Xcode configuration uploads the ProGuard mapping and dSYM for symbolication

#### Scenario: Reporting works before symbol upload is wired
- **WHEN** the operator CI upload step is not yet configured
- **THEN** crashes still report (unsymbolicated), and the operator task to wire upload remains tracked

### Requirement: Backend error capture is out of scope (deferred)
This change SHALL NOT add Sentry error capture to `:backend:ktor`. Backend error reporting remains served by the existing OpenTelemetry tracing; Sentry-Java backend integration is deferred and MUST be tracked as a follow-up so the gap is explicit, not silent.

#### Scenario: No Sentry in the backend
- **WHEN** this change is implemented
- **THEN** `:backend:ktor` contains no Sentry SDK dependency or initialization

#### Scenario: Deferral is tracked
- **WHEN** this change is implemented
- **THEN** a `follow-up` issue exists capturing backend Sentry-Java error capture as deferred scope
