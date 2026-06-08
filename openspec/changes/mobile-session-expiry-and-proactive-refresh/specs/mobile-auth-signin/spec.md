## ADDED Requirements

### Requirement: Terminal-401 re-route to SignInScreen is delivered reliably

The mobile app SHALL deliver the terminal-401 session-invalidation signal (refresh failed → store cleared) to the re-route observer such that the signal is NEVER lost due to subscriber timing — including when `SessionInvalidator.invalidate()` is invoked **before** the re-route observer (`SessionExpiryEffect`) begins collecting (e.g. during cold start, or while no screen hosting the observer is composed). The delivery mechanism SHALL buffer a pre-subscription signal and deliver it to the first collector, and SHALL be consume-once: after a signal is delivered and the user subsequently re-authenticates, a freshly-mounted observer MUST NOT replay a stale invalidation (no spurious re-route loop). This is a NEW delivery-reliability guarantee: the existing "Ktor HTTP client attaches Bearer token and handles 401 refresh" requirement (and its "Refresh failure produces terminal 401 + store cleared by AuthRepository" scenario) establishes THAT a re-route fires on terminal 401 but does not constrain delivery reliability against subscriber timing; that existing behavior is unchanged and is not contradicted here.

#### Scenario: invalidate() before the observer subscribes still re-routes
- **GIVEN** `SessionInvalidator.invalidate()` is invoked before any `SessionExpiryEffect` collector is subscribed
- **WHEN** the collector subsequently subscribes
- **THEN** it observes the buffered invalidation AND performs `replaceAll(SignInRoute)` (the signal is not dropped)

#### Scenario: consume-once — no stale replay after re-login
- **GIVEN** one invalidation has been delivered and consumed AND the user has signed back in
- **WHEN** a fresh `SessionExpiryEffect` collector subscribes
- **THEN** it does NOT immediately re-route to `SignInScreen` (the prior invalidation is not replayed)

#### Scenario: signal carrier is not a replay=0 SharedFlow
- **WHEN** inspecting `SessionInvalidator`'s signal carrier
- **THEN** it is a buffered consume-once carrier (a `Channel`/`receiveAsFlow` or equivalent) — NOT a `MutableSharedFlow(replay = 0)` whose emission is lost when there is no active subscriber

### Requirement: Involuntary logout shows a session-expired notice on SignInScreen

When the app routes to `SignInScreen` because of an **involuntary** session invalidation (terminal 401), the screen SHALL display a session-expired notice sourced from CMP Resources (a new key, e.g. `signin_session_expired` = "Sesi kamu berakhir. Masuk lagi untuk lanjut."). The notice MUST be distinct from the connectivity-error string (`signin_error_network`) and MUST NOT appear on a fresh launch (a normal cold start with no prior session shows no notice). All copy via `stringResource` — no hardcoded UI text (per the mobile-strings invariant). The involuntary-entry signal SHALL be carried to the screen without persistence (a NavKey argument or the in-memory re-auth holder), not inferred from token state alone.

#### Scenario: involuntary re-route renders the session-expired notice
- **GIVEN** `SignInScreen` is entered via a terminal-401 re-route
- **THEN** the rendered tree contains a node whose text matches `stringResource(Res.string.signin_session_expired)` AND does NOT contain `stringResource(Res.string.signin_error_network)`

#### Scenario: fresh launch shows no session-expired notice
- **GIVEN** `SignInScreen` is entered on a normal cold launch with no prior session (not via an involuntary re-route)
- **THEN** the session-expired notice is NOT rendered

#### Scenario: no hardcoded session-expired string in source
- **WHEN** scanning `SignInScreen` source (comments stripped)
- **THEN** the session-expired copy appears only via `stringResource(Res.string.signin_session_expired)`, with no inline string literal

### Requirement: Proactive preemptive token refresh on app resume within the expiry window

