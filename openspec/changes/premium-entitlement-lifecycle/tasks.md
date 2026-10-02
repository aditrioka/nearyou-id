## 1. `:infra:revenuecat` identity seam

- [x] 1.1 `PurchaseController`: add `suspend fun logIn(appUserId: String): Boolean` and `suspend fun logOut()` (vendor-free, KDoc'd non-throwing contract). Add `data object Pending : PurchaseResult`.
- [x] 1.2 `RevenueCatPurchaseController.logIn`:
  - returns `false` when unconfigured
  - no-ops to `true` when already identified as `appUserId`
  - otherwise calls `awaitLogIn`; `PurchasesException` → `false`
- [x] 1.3 `RevenueCatPurchaseController.logOut`: no-op when unconfigured or `isAnonymous`, else `awaitLogOut`; swallow `PurchasesException`.
- [x] 1.4 `RevenueCatPurchaseController.purchase`:
  - return `Error("identity_unavailable")` when `isAnonymous` (the D3 fail-closed guard)
  - map `PurchasesErrorCode.PaymentPendingError` → `PurchaseResult.Pending`
- [x] 1.5 Refresh the stale "tracked follow-on" KDoc in `RevenueCatConfig.kt` and `KoinInit.kt` (configure stays anonymous; `PremiumEntitlementSession` binds via `logIn`).

## 2. `PremiumEntitlementSession` + session-boundary wiring

- [x] 2.1 New `mobile/app/src/commonMain/kotlin/id/nearyou/app/billing/PremiumEntitlementSession.kt`:
  - `syncIdentity(): Boolean`, Mutex-serialized: resolves the id via `SelfUserIdProvider`, then `logIn`/`logOut`; resets `purchaseConfirmed` when the bound id changes
  - `purchaseConfirmed: StateFlow<Boolean>`
  - `onPurchaseConfirmed()`
- [x] 2.2 `MobileModule`: `single { PremiumEntitlementSession(get(), get()) }`. Pass it to `AuthRepository` and `SessionInvalidator` (both new params default `null`).
- [x] 2.3 `AuthRepository`: call `syncIdentity()` after `tokenStore.write` on sign-in AND sign-up success (before returning `Success`).
- [x] 2.4 Involuntary invalidation: `SessionExpiryEffect` launches `syncIdentity()` after its re-route when it consumes the session-expired signal. Revised in review round 1: NOT inside `SessionInvalidator.invalidate`, which runs within `TokenRefresher`'s single-flight critical section.
- [x] 2.5 `SettingsViewModel`:
  - add `premiumEntitlement: PremiumEntitlementSession? = null`
  - call `syncIdentity()` inside the `NonCancellable` wipe, after `tokenStore.clear()` and `_loggedOut.value = true`
  - `SettingsScreen` passes it via the fail-safe `getOrNull` lookup
  - Do NOT touch the Sentry `clearUser` on logout (#492, held).
- [x] 2.6 New app-root `BillingIdentityEffect` (`screens/routing/`, mirrors `ProactiveRefreshEffect`): on `ON_RESUME`, launch `syncIdentity()` on the app-root scope; swallow non-cancellation failures. Host it in `App.kt` next to `ProactiveRefreshEffect`, resolving the session via `getOrNull` so the app composes when it is unbound.

## 3. Paywall confirm / pending

- [x] 3.1 `PaywallUiState.Content`: add `purchasePending: Boolean = false`.
- [x] 3.2 `PaywallViewModel(purchaseController, premiumEntitlement: PremiumEntitlementSession? = null)`. `onSubscribe`:
  - if pending → recheck (no purchase)
  - otherwise set in-progress → `syncIdentity()` (`false` → `purchaseError`, no purchase) → `purchase`
  - map the result: `Success(true)` → confirm; `Success(false)` → one `isPremiumEntitlementActive()` recheck → confirm/pending; `Pending` → pending
  - confirm = `onPurchaseConfirmed()` + `purchaseSucceeded`
- [x] 3.3 `PaywallScreen`:
  - resolve the session via `getOrNull`
  - pending renders `paywall_purchase_pending` (onSurfaceVariant, in the error-message slot)
  - the CTA label becomes `paywall_cta_check_status` while pending
- [x] 3.4 Add the two strings to `:shared:resources` `values/strings.xml` (Bahasa Indonesia, matching the existing paywall copy voice) plus any locale file that mirrors the paywall keys.

## 4. Gated-surface re-evaluation

- [x] 4.1 `NearbyTimelineViewModel`:
  - `premiumConfirmed: StateFlow<Boolean> = MutableStateFlow(false)` param
  - on-entry result ORs `premiumConfirmed.value`
  - a collector sets `_isPremiumKnown = true` when it flips `true`
  - `NearbyTimelineScreen` passes it via the fail-safe lookup
- [x] 4.2 `UsernameCustomizationViewModel`:
  - same param
  - on-entry ORs the signal
  - the collector sets `isPremiumKnown = true`, clears a `CheckPremiumGate`/`PremiumGate` outcome, and resets `lastProbedCandidate`
  - `UsernameCustomizationScreen` passes it via the fail-safe lookup
- [x] 4.3 `SearchViewModel`:
  - same param
  - the collector calls `retry()` once when it flips `true` while the outcome is `PremiumGate`
  - `SearchScreen` passes it via the fail-safe lookup
- [x] 4.4 `AdFeedController`:
  - add `purchaseConfirmed: StateFlow<Boolean>` and `currentAccountId: suspend () -> String?` (both defaulted)
  - `frequency` = `combine(_frequency, purchaseConfirmed)` (null while confirmed)
  - `loadAd` returns null while confirmed
  - `prepare()` is session-keyed via `PremiumEntitlementSession.sessionKey` (re-evaluates + clears cached ads on any new session, incl. the same account after a sign-out — review round 1 B1) and short-circuits before SDK init/UMP while confirmed
  - `rememberTimelineAds` collects with `collectAsState(initial = null)`
  - `MobileModule` binds the session's signal (via `getOrNull`) + `SelfUserIdProvider::selfUserId`

## 5. Backend webhook

- [x] 5.1 `RevenueCatWebhookRoutes`: a non-UUID `app_user_id` → `200 {"status":"ignored"}` + WARN `event=revenuecat_orphan_event reason=non_uuid_app_user_id rc_event_id=…`. Never log the raw id. A missing/blank `app_user_id` stays `400`.
- [x] 5.2 `RevenueCatWebhookRoutesTest`: a non-UUID `app_user_id` (`$RCAnonymousID:abc123`) → 200 `ignored`, zero `subscription_events` rows, `subscription_status` unchanged.

## 6. Tests

- [x] 6.1 `FakePurchaseController`: record `logIn` ids / `logOut` count; configurable `logInResult`; settable `premiumActive`.
- [x] 6.2 `PremiumEntitlementSessionTest`:
  - signed-in → `logIn(sub)` + `true`
  - signed-out → `logOut`
  - `logIn` failure → `false`
  - confirmed set / survives same-account resync / reset on sign-out and account switch
- [x] 6.3 Boundary call sites:
  - `AuthRepositoryTest`: sign-in `logIn(sub)` + success despite `logIn` failure
  - `AuthRepositorySignUpTest`: sign-up `logIn(sub)`
  - `SessionInvalidatorTest`: `invalidate` → `logOut` + signal still delivered
  - `SettingsLogoutViewModelTest`: `logout` → `logOut` + wipe
- [x] 6.4 `PaywallViewModelTest`:
  - confirmed → `purchaseConfirmed` true
  - `Success(false)` + recheck true → success
  - `Success(false)` + recheck false → pending (no success)
  - `Pending` → pending, no error
  - CTA while pending rechecks without a second purchase
  - identity-sync failure → error, no purchase
- [x] 6.5 `NearbyTimelineViewModelTest`: signal flip unlocks 50 km (fetch issued, no upsell); a lagging Free self read after confirmation stays Premium.
- [x] 6.6 `UsernameCustomizationViewModelTest`: signal flip leaves `PremiumGate` for the editor; a stale `CheckPremiumGate` is cleared and the candidate re-probed.
- [x] 6.7 `SearchViewModelTest`: `PremiumGate` + signal → exactly one re-query → `Results`; a non-gate outcome + signal → no re-query.
- [x] 6.8 `AdFeedControllerTest`:
  - signal flip → frequency null + `loadAd` null without a provider call
  - confirmed-before-prepare → no init/consent
  - same account latched (one config fetch)
  - account change → config re-fetched, cached ad dropped
  - update the existing `.value` reads to the Flow
- [x] 6.9 `PaywallScreenTest` (Robolectric, already in the Release-variant exclude): the pending state renders the pending copy + check-status CTA label and no error text.
- [x] 6.10 `PaywallFlowIosTest`: add the `Success(false)` + recheck-true confirm path and the pending path on Kotlin/Native (K/N-legal test names).
- [x] 6.11 `PremiumEntitlementKoinResolutionTest` (androidUnitTest, mirrors `CrashReportingKoinResolutionTest`): the real `mobileModule` + stub platform bindings resolve `PremiumEntitlementSession`, `SessionInvalidator`, `AuthRepository`, the `HttpClient`, and `AdFeedController`. This proves the D2/D6 wiring adds no Koin resolution cycle and satisfies the "Production binds the session as a Koin single" scenario.
- [x] 6.12 The "unbound session degrades to never-confirmed" scenario is backed by the existing `NearbyTimelineScreenTest` / `UsernameCustomizationScreenTest` / `SearchScreenTest` / `PaywallScreenTest` / `TimelineAdsScreenTest`. None of them bind `PremiumEntitlementSession`, so they MUST stay green unmodified (no new Koin binding added to their modules).

- [x] 6.13 Review round 1 fixes:
  - `syncIdentity` catch-all + a throwing-vendor test
  - `FETCH_CURRENT` recheck
  - pure `transactionFailureResult` + `TransactionFailureResultTest` (`:infra:revenuecat`)
  - same-account re-sign-in ads test
  - non-vacuous cached-ad test
  - synchronous frequency seed
  - webhook WARN-log capture + blank-`app_user_id` 400 test
  - sign-up sync-fails test
  - Koin single + same-instance ads test
  - paywall in-progress/double-tap, exactly-one-recheck, pending-with-session tests
  - username submit-gate clear test
  - `SessionExpiryEffectTest` + `BillingIdentityEffectTest` (Robolectric, Release-excluded)

## 7. Gates + verification

- [x] 7.1 `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` green. Run the backend DB suite against a fresh throwaway PostGIS container per `docs/13` §5 (the `!network` tag).
- [x] 7.2 `./gradlew :mobile:app:ktlintCheck :mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest :infra:revenuecat:ktlintCheck` green (named explicitly — the root aggregate is not trusted for mobile).
- [x] 7.3 `./gradlew :mobile:app:iosSimulatorArm64Test` (K/N — the paywall flow test + the new commonMain code) and `:mobile:app:linkDebugFrameworkIosSimulatorArm64` (the `:infra:revenuecat` `awaitLogIn`/`awaitLogOut` iOS link). The known pre-existing `AppShellFlowIosTest` red (#348) is confirmed by stash-rerun, not attributed to this change.
- [x] 7.4 Manual UI verification (docs/11 §5 DoD): render the paywall pending state and the post-confirmation radius unlock on the Android emulator (verify-loop §B), with screenshots in the PR body.
- [ ] 7.5 **HUMAN-REQUIRED (operator):** the staging sandbox purchase end-to-end. Staging uses the RevenueCat **Test Store** (project `9d323c42`), so no Play license tester is needed; any device or emulator running a `stagingDebug` build carrying the Test Store key (`-PstagingRevenueCatPublicKey`, value from Secret Manager `staging-revenuecat-test-api-key`) works.
  - Prerequisite: the RevenueCat dashboard webhook is registered (Integrations → Webhooks → `https://api-staging.nearyou.id/internal/revenuecat-webhook` with the `staging-revenuecat-webhook-secret` Bearer).
  - Sign in on the `stagingDebug` build.
  - Confirm in the RevenueCat dashboard that the customer id = the account's `users.id` (not `$RCAnonymousID`).
  - Buy the Monthly package in sandbox.
  - Confirm the webhook `200 ok` in Cloud Run logs and `users.subscription_status = 'premium_active'` (Supabase MCP read).
  - Confirm the Nearby 50 km radius is selectable on return without a restart.
  - Sign out and confirm RevenueCat returns to an anonymous customer.

  The real `RevenueCatPurchaseController` cannot be unit-tested without the provisioned SDK/store (covered app-side via `FakePurchaseController`), so this is an explicit, non-skip-rationalized manual boundary.
