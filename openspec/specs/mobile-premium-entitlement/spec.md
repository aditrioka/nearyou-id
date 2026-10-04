# mobile-premium-entitlement Specification

## Purpose
The client Premium-entitlement lifecycle that makes real purchases unlock Premium. `PremiumEntitlementSession` keeps the RevenueCat app-user id bound to the signed-in `users.id` at every session boundary (sign-in/sign-up, logout, involuntary session expiry, app-root resume, and immediately before a purchase), so each purchase reaches the `subscription-billing-webhook` under a resolvable user. Before this, an anonymous `$RCAnonymousID` purchase could never flip `subscription_status` (issue #490). The session also publishes an account-scoped `purchaseConfirmed` signal and a per-session key, so Premium-gated client surfaces (Nearby radius, username, search, ads) re-evaluate right after a confirmed purchase, and nothing outlives the account that bought. During the webhook-lag window the same signal turns every Free upsell (the cap dialogs, the Premium-gate upsells, the read-cap states, the Settings toggles) into a shared "Premium sedang diaktifkan" notice, so a buyer is never told to upgrade or sent to buy again. It is read once through `rememberPremiumActivating()` by the shared rendering layer, not per ViewModel. The server's webhook-driven tier and its 403s remain authoritative.
## Requirements
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

1. Sign-in and sign-up success (`AuthRepository`): after the token pair is persisted and before the success outcome is returned. The sync is bounded by a short timeout, so a hanging vendor call never holds sign-in; a timed-out bind is healed by the resume and pre-purchase syncs.
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

#### Scenario: A hanging identity sync does not hold sign-in

- **GIVEN** a `PurchaseController` whose `logIn(...)` never completes
- **WHEN** `signInWithGoogle()` completes with a `200` token pair
- **THEN** the outcome is `SignInOutcome.Success` AND it is returned within the sync timeout bound

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

Every composable that resolves `PremiumEntitlementSession` SHALL use a fail-safe lookup (`getKoin().getOrNull<PremiumEntitlementSession>()`, the `TimelineAds` precedent). That covers the screens that hand `purchaseConfirmed` to their ViewModel (Nearby, username, search, paywall, settings) and the app-root `BillingIdentityEffect` / `SessionExpiryEffect`. The `mobileModule` bindings declared alongside the session (`AuthRepository`, `AdFeedController`) resolve it with a strict `get()`: the session is declared in the same module, so a missing definition there must fail loudly. If it is not bound (a screen test that installs its own Koin module, or a DI gap), the surface falls back to a never-confirmed `false` signal and behaves exactly as before this change: no crash, no `NoDefinitionFound`. Production binds `PremiumEntitlementSession` as a single in `mobileModule`.

#### Scenario: An unbound session degrades to never-confirmed

- **GIVEN** a screen test whose Koin module does not bind `PremiumEntitlementSession`
- **WHEN** the Nearby / username / search / paywall screen composes
- **THEN** it renders without a resolution error AND its Premium gate behaves as if no purchase was confirmed

#### Scenario: Production binds the session as a Koin single

- **WHEN** inspecting `mobileModule`
- **THEN** `PremiumEntitlementSession` is declared as a `single` over `SelfUserIdProvider` + `PurchaseController` AND the same instance is passed to `AuthRepository` and `AdFeedController` (strict `get()`), and to the gated screens, `SettingsViewModel`, `PaywallViewModel`, `BillingIdentityEffect` and `SessionExpiryEffect` (via the fail-safe lookup)

### Requirement: Test coverage for the entitlement lifecycle

The change SHALL ship test coverage (commonTest, plus Robolectric androidUnitTest for the composable app-root effects) for:

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

### Requirement: Upsell surfaces show the activating notice during the webhook-lag window

While `purchaseConfirmed` is `true`, no Free upsell surface SHALL tell the buyer to "Upgrade ke Premium" or send them to the paywall to buy again. Each SHALL show the shared activating notice described below instead.

After a confirmed purchase, the server's `users.subscription_status` stays Free until the RevenueCat webhook lands (`subscription-billing-webhook`). In that window the server, or a client gate seeded from a server read, can still answer as Free:
- a cap `429`;
- a `403 premium_required` / `radius_premium_only`;
- a timeline read-cap flag;
- a Free tier read on screen entry.

`mobile-paywall` accepts that a server-gated action may stay gated briefly. This requirement adds its own rule: no upsell surface SHALL tell a buyer who just paid to "Upgrade ke Premium", or send them to the paywall to buy again.

While `purchaseConfirmed` is `true`, every Free upsell surface SHALL swap its upgrade pitch for the shared **activating notice**:
- The notice says the purchase succeeded and Premium is being activated, so the viewer should try again shortly.
- It uses two new `:shared:resources` keys: `premium_activating_title` ("Premium sedang diaktifkan") and `premium_activating_body` ("Pembelianmu berhasil dan Premium sedang diaktifkan. Coba lagi sebentar lagi ya.").
- It shows NO "Aktifkan Premium" CTA and pushes no `PaywallRoute`.

The surfaces:

| Surface | Free upsell | During the lag window |
|---|---|---|
| `DailyCapUpsellDialog`: like / reply / post / chat `429` on the seven hosts (three feeds, post-detail, composer, chat thread, share picker) | frame-18 cap dialog | `PremiumActivatingDialog` |
| `RadiusPremiumUpsellDialog` (Nearby) | radius upsell dialog | `PremiumActivatingDialog` |
| Post-edit `403 premium_required` upsell | edit upsell dialog | `PremiumActivatingDialog` |
| Composer image-attach gate (tier read on entry) | push `PaywallRoute(IMAGE_ATTACH)` | `PremiumActivatingDialog`, no push |
| `HardLimitState` (timeline hard read cap, three feeds) | `timeline_limit_hard` + "Aktifkan Premium" | `premium_activating_body` + "Coba lagi" → page-1 reload |
| `SoftLimitBanner` (timeline soft read cap, three feeds) | `timeline_limit_soft` + "Aktifkan Premium" | not rendered (the soft cap blocks no reading) |
| Search `403` gate panel | `search_premium_gate_body` + CTA | `premium_activating_body` + "Coba lagi" → the same query |
| Username `403` gate panel | `username_premium_gate_body` + CTA | `premium_activating_body` + "Coba lagi" → back to the editor |
| Settings "Sembunyikan jarak" / "Profil privat" Free tap (tier read on entry) | `settings_*_premium_only` snackbar | `premium_activating_body` snackbar |

`PremiumActivatingDialog` is ONE shared `ui/components/` Material 3 `AlertDialog`:
- title `premium_activating_title`, text `premium_activating_body`;
- a single "Tutup" (`cta_close`) in the confirm slot;
- its `onDismissRequest` and that button both invoke the hoisted `onDismiss`, which each surface wires to the path its own dismiss already uses;
- it holds no navigation reference.

The swap is made in the shared rendering layer, not per ViewModel. One composable, `rememberPremiumActivating()` in `ui/billing/`, reads the `purchaseConfirmed` value of the existing fail-safe `rememberPremiumConfirmed()`, collected with `collectAsStateWithLifecycle()`.
- The shared components (`DailyCapUpsellDialog`, `RadiusPremiumUpsellDialog`, `SoftLimitBanner`, `HardLimitState`) take a `premiumActivating: Boolean` parameter defaulted to it, so every host gets the behavior with no wiring. A test composing one with no Koin context passes the value explicitly.
- The screen-private surfaces (edit upsell, search gate, username gate, the Settings snackbar text, the composer attach one-shot) call the resolver directly.

No ViewModel, `UiState` or repository changes. The retries reuse each surface's existing path (the feed reload, the search retry, the username candidate change). The server stays authoritative: the viewer retries, and the activating notice itself never retries on its own. Pre-existing confirmation-driven re-evaluation, such as search's once-only re-run and the username gate clearing, is unchanged.

The resolution is fail-safe, like the existing consumers (§ "Premium-gated surfaces resolve the signal fail-safe"). With no `PremiumEntitlementSession` bound, every surface renders its Free upsell exactly as before.

Two limits are accepted:
- **The signal lives only in memory.** It stays `true` for the rest of the account's session, so a buyer who later hits the both-tier 500/h like burst limiter sees the activating notice. Before this change they saw an upgrade pitch, which is worse. A cold start inside the lag window resets the signal, and the Free upsell returns until the webhook lands.
- **Tier read once on entry.** A surface that read the tier on screen entry (the composer, Settings) keeps showing the notice until it is reopened after the webhook.

#### Scenario: The activating dialog renders its copy and a single dismiss

- **GIVEN** `PremiumActivatingDialog` composed under `NearYouTheme` (light, then dark) with a recording `onDismiss`
- **WHEN** the dialog renders and "Tutup" is tapped
- **THEN** the tree contains `premium_activating_title`, `premium_activating_body` and a "Tutup" control AND no "Aktifkan Premium" control AND `onDismiss` fires exactly once

#### Scenario: The shared timeline read-cap states follow the signal

- **GIVEN** `SoftLimitBanner` and `HardLimitState` each composed once with `premiumActivating = false` and once with `premiumActivating = true`, with recording `onActivatePremium` / `onRetry`
- **WHEN** each renders and its control is activated
- **THEN** with `false` each shows its limit copy AND an "Aktifkan Premium" control that fires `onActivatePremium` once AND with `true` the banner renders nothing while the hard state shows `premium_activating_body` and a "Coba lagi" control that fires `onRetry` once, never `onActivatePremium`

#### Scenario: An unbound session renders the Free upsell

- **GIVEN** a Koin graph that does not bind `PremiumEntitlementSession`
- **WHEN** a cap host raises the cap dialog, or a composable reads `rememberPremiumActivating()`
- **THEN** the frame-18 upsell renders with its "Aktifkan Premium" CTA AND the resolver reads `false` (no resolution error, no activating notice)

#### Scenario: The resolver follows the signal

- **GIVEN** a bound, signed-in `PremiumEntitlementSession` whose `purchaseConfirmed` is `false` and a composable reading `rememberPremiumActivating()`
- **WHEN** `onPurchaseConfirmed()` runs, and later the session is signed out and `syncIdentity()` resets the signal
- **THEN** the read value becomes `true` and recomposes, and then returns to `false`