The mobile app SHALL, on each app-root `Lifecycle.Event.ON_RESUME` (which covers both the cold-start first resume and every foreground return), read the stored `TokenPair.accessExpiresAtEpochMillis` and, when the access token is within **5 minutes** of expiry (or already expired) AND a token pair exists, trigger a single proactive refresh **asynchronously and non-blocking** — it MUST NOT block composition or the first frame. The proactive refresh SHALL share the **single-flight** refresh path with the reactive (Ktor `Auth` bearer) refresh, so that concurrent proactive + reactive attempts perform **exactly ONE** network `POST /api/v1/auth/refresh` (preserving the existing "Concurrent 401s … retry once" guarantee). Reactive 401-triggered refresh remains the fallback and is otherwise unchanged. A proactive refresh whose refresh token is rejected SHALL invoke the same `SessionInvalidator` path (→ the reliable re-route above). This realizes the preemptive-refresh behavior prescribed by `docs/05-Implementation.md` § Session management (line 38), which the client previously stored the expiry for but never acted on. The lifecycle hook SHALL be implemented in commonMain via the already-present `lifecycle-runtime-compose` (no new dependency, no vendor SDK import outside `:infra:*`).

#### Scenario: resume within the expiry window refreshes once, non-blocking
- **GIVEN** a stored `TokenPair` whose access token expires in less than 5 minutes
- **WHEN** the app-root `ON_RESUME` fires
- **THEN** exactly one `POST /api/v1/auth/refresh` is issued off the UI/composition path AND subsequent requests carry the refreshed access token (no user-visible 401→refresh round-trip)

#### Scenario: resume outside the expiry window does not refresh
- **GIVEN** a stored `TokenPair` whose access token expires in more than 5 minutes
- **WHEN** the app-root `ON_RESUME` fires
- **THEN** no proactive `POST /api/v1/auth/refresh` is issued

#### Scenario: single-flight across proactive and reactive refresh
- **GIVEN** a proactive refresh is in flight
- **WHEN** a concurrent authenticated request receives a 401 and triggers the reactive refresh
- **THEN** exactly ONE network `POST /api/v1/auth/refresh` occurs (the second path awaits the in-flight refresh and reuses its result)

#### Scenario: proactive refresh with a rejected refresh token re-routes
- **GIVEN** the app-root `ON_RESUME` triggers a proactive refresh
- **WHEN** the refresh endpoint returns 401 (refresh token expired/revoked)
- **THEN** `SessionInvalidator.invalidate()` runs AND the app reliably re-routes to `SignInScreen` (with the session-expired notice)

### Requirement: Post-re-auth destination preservation

After an involuntary session invalidation, the app SHALL capture the user's current destination **before** re-routing to `SignInScreen`, and on a successful re-authentication SHALL return the user to that captured destination rather than `HomeRoute` — except when the captured destination is itself an auth/sign-in route, in which case it SHALL fall back to `HomeRoute`. The captured destination SHALL be held **in memory only** (a Koin `single` holder mirroring the existing `PendingSignupIdentity` pattern — never persisted, never placed on a NavKey) and SHALL be cleared once consumed. A restored destination that needs fresh data re-fetches on mount.

#### Scenario: destination restored after involuntary re-auth
- **GIVEN** the user was on a non-auth destination when an involuntary terminal-401 occurred AND the destination was captured before the re-route
- **WHEN** the user re-authenticates successfully
- **THEN** the app navigates to the captured destination (not `HomeRoute`) AND the captured destination is cleared

#### Scenario: captured auth route falls back to Home
- **GIVEN** the captured destination is an auth/sign-in route
- **WHEN** re-authentication succeeds
- **THEN** the app navigates to `HomeRoute` (it does not restore into the auth flow)

#### Scenario: captured destination is in-memory only
- **WHEN** inspecting the destination-capture holder
- **THEN** it is an in-memory Koin `single` (mirroring `PendingSignupIdentity`) that is not persisted to disk and is not carried on a NavKey
