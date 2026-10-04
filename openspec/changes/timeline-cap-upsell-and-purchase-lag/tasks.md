# Tasks: timeline-cap-upsell-and-purchase-lag

## 1. Paywall entry + strings

- [x] 1.1 `NavKeys.kt`: append `TIMELINE_CAP` to `PaywallEntry` (append-only, design D2) with a KDoc line naming the read-cap banner + hard state
- [x] 1.2 `:shared:resources` `strings.xml`: add `paywall_subhead_timeline_cap` ("Baca timeline tanpa batas"), `premium_activating_title` ("Premium sedang diaktifkan"), `premium_activating_body` ("Pembelianmu berhasil dan Premium sedang diaktifkan. Coba lagi sebentar lagi ya.") with a comment citing the specs
- [x] 1.3 `PaywallScreen.PaywallHero`: map `TIMELINE_CAP` → `paywall_subhead_timeline_cap` (exhaustive `when`, no `else`)

## 2. The shared activating notice (#517, design D4–D6)

- [x] 2.1 `ui/billing/RememberPremiumConfirmed.kt`: add `rememberPremiumActivating(): Boolean` over `rememberPremiumConfirmed()` (`collectAsStateWithLifecycle()`); KDoc the webhook-lag meaning and the bare-test caveat
- [x] 2.2 New `ui/components/PremiumActivatingDialog.kt`: M3 `AlertDialog`, `premium_activating_title` / `premium_activating_body`, a single "Tutup" (`cta_close`) confirm slot → `onDismiss`; `onDismissRequest` → `onDismiss`; test-tag constants; no navigation reference
- [x] 2.3 `DailyCapUpsellDialog`: `premiumActivating: Boolean = rememberPremiumActivating()`; when true, early-return `PremiumActivatingDialog(onDismiss)` (before the countdown state); KDoc
- [x] 2.4 `RadiusPremiumUpsellDialog`: same parameter + early-return; KDoc
- [x] 2.5 `EditPostScreen.EditPremiumUpsellDialog`: when `rememberPremiumActivating()`, render `PremiumActivatingDialog(onDismiss)`
- [x] 2.6 `SearchScreen.PremiumGateState(onActivatePremium, onRetry)`: when activating, `premium_activating_body` + a "Coba lagi" (`SEARCH_RETRY_TAG`) → `viewModel::retry`, no CTA
- [x] 2.7 `UsernameCustomizationScreen.PremiumGate(onActivatePremium, onRetry)`: when activating, `premium_activating_body` + a "Coba lagi" (`USERNAME_GATE_RETRY_TAG`) → `viewModel.onCandidateChange(candidate)` (back to the editor), no CTA
- [x] 2.8 `SettingsScreen`: the hide-distance / private-profile upsell snackbar text reads `premium_activating_body` when activating
- [x] 2.9 `PostCreationScreen`: the `routeToPaywall` one-shot pushes `IMAGE_ATTACH` only when not activating; when activating it shows `PremiumActivatingDialog`, whose "Tutup" calls `onPaywallRouted()` (design D6)

## 3. Timeline read-cap CTA (#516, design D1–D3)

- [x] 3.1 `ListStates.kt`: `SoftLimitBanner(onActivatePremium, modifier, premiumActivating = rememberPremiumActivating())` bakes `timeline_limit_soft` + an end-aligned "Aktifkan Premium" `TextButton` (`SOFT_LIMIT_PREMIUM_TAG`); renders nothing while activating
- [x] 3.2 `ListStates.kt`: new `HardLimitState(onActivatePremium, onRetry, testTag, modifier, premiumActivating = …)` inside `ListScrollableState`: `timeline_limit_hard` + a filled "Aktifkan Premium" (`HARD_LIMIT_PREMIUM_TAG`), the `ListErrorState` layout; activating → `premium_activating_body` + "Coba lagi" (`HARD_LIMIT_RETRY_TAG`) → `onRetry`; refresh the kit header comment (copy no longer all hoisted)
- [x] 3.3 `PostFeedList`: `banner: String?` → `softLimitUpsell: (() -> Unit)?` (non-null renders `SoftLimitBanner(onActivatePremium = it)`); KDoc
- [x] 3.4 `Global` / `Following` / `NearbyTimelineScreen`: thread `onReadCapUpsell = { onActivatePremium(PaywallEntry.TIMELINE_CAP) }` into the private content; `HardLimit` → `HardLimitState(onActivatePremium = onReadCapUpsell, onRetry = onRetry, …)`, `SoftLimit` → `softLimitUpsell = onReadCapUpsell`
- [x] 3.5 `docs/11-Engineering-Standards.md` § 2.1: add `HardLimitState` to the list-state kit + register the "shared component reads an app-wide UI signal through a defaulted parameter" idiom (design D4)
- [x] 3.6 `docs/03-UX-Design.md` § Rate Limit Communication: the read-cap CTA (`TIMELINE_CAP`) + the webhook-lag activating copy

## 4. Tests (one per spec'd scenario)

