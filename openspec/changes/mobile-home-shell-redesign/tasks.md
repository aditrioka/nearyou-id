## 1. Apply-kickoff re-checks (do FIRST, before coding)

- [ ] 1.1 Dated pre-implementation library re-check (OQ1): WebSearch "Compose Multiplatform material icons 2026 core vs extended" + confirm bundled-vector-drawables vs `material-icons-core`; record the verdict + date in `design.md` Decision D4. Default: bundled drawables.
- [ ] 1.2 Dated M3 API re-check against the operator-supplied references (m3.material.io + developer.android.com/develop/ui/compose/*): confirm current `PullToRefreshBox` `isRefreshing` semantics, `HorizontalPager`↔`TabRow` sync pattern, edge-to-edge `WindowInsets`/`consumeWindowInsets` ownership, `NavigationBarItemDefaults` colors, and `rememberPagerState` saved-state behavior on Kotlin/Native. Log any divergence in `design.md` § Open Questions before coding.
- [ ] 1.3 Confirm OQ2 tab wording (`Sekitar` / `Mengikuti` / `Global`) and OQ3 (operator inspiration screenshots) with the user; if screenshots are still pending, proceed with the structural layer and gate the aesthetic-tuning tasks (§9) on their arrival.
- [ ] 1.4 Copy `local.properties` SDK pointer into the worktree if absent (mobile gate needs it).

## 2. Assets + strings (:shared:resources)

- [ ] 2.1 Bundle the Material icon vector drawables under `shared/resources/src/commonMain/composeResources/drawable/` (bottom-nav Home/Notifications/Profile; feed-tab Nearby/Following/Global; composer action; filled+outlined where the M3 selected/unselected convention needs both), with Material Symbols (Apache-2.0) provenance recorded. (shared-resources § "Material icon vector drawables …")
- [ ] 2.2 Change the tab string values to Bahasa Indonesia in `strings.xml`: `tab_nearby`="Sekitar", `tab_following`="Mengikuti", `tab_global`="Global"; leave `section_*` unchanged; retain `timeline_nearby_title` + `timeline_global_title` (now unreferenced as headers). (shared-resources § "Home-section feed tab labels …")
- [ ] 2.3 If the re-check (1.1) adopted a `material-icons-core` dependency instead, add exactly that one `gradle/libs.versions.toml` entry — never `material-icons-extended`; otherwise add no dependency.
- [ ] 2.4 Extend `SharedStringsCatalogTest` (or equivalent) for the changed tab values + any new drawable accessors; update declared-count assertions if applicable.

## 3. App-shell single Scaffold + edge-to-edge insets (mobile-design-system D1)

- [ ] 3.1 Make `AppShellScreen`'s `Scaffold` the single inset-owning Scaffold (edge-to-edge via the existing `enableEdgeToEdge()` + `contentWindowInsets`); pass `innerPadding` into the section body and apply `Modifier.consumeWindowInsets(innerPadding)`.
- [ ] 3.2 Remove `HomeScreen`'s own `Scaffold` (render tab row + pager body inset-free under shell padding).
- [ ] 3.3 Remove the inner `Scaffold` + `TopAppBar` from `NearbyTimelineContent` and `GlobalTimelineContent` (render inset-free).
- [ ] 3.4 Verify the status-bar gap is gone and feed lists fill the space between tab row and bottom nav (design-system § inset scenarios).

## 4. Real Material icons + visible labels (mobile-design-system D4/D5)

- [ ] 4.1 Replace the bottom-nav `SectionItem` brand-dot with the bundled Material icon drawables (Home/Notifications/Person) via `painterResource`; preserve the Notifikasi unread badge.
- [ ] 4.2 Replace the feed-tab `HomeFeedTab` brand-dot with the bundled tab icons (Nearby/Following/Global).
- [ ] 4.3 Use `NavigationBarItemDefaults.colors()` / default `Tab` content color so selected labels are visible (fixes the invisible-selected-label bug). Verify against the label-visibility scenarios.

## 5. Swipeable feed pager (mobile-home-tab-host § "Feed tabs are swipeable …" / D2)

- [ ] 5.1 Wrap the three feed bodies in a `HorizontalPager` (page order = Nearby/Following/Global) under the `PrimaryTabRow`.
- [ ] 5.2 Bidirectional sync: `selectedTabIndex = pagerState.currentPage`; tab tap → `LaunchedEffect` `animateScrollToPage`; settled swipe → write back the serializable `Tab`. Keep the `@Serializable Tab` in `rememberSaveable` as the durable selection.
- [ ] 5.3 Verify all three pages compose directly under `HomeRoute` (no per-tab `NavDisplay`, no new `NavKey`) and swipe does NOT re-fetch (HomeRoute-scoped VMs survive).

## 6. Icon-only composer FAB (mobile-home-tab-host + mobile-post-creation / D6-FAB)

- [ ] 6.1 Replace the `ExtendedFloatingActionButton { Text(cta_post) }` with an icon-only `FloatingActionButton` (Material add/compose icon, `contentDescription = stringResource(cta_post)`), rendered in the Home section's inset-free body (or shell FAB slot gated on the Home section), not in a nested Scaffold.
- [ ] 6.2 Verify the FAB shows only on the Home section (absent on Notifikasi/Profil) and still pushes `PostCreationRoute` to the root stack via `onOpenComposer`.

## 7. Timeline loading/refresh split + header removal (mobile-nearby-timeline + mobile-global-timeline / D3)

- [ ] 7.1 `NearbyTimelineViewModel` + `GlobalTimelineViewModel`: replace the single `inFlight` with `isInitialLoad` + `isRefreshing`; on `reload()` keep the prior outcome + set `isRefreshing=true`, swap + clear on completion.
- [ ] 7.2 Update the pure projections to `…UiState(outcome, isInitialLoad)` (initial → Loading; retained Loaded during refresh → Content). Pass `isRefreshing` separately to `PullToRefreshBox`.
- [ ] 7.3 Remove the redundant `timeline_nearby_title` / `timeline_global_title` header rendering; the screens render inset-free, lists `fillMaxSize`.
- [ ] 7.4 Ensure the content list stays mounted during refresh (do not collapse to the loading skeleton) so the pull-to-refresh gesture target persists — fixes the broken pull-to-refresh + double indicator.

## 8. Docs amendments + FOLLOW_UPS

- [ ] 8.1 Amend `docs/02-Product.md` + `docs/03-UX-Design.md` § UX Copy Strategy: the "Post dari lokasi ini" disambiguation moves from a redundant screen header to the one-time onboarding hint + per-card "Diposting dari {city}" context; `timeline_nearby_title` retained in the catalog, no longer a header.
- [ ] 8.2 Add a "Material 3 Design System / Foundation" section to `docs/03-UX-Design.md` codifying the substrate (single-Scaffold inset ownership, the Material icon set per destination, the canonical loading/refresh pattern, label visibility, single-language Bahasa Indonesia) so future screen changes cite it as canonical.
- [ ] 8.3 Add/refresh `FOLLOW_UPS.md` entries: `mobile-localization-language-switching` (runtime i18n deferred), `mobile-location-disambiguation-onboarding-hint` (implement the relocated disambiguation hint), and extend `mobile-nearby-timeline-infinite-scroll` to cover Global.

## 9. Aesthetic refinement (gated on operator screenshots — OQ3)

- [ ] 9.1 Once screenshots are provided: tune card spacing/elevation/shape, list content padding, the FAB icon choice, and color refinement to match the inspiration, staying within `NearYouTheme` tokens. (If screenshots remain unavailable, ship the structural layer and file a follow-up for the aesthetic pass.)

## 10. Tests + verification

- [ ] 10.1 Update/add Robolectric `AppShellScreenTest` / `HomeScreenTest`: Material icons present (not dots), selected label visible, swipe changes tab + no re-fetch, icon-only FAB present on Home only. Add new `*ScreenTest` globs to the `mobile/app/build.gradle.kts` Release-variant exclude list.
- [ ] 10.2 Update `NearbyTimelineScreenTest` / `GlobalTimelineScreenTest`: no header node, list fills, initial-load skeleton (one indicator, PTR `isRefreshing=false`), refresh keeps content + PTR `isRefreshing=true`. Use `waitUntil` polling for real-flow async (waitForIdle won't await MockEngine submit).
- [ ] 10.3 commonTest projection tests (`NearbyTimelineUiStateTest` / `GlobalTimelineUiStateTest`): `isInitialLoad` → Loading; retained Loaded + `isInitialLoad=false` → Content; VM reload toggles `isRefreshing` not `isInitialLoad` and retains the outcome. Add a serializable-`Tab` round-trip test (iOS-safe).
- [ ] 10.4 iOS flow test under `mobile/app/src/iosTest/...` (K/N-legal names): shell + Home tabs + swipe on the simulator; run `:mobile:app:iosSimulatorArm64Test`. Verify `rememberPagerState` survives process death on K/Native.
- [ ] 10.5 Any source-scan guard test strips comments first (so KDoc doesn't trip the no-hardcoded-string / no-Scaffold-in-screen scans).
- [ ] 10.6 Run the mobile gate: `./gradlew ktlintCheck detekt :mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest` (+ `:backend:ktor:test :lint:detekt-rules:test` per the pre-push gate). Then run `dev/scripts/sync-readme.sh --check` (no new module expected, but confirm no drift).
- [ ] 10.7 Manual verification on Android emulator + iOS simulator (verify-loop): status-bar flush, list fills, swipe between feeds, icon-only FAB, single indicator on refresh, working pull-to-refresh, visible selected nav label, all-Indonesian labels.
