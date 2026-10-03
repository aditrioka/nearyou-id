## Why

Real purchases never unlock Premium. The mobile app configures RevenueCat with an **anonymous** app-user id (`KoinInit.kt` → `configureRevenueCat(appUserId = null)`) and never calls `Purchases.logIn`/`logOut`, so every purchase reaches the `subscription-billing-webhook` under a `$RCAnonymousID:…` identifier, which the backend cannot map to a `users.id` and rejects. `users.subscription_status` never flips. The archived `mobile-paywall-screen` design required the opposite ("appUserID SHALL be configured to the authenticated users.id").

On the client, the paywall reports success without checking the entitlement, and the gated surfaces (Nearby radius, username gate, search gate, ads) resolve Premium once, on entry. A buyer who returns from the paywall stays locked out until they cold-start the app. Sign-out never resets the RevenueCat identity either, so a second account on the same device inherits the first account's client-side entitlement. This is the revenue loop's last broken link, found by the 2026-09-25 spec-completion audit (issue [#490](https://github.com/aditrioka/nearyou-id/issues/490); findings M3-C1/C2/C6, M2-C1/C2).

## What Changes

- **RevenueCat identity follows the session.** `:infra:revenuecat` `PurchaseController` gains `logIn(appUserId)` and `logOut()`, both vendor-free and non-throwing. A new mobile `PremiumEntitlementSession` Koin single owns `syncIdentity()`. It aligns the RevenueCat app-user id with the signed-in `users.id` (the access token's `sub`): `logIn` when signed in, `logOut` when signed out, idempotent. It runs on sign-in/sign-up success, voluntary logout, involuntary session invalidation, every app-root `ON_RESUME` (cold-start session restore plus self-heal after a failed `logIn`), and immediately before a purchase.
- **No unattributable purchases.** `RevenueCatPurchaseController.purchase()` refuses to purchase while the SDK identity is anonymous, returning a retryable error rather than taking money the server can never attribute. The paywall syncs identity first and surfaces the retryable error if that fails.
- **The paywall confirms the entitlement.** `PurchaseResult.Success(entitlementActive = true)` is confirmed. `Success(false)` triggers one `isPremiumEntitlementActive()` recheck, then counts as confirmed or **pending**. A new `PurchaseResult.Pending` covers the store's payment-pending error (Play cash / convenience-store / carrier-billing payments, which are common in Indonesia). While pending, the paywall renders an informational state (never success, never error), and the CTA rechecks the entitlement instead of re-purchasing.
- **Premium re-evaluates after a confirmed purchase.** `PremiumEntitlementSession.purchaseConfirmed` is a `StateFlow<Boolean>` set on confirmation and reset when the account changes. It never outlives the account that bought. Consumers:
  - The Nearby radius gate and the username gate treat a confirmed purchase as Premium-known, so the effective tier is server `isPremium` OR client-confirmed. The server 403s still backstop.
  - Search re-runs the gated query once.
  - `AdFeedController` drops ad slots on confirmation. Its `prepare` latch is now keyed to the signed-in session, so any sign-out → sign-in re-evaluates eligibility, even back into the same account.
- **Backend webhook tolerates anonymous ids.** A well-formed event whose `app_user_id` is not a UUID (a RevenueCat anonymous id) maps to no `users.id`. It is now acknowledged `200 ignored` with a WARN, per the existing unknown-user scenario, instead of `400`, so RevenueCat stops retrying it.
- Out of scope, held for sequencing: Sentry crash-reporting user/session handling on logout (issue #492) touches the same logout path and lands after this change.

## Capabilities

### New Capabilities
- `mobile-premium-entitlement`: the client Premium-entitlement lifecycle. It covers binding the RevenueCat identity to the session (sign-in / restore / sign-out / invalidation / pre-purchase), the account-scoped confirmed-purchase signal, and the contract that Premium-gated surfaces re-evaluate from it.

### Modified Capabilities
- `mobile-paywall`: `PurchaseController` gains the identity methods and a `Pending` result. The subscribe action syncs identity before purchasing, confirms the entitlement (recheck, then confirmed or pending), publishes the confirmed-purchase signal, and renders a pending state.
- `mobile-nearby-radius-slider`: on-entry tier resolution also honours a confirmed purchase, both at entry and live while the HomeRoute-scoped VM survives the paywall push.
- `mobile-premium-username`: on-entry Premium resolution also honours a confirmed purchase, both at entry and live, clearing a stale gate.
- `mobile-search`: a confirmed purchase re-runs the gated query once, a documented exception to "the gate panel issues no further request".
- `mobile-ads`: a confirmed client purchase suppresses ads (fail-safe direction), and the prepare latch is per-session rather than per-process.
- `subscription-billing-webhook`: a non-UUID `app_user_id` is treated as an unknown user (`200 ignored`), not a malformed body.

## Impact

- **Mobile (`:mobile:app`, `:infra:revenuecat`)**:
  - `PurchaseController.kt`, `RevenueCatPurchaseController.kt`, `RevenueCatConfig.kt` (KDoc).
  - New `billing/PremiumEntitlementSession.kt` plus an app-root `BillingIdentityEffect`.
  - `AuthRepository`, `SettingsViewModel`, `SessionExpiryEffect` (the involuntary unbind), and a note in `SessionInvalidator` explaining why it makes no RevenueCat call.
  - `PaywallViewModel` / `PaywallUiState` / `PaywallScreen` (pending copy).
  - `NearbyTimelineViewModel`, `UsernameCustomizationViewModel`, `SearchViewModel`, `AdFeedController` (+ `TimelineAds`), `MobileModule` wiring, and the matching screens' VM construction.
  - New CMP strings: paywall pending message, recheck CTA.
  - Tests: commonTest (session, paywall, radius/username/search re-gating, ads latch), Robolectric paywall pending render, the iOS paywall flow test.
- **Backend (`:backend:ktor`)**: `RevenueCatWebhookRoutes.kt` non-UUID branch, plus one route test. No schema change, no migration.
- **Operator**: a staging sandbox purchase on a device to verify end-to-end attribution (webhook → `subscription_status`), since the RevenueCat SDK cannot be unit-tested without the provisioned store. The staging RevenueCat project, entitlement, and offering are already provisioned (PR #319).
- **No new dependency.** It uses `purchases-kmp` 3.0.6's existing `awaitLogIn` / `awaitLogOut` / `isAnonymous`.
