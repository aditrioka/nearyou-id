# mobile-fcm-token-registration — Delta Specification

## ADDED Requirements

### Requirement: FcmTokenProvider is a platform-free commonMain seam with Koin-bound platform actuals

The mobile app SHALL ship a commonMain `interface FcmTokenProvider` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/push/FcmTokenProvider.kt`) that is Compose-free and platform-free — it SHALL reference NO Firebase / platform notification API (`FirebaseMessaging`, `Messaging`, `UNUserNotificationCenter`, `FirebaseMessagingService`). It SHALL expose:

- `suspend fun currentToken(): String?` — acquire the current device FCM registration token, or `null` when none is available (token not yet issued, or platform prerequisites unmet).
- `val tokenRefreshes: Flow<String>` — a stream that emits the new token whenever the platform SDK rotates it.

The Android actual (file under `mobile/app/src/androidMain/.../push/`) SHALL implement `currentToken()` via the `firebase-messaging` SDK and bridge `tokenRefreshes` from the SDK's `onNewToken` callback. The iOS actual (file under `mobile/app/src/iosMain/.../push/`) SHALL implement `currentToken()` via the `FirebaseMessaging` Pod (`Messaging.messaging().token`) and bridge `tokenRefreshes` from the messaging-delegate registration-token callback. The Firebase / platform SDK import SHALL appear ONLY in the platform source sets — never in commonMain (the "no vendor SDK import outside the platform / `:infra:*` boundary" invariant). Each actual SHALL be bound in its Koin platform module (the `LocationProvider` precedent).

#### Scenario: The commonMain interface references no platform SDK type

- **WHEN** inspecting `FcmTokenProvider.kt` in commonMain
- **THEN** it declares `currentToken()` and `tokenRefreshes` AND contains no reference to `FirebaseMessaging`, `Messaging`, `UNUserNotificationCenter`, or `FirebaseMessagingService`

#### Scenario: A fake provider can drive both acquisition and refresh in tests

- **WHEN** a test substitutes a `FakeFcmTokenProvider` that returns a fixed token from `currentToken()` and emits a new token on `tokenRefreshes`
- **THEN** the registrar consumes both without any platform dependency (the test runs in commonTest with no Android/iOS runtime)

### Requirement: FcmTokenApiClient posts the canonical registration request with Bearer auth attached by the shipped Auth plugin

The mobile app SHALL ship a `FcmTokenApiClient` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/data/push/FcmTokenApiClient.kt`) that issues `POST /api/v1/user/fcm-token` (the canonical endpoint; the path source of truth is the SHIPPED `backend/ktor/src/main/kotlin/id/nearyou/app/user/FcmTokenRoutes.kt`). The request body SHALL be JSON `{ "token": <string>, "platform": <string>, "app_version": <string|null> }` where:

- `token` is the value returned by `FcmTokenProvider`, trimmed, non-empty, and ≤ 4096 characters (the client SHALL guard these bounds before issuing the request — mirroring the backend `empty_token` / `token_too_long` validators — so a malformed value never round-trips into a 400 the silent registrar cannot surface).
- `platform` is exactly `"android"` or `"ios"` (matching the backend `CHECK (platform IN ('android','ios'))`), sourced as a compile-time constant in each platform actor — NEVER runtime-detected in commonMain.
- `app_version` is sourced from the existing build-config seam (the same mechanism `mobile-auth-signin` uses for the API base URL); when present it SHALL be ≤ 64 characters (the backend `app_version_too_long` bound).

The Bearer `Authorization` header SHALL be attached by the SHIPPED `HttpClient` `Auth` plugin — this capability MUST NOT reimplement Bearer attachment or token refresh.

#### Scenario: First registration sends the canonical body with the platform constant

- **WHEN** the client registers token `"tok-abc"` with `app_version = "1.4.0"` on Android (via Ktor `MockEngine`)
- **THEN** the captured request is `POST /api/v1/user/fcm-token` with JSON body whose `token` = `"tok-abc"`, `platform` = `"android"`, and `app_version` = `"1.4.0"` AND no Bearer header is set by the client itself (the `Auth` plugin owns it)

#### Scenario: An over-length token is rejected client-side before any request

- **WHEN** the client is asked to register a 4097-character token
- **THEN** no HTTP request is issued AND the outcome is a client-side rejection (mapped to `Rejected(token_too_long)` or an equivalent guard) — the oversize value never reaches the wire

