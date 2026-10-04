## 1. Shared by-id post resolution (design D8)

- [ ] 1.1 Move `PostTargetResolution` from `notifications/NotificationsFlow.kt` to a new `post/PostTargetResolution.kt` (same shape, KDoc kept) + add the shared `SinglePostFullResult.toPostTargetResolution()` mapping there
- [ ] 1.2 `NotificationsRepository.resolvePostTarget` calls the shared mapping (its type-only `Unavailable` diagnostic kept); `NotificationsFlow` + notification fakes/tests update imports only
- [ ] 1.3 Move `Resolved → PostDetailTarget` out of `NotificationNavTargetResolver` (private) into a public `PostTargetResolution.Resolved.toPostDetailTarget()` next to `PostDetailTarget` in `screens/home/HomeScreen.kt`; the notification resolver calls it

## 2. Search data seam (#255, design D6/D7)

- [ ] 2.1 `SearchFlow` gains `suspend fun resolvePost(postId: String): PostTargetResolution`
- [ ] 2.2 `SearchRepository` takes `SinglePostApiClient` and implements `resolvePost` via `fetchFullPost` + the shared mapping (type-only diagnostic on `Unavailable`); `MobileModule` passes the shared `SinglePostApiClient` single
- [ ] 2.3 `FakeSearchFlow` gains a programmable `resolvePost` (outcome per id, optional suspend gate, recorded ids)

## 3. SearchViewModel (#253 + #255, design D1–D7, D9)

- [ ] 3.1 `searchUiState(...)` gains the explicit `viewerKnownFree: Boolean` input: a below-2 query projects to `PremiumGate` when `true`, `Idle` otherwise; eligible queries unchanged
- [ ] 3.2 `SearchScreenUiState(query, surface, openingPostId, pendingOpenPost)`; `SearchViewModel` folds its state into ONE `uiState` via `stateIn(WhileSubscribed(5_000))` (raw `outcome` kept as the white-box seam; `isLoading`/`isLoadingMore` private)
- [ ] 3.3 Constructor gains `ProfileFlow` + `SelfUserIdProvider`; on-entry self read → tier (`isPremium || purchaseConfirmed.value`; null id / non-`Loaded` → not-known-Free)
- [ ] 3.4 First-page `Results` / `RateLimited` marks the tier known Premium; a `403` never marks it Free
- [ ] 3.5 The existing `purchaseConfirmed` collector also marks the tier known Premium (the once-only `PremiumGate` re-run untouched)
- [ ] 3.6 `onResultTap(hit)`: cancel prior → `openingPostId = hit.postId` → `flow.resolvePost` → `pendingOpenPost` = `Resolved.toPostDetailTarget()` or the v1 fallback target; `openingPostId` cleared only by the latest job; `onOpenPostConsumed()` clears the pending target

## 4. Screen + host (design D2, D6, D10)

- [ ] 4.1 `SearchScreen`: inject `ProfileFlow` + `SelfUserIdProvider`; collect the single `uiState`; `LaunchedEffect(pendingOpenPost)` → `onOpenPost(target)` + `onOpenPostConsumed()`; `onOpenPost: (PostDetailTarget) -> Unit`; card tap → `viewModel::onResultTap`
- [ ] 4.2 `SearchResultCard` gains `isOpening` → a 16 dp `CircularProgressIndicator` tagged `SEARCH_RESULT_OPENING_TAG` (the notification-row idiom); no new strings
- [ ] 4.3 `AppEntryProvider`: private `PostDetailTarget.toRoute(focusReplyComposer = false)` used by the Home open / reply-shortcut pushes and the search entry (`onOpenPost = { backStack.add(it.toRoute()) }`); refresh the entry comments (no more "documented defaults")
- [ ] 4.4 Do NOT touch `PostDetailScreen.kt` (concurrent owner); record "no edit" in the PR body

## 5. Tests (one per spec'd scenario)

- [ ] 5.1 commonTest `SearchUiStateTest`: existing cases pass `viewerKnownFree = false`; new: `true` → empty/`"a"` = `PremiumGate`, `"jakarta"` + Results = `Results`
- [ ] 5.2 commonTest `SearchViewModelTest` (via `uiState` with a collector, plus the raw `outcome` seam):
  - **On-entry tier:** Free → `PremiumGate` with no search issued; Premium → `Idle`; pending, `NetworkError`, or null self id → `Idle`.
  - **Server evidence:** a Free viewer's `Results` then clear → `Idle`; a Free viewer's `403` then clear → `PremiumGate`.
  - **Purchase:** confirmed while on-entry gated → `Idle` with no request; confirmed before entry with a Free read → `Idle`.
  - **Taps:** a `Resolved` tap delivers the hydrated target (`distanceM = null`); `Unavailable` → the fallback defaults; an in-flight tap exposes `openingPostId`; a second tap supersedes (one target, the latest); `onOpenPostConsumed` clears it.
  - **Existing:** the existing tests are kept green (adapted to the fold).
- [ ] 5.3 commonTest `SearchRepository` `resolvePost` (MockEngine): `200` mixed-case full body → `Resolved` (snake `city_name`/`liked_by_viewer`/`reply_count`, bare `imageUrl`); `404` → `Unavailable`
- [ ] 5.4 Robolectric `SearchScreenTest`:
  - Koin binds `ProfileFlow` (Premium by default) + `SelfUserIdProvider`.
  - New: a Free read → the upsell body + CTA render before typing, the Idle prompt absent, zero searches.
  - New: the tap → `onOpenPost` gets the hydrated target.
  - New: an in-flight tap shows `SEARCH_RESULT_OPENING_TAG`.
  - New: under `TestNavHost` with the real `appEntryProvider`, a tap pushes `PostDetailRoute` with the hydrated fields.
  - New: a typed query never renders a typeahead and only `search(...)` is called.
  - Updated: the existing payload test now asserts the `Unavailable` fallback defaults.
- [ ] 5.5 iosTest `SearchFlowIosTest` (K/N-legal names): bind a Premium self read so the existing three stay green; new `onEntry_freeViewer_showsUpsellBeforeTyping`
- [ ] 5.6 Notification tests (`NotificationsViewModelNavTest`, `NotificationsScreenNavTest`, `FakeNotificationsFlow`, push-tap tests): imports only; all green unchanged in behavior

## 6. Docs

- [ ] 6.1 `docs/11-Engineering-Standards.md` §2.2 known-debt sentence: drop `SearchViewModel` (now one `uiState`); count 10 → 9
- [ ] 6.2 Confirm `docs/03-UX-Design.md` § Search UX needs no edit ("Free users see an upsell on tap" now true); no new strings, so no `strings.xml` change

## 7. Verification & lifecycle

- [ ] 7.1 Gate (docs/13): `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test :mobile:app:ktlintCheck :mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest` (throwaway Postgres on :5434 if the dev DB is dirty) + `:mobile:app:iosSimulatorArm64Test`
- [ ] 7.2 Manual verify (verify-loop §B, local emulator) against a stub backend serving the real wires: Free self read → upsell on entry; Premium → Idle; a result tap → detail opens with the real city / like state / reply count / image; a `404` by-id → fallback opens. Screenshots in the PR body (docs/11 §5 DoD)
- [ ] 7.3 PR title/body current at each phase boundary; body carries `Closes #253` and `Closes #255` on separate lines; archive via `/opsx:archive`, then hand-edit the now-stale `## Purpose` of `mobile-search` (on-entry gate + by-id hydration), since deltas cannot modify Purpose
