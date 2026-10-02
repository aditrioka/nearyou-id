## Context

The `mobile-paywall-screen` change (#235, PR #309) shipped the purchase loop: offerings, `awaitPurchase`, and the webhook-driven `subscription_status`. It deliberately left the identity half as a "tracked follow-on" (`KoinInit.kt:55-57`, `RevenueCatConfig.kt:17-18`) that was never tracked. The result today:

- `configureRevenueCat(apiKey, appUserId = null)` runs once at startup, so RevenueCat mints a `$RCAnonymousID:…`. Nothing calls `logIn`/`logOut`.
- `RevenueCatWebhookRoutes` parses `app_user_id` as a UUID and `400`s otherwise. An anonymous purchase therefore never reaches `SubscriptionService`, and RevenueCat retries a `400`.
- `PaywallViewModel.onSubscribe` maps any `PurchaseResult.Success` to success and ignores `entitlementActive`.
- The gated surfaces resolve tier once, on entry:
  - Nearby radius: `NearbyTimelineViewModel.resolvePremiumOnEntry()` from `init`. The VM is HomeRoute-scoped and survives the paywall push.
  - Username gate: `UsernameCustomizationViewModel.resolvePremiumOnEntry()`.
  - Search gate: `SearchViewModel` holds the `PremiumGate` outcome until the next query.
  - Ads: `AdFeedController`'s `prepared` latch is process-lifetime. It is never reset on purchase or sign-out.
- Session boundaries live in three places:
  - `AuthRepository`: sign-in/sign-up success writes tokens and sets the Sentry user.
  - `SettingsViewModel.confirmLogout`: voluntary logout, then an unconditional wipe.
  - `SessionInvalidator.invalidate`: involuntary terminal 401. It clears tokens and the Sentry user.
- Cold-start session restore is `RootRouterScreen` reading the token store. `ProactiveRefreshEffect` runs at the app root on every `ON_RESUME`.

Constraints: invariant #16 (the RevenueCat SDK is imported only in `:infra:revenuecat`); docs/11 §2.2 (one-shot effects are state, not event buses); screen tests install per-test Koin modules, so a new `koinInject` in a widely-composed screen breaks N tests (the `TimelineAds` `getOrNull` precedent); and the Koin graph already has a `SessionInvalidator → HttpClient` edge (`HttpClientFactory.create(sessionInvalidator = get())`).

## Goals / Non-Goals

**Goals:**
- Every RevenueCat purchase is made under the purchaser's `users.id`, so the existing webhook activates Premium.
- The RevenueCat identity tracks the session. Sign-in, cold-start restore, and foreground resume bind it. Voluntary and involuntary sign-out unbind it. A second account never inherits the first account's entitlement.
- The paywall claims success only for a confirmed entitlement. A pending payment is a distinct, honest state.
- A buyer returning from the paywall is re-evaluated as Premium on the surface that opened it (Nearby radius, username, search, ads) without a cold start.
- The webhook acknowledges anonymous-id events instead of `400`-ing them into RevenueCat's retry loop.

**Non-Goals:**
- **Closing the webhook-lag window server-side.** After a confirmed purchase, a server-gated call (the radius fetch, the username probe, a search query, a like past the cap) can still see the pre-webhook `subscription_status` for the seconds before RevenueCat delivers the webhook. The client re-evaluation is optimistic, and the server's 403 still backstops. The accepted `mobile-paywall` contract already permits this ("a subsequently re-attempted server-gated action MAY briefly still be gated until the webhook lands"). An on-demand server reconcile against the RevenueCat REST API would be a new backend capability with its own state-machine reconciliation, not part of fixing the identity bug.
- Sentry crash-reporting user/session handling on logout (#492). It shares the logout path and is sequenced after this change.
- Premium state for surfaces the issue does not name (Settings hide-distance / private-profile toggles, the like cap). They already read the server on entry and are not reachable through a surviving VM behind the paywall.
- Restore-purchases UI and subscription management UI. These are separate capabilities.

## Standards conformance

- **State holder (docs/11 §2.2):** all new behavior lives in the existing route-scoped androidx ViewModels (`PaywallViewModel`, `NearbyTimelineViewModel`, `UsernameCustomizationViewModel`, `SearchViewModel`) and the existing app singleton `AdFeedController`. The paywall's pending state is a field on the existing `PaywallUiState.Content`. No event bus.
- **App-wide signal:** `PremiumEntitlementSession` follows the established Koin-single state holder pattern: a class exposing a `StateFlow`, like `PushTapNavSignal` and `AppealSession`. It is not a second pattern.
- **App-root lifecycle hook:** `BillingIdentityEffect` mirrors `ProactiveRefreshEffect` exactly: `LifecycleEventEffect(ON_RESUME)`, launch on `rememberCoroutineScope`, delegate to a Koin single, swallow non-cancellation failures.
- **Data / vendor seam (docs/11 §2.6, invariant #16):** the identity methods extend the existing vendor-free `PurchaseController` interface in `:infra:revenuecat`. The RevenueCat import stays fenced in `RevenueCatPurchaseController`.
- **Backend layering (docs/11 §3.1):** a one-branch change in the route's validation step. `SubscriptionService` is untouched.
- **No Pattern-Registry deviation.**

## Cross-layer scope (docs/12)

| Layer | In this change |
|---|---|
| Backend | Webhook: a non-UUID `app_user_id` → `200 ignored` (the unknown-user contract). The existing activation path is unchanged. |
| Mobile (Android + iOS, commonMain) | Identity binding, paywall confirm/pending, gated-surface re-evaluation, ad latch. |
| Admin | None. Subscription state is already visible to admins through the shipped subscription surfaces, and nothing new is recorded. |

The full vertical slice ships. No layer is deferred.

## Decisions

### D1 — The identity source of truth is the token store's `sub`, applied by one idempotent `syncIdentity()`

`PremiumEntitlementSession.syncIdentity()` reads the signed-in user id through the existing `SelfUserIdProvider` (the access token's `sub`, the same seam the profile and radius gates use). It then calls `purchaseController.logIn(id)` when signed in, or `logOut()` when signed out. It is idempotent: `logIn` for the already-bound id and `logOut` while anonymous are local no-ops in the controller. It never throws, and it returns whether RevenueCat is now identified as the signed-in user.

There is one function instead of separate `onSignedIn(id)` / `onSignedOut()` callbacks, so every call site simply means "make RevenueCat match the session". A missed or failed call is healed by the next one.

- *Alternative: configure with `appUserId` at startup.* `configureRevenueCat` runs synchronously in `initKoin`, before the async secure-storage token read. It also cannot handle account switches. Rejected; configure stays anonymous and `logIn` binds.
- *Alternative: pass the user id from each call site.* That duplicates the JWT decode at every site and invites drift. Rejected.

### D2 — Five call sites cover every session boundary

| Boundary | Call site | Ordering |
|---|---|---|
| Sign-in / sign-up success | `AuthRepository` | After `tokenStore.write`, before returning `Success`, awaited. |
| Voluntary logout | `SettingsViewModel.confirmLogout` | Inside the existing `NonCancellable` wipe, after `tokenStore.clear()` and the `loggedOut` flip, so routing is not delayed. |
| Involuntary invalidation | App-root `SessionExpiryEffect`, when it consumes the session-expired signal | After its re-route, launched so it never stalls the collector. Deliberately NOT inside `SessionInvalidator.invalidate`, which runs within `TokenRefresher`'s single-flight critical section; a vendor round-trip there would hold every request waiting on the refresh (review round 1). |
| Cold-start restore + foreground | New app-root `BillingIdentityEffect` (`ON_RESUME`) | Fire-and-forget on the app-root scope. It also self-heals a `logIn` that failed offline. |
| Immediately before a purchase | `PaywallViewModel.onSubscribe` | If it returns `false`, the purchase is not attempted and a retryable error shows. |

New constructor parameters on `AuthRepository` / `SettingsViewModel` / `PaywallViewModel` default to `null`, the existing `crashReporter` / nullable-seam precedent, so existing constructions and tests are unaffected.

`PremiumEntitlementSession` depends only on `SelfUserIdProvider → TokenStore` and `PurchaseController`. `RevenueCatPurchaseController` has no dependencies. So no consumer of the session gets a cycle through the `HttpClient`. `syncIdentity()` also wraps its body in a non-cancellation catch-all, so its "never throws" contract is enforced at the vendor boundary rather than trusted to the binding.

- *Alternative: sync from `RootRouterScreen`.* The router leaves composition immediately after routing, cancelling its effect scope. It also runs once, so a failed `logIn` never retries. Rejected in favour of the `ON_RESUME` effect.
- *Why sign-in awaits:* the sign-in flow already shows a loader across the Google ceremony and the backend exchange. An offline RevenueCat call fails fast, and a slow one is healed by the next resume anyway. The pre-purchase sync is the hard guarantee.

### D3 — A purchase is never made under an anonymous identity

`RevenueCatPurchaseController.purchase()` returns `PurchaseResult.Error("identity_unavailable")` when `Purchases.sharedInstance.isAnonymous`. The paywall's pre-purchase `syncIdentity()` makes this unreachable in the normal path. The controller guard is the fail-closed backstop against taking money the server can never attribute, for example after a DI gap.

### D4 — The paywall confirms the entitlement; pending is a distinct state

- `Success(entitlementActive = true)` → confirmed.
- `Success(false)` → one `isPremiumEntitlementActive()` recheck, because `CustomerInfo` on the purchase result can trail the entitlement grant. The recheck result is confirmed or pending. The recheck reads `CacheFetchPolicy.FETCH_CURRENT`; the SDK default `CACHED_OR_FETCHED` would just return the cached `CustomerInfo` the purchase itself produced, which defeats the recheck (review round 1).
- New `PurchaseResult.Pending`, mapped from the store's `PaymentPendingError` (Play cash / convenience-store / carrier-billing payments), → pending.

Confirmed calls `PremiumEntitlementSession.onPurchaseConfirmed()` and sets the existing one-shot `purchaseSucceeded` flag, so the host pops the paywall.

Pending sets a new `purchasePending` field on `PaywallUiState.Content`. The screen shows `paywall_purchase_pending` (informational copy, `onSurfaceVariant` color, never error-red) in the error-message slot. The CTA label becomes `paywall_cta_check_status`. A CTA tap while pending rechecks the entitlement instead of re-purchasing, which would hit the store's already-owned error.

- *Alternative: pop on any `Success` and let surfaces sort it out.* That violates the spec's "confirm the entitlement is active" requirement and shows success for an unpaid pending purchase. Rejected.

### D5 — `purchaseConfirmed` is an account-scoped `StateFlow<Boolean>`; surfaces combine it with the server tier

`PremiumEntitlementSession.purchaseConfirmed` becomes `true` on a confirmed purchase. `syncIdentity()` resets it to `false` whenever the resolved user id differs from the last bound id: sign-out, account switch, or first bind. It is state, not an event (docs/11 §2.2). A surface composed *after* the purchase reads `true` immediately, and one alive *across* the purchase observes the flip.

Consumers take `StateFlow<Boolean>` (not the session class), so tests drive a `MutableStateFlow`. Screens resolve it via `getKoin().getOrNull<PremiumEntitlementSession>()?.purchaseConfirmed`, falling back to a constant `false` flow, the `TimelineAds` fail-safe precedent. That keeps the per-test Koin modules of every screen test valid.

- **Nearby / username:** effective tier = server `isPremium` OR `purchaseConfirmed`. The on-entry read ORs in the current value, so a lagging server read cannot overwrite a confirmed purchase. A collector flips `isPremiumKnown = true` when confirmation lands while the VM is alive. Username also clears a stale `PremiumGate` probe/change outcome and the last-probed memo, so the candidate is re-probed.
- **Search:** when confirmation lands while the outcome is `PremiumGate`, re-run the current query once (`retry()`).
- **Server backstops are unchanged.** A `radius_premium_only` / `premium_required` 403 during the webhook-lag window still maps to the existing upsell (see Non-Goals).
- *Alternative: an event counter consumers `drop(1)` on.* A surface created after the purchase would miss it and fall back to the lagging server tier. Rejected.
- *Alternative: consumers re-read the server profile on the signal.* During the lag window this returns Free and re-locks the buyer, which is the exact bug. Rejected.

### D6 — Ads: client confirmation suppresses ads; the latch is keyed to the session

`AdFeedController` takes `purchaseConfirmed: StateFlow<Boolean>` and `currentSessionKey: suspend () -> String?`, bound to `PremiumEntitlementSession::sessionKey`. The key is the user id plus a count of ended sessions. Both parameters are defaulted for existing tests. Keying on the bare user id was a bug (review round 1): a buyer who signed out and back in as the same account kept the latched pre-purchase frequency while the confirmed signal had reset, so ads came back.

- `frequency` becomes `combine(_frequency, purchaseConfirmed) { f, confirmed -> f.takeUnless { confirmed } }`, so slots vanish the moment a purchase is confirmed. `loadAd` returns `null` while confirmed.
- `prepare()` re-runs when the session key differs from the one it last prepared for (a new account, or the same account after a sign-out): it resets the frequency, clears cached ads, and re-fetches config. Within a session it stays a no-op. `currentFrequency` gives a synchronous seed for the UI collector, so feed re-entry never renders one ad-less frame. A confirmed purchase short-circuits `prepare()` before SDK init / UMP, per "Premium viewers see zero ads … no SDK initialization, no UMP form".
- This amends `mobile-ads`' "no client-only premium flag" wording: the client signal can only *remove* ads, which is the fail-safe direction. The server `ads-config` remains the sole source that can *enable* them.
- *Why not have the session reset the controller:* `PremiumEntitlementSession → AdFeedController → AdsConfigFlow → HttpClient → SessionInvalidator → PremiumEntitlementSession` is a Koin resolution cycle. The controller observes instead.

### D7 — Backend: a non-UUID `app_user_id` is an unknown user, not a malformed body

The route's `UUID.fromString` failure now responds `200 {"status":"ignored"}` with a WARN `event=revenuecat_orphan_event reason=non_uuid_app_user_id rc_event_id=…`. The anonymous id itself is never logged. There are no writes.

This matches the existing spec scenario: "an app-user identifier that maps to no `users.id` → 200 … so RevenueCat does not retry indefinitely". A RevenueCat anonymous id is well-formed and simply maps to no user. A missing or blank `app_user_id` stays `400 invalid_request`, because the body is malformed.

## Risks / Trade-offs

- **[Webhook-lag window]** A server-gated action retried within seconds of purchase may 403 back into the upsell → accepted by the `mobile-paywall` contract. The client suppresses ads and unlocks the client-side gates immediately, so only the server-enforced calls can lag. Revisit with a server reconcile only if staging shows noticeable lag.
- **[Sign-in latency]** Awaiting RevenueCat `logIn` adds one round trip to sign-in → offline fails fast. Resume and pre-purchase syncs heal any miss, and the loader is already on screen.
- **[RevenueCat alias semantics]** `logIn` from an anonymous id that has no purchases simply switches. An anonymous id *with* purchases (impossible after D3, but possible for a hypothetical pre-fix sandbox purchase) is merged by RevenueCat under its default transfer behavior → acceptable pre-launch. No production purchases exist.
- **[Identity guard false-negative]** If `logIn` fails repeatedly (RevenueCat outage), purchases are blocked with a retryable error → a deliberate fail-closed choice. Money the server cannot attribute is worse than a retry.
- **[Instant search re-run vs. the lag window]** Search re-runs the gated query the moment the purchase is confirmed, while the paywall is still on top. If the webhook hasn't landed yet, that re-run 403s back to the upsell → accepted under the webhook-lag non-goal (review round 1, N2). The username gate deliberately does NOT auto-re-probe, for the same reason: it clears the stale gate and re-probes on the next edit. A server-side reconcile would close the window for every surface.
- **[Webhook alias lookup]** The route does not fall back to `original_app_user_id` / `aliases` before ignoring a non-UUID id (review round 1, Q1). Not needed: the client now refuses anonymous purchases (D3), and no production purchases exist pre-launch.
- **[#492 overlap]** This change adds one line to the logout wipe that #492 also touches → #492 is held until this merges. It will rebase trivially.

## Migration Plan

- No schema change and no migration. Mobile and backend changes are independent and backward compatible. An older app build still purchases anonymously, and the backend now acks those events `200 ignored` instead of `400`.
- Rollback = revert the PR. The only persisted effect is RevenueCat-side aliasing of test sandbox customers.

## Open Questions

- None blocking. The staging sandbox purchase (operator, physical device, Play license tester) is the end-to-end verification of attribution: purchase → webhook `200 ok` → `subscription_status = 'premium_active'`. It is recorded as a human-required task.
