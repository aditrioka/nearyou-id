# mobile-age-gate Specification

## Purpose

The `mobile-age-gate` capability is the mobile signup-new-user flow: when a verified Google identity has no NearYouID account yet (backend `/signin` returns `404 user_not_found`), the app routes to `AgeGateScreen` — a date-of-birth gate that creates an account via `POST /api/v1/auth/signup` only for users 18 or older. It satisfies the platform's mandatory 18+ age gate (`docs/06-Security-Privacy.md` § Age Gate; UU PDP + PP 17/2025 child-protection compliance for a pseudonymous, location-based, stranger-chat app) while preserving the server's anti-DOB-shopping blocklist — the client never hard-blocks an under-18 DOB, so an honest under-18 attempt reaches the server and is permanently recorded (it cannot be retried with a fabricated older DOB). It owns `AgeGateScreen`, the `AuthRepository.signUpWithGoogle` orchestration, the HTTP-status-driven `SignUpOutcome` → user-facing-copy mapping (`201` Success / `403` Blocked / `409` AccountExists / `401` one-refresh-then-terminal / `5xx`·`503`·IO retryable, with NO generic fallthrough), and the routing that interjects the age gate between sign-in and Home.

## Requirements
### Requirement: AgeGateScreen renders the DOB picker and create-account surface

The mobile app SHALL ship a Voyager `Screen` implementation `AgeGateScreen` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/auth/AgeGateScreen.kt`) that renders the signup-new-user surface reached when sign-in reports no existing account. The screen SHALL display: (a) a screen title via `stringResource(Res.string.age_gate_title)`; (b) an explainer that states the 18+ minimum via `stringResource(Res.string.age_gate_explainer)` (satisfies the PP 17/2025 "clear minimum-age information" obligation per `docs/06-Security-Privacy.md` § Age Gate); (c) a date-of-birth field labelled via `stringResource(Res.string.age_gate_dob_label)` that opens a Material 3 `DatePicker`; (d) a primary create-account CTA via `stringResource(Res.string.cta_create_account)`. No hardcoded UI string literals SHALL appear in the screen source. The screen SHALL render under `NearYouTheme` and reuse the theme-aware brand-logo pattern consistent with `SignInScreen`/`HomeScreen`.

#### Scenario: Initial render shows title, DOB field, and create-account CTA

- **WHEN** a `commonTest` runs `runComposeUiTest { setContent { NearYouTheme { AgeGateScreen(<stub identity>).Content() } } }` against a fresh composition
- **THEN** the rendered tree contains a node whose text matches the runtime value of `stringResource(Res.string.age_gate_title)` AND a node whose text matches `stringResource(Res.string.age_gate_dob_label)` AND a clickable node whose text matches `stringResource(Res.string.cta_create_account)`

#### Scenario: No hardcoded UI strings in AgeGateScreen source

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/auth/AgeGateScreen.kt`
- **THEN** every `Text(...)` / `contentDescription = ...` / similar UI-string-bearing call site sources its text via `stringResource(Res.string.<name>)` (Compose Multiplatform Resources accessor); zero literal string arguments appear in such call sites

### Requirement: AgeGateScreen reuses the verified Google identity from the sign-in no-account path

`AgeGateScreen` SHALL be entered carrying the verified Google identity (the `id_token` from the `GoogleSignInResult.Success` that produced the backend `404 user_not_found`) so the signup call reuses it. `AgeGateScreen` (and the flow reaching it) SHALL NOT trigger a fresh `GoogleSignInClient.signIn()` ceremony on entry — the user MUST NOT see a second Google account sheet for one continuous registration. The carried `id_token` MUST NOT be logged and MUST NOT be rendered into any UI string.

#### Scenario: Entering AgeGateScreen does not re-invoke the Google ceremony

- **GIVEN** a stub `GoogleSignInClient` that records each `signIn()` invocation, AND a sign-in flow that has just received `404 user_not_found` for a `GoogleSignInResult.Success(idToken="g-id", ...)`
- **WHEN** the flow navigates to `AgeGateScreen` and the screen is composed
- **THEN** `GoogleSignInClient.signIn()` has NOT been invoked a second time as part of reaching or composing `AgeGateScreen` (the `id_token` carried from the sign-in ceremony is reused)

