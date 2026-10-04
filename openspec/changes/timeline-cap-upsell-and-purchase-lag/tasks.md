# Tasks: timeline-cap-upsell-and-purchase-lag

## 1. Paywall entry + strings

- [ ] 1.1 `NavKeys.kt`: append `TIMELINE_CAP` to `PaywallEntry` (append-only, design D2) with a KDoc line naming the read-cap banner + hard state
- [ ] 1.2 `:shared:resources` `strings.xml`: add `paywall_subhead_timeline_cap` ("Baca timeline tanpa batas"), `premium_activating_title` ("Premium sedang diaktifkan"), `premium_activating_body` ("Pembelianmu berhasil dan Premium sedang diaktifkan. Coba lagi sebentar ya.") with a comment citing the specs
- [ ] 1.3 `PaywallScreen.PaywallHero`: map `TIMELINE_CAP` → `paywall_subhead_timeline_cap` (exhaustive `when`, no `else`)

## 2. The shared activating notice (#517, design D4/D5)

- [ ] 2.1 `ui/billing/RememberPremiumConfirmed.kt`: add `rememberPremiumActivating(): Boolean` over `rememberPremiumConfirmed()` (collected as Compose state); KDoc the webhook-lag meaning and the bare-test caveat
- [ ] 2.2 New `ui/components/PremiumActivatingDialog.kt`: M3 `AlertDialog`, `premium_activating_title` / `premium_activating_body`, a single "Tutup" (`cta_close`) confirm slot → `onDismiss`; `onDismissRequest` → `onDismiss`; test-tag constants; no navigation reference
- [ ] 2.3 `DailyCapUpsellDialog`: `premiumActivating: Boolean = rememberPremiumActivating()`; when true, early-return `PremiumActivatingDialog(onDismiss)` (before the countdown state); KDoc
- [ ] 2.4 `RadiusPremiumUpsellDialog`: same parameter + early-return; KDoc
- [ ] 2.5 `EditPostScreen.EditPremiumUpsellDialog`: when `rememberPremiumActivating()`, render `PremiumActivatingDialog(onDismiss)`
- [ ] 2.6 `SearchScreen.PremiumGateState`: when activating, `premium_activating_body` in place of `search_premium_gate_body` and no CTA
- [ ] 2.7 `UsernameCustomizationScreen.PremiumGate`: when activating, `premium_activating_body` in place of `username_premium_gate_body` and no CTA

## 3. Timeline read-cap CTA (#516, design D1–D3)

- [ ] 3.1 `ListStates.kt`: `SoftLimitBanner(onActivatePremium, modifier, premiumActivating = rememberPremiumActivating())` bakes `timeline_limit_soft` and adds an end-aligned "Aktifkan Premium" `TextButton` (tagged); activating → `premium_activating_body`, no button
- [ ] 3.2 `ListStates.kt`: new `HardLimitState(onActivatePremium, testTag, modifier, premiumActivating = …)` inside `ListScrollableState`: `timeline_limit_hard` + a filled "Aktifkan Premium" `Button` (tagged), the `ListErrorState` layout; activating → `premium_activating_body`, no button
- [ ] 3.3 `PostFeedList`: `banner: String?` → `softLimitUpsell: (() -> Unit)?` (non-null renders `SoftLimitBanner(onActivatePremium = it)`); KDoc
- [ ] 3.4 `Global` / `Following` / `NearbyTimelineScreen`: thread `onReadCapUpsell = { onActivatePremium(PaywallEntry.TIMELINE_CAP) }` into the private content; `HardLimit` → `HardLimitState`, `SoftLimit` → `softLimitUpsell = onReadCapUpsell`; refresh KDoc
- [ ] 3.5 `docs/11-Engineering-Standards.md` § 2.1: add `HardLimitState` to the list-state kit

## 4. Tests (one per spec'd scenario)

