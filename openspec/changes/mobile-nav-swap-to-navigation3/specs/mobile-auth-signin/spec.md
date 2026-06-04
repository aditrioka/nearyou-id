## MODIFIED Requirements

### Requirement: SignInScreen renders Google Sign-In entry point

The mobile app SHALL ship a composable `SignInScreen` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/auth/SignInScreen.kt`), mapped from the `SignInRoute` `NavKey` by the `entryProvider`, that renders the unauthenticated entry surface. The screen SHALL display: (a) the brand logo via `painterResource(Res.drawable.logo_brand_{light,dark})` (theme-aware per `isSystemInDarkTheme()` consistent with [`HomeScreen`](../../../../mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/home/HomeScreen.kt)); (b) a screen title consumed via `stringResource(Res.string.signin_screen_title)`; (c) a primary call-to-action button consumed via `stringResource(Res.string.cta_signin_google)`; (d) a footnote consumed via `stringResource(Res.string.account_separation_disclosure)`. No hardcoded UI string literals SHALL appear in the screen source.

#### Scenario: Initial render shows the Google Sign-In CTA

- **WHEN** a `commonTest` runs `runComposeUiTest { setContent { NearYouTheme { SignInScreen(...) } } }` against a fresh composition with no in-flight auth state
- **THEN** the rendered tree contains a node whose text matches the runtime value of `stringResource(Res.string.cta_signin_google)` (i.e., "Masuk dengan Google") AND the node is clickable

#### Scenario: Initial render shows the screen title and disclosure

- **WHEN** `SignInScreen` is composed
- **THEN** the rendered tree contains a node whose text matches the runtime value of `stringResource(Res.string.signin_screen_title)` AND a node whose text matches the runtime value of `stringResource(Res.string.account_separation_disclosure)`

#### Scenario: No hardcoded UI strings in SignInScreen source

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/auth/SignInScreen.kt`
- **THEN** every `Text(...)` / `contentDescription = ...` / similar UI-string-bearing call site sources its text via `stringResource(Res.string.<name>)` (Compose Multiplatform Resources accessor); zero literal string arguments appear in such call sites

#### Scenario: SignInScreen brand logo swaps on system-theme change at recomposition

- **GIVEN** `SignInScreen` is composed in light mode (`isSystemInDarkTheme() == false`) — the rendered brand logo node uses `Res.drawable.logo_brand_light`
- **WHEN** the system theme is toggled to dark mode AND the screen is recomposed
- **THEN** the rendered brand logo node uses `Res.drawable.logo_brand_dark` (no crash, no stale-logo retention); recomposition is triggered automatically because `isSystemInDarkTheme()` is observable Compose state

### Requirement: Backend error codes are mapped to user-facing copy with no fallthrough

The `AuthRepository` SHALL map each result from the sign-in flow to a specific UI state per Decision 7's table:

