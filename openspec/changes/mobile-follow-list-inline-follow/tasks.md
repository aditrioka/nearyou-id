## 1. Backend wire — viewer-relative row state (design D1)

- [ ] 1.1 `core/data/.../UserFollowsRepository.kt`: `FollowListRow.followedByViewer: Boolean` + KDoc (calling-viewer relative; the list method KDocs name the new column)
- [ ] 1.2 `infra/supabase/.../JdbcUserFollowsRepository.kt`: both list queries select `EXISTS (SELECT 1 FROM follows vf WHERE vf.follower_id = ? AND vf.followee_id = <row user>) AS followed_by_viewer`; `queryFollowListPage` binds the viewer first (select-list param), then the unchanged WHERE / cursor / limit params; row mapper reads the column. Sourcing, block NOT-INs, keyset, ORDER BY, LIMIT unchanged
- [ ] 1.3 `backend/ktor/.../follow/FollowRoutes.kt`: `FollowListItem` gains bare-camelCase `followedByViewer` + `isSelf`; `FollowPage.toResponse(viewerId)` sets `isSelf = row.userId == viewerId`; KDoc updated
- [ ] 1.4 `SocialGraphRateLimitTest.kt` fake repo still compiles (signature unchanged; no-op if so)

## 2. Backend tests (follow-system integration items 24–26)

- [ ] 2.1 `FollowEndpointsTest`: `/followers` third-party viewer — `followedByViewer` true for a row the viewer follows, false for a row only the profile owner follows; `isSelf` false on both
- [ ] 2.2 `FollowEndpointsTest`: `/followers` the viewer's own row → `isSelf = true`, `followedByViewer = false`
- [ ] 2.3 `FollowEndpointsTest`: `/following` own list → every row `followedByViewer = true`, `isSelf = false`; third-party list → per-row viewer state + the viewer's own row `isSelf = true`
- [ ] 2.4 `FollowEndpointsTest`: extend the exact-camelCase-keys test (followers) + the following summary test with `followedByViewer` / `isSelf` present and no snake_case variants

## 3. Mobile data layer (design D1/D2)

- [ ] 3.1 `followlist/FollowListApiClient.kt`: `FollowListItemDto` gains REQUIRED `followedByViewer` + `isSelf` (no default) + KDoc
- [ ] 3.2 `followlist/FollowListFlow.kt`: `FollowListUser` gains `followedByViewer` + `isSelf`; `followlist/FollowListRepository.kt` maps them
- [ ] 3.3 Tests: `FollowListApiClientTest` shipped-wire fixture gains the fields + asserts them; new missing-field case → repository `NetworkError` (`FollowListRepositoryTest`); `FakeFollowListFlow.user(...)` gains `followed` / `self` params

## 4. Shared settlement + profile reuse (design D3)

- [ ] 4.1 `screens/profile/ProfileUiState.kt`: `FollowToggleOutcome.settle(previouslyFollowed): FollowSettlement(followed, message)` (the five-outcome table)
- [ ] 4.2 `ProfileViewModel.onToggleFollow` uses `settle` (behavior identical — existing `ProfileViewModelTest` stays green); `ProfileScreen.kt` `ProfileMessage.resource()` → `internal`
- [ ] 4.3 commonTest: `settle` table test (all five outcomes; message only on RateLimited / TargetGone; revert value = previous)

## 5. FollowListViewModel — toggle + re-read (design D4–D6)

