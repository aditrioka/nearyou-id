# mobile-fcm-token-registration — Delta Specification

## ADDED Requirements

### Requirement: FcmTokenProvider is a platform-free commonMain seam with Koin-bound platform actuals

The mobile app SHALL ship a commonMain `interface FcmTokenProvider` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/push/FcmTokenProvider.kt`) that is Compose-free and platform-free — it SHALL reference NO Firebase / platform notification API (`FirebaseMessaging`, `Messaging`, `UNUserNotificationCenter`, `FirebaseMessagingService`). It SHALL expose:

- `suspend fun currentToken(): String?` — acquire the current device FCM registration token, or `null` when none is available (token not yet issued, or platform prerequisites unmet).
- `val tokenRefreshes: Flow<String>` — a stream that emits the new token whenever the platform SDK rotates it.

The Android actual (file under `mobile/app/src/androidMain/.../push/`) SHALL implement `currentToken()` via the `firebase-messaging` SDK and bridge `tokenRefreshes` from the SDK's `onNewToken` callback. The iOS actual (file under `mobile/app/src/iosMain/.../push/`) SHALL implement `currentToken()` via the `FirebaseMessaging` Pod (`Messaging.messaging().token`) and bridge `tokenRefreshes` from the messaging-delegate registration-token callback. The Firebase / platform SDK import SHALL appear ONLY in the platform source sets — never in commonMain (the "no vendor SDK import outside the platform / `:infra:*` boundary" invariant). Each actual SHALL be bound in its Koin platform module (the `LocationProvider` precedent). **Enforcement note:** the global `VendorSdkLeakageScanTest` (`:lint:detekt-rules`) scans only `core/domain`, `core/data`, and `backend/ktor` — NOT `mobile/app/src` — so it does NOT enforce this boundary for the mobile module. Mobile confinement SHALL therefore be test-enforced by a dedicated `androidUnitTest` source-guard (the shipped `LocationSourceGuardTest` / `PostCreationSourceGuardTest` idiom — walk the repo source tree, assert the forbidden token's absence in the disallowed source set), not by review alone.

#### Scenario: The commonMain interface references no platform SDK type

- **WHEN** inspecting `FcmTokenProvider.kt` in commonMain
- **THEN** it declares `currentToken()` and `tokenRefreshes` AND contains no reference to `FirebaseMessaging`, `Messaging`, `UNUserNotificationCenter`, or `FirebaseMessagingService`

#### Scenario: A source-guard test enforces Firebase confinement to the platform source sets

- **WHEN** the dedicated `FcmPushSourceGuardTest` (`androidUnitTest`, the `LocationSourceGuardTest` idiom) runs
- **THEN** it asserts that no `commonMain` push source file references `com.google.firebase` / `FirebaseMessaging` / `Messaging` / `UNUserNotificationCenter` AND that the Firebase SDK imports appear ONLY under `androidMain`/`iosMain` — failing if a future edit leaks the vendor SDK into common code

#### Scenario: A fake provider can drive both acquisition and refresh in tests

- **WHEN** a test substitutes a `FakeFcmTokenProvider` that returns a fixed token from `currentToken()` and emits a new token on `tokenRefreshes`
- **THEN** the registrar consumes both without any platform dependency (the test runs in commonTest with no Android/iOS runtime)

### Requirement: FcmTokenApiClient posts the canonical registration request with Bearer auth attached by the shipped Auth plugin

The mobile app SHALL ship a `FcmTokenApiClient` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/push/FcmTokenApiClient.kt`) that issues `POST /api/v1/user/fcm-token` (the canonical endpoint; the path source of truth is the SHIPPED `backend/ktor/src/main/kotlin/id/nearyou/app/user/FcmTokenRoutes.kt`). The request body SHALL be JSON `{ "token": <string>, "platform": <string>, "app_version": <string|null> }` where:

- `token` is the value returned by `FcmTokenProvider`, trimmed, non-empty, and ≤ 4096 characters (the client SHALL guard these bounds before issuing the request — mirroring the backend `empty_token` / `token_too_long` validators — so a malformed value never round-trips into a 400 the silent registrar cannot surface).
- `platform` is exactly `"android"` or `"ios"` (matching the backend `CHECK (platform IN ('android','ios'))`), sourced as a compile-time constant in each platform actor — NEVER runtime-detected in commonMain.
- `app_version` is sourced from the existing build-config seam (the same mechanism `mobile-auth-signin` uses for the API base URL); when present it SHALL be ≤ 64 characters (the backend `app_version_too_long` bound).

