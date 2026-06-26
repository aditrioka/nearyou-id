## MODIFIED Requirements

### Requirement: Backend error codes are mapped to user-facing copy with no fallthrough

The `AuthRepository` SHALL map each result from the sign-in flow to a specific UI state per Decision 7's table:

- **HTTP 200 (backend `/signin` success)**: persist `TokenPair` via `SecureTokenStore.write(...)` AND emit a navigation event routing to `HomeRoute` (the authenticated `HomeScreen`) via `backStack.replaceAll(HomeRoute)`.
- **HTTP 404 with `error = "user_not_found"`**: this is NOT an error — it is the "no account exists yet for this verified Google identity" signal. Set the in-memory `PendingSignupIdentity` holder to the verified Google identity (the `id_token` from the `GoogleSignInResult.Success` that produced this `404`) AND emit a navigation event appending `AgeGateRoute` to the back stack — the identity is read from the holder by the signup flow (per the `mobile-age-gate` capability), NOT carried as a route parameter (which would write it into the serialized back stack on iOS — see `mobile-age-gate` § "The verified id_token is never written to the serialized back stack"), so the signup flow reuses it without a second Google ceremony. Do NOT emit an on-`SignInScreen` error banner for `404`. The `signin_error_no_account` string is no longer rendered on this path (retired in place; its removal/repurpose for a narrow network-edge fallback is an implementation-time decision per design Open Questions). This replaces the temporary Mobile #3 behavior ("show `signin_error_no_account` banner, remain on `SignInScreen`") and resolves the earlier follow-up `mobile-auth-signin-404-route-to-age-gate`.
- **HTTP 403 with `error = "account_banned"`**: the 403 body carries a `suspended_until` field (per `auth-signin`: an ISO-8601 timestamp for a suspension, `null` for a permanent ban) that the `AuthRepository` SHALL parse and branch on, emitting one of two banned sub-states. Both remain on `SignInScreen` with the CTA disabled (visually disabled AND tap-rejected per the dedicated scenario below) to prevent retry, and both capture the limited `appeal_token` from the 403 body into the in-memory appeal-session holder (per `mobile-appeal`):
  - **`suspended_until` non-null (7-day suspension)** → emit the *suspension* banned sub-state whose user-facing copy is `stringResource(Res.string.signin_error_suspended)` (a net-new suspension-specific message, e.g. "Akun kamu sedang ditangguhkan sementara. Kamu bisa mengajukan banding."). The suspension sub-state surfaces the "Ajukan banding" appeal entry (per `mobile-appeal`).
  - **`suspended_until` null (permanent ban)** → emit the *permanent* banned sub-state whose user-facing copy is `stringResource(Res.string.signin_error_banned)` ("Akun kamu telah dinonaktifkan. Hubungi support jika ini keliru."). NO appeal entry is surfaced (the support path applies).

  This resolves issue #187 (`mobile-auth-signin-suspended-user-copy-split`) and the appeal-routing half of #391. The backend differentiation is the `suspended_until` field on the existing `account_banned` 403 — NOT a separate `account_suspended` code (an earlier revision of this requirement hinted at `account_suspended`; that mechanism is superseded — see this change's `design.md` for the rationale). The mobile copy + appeal-entry split is keyed on that field. The previous "uniform permanent-ban copy for suspended users" Mobile #3 behavior is removed.
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

#### Scenario: 403 suspension emits suspension copy and surfaces the appeal entry

- **GIVEN** backend response `403 { error: { code: "account_banned" }, suspended_until: "2026-07-03T00:00:00Z", appeal_token: "<jwt>" }` (a non-null future `suspended_until`)
- **WHEN** the `AuthRepository.signInWithGoogle(...)` flow processes the response
- **THEN** the emitted UI state's error message text equals the runtime value of `Res.string.signin_error_suspended` AND the UI state's `ctaEnabled` field is `false` AND the suspension banned sub-state surfaces the "Ajukan banding" appeal entry AND the `appeal_token` is captured into the appeal-session holder

#### Scenario: 403 permanent ban emits support copy with no appeal entry

- **GIVEN** backend response `403 { error: { code: "account_banned" }, suspended_until: null, appeal_token: "<jwt>" }` (a null `suspended_until`)
- **WHEN** the `AuthRepository.signInWithGoogle(...)` flow processes the response
- **THEN** the emitted UI state's error message text equals the runtime value of `Res.string.signin_error_banned` AND the UI state's `ctaEnabled` field is `false` AND NO appeal entry is surfaced AND the `appeal_token` is captured into the appeal-session holder

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

- **GIVEN** UI state is in a banned sign-in sub-state (suspension OR permanent) with `ctaEnabled = false`
- **WHEN** a synthetic Compose tap event is dispatched on the CTA node (some Compose configurations still synthesize click events on `Button(enabled=false)`)
- **THEN** `GoogleSignInClient.signIn()` is NOT invoked AND `AuthRepository.signInWithGoogle()` is NOT entered (the tap is rejected by the visual-AND-tap-handler-disable defense pair, not visual-disable-only)

#### Scenario: No error-state UI renders Google email or displayName

- **GIVEN** the in-flight `GoogleSignInResult.Success(idToken="g-id", displayName="Test User", email="test@example.com")` followed by ANY non-200 backend response that produces a `SignInScreen` error state (403 suspension, 403 permanent, 401, 5xx — note `404` no longer produces a `SignInScreen` error state; it sets `PendingSignupIdentity` and navigates to `AgeGateRoute`, whose PII discipline is covered by the `mobile-age-gate` capability)
- **WHEN** the resulting error-state UI is rendered
- **THEN** the rendered tree contains NO node whose text contains the substring `"test@example.com"` AND NO node whose text contains the substring `"Test User"` (the PII payload from `GoogleSignInResult.Success` is consumed only for the backend API call body; it is NOT plumbed into any error-state banner / disclaimer / debug text)

#### Scenario: Double-tap on CTA rejects the second concurrent invocation

- **GIVEN** `AuthRepository.signInWithGoogle()` is currently in-flight (first invocation suspended on the Google ceremony or the backend call)
- **WHEN** the user taps the CTA a second time within the same in-flight window
- **THEN** the second invocation either (a) is silently rejected via an `isInFlight` guard in `AuthRepository` (the first call completes normally; the second call returns immediately without re-invoking `GoogleSignInClient.signIn()`), OR (b) the CTA is visibly transitioned to a disabled / `signin_loading` state for the duration of the in-flight window (preventing a second tap). The implementation MAY choose either pattern; both prevent the race-condition outcome where TWO concurrent token writes hit `SecureTokenStore`