- [ ] 4.1 `NavKeySerializationTest`: the declared-order assertion gains `TIMELINE_CAP` (the round-trip already iterates `PaywallEntry.entries`)
- [ ] 4.2 Robolectric `PremiumActivatingDialogTest` (new): title + body + "Tutup", no "Aktifkan Premium", "Tutup" → `onDismiss` once; light + dark render
- [ ] 4.3 Robolectric `DailyCapUpsellDialogTest`: bare tests pass `premiumActivating = false`; new test: `premiumActivating = true` → activating copy, no CTA / `cap_dialog_title` / countdown, "Tutup" → `onDismiss`, `onActivatePremium` never
- [ ] 4.4 Robolectric radius-dialog coverage (`RadiusPremiumUpsellDialog` test — new or the existing host test): `premiumActivating = true` → activating copy, no CTA, dismiss once, `onActivatePremium` never
- [ ] 4.5 Robolectric `ListStatesTest`: `SoftLimitBanner` + `HardLimitState` with `false` → limit copy + CTA fires once; with `true` → activating body, no limit copy, no CTA
- [ ] 4.6 Robolectric `GlobalTimelineScreenTest` / `FollowingTimelineScreenTest` / `NearbyTimelineScreenTest`: hard CTA → `onActivatePremium(TIMELINE_CAP)` once; soft banner CTA → `TIMELINE_CAP` once with cards rendered; a Koin-bound confirmed `PremiumEntitlementSession` → activating body on hard + soft, no CTA
- [ ] 4.7 Robolectric `GlobalTimelineScreenTest` (or the Home host test): like-cap `429` with a confirmed session → activating notice, no `PaywallRoute`; with no session bound → the frame-18 upsell with its CTA
- [ ] 4.8 Robolectric `HomeTabHostScreenTest` (real `appEntryProvider`): the hard read-cap CTA → root top `PaywallRoute(TIMELINE_CAP)`
- [ ] 4.9 Robolectric `SearchScreenTest`, `UsernameCustomizationScreenTest`, `EditPostScreenTest`: a confirmed session → activating copy, no CTA (edit: "Tutup" dismisses, `onActivatePremium` never, editor keeps the text)
- [ ] 4.10 Robolectric `PaywallScreenTest`: `TIMELINE_CAP` renders `paywall_subhead_timeline_cap`, not the default
- [ ] 4.11 commonTest / Robolectric resolver test: `rememberPremiumActivating()` over a bound session flips `false` → `true` on `onPurchaseConfirmed()` and back on a sign-out `syncIdentity()`
- [ ] 4.12 Every new `*Test` that composes UI is on the `mobile/app/build.gradle.kts` Release-variant exclude list; `:mobile:app:testDevReleaseUnitTest` passes
- [ ] 4.13 iosTest (K/N-legal names): `PaywallFlowIosTest` renders the `TIMELINE_CAP` headline; one K/N render of the activating dialog (the cap dialog with `premiumActivating = true`)

## 5. Verification & lifecycle

- [ ] 5.1 Gate (docs/13): `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test :mobile:app:ktlintCheck :mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest` + `:mobile:app:iosSimulatorArm64Test` (pre-existing #348 drift: compare against an `origin/main` baseline)
- [ ] 5.2 Mockups: render frames 1, 17, 18; `dev/scripts/mockup-measure.sh dev/mockups/nearyou-screens-mockup.html 18`; confirm `PremiumActivatingDialog` matches the frame-18 component
- [ ] 5.3 Manual verify (verify-loop §B, local `verify36` emulator) against a stub backend serving the real wires (memory: stub backend for 429/403 upsell verify): soft banner CTA → paywall `TIMELINE_CAP` headline; hard state CTA → paywall; like `429` dialog; then with a confirmed purchase (temporary uncommitted harness flipping `purchaseConfirmed`) → activating notice on the like dialog, hard state, soft banner, and one `403` gate. Screenshots in the PR body (docs/11 §5 DoD)
- [ ] 5.4 PR title/body current at each phase boundary; body carries `Closes #516` and `Closes #517` on separate lines; archive via `/opsx:archive`. At archive, hand-edit the now-stale `## Purpose` of `mobile-paywall` (entry list) and `mobile-premium-entitlement` (consumers), since deltas cannot modify Purpose