The Bearer `Authorization` header SHALL be attached by the SHIPPED `HttpClient` `Auth` plugin — this capability MUST NOT reimplement Bearer attachment or token refresh.

#### Scenario: First registration sends the canonical body with the platform constant

- **WHEN** the client registers token `"tok-abc"` with `app_version = "1.4.0"` on Android (via Ktor `MockEngine`)
- **THEN** the captured request is `POST /api/v1/user/fcm-token` with JSON body whose `token` = `"tok-abc"`, `platform` = `"android"`, and `app_version` = `"1.4.0"` AND no Bearer header is set by the client itself (the `Auth` plugin owns it)

#### Scenario: An over-length token is rejected client-side before any request

- **WHEN** the client is asked to register a 4097-character token
- **THEN** no HTTP request is issued AND the outcome is exactly `Rejected(token_too_long)` — the oversize value never reaches the wire

#### Scenario: An empty-after-trim token is rejected client-side before any request

- **WHEN** the client is asked to register a non-null token that is empty or whitespace-only after trimming
- **THEN** no HTTP request is issued AND the outcome is exactly `Rejected(empty_token)` — a blank token never round-trips into the backend `empty_token` 400

#### Scenario: An over-length app_version is rejected client-side before any request

- **WHEN** the client is asked to register with an `app_version` longer than 64 characters
- **THEN** no HTTP request is issued AND the outcome is exactly `Rejected(app_version_too_long)`

#### Scenario: A null app_version is omitted from the canonical body