- **HTTP 200 (backend `/signin` success)**: persist `TokenPair` via `SecureTokenStore.write(...)` AND emit a navigation event routing to `HomeRoute` (the authenticated `HomeScreen`) via `backStack.replaceAll(HomeRoute)`.
- **HTTP 404 with `error = "user_not_found"`**: this is NOT an error — it is the "no account exists yet for this verified Google identity" signal. Set the in-memory `PendingSignupIdentity` holder to the verified Google identity (the `id_token` from the `GoogleSignInResult.Success` that produced this `404`) AND emit a navigation event appending `AgeGateRoute` to the back stack — the identity is read from the holder by the signup flow (per the `mobile-age-gate` capability), NOT carried as a route parameter (which would write it into the serialized back stack on iOS — see `mobile-age-gate` § "The verified id_token is never written to the serialized back stack"), so the signup flow reuses it without a second Google ceremony. Do NOT emit an on-`SignInScreen` error banner for `404`. The `signin_error_no_account` string is no longer rendered on this path (retired in place; its removal/repurpose for a narrow network-edge fallback is an implementation-time decision per design Open Questions). This replaces the temporary Mobile #3 behavior ("show `signin_error_no_account` banner, remain on `SignInScreen`") and resolves the `FOLLOW_UPS.md` entry `mobile-auth-signin-404-route-to-age-gate`.
- **HTTP 403 with `error = "account_banned"` (permanent-ban path)**: emit an error state whose user-facing copy is `stringResource(Res.string.signin_error_banned)` ("Akun kamu telah dinonaktifkan. Hubungi support jika ini keliru."); remain on `SignInScreen`; CTA is disabled (visually disabled AND tap-rejected per the dedicated scenario below) to prevent retry. **Note:** the current backend `/signin` emits `account_banned` for any `is_banned = TRUE` row WITHOUT inspecting `suspended_until`, so temporarily-suspended users (`is_banned = TRUE AND suspended_until > NOW()` per V2 schema) hit this same permanent-ban copy in Mobile #3. A FOLLOW_UPS entry `mobile-auth-signin-suspended-user-copy-split` tracks the eventual backend differentiation (emit `account_suspended` with a `suspended_until` field at `/signin`) + mobile copy split per `docs/03-UX-Design.md` § Suspension UX (the paragraphs beginning `When users.is_banned = TRUE AND users.suspended_until > NOW()` and `When users.is_banned = TRUE AND users.suspended_until IS NULL`); until that lands, Mobile #3 deliberately ships the permanent-ban copy as a uniform path.
- **HTTP 401 with `error = "invalid_id_token"`**: emit an error state whose user-facing copy is `stringResource(Res.string.signin_error_token_invalid)` ("Sesi Google bermasalah. Coba lagi."); automatically re-invoke `GoogleSignInClient.signIn()` ONCE; if the re-invocation also produces a 401, remain on the error state and require a manual retry tap. The retry counter SHALL be **screen-state-local** (held in the `SignInScreen`'s composition state) — re-entering `SignInScreen` (e.g., after a process death + relaunch) SHALL reset the counter to zero.
- **HTTP 5xx OR network/IO failure**: emit an error state whose user-facing copy is `stringResource(Res.string.signin_error_network)` ("Tidak bisa terhubung. Periksa koneksi internet kamu."); CTA changes label to "Coba lagi" and re-invokes the full flow on tap.
- **`GoogleSignInResult.UserCancelled`**: emit no error state (cancellation is not a failure); `SignInScreen` returns to the initial CTA-visible state.
- **`GoogleSignInResult.Failed(message)`**: emit the same `NetworkError` UI state as the HTTP 5xx / network-IO path (user-facing copy `signin_error_network`); the Google ceremony failing pre-backend is operationally indistinguishable from a network failure from the user's perspective; the `message` payload SHOULD be emitted to Sentry / OTel logs (NOT to the user-facing UI) for diagnosis.

There SHALL NOT be a generic "Sign-in failed" fallthrough — every observed result from the flow maps to exactly one explicit outcome (a navigation event OR an error state) among those listed above. Error states SHALL NOT render the Google account `email` or `displayName` from `GoogleSignInResult.Success` in any user-facing UI (no "Hi, foo@example.com — try again?" pattern); the `displayName` MAY be surfaced ONLY post-authentication, on screens behind the auth gate.

#### Scenario: 200 success persists tokens and navigates to Home

- **GIVEN** a successful Google ID-token exchange producing backend response `200 { access_token: "at-X", refresh_token: "rt-Y", expires_in: 900 }`
- **WHEN** the `AuthRepository.signInWithGoogle(...)` flow processes the response
- **THEN** `SecureTokenStore.write(TokenPair("at-X", "rt-Y", <epoch-millis-now + 900_000>))` is called exactly once AND a navigation event routing to `HomeRoute` (via `backStack.replaceAll(HomeRoute)`) is emitted

#### Scenario: 404 sets the pending identity and navigates to AgeGateRoute

- **GIVEN** backend response `404 { error: { code: "user_not_found" } }` for a `GoogleSignInResult.Success(idToken="g-id", ...)`
- **WHEN** the `AuthRepository.signInWithGoogle(...)` flow processes the response
- **THEN** the `PendingSignupIdentity` holder is set to `idToken = "g-id"` AND a navigation event appending `AgeGateRoute` to the back stack is emitted (the identity is read from the holder, NOT carried as a route parameter) AND NO on-`SignInScreen` error banner is emitted (the `signin_error_no_account` copy is NOT shown) AND no token write is performed

#### Scenario: 403 banned emits banned error state with CTA disabled

- **GIVEN** backend response `403 { error: { code: "account_banned" } }`
- **WHEN** the `AuthRepository.signInWithGoogle(...)` flow processes the response
- **THEN** the emitted UI state's error message text equals the runtime value of `Res.string.signin_error_banned` AND the UI state's `ctaEnabled` field is `false`

#### Scenario: 401 invalid_id_token auto-retries once

- **GIVEN** backend responds `401 { error: { code: "invalid_id_token" } }` on the first call AND responds `200 { ... }` on a subsequent call
- **WHEN** the `AuthRepository.signInWithGoogle(...)` flow processes the first 401
- **THEN** `GoogleSignInClient.signIn()` is invoked a second time within the same flow AND the second call's resulting ID token is submitted via a second `POST /api/v1/auth/signin` AND on the second success the standard token-persist + navigation path runs (per the 200 scenario)

#### Scenario: Network failure emits network error with retry CTA

- **GIVEN** the Ktor MockEngine throws `IOException("connection refused")` on the request
- **WHEN** the `AuthRepository.signInWithGoogle(...)` flow processes the exception
- **THEN** the emitted UI state's error message text equals the runtime value of `Res.string.signin_error_network` AND the UI state's CTA label equals the runtime value of `Res.string.cta_retry` (NOT `Res.string.cta_signin_google` in this state)

#### Scenario: User cancellation produces no error state

- **GIVEN** `GoogleSignInClient.signIn()` returns `GoogleSignInResult.UserCancelled`
- **WHEN** the `AuthRepository.signInWithGoogle(...)` flow processes the cancellation
- **THEN** no error message is emitted; the UI state remains in the initial CTA-visible state (no banner, no disabled CTA, no navigation)

#### Scenario: 401 invalid_id_token second attempt also failing emits terminal InvalidIdToken state

- **GIVEN** backend responds `401 { error: { code: "invalid_id_token" } }` on the first call AND `401 { error: { code: "invalid_id_token" } }` on the auto-retry second call
- **WHEN** the `AuthRepository.signInWithGoogle(...)` flow processes the second 401
- **THEN** the emitted UI state's error message equals `Res.string.signin_error_token_invalid` AND `GoogleSignInClient.signIn()` is NOT invoked a third time (the retry budget is exhausted at one) AND the CTA returns to its initial label ("Masuk dengan Google") with `ctaEnabled = true` so the user can manually tap to start a fresh flow

#### Scenario: 401 invalid_id_token retry counter is screen-state-local and resets across SignInScreen re-entries

- **GIVEN** the user just exhausted the auto-retry budget (two consecutive 401s within one flow) AND has navigated AWAY from `SignInScreen` (e.g., closed the app, was routed to `HomeRoute` by an unrelated path then back to `SignInRoute` via 401-clear, OR the process died and relaunched)
- **WHEN** `SignInScreen` is re-composed AND the user taps the CTA again
- **THEN** the auto-retry counter starts fresh at zero — the next 401 (if any) triggers ONE auto-retry per the canonical mapping, NOT zero (the counter is NOT persisted to disk, NOT persisted across back-stack pops)

#### Scenario: GoogleSignInResult.Failed maps to NetworkError state

- **GIVEN** `GoogleSignInClient.signIn()` returns `GoogleSignInResult.Failed("Credential Manager failed to reach Google's servers")`
- **WHEN** the `AuthRepository.signInWithGoogle(...)` flow processes the result
- **THEN** the emitted `SignInOutcome` is `NetworkError` (NOT a silent no-op, NOT a separate `UnexpectedFailure` state — the user-perceived experience is "the Google sign-in didn't work because of a connectivity issue") AND the UI banner copy equals `Res.string.signin_error_network` AND the `message` payload IS emitted to Sentry / OTel logs (for diagnosis) but is NOT rendered to user-facing UI

#### Scenario: Banned banner CTA does not invoke signInWithGoogle when tapped

- **GIVEN** UI state is in `SignInOutcome.Banned` with `ctaEnabled = false`
- **WHEN** a synthetic Compose tap event is dispatched on the CTA node (some Compose configurations still synthesize click events on `Button(enabled=false)`)
- **THEN** `GoogleSignInClient.signIn()` is NOT invoked AND `AuthRepository.signInWithGoogle()` is NOT entered (the tap is rejected by the visual-AND-tap-handler-disable defense pair, not visual-disable-only)

#### Scenario: No error-state UI renders Google email or displayName

- **GIVEN** the in-flight `GoogleSignInResult.Success(idToken="g-id", displayName="Test User", email="test@example.com")` followed by ANY non-200 backend response that produces a `SignInScreen` error state (403, 401, 5xx — note `404` no longer produces a `SignInScreen` error state; it sets `PendingSignupIdentity` and navigates to `AgeGateRoute`, whose PII discipline is covered by the `mobile-age-gate` capability)
- **WHEN** the resulting error-state UI is rendered
- **THEN** the rendered tree contains NO node whose text contains the substring `"test@example.com"` AND NO node whose text contains the substring `"Test User"` (the PII payload from `GoogleSignInResult.Success` is consumed only for the backend API call body; it is NOT plumbed into any error-state banner / disclaimer / debug text)

#### Scenario: Double-tap on CTA rejects the second concurrent invocation

- **GIVEN** `AuthRepository.signInWithGoogle()` is currently in-flight (first invocation suspended on the Google ceremony or the backend call)
- **WHEN** the user taps the CTA a second time within the same in-flight window
- **THEN** the second invocation either (a) is silently rejected via an `isInFlight` guard in `AuthRepository` (the first call completes normally; the second call returns immediately without re-invoking `GoogleSignInClient.signIn()`), OR (b) the CTA is visibly transitioned to a disabled / `signin_loading` state for the duration of the in-flight window (preventing a second tap). The implementation MAY choose either pattern; both prevent the race-condition outcome where TWO concurrent token writes hit `SecureTokenStore`

### Requirement: RootRouterScreen routes based on token presence

The navigation back stack SHALL be seeded with `RootRoute` as its start destination; `RootRoute` maps (via the `entryProvider`) to the `RootRouterScreen` composable (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/routing/RootRouterScreen.kt`). On first composition, `RootRouterScreen` SHALL read `SecureTokenStore.read()` once (in a `LaunchedEffect`-suspended scope, via `AuthRepository.isAuthenticated()`) and route via `backStack.replaceAll(...)` (the clear-and-set back-stack operation):

- If `read()` returns a non-null `TokenPair`, replace-all with `HomeRoute`. **The router gates on token PRESENCE only** — it does NOT compare `accessExpiresAtEpochMillis` against the current time. Whether the persisted access token is still fresh or already expired is decided lazily, downstream, by the Ktor `Auth` plugin: `loadTokens` attaches the persisted access token, and on the first authenticated `401` `refreshTokens` exchanges the refresh token for a new pair (or, on refresh failure, `SessionInvalidator` clears the store + re-routes to `SignInRoute`). The stored `accessExpiresAtEpochMillis` is retained on the `TokenPair` for a future pre-emptive-refresh optimization in `loadTokens`, but is intentionally NOT a routing gate (the project cannot cheaply know the refresh-token's own expiry client-side, so "a `TokenPair` exists" is the routing signal and the backend is the authority on refresh-token validity).
- If `read()` returns `null`, replace-all with `SignInRoute`.

While the `read()` is in-flight, the screen SHALL render a splash composition (brand logo centered + `CircularProgressIndicator`); no token-bearing decisions are made before the read completes.

#### Scenario: Token present routes to HomeScreen

- **GIVEN** `SecureTokenStore` contains `TokenPair("at-X", "rt-Y", <future-epoch-millis>)`
- **WHEN** the app launches with `RootRoute` as the seeded start destination
- **THEN** the in-flight read completes, AND `backStack.replaceAll(HomeRoute)` is invoked; the visible entry post-route is `HomeRoute` (the `HomeScreen` composable)

#### Scenario: Token absent routes to SignInScreen

- **GIVEN** `SecureTokenStore.read()` returns `null`
- **WHEN** the app launches with `RootRoute` as the seeded start destination
- **THEN** `backStack.replaceAll(SignInRoute)` is invoked; the visible entry post-route is `SignInRoute` (the `SignInScreen` composable)

#### Scenario: Splash composition renders while token check is in-flight

- **GIVEN** a `SecureTokenStore` test stub that suspends indefinitely on `read()`
- **WHEN** `RootRouterScreen` is composed
- **THEN** the rendered tree contains the brand logo node AND a `CircularProgressIndicator`; the screen does NOT make a routing decision (the back stack is unchanged — still `[RootRoute]`)

#### Scenario: Persisted TokenPair with a still-fresh access token routes to HomeScreen

- **GIVEN** `SecureTokenStore.read()` returns `TokenPair("at-X", "rt-Y", accessExpiresAtEpochMillis = now + 1)` (one millisecond in the future)
- **WHEN** `RootRouterScreen` is composed
- **THEN** the routing decision is `HomeRoute` — the router gates on token PRESENCE (non-null `TokenPair`), NOT on the expiry comparison; a fresh access token simply means the Ktor `Auth` plugin won't need to refresh on the first authenticated call

#### Scenario: Persisted TokenPair with an already-expired access token still routes to HomeScreen

- **GIVEN** `SecureTokenStore.read()` returns `TokenPair("at-X", "rt-Y", accessExpiresAtEpochMillis = now)` (epoch equals current time, i.e. the access token is at/past expiry)
- **WHEN** `RootRouterScreen` is composed
- **THEN** the routing decision is still `HomeRoute` — the presence-only gate does NOT distinguish fresh from expired access tokens; `RootRouterScreen` does NOT compare the expiry or pre-emptively refresh. The Ktor `Auth` plugin refreshes lazily on the first authenticated request (or, on refresh failure, `SessionInvalidator` clears the store + re-routes to `SignInRoute`). This is why both the `now + 1` and `now` cases route identically: routing is presence-driven, expiry handling is the Auth plugin's concern.

#### Scenario: Post-Banned process restart routes to SignInScreen with token cleared

- **GIVEN** a previous flow ended in `SignInOutcome.Banned` AND no token was persisted (per the `Banned` scenario, `SecureTokenStore.write` is NOT called) AND the user kills the app
- **WHEN** the app relaunches with `RootRoute` as the seeded start destination
- **THEN** `SecureTokenStore.read()` returns `null` AND the routing decision is `SignInRoute`; the previous `Banned` UI state is NOT restored across process death (which is correct — the Banned state is composition-local; the user MUST re-attempt sign-in on next launch to get the up-to-date banned status from the backend)
