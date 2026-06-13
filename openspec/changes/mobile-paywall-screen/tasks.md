## 1. Substrate + `:infra:revenuecat` module scaffold

- [ ] 1.1 Re-check the RevenueCat KMP SDK pin freshness at implementation kickoff (project.md § Pre-implementation library re-check): confirm the latest stable `purchases-kmp` (`com.revenuecat.purchases:purchases-kmp-core`) version on Maven Central; pin it in `gradle/libs.versions.toml` (version + library alias) per docs/11 §1.
- [ ] 1.2 Create the `:infra:revenuecat` KMP module (`infra/revenuecat/build.gradle.kts`) with `commonMain` + `androidMain` + `iosMain` source sets, mirroring `:infra:supabase-realtime`'s module shape; declare the `purchases-kmp` dependency `implementation`-scoped (NOT `api`) so it never reaches the consumer's compile classpath.
- [ ] 1.3 Register the module in `settings.gradle.kts` (`include(":infra:revenuecat")`).
- [ ] 1.4 Add the module's one-line description to `dev/module-descriptions.txt` and run `dev/scripts/sync-readme.sh --write`; verify the root README module list updated.

## 2. PurchaseController seam (vendor-SDK-free interface + RevenueCat binding)

- [ ] 2.1 In `:infra:revenuecat` commonMain, define the vendor-SDK-free `PurchaseController` interface + plain-Kotlin domain models (`PaywallPackage` with period + store-localized price + raw amount/currency for derivation; `OfferingsResult` = Loaded(packages) / Unavailable; `PurchaseResult` = Success(entitlementActive) / Cancelled / Error; an entitlement check) — no RevenueCat SDK type in any signature.
- [ ] 2.2 Implement the RevenueCat-backed `PurchaseController` in `:infra:revenuecat` commonMain/androidMain/iosMain over `purchases-kmp-core` (configure, `getOfferings`, `purchase(package)`, `CustomerInfo` entitlement read), mapping vendor types → the domain models. Map "no offering / not configured / fetch failure" → `OfferingsResult.Unavailable` (the fail-soft seam, design D6).
- [ ] 2.3 Provide platform SDK init via per-platform Koin modules (Android needs the `Application` context; iOS the configuration) — the §2.5 platform-binding pattern, NOT an `expect class`. Read the RevenueCat API key via the project secret/config seam (`staging-revenuecat-*` slots, unset until provisioned → `Unavailable`).
- [ ] 2.4 Run `:infra:revenuecat:linkDebugFrameworkIosSimulatorArm64` locally to catch K/N ObjC category-member import gaps (docs/11 §2.5 — Linux CI cannot).

## 3. Strings (CMP Resources only)

- [ ] 3.1 Add the new CMP string keys to `:shared:resources` (`composeResources/values/strings.xml` + dark/locale variants as applicable): the 6 benefit labels, period labels (Mingguan/Bulanan/Tahunan), `Hemat`/per-day/`Paling hemat` format strings, the paywall hero/title, the Unconfigured ("Premium belum tersedia") copy, the disclosure-footer lines, and any paywall-specific CTA copy (reuse the existing `cta_activate_premium` / `cta_close` where they fit).
- [ ] 3.2 Update `SharedStringsCatalogTest` to include the new keys (the catalog-completeness gate).

## 4. Route + navigation

- [ ] 4.1 Add the `PaywallEntry` enum + the `@Serializable data class PaywallRoute(val entry: PaywallEntry)` to `screens/routing/NavKeys.kt` (no PII payload).
- [ ] 4.2 Register `PaywallRoute` in `screens/routing/AppNavSerialization.kt`'s polymorphic `SerializersModule` via an explicit `subclass(PaywallRoute::class, PaywallRoute.serializer())` (iOS-saveable back stack; keep the load-bearing explicit form).
- [ ] 4.3 Map `PaywallRoute` → `PaywallScreen` in `screens/routing/AppEntryProvider.kt`, wiring `onClose` to pop and `onPurchaseComplete` to pop (natural return; `PendingReturnDestination` NOT reused).

## 5. ViewModel, UiState, price derivation

- [ ] 5.1 Render mockup frame 17 and generate the per-frame measurement annex (`dev/scripts/mockup-measure.sh nearyou-screens-mockup 17`) for exact spacing/typography/token mapping (docs/11 §2.8; on-demand, not committed).
- [ ] 5.2 Implement `PaywallUiState` (Compose-free sealed/data type: LoadingOfferings / Content(packages, selectedPeriod) / PurchaseInProgress / Success / Error / Unconfigured) + a pure `paywallUiState(...)` projection.
- [ ] 5.3 Implement the pure commonMain price-derivation helper: Monthly strike-anchor = 4× Weekly, Yearly strike-anchor = 12× Monthly, savings % from those anchors, Weekly per-day = Weekly ÷ 7, default-selected Monthly, "Paling hemat" on Yearly — all from package prices, no hardcoded values.
- [ ] 5.4 Implement `PaywallViewModel` (androidx ViewModel in commonMain, `koinViewModel()`, one `StateFlow<PaywallUiState>` via `stateIn(WhileSubscribed(5000))`, one-shot return signal as a nullable field cleared via `onReturnShown()`); load offerings on init via `PurchaseController`, drive `purchase(...)` on subscribe (in-progress → Success/Cancelled→Content/Error).

