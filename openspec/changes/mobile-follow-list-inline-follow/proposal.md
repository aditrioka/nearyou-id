## Why

The follower/following list rows (`mobile-follow-lists`, PR #298) are navigational only, so the most common list action — "follow back from my followers list" or "unfollow from my following list" — costs a row tap, a profile load, the toggle, and a back. The deferral was recorded as an explicit requirement ("Inline follow/unfollow on rows is deferred") tracked by issue [#307](https://github.com/aditrioka/nearyou-id/issues/307). The 2026-09-25 triage corrected the issue body: the list wire carries no viewer follow state, so an inline toggle needs a backend wire change too.

## What Changes

- **Backend wire (`follow-system`)**: each `GET /api/v1/users/{id}/followers` and `/following` row gains two bare-camelCase booleans, matching the `UserProfileResponse` vocabulary:
  - `followedByViewer`: whether the calling viewer follows the row user. It is a correlated `EXISTS` on the `follows` primary key.
  - `isSelf`: whether the row user is the calling viewer.
  Additive. No new endpoint, no migration, no change to row sourcing, filtering, ordering, the cursor, or the constant 404.
- **Mobile (`mobile-follow-lists`)**: each non-self row gets an inline follow toggle. It shows "Ikuti" when the viewer does not follow the row user and "Mengikuti" when they do. The toggle reuses the shipped `ProfileFlow.follow` / `unfollow` calls and the profile toggle's optimistic flip, its revert on failure, its 429 rate-limit message, and its constant-404 "user unavailable" message. A user who appears in both tabs flips in both. The write survives a screen pop (`NonCancellable`, docs/11 § 2.2).
- **Mobile, return from a row's profile**: the row tap still opens `ProfileRoute`. The follow state shown on the row can now change on that profile (the user follows, unfollows or blocks there). So when the list resumes, it silently re-reads that one user (docs/11 § 2.3 ON_RESUME re-read):
  - The user still resolves: the row's follow state is patched.
  - The user no longer resolves (blocked, hidden or gone): the row is removed from both tabs.
- **Refactor (no behavior change)**: the follow-outcome settlement (follow result → settled state + optional message) is extracted once and shared by `ProfileViewModel` and `FollowListViewModel`, so the two toggles cannot drift.
- The `mobile-follow-lists` deferral requirement is replaced by the shipped toggle requirement.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `follow-system`: the `/followers` and `/following` list requirements gain the `followedByViewer` + `isSelf` row fields (with scenarios). The integration-test coverage list gains the matching items.
- `mobile-follow-lists`: these requirements change:
  - The list-wire DTO requirement gains the two fields.
  - The "Inline follow/unfollow on rows is deferred" requirement is renamed and rewritten as the shipped inline toggle.
  - A new requirement covers the return-from-profile row re-read.
  - The Koin-wiring requirement now lets the ViewModel also depend on the `ProfileFlow` seam.
  - The test-trio requirement covers the new behavior.

## Impact

- **Backend**: `core/data/.../UserFollowsRepository.kt` (`FollowListRow.followedByViewer`), `infra/supabase/.../JdbcUserFollowsRepository.kt` (the `EXISTS` select column + bind order), `backend/ktor/.../follow/FollowRoutes.kt` (`FollowListItem` fields, `isSelf` mapping), `FollowEndpointsTest.kt`, plus the fake in `SocialGraphRateLimitTest.kt` (compile only).
- **Mobile**:
  - Data and seam: `followlist/FollowListApiClient.kt` (DTO), `followlist/FollowListRepository.kt`, `followlist/FollowListFlow.kt` (`FollowListUser`).
  - Screen: `screens/followlist/FollowListViewModel.kt`, `FollowListUiState.kt`, `FollowListScreen.kt` (row toggle + snackbar + ON_RESUME re-read).
  - Profile: `screens/profile/ProfileViewModel.kt` + `ProfileUiState.kt` (shared settlement) and `ProfileScreen.kt` (the message→string mapping becomes `internal` for reuse).
  - Tests: commonTest, the Robolectric `FollowListScreenTest`, and the iosTest `FollowListFlowIosTest`.
- **Strings**: none new. The toggle reuses `profile_follow` / `profile_unfollow` / `profile_follow_rate_limited` / `profile_action_user_unavailable`.
- **Admin**: not applicable. The admin panel has no viewer-relative follow surface.
- **Wire compatibility**: additive response fields. Older app builds ignore them (`ignoreUnknownKeys = true`). The new app build requires them, and backend + mobile ship in this one PR.
- No Flyway migration, no new route, no new dependency, no new string key.
