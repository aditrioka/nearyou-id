## 1. One radius_premium_only mapping (#518)

- [x] 1.1 `timeline/NearbyTimelineFlow.kt`: rename `RadiusChangeResult` → `NearbyFetchResult` (`Loaded(outcome)` | `PremiumGated`), have `loadFirstPage` and `loadMore` return it, and delete `changeRadius`. `NearbyTimelineOutcome` stays unchanged.
- [x] 1.2 `timeline/NearbyTimelineRepository.kt`: wrap the shared `toOutcome` mapping in a private `toResult` that maps `HttpError(403, "radius_premium_only")` → `PremiumGated` before any other branch. Use it from `loadFirstPage` + `loadMore`, delete `changeRadius`, and wrap the position-unavailable early return in `Loaded(NetworkError)`.
- [x] 1.3 `screens/timeline/NearbyTimelineViewModel.kt`: route every page-1 fetch through one private `fetchFirstPage()` (`PremiumGated` → `applyPremiumGate()` + one non-recursive 20 km re-fetch, where a gated 20 km → `NetworkError`). Make `selectRadius` set the radius + `reload()` (delete `changeRadiusAndReload`). The load-more `fetchPage` maps `PremiumGated` → `applyPremiumGate()` + `reload()` (stale page dropped by the controller generation).
- [x] 1.4 `FakeNearbyTimelineFlow`: replace `changeRadiusResult` / `changeRadiusCalls` with a `gatedRadii` set that page-1 + load-more both honour. Keep the constructor unchanged for the screen + iOS tests.
- [x] 1.5 Update the KDoc that still names `changeRadius` / `RadiusChangeResult`, including the `NEARBY_RADIUS_M` and repository class docs.
- [x] 1.6 D4 (review round 1): a gated fetch already at 20 km (original or re-fetch) → `NetworkError` with no upsell and no further fetch. Pin, in a comment, the invariant the load-more backstop relies on: every refresh goes through `reload()` → `reset()`.

## 2. Feed refresh on a successful post (#173)

- [x] 2.1 `screens/routing/AppEntryProvider.kt`: add a defaulted `postCreated: MutableState<Boolean>` parameter, and make `onPostCreated` set it before `removeLastOrNull()`. In `entry<HomeRoute>`, hold a `rememberSaveable` counter, consume the flag in a `LaunchedEffect` (clear + increment), and pass `feedReloadKey` to `AppShellScreen`. Update the KDoc map. Do not touch `NavKeys.kt`, `App.kt` or `PostCreationScreen.kt`.
- [x] 2.2 Thread `feedReloadKey: Int = 0` through `AppShellScreen` → `HomeScreen` → `NearbyTimelineScreen` (→ `NearbyFeed`) and `GlobalTimelineScreen`. Following receives no key.
- [x] 2.3 `NearbyTimelineViewModel` + `GlobalTimelineViewModel`: add `onFeedReloadKey(key)` (the first observation is recorded without a fetch; a changed key → `reload()`). The feed composables call it from `LaunchedEffect(feedReloadKey)`.
- [x] 2.4 Update the KDoc that says returning from the composer never re-fetches (Nearby/Global VM + screen docs, `App.kt` is untouched) to name the successful-post exception.

## 3. Tests

- [x] 3.1 `NearbyTimelineRepositoryTest`: `radius_premium_only` 403 → `PremiumGated` from `loadFirstPage` AND `loadMore`. Another 403 code → `Loaded(NetworkError)`. Adapt the existing outcome assertions to `Loaded(...)`.
- [x] 3.2 `NearbyTimelineViewModelTest`, the radius backstop on every path:
  - error-state retry at 50 km → 403 → upsell + 20 km + 20 km re-fetch, outcome not the 403-as-NetworkError;
  - pull-to-refresh at 50 km → same;
  - radius selection → same (existing test, adapted);
  - gated load-more → upsell + 20 km + page-1 reload at 20 km + no load-more error footer;
  - a gated 20 km re-fetch → `NetworkError`, no loop.
  
  Adapt the existing `changeRadius*` assertions to `loadFirstPageRadii`.
- [x] 3.3 `NearbyTimelineViewModelTest` + `GlobalTimelineViewModelTest`: the first observed key does not fetch; the same key does not fetch; a changed key fetches once with the prior outcome retained.
- [x] 3.4 `HomeScreenFabTest` (real `appEntryProvider` via `TestNavHost`):
  - composer → successful post → popped back to Home + Nearby fetch count 2;
  - composer → back without posting → count 1 (existing test kept).
- [x] 3.5 `HomeTabHostScreenTest`: with the key bumped while Nearby is on screen, Nearby re-fetches, Following stays at 1 after a swipe, and Global re-fetches once its page is shown.
- [x] 3.6 `PostCreationSourceGuardTest`: keep the composer feed-agnostic guard (no timeline type / `reload` / `ResultEventBus` in `PostCreationScreen.kt`) under a renamed test. Add the hoisting guard: `AppEntryProvider.kt` holds the key with `rememberSaveable` and raises the signal before popping; no `Channel` / `SharedFlow` / `ResultEventBus` appears in it or in the Nearby/Global feed ViewModels.
- [x] 3.7 `NearbyTimelineViewModelTest`: a gated 20 km refresh → `NetworkError`, no upsell, no re-fetch. The gated load-more test also asserts nothing was appended.

