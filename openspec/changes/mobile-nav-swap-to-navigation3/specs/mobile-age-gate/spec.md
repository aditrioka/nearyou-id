## MODIFIED Requirements

### Requirement: AgeGateScreen renders the DOB picker and create-account surface

The mobile app SHALL ship a composable `AgeGateScreen` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/auth/AgeGateScreen.kt`), mapped from the `AgeGateRoute` `NavKey` by the `entryProvider`, that renders the signup-new-user surface reached when sign-in reports no existing account. The screen SHALL display: (a) a screen title via `stringResource(Res.string.age_gate_title)`; (b) an explainer that states the 18+ minimum via `stringResource(Res.string.age_gate_explainer)` (satisfies the PP 17/2025 "clear minimum-age information" obligation per `docs/06-Security-Privacy.md` § Age Gate); (c) a date-of-birth field labelled via `stringResource(Res.string.age_gate_dob_label)` that opens a Material 3 `DatePicker`; (d) a primary create-account CTA via `stringResource(Res.string.cta_create_account)`. No hardcoded UI string literals SHALL appear in the screen source. The screen SHALL render under `NearYouTheme` and reuse the theme-aware brand-logo pattern consistent with `SignInScreen`/`HomeScreen`.

#### Scenario: Initial render shows title, DOB field, and create-account CTA

- **WHEN** a `commonTest` runs `runComposeUiTest { setContent { NearYouTheme { AgeGateScreen(...) } } }` against a fresh composition with the `PendingSignupIdentity` holder seeded with a stub identity (via the test Koin module)
- **THEN** the rendered tree contains a node whose text matches the runtime value of `stringResource(Res.string.age_gate_title)` AND a node whose text matches `stringResource(Res.string.age_gate_dob_label)` AND a clickable node whose text matches `stringResource(Res.string.cta_create_account)`

#### Scenario: No hardcoded UI strings in AgeGateScreen source

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/auth/AgeGateScreen.kt`
- **THEN** every `Text(...)` / `contentDescription = ...` / similar UI-string-bearing call site sources its text via `stringResource(Res.string.<name>)` (Compose Multiplatform Resources accessor); zero literal string arguments appear in such call sites

### Requirement: AgeGateScreen reuses the verified Google identity from the sign-in no-account path

`AgeGateScreen` SHALL obtain the verified Google identity (the `id_token` from the `GoogleSignInResult.Success` that produced the backend `404 user_not_found`) from an **in-memory `PendingSignupIdentity` holder** so the signup call reuses it — the sign-in no-account path sets the holder (`pendingSignupIdentity.set(idToken)`) immediately before appending `AgeGateRoute` to the back stack. The identity SHALL NOT be carried as a property on the `AgeGateRoute` `NavKey` (which would write it into the serialized back stack on iOS — see § The verified id_token is never written to the serialized back stack). `AgeGateScreen` (and the flow reaching it) SHALL NOT trigger a fresh `GoogleSignInClient.signIn()` ceremony on entry — the user MUST NOT see a second Google account sheet for one continuous registration. The held `id_token` MUST NOT be logged and MUST NOT be rendered into any UI string.

#### Scenario: Entering AgeGateScreen does not re-invoke the Google ceremony

- **GIVEN** a stub `GoogleSignInClient` that records each `signIn()` invocation, AND a sign-in flow that has just received `404 user_not_found` for a `GoogleSignInResult.Success(idToken="g-id", ...)` and has set `PendingSignupIdentity` to that identity
- **WHEN** the flow appends `AgeGateRoute` to the back stack and the `AgeGateScreen` composable is composed
- **THEN** `GoogleSignInClient.signIn()` has NOT been invoked a second time as part of reaching or composing `AgeGateScreen` (the `id_token` read from the `PendingSignupIdentity` holder is reused)

### Requirement: Account-exists collision routes to sign-in

On HTTP `409` with `error = "user_exists"` from `/signup` (the verified identity already has a `users` row — e.g., an account was created between the sign-in `404` and the signup call), `AuthRepository` SHALL emit an `AccountExists` outcome whose user-facing copy is `stringResource(Res.string.signup_error_account_exists)` ("*Akun sudah terdaftar. Silakan masuk.*") and route the user back to `SignInRoute` (via `backStack.replaceAll(SignInRoute)`). NO token write SHALL occur.

