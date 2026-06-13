## ADDED Requirements

### Requirement: PaywallRoute is a payload-carrying serializable NavKey registered for the iOS-saveable back stack

The change SHALL introduce a `PaywallRoute` `NavKey` (in `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/routing/NavKeys.kt`) that is a `@Serializable data class` carrying a single non-PII `entry: PaywallEntry` property — an enum of the gated surfaces that can open the paywall (at minimum `LIKE_CAP` and `SEARCH_GATE`; `USERNAME` reserved for the in-flight `premium-username-customization`). The route MUST NOT carry any PII, token, coordinate, or user identifier. It SHALL be registered in the `navSavedStateConfiguration` polymorphic `SerializersModule` (`mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/routing/AppNavSerialization.kt`) via an explicit `subclass(PaywallRoute::class, PaywallRoute.serializer())` entry so the back stack is saveable on Kotlin/Native (iOS), mirroring `PostDetailRoute`. `PaywallRoute` SHALL be appended to the **root** back stack (overlaying the section `NavigationBar`), the same mechanism `SearchRoute` / `PostDetailRoute` / the composer FAB use — deliberately NOT a per-tab back stack.

#### Scenario: PaywallRoute survives a serialized back-stack round-trip

- **WHEN** a back stack containing `PaywallRoute(entry = LIKE_CAP)` is serialized via `navSavedStateConfiguration` and restored
- **THEN** the restored entry is a `PaywallRoute` whose `entry` is `LIKE_CAP` (the polymorphic `subclass(...)` registration makes it decode on Kotlin/Native)

#### Scenario: PaywallRoute carries only the non-PII entry-context

- **WHEN** inspecting the `PaywallRoute` declaration
- **THEN** its only property is the `PaywallEntry` enum AND it declares no `latitude`/`longitude`, no token, no user id, and no other identity payload

### Requirement: PaywallScreen renders the frame-17 paywall surface and is navigation-free