- [x] 3.8 D5 (found on device): `PostFeedList` pins a viewer at the top to the top when posts are prepended (`requestScrollToItem(0)` in a `SideEffect`). New Robolectric `PostFeedListTest` (at top → the prepended post is fully visible, checked with the unclipped `positionInRoot`, mutation-checked; scrolled down → position kept), added to the Release-variant exclude block.

- [x] 3.9 Review round 2 (code-correctness + test-coverage lenses):
  - B1/F2: a gated 20 km load-more → the plain retry footer, no upsell/reload.
  - F1: a stale gated load-more applies nothing.
  - F3: a key change during an in-flight refresh re-fetches once more when that refresh lands. `feedReloadKey` is now declared before `init`.
  - S1: the key tests start at a non-zero key, plus a Robolectric case where Global is first shown after a post and loads once.
  - S2: a regex raise-then-pop guard.
  - S3: Global is not read eagerly.
  - S4: a distinct 20 km page.
  - S5: card-based pin assert + an offset > 0 no-jump case.
  - S7: a repository position-failure test.
  
  The F1/F3/S1/S5 guards are mutation-checked (each mutant caught).
- [x] 3.10 Review round 2 (general/standards lens):
  - Stale composer, `App.kt` and Nearby screen comments fixed.
  - The radius-slider load-more bullet now cross-references the `mobile-design-system` load-more footer.
  - docs/11 §2.3 registers the refresh-after-overlay boundary.
  - design.md explains why `NearbyFetchResult` wraps the frozen outcome.
  - The proposal Impact list is complete.
  - Declined, with reasons recorded in the PR body: a block-body local instead of the defaulted `postCreated` param (it would re-indent all of `appEntryProvider` and conflict with the sibling nav work); a `reloadPending` flag instead of `keyAtStart` (it spends a wasted read on the process-death-with-composer-open path; the remaining first-load gap is documented).

## 4. Gates + verification

- [x] 4.1 `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test`, with the backend DB suite against a throwaway PostGIS container if `:5433` is dirty (`docs/13` §5).
- [x] 4.2 `./gradlew :mobile:app:ktlintCheck :mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest` (named explicitly).
- [x] 4.3 `./gradlew :mobile:app:iosSimulatorArm64Test`, because commonMain changed. Result: 1246 tests, 38 red. Every red one matches the documented pre-existing #348 signature on `main`: 35 Koin `NoDefinitionFoundException` (older iOS fixture graphs missing `LikeFlow` / `ProfileFlow` / `PostEditFlow` / `ConsentSnapshotStore` / `FcmTokenRegistrar` / `PrivateProfileRepository`; this change adds no Koin injection) plus the 3 SignIn/Search `assertExists` failures. No new failure signature. The K/N compile of all changed commonMain + commonTest code succeeded.
- [x] 4.4 Manual UI verification (docs/11 §5 DoD) on the `verify36` emulator, with screenshots in the PR body:
  - (a) post from the composer → the new post is at the top of Nearby on return with no pull-to-refresh;
  - (b) the Global tab shows it on the next swipe;
  - (c) against a stub backend, a 50 km retry / pull-to-refresh that gets `403 radius_premium_only` shows the radius upsell and the control returns to 20 km.

  **Done 2026-10-04.** Verified on a dedicated `verify36`-image AVD against a stateful Python stub on a private host port (`-PdevApiBaseUrl` + `adb reverse`, so sibling sessions were not disturbed). Frames are on the orphan branch `evidence/feed-refresh-on-post-and-radius-backstop`.
  - (a) Post → back on Nearby with the new post at the top: one POST plus one Nearby GET. The first run exposed the D5 hidden-post bug, which is now fixed.
  - (b) Global visited, then a post from Nearby → Global re-fetched once when shown, with the new post at the top. Following was not re-fetched.
  - Rotation after the posts: the Activity relaunched with 0 new requests (D2).
  - (c) Error state at 50 km → `403 radius_premium_only` on "Coba lagi" → upsell + 20 km + 20 km re-fetch.
  - Loaded at 50 km → 403 on pull-to-refresh → the same backstop.

  **iOS simulator, also done** (docs/11 §5.3, local session). A dedicated iPhone 17 / iOS 26.3 simulator ran the `iosApp (Dev)` build with `APP_API_BASE_URL` pointed at the same stub. A temporary `App.kt` token/HomeRoute harness was used, never committed, and `git restore`d before any further step.
  - Post → the new post at the top of Nearby, with one POST and one Nearby GET. The D5 pin also works on iOS.
  - Loaded at 50 km → `403 radius_premium_only` on pull-to-refresh → upsell + 20 km + a 20 km re-fetch.
  - The `ios-*` frames are on the same evidence branch.

  **Gate results, HEAD 4bea139d.**
  - Root `ktlintCheck` + `detekt`: green.
  - `:backend:ktor:test` 2641/0 (forced rerun on a throwaway PostGIS; the backend inputs are unchanged since).
  - `:lint:detekt-rules:test` 344/0.
  - `:mobile:app:ktlintCheck`: green.
  - `testDevDebugUnitTest` 1714/0 and `testDevReleaseUnitTest` 1283/0 (forced `--rerun`).
