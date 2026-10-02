## ADDED Requirements

### Requirement: The RevenueCat app-user identity is bound to the signed-in users.id

The mobile app SHALL keep the RevenueCat app-user id equal to the signed-in user's `users.id` (the access token's `sub`, resolved through the existing `SelfUserIdProvider`) whenever a session exists, and anonymous whenever it does not. A `PremiumEntitlementSession` Koin single (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/billing/PremiumEntitlementSession.kt`) SHALL own a `suspend fun syncIdentity(): Boolean` that resolves the signed-in id and calls `PurchaseController.logIn(id)` when one exists, or `PurchaseController.logOut()` when none does. It returns `true` iff RevenueCat is now identified as the signed-in user. `syncIdentity()` SHALL be idempotent (a repeat call for the already-bound id, or a sign-out while already anonymous, performs no identity change) and SHALL NOT throw. Concurrent calls SHALL be serialized. The RevenueCat SDK SHALL remain configured anonymously at startup (`configureRevenueCat(appUserId = null)`); binding happens only through `logIn`.

#### Scenario: A signed-in session logs RevenueCat in as the users.id

- **GIVEN** a `PremiumEntitlementSession` over a `SelfUserIdProvider` returning `"u-1"` and a `FakePurchaseController`
- **WHEN** `syncIdentity()` is invoked
- **THEN** `logIn("u-1")` is invoked exactly once AND `syncIdentity()` returns `true` AND `logOut()` is not invoked

#### Scenario: No session logs RevenueCat out to anonymous

- **GIVEN** a `SelfUserIdProvider` returning `null` (no stored token)
- **WHEN** `syncIdentity()` is invoked
- **THEN** `logOut()` is invoked AND `logIn(...)` is not invoked AND `syncIdentity()` returns `false`

#### Scenario: A failed logIn reports not-identified without throwing

- **GIVEN** a signed-in id and a `PurchaseController` whose `logIn(...)` returns `false` (network / SDK failure)
- **WHEN** `syncIdentity()` is invoked
- **THEN** it returns `false` AND no exception propagates

### Requirement: Identity is synced at every session boundary

`syncIdentity()` SHALL be invoked at each point where the session begins, is restored, or ends:

1. Sign-in and sign-up success (`AuthRepository`): after the token pair is persisted and before the success outcome is returned.
2. Voluntary logout (`SettingsViewModel.confirmLogout`): inside the unconditional, non-cancellable client wipe, after the token store is cleared.
3. Involuntary session invalidation: when the app-root `SessionExpiryEffect` consumes the session-expired signal, after its re-route to sign-in, launched so it never stalls the collector. It MUST NOT run inside `SessionInvalidator.invalidate`, which executes within `TokenRefresher`'s single-flight critical section; a vendor round-trip there would hold every request waiting on the refresh.
4. Every app-root `Lifecycle.Event.ON_RESUME` (a new `BillingIdentityEffect` hosted next to `ProactiveRefreshEffect` in `App.kt`), covering cold-start session restore and foreground return, and self-healing a `logIn` that previously failed. Fire-and-forget on the app-root coroutine scope; a failure MUST NOT crash the scope, and `CancellationException` is re-thrown.
5. Immediately before a purchase (`PaywallViewModel`, per `mobile-paywall`).

Sign-in, logout, and invalidation MUST complete their existing behavior when identity sync fails: a failed sync never blocks sign-in success, the logout wipe, or the re-route.

#### Scenario: Sign-in success binds the new identity

- **GIVEN** `AuthRepository` wired with a `PremiumEntitlementSession` over a `FakePurchaseController`
- **WHEN** `signInWithGoogle()` completes with a `200` token pair whose access token `sub` is `"u-1"`
- **THEN** the outcome is `SignInOutcome.Success` AND `logIn("u-1")` was invoked on the controller

#### Scenario: Sign-up success binds the new identity

- **WHEN** `signUpWithGoogle(...)` completes with a `201` token pair whose access token `sub` is `"u-2"`
- **THEN** the outcome is `SignUpOutcome.Success` AND `logIn("u-2")` was invoked on the controller

#### Scenario: Voluntary logout unbinds the identity

- **GIVEN** a signed-in `SettingsViewModel` wired with a `PremiumEntitlementSession`
- **WHEN** `confirmLogout()` runs (whether the server revoke succeeds or fails)
- **THEN** the token store is cleared AND `loggedOut` becomes `true` AND `logOut()` was invoked on the controller

#### Scenario: Involuntary invalidation unbinds the identity

- **GIVEN** `SessionExpiryEffect` composed over a back stack, with a bound `PremiumEntitlementSession` resolvable from Koin
- **WHEN** `SessionInvalidator.invalidate()` runs
- **THEN** the back stack is re-routed to `SignInRoute` AND `logOut()` is invoked on the controller AND `SessionInvalidator` itself makes no RevenueCat call

#### Scenario: A restored session is bound on the first resume

- **GIVEN** a token already persisted at cold start (no sign-in call this process) and `BillingIdentityEffect` composed
- **WHEN** the first `ON_RESUME` fires
- **THEN** `logIn(<the token's sub>)` is invoked on the controller

#### Scenario: A throwing vendor call never escapes syncIdentity

- **GIVEN** a `PurchaseController` whose `logIn` / `logOut` throws a non-`PurchasesException` error
- **WHEN** `syncIdentity()` is invoked signed in or signed out
- **THEN** it returns `false` AND no exception propagates

#### Scenario: A failed identity sync does not block sign-in

- **GIVEN** a `PurchaseController` whose `logIn(...)` returns `false`
- **WHEN** `signInWithGoogle()` completes with a `200` token pair
- **THEN** the outcome is still `SignInOutcome.Success` AND the tokens are persisted

### Requirement: A confirmed purchase publishes an account-scoped purchaseConfirmed signal

`PremiumEntitlementSession` SHALL also expose `sessionKey()`: the signed-in user id plus a count of ended sessions, or null when signed out. A sign-out followed by a sign-in, even into the same account, yields a new key; per-session caches (the ad-eligibility latch) key on it.

`PremiumEntitlementSession` SHALL expose `purchaseConfirmed: StateFlow<Boolean>`. It is `false` initially, set to `true` by `onPurchaseConfirmed()` (invoked by the paywall on a confirmed entitlement, per `mobile-paywall`), and reset to `false` by `syncIdentity()` whenever the resolved signed-in id differs from the id it last bound (sign-out, account switch). The signal SHALL be modelled as state, not an event stream (docs/11 §2.2): a Premium-gated surface created after the purchase reads `true` immediately, and a surface alive across the purchase observes the transition. It represents the client entitlement (RevenueCat `CustomerInfo`), which is authoritative for client gating while the server's webhook-driven `subscription_status` catches up. It MUST NOT outlive the account that purchased.

#### Scenario: A confirmed purchase sets the signal

- **GIVEN** a signed-in `PremiumEntitlementSession` whose `purchaseConfirmed` is `false`
- **WHEN** `onPurchaseConfirmed()` is invoked
- **THEN** `purchaseConfirmed.value` is `true`

#### Scenario: The signal survives a same-account resync

- **GIVEN** `purchaseConfirmed` is `true` for signed-in user `"u-1"`
- **WHEN** `syncIdentity()` runs again while `"u-1"` is still signed in (e.g. a foreground resume)
- **THEN** `purchaseConfirmed.value` remains `true`

#### Scenario: Sign-out resets the signal so the next account does not inherit it

- **GIVEN** `purchaseConfirmed` is `true` for signed-in user `"u-1"`
- **WHEN** the session is cleared and `syncIdentity()` runs, then user `"u-2"` signs in and `syncIdentity()` runs
- **THEN** `purchaseConfirmed.value` is `false` after the sign-out sync AND remains `false` after the `"u-2"` sync

### Requirement: Premium-gated surfaces resolve the signal fail-safe

Every screen that hands `purchaseConfirmed` to its ViewModel (Nearby, username, search, paywall) and the `AdFeedController` binding SHALL resolve `PremiumEntitlementSession` via a fail-safe lookup (`getKoin().getOrNull<PremiumEntitlementSession>()`, the `TimelineAds` precedent). If it is not bound (a screen test that installs its own Koin module, or a DI gap), the surface falls back to a never-confirmed `false` signal and behaves exactly as before this change: no crash, no `NoDefinitionFound`. Production binds `PremiumEntitlementSession` as a single in `mobileModule`.

#### Scenario: An unbound session degrades to never-confirmed

- **GIVEN** a screen test whose Koin module does not bind `PremiumEntitlementSession`
- **WHEN** the Nearby / username / search / paywall screen composes
- **THEN** it renders without a resolution error AND its Premium gate behaves as if no purchase was confirmed

#### Scenario: Production binds the session as a Koin single

- **WHEN** inspecting `mobileModule`
- **THEN** `PremiumEntitlementSession` is declared as a `single` over `SelfUserIdProvider` + `PurchaseController` AND the same instance is passed to `AuthRepository`, `SessionInvalidator`, and (via the fail-safe lookup) the gated screens and `AdFeedController`

### Requirement: Test coverage for the entitlement lifecycle

The change SHALL ship commonTest coverage for:

- **(1)** `PremiumEntitlementSession`: signed-in → `logIn(sub)`; signed-out → `logOut()`; `logIn` failure → `false`; the confirmed signal set, surviving a same-account resync, and reset on sign-out / account switch.
- **(2)** The session-boundary call sites (`AuthRepository` sign-in + sign-up, `SettingsViewModel` logout, `SessionExpiryEffect` invalidation, `BillingIdentityEffect` resume), each asserting the controller identity call, and, for sign-in and sign-up, that the boundary's existing behavior still completes when sync fails.
- **(3)** The re-gating consumers specified in `mobile-paywall`, `mobile-nearby-radius-slider`, `mobile-premium-username`, `mobile-search`, and `mobile-ads`.

The real `RevenueCatPurchaseController` identity calls cannot be unit-tested without the provisioned SDK/store. Their live behavior (sign-in → RevenueCat customer = `users.id`; sandbox purchase → webhook `200 ok` → `subscription_status = 'premium_active'`; sign-out → anonymous) SHALL be a documented MANUAL staging verification recorded in `tasks.md`, never skip-rationalized.

#### Scenario: The lifecycle tests are discoverable

- **WHEN** running `./gradlew :mobile:app:testDevDebugUnitTest`
- **THEN** a `PremiumEntitlementSessionTest` is discovered AND each boundary call site above has at least one `@Test` asserting its identity call

#### Scenario: The manual staging verification boundary is recorded

- **WHEN** inspecting `tasks.md`
- **THEN** it states the staging sandbox purchase verification of attribution (RevenueCat customer id = `users.id`, webhook `200 ok`, `subscription_status` flips) as an explicit operator task