- **WHEN** the client registers a valid token whose `app_version` resolves to `null` from the build-config seam
- **THEN** the request is issued with the canonical body, and `app_version` is **omitted** from the JSON (the shared `HttpClient` `Json` uses `explicitNulls = false`, so a null field is serialized as absent — the backend's nullable `app_version` column accepts the absence; behaviorally equivalent to a JSON `null`)

### Requirement: Registration responses map to a closed FcmRegistrationOutcome vocabulary

`FcmTokenApiClient` SHALL map every backend response to exactly one sealed `FcmRegistrationOutcome` value, with NO generic fallthrough:

- **HTTP 204** → `Registered`.
- **HTTP 400** → `Rejected(code)` where `code` is the backend's closed error vocabulary parsed from the JSON `{"error": …}` body: `malformed_body` / `invalid_platform` / `empty_token` / `token_too_long` / `app_version_too_long`. An unrecognized 400 error string maps to a single explicit `Rejected(unknown)` — not a crash and not silently dropped.
- **HTTP 401** → `Unauthorized`. Because the shipped `HttpClient` `Auth` plugin already owns one refresh-and-retry on a 401 (and invalidates the session on refresh failure), a 401 surfacing to this client means the plugin's refresh has ALREADY failed (the session is being torn down) — so the client SHALL NOT itself retry-loop; the next session-active trigger re-attempts after re-authentication.
- **Any transport/IO failure or other non-2xx** → `TransportError`.

#### Scenario: 204 maps to Registered

- **WHEN** the backend responds `204 No Content`
- **THEN** the outcome is `Registered`

#### Scenario: Each documented 400 code maps to its Rejected variant

- **WHEN** the backend responds `400` with body `{"error":"invalid_platform"}` (and, in separate cases, each of `malformed_body` / `empty_token` / `token_too_long` / `app_version_too_long`)
- **THEN** the outcome is `Rejected(invalid_platform)` (respectively the matching `Rejected(code)` for each) AND no other outcome is produced

#### Scenario: An unrecognized 400 error code maps to Rejected(unknown)

- **WHEN** the backend responds `400` with a body whose `error` value is not in the documented vocabulary (e.g. `{"error":"some_future_code"}`) or has no parseable `error` field
- **THEN** the outcome is exactly `Rejected(unknown)` — no crash, no silent drop, no other variant

#### Scenario: 401 maps to Unauthorized without a retry loop

- **WHEN** the backend responds `401` (the `Auth` plugin's refresh has already failed)
- **THEN** the outcome is `Unauthorized` AND the client issues no immediate re-request (re-attempt is deferred to the next session-active trigger)

### Requirement: FcmTokenRegistrar acquires and registers the token on session-active transitions

The mobile app SHALL ship a Compose-free `FcmTokenRegistrar` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/push/FcmTokenRegistrar.kt`), a stateless Koin singleton with `suspend fun registerCurrentToken(): FcmRegistrationOutcome` that: reads `FcmTokenProvider.currentToken()`; if non-null, registers it via `FcmTokenApiClient` and returns the outcome; if `null`, returns a `NoTokenAvailable` outcome without issuing a request. The registrar SHALL be invoked on every documented session-active transition (`docs/04-Architecture.md` § FCM Token Registration, "Client must re-register when: the app first opens after install; the FCM token-refresh SDK callback fires; the user logs out + re-logs in"):

- **Authenticated app-shell entry** — a single `LaunchedEffect` in the authenticated section shell (`AppShellScreen`, the `HomeRoute` entry) where every authenticated path converges: cold-start-with-token (`RootRoute` → `HomeRoute`), fresh sign-in (`SignInRoute` → `replaceAll(HomeRoute)`), and signup (`AgeGateRoute` → `HomeRoute`). One hook fires once per authenticated entry, covering the doc's "first open after install" / "logs out + re-logs in" / "app is reinstalled" triggers (a reinstall yields a fresh token registered on the first authenticated open, or via the refresh stream below).
- **Token rotation** — the SDK token-refresh callback, handled by the separate token-rotation requirement below.

The single shell hook realizes all four doc triggers (`docs/04-Architecture.md` line 469: install / refresh / re-login / reinstall); the convergence is intentional, not a coverage gap. The registrar SHALL NOT be wired into `AuthRepository` / `RootRouterScreen` internals (no auth↔push coupling).

The invocation SHALL be fire-and-forget on a non-blocking scope so registration NEVER delays navigation. A `TransportError` SHALL be swallowed (logged non-confidentially) and naturally retried on the next trigger — no bespoke backoff machinery. **Concurrency posture:** the registrar does NOT guard against concurrent/overlapping `registerCurrentToken()` invocations (e.g. a cold-start trigger overlapping a token-refresh emission). Concurrent or duplicate registrations of the same token are intentionally permitted and harmless — the backend upserts on `(user_id, platform, token)` (idempotent, just refreshes `last_seen_at`). This is a deliberate decision (relying on backend idempotency) rather than an unconsidered gap; no client-side mutex/dedup is introduced.

#### Scenario: Acquire-then-register happy path

- **GIVEN** a `FakeFcmTokenProvider` returning `"tok-1"` and a `FakeFcmTokenApiClient` returning `Registered`
- **WHEN** `registerCurrentToken()` is invoked
- **THEN** the api client received `"tok-1"` exactly once AND the returned outcome is `Registered`

#### Scenario: Null token short-circuits without a request

- **GIVEN** a provider whose `currentToken()` returns `null`
- **WHEN** `registerCurrentToken()` is invoked
- **THEN** no registration request is issued AND the outcome is `NoTokenAvailable`

#### Scenario: iOS denied-authorization yields the NoTokenAvailable outcome end-to-end

- **GIVEN** a provider modelling iOS denied notification authorization (`currentToken()` returns `null` per the iOS-authorization requirement)
- **WHEN** `registerCurrentToken()` is invoked
- **THEN** the outcome is `NoTokenAvailable` AND no registration request is issued — tying the iOS denied-auth → null-token → `NoTokenAvailable` chain together at the registrar level (not only at the provider level)

#### Scenario: Concurrent registrations of the same token are permitted (no client-side dedup)

- **GIVEN** a provider returning `"tok-1"` and an api client recording each call
- **WHEN** `registerCurrentToken()` is invoked while a prior `registerCurrentToken()` / token-refresh registration for the same token is still in flight
- **THEN** both registrations are allowed to proceed (no exception, no client-side mutex) — duplicate POSTs are tolerated because the backend upsert is idempotent

#### Scenario: TransportError is swallowed and re-attempted on the next trigger

- **GIVEN** the api client returns `TransportError` on the first call and `Registered` on the second
- **WHEN** `registerCurrentToken()` is invoked, then invoked again (simulating a later session-active trigger)
- **THEN** the first invocation does not throw (the failure is swallowed) AND the second invocation returns `Registered`

### Requirement: Token rotation re-registers the new token

The `FcmTokenRegistrar` SHALL collect `FcmTokenProvider.tokenRefreshes` for the app's lifetime (started once at app scope) and register each emitted token via `FcmTokenApiClient`. Re-registration is idempotent at the backend (upsert on `(user_id, platform, token)` refreshes `last_seen_at`), so a repeated token is a cheap refresh, not an error.

#### Scenario: A refreshed token is registered

- **GIVEN** the registrar is observing `tokenRefreshes` and the user is authenticated
- **WHEN** the provider emits a new token `"tok-2"`
- **THEN** the api client receives `"tok-2"` for registration

### Requirement: No registration is attempted while unauthenticated

The registrar SHALL NOT issue a registration request when there is no active session. The `/api/v1/user/fcm-token` endpoint is JWT-gated; without a session there is no Bearer token to attach and the call would 401. **Mechanism (structural):** both the acquisition trigger and the token-refresh collector run from the authenticated shell's composition scope (`AppShellScreen`'s `LaunchedEffect`), which is composed ONLY behind the auth gate — so while signed out there is no shell, hence no registration coroutine running at all (no explicit session-presence check is needed because no code path runs). On sign-out the shell leaves composition and the refresh collector is cancelled; on the next sign-in the shell re-composes and acquisition + the collector restart.

#### Scenario: A token refresh while signed out does not POST

- **GIVEN** no active session (the app is on the unauthenticated surface), so the authenticated shell — and therefore the refresh collector — is not composed
- **WHEN** the provider rotates the token
- **THEN** no registration request is issued (no collector is running to observe it) — the token is registered on the next authenticated shell entry instead

### Requirement: iOS requests only the minimal notification authorization that token acquisition structurally requires

On iOS, because `Messaging.messaging().token` resolves only after APNs registration, the iOS `FcmTokenProvider` actual SHALL request notification authorization (`UNUserNotificationCenter.requestAuthorization`) and call `registerForRemoteNotifications()` as the platform-mandated prerequisite for obtaining a token. This is the token-acquisition minimum ONLY — this capability SHALL NOT implement the contextual, product-level "before we notify you" permission rationale UX (that is deferred to the consuming chat surface). If iOS authorization is denied, `currentToken()` SHALL return `null` (no token obtainable) and the registrar SHALL short-circuit per the `NoTokenAvailable` path.

#### Scenario: Denied iOS authorization yields no token, no crash

- **WHEN** iOS notification authorization is denied
- **THEN** `currentToken()` returns `null` AND the registrar issues no request AND the app does not crash

### Requirement: Android requests no notification permission for token acquisition

On Android, FCM token issuance does NOT require the `POST_NOTIFICATIONS` runtime permission (the permission gates notification *display*, not token issuance). The Android `FcmTokenProvider` actual SHALL acquire and register the token WITHOUT requesting `POST_NOTIFICATIONS`; the runtime-permission prompt timing is left entirely to the consuming feature (the chat screen). This change SHALL NOT add a `POST_NOTIFICATIONS` request flow.

#### Scenario: Android registers the token without a permission prompt

- **WHEN** the Android provider acquires a token on a device where `POST_NOTIFICATIONS` has not been granted
- **THEN** a non-null token is obtainable and registered AND no `POST_NOTIFICATIONS` runtime prompt is shown by this capability

### Requirement: The raw FCM token is never written to a log sink

No code path in this capability SHALL log the raw FCM token value (or a substring/prefix/hash of it) — the token is a device-addressed credential (mirroring backend D11). Diagnostic logging SHALL use a token-free shape (e.g. `event=fcm_token_registered platform={} outcome={}`). The guard SHALL cover the full log-sink set across ALL push source files — `FcmTokenRegistrar.kt`, `FcmTokenApiClient.kt`, and both platform actuals — for the sink vocabulary the shipped `LocationSourceGuardTest` already enumerates (`Log.`, `println`, `print(`, `NSLog`, `os_log`, `Napier`, `Timber`), NOT just the registrar. Additionally, the shared `HttpClient` `Logging` plugin level SHALL NOT be `LogLevel.BODY` / `LogLevel.ALL` on the path that carries the registration request (those levels would log the request body — hence the token — to the log sink); the guard SHALL assert this.

#### Scenario: No push source file logs the token through any sink

- **WHEN** the no-token-in-logs guard runs across `FcmTokenRegistrar.kt`, `FcmTokenApiClient.kt`, and both platform actuals
- **THEN** no enumerated log sink (`Log.` / `println` / `print(` / `NSLog` / `os_log` / `Napier` / `Timber`) receives the token value AND the `HttpClient` `Logging` level on the registration path is not `BODY`/`ALL` AND the emitted diagnostic line contains the platform + outcome but not the token

### Requirement: Koin resolves the FCM registration graph

The new types SHALL be registered in Koin: `FcmTokenProvider` (platform actual in each platform module), `FcmTokenApiClient` and `FcmTokenRegistrar` (common module singletons). The registrar SHALL resolve with its provider + api-client dependencies satisfied.

#### Scenario: The registrar resolves from the Koin graph

- **WHEN** a Koin-resolution test requests `FcmTokenRegistrar` with the platform module providing a fake `FcmTokenProvider`
- **THEN** the registrar resolves with its `FcmTokenProvider` and `FcmTokenApiClient` dependencies bound (no missing-definition error)

### Requirement: Push message display and handling are deferred

This capability stops at token registration. Android data-only local notification rendering (with the user-preference check) and the iOS Notification Service Extension body rewrite (`docs/04-Architecture.md` § 459–501) are explicitly OUT OF SCOPE and SHALL NOT be implemented here; they are tracked for the follow-up `mobile-push-message-handling` change. No incoming-message handler SHALL be added by this change beyond the token-refresh bridge.

#### Scenario: No push-display handler ships in this change

- **WHEN** inspecting this change's diff
- **THEN** there is no incoming-push-message display/handling code (no local-notification builder, no NSE) — only token acquisition, registration, and the token-refresh bridge — AND the deferral is tracked by the `mobile-push-message-handling` follow-up GitHub issue ([#256](https://github.com/aditrioka/nearyou-id/issues/256))

### Requirement: The contextual notification-permission prompt UX is deferred to the consuming feature

The product-level notification-permission rationale + prompt timing (e.g. the chat screen's "on first send" prompt) is OUT OF SCOPE. This capability owns only the iOS token-acquisition authorization minimum (above); it SHALL NOT add an Android `POST_NOTIFICATIONS` prompt or an in-app permission-rationale screen. The consuming chat change is the MODIFY hook for the contextual prompt.

#### Scenario: No contextual permission UX ships in this change

- **WHEN** inspecting this change's diff
- **THEN** no in-app notification-permission rationale screen and no Android `POST_NOTIFICATIONS` runtime-prompt flow are added AND the deferral is tracked by a `follow-up` GitHub issue referencing the chat surface ([#257](https://github.com/aditrioka/nearyou-id/issues/257))

### Requirement: Code builds and unit-tests without the operator Firebase client config; live verification is gated on it

The registrar, provider interface, api client, and their fakes SHALL build and pass unit tests WITHOUT the Firebase client config files (`google-services.json` per flavor; `GoogleService-Info.plist`) present — the commonTest surface uses `FakeFcmTokenProvider` + a Ktor `MockEngine`-backed api client and depends on no live Firebase initialization. Live end-to-end verification (real device token → real `204`) is gated on the operator placing the config files (tied to the separate staging Firebase project per `docs/04-Architecture.md` § 259) and SHALL be recorded as a manual-verification step, not a blocking automated gate.

#### Scenario: The unit-test surface needs no Firebase config

- **WHEN** the mobile unit tests run in CI with no `google-services.json` / `GoogleService-Info.plist` present
- **THEN** the `FcmTokenRegistrar` / `FcmTokenApiClient` tests pass (they exercise fakes + `MockEngine`, never live Firebase)

### Requirement: Test coverage for the registration lifecycle

The change SHALL include: a Compose-free `FcmTokenRegistrarTest` (acquire→register happy path; `NoTokenAvailable` on null token; the iOS-denied→`NoTokenAvailable` chain; token-refresh re-registration; `TransportError` swallowed + re-attempt; no-register-while-unauthenticated with the session-presence check; concurrent-registration tolerance); a `FcmTokenApiClientTest` over Ktor `MockEngine` (canonical body shape incl. platform constant + present/null `app_version`; the 204→`Registered`, each documented 400→`Rejected(code)`, unknown-400→`Rejected(unknown)`, 401→`Unauthorized`, transport→`TransportError` mappings; the client-side guards: over-length token→`Rejected(token_too_long)`, empty-after-trim→`Rejected(empty_token)`, over-length app_version→`Rejected(app_version_too_long)`, each issuing NO request); a Koin-resolution test for the new bindings; the `FcmPushSourceGuardTest` (commonMain Firebase-confinement); and the no-token-in-logs guard across all push files.

#### Scenario: The registrar and api-client test suites cover the outcome matrix

- **WHEN** the test suite runs
- **THEN** every `FcmRegistrationOutcome` variant (`Registered`, each `Rejected(code)` including `Rejected(unknown)`, `Unauthorized`, `TransportError`, `NoTokenAvailable`) is asserted by at least one scenario AND the `FcmPushSourceGuardTest` and the no-token-in-logs guard pass