## 6. PaywallScreen (frame 17)

- [ ] 6.1 Implement `screens/paywall/PaywallScreen.kt` per the frame-17 annex: close app-bar action; Premium hero; benefit list (features-available-now, no image upload); the 3 pricing cards (store-localized prices + derived anchors/savings/per-day, default-selected Monthly, "Paling hemat" on Yearly); full-width "Aktifkan Premium" CTA; disclosure footer. Navigation-free (hoisted `onClose` / `onPurchaseComplete`); all text via `stringResource`; `NearYouTheme` tokens only; light + dark. Entry-context tailors the hero headline.
- [ ] 6.2 Implement the Unconfigured + PurchaseInProgress + Error renderings (graceful "Premium belum tersedia"; single progress indicator + disabled CTA; retryable error) — no crash, no fabricated price card.

## 7. Wire the dead-end CTAs

- [ ] 7.1 Wire the `DailyCapUpsellDialog` host(s) (the feed surfaces) so `onActivatePremium` pushes `PaywallRoute(entry = LIKE_CAP)` + dismisses — `mobile-cap-upsell-dialog` MODIFIED requirement. Keep the dialog component navigation-free.
- [ ] 7.2 Wire the `SearchScreen` PremiumGate host (`appEntryProvider` call site) so the "Aktifkan Premium" CTA pushes `PaywallRoute(entry = SEARCH_GATE)` — `mobile-search` MODIFIED requirement. Confirm the `429` rate-limit state is untouched.

## 8. DI

- [ ] 8.1 Bind `PurchaseController` (production `:infra:revenuecat` impl) + register `PaywallViewModel` for the `PaywallRoute` entry in the mobile Koin module(s); add a `FakePurchaseController` in commonTest for the screen/VM seam.

## 9. Tests

- [ ] 9.1 commonTest `PaywallViewModelTest` / `PaywallUiStateTest` over `FakePurchaseController`: LoadingOfferings→Content, purchase success→return signal, user-cancel→Content, purchase-error→retryable error, empty-offerings→Unconfigured.
- [ ] 9.2 commonTest for the pure price-derivation helper (anchors, savings %, per-day, default Monthly) — deterministic, no hardcoded values.
- [ ] 9.3 commonTest for the `PaywallRoute` serialized round-trip (the polymorphic registration decodes `entry` on K/N).
- [ ] 9.4 Robolectric `PaywallScreenTest` (androidUnitTest, v2 ComposeUiTest API): frame-17 surface (hero, benefits-without-image-upload, 3 cards with localized prices, CTA, disclosure footer), close affordance, Unconfigured state, no-hardcoded-strings + token-only assertions. ADD to the `mobile/app/build.gradle.kts` Release-variant test-exclude list; verify `:mobile:app:testDevReleaseUnitTest` passes.
- [ ] 9.5 `iosTest` paywall flow test over `FakePurchaseController` (the per-screen `*FlowIosTest` convention); run `:mobile:app:iosSimulatorArm64Test`.
- [ ] 9.6 Update the cap-dialog host test + the search PremiumGate test to assert the CTA now pushes `PaywallRoute` with the correct entry-context (the MODIFIED scenarios).

## 10. Reconciliation + docs

- [ ] 10.1 Set the disclosure-footer string to the verbatim `docs/01-Business.md` § Pricing & Payment clause "Fitur Premium dapat berubah atau ditambahkan seiring waktu." (confirmed present at docs/01:30; same text as the frame-17 footer).
- [ ] 10.2 Resolve design Open Question D7 (tenure ladder): implement per the review decision (recommended: omit the live ladder from v1).

## 11. Verification + housekeeping

- [ ] 11.1 Run the full local gate: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` + the mobile unit tests `:mobile:app:testStagingDebugUnitTest` (mobile units are local-only; CI mobile = device-run APK build).
- [ ] 11.2 Manual UI verification (docs/11 §5 DoD): build + run the app, open the paywall from BOTH CTAs (cap dialog + search gate), screenshot light + dark; with billing UNprovisioned, verify the graceful Unconfigured state (no crash). Attach evidence to the PR.
- [ ] 11.3 Record the live-purchase manual-verification boundary explicitly: the real RevenueCat Offerings/purchase path is verified MANUALLY after the operator provisions the RevenueCat dashboard + Google Play / App Store products + `staging-revenuecat-*` secret slots (the app-side logic is covered via `FakePurchaseController`). Not a skip-rationalization — an external-provisioning dependency.
- [ ] 11.4 On ship: close GitHub issue [#235](https://github.com/aditrioka/nearyou-id/issues/235) (keystone); confirm [#254](https://github.com/aditrioka/nearyou-id/issues/254) is addressed (route the search CTA) and [#253](https://github.com/aditrioka/nearyou-id/issues/253) remains open as the tracked proactive-upsell follow-up.
- [ ] 11.5 At `/opsx:archive`: verify the `mobile-cap-upsell-dialog` RENAMED FROM-header byte-matched (no silent archive fail) — grep the archived spec for the new "Premium CTA navigates to the paywall" requirement + run `openspec validate mobile-cap-upsell-dialog --type spec --strict`.