### Requirement: Registration responses map to a closed FcmRegistrationOutcome vocabulary

`FcmTokenApiClient` SHALL map every backend response to exactly one sealed `FcmRegistrationOutcome` value, with NO generic fallthrough:

- **HTTP 204** → `Registered`.
- **HTTP 400** → `Rejected(code)` where `code` is the backend's closed error vocabulary parsed from the JSON `{"error": …}` body: `malformed_body` / `invalid_platform` / `empty_token` / `token_too_long` / `app_version_too_long`. An unrecognized 400 error string maps to a single explicit `Rejected(unknown)` — not a crash and not silently dropped.
- **HTTP 401** → `Unauthorized` (the registrar treats this as "session not valid yet / refresh in flight" and does not retry-loop; the next session-active trigger re-attempts).
- **Any transport/IO failure or other non-2xx** → `TransportError`.

#### Scenario: 204 maps to Registered

- **WHEN** the backend responds `204 No Content`
- **THEN** the outcome is `Registered`

#### Scenario: Each documented 400 code maps to its Rejected variant

- **WHEN** the backend responds `400` with body `{"error":"invalid_platform"}` (and, in separate cases, each of `malformed_body` / `empty_token` / `token_too_long` / `app_version_too_long`)
- **THEN** the outcome is `Rejected(invalid_platform)` (respectively the matching `Rejected(code)` for each) AND no other outcome is produced

#### Scenario: 401 maps to Unauthorized without a retry loop

- **WHEN** the backend responds `401`
- **THEN** the outcome is `Unauthorized` AND the client issues no immediate re-request (re-attempt is deferred to the next session-active trigger)

### Requirement: FcmTokenRegistrar acquires and registers the token on session-active transitions

