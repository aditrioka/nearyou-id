# Tasks: mobile-following-timeline-screen

> Mobile-only change. NO backend work, NO Flyway migration, NO new library pin (read-only consumer of the shipped `GET /api/v1/timeline/following`) → the pre-implementation library re-check (`openspec/project.md` § Change Delivery Workflow) is **N/A**. Mirrors the shipped Global timeline seam (`openspec/specs/mobile-global-timeline/spec.md`) minus the spatial filter, plus the Following-specific directive empty state.

## 1. Mobile — data seam (mirror the Global trio)

- [x] 1.1 Create `timeline/FollowingTimelineApiClient.kt` — `GET /api/v1/timeline/following`, NO `lat`/`lng`/`radius_m`, omit `cursor` on first page, `X-Session-Id` from the SHARED `SessionIdProvider` singleton, Bearer via the shipped `Auth` plugin. Colocate the `@Serializable` `FollowingPostDto` / `FollowingResponse` / `UpsellDto` DTOs from the SHIPPED `TimelineRoutes.kt` Following wire (bare camelCase `id`/`authorUserId`/`authorUsername`/`authorDisplayName`/`content`/`latitude`/`longitude`/`createdAt`; `@SerialName` snake `city_name`/`liked_by_viewer`/`reply_count`; top-level `posts` + bare `nextCursor` + `upsell`; **no `distanceM`**)
- [x] 1.2 Create `timeline/FollowingTimelineRepository.kt` + sealed `FollowingTimelineOutcome` (`Loaded(posts, nextCursor, upsell)` / `SessionExpired` / `Error` / `NetworkError`) — HTTP-status-driven mapping (200→Loaded; terminal 401→SessionExpired never NetworkError; 400 invalid_cursor→retryable Error + a **coord-safe diagnostic** (status + exception-type only — never `cause.message`, a response-body field, or a coordinate; Following's coords are in the body, so mirror the shipped `GlobalTimelineRepository` diagnostic); 5xx/IO→NetworkError; any other non-2xx→defined NetworkError fallback). No generic "load failed" copy; do not reimplement 401 refresh/retry. Map DTOs → the domain post model (no PII: no UUID, no raw coords surfaced to the card)
- [x] 1.3 Create `timeline/FollowingTimelineFlow.kt` interface (the testable seam `FakeFollowingTimelineFlow` implements), mirroring `GlobalTimelineFlow`

## 2. Mobile — state projection + ViewModel

- [x] 2.1 Create `screens/timeline/FollowingTimelineUiState.kt` — Compose-free `FollowingTimelineUiState` (data class / sealed type) + the pure projection `followingTimelineUiState(outcome, isInitialLoad)`: `isInitialLoad=true`→Loading; else Loaded(non-empty,no upsell)→Content, Loaded(empty,no upsell)→**Empty (directive)**, Loaded(empty,upsell.hard)→RateLimitHard, Loaded(non-empty,upsell.soft)→Content+softBanner, SessionExpired→Redirect, NetworkError/Error→Error. No PII in the projection
- [x] 2.2 Create `screens/timeline/FollowingTimelineViewModel.kt` — `HomeRoute`-scoped (resolved via `viewModel { }`), `loadFirstPage()` once on construction, `reload()` on pull-to-refresh/retry, distinct `isInitialLoad` + `isRefreshing` flags (retain prior outcome during refresh), delegate the inline-like lifecycle to the SHARED `InlineLikeController` (no per-feed copy), expose the one-shot cap-dialog state cleared via `onLikeCapDialogDismissed()`

## 3. Mobile — screen (replace the placeholder)

- [x] 3.1 Create `screens/timeline/FollowingTimelineScreen.kt` — inset-free (no `Scaffold`/`TopAppBar`), pull-to-refresh `LazyColumn` filling the space between tab row and bottom nav, rendering the shared `mobile-post-card` (`distanceM = null`); six states via `stringResource` per the canonical loading/refresh pattern; hoisted `onOpenPost` / `onOpenPostReply` / `onSwitchToGlobal` lambdas; navigation-free; `NearYouTheme` light/dark; no hardcoded UI strings
- [x] 3.2 Implement the **directive Empty state**: `timeline_following_placeholder` copy + a `cta_see_global` ("Lihat Global") control invoking `onSwitchToGlobal`, rendered inside a scrollable (so pull-to-refresh works from it) — NOT the loading-skeleton copy. Hard-cap state uses `timeline_limit_hard` (distinct); soft uses the `timeline_limit_soft` banner over the list
- [x] 3.3 Wire the inline like affordance + reply shortcut through the ViewModel's shared-controller delegation; render `mobile-cap-upsell-dialog` with `post_detail_likes_cap_upsell` + countdown while the cap state is set; SessionExpired → `timeline_session_redirect` (no retry); NetworkError/Error → `signin_error_network` + `cta_retry`
- [x] 3.4 DELETE `screens/timeline/FollowingPlaceholderScreen.kt`

## 4. Mobile — Koin wiring

- [x] 4.1 In `di/MobileModule.kt`: register `single { FollowingTimelineApiClient(...) }` + `single { FollowingTimelineRepository(...) }` + bind `single<FollowingTimelineFlow> { get<FollowingTimelineRepository>() }`; resolve the EXISTING `SessionIdProvider` single (no second registration)

## 5. Mobile — home-tab-host integration

- [x] 5.1 Repoint the Following pager page (page 1) in `screens/home/HomeScreen.kt` from `FollowingPlaceholderScreen` to `FollowingTimelineScreen`, hoisting `onOpenPost` / `onOpenPostReply` into it (identical to Nearby/Global) and wiring `onSwitchToGlobal = { scope.launch { pagerState.animateScrollToPage(<Global index>) } }`
- [x] 5.2 In `screens/routing/AppEntryProvider.kt` / `screens/shell/AppShellScreen.kt`: forward `onOpenPost` / `onOpenPostReply` to the Following page exactly as for Nearby/Global (Following cards carry `distanceM = null`); no new `NavKey`, no per-tab `NavDisplay`

## 6. Tests

- [x] 6.1 commonTest `FollowingTimelineUiStateTest` — the pure projection across all six states incl. the Following-specific directive empty state (deterministic, no wall-clock/platform dependency)
- [x] 6.2 commonTest `FollowingTimelineViewModelTest` — load-once-on-construction, reload toggles `isRefreshing` not `isInitialLoad` + retains outcome, load failure → NetworkError, inline-like delegation to the shared controller (optimistic flip / RateLimited revert + cap state / PostGone revert + reload / NetworkError silent revert)
- [x] 6.3 MockEngine `FollowingTimelineApiClient` / `FollowingTimelineRepository` tests — endpoint path with no spatial params + no first-page cursor, shipped mixed-case + distance-less wire parse, snake_case-only negative-regression guard, "no `distanceM`" assertion, reused `X-Session-Id` (equals the shared provider), `upsell` parse, full status→outcome mapping (incl. terminal-401→SessionExpired)
- [x] 6.4 Robolectric `FollowingTimelineScreenTest` (androidUnitTest) — initial render + each of the six states via `FakeFollowingTimelineFlow` (incl. directive empty + "Lihat Global" CTA firing `onSwitchToGlobal`; no-UUID/no-coords assertion; SessionExpired redirect ≠ connectivity copy); ADD `**/FollowingTimelineScreenTest*` to the `mobile/app/build.gradle.kts` Release-variant exclude
- [x] 6.5 iosTest Following-feed flow test mirroring `NearbyTimelineFlowIosTest` (K/N-legal names), exercising the load path on the simulator
- [x] 6.6 Update the home-tab-host tests: REMOVE `FollowingTabNoFetchScanTest`; extend the no-re-fetch commonTest to include `FakeFollowingTimelineFlow` (Following fetch count stays 1 across swipe/section/composer round-trips); extend the Robolectric shell/host test so the Following page asserts the live feed (renders `FollowingTimelineScreen`, issues its fetch) instead of the placeholder; add the "Lihat Global" CTA → pager-to-Global scenario
- [x] 6.7 `FollowingTimelineKoinResolutionTest` (parity with the shipped `GlobalTimelineKoinResolutionTest`) — `mobileModule` resolves `FollowingTimelineApiClient` / `FollowingTimelineRepository` / `FollowingTimelineFlow` at runtime, the flow binding returns the same instance as the repository, and the resolved `SessionIdProvider` is the same singleton the Global graph resolves

## 7. Verification gates

- [x] 7.1 Mobile gate: `./gradlew :mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest` green (new `FollowingTimelineScreenTest` in the Release exclude) + `:mobile:app:iosSimulatorArm64Test` for the commonTest + iosTest additions
- [x] 7.2 Lint gate: `./gradlew ktlintCheck detekt :lint:detekt-rules:test` green (no hardcoded-UI-string violations; no vendor-SDK-leak)
- [ ] 7.3 Manual verification per docs/11 §5 DoD #3 (`verify-loop` / `mobile-ui-foundation`): cloud sandbox routes to Firebase Test Lab via `scripts/run_on_device.sh` — screenshot the Following tab in its directive empty state (follows nobody) and, where a follow can be seeded, the loaded feed; light + dark; verify against `nearyou-screens-mockup.html` frame 1 (Beranda feed) — attach evidence to the PR body BEFORE archive
- [x] 7.4 No staging deploy/smoke needed (no backend/runtime change) — mark Section 7.3 evidence as the UI DoD; note "no backend change" in the archive commit body
- [ ] 7.5 At archive: reconcile the stale forward-reference in `openspec/specs/following-timeline/spec.md` (≈lines 43, 88–89) that still describes the mobile Following surface as a "deferred placeholder … the future `mobile-following-timeline-screen` change consumes them" — now shipped. Update the prose to past-tense/shipped (a `following-timeline` prose touch-up, not a behavioral delta), OR file a `follow-up` issue if it reads as a separate doc-reconciliation task at that time (canonical-docs reconciliation per CLAUDE.md)