- [x] 4.1 `NavKeySerializationTest`: the declared-order assertion gains `TIMELINE_CAP` (the round-trip already iterates `PaywallEntry.entries`)
- [x] 4.2 Robolectric `PremiumActivatingDialogTest` (new, bare): title + body + "Tutup", no "Aktifkan Premium", "Tutup" → `onDismiss` once; dark render; `RadiusPremiumUpsellDialog` with `premiumActivating = true` → the notice, no CTA, dismiss once, `onActivatePremium` never (and `false` keeps the upsell)
- [x] 4.3 Robolectric `DailyCapUpsellDialogTest`: the bare tests pass `premiumActivating = false`; new: `premiumActivating = true` → the notice, no CTA / `cap_dialog_title` / cap body, "Tutup" → `onDismiss` once, `onActivatePremium` never
- [x] 4.4 Robolectric `ListStatesTest`: `SoftLimitBanner` `false` → copy + CTA fires once, `true` → renders nothing; `HardLimitState` `false` → copy + CTA fires once (no retry), `true` → activating body + "Coba lagi" fires `onRetry` once, never `onActivatePremium`
- [x] 4.5 Robolectric `GlobalTimelineScreenTest` / `FollowingTimelineScreenTest` / `NearbyTimelineScreenTest`: hard CTA → `onActivatePremium(TIMELINE_CAP)` once; soft banner CTA → `TIMELINE_CAP` once with cards rendered; confirmed session → hard shows the notice (no limit copy, no CTA) and "Coba lagi" re-fetches page 1; soft renders no banner (no limit copy, no notice, no CTA) with the cards kept
- [x] 4.6 Robolectric `GlobalTimelineScreenTest`: like-cap `429` with a confirmed session → the activating notice (no frame-18 dialog, no CTA), "Tutup" closes it, `onActivatePremium` never; the existing like-cap tests (no session bound) keep the frame-18 upsell
- [x] 4.7 Robolectric `HomeTabHostScreenTest` (real `appEntryProvider`, Nearby start tab): the hard read-cap CTA → root top `PaywallRoute(TIMELINE_CAP)`
- [x] 4.8 Robolectric `SearchScreenTest`, `UsernameCustomizationScreenTest`, `EditPostScreenTest`: a confirmed session → the notice, no gate body, no CTA; search "Coba lagi" re-runs the query; username "Coba lagi" returns to the editor holding the candidate; edit "Tutup" dismisses, `onActivatePremium` never, editor keeps the typed text
- [x] 4.9 Robolectric `PostCreationScreenTest` (Free tier read + confirmed session → attach tap shows the notice, "Tutup" closes it, no paywall, no picker) and `SettingsScreenTest` (both Premium toggles → the activating snackbar, no `settings_*_premium_only`, no `PATCH`)
- [x] 4.10 Robolectric `PaywallScreenTest`: `TIMELINE_CAP` renders `paywall_subhead_timeline_cap`, not the default
- [x] 4.11 Robolectric `RememberPremiumActivatingTest` (new): signed-in session → `false`, `onPurchaseConfirmed()` → `true`, sign-out `syncIdentity()` → `false`; unbound → `false`
- [ ] 4.12 The new UI tests (`PremiumActivatingDialogTest`, `RememberPremiumActivatingTest`) are on the `mobile/app/build.gradle.kts` Release-variant exclude list; `:mobile:app:testDevReleaseUnitTest` passes
- [ ] 4.13 iosTest (K/N-legal names): `PaywallFlowIosTest` renders the `TIMELINE_CAP` headline; `PremiumActivatingIosTest` (new) renders the activating notice from `DailyCapUpsellDialog` through the default Koin resolution of a confirmed session

## 5. Verification & lifecycle

- [ ] 5.1 Gate (docs/13): `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test :mobile:app:ktlintCheck :mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest` + `:mobile:app:iosSimulatorArm64Test` (pre-existing #348 drift: compare against an `origin/main` baseline)
- [ ] 5.2 Mockups: render frames 1, 17, 18; `dev/scripts/mockup-measure.sh dev/mockups/nearyou-screens-mockup.html 18`; confirm `PremiumActivatingDialog` matches the frame-18 component
- [ ] 5.3 Manual verify (verify-loop §B, local `verify36` emulator) against a stub backend serving the real wires (memory: stub backend for 429/403 upsell verify): soft banner CTA → paywall `TIMELINE_CAP` headline; hard state CTA → paywall; like `429` dialog; then with a confirmed purchase (temporary uncommitted harness flipping `purchaseConfirmed`) → the activating notice on the like dialog, the hard state (+ retry), the soft banner gone, and one `403` gate. Screenshots in the PR body (docs/11 §5 DoD)
- [ ] 5.4 PR title/body current at each phase boundary; body carries `Closes #516` and `Closes #517` on separate lines; archive via `/opsx:archive`. At archive, hand-edit the now-stale `## Purpose` of `mobile-paywall` (entry list), `mobile-premium-entitlement` (consumers) and `mobile-cap-upsell-dialog` (the activating variant), since deltas cannot modify Purpose
