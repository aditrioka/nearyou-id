## MODIFIED Requirements

### Requirement: AgeGateScreen renders the DOB picker and create-account surface

The mobile app SHALL ship a composable `AgeGateScreen` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/auth/AgeGateScreen.kt`), mapped from the `AgeGateRoute` `NavKey` by the `entryProvider`, that renders the signup-new-user surface reached when sign-in reports no existing account. The screen SHALL display: (a) a screen title via `stringResource(Res.string.age_gate_title)`; (b) an explainer that states the 18+ minimum via `stringResource(Res.string.age_gate_explainer)` (satisfies the PP 17/2025 "clear minimum-age information" obligation per `docs/06-Security-Privacy.md` § Age Gate); (c) a date-of-birth field labelled via `stringResource(Res.string.age_gate_dob_label)` that opens a Material 3 `DatePicker`; (d) a primary create-account CTA via `stringResource(Res.string.cta_create_account)`; (e) an **optional** invite-code text field labelled via `stringResource(Res.string.age_gate_invite_code_label)` ("Kode undangan (opsional)") whose entered value is forwarded to the signup call as the optional `invite_code` (per the Signup-call requirement below). The invite-code field is non-blocking — it MAY be left empty, and an empty/blank value MUST NOT prevent account creation. No hardcoded UI string literals SHALL appear in the screen source. The screen SHALL render under `NearYouTheme` and reuse the theme-aware brand-logo pattern consistent with `SignInScreen`/`HomeScreen`.

#### Scenario: Initial render shows title, DOB field, create-account CTA, and the optional invite-code field

- **WHEN** a `commonTest` runs `runComposeUiTest { setContent { NearYouTheme { AgeGateScreen(...) } } }` against a fresh composition with the `PendingSignupIdentity` holder seeded with a stub identity (via the test Koin module)
- **THEN** the rendered tree contains a node whose text matches the runtime value of `stringResource(Res.string.age_gate_title)` AND a node whose text matches `stringResource(Res.string.age_gate_dob_label)` AND a clickable node whose text matches `stringResource(Res.string.cta_create_account)` AND a node whose text matches `stringResource(Res.string.age_gate_invite_code_label)`

#### Scenario: No hardcoded UI strings in AgeGateScreen source

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/auth/AgeGateScreen.kt`
- **THEN** every `Text(...)` / `contentDescription = ...` / similar UI-string-bearing call site sources its text via `stringResource(Res.string.<name>)` (Compose Multiplatform Resources accessor); zero literal string arguments appear in such call sites

### Requirement: Signup call uses the canonical endpoint, snake_case body, no fingerprint

On create-account submission with a well-formed DOB, `AuthRepository.signUpWithGoogle(...)` SHALL issue `POST /api/v1/auth/signup` with a JSON body containing `provider = "google"`, `id_token = <carried google id token>`, `date_of_birth = "YYYY-MM-DD"` (ISO-8601 calendar date), and — when the user entered a non-blank invite code — an OPTIONAL `invite_code = <entered code, trimmed>`, serialized in snake_case (`id_token`, `date_of_birth`, `invite_code`) per the `auth-signup` spec wire contract (which already accepts the optional `invite_code`). When the invite-code field is empty or blank, the `invite_code` key SHALL be omitted from the body entirely (matching the backend's `inviteCode?.trim()?.takeIf { it.isNotEmpty() }` treatment). The invite code is non-secret user-entered data carried in the age-gate ViewModel UI state — it SHALL NOT be placed in the `PendingSignupIdentity` credential holder. The body MUST NOT include a `device_fingerprint_hash` key (attestation deferred, consistent with Mobile #3 Decision 9). On HTTP `201`, the returned `{access_token, refresh_token, expires_in}` SHALL be persisted via `SecureTokenStore.write(...)` and a navigation event routing to **`ConsentScreen`** (via the age-gate signup-success navigation callback — the `AgeGateScreen` `onSignedUp` handler wired in `AppEntryProvider`, NOT `RootRouterScreen` which only does cold-start token routing) SHALL be emitted. `ConsentScreen` is the first-run analytics-consent step (per the `mobile-analytics-consent` capability) and routes onward to `HomeScreen` on consent submit; signup-success therefore terminates at `ConsentScreen`, not `HomeScreen` directly. (Prior to the `mobile-analytics-consent-screen` change the `201` terminus was `HomeScreen`; the returning-user sign-in terminus remains `HomeScreen` and is unaffected.)

#### Scenario: Valid 18+ DOB submits canonical signup request and routes to ConsentScreen on 201

- **GIVEN** a Ktor MockEngine capturing outbound requests that responds `201 {access_token:"at-X", refresh_token:"rt-Y", expires_in:900}`, AND `signUpWithGoogle` carrying `id_token = "g-id"` with no invite code entered, AND a clean `SecureTokenStore`
- **WHEN** an 18+ DOB (e.g., `"1995-03-14"`) is submitted
- **THEN** the captured outbound request is `POST /api/v1/auth/signup` whose JSON body parses as `{provider:"google", id_token:"g-id", date_of_birth:"1995-03-14"}` with NO `device_fingerprint_hash` key AND NO `invite_code` key AND `SecureTokenStore.write(TokenPair("at-X","rt-Y", <epoch-now + 900_000>))` is called exactly once AND a navigation event routing to `ConsentScreen` (NOT `HomeScreen`) is emitted

#### Scenario: Entered invite code is forwarded as snake_case invite_code

- **GIVEN** a Ktor MockEngine capturing outbound requests that responds `201 {...}`, AND `signUpWithGoogle` carrying `id_token = "g-id"` AND an entered invite code `"a3f7k2mq"`
- **WHEN** an 18+ DOB is submitted
- **THEN** the captured outbound `POST /api/v1/auth/signup` JSON body parses as `{provider:"google", id_token:"g-id", date_of_birth:"<dob>", invite_code:"a3f7k2mq"}` (the `invite_code` key present and equal to the entered code)

#### Scenario: Blank invite code omits the invite_code key

- **GIVEN** `signUpWithGoogle` carrying `id_token = "g-id"` AND an invite-code field that is empty or whitespace-only
- **WHEN** an 18+ DOB is submitted
- **THEN** the captured outbound `POST /api/v1/auth/signup` JSON body contains NO `invite_code` key (verifiable via a parsed-JSON assertion)

#### Scenario: Signup request body carries no device_fingerprint_hash

- **WHEN** `signUpWithGoogle(...)` makes the `/signup` API call
- **THEN** the captured outbound JSON body contains no `device_fingerprint_hash` key (verifiable via `body.toString().contains("device_fingerprint_hash") == false` OR a parsed-JSON assertion)
