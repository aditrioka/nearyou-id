## Context

`:mobile:app` has two shipped Premium upsell CTAs that go nowhere: the `DailyCapUpsellDialog` "Aktifkan Premium" button (frame 18) and the `SearchScreen` Premium-gate / rate-limit CTA ([`SearchScreen.kt:318`](mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/search/SearchScreen.kt:318)) — both invoke a hoisted callback wired to dismiss only, because no paywall destination exists. `mobile-cap-upsell-dialog` § "Premium CTA navigation is deferred" was authored with this change as its named MODIFY hook.

The backend subscription contract is owned by `subscription-billing-webhook` ([PR #291](https://github.com/aditrioka/nearyou-id/pull/291), **merged**; its spec is on `main`): RevenueCat webhooks are the authoritative writer of `users.subscription_status ∈ {free, premium_active, premium_billing_retry}`. Critically, **#291 exposes no client read endpoint** — which is the canonical RevenueCat split: the client SDK's `CustomerInfo` entitlement is the client-side premium signal; the webhook keeps the server's authority in sync. Both sync from RevenueCat. Server-side gates (e.g. premium search's `403 premium_required`) read the webhook-written column.

The RevenueCat **mobile** SDK is not yet integrated: no `:infra:revenuecat` module, no catalog dependency, and the RevenueCat dashboard / store products / API-key secret slots are Phase-4 operator setup that is not done. So a live, exercisable purchase cannot be built or CI-verified today — this design must degrade gracefully until that provisioning lands.

## Goals / Non-Goals

**Goals:**
- A `PaywallRoute` / `PaywallScreen` per mockup frame 17, reachable from both existing CTAs, turning dead-ends into live conversion entry points (closes [#235](https://github.com/aditrioka/nearyou-id/issues/235), addresses [#254](https://github.com/aditrioka/nearyou-id/issues/254)).
- A real RevenueCat purchase path: fetch Offerings → display store-localized pricing → execute purchase → confirm entitlement → return and let the gated surface re-evaluate.
- Fence the RevenueCat SDK in a new `:infra:revenuecat` module behind a vendor-SDK-free `PurchaseController` interface (invariant #16), reusing the established `:infra:*` consumer-seam pattern.
- Fail-soft correctness before billing infra is provisioned (no crash, no fake purchase, CI/sandbox green).

**Non-Goals:**
- The premium **tenure-badge system** (live Perunggu→Berlian tier from subscription duration; companion board `nearyou-premium-tenure-badges.html`) — its own future change. The frame-17 ladder, if rendered, is a static marketing pitch only (no live tier computation); recommend deferring it (see Decision 7).
- The **proactive** search upsell-on-open ([#253](https://github.com/aditrioka/nearyou-id/issues/253)) — captured as an explicit deferred requirement; the entitlement seam ships here, the behavior does not.
- **Manage-subscription / restore-purchases** (the existing `settings_row_manage_subscription` row, RevenueCat Customer Center) — separate change.
- Any backend change: the subscription contract is owned by [#291](https://github.com/aditrioka/nearyou-id/pull/291). No Flyway migration.
- Mandatory production webhook signing, privacy-flip 72h coupling — owned by other changes per the #291 spec.

## Decisions

**D1 — `:infra:revenuecat` wraps `purchases-kmp-core` behind a commonMain `PurchaseController` interface.**
The RevenueCat Kotlin Multiplatform SDK (`purchases-kmp-core`) is imported only in `:infra:revenuecat`, `implementation`-scoped, so it never reaches `:mobile:app`'s compile classpath (invariant #16, "no vendor SDK import outside `:infra:*`"). `:mobile:app` depends only on the `PurchaseController` interface (`suspend fetchOfferings()`, `suspend purchase(pkg)`, an `isPremiumEntitlementActive()` / entitlement check). This is the **same architecture as the §2.6 realtime-consumer seam** (`:infra:supabase-realtime` / `ChatRealtimeSubscriber`): vendor-SDK-free interface in a KMP `:infra:*` module alongside the single vendor implementation, app consumes the interface. *Alternative considered:* import the SDK directly in `:mobile:app` — rejected, violates invariant #16. *Alternative:* the unofficial `mirzemehdi/KMPRevenueCat` wrapper — rejected in favor of RevenueCat's own first-party SDK. *Evidence (verified 2026-06-16):* RevenueCat `purchases-kmp` is a **stable, Maven-Central-published** SDK (the catalog pins the current `purchases-kmp-core = 3.0.6`, which wraps `purchases-ios` 5.77.0 / `purchases-android` 10.8.0) and is RevenueCat's canonical KMP in-app-subscription SDK per its installation docs ([install docs](https://www.revenuecat.com/docs/getting-started/installation/kotlin-multiplatform), [repo](https://github.com/RevenueCat/purchases-kmp)).

**D2 — Custom Compose paywall UI (frame 17), not RevenueCat's `purchases-kmp-ui` hosted-paywall template.**
The SDK ships Compose Multiplatform paywall components driven by the RevenueCat dashboard, but we render our own frame-17 / brand UI from `purchases-kmp-core` Offerings data. This keeps the vendor SDK fully fenced in `:infra:revenuecat`, keeps the UI in `:mobile:app` on the `mobile-design-system` substrate, and avoids coupling the screen's look to dashboard-hosted templates. *Trade-off:* we maintain the pricing-card layout ourselves rather than getting it for free — acceptable; the mockup is the contract and the layout is small.

**D3 — Pricing, anchors, and savings are derived from Offerings at runtime; the mockup governs layout only.**
docs/01 §Multi-Period Pricing values (Weekly Rp9.900 / Monthly Rp29.000 / Yearly Rp249.000) are explicitly "target, verify Pre-Phase 1" — they are **not** the runtime source of truth. The screen reads each package's store-localized price string from the fetched Weekly/Monthly/Yearly packages, and computes the frame-17 treatment from those values: the strike-through anchor (Monthly vs 4× Weekly; Yearly vs 12× Monthly), the savings percentage, the Weekly per-day baseline (price ÷ 7), the default-selected Monthly card, and the "Paling hemat" tag on Yearly. This satisfies docs/11 §2.8 precedence (specs/docs govern behavior, mockups govern look) and the CMP-strings invariant — prices are SDK data; only the static labels (Mingguan / Bulanan / Tahunan / Hemat / per hari / Paling hemat) are string resources. *Alternative considered:* hardcode the doc prices — rejected (drifts from the store, breaks store-localization, and the doc itself says verify-later).

**D4 — `PaywallRoute` is a payload-carrying `NavKey` with an entry-context enum; return is a natural Nav3 pop.**
The route carries a non-PII `PaywallEntry` enum (`LIKE_CAP`, `SEARCH_GATE`, `USERNAME`) so the hero headline is contextual to the surface that opened it; the `USERNAME` case lets `premium-username-customization` ([#322](https://github.com/aditrioka/nearyou-id/pull/322)) route in — its username gate's `appEntryProvider` call site pushes `PaywallRoute(USERNAME)`, wired here on merge (docs/03 § Premium Username Customization entry point). It is registered in `AppNavSerialization.kt`'s polymorphic `SerializersModule` via explicit `subclass(...)` (iOS-saveable back stack, docs/11 §2.3) and pushed onto the **root** back stack like `SearchRoute` / `PostDetailRoute`. Return uses Nav3's natural back-stack pop — `PendingReturnDestination` is the 401-re-route mechanism only and is **not** reused here. *Alternative considered:* parameterless route + a shared "reason" holder — rejected; the entry-context is display data safe to serialize, and a typed payload is the established `PostDetailRoute` pattern.

**D5 — Subscribe → confirm entitlement → pop; the client-fast / backend-eventual window is handled, not hidden.**
On "Aktifkan Premium", the ViewModel calls `PurchaseController.purchase(selectedPackage)` (state → `PurchaseInProgress`); on success it confirms the entitlement via `CustomerInfo` and pops. The RevenueCat SDK flips `CustomerInfo` immediately client-side; `users.subscription_status` flips later via the #291 webhook. So a server-gated retry (e.g. re-running a search) may briefly still return `403` until the webhook lands. The design treats this as expected eventual consistency: the success path is driven by the SDK entitlement (authoritative for the client), and gated server surfaces simply re-attempt — they do not render a false "you are not Premium" hard error during the window. *Trade-off:* a few seconds of possible re-gating after purchase; acceptable and self-healing, and far simpler than a client/server purchase-token handshake.

**D6 — Fail-soft `Unconfigured` state until billing infra is provisioned.**
`fetchOfferings()` returns a domain result that distinguishes "loaded with packages", "empty/unavailable" (SDK not configured, no offerings, network), and "error". With no provisioned dashboard/store, the screen renders a graceful `Unconfigured` state ("Premium belum tersedia") instead of crashing or faking a purchase. This keeps the change shippable and CI/sandbox-green now; the real purchase path is verified manually once the operator provisions RevenueCat + store products + the publishable client key (docs/11 §5 DoD — the manual-verification boundary is stated honestly, not skip-rationalized).

**D9 — The RevenueCat mobile key is a per-flavor publishable client key, NOT a `secretKey()` slot.**
The RevenueCat **mobile** SDK key is a *publishable* client key that ships in the app binary by design; it is wired per-flavor via Android `buildConfigField` / iOS xcconfig, exactly like the existing `SUPABASE_ANON_KEY` / `GOOGLE_SERVER_CLIENT_ID` (overridable via a `-PstagingRevenueCatPublicKey`-style project property), with a `REPLACE_WITH_*` placeholder until provisioned (→ `Unavailable`). The `secretKey(env, name)` helper + the `staging-revenuecat-*` GCP Secret Manager slot are **backend-only** and belong to #291's `revenuecat-webhook-secret` — a different secret, not reused here. The RevenueCat `appUserID` SHALL be configured to the authenticated `users.id` UUID so the backend webhook's user resolution (#291) matches the client purchase.

**D7 — Tenure ladder deferred from this change (recommendation, open to review).**
Frame 17's caption marks the Perunggu→Berlian ladder as an *optional* board element, and the tenure-badge system is a separate concept board. Rendering a live ladder needs tenure computation that does not exist. Recommendation: omit the ladder from v1 (or render nothing tenure-related) and let the dedicated tenure-badge change own it. Flagged as an Open Question for review.

**D8 — Placement follows the de-facto `screens/<feature>/` convention.**
`PaywallScreen` / `PaywallViewModel` / `PaywallUiState` live in `screens/paywall/`; the route in `screens/routing/NavKeys.kt`. This matches all eight shipped screens and the routing infra this change must extend (`NavKeys`, `AppNavSerialization`, `AppEntryProvider` all live under `screens/routing/`). The docs/11 §2.1 `ui/<feature>/` target shape is a repo-wide migration not yet performed for any screen; adopting it for one screen amid a `screens/`-based router would fork the layout. The migration stays a separate cross-cutting move (not triggered here). Called out to preempt a §2.1 reviewer flag.

### Standards conformance (docs/11 — anti-patchwork)

This change builds on existing Pattern-Registry patterns and introduces **no new pattern** → **no docs/11 amendment required**:
- **State (§2.2):** `PaywallViewModel` = androidx `ViewModel` in commonMain via `koinViewModel()`, one `StateFlow<PaywallUiState>` via `stateIn(WhileSubscribed(5000))`, the one-shot purchase-success signal as a nullable-style flag cleared via `onReturnConsumed()` (`purchaseError` is a sticky flag cleared on the next `onSubscribe()`) — no event-bus.
- **Navigation (§2.3):** `@Serializable` `NavKey` registered in the polymorphic `SavedStateConfiguration`; root-stack push like `SearchRoute` / `PostDetailRoute`.
- **Data / vendor-SDK seam (§2.5 + §2.6):** commonMain `PurchaseController` interface + per-platform Koin binding; the RevenueCat SDK lives only in `:infra:revenuecat`, `implementation`-scoped — the identical fencing as `:infra:supabase-realtime`.
- **UI substrate:** `mobile-design-system` tokens; mockup frame 17 per §2.8 (behavior from specs/docs, look from the board).
- **Module/README maintenance:** new `:infra:revenuecat` → `dev/module-descriptions.txt` + `dev/scripts/sync-readme.sh --write`.

## Risks / Trade-offs

- **Billing infra not provisioned → live purchase unverifiable in CI/sandbox** → D6 fail-soft `Unconfigured` state keeps the app correct; tasks include a manual post-provisioning verification step; the `PurchaseController` is consumed via a Fake in tests so the app-side logic is fully covered.
- **iOS K/N category-member imports** (RevenueCat StoreKit bridging in `iosMain`) compile-only, Linux CI can't catch (docs/11 §2.5) → run `linkDebugFrameworkIosSimulatorArm64` locally when touching `iosMain`; tasks call this out.
- **Client-premium / backend-`403` window after purchase** → D5: re-attempt, never a false hard error; self-heals on webhook delivery.
- **RevenueCat SDK init needs Android `Application` context** → provide via the androidMain Koin platform module (the existing platform-binding pattern), not an `expect class`.
- **Catalog substrate add (`purchases-kmp`)** → version pinned per docs/11 §1; re-checked at `/opsx:apply` kickoff per the project's pre-implementation library re-check.

## Migration Plan

No data migration. Rollout: ship the screen + `:infra:revenuecat` with the fail-soft state; the CTAs route to a working (if `Unconfigured`-until-provisioned) paywall. When the operator provisions the RevenueCat dashboard + store products + the per-flavor publishable client key (D9), the live Offerings/purchase path activates with no code change. Rollback: revert the PR (no schema/state to unwind); the CTAs return to dismiss-only.

## Open Questions

- **D7 — render the frame-17 tenure ladder as a static marketing pitch now, or omit until the tenure-badge change?** Recommendation: omit. Resolve at review.
- **Disclosure footer clause (resolved during reconciliation):** the verbatim user-facing clause is the `docs/01-Business.md` § Pricing & Payment line — "Fitur Premium dapat berubah atau ditambahkan seiring waktu." — which is the same text shown as the frame-17 footer. docs/01 mandates it during Months 1-5 (features-available-now, no image-upload mention). No ambiguity remains; the implementation uses this verbatim.
- **RevenueCat entitlement identifier (resolved):** staging is now provisioned (PR [#319](https://github.com/aditrioka/nearyou-id/pull/319), Test Store via the v2 REST API) — the entitlement is `premium` (`entlcce792ba27`), offering `default` (`ofrngcfc30f15d2`), packages `$rc_weekly`/`$rc_monthly`/`$rc_annual`. The binding bakes these in (tasks 2.2/2.4). So the live Test Store path is now exercisable in **staging** (not purely post-provisioning-deferred); **production** RevenueCat remains unprovisioned (needs Apple/Google dev accounts), so the fail-soft `Unconfigured` state (D6) and the manual prod-verification boundary still stand. Caveat: Test Store package *prices* aren't settable via the v2 API (dashboard-only) → may be unset until configured; the degenerate-price guard + `Unavailable` handle it.
