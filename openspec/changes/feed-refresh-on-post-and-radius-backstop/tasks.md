## 1. One radius_premium_only mapping (#518)

- [ ] 1.1 `timeline/NearbyTimelineFlow.kt`: rename `RadiusChangeResult` → `NearbyFetchResult` (`Loaded(outcome)` | `PremiumGated`), have `loadFirstPage` and `loadMore` return it, and delete `changeRadius`. `NearbyTimelineOutcome` stays unchanged.
- [ ] 1.2 `timeline/NearbyTimelineRepository.kt`: wrap the shared `toOutcome` mapping in a private `toResult` that maps `HttpError(403, "radius_premium_only")` → `PremiumGated` before any other branch. Use it from `loadFirstPage` + `loadMore`, delete `changeRadius`, and wrap the position-unavailable early return in `Loaded(NetworkError)`.
- [ ] 1.3 `screens/timeline/NearbyTimelineViewModel.kt`: route every page-1 fetch through one private `fetchFirstPage()` (`PremiumGated` → `applyPremiumGate()` + one non-recursive 20 km re-fetch, where a gated 20 km → `NetworkError`). Make `selectRadius` set the radius + `reload()` (delete `changeRadiusAndReload`). The load-more `fetchPage` maps `PremiumGated` → `applyPremiumGate()` + `reload()` (stale page dropped by the controller generation).
- [ ] 1.4 `FakeNearbyTimelineFlow`: replace `changeRadiusResult` / `changeRadiusCalls` with a `gatedRadii` set that page-1 + load-more both honour. Keep the constructor unchanged for the screen + iOS tests.
- [ ] 1.5 Update the KDoc that still names `changeRadius` / `RadiusChangeResult`, including the `NEARBY_RADIUS_M` and repository class docs.

## 2. Feed refresh on a successful post (#173)

- [ ] 2.1 `screens/routing/AppEntryProvider.kt`: add a defaulted `postCreated: MutableState<Boolean>` parameter, and make `onPostCreated` set it before `removeLastOrNull()`. In `entry<HomeRoute>`, hold a `rememberSaveable` counter, consume the flag in a `LaunchedEffect` (clear + increment), and pass `feedReloadKey` to `AppShellScreen`. Update the KDoc map. Do not touch `NavKeys.kt`, `App.kt` or `PostCreationScreen.kt`.
- [ ] 2.2 Thread `feedReloadKey: Int = 0` through `AppShellScreen` → `HomeScreen` → `NearbyTimelineScreen` (→ `NearbyFeed`) and `GlobalTimelineScreen`. Following receives no key.
- [ ] 2.3 `NearbyTimelineViewModel` + `GlobalTimelineViewModel`: add `onFeedReloadKey(key)` (the first observation is recorded without a fetch; a changed key → `reload()`). The feed composables call it from `LaunchedEffect(feedReloadKey)`.
- [ ] 2.4 Update the KDoc that says returning from the composer never re-fetches (Nearby/Global VM + screen docs, `App.kt` is untouched) to name the successful-post exception.

## 3. Tests

- [ ] 3.1 `NearbyTimelineRepositoryTest`: `radius_premium_only` 403 → `PremiumGated` from `loadFirstPage` AND `loadMore`. Another 403 code → `Loaded(NetworkError)`. Adapt the existing outcome assertions to `Loaded(...)`.
- [ ] 3.2 `NearbyTimelineViewModelTest`, the radius backstop on every path:
  - error-state retry at 50 km → 403 → upsell + 20 km + 20 km re-fetch, outcome not the 403-as-NetworkError;
  - pull-to-refresh at 50 km → same;
  - radius selection → same (existing test, adapted);
  - gated load-more → upsell + 20 km + page-1 reload at 20 km + no load-more error footer;
  - a gated 20 km re-fetch → `NetworkError`, no loop.
  
  Adapt the existing `changeRadius*` assertions to `loadFirstPageRadii`.
- [ ] 3.3 `NearbyTimelineViewModelTest` + `GlobalTimelineViewModelTest`: the first observed key does not fetch; the same key does not fetch; a changed key fetches once with the prior outcome retained.
- [ ] 3.4 `HomeScreenFabTest` (real `appEntryProvider` via `TestNavHost`):
  - composer → successful post → popped back to Home + Nearby fetch count 2;
  - composer → back without posting → count 1 (existing test kept).
- [ ] 3.5 `HomeTabHostScreenTest`: with the key bumped while Nearby is on screen, Nearby re-fetches, Following stays at 1 after a swipe, and Global re-fetches once its page is shown.
- [ ] 3.6 `PostCreationSourceGuardTest`: keep the composer feed-agnostic guard (no timeline type / `reload` / `ResultEventBus` in `PostCreationScreen.kt`) under a renamed test. Add the hoisting guard: `AppEntryProvider.kt` holds the key with `rememberSaveable` and raises the signal before popping; no `Channel` / `SharedFlow` is introduced for it.

## 4. Gates + verification

- [ ] 4.1 `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test`, with the backend DB suite against a throwaway PostGIS container if `:5433` is dirty (`docs/13` §5).
- [ ] 4.2 `./gradlew :mobile:app:ktlintCheck :mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest` (named explicitly).
- [ ] 4.3 `./gradlew :mobile:app:iosSimulatorArm64Test`, because commonMain changed. The known pre-existing `AppShellFlowIosTest` red (#348) is confirmed against a main-baseline worktree, not attributed to this change.
- [ ] 4.4 Manual UI verification (docs/11 §5 DoD) on the `verify36` emulator, with screenshots in the PR body:
  - (a) post from the composer → the new post is at the top of Nearby on return with no pull-to-refresh;
  - (b) the Global tab shows it on the next swipe;
  - (c) against a stub backend, a 50 km retry / pull-to-refresh that gets `403 radius_premium_only` shows the radius upsell and the control returns to 20 km.