The mobile app SHALL ship a Compose-free `FcmTokenRegistrar` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/push/FcmTokenRegistrar.kt`), a stateless Koin singleton with `suspend fun registerCurrentToken(): FcmRegistrationOutcome` that: reads `FcmTokenProvider.currentToken()`; if non-null, registers it via `FcmTokenApiClient` and returns the outcome; if `null`, returns a `NoTokenAvailable` outcome without issuing a request. The registrar SHALL be invoked on every documented session-active transition (`docs/04-Architecture.md` § FCM Token Registration, "Client must re-register when: the app first opens after install; the FCM token-refresh SDK callback fires; the user logs out + re-logs in"):

- **App first open after sign-in** — the cold-start path where a persisted `TokenPair` routes to `HomeRoute` (the `RootRouterScreen` / `AuthRepository.isAuthenticated()` seam).
- **Fresh sign-in success** — the `AuthRepository` HTTP-200 sign-in path.

The invocation SHALL be fire-and-forget on a non-blocking scope so registration NEVER delays navigation. A `TransportError` SHALL be swallowed (logged non-confidentially) and naturally retried on the next trigger — no bespoke backoff machinery.

#### Scenario: Acquire-then-register happy path

- **GIVEN** a `FakeFcmTokenProvider` returning `"tok-1"` and a `FakeFcmTokenApiClient` returning `Registered`
- **WHEN** `registerCurrentToken()` is invoked
- **THEN** the api client received `"tok-1"` exactly once AND the returned outcome is `Registered`

#### Scenario: Null token short-circuits without a request

- **GIVEN** a provider whose `currentToken()` returns `null`
- **WHEN** `registerCurrentToken()` is invoked
- **THEN** no registration request is issued AND the outcome is `NoTokenAvailable`

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

The registrar SHALL NOT issue a registration request when there is no active session (no persisted `TokenPair`). The `/api/v1/user/fcm-token` endpoint is JWT-gated; without a session there is no Bearer token to attach and the call would 401. Session-active triggers are the ONLY registration entry points; the registrar SHALL NOT register from the unauthenticated `SignInScreen` state.

#### Scenario: A token refresh while signed out does not POST

- **GIVEN** no active session (the app is on the unauthenticated surface)
- **WHEN** the provider emits a token refresh
- **THEN** no registration request is issued (the token is registered on the next session-active trigger instead)

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

No code path in this capability SHALL log the raw FCM token value (or a substring/prefix/hash of it) — the token is a device-addressed credential (mirroring backend D11). Diagnostic logging SHALL use a token-free shape (e.g. `event=fcm_token_registered platform={} outcome={}`).

#### Scenario: Registration logging omits the token

- **WHEN** `registerCurrentToken()` runs to completion (success or failure)
- **THEN** no logging call site receives the token value as an argument (asserted by a source/behavior guard test) AND the emitted diagnostic line contains the platform and outcome but not the token

### Requirement: Koin resolves the FCM registration graph

The new types SHALL be registered in Koin: `FcmTokenProvider` (platform actual in each platform module), `FcmTokenApiClient` and `FcmTokenRegistrar` (common module singletons). The registrar SHALL resolve with its provider + api-client dependencies satisfied.

#### Scenario: The registrar resolves from the Koin graph

- **WHEN** a Koin-resolution test requests `FcmTokenRegistrar` with the platform module providing a fake `FcmTokenProvider`
- **THEN** the registrar resolves with its `FcmTokenProvider` and `FcmTokenApiClient` dependencies bound (no missing-definition error)

### Requirement: Push message display and handling are deferred

This capability stops at token registration. Android data-only local notification rendering (with the user-preference check) and the iOS Notification Service Extension body rewrite (`docs/04-Architecture.md` § 459–501) are explicitly OUT OF SCOPE and SHALL NOT be implemented here; they are tracked for the follow-up `mobile-push-message-handling` change. No incoming-message handler SHALL be added by this change beyond the token-refresh bridge.

#### Scenario: No push-display handler ships in this change

- **WHEN** inspecting this change's diff
- **THEN** there is no incoming-push-message display/handling code (no local-notification builder, no NSE) — only token acquisition, registration, and the token-refresh bridge — AND the deferral is tracked by a `mobile-push-message-handling` follow-up GitHub issue

### Requirement: The contextual notification-permission prompt UX is deferred to the consuming feature

The product-level notification-permission rationale + prompt timing (e.g. the chat screen's "on first send" prompt) is OUT OF SCOPE. This capability owns only the iOS token-acquisition authorization minimum (above); it SHALL NOT add an Android `POST_NOTIFICATIONS` prompt or an in-app permission-rationale screen. The consuming chat change is the MODIFY hook for the contextual prompt.

#### Scenario: No contextual permission UX ships in this change

- **WHEN** inspecting this change's diff
- **THEN** no in-app notification-permission rationale screen and no Android `POST_NOTIFICATIONS` runtime-prompt flow are added AND the deferral is tracked by a `follow-up` GitHub issue referencing the chat surface

### Requirement: Code builds and unit-tests without the operator Firebase client config; live verification is gated on it

The registrar, provider interface, api client, and their fakes SHALL build and pass unit tests WITHOUT the Firebase client config files (`google-services.json` per flavor; `GoogleService-Info.plist`) present — the commonTest surface uses `FakeFcmTokenProvider` + a Ktor `MockEngine`-backed api client and depends on no live Firebase initialization. Live end-to-end verification (real device token → real `204`) is gated on the operator placing the config files (tied to the separate staging Firebase project per `docs/04-Architecture.md` § 259) and SHALL be recorded as a manual-verification step, not a blocking automated gate.

#### Scenario: The unit-test surface needs no Firebase config

- **WHEN** the mobile unit tests run in CI with no `google-services.json` / `GoogleService-Info.plist` present
- **THEN** the `FcmTokenRegistrar` / `FcmTokenApiClient` tests pass (they exercise fakes + `MockEngine`, never live Firebase)

### Requirement: Test coverage for the registration lifecycle

The change SHALL include: a Compose-free `FcmTokenRegistrarTest` (acquire→register happy path; `NoTokenAvailable` on null token; token-refresh re-registration; `TransportError` swallowed + re-attempt; no-register-while-unauthenticated); a `FcmTokenApiClientTest` over Ktor `MockEngine` (canonical body shape incl. platform constant + app_version; the 204→`Registered`, each 400→`Rejected(code)`, 401→`Unauthorized`, transport→`TransportError` mappings; the over-length-token client-side guard); a Koin-resolution test for the new bindings; and the no-token-in-logs guard.

#### Scenario: The registrar and api-client test suites cover the outcome matrix

- **WHEN** the test suite runs
- **THEN** every `FcmRegistrationOutcome` variant (`Registered`, each `Rejected(code)`, `Unauthorized`, `TransportError`, `NoTokenAvailable`) is asserted by at least one scenario AND the no-token-in-logs guard passes