#### Scenario: 409 user_exists routes back to SignInScreen

- **GIVEN** a MockEngine responding `409 {error:{code:"user_exists"}}` for the signup call
- **WHEN** a valid DOB is submitted
- **THEN** the emitted outcome's message text equals the runtime value of `Res.string.signup_error_account_exists` AND a navigation event routing to `SignInRoute` is emitted AND no `SecureTokenStore.write` is performed

### Requirement: Process death on AgeGateScreen routes to SignInScreen on relaunch

The verified Google `id_token` is held in the in-memory `PendingSignupIdentity` holder only (never persisted, never written to the serialized back stack — see § AgeGateScreen reuses the verified Google identity and design Decision 4). If the host process dies while the user is on `AgeGateScreen` (Android process death, iOS termination) before signup completes, the `id_token` is lost. Two relaunch paths SHALL both land the user on `SignInRoute` with no stale-token state, no stuck splash, and no crash:

- **Cold back stack** — if the back stack is not restored, the seeded `RootRoute` re-evaluates: `RootRouterScreen` finds no persisted `TokenPair` (signup never wrote one) and routes to `SignInRoute` per `mobile-auth-signin`.
- **Restored back stack** — because Nav3's back stack is saveable, a restored stack MAY include `AgeGateRoute`. Since the in-memory `PendingSignupIdentity` does NOT survive process death, `AgeGateScreen` SHALL detect the absent pending identity on entry and emit a one-shot re-route to `SignInRoute`; it MUST NOT render the signup form without a pending identity, MUST NOT crash, and MUST NOT hang.

#### Scenario: Process death mid-signup relaunches to SignInScreen (cold back stack)

- **GIVEN** the user reached `AgeGateScreen` carrying a verified Google identity but has NOT completed signup (no `201`, so `SecureTokenStore` holds no `TokenPair`)
- **WHEN** the host process is killed (Android process death / iOS termination), the back stack is NOT restored, AND the app is relaunched with `RootRoute` re-evaluating
- **THEN** `SecureTokenStore.read()` returns `null` AND the routing decision is `SignInRoute` (the prior `AgeGateScreen` + its identity are NOT restored — the user starts a fresh sign-in) AND no crash and no indefinite splash occurs

#### Scenario: Restored back stack on AgeGateRoute with absent identity re-routes to SignInScreen

- **GIVEN** a back stack restored to `[RootRoute, SignInRoute, AgeGateRoute]` after process death, with the in-memory `PendingSignupIdentity` holder now empty (it did not survive the process death)
- **WHEN** `AgeGateScreen` is composed for the restored `AgeGateRoute` entry
- **THEN** `AgeGateScreen` detects the absent pending identity and emits a one-shot re-route to `SignInRoute`; the signup form is NOT rendered without an identity, and no crash and no indefinite splash occurs

## ADDED Requirements

### Requirement: The verified id_token is never written to the serialized back stack

Because Nav3's back stack is serialized on iOS (per `mobile-app-scaffold` § "Back stack uses serializable NavKey routes"), the verified Google `id_token` SHALL NOT appear in any serialized navigation state. The `id_token` SHALL be held only in the in-memory `PendingSignupIdentity` holder, and `AgeGateRoute` SHALL be a parameterless marker `NavKey` carrying no identity payload. This reproduces, under Nav3's saveable back stack, the privacy guarantee the prior Voyager (never-serialized) back stack provided for free.

#### Scenario: Serialized back stack containing AgeGateRoute carries no id_token

- **WHEN** a `commonTest` builds a back stack `[RootRoute, SignInRoute, AgeGateRoute]` with the `PendingSignupIdentity` holder set to a stub `id_token = "g-id-secret"`, then serializes the back stack via the polymorphic `SavedStateConfiguration` module
- **THEN** the serialized output contains NO occurrence of the substring `"g-id-secret"` (the identity lives only in the in-memory holder, never in `AgeGateRoute` or any serialized navigation state)

#### Scenario: AgeGateRoute declares no identity property

- **WHEN** inspecting the `AgeGateRoute` `NavKey` declaration in `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/routing/`
- **THEN** `AgeGateRoute` is a parameterless marker (e.g. a `data object`) with no `idToken` / identity property; the verified identity is read from the `PendingSignupIdentity` Koin singleton instead
