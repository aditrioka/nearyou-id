## MODIFIED Requirements

### Requirement: The :infra:revenuecat module fences the RevenueCat SDK behind a commonMain PurchaseController interface

The change SHALL introduce a new KMP module `:infra:revenuecat` exposing a vendor-SDK-free commonMain `PurchaseController` interface over plain Kotlin domain models (no RevenueCat type leaks across the module boundary). The interface SHALL provide:

- `suspend fun fetchOfferings(): OfferingsResult`
- `suspend fun purchase(pkg: PaywallPackage): PurchaseResult`
- an entitlement check (`isPremiumEntitlementActive(): Boolean`), which SHALL read the CURRENT `CustomerInfo` from RevenueCat, not the SDK cache. Its callers are the post-purchase and pending rechecks, which exist because the cached `CustomerInfo` can trail the entitlement grant.
- the identity pair (added by `premium-entitlement-lifecycle`):
  - `suspend fun logIn(appUserId: String): Boolean` binds the RevenueCat app-user id to `appUserId` (`Purchases.logIn`) and returns `true` iff identified. It is a no-op returning `true` when already identified as that id, and returns `false` when the SDK is unconfigured or the call fails.
  - `suspend fun logOut()` returns to an anonymous identity (`Purchases.logOut`). It is a no-op when unconfigured or already anonymous.

  Neither identity method throws across the boundary.

`PurchaseResult` SHALL model `Success(entitlementActive)`, `Cancelled`, `Error(message)`, and `Pending`. `Pending` is the store's payment-pending outcome (the RevenueCat `PaymentPendingError`, e.g. a Play cash / convenience-store / carrier-billing payment awaiting settlement). The production `purchase(...)` SHALL refuse to purchase while the SDK identity is anonymous, returning `Error` rather than making a purchase the backend cannot attribute to a `users.id`.

The RevenueCat Kotlin Multiplatform SDK (`purchases-kmp-core`) SHALL be imported ONLY inside `:infra:revenuecat`, declared `implementation`-scoped so the vendor SDK never reaches `:mobile:app`'s compile classpath (invariant #16 — no vendor SDK import outside `:infra:*`). `:mobile:app` SHALL depend only on the `PurchaseController` interface. Platform SDK initialization (the Android `Application` context; the iOS configuration) SHALL be provided via per-platform Koin bindings (the established platform-module pattern, docs/11 §2.5), not an `expect class`. The module SHALL be added to `settings.gradle.kts`, the `purchases-kmp` version pinned in `gradle/libs.versions.toml`, and the module documented in `dev/module-descriptions.txt` with `dev/scripts/sync-readme.sh --write` run.

#### Scenario: The RevenueCat SDK does not leak onto the app compile classpath

- **WHEN** inspecting `:infra:revenuecat`'s and `:mobile:app`'s build files and the `PurchaseController` interface
- **THEN** the `purchases-kmp` dependency is declared in `:infra:revenuecat` as `implementation` (not `api`) AND `PurchaseController`'s signatures reference only plain Kotlin domain models (no RevenueCat SDK type) AND `:mobile:app` does not declare the `purchases-kmp` dependency

#### Scenario: The vendor-SDK-leakage scan stays green

- **WHEN** the `vendor-sdk-leakage-scan` lint runs over the change
- **THEN** no RevenueCat SDK import appears outside `:infra:revenuecat`

#### Scenario: A payment-pending store failure maps to Pending

- **WHEN** the production failure mapping receives a non-cancelled `PaymentPendingError` / a user cancellation / any other store error
- **THEN** it yields `PurchaseResult.Pending` / `PurchaseResult.Cancelled` / a retryable `PurchaseResult.Error` respectively

#### Scenario: The interface carries the identity pair and the Pending result

- **WHEN** inspecting `PurchaseController` and `PurchaseResult`
- **THEN** `PurchaseController` declares `logIn(appUserId: String): Boolean` and `logOut()` AND `PurchaseResult` declares a `Pending` member AND none of these reference a RevenueCat SDK type

### Requirement: The subscribe action drives the purchase and returns to the gated surface on success

Activating the "Aktifkan Premium" CTA SHALL invoke `PaywallViewModel`, which moves the UI state to a purchase-in-progress state (a single progress indicator; the CTA is disabled, no double-submit). It then SHALL first align the RevenueCat identity with the signed-in user via `PremiumEntitlementSession.syncIdentity()` (the `mobile-premium-entitlement` capability). If that returns `false`, the purchase SHALL NOT be attempted and the state SHALL surface the retryable purchase error. Otherwise it calls `PurchaseController.purchase(selectedPackage)`.

The ViewModel SHALL confirm the entitlement before claiming success:

- `Success(entitlementActive = true)` is confirmed.
- `Success(entitlementActive = false)` triggers exactly one `PurchaseController.isPremiumEntitlementActive()` recheck; `true` is confirmed, `false` is pending.
- `Pending` is pending.

On a confirmed entitlement the ViewModel SHALL invoke `PremiumEntitlementSession.onPurchaseConfirmed()` (publishing the account-scoped `purchaseConfirmed` signal the gated surfaces re-evaluate from) and signal completion, so the host pops `PaywallRoute` (a natural Nav3 back-stack pop returning to the surface that opened the paywall; `PendingReturnDestination` is NOT reused).

On pending, the state SHALL remain on the paywall with a `purchasePending` flag: the screen renders an informational pending message via `stringResource` (not error-styled, and never a success claim), and the CTA label becomes a "check status" label via `stringResource`. Activating the CTA while pending SHALL recheck the entitlement via `isPremiumEntitlementActive()` instead of re-purchasing; a `true` recheck is confirmed. Pending MUST NOT signal completion and MUST NOT publish the confirmed signal.

