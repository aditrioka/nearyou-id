## 1. Pre-flight

- [ ] 1.1 Confirm worktree gradle can build `:mobile:app` (copy `local.properties` SDK pointer into the worktree if absent).
- [ ] 1.2 Pre-implementation library re-check: **N/A** — this change introduces no new `gradle/libs.versions.toml` pin and activates no previously-unused library (Navigation 3, Koin, Ktor KMP client, Compose Multiplatform Resources are all already pinned + actively used). Drop the one-line "no new substrate; re-check skipped" note in the first feat commit body (per `openspec/project.md` § Pre-implementation library re-check).
- [ ] 1.3 Re-read the SHIPPED Global wire (`backend/ktor/.../timeline/TimelineRoutes.kt` `GlobalPostDto`/`GlobalResponse`) at apply time to confirm field names/casing haven't drifted since proposal (camelCase `id`/`authorUserId`/`content`/`latitude`/`longitude`/`createdAt`; `@SerialName` `city_name`/`liked_by_viewer`/`reply_count`; **no `distanceM`**; bare `nextCursor` + optional `upsell`).

## 2. Strings in :shared:resources

- [ ] 2.1 Add Bahasa Indonesia `Res.string` entries: `tab_nearby`, `tab_following`, `tab_global`, `timeline_global_title` ("*Seluruh Indonesia*"), `timeline_empty_global`, `timeline_following_placeholder` ("*Kamu belum mengikuti siapa pun. Lihat Nearby atau Global dulu.*"), `cta_see_global` ("*Lihat Global*"), plus icon `contentDescription` strings for the three tabs.
- [ ] 2.2 Reuse existing strings where they already fit (`timeline_loading`, `signin_error_network`, `cta_retry`, `cta_post`, `timeline_empty_nearby`, `timeline_limit_hard`, `timeline_limit_soft`) — do NOT duplicate.
- [ ] 2.3 Update `SharedStringsCatalogTest` (commonTest) to cover the new keys; confirm the grep-based "no hardcoded UI strings" guard still passes against the new screens.

## 3. Tab selection model (serializable Tab enum)

- [ ] 3.1 Add a `@Serializable enum class Tab { Nearby, Following, Global }` (e.g. `screens/home/Tab.kt`); the tab host holds the selected `Tab` in `rememberSaveable`. Add NO new tab-root `NavKey`s — per-tab `NavDisplay` back stacks are deferred (design D1).
- [ ] 3.2 commonTest: the selected `Tab` saves + restores via the `rememberSaveable` saver (serializable-enum path, iOS-safe) — `mobile-home-tab-host` § "Selected tab survives a saved-state round-trip".

## 4. Global feed plumbing (mirror Nearby, minus distance/spatial params)

- [ ] 4.1 `timeline/GlobalTimelineApiClient.kt`: define `@Serializable GlobalPostDto` (NO `distanceM`) + `GlobalResponseDto` + reuse the shared `UpsellDto`; `fetchGlobal(cursor)` issues `GET /api/v1/timeline/global` with NO `lat`/`lng`/`radius_m`, optional `cursor`, and the `X-Session-Id` header from the existing `SessionIdProvider`.
- [ ] 4.2 `timeline/GlobalTimelineFlow.kt` (interface + `GlobalTimelineOutcome` sealed type) + `timeline/GlobalTimelineRepository.kt`: HTTP-status-driven mapping (200→`Loaded`; 401→`Auth` plugin; 400→retryable `Error`; 5xx/IO→`NetworkError`), no generic fallthrough.
- [ ] 4.3 `screens/timeline/GlobalTimelineUiState.kt`: pure `GlobalTimelineUiState` + `globalTimelineUiState(outcome, inFlight)` projection (6 states; no PII).
- [ ] 4.4 `screens/timeline/GlobalTimelineViewModel.kt`: `HomeRoute`-scoped VM; loads once on construction; `reload()` re-fetches; failure → `NetworkError`.
- [ ] 4.5 `screens/timeline/GlobalTimelineScreen.kt`: navigation-free composable; title `timeline_global_title`; pull-to-refresh `LazyColumn`; six-state mapping; post card renders `city_name` + `content` + `created_at` + read-only `liked_by_viewer`/`reply_count`, **no distance**, **no `authorUserId`/raw coords**; all copy via `stringResource`.