The mobile app SHALL ship a composable `PaywallScreen` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/paywall/PaywallScreen.kt`), mapped from the `PaywallRoute` `NavKey` by the `appEntryProvider`, rendering the paywall per the canonical mockup (frame 17, `dev/mockups/nearyou-screens-mockup.html`, binding for look/layout per docs/11 §2.8): a top app bar with a close (X) affordance; a Premium hero (the `workspace_premium` premium-accent icon + a "NearYouID Premium" heading); the benefit list (§ "The paywall benefit set"); the pricing cards (§ "Pricing, anchors, and savings are derived"); a full-width primary "Aktifkan Premium" CTA; and the disclosure footer (§ "The disclosure footer"). The hero headline SHALL be tailored to the route's `PaywallEntry` (e.g. a like-cap entry leads with the unlimited-engagement benefit, a search-gate entry leads with search) while always presenting the full Premium offering. `PaywallScreen` SHALL be navigation-free: it holds no back-stack reference; its close affordance invokes a hoisted `onClose` lambda and a successful purchase invokes a hoisted `onPurchaseComplete` (or equivalent return) lambda. No hardcoded UI string literals SHALL appear in the screen source (every `Text` / `contentDescription` resolves via `stringResource(Res.string.<name>)`); colors and typography SHALL come from `NearYouTheme` tokens (no hex literals); the screen SHALL render under both light and dark schemes.

#### Scenario: The paywall renders the frame-17 surface and is navigation-free

- **GIVEN** `PaywallScreen` composed for `PaywallRoute(entry = LIKE_CAP)` over a Content state with loaded packages under `NearYouTheme`
- **THEN** the rendered tree contains the Premium hero, the benefit list, the three pricing cards, a primary CTA labelled `stringResource(Res.string.cta_activate_premium)`, and a close affordance bound to the hoisted `onClose` AND the screen holds no back-stack reference (navigation is delivered via the hoisted lambdas only)

#### Scenario: No hardcoded UI strings and token-only styling

- **WHEN** inspecting `PaywallScreen.kt`
- **THEN** every user-visible text resolves via `stringResource(Res.string.<name>)` AND the source contains no hex color literals (theme tokens only) AND the screen renders without crash under `NearYouTheme` light and dark

### Requirement: The paywall benefit set shows features available now per the disclosure rule

The benefit list SHALL present the Month-1 Premium feature set per `docs/01-Business.md` § Freemium Tiers / `docs/02-Product.md`, each label via `stringResource` — covering: unlimited posts/replies/likes, the 10–100 km Nearby radius, hide-distance (city name stays visible), custom username (1× per 30 days), search + 30-minute post edit, and no-ads + the Premium tenure badge. It MUST NOT advertise image upload (a Month-6 feature, not yet shipped) — the `docs/03-UX-Design.md` § Paywall & Premium Disclosure rule that "the paywall shows features available NOW". Each benefit label is a CMP string resource (no hardcoded literal).

#### Scenario: The benefit list shows now-available features and omits image upload

- **GIVEN** `PaywallScreen` composed in the Content state
- **THEN** the rendered tree contains the now-available Premium benefit rows (each via `stringResource`) AND contains no node advertising image/photo upload

### Requirement: Pricing, anchors, and savings are derived from RevenueCat Offerings, not hardcoded

The pricing cards SHALL render their values from the RevenueCat Offering packages fetched via `PurchaseController.fetchOfferings()` — Weekly, Monthly, and Yearly (the Daily tier is dropped for cross-platform parity per `docs/01-Business.md`). The displayed price for each card SHALL be the store-localized price string from that package; the screen MUST NOT hardcode the rupiah price values (the `docs/01-Business.md` § Multi-Period Pricing figures are explicitly "target, verify Pre-Phase 1", not the runtime source of truth). The frame-17 price treatment SHALL be COMPUTED from the package prices via a pure, unit-testable commonMain helper: the Monthly card is default-selected; the strike-through anchor is the Monthly price compared to 4× the Weekly price and the Yearly price compared to 12× the Monthly price; the savings percentage and the Weekly per-day baseline (Weekly price ÷ 7) are derived from those values; the "Paling hemat" tag is on Yearly. Only the static labels (period names, "Hemat", "per hari", "Paling hemat") are `stringResource` values. The mockup governs the card LAYOUT; the package data governs the VALUES (docs/11 §2.8 precedence).

#### Scenario: Cards render store-localized prices with no hardcoded rupiah values

- **GIVEN** a `FakePurchaseController` returning Weekly/Monthly/Yearly packages with known localized price strings
- **WHEN** `PaywallScreen` renders the Content state
- **THEN** each card shows its package's localized price string AND the `PaywallScreen` source contains no hardcoded rupiah price literal AND the Monthly card is selected by default

#### Scenario: Anchors and savings are derived from the package prices

- **WHEN** the pure price-derivation helper is invoked with Weekly/Monthly/Yearly package prices
- **THEN** it computes the Monthly strike-anchor as 4× Weekly and the Yearly strike-anchor as 12× Monthly, the savings percentages from those anchors, and the Weekly per-day as Weekly ÷ 7 — deterministically, with no hardcoded percentage or price

### Requirement: The :infra:revenuecat module fences the RevenueCat SDK behind a commonMain PurchaseController interface

The change SHALL introduce a new KMP module `:infra:revenuecat` exposing a vendor-SDK-free commonMain `PurchaseController` interface — `suspend fun fetchOfferings(): OfferingsResult`, `suspend fun purchase(pkg: PaywallPackage): PurchaseResult`, and an entitlement check (`isPremiumEntitlementActive(): Boolean` or an entitlement state read) — over plain Kotlin domain models (no RevenueCat type leaks across the module boundary). The RevenueCat Kotlin Multiplatform SDK (`purchases-kmp-core`) SHALL be imported ONLY inside `:infra:revenuecat`, declared `implementation`-scoped so the vendor SDK never reaches `:mobile:app`'s compile classpath (invariant #16 — no vendor SDK import outside `:infra:*`). `:mobile:app` SHALL depend only on the `PurchaseController` interface. Platform SDK initialization (the Android `Application` context; the iOS configuration) SHALL be provided via per-platform Koin bindings (the established platform-module pattern, docs/11 §2.5), not an `expect class`. The module SHALL be added to `settings.gradle.kts`, the `purchases-kmp` version pinned in `gradle/libs.versions.toml`, and the module documented in `dev/module-descriptions.txt` with `dev/scripts/sync-readme.sh --write` run.

#### Scenario: The RevenueCat SDK does not leak onto the app compile classpath

- **WHEN** inspecting `:infra:revenuecat`'s and `:mobile:app`'s build files and the `PurchaseController` interface
- **THEN** the `purchases-kmp` dependency is declared in `:infra:revenuecat` as `implementation` (not `api`) AND `PurchaseController`'s signatures reference only plain Kotlin domain models (no RevenueCat SDK type) AND `:mobile:app` does not declare the `purchases-kmp` dependency

#### Scenario: The vendor-SDK-leakage scan stays green

- **WHEN** the `vendor-sdk-leakage-scan` lint runs over the change
- **THEN** no RevenueCat SDK import appears outside `:infra:revenuecat`

### Requirement: The subscribe action drives the purchase and returns to the gated surface on success

Activating the "Aktifkan Premium" CTA SHALL invoke `PaywallViewModel`, which calls `PurchaseController.purchase(selectedPackage)` and moves the UI state to a purchase-in-progress state (a single progress indicator; the CTA is disabled, no double-submit). On a successful purchase the ViewModel SHALL confirm the entitlement is active (via `CustomerInfo`) and signal completion so the host pops `PaywallRoute` (a natural Nav3 back-stack pop returning to the surface that opened the paywall; `PendingReturnDestination` is NOT reused). On a user cancellation the state SHALL return to Content (no error chrome). On a purchase error the state SHALL surface a retryable error (a message via `stringResource` + a retry affordance) and MUST NOT claim success. The client entitlement (RevenueCat `CustomerInfo`) is authoritative for the client's post-purchase state; the server's `users.subscription_status` updates independently via the RevenueCat webhook (`subscription-billing-webhook`), so a subsequently re-attempted server-gated action MAY briefly still be gated until the webhook lands — the paywall success path MUST NOT block on the server flag and MUST NOT render a false "not Premium" error during that window.

#### Scenario: A successful purchase confirms entitlement and signals return

- **GIVEN** `PaywallViewModel` over a `FakePurchaseController` whose `purchase(...)` succeeds with an active entitlement
- **WHEN** the subscribe action is invoked for the selected package
- **THEN** the state passes through purchase-in-progress and reaches Success AND the host return/pop lambda is signalled exactly once

#### Scenario: A user cancellation returns to Content without error

- **GIVEN** `PaywallViewModel` over a `FakePurchaseController` whose `purchase(...)` reports user cancellation
- **WHEN** the subscribe action is invoked
- **THEN** the state returns to Content AND no error message is shown AND the return/pop lambda is NOT signalled

#### Scenario: A purchase error surfaces a retryable error and does not claim success

- **GIVEN** `PaywallViewModel` over a `FakePurchaseController` whose `purchase(...)` fails (non-cancellation)
- **WHEN** the subscribe action is invoked
- **THEN** the state is an error state exposing a `stringResource` message and a retry affordance AND the Success state is never entered AND the return/pop lambda is NOT signalled

### Requirement: The paywall degrades to a fail-soft Unconfigured state when Offerings are unavailable

When `PurchaseController.fetchOfferings()` returns no usable packages — the RevenueCat SDK is not configured, no offering is published, or the fetch fails (the expected state until the operator provisions the RevenueCat dashboard, store products, and API-key secret slots) — `PaywallScreen` SHALL render a graceful Unconfigured state via `stringResource` (a "Premium belum tersedia" message and a close affordance), and the subscribe CTA SHALL be absent or disabled. The screen MUST NOT crash and MUST NOT present a purchasable card with a fabricated price. This keeps the change shippable and CI/sandbox-green; the live Offerings/purchase path activates with no code change once provisioning lands.

#### Scenario: Empty/unavailable Offerings render the Unconfigured state, not a crash or fake card

- **GIVEN** `PaywallScreen` over a `FakePurchaseController` whose `fetchOfferings()` returns the empty/unavailable result
- **WHEN** the screen renders
- **THEN** it shows the Unconfigured `stringResource` message and a close affordance AND renders no purchasable price card AND does not crash

### Requirement: PaywallViewModel is a commonMain ViewModel exposing one PaywallUiState StateFlow

The change SHALL ship a `PaywallViewModel` (androidx `ViewModel` in commonMain, obtained via `koinViewModel()`, scoped to the `PaywallRoute` NavEntry per docs/11 §2.2/§2.3) exposing exactly ONE `StateFlow<PaywallUiState>` via `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), <initial>)`. `PaywallUiState` SHALL be a Compose-free type produced by a pure, unit-testable projection covering: LoadingOfferings, Content (the derived packages + selected period), PurchaseInProgress, Success, Error, and Unconfigured. One-shot effects (e.g. the return-on-success signal) SHALL be modelled as nullable state fields consumed via an `onXxxShown()` callback — NOT a `Channel`/`SharedFlow` event bus. The ViewModel SHALL launch all purchase/offering work in `viewModelScope` (no work launched from composables, no `GlobalScope`).

#### Scenario: The ViewModel exposes a single StateFlow and maps offerings deterministically

- **GIVEN** `PaywallViewModel` over a `FakePurchaseController` returning loaded packages
- **WHEN** the offerings load completes
- **THEN** the single `StateFlow<PaywallUiState>` emits LoadingOfferings then Content with the derived packages AND the projection is deterministic (no wall-clock / platform dependency)

### Requirement: The disclosure footer renders the verbatim disclosure clause

`PaywallScreen` SHALL render a disclosure footer via `stringResource` carrying the verbatim user-facing disclosure clause from `docs/01-Business.md` § Pricing & Payment — "Fitur Premium dapat berubah atau ditambahkan seiring waktu." — which is the same text shown as the frame-17 footer line. This satisfies the `docs/03-UX-Design.md` § Paywall & Premium Disclosure mandate (the Months-1-5 rule that the paywall shows only features available now, with no image-upload mention). The footer MUST be present in every purchasable (Content) rendering, and the string value MUST match the canonical clause verbatim (no invented copy).

#### Scenario: The disclosure footer is present in the Content state

- **GIVEN** `PaywallScreen` rendered in the Content state
- **THEN** the rendered tree contains the disclosure footer text via `stringResource` carrying the verbatim docs/01 clause ("Fitur Premium dapat berubah atau ditambahkan seiring waktu.")

### Requirement: The paywall graph is registered in Koin behind testable seams

`PurchaseController` SHALL be bound in Koin so the production `:infra:revenuecat` implementation is injected in the app and a `FakePurchaseController` substitutes in commonTest (mirroring the `SearchFlow`/`ProfileFlow` seams). `PaywallViewModel` SHALL be registered for the `PaywallRoute` NavEntry. No screen or ViewModel SHALL construct the RevenueCat binding directly.

#### Scenario: Koin binds PurchaseController behind an interface and registers the ViewModel

- **WHEN** inspecting the mobile Koin module(s)
- **THEN** `PurchaseController` is bound to the `:infra:revenuecat` implementation AND `PaywallViewModel` is registered for the `PaywallRoute` entry AND commonTest can substitute a `FakePurchaseController`

### Requirement: Test coverage for the paywall screen, projection, price derivation, and purchase flow

The change SHALL ship: (1) a commonTest `PaywallUiStateTest` / `PaywallViewModelTest` over a `FakePurchaseController` covering LoadingOfferings → Content, the price-derivation helper (anchors, savings %, per-day, default-selected Monthly), the purchase success → return signal, the user-cancellation → Content, the purchase-error → retryable error, and the empty-offerings → Unconfigured mapping; (2) a Robolectric `PaywallScreenTest` (`mobile/app/src/androidUnitTest/...`, v2 ComposeUiTest API) covering the frame-17 surface render (hero, benefits, three cards with localized prices, CTA, disclosure footer), the close affordance, the Unconfigured state, and the no-hardcoded-strings/token-only assertions — ADDED to the `mobile/app/build.gradle.kts` Release-variant test-exclude list (verify `:mobile:app:testDevReleaseUnitTest` passes); (3) an `iosTest` flow test exercising the paywall data seam on Kotlin/Native via the `FakePurchaseController` (the per-screen `*FlowIosTest` convention). The real `:infra:revenuecat` RevenueCat binding cannot be unit-tested without the provisioned SDK/store; its live behavior is a documented MANUAL post-provisioning verification (stated explicitly in `tasks.md`, never skip-rationalized).

#### Scenario: Test classes exist and are discoverable

- **WHEN** running `./gradlew :mobile:app:testDevDebugUnitTest`
- **THEN** `PaywallViewModelTest`/`PaywallUiStateTest`, the price-derivation test, `PaywallScreenTest`, and the iOS flow test are discovered AND each documented behavior above corresponds to at least one `@Test`

#### Scenario: The screen test is excluded from the Release variant

- **WHEN** inspecting `mobile/app/build.gradle.kts`
- **THEN** the Release-variant `tasks.withType<Test>()` exclude block lists `PaywallScreenTest` alongside the existing `*ScreenTest` exclusions AND `:mobile:app:testDevReleaseUnitTest` passes

#### Scenario: The un-provisioned billing manual-verification boundary is recorded

- **WHEN** inspecting `tasks.md`
- **THEN** it states that the live RevenueCat Offerings/purchase path is verified manually after the operator provisions the dashboard / store products / secret slots (the app-side logic is covered via `FakePurchaseController`) — an explicit, non-skip-rationalized boundary