### Requirement: DOB picker does not constrain selectable range to 18+

The Material 3 `DatePicker` presented by `AgeGateScreen` SHALL allow the user to select a date of birth that is under 18 years before the current date; it MUST NOT restrict its `selectableDates` / year range to 18+-only dates, and the client MUST NOT reject an under-18 submission locally before calling the backend. Client-side validation SHALL be limited to format/sanity (a valid calendar date that is not in the future). The 18+ decision is made solely by the backend. Rationale: the anti-DOB-shopping blocklist (`rejected_identifiers`, per the `age-gate` capability) only populates when an honest under-18 DOB reaches `POST /api/v1/auth/signup`; a picker constrained to 18+ would defeat the blocklist by training the user to pick a fabricated 18+ date.

The client MUST treat BOTH `today − 18 years` (exactly 18) AND `today − 18 years + 1 day` (one day under) as submittable — it never decides the 18+ boundary itself; the server's inclusive-at-exactly-18 / strict-below rule (`age-gate` capability § Strict 18+ DOB check) is authoritative. The DOB sanity-validation SHALL take an injectable notion of "today" (a `Clock` / `today: LocalDate` seam, mirroring Mobile #3's `nowMillis: () -> Long` injection in `AuthApiClient`) so the under-18, exactly-18-boundary, and future-date checks are deterministically testable rather than wall-clock-dependent.

#### Scenario: Under-18 date is selectable and submittable

- **GIVEN** `AgeGateScreen` composed with an injected "today" fixed at a known value
- **WHEN** the DOB `DatePicker` is opened
- **THEN** a date corresponding to an age under 18 (e.g., injected-today minus 15 years) is selectable AND, once selected, the create-account CTA is enabled (the client does NOT block submission of an under-18 date)

#### Scenario: Exactly-18 and one-day-under dates are both client-submittable

- **GIVEN** `AgeGateScreen` composed with an injected "today" fixed at a known value
- **WHEN** the user selects a DOB of exactly `today − 18 years`, and separately a DOB of `today − 18 years + 1 day`
- **THEN** BOTH dates are selectable AND enable the create-account CTA (the client submits both to the server; it does NOT locally accept the exactly-18 case while rejecting the one-day-under case — the boundary decision is delegated entirely to the backend, so a regression cannot re-introduce a client-side 18+ gate)

#### Scenario: Future date is not accepted

- **WHEN** the user attempts to select or submit a date later than the current date
- **THEN** the client rejects it via format/sanity validation (the create-account CTA is not enabled for a future date) and no `POST /api/v1/auth/signup` call is made

### Requirement: Signup call uses the canonical endpoint, snake_case body, no fingerprint