- [ ] 5.1 VM takes `profileFlow: ProfileFlow`; state gains `followInFlight: Map<String, Boolean>` + `message: ProfileMessage?` + `lastOpenedUserId: String?`; `FollowListUiState` exposes `followInFlight: Set<String>` + `message`
- [ ] 5.2 `onToggleFollow(userId)`: no-op if in flight or self; claim + optimistic patch in BOTH tabs synchronously; launch, network leg under `withContext(NonCancellable)`; settle via `settle` → patch both tabs, release claim, set message
- [ ] 5.3 Page loads (first page / refresh / load-more) re-apply in-flight optimistic values over incoming rows
- [ ] 5.4 `onRowOpened(userId)` records the marker; `onResumed()` → one `loadProfile` when marked and not in flight: Loaded → patch both tabs; NotFound → drop rows from both tabs; NetworkError → nothing; marker cleared
- [ ] 5.5 `onMessageShown()` clears the message
- [ ] 5.6 `FollowListViewModelTest`: optimistic follow + settle; unfollow keeps the row; revert on NetworkError / RateLimited (+message) / TargetGone (+message); both-tabs patch; duplicate tap no-op; load landing mid-toggle keeps optimistic value; self row toggle no-op; cancel-safety (gated `FakeProfileFlow`, clear the ViewModelStore mid-flight, release, assert the call completed — the `PostDetailViewModelTest` pattern); re-read patch / remove (incl. tab → Empty) / NetworkError no-op / no-read without marker; `onMessageShown` clears

## 6. FollowListScreen — row toggle + snackbar + resume (design D6/D7)

- [ ] 6.1 Consult mockup frame 7 annex (`dev/scripts/mockup-measure.sh dev/mockups/nearyou-screens-mockup.html 7`) + `mobile-ui-foundation` checklist; row trailing toggle: `FilledTonalButton` "Ikuti" / `OutlinedButton` "Mengikuti" (34 dp, 16 dp h-padding, pill, 12 dp gap), disabled while in flight, absent on self rows, test tag per row
- [ ] 6.2 Scaffold `SnackbarHost`; message → `stringResource(message.resource())` → `showSnackbar` → `onMessageShown()`
- [ ] 6.3 Row tap → `viewModel.onRowOpened(id)` then `onOpenProfile(id)`; `LifecycleEventEffect(ON_RESUME) { viewModel.onResumed() }`; VM factory passes `koinInject<ProfileFlow>()`
- [ ] 6.4 `FollowListScreenTest` (Robolectric): Koin binds `FakeProfileFlow`; labels "Ikuti"/"Mengikuti" by `followedByViewer`; toggle tap flips the label and fires no navigation; self row has no toggle; 429 → rate-limit snackbar text
- [ ] 6.5 `FollowListFlowIosTest`: Koin binds `FakeProfileFlow`; toggle flip on the simulator

## 7. Spec purpose + docs

- [ ] 7.1 At archive: hand-edit `openspec/specs/mobile-follow-lists/spec.md` `## Purpose` (no longer "no backend code" / "Inline per-row follow/unfollow is deferred") — deltas cannot modify Purpose
- [ ] 7.2 Close the loop on the deferral's tracking issue: PR body `Closes #307`

## 8. Verification & lifecycle

- [ ] 8.1 Backend gate on a throwaway Postgres (docs/13; AxonFlow loopback override operator-approved): `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test`
- [ ] 8.2 Mobile gate: `:mobile:app:ktlintCheck :mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest :mobile:app:iosSimulatorArm64Test`
- [ ] 8.3 Manual verify (verify-loop): Android emulator AND iOS simulator screenshots of the follow list with "Ikuti" / "Mengikuti" rows, a toggle flip, and a self row without a toggle; evidence in the PR body (docs/11 §5.3)
- [ ] 8.4 Staging branch deploy + smoke of `GET /users/{id}/followers` carrying `followedByViewer` / `isSelf` (docs/11 §5 DoD #4 — runtime-impacting backend)
- [ ] 8.5 Self-review sub-agents — FOUR lenses (general / security-and-invariant / OpenSpec / test-coverage) in two waves of two: the list SQL edit shifts bind indices in the statement that carries the bidirectional block-exclusion subqueries (invariant: block enforcement — "unsure = touched"); PR body carries `Review findings applied: …`
- [ ] 8.6 `/opsx:archive` on the same branch; operator merges