On a user cancellation the state SHALL return to Content (no error chrome). On a purchase error the state SHALL surface a retryable error (a message via `stringResource` + a retry affordance) and MUST NOT claim success.

The client entitlement (RevenueCat `CustomerInfo`) is authoritative for the client's post-purchase state. The server's `users.subscription_status` updates independently via the RevenueCat webhook (`subscription-billing-webhook`), so a subsequently re-attempted server-gated action MAY briefly still be gated until the webhook lands. The paywall success path MUST NOT block on the server flag and MUST NOT render a false "not Premium" error during that window.

#### Scenario: A successful purchase confirms entitlement and signals return

- **GIVEN** `PaywallViewModel` over a `FakePurchaseController` whose `purchase(...)` succeeds with an active entitlement and a `PremiumEntitlementSession`
- **WHEN** the subscribe action is invoked for the selected package
- **THEN** the state passes through purchase-in-progress and reaches Success AND the host return/pop lambda is signalled exactly once AND `purchaseConfirmed` becomes `true`

#### Scenario: An inactive entitlement that confirms on recheck still succeeds

- **GIVEN** `purchase(...)` returns `Success(entitlementActive = false)` AND `isPremiumEntitlementActive()` returns `true`
- **WHEN** the subscribe action is invoked
- **THEN** the state reaches Success AND the return signal fires AND `purchaseConfirmed` becomes `true`

#### Scenario: An inactive entitlement that stays inactive is pending, not success

- **GIVEN** `purchase(...)` returns `Success(entitlementActive = false)` AND `isPremiumEntitlementActive()` returns `false`
- **WHEN** the subscribe action is invoked
- **THEN** the Content state carries `purchasePending = true` AND the Success/return signal is NOT raised AND `purchaseConfirmed` stays `false`

#### Scenario: A payment-pending purchase is pending

- **GIVEN** `purchase(...)` returns `PurchaseResult.Pending`
- **WHEN** the subscribe action is invoked
- **THEN** the Content state carries `purchasePending = true` AND no error message is shown AND the return signal is NOT raised

#### Scenario: The CTA rechecks while pending instead of re-purchasing

- **GIVEN** the Content state carries `purchasePending = true` AND `isPremiumEntitlementActive()` now returns `true`
- **WHEN** the CTA is activated
- **THEN** `purchase(...)` is NOT invoked a second time AND the state reaches Success AND `purchaseConfirmed` becomes `true`

#### Scenario: The pending state renders informational copy and a check-status CTA

- **GIVEN** `PaywallScreen` rendered over a Content state with `purchasePending = true`
- **THEN** the rendered tree contains the pending message via `stringResource` AND the CTA is labelled with the check-status `stringResource` AND the purchase-error message is absent

#### Scenario: A failed identity sync blocks the purchase with a retryable error

- **GIVEN** a `PremiumEntitlementSession` whose `syncIdentity()` returns `false`
- **WHEN** the subscribe action is invoked
- **THEN** `purchase(...)` is NOT invoked AND the state surfaces the retryable purchase error AND the return signal is NOT raised

#### Scenario: A user cancellation returns to Content without error

- **GIVEN** `PaywallViewModel` over a `FakePurchaseController` whose `purchase(...)` reports user cancellation
- **WHEN** the subscribe action is invoked
- **THEN** the state returns to Content AND no error message is shown AND the return/pop lambda is NOT signalled

#### Scenario: A purchase error surfaces a retryable error and does not claim success

- **GIVEN** `PaywallViewModel` over a `FakePurchaseController` whose `purchase(...)` fails (non-cancellation)
- **WHEN** the subscribe action is invoked
- **THEN** the state is an error state exposing a `stringResource` message and a retry affordance AND the Success state is never entered AND the return/pop lambda is NOT signalled

### Requirement: PaywallViewModel is a commonMain ViewModel exposing one PaywallUiState StateFlow

The change SHALL ship a `PaywallViewModel` (androidx `ViewModel` in commonMain, obtained via `koinViewModel()`, scoped to the `PaywallRoute` NavEntry per docs/11 §2.2/§2.3) exposing exactly ONE `StateFlow<PaywallUiState>` via `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), <initial>)`. `PaywallUiState` SHALL be a Compose-free type produced by a pure, unit-testable projection covering: LoadingOfferings, Content (the derived packages + selected period), PurchaseInProgress, PurchasePending (the payment-pending / unconfirmed-entitlement state), Success, Error, and Unconfigured. One-shot effects (e.g. the return-on-success signal) SHALL be modelled as nullable state fields consumed via an `onXxxShown()` callback — NOT a `Channel`/`SharedFlow` event bus. The ViewModel SHALL launch all purchase/offering work in `viewModelScope` (no work launched from composables, no `GlobalScope`).

#### Scenario: The ViewModel exposes a single StateFlow and maps offerings deterministically

- **GIVEN** `PaywallViewModel` over a `FakePurchaseController` returning loaded packages
- **WHEN** the offerings load completes
- **THEN** the single `StateFlow<PaywallUiState>` emits LoadingOfferings then Content with the derived packages AND the projection is deterministic (no wall-clock / platform dependency)

#### Scenario: Pending is a state field, not an event

- **WHEN** inspecting `PaywallUiState.Content`
- **THEN** the pending state is a `purchasePending: Boolean` field alongside `purchaseInProgress` / `purchaseError` / `purchaseSucceeded` AND no `Channel`/`SharedFlow` carries it
