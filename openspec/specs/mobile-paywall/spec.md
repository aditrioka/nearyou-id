# mobile-paywall Specification

## Purpose
The in-app conversion surface that turns every Free cap and Premium gate into a working subscribe flow. It defines `PaywallRoute` / `PaywallScreen` / `PaywallViewModel` (Compose Multiplatform / Material 3, mockup frame 17):
- a Premium hero whose subheadline is tailored to the `PaywallEntry` that opened it;
- the Month-1 benefit set: features available now only, with no image upload and no tenure counter;
- RevenueCat-Offerings-derived pricing cards;
- a subscribe → confirm-entitlement → return-and-re-evaluate flow.

This is backed by the vendor-free `PurchaseController` seam in `:infra:revenuecat` (the SDK is `implementation`-fenced there per invariant #16). The paywall is reachable from:
- the like / reply / post / chat daily-cap dialogs (`LIKE_CAP` / `REPLY_CAP` / `POST_CAP` / `CHAT_CAP`);
- the search 403 gate (`SEARCH_GATE`);
- the premium-username gate (`USERNAME`);
- the composer image-attach gate (`IMAGE_ATTACH`, which keeps the generic headline until image upload launches);
- the post-edit 403 gate (`EDIT_GATE`);
- the Nearby radius gate (`RADIUS_GATE`);
- the Free timeline read cap: the soft-limit banner and the hard-limit state on the three feeds (`TIMELINE_CAP`).

It degrades to a fail-soft `Unconfigured` state until RevenueCat billing is provisioned. The subscription contract is owned by the `subscription-billing-webhook` change: `subscription_status` is webhook-driven, and the client premium signal is the RevenueCat `CustomerInfo` entitlement.
## Requirements
### Requirement: PaywallRoute is a payload-carrying serializable NavKey registered for the iOS-saveable back stack

The mobile app SHALL declare a `PaywallRoute` `NavKey` (in `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/routing/NavKeys.kt`). It is a `@Serializable data class` carrying a single non-PII `entry: PaywallEntry` property, where `PaywallEntry` is an enum naming the gated surface that opened the paywall. The enum SHALL declare exactly these values, each wired at its call site in `appEntryProvider` (or threaded to it through the surface's hoisted `onActivatePremium`):

| Entry | Opened from |
|---|---|
| `LIKE_CAP` | the like-cap `DailyCapUpsellDialog` on the three feeds and on post-detail |
| `SEARCH_GATE` | the search `403 premium_required` gate |
| `USERNAME` | the premium-username gate (`mobile-premium-username`) |
| `IMAGE_ATTACH` | the composer's proactive image-attach gate (`mobile-image-attachment`) |
| `CHAT_CAP` | the chat 50/day cap dialog on the chat thread and the share-to-chat picker |
| `REPLY_CAP` | the reply 20/day cap dialog on post-detail |
| `POST_CAP` | the post 10/day cap dialog on the composer |
| `EDIT_GATE` | the post-edit `403 premium_required` upsell |
| `RADIUS_GATE` | the Nearby Premium-radius upsell |
| `TIMELINE_CAP` | the Free timeline read cap (`timeline-read-rate-limit`): the soft-limit banner and the hard-limit state on the three feeds |

New values SHALL be appended, never reordered or renamed: kotlinx.serialization encodes the enum by name, so a persisted iOS back stack must keep decoding. A gated surface MUST NOT open the paywall with another surface's entry (e.g. the radius upsell MUST NOT push `LIKE_CAP`).

The route MUST NOT carry any PII, token, coordinate, or user identifier. It SHALL be registered in the `navSavedStateConfiguration` polymorphic `SerializersModule` (`mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/routing/AppNavSerialization.kt`) via an explicit `subclass(PaywallRoute::class, PaywallRoute.serializer())` entry so the back stack is saveable on Kotlin/Native (iOS), mirroring `PostDetailRoute`. `PaywallRoute` SHALL be appended to the **root** back stack (overlaying the section `NavigationBar`), the same mechanism `SearchRoute` / `PostDetailRoute` / the composer FAB use — deliberately NOT a per-tab back stack.

#### Scenario: PaywallRoute survives a serialized back-stack round-trip for every entry

- **WHEN** a back stack containing `PaywallRoute(entry = e)` for EVERY `e` in `PaywallEntry.entries` is serialized via `navSavedStateConfiguration` and restored (`NavKeySerializationTest`)
- **THEN** each restored entry is a `PaywallRoute` whose `entry` equals the original `e` (the polymorphic `subclass(...)` registration makes it decode on Kotlin/Native)

#### Scenario: PaywallEntry declares exactly the wired gates

- **WHEN** inspecting the `PaywallEntry` enum
- **THEN** its values are exactly `LIKE_CAP`, `SEARCH_GATE`, `USERNAME`, `IMAGE_ATTACH`, `CHAT_CAP`, `REPLY_CAP`, `POST_CAP`, `EDIT_GATE`, `RADIUS_GATE`, `TIMELINE_CAP`, in that declaration order

#### Scenario: PaywallRoute carries only the non-PII entry-context

- **WHEN** inspecting the `PaywallRoute` declaration
- **THEN** its only property is the `PaywallEntry` enum AND it declares no `latitude`/`longitude`, no token, no user id, and no other identity payload

### Requirement: PaywallScreen renders the frame-17 paywall surface and is navigation-free

The mobile app SHALL ship a composable `PaywallScreen` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/paywall/PaywallScreen.kt`), mapped from the `PaywallRoute` `NavKey` by the `appEntryProvider`, rendering the paywall per the canonical mockup (frame 17, `dev/mockups/nearyou-screens-mockup.html`, binding for look/layout per docs/11 §2.8). It renders:
- a top app bar with a close (X) affordance;
- a Premium hero (the `workspace_premium` premium-accent icon + a "NearYouID Premium" heading);
- the benefit list (§ "The paywall benefit set");
- the pricing cards (§ "Pricing, anchors, and savings are derived");
- a full-width primary "Aktifkan Premium" CTA;
- the disclosure footer (§ "The disclosure footer").

The hero subheadline SHALL be tailored to the route's `PaywallEntry` while always presenting the full Premium offering. The mapping is exhaustive over the enum with no `else` branch, each subheadline via `stringResource`:

| Entry | Subheadline key |
|---|---|
| `LIKE_CAP` | `paywall_subhead_like_cap` |
| `SEARCH_GATE` | `paywall_subhead_search` |
| `USERNAME` | `username_premium_gate_body` (the `docs/03-UX-Design.md` § Premium Username Customization paywall copy, "Ganti username adalah fitur Premium.") |
| `CHAT_CAP` | `paywall_subhead_chat_cap` |
| `REPLY_CAP` | `paywall_subhead_reply_cap` |
| `POST_CAP` | `paywall_subhead_post_cap` |
| `EDIT_GATE` | `paywall_subhead_edit` |
| `RADIUS_GATE` | `paywall_subhead_radius` |
| `TIMELINE_CAP` | `paywall_subhead_timeline_cap` ("Baca timeline tanpa batas") |
| `IMAGE_ATTACH` | `paywall_subhead_default` |

`IMAGE_ATTACH` deliberately keeps the generic `paywall_subhead_default` headline. Image upload is a Month-6 feature behind the `image_upload_enabled` flag (default `false`, docs/05), and docs/01 + docs/03 § Paywall & Premium Disclosure forbid advertising image upload before it ships. A photo-led headline would promise a feature a buyer may not receive. A later change MAY tailor it once the image launch is live.

`PaywallScreen` SHALL be navigation-free: it holds no back-stack reference; its close affordance invokes a hoisted `onClose` lambda and a successful purchase invokes a hoisted `onPurchaseComplete` (or equivalent return) lambda. No hardcoded UI string literals SHALL appear in the screen source (every `Text` / `contentDescription` resolves via `stringResource(Res.string.<name>)`); colors and typography SHALL come from `NearYouTheme` tokens (no hex literals); the screen SHALL render under both light and dark schemes.

#### Scenario: The paywall renders the frame-17 surface and is navigation-free

- **GIVEN** `PaywallScreen` composed for `PaywallRoute(entry = LIKE_CAP)` over a Content state with loaded packages under `NearYouTheme`
- **THEN** the rendered tree contains the Premium hero, the benefit list, the three pricing cards, a primary CTA labelled `stringResource(Res.string.cta_activate_premium)`, and a close affordance bound to the hoisted `onClose` AND the screen holds no back-stack reference (navigation is delivered via the hoisted lambdas only)

#### Scenario: No hardcoded UI strings and token-only styling

- **WHEN** inspecting `PaywallScreen.kt`
- **THEN** every user-visible text resolves via `stringResource(Res.string.<name>)` AND the source contains no hex color literals (theme tokens only) AND the screen renders without crash under `NearYouTheme` light and dark

#### Scenario: The hero headline is tailored to the entry-context

- **GIVEN** `PaywallScreen` composed once for `PaywallRoute(entry = LIKE_CAP)` and once for `PaywallRoute(entry = SEARCH_GATE)`, both in the Content state
- **THEN** the two renderings present a different hero headline (the entry-context tailoring) AND both still present the full benefit list and pricing cards (the contextual hero leads, it does not narrow the offering)

#### Scenario: Every new cap/gate entry renders its own headline

- **GIVEN** `PaywallScreen` composed in the Content state for each of `CHAT_CAP`, `REPLY_CAP`, `POST_CAP`, `EDIT_GATE`, `RADIUS_GATE`, `TIMELINE_CAP`, and `USERNAME`
- **THEN** each rendering shows its mapped subheadline (`paywall_subhead_chat_cap`, `paywall_subhead_reply_cap`, `paywall_subhead_post_cap`, `paywall_subhead_edit`, `paywall_subhead_radius`, `paywall_subhead_timeline_cap`, `username_premium_gate_body` respectively) AND none shows `paywall_subhead_default`

#### Scenario: The image-attach entry does not advertise image upload

- **GIVEN** `PaywallScreen` composed in the Content state for `PaywallRoute(entry = IMAGE_ATTACH)`
- **THEN** the hero shows `paywall_subhead_default` AND the rendered tree contains no node advertising image/photo upload

### Requirement: The paywall benefit set shows features available now per the disclosure rule

The benefit list SHALL present the Month-1 Premium feature set per `docs/01-Business.md` § Freemium Tiers / `docs/02-Product.md`, each label via `stringResource`, restricted to features that actually ship in the app:
- unlimited posts/replies/likes;
- the 10/20/50/100 km Nearby radius;
- hide-distance (city name stays visible);
- custom username (1× per 30 days);
- search + 30-minute post edit;
- no-ads + the Premium badge.

It MUST NOT advertise image upload: that is a Month-6 feature, not yet launched, and `docs/03-UX-Design.md` § Paywall & Premium Disclosure requires that "the paywall shows features available NOW". For the same reason it MUST NOT advertise the Premium **tenure** counter (docs/01 § Premium Tenure Counter), which is not yet shipped. The Settings "Perjalanan Premium" row is still a deferred placeholder; only the profile Premium badge ships. So the no-ads row reads "Tanpa iklan · badge Premium", with no tenure claim. Each benefit label is a CMP string resource (no hardcoded literal).

#### Scenario: The benefit list shows now-available features and omits image upload

- **GIVEN** `PaywallScreen` composed in the Content state
- **THEN** the rendered tree contains the now-available Premium benefit rows (each via `stringResource`) AND contains no node advertising image/photo upload

#### Scenario: The benefit list makes no tenure claim

- **GIVEN** `PaywallScreen` composed in the Content state
- **THEN** the no-ads benefit row renders `paywall_benefit_no_ads` = "Tanpa iklan · badge Premium" AND no rendered node contains the word "tenure"

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

### Requirement: The paywall degrades to a fail-soft Unconfigured state when Offerings are unavailable

When `PurchaseController.fetchOfferings()` returns no usable packages — the RevenueCat SDK is not configured, no offering is published, or the fetch fails (the expected state until the operator provisions the RevenueCat dashboard, store products, and API-key secret slots) — `PaywallScreen` SHALL render a graceful Unconfigured state via `stringResource` (a "Premium belum tersedia" message and a close affordance), and the subscribe CTA SHALL be absent or disabled. The screen MUST NOT crash and MUST NOT present a purchasable card with a fabricated price. This keeps the change shippable and CI/sandbox-green; the live Offerings/purchase path activates with no code change once provisioning lands.

#### Scenario: Empty/unavailable Offerings render the Unconfigured state, not a crash or fake card

- **GIVEN** `PaywallScreen` over a `FakePurchaseController` whose `fetchOfferings()` returns the empty/unavailable result
- **WHEN** the screen renders
- **THEN** it shows the Unconfigured `stringResource` message and a close affordance AND renders no purchasable price card AND does not crash

### Requirement: PaywallViewModel is a commonMain ViewModel exposing one PaywallUiState StateFlow

The change SHALL ship a `PaywallViewModel` (androidx `ViewModel` in commonMain, obtained via `koinViewModel()`, scoped to the `PaywallRoute` NavEntry per docs/11 §2.2/§2.3) exposing exactly ONE `StateFlow<PaywallUiState>` via `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), <initial>)`. `PaywallUiState` SHALL be a Compose-free type produced by a pure, unit-testable projection covering: LoadingOfferings, Content (the derived packages + selected period), PurchaseInProgress, PurchasePending (the payment-pending / unconfirmed-entitlement state), Success, Error, and Unconfigured. One-shot effects (e.g. the return-on-success signal) SHALL be modelled as nullable state fields consumed via an `onXxxShown()` callback — NOT a `Channel`/`SharedFlow` event bus. The ViewModel SHALL launch all purchase/offering work in `viewModelScope` (no work launched from composables, no `GlobalScope`).

#### Scenario: The ViewModel exposes a single StateFlow and maps offerings deterministically

- **GIVEN** `PaywallViewModel` over a `FakePurchaseController` returning loaded packages
- **WHEN** the offerings load completes
- **THEN** the single `StateFlow<PaywallUiState>` emits LoadingOfferings then Content with the derived packages AND the projection is deterministic (no wall-clock / platform dependency)

#### Scenario: Pending is a state field, not an event

- **WHEN** inspecting `PaywallUiState.Content`
- **THEN** the pending state is a `purchasePending: Boolean` field alongside `purchaseInProgress` / `purchaseError` / `purchaseSucceeded` AND no `Channel`/`SharedFlow` carries it

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

