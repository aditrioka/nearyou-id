# mobile-auth-signin — delta (consent-rootrouter-regate)

## MODIFIED Requirements

### Requirement: RootRouterScreen routes based on token presence

The navigation back stack SHALL be seeded with `RootRoute` as its start destination; `RootRoute` maps (via the `entryProvider`) to the `RootRouterScreen` composable (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/routing/RootRouterScreen.kt`). On first composition, `RootRouterScreen` SHALL read `SecureTokenStore.read()` once (in a `LaunchedEffect`-suspended scope, via `AuthRepository.isAuthenticated()`) and route via `backStack.replaceAll(...)` (the clear-and-set back-stack operation):

- If `read()` returns a non-null `TokenPair`, replace-all with `HomeRoute` — unless the device holds no consent snapshot (`ConsentSnapshotStore.read() == null`), in which case the consent re-gate interposes `ConsentRoute` instead (owned by `mobile-analytics-consent` § "RootRouter re-gates token-bearing users who never completed consent"). **The token gate itself remains PRESENCE-only** — it does NOT compare `accessExpiresAtEpochMillis` against the current time. Whether the persisted access token is still fresh or already expired is decided lazily, downstream, by the Ktor `Auth` plugin: `loadTokens` attaches the persisted access token, and on the first authenticated `401` `refreshTokens` exchanges the refresh token for a new pair (or, on refresh failure, `SessionInvalidator` clears the store + re-routes to `SignInRoute`). The stored `accessExpiresAtEpochMillis` is retained on the `TokenPair` for a future pre-emptive-refresh optimization in `loadTokens`, but is intentionally NOT a routing gate (the project cannot cheaply know the refresh-token's own expiry client-side, so "a `TokenPair` exists" is the routing signal and the backend is the authority on refresh-token validity).
- If `read()` returns `null`, replace-all with `SignInRoute`.

While the `read()` is in-flight, the screen SHALL render a splash composition (brand logo centered + `CircularProgressIndicator`); no token-bearing decisions are made before the read completes.

#### Scenario: Token present routes to HomeScreen

- **GIVEN** `SecureTokenStore` contains `TokenPair("at-X", "rt-Y", <future-epoch-millis>)` AND `ConsentSnapshotStore.read()` returns a persisted snapshot (consent was completed on this device)
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

- **GIVEN** `SecureTokenStore.read()` returns `TokenPair("at-X", "rt-Y", accessExpiresAtEpochMillis = now + 1)` (one millisecond in the future) AND a consent snapshot is present
- **WHEN** `RootRouterScreen` is composed
- **THEN** the routing decision is `HomeRoute` — the router gates on token PRESENCE (non-null `TokenPair`), NOT on the expiry comparison; a fresh access token simply means the Ktor `Auth` plugin won't need to refresh on the first authenticated call

#### Scenario: Persisted TokenPair with an already-expired access token still routes to HomeScreen

- **GIVEN** `SecureTokenStore.read()` returns `TokenPair("at-X", "rt-Y", accessExpiresAtEpochMillis = now)` (epoch equals current time, i.e. the access token is at/past expiry) AND a consent snapshot is present
- **WHEN** `RootRouterScreen` is composed
- **THEN** the routing decision is still `HomeRoute` — the presence-only gate does NOT distinguish fresh from expired access tokens; `RootRouterScreen` does NOT compare the expiry or pre-emptively refresh. The Ktor `Auth` plugin refreshes lazily on the first authenticated request (or, on refresh failure, `SessionInvalidator` clears the store + re-routes to `SignInRoute`). This is why both the `now + 1` and `now` cases route identically: routing is presence-driven, expiry handling is the Auth plugin's concern.

#### Scenario: Post-Banned process restart routes to SignInScreen with token cleared

- **GIVEN** a previous flow ended in `SignInOutcome.Banned` AND no token was persisted (per the `Banned` scenario, `SecureTokenStore.write` is NOT called) AND the user kills the app
- **WHEN** the app relaunches with `RootRoute` as the seeded start destination
- **THEN** `SecureTokenStore.read()` returns `null` AND the routing decision is `SignInRoute`; the previous `Banned` UI state is NOT restored across process death (which is correct — the Banned state is composition-local; the user MUST re-attempt sign-in on next launch to get the up-to-date banned status from the backend)