On create-account submission with a well-formed DOB, `AuthRepository.signUpWithGoogle(...)` SHALL issue `POST /api/v1/auth/signup` with a JSON body containing exactly `provider = "google"`, `id_token = <carried google id token>`, and `date_of_birth = "YYYY-MM-DD"` (ISO-8601 calendar date), serialized in snake_case (`id_token`, `date_of_birth`) per the `auth-signup` spec wire contract. The body MUST NOT include a `device_fingerprint_hash` key (attestation deferred, consistent with Mobile #3 Decision 9). On HTTP `201`, the returned `{access_token, refresh_token, expires_in}` SHALL be persisted via `SecureTokenStore.write(...)` and a navigation event routing to `HomeScreen` (via `RootRouterScreen`) SHALL be emitted.

#### Scenario: Valid 18+ DOB submits canonical signup request and routes Home on 201

- **GIVEN** a Ktor MockEngine capturing outbound requests that responds `201 {access_token:"at-X", refresh_token:"rt-Y", expires_in:900}`, AND `signUpWithGoogle` carrying `id_token = "g-id"`, AND a clean `SecureTokenStore`
- **WHEN** an 18+ DOB (e.g., `"1995-03-14"`) is submitted
- **THEN** the captured outbound request is `POST /api/v1/auth/signup` whose JSON body parses as `{provider:"google", id_token:"g-id", date_of_birth:"1995-03-14"}` with NO `device_fingerprint_hash` key AND `SecureTokenStore.write(TokenPair("at-X","rt-Y", <epoch-now + 900_000>))` is called exactly once AND a navigation event routing to `HomeScreen` is emitted

#### Scenario: Signup request body carries no device_fingerprint_hash

- **WHEN** `signUpWithGoogle(...)` makes the `/signup` API call
- **THEN** the captured outbound JSON body contains no `device_fingerprint_hash` key (verifiable via `body.toString().contains("device_fingerprint_hash") == false` OR a parsed-JSON assertion)

### Requirement: Under-18 / blocked rejection shows one generic blocked message

On an HTTP `403` response from `/signup`, `AuthRepository` SHALL emit a `Blocked` outcome whose user-facing copy is `stringResource(Res.string.age_gate_under18_blocked)` ("*Platform ini hanya tersedia untuk pengguna usia 18 tahun ke atas.*"). NO token write SHALL occur and NO navigation away from `AgeGateScreen` SHALL be emitted (the user remains to read the message). The client SHALL NOT attempt to distinguish a fresh under-18 rejection from an already-blocked identifier — the backend guarantees byte-identical `403` bodies for the two paths (per the `age-gate` and `auth-signup` privacy-preserving-blocked-body requirements), so a single generic message is rendered for both.

The `403`→`Blocked` mapping MUST key on the HTTP **status** alone, NOT on a parsed `error.code`. The backend's `403` body is the FLAT shape `{"error":"user_blocked","message":"Akun tidak dapat dibuat dengan data ini."}` — `error` is a string with NO machine-readable `code` field (that absence IS the privacy guarantee), unlike the nested `{"error":{"code":...}}` shape the other signup codes use and which the shipped Mobile #3 error parser (`AuthApiClient.BackendErrorBody`) decodes. Attempting to read `error.code` from the flat `403` yields `null` and would misroute the rejection to the retryable/network fallthrough — so the mapping is status-driven. The client also deliberately ignores the server `message` field and renders its own local `age_gate_under18_blocked` string; the `403` response body MUST NOT be logged.

#### Scenario: Flat 403 body maps to Blocked, not the retryable fallthrough

- **GIVEN** a MockEngine responding with HTTP `403` and the actual flat backend body `{"error":"user_blocked","message":"Akun tidak dapat dibuat dengan data ini."}` for the signup call
- **WHEN** an under-18 DOB is submitted (the request reaches the server)
- **THEN** the emitted outcome is `Blocked` whose message text equals the runtime value of `Res.string.age_gate_under18_blocked` AND the outcome is NOT the retryable/network error (the mapping keys on HTTP status `403`, not on a parsed `error.code` — which is absent from the flat body) AND no `SecureTokenStore.write` is performed AND no navigation event is emitted

### Requirement: Account-exists collision routes to sign-in

On HTTP `409` with `error = "user_exists"` from `/signup` (the verified identity already has a `users` row — e.g., an account was created between the sign-in `404` and the signup call), `AuthRepository` SHALL emit an `AccountExists` outcome whose user-facing copy is `stringResource(Res.string.signup_error_account_exists)` ("*Akun sudah terdaftar. Silakan masuk.*") and route the user back to `SignInScreen`. NO token write SHALL occur.

#### Scenario: 409 user_exists routes back to SignInScreen

- **GIVEN** a MockEngine responding `409 {error:{code:"user_exists"}}` for the signup call
- **WHEN** a valid DOB is submitted
- **THEN** the emitted outcome's message text equals the runtime value of `Res.string.signup_error_account_exists` AND a navigation event routing to `SignInScreen` is emitted AND no `SecureTokenStore.write` is performed

### Requirement: invalid_id_token refreshes the Google token once then surfaces a terminal state

On HTTP `401` with `error = "invalid_id_token"` from `/signup` (the carried Google ID token staled between sign-in and submission), `AuthRepository` SHALL re-invoke `GoogleSignInClient.signIn()` exactly once to obtain a fresh ID token and retry `POST /api/v1/auth/signup` once with the same DOB. A second consecutive `401 invalid_id_token` SHALL surface a terminal outcome whose user-facing copy is `stringResource(Res.string.signin_error_token_invalid)` ("*Sesi Google bermasalah. Coba lagi.*"); the flow SHALL NOT invoke `GoogleSignInClient.signIn()` a third time within the same submission.

#### Scenario: First 401 refreshes the token and retries signup

- **GIVEN** a MockEngine responding `401 {error:{code:"invalid_id_token"}}` on the first signup call and `201 {...}` on the retried call, AND a `GoogleSignInClient` stub returning `Success("g-id-fresh", ...)` on its (single) re-invocation
- **WHEN** a valid DOB is submitted
- **THEN** `GoogleSignInClient.signIn()` is invoked exactly once during the submission AND a second `POST /api/v1/auth/signup` is issued carrying `id_token = "g-id-fresh"` AND on its `201` the token-persist + route-Home path runs

#### Scenario: Second consecutive 401 is terminal

- **GIVEN** a MockEngine responding `401 invalid_id_token` to both the first signup call and the retried call
- **WHEN** a valid DOB is submitted
- **THEN** the emitted outcome's message equals `Res.string.signin_error_token_invalid` AND `GoogleSignInClient.signIn()` is NOT invoked a third time within the submission

### Requirement: Transient and network failures surface a retryable error with no generic fallthrough

`AuthRepository.signUpWithGoogle(...)` SHALL map every observed result to exactly one `SignUpOutcome`, with no generic "signup failed" fallthrough. HTTP `5xx`, HTTP `503 username_generation_failed`, network/IO failure, and `GoogleSignInResult.Failed(message)` SHALL all map to a retryable error outcome whose user-facing copy is `stringResource(Res.string.signin_error_network)` and whose retry affordance label is `stringResource(Res.string.cta_retry)`; the `Failed(message)` payload SHALL be emitted to Sentry/OTel logs (NOT to user-facing UI). A `400 invalid_request` (not expected from a well-formed picker submission) SHALL map to the same retryable error outcome with a logged diagnostic rather than a silent no-op.

The result → outcome mapping SHALL be keyed on the HTTP **status code** (and transport-failure type), NOT on the parsed error envelope — `/signup` emits a flat `{"error":"user_blocked",...}` body for `403` and a nested `{"error":{"code":...}}` body for `400/401/409/503`, and a single nested-shape parser cannot decode both (see § Under-18 / blocked rejection). Each `/signup` status maps to exactly one outcome, so the envelope is informational only.

#### Scenario: 503 username_generation_failed (typed body) maps to retryable error

- **GIVEN** a MockEngine responding HTTP `503` with the nested body `{"error":{"code":"username_generation_failed","message":"Try again in a moment."}}` for the signup call
- **WHEN** a valid DOB is submitted
- **THEN** the emitted outcome's message text equals `Res.string.signin_error_network` AND its retry CTA label equals `Res.string.cta_retry` AND no token write is performed (the typed `503` body does not change the outcome — mapping is status-driven)

#### Scenario: 5xx / network-IO failure maps to retryable error

- **GIVEN** a MockEngine responding bare HTTP `500` (or throwing `IOException`) for the signup call
- **WHEN** a valid DOB is submitted
- **THEN** the emitted outcome's message text equals `Res.string.signin_error_network` AND its retry CTA label equals `Res.string.cta_retry` AND no token write is performed

#### Scenario: 400 invalid_request maps to retryable error with a logged diagnostic

- **GIVEN** a MockEngine responding HTTP `400` with `{"error":{"code":"invalid_request","message":"Malformed signup payload."}}` (not expected from a well-formed picker submission)
- **WHEN** a DOB is submitted
- **THEN** the emitted outcome is the retryable error (`Res.string.signin_error_network` + `Res.string.cta_retry`) AND a diagnostic is emitted to Sentry/OTel logs (NOT a silent no-op, NOT a crash)

#### Scenario: Every documented signup result maps to an outcome

- **WHEN** inspecting the `signUpWithGoogle` result mapping (and its `SignUpOutcome` sealed type)
- **THEN** each of `201`, `400`, `401`, `403`, `409`, `503`, `5xx`, network/IO failure, and `GoogleSignInResult.Failed` maps to exactly one `SignUpOutcome` member, keyed on HTTP status / transport-failure type; there is no `else`/wildcard branch emitting a generic "signup failed" copy

### Requirement: No signup UI renders Google email or displayName

No surface in the age-gate/signup flow — neither `AgeGateScreen` itself nor any of its outcome states (`Blocked`, `AccountExists`, `InvalidIdToken`, retryable error) — SHALL render the Google account `email` or `displayName` from `GoogleSignInResult.Success`. The identity payload is consumed only for the `POST /api/v1/auth/signup` request body; it is never plumbed into a banner, disclaimer, or debug text (consistent with the `mobile-auth-signin` error-state PII rule).

#### Scenario: No age-gate UI node renders the Google PII

- **GIVEN** the flow reached `AgeGateScreen` carrying `GoogleSignInResult.Success(idToken="g-id", displayName="Test User", email="test@example.com")` followed by ANY non-201 signup response (403/409/401/5xx)
- **WHEN** the resulting UI state is rendered
- **THEN** the rendered tree contains NO node whose text contains the substring `"test@example.com"` AND NO node whose text contains the substring `"Test User"`

### Requirement: AuthRepository orchestrates the signup flow as a Koin singleton

The mobile app SHALL extend `AuthRepository` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/auth/AuthRepository.kt`, the Koin singleton from `mobile-auth-signin`) with `suspend fun signUpWithGoogle(dateOfBirth: LocalDate): SignUpOutcome` (or an equivalent signature taking the picked DOB), composing the carried Google identity → `POST /api/v1/auth/signup` → token persistence → outcome emission. `SignUpOutcome` SHALL be a sealed type registered/consumed within `mobile/app/src/commonMain/kotlin/id/nearyou/app/auth/**`. The DOB → `"YYYY-MM-DD"` conversion SHALL use `kotlinx-datetime` (already on the `:mobile:app` classpath).

`signUpWithGoogle` SHALL guard against concurrent/duplicate invocation — via an `isInFlight` / `Mutex.tryLock` guard in `AuthRepository` OR a create-account CTA disabled-while-loading (`signup_loading`) state — so a double-tap on the create-account CTA cannot trigger two concurrent `POST /api/v1/auth/signup` calls or two `SecureTokenStore.write`s (mirroring the `mobile-auth-signin` § "Double-tap on CTA rejects the second concurrent invocation" defense).

#### Scenario: signUpWithGoogle is exposed on the Koin-registered AuthRepository

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/auth/AuthRepository.kt` and the commonMain Koin module (`MobileModule.kt`)
- **THEN** `AuthRepository` declares a `signUpWithGoogle(...)` suspend member returning `SignUpOutcome` AND `AuthRepository` remains a `single<AuthRepository> { ... }` Koin binding (signup orchestration is added to the existing singleton, not a separate ad-hoc client)

#### Scenario: Double-tap on the create-account CTA rejects the second concurrent invocation

- **GIVEN** `signUpWithGoogle(...)` is currently in-flight (the first invocation suspended on the `/signup` call)
- **WHEN** the create-account CTA is tapped a second time within the same in-flight window
- **THEN** the second invocation either (a) is silently rejected via the `isInFlight` / `Mutex.tryLock` guard (the first call completes normally; the second returns immediately without a second `/signup` call), OR (b) the CTA is in its disabled `signup_loading` state for the in-flight duration so the tap cannot dispatch — in both cases exactly ONE `/signup` call is observed and at most ONE `SecureTokenStore.write` occurs

### Requirement: Process death on AgeGateScreen routes to SignInScreen on relaunch

The carried Google `id_token` is held in in-memory navigation state only (never persisted — see § AgeGateScreen reuses the verified Google identity and design D3). If the host process dies while the user is on `AgeGateScreen` (Android process death, iOS termination) before signup completes, the `id_token` is lost. On relaunch, `RootRouterScreen` (per `mobile-auth-signin`) SHALL find no persisted `TokenPair` (signup never wrote one) and route to `SignInScreen`; the user re-attempts sign-in. There SHALL be no stale-token state, no stuck splash, and no crash.

#### Scenario: Process death mid-signup relaunches to SignInScreen

- **GIVEN** the user reached `AgeGateScreen` carrying a verified Google identity but has NOT completed signup (no `201`, so `SecureTokenStore` holds no `TokenPair`)
- **WHEN** the host process is killed (Android process death / iOS termination) AND the app is relaunched with `RootRouterScreen` as the start destination
- **THEN** `SecureTokenStore.read()` returns `null` AND the routing decision is `SignInScreen` (the prior `AgeGateScreen` + its carried `id_token` are NOT restored — the user starts a fresh sign-in) AND no crash and no indefinite splash occurs