## 5. Tab host (HomeScreen rework)

- [ ] 5.1 Rework `screens/home/HomeScreen.kt` into a `Scaffold` with a Material 3 `NavigationBar` (Nearby/Following/Global, labels via `stringResource`) + the home-level FAB (`onOpenComposer`, unchanged) + `rememberSaveable` selected tab (default **Nearby**).
- [ ] 5.2 Render the selected tab's screen directly in `HomeScreen`'s body via `when(selectedTab)` (NO per-tab `NavDisplay`), so each feed screen composes under the `HomeRoute` scope and its `viewModel { }` resolves to the `HomeRoute` store (design D1/D2).
- [ ] 5.3 Wire tab content: Nearby → `NearbyTimelineScreen` (with `onSeeGlobal = { select Global tab }`); Global → `GlobalTimelineScreen`; Following → `screens/timeline/FollowingPlaceholderScreen.kt` (renders `timeline_following_placeholder`, issues NO fetch, wires no following API client).
- [ ] 5.4 FAB pushes `PostCreationRoute` onto the **root** back stack (above `HomeRoute`) — verify it is present on every tab and never duplicated / never pushed into a per-tab stack.
- [ ] 5.5 Confirm `AppEntryProvider.kt` maps `HomeRoute` to the tab host; confirm `RootRouterScreen` still routes authenticated → `HomeRoute` (no routing-target edit). No new `entryProvider` entries are needed (no new routes added — tabs are host-internal state, the composer route already exists).

## 6. Nearby tab: empty-state CTA + feed-state scoping

- [ ] 6.1 Add `onSeeGlobal: () -> Unit` (default no-op) to `NearbyTimelineScreen`; render the `cta_see_global` CTA in the empty state, invoking it. Keep `NearbyTimelineScreen` navigation-free (no back-stack reference).
- [ ] 6.2 Confirm `NearbyTimelineViewModel` is resolved under the `HomeRoute` NavEntry (already is) so it survives both the composer round-trip AND tab switches — no re-fetch on tab return.

## 7. Koin wiring

- [ ] 7.1 `di/MobileModule.kt`: register `GlobalTimelineApiClient` + `GlobalTimelineRepository` singletons; bind `single<GlobalTimelineFlow> { get<GlobalTimelineRepository>() }`; reuse the existing `SessionIdProvider` single (do NOT register a second).
- [ ] 7.2 Confirm Koin graph resolves (extend `KoinInitTest` / a `CreatePostFlowKoinResolutionTest`-style check for the Global graph + tab host).

## 8. Tests — commonTest

- [ ] 8.1 `GlobalTimelineUiStateTest`: projection maps each of inFlight / Loaded(non-empty,no upsell) / Loaded(empty,no upsell) / Loaded(empty,hard) / Loaded(non-empty,soft) / NetworkError to its state.
- [ ] 8.2 `GlobalTimelineApiClientTest` (MockEngine): first-page request path `/api/v1/timeline/global` with NO `lat`/`lng`/`radius_m`/`cursor`; `X-Session-Id` present + equals the shared provider's id; full shipped-wire parse (incl. no `distanceM`); snake_case-only negative regression; `upsell`/`nextCursor` absence tolerated.
- [ ] 8.3 `GlobalTimelineRepositoryTest` (MockEngine): 200→Loaded(posts,cursor,upsell); hard-cap 200(empty+hard)→Loaded; 5xx/IO→NetworkError; 400→retryable Error; exactly-one-outcome / no-fallthrough.
- [ ] 8.4 `GlobalTimelineViewModelTest`: loads once on construction; `reload()` re-fetches; throwing flow → NetworkError.
- [ ] 8.5 Tab-host commonTest: selected-`Tab` saved-state round-trip (3.2); no-re-fetch-on-tab-switch invariant via `FakeNearbyTimelineFlow` + `FakeGlobalTimelineFlow` counters (`mobile-home-tab-host` § "Returning to a feed tab does not re-fetch" + `mobile-nearby-timeline` § "Switching tabs and returning to Nearby does not re-fetch").
- [ ] 8.6 Add `FakeGlobalTimelineFlow` test double (mirror `FakeNearbyTimelineFlow`).

## 9. Tests — Robolectric screen tests (Android)

- [ ] 9.1 `GlobalTimelineScreenTest`: initial render (title) + all six visual states via `FakeGlobalTimelineFlow`; PII/no-distance assertions (no `authorUserId`, no raw coords, no distance string); empty `city_name` tolerated. Use `waitUntil` for any MockEngine-backed assertion (Fake flow is synchronous; real network submit isn't awaited by `waitForIdle`).
- [ ] 9.2 Tab-host screen test (`HomeTabHostScreenTest` or extend `HomeScreenFabTest`): three labelled tabs; selecting a tab swaps the body; FAB present on each tab + pushes `PostCreationRoute` to root stack; Following placeholder renders + issues no fetch (MockEngine captures zero `/timeline/following`); Nearby empty-state `cta_see_global` switches to the Global tab.
- [ ] 9.3 Update the existing Nearby/Home screen tests for the tab-host hosting (Nearby tab content + empty-state CTA).
- [ ] 9.4 Add all new `*ScreenTest` globs to the `mobile/app/build.gradle.kts` Release-variant `tasks.withType<Test>()` exclude block (ui-test-manifest host is debug-only); verify `:mobile:app:testDevReleaseUnitTest` passes.

## 10. Tests — iOS flow (iosTest)

- [ ] 10.1 Add an iOS flow test under `mobile/app/src/iosTest/...` (mirror `NearbyTimelineFlowIosTest`) exercising the tab host on the simulator (tab switch + Global feed render); use kotlin.test `@Test`, K/N-legal fn names (no `,()#`).

## 11. Verify (build + lint gate)

- [ ] 11.1 `./gradlew ktlintCheck detekt` (root-level detekt) — green.
- [ ] 11.2 `./gradlew :mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest` — green (flavor-qualified per `reference_mobile_gate_flavor_qualified_tasks`).
- [ ] 11.3 (Optional, recommended) Manual smoke via the verify-loop: run the app, sign in, confirm the three tabs render, Global shows live posts, Following shows the placeholder, the Nearby empty-state CTA jumps to Global, and the FAB opens the composer over the tab bar.
- [ ] 11.4 Staging deploy / smoke: **N/A** — mobile-only change, no backend/runtime/schema impact (mark Section N/A in the archive commit body).

## 12. Docs + follow-ups

- [ ] 12.1 Delete the `mobile-home-tab-host` and `mobile-timeline-empty-global-cta` entries from `FOLLOW_UPS.md` (shipped by this change).
- [ ] 12.2 Add `FOLLOW_UPS.md` entries: `mobile-following-timeline-screen` (deferred real Following feed; MODIFIES `mobile-home-tab-host` § "Following tab renders the deferred placeholder") and `mobile-home-tab-host-per-tab-backstacks` (deferred per-tab `NavDisplay` back stacks, for the first intra-tab destination — MODIFIES `mobile-home-tab-host` § "Tab selection is serializable and survives process death"); extend the existing `mobile-nearby-timeline-infinite-scroll` entry to note Global also defers load-more.
- [ ] 12.3 No new module added → no `dev/module-descriptions.txt` / README sync needed. Confirm.
