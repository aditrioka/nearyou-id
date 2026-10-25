## Context

`mobile-follow-lists` (PR #298) shipped the tabbed Pengikut / Mengikuti surface with navigational-only rows. The follow action lives on the destination `ProfileScreen`, whose toggle is optimistic with revert and maps 429 → the follow-churn message and the follow-`POST` constant 404 → the neutral "user unavailable" message (`mobile-profile`). Issue #307 asks for that toggle inline on each row.

The list wire (`FollowListItem` in `FollowRoutes.kt`) carries `userId / username / displayName / isPremium / createdAt` and no viewer-relative state. So the client cannot know which label a row starts with. The 2026-09-25 triage corrected the issue body on this point. The profile read already exposes the needed vocabulary: `UserProfileResponse.followedByViewer` + `isSelf`.

Layers spanned (docs/12):

- **backend**: list wire fields.
- **mobile**: row toggle + return re-read.
- **admin**: not applicable. There is no viewer-relative follow surface in the panel, and the admin user lookup is not viewer-scoped.

All three are covered in this PR. Nothing is deferred.

## Goals / Non-Goals

**Goals:**
- Each follower/following row carries `followedByViewer` + `isSelf` from the server.
- Each non-self row renders an inline toggle with the exact profile-toggle semantics: optimistic, revert, 429 message, constant-404 message.
- The row stays correct after the viewer edits that user's follow or block state on the profile reached by the row tap.

**Non-Goals:**
- Mutating the parent profile's `followerCount` / `followingCount` locally. Counts are raw read snapshots (`user-profile-read`), and the parent `ProfileScreen` already re-reads on `ON_RESUME` (#498).
- Removing a row when the viewer unfollows from their own Mengikuti tab. The row stays and flips to "Ikuti", so an accidental unfollow can be undone in place. A refresh drops it.
- An unfollow confirmation dialog. The profile toggle has none, and parity is the contract.
- Propagating toggles across separate `FollowListRoute` entries deeper in the stack. Each entry re-reads only the row it opened.
- Making `ProfileViewModel`'s own follow write `NonCancellable`. That is issue #577's sweep, and this change does not pre-empt it.

## Decisions

### D1 — Wire: `followedByViewer` via correlated `EXISTS`; `isSelf` computed at the route

`followedByViewer` is added to both list queries as a select-list column:

```sql
EXISTS (SELECT 1 FROM follows vf WHERE vf.follower_id = :viewer AND vf.followee_id = <row user>) AS followed_by_viewer
```

- It is a primary-key probe on `follows (follower_id, followee_id)` (V6), at most 31 per page.
- It leaves the row sourcing, the bidirectional block NOT-IN subqueries, the keyset predicate, `ORDER BY` and `LIMIT` untouched.
- The `JOIN visible_users` stays out of the ordering/keyset per the existing contract.
- `follows` is outside `BlockExclusionJoinRule`'s protected-table pattern, so no annotation is needed.

`isSelf` needs no SQL: `row.userId == viewerId` is computed where the route maps `FollowPage` → `FollowListResponse`, the layer that already knows the viewer.

- For a self row `followedByViewer` is always `false`, because the V6 CHECK forbids a self-edge.
- Field names reuse the `UserProfileResponse` vocabulary, bare camelCase with no `@SerialName` (the list wire precedent).

*Alternatives:*
- A `LEFT JOIN follows vf ON …` also works and is equivalent. `EXISTS` was chosen because it cannot fan out rows: the PK makes the join 0..1, but `EXISTS` states the intent.
- Computing `isSelf` client-side via `SelfUserIdProvider` was rejected. It would add a token-decode dependency to the list VM for a fact the server already holds.

### D2 — Toggle data path: reuse `ProfileFlow.follow` / `unfollow`; no new client

The row toggle calls the shipped `ProfileFlow` seam (bound to `ProfileRepository` → `ProfileApiClient`'s `POST`/`DELETE /api/v1/follows/{id}`), and it consumes the shipped `FollowToggleOutcome`. `FollowListViewModel` therefore depends on two repository seams: `FollowListFlow` for reads and `ProfileFlow` for the toggle and the return re-read. Both are already Koin singles, so there are no DI changes. The UI → ViewModel → Repository direction is preserved.

*Alternative:* adding `follow` / `unfollow` to `FollowListFlow` and `FollowListRepository` was rejected. It would be a second client for the same two endpoints and a second status→outcome mapping that could drift from the profile's.

### D3 — One shared settlement mapping

`FollowToggleOutcome.settle(previouslyFollowed)` → `(followed, message)` is extracted from `ProfileViewModel.onToggleFollow` into `screens/profile/ProfileUiState.kt`, next to `ProfileMessage`. Both VMs call it:

| Outcome | Settled state | Message |
|---|---|---|
| `Followed` | `true` | — |
| `Unfollowed` | `false` | — |
| `RateLimited` | revert | `FOLLOW_RATE_LIMITED` |
| `TargetGone` | revert | `TARGET_UNAVAILABLE` |
| `NetworkError` | revert | — (silent) |

The list screen renders the message through the same `ProfileMessage → StringResource` mapping. That function moves from `private` to `internal` in `ProfileScreen.kt`, so no string key is added and both surfaces read the same copy.

### D4 — Optimistic state is a patch of the rows, keyed by `userId`, across both tabs

The VM keeps `followedByViewer` inside the loaded rows and patches every row with that `userId` in **both** tabs. The same user can appear in both tabs, for example a mutual follow on the viewer's own lists. A second map, `followInFlight: Map<userId, optimisticValue>`, guards duplicates and disables that user's buttons.

- A toggle claims its in-flight entry synchronously, patches the rows optimistically, runs the write, then patches the rows to the settled value and releases the entry.
- A page load or refresh that lands while a toggle is in flight re-applies the in-flight optimistic value over the incoming rows, so the button does not flicker.

*Alternative:* a VM-lifetime override map applied at projection time was rejected. A refresh of one tab would then have to decide whether to drop an override that the other tab's still-stale rows depend on. Patching the rows keeps a single source of truth: the newest rows, plus whatever is still in flight.

### D5 — Must-complete write (docs/11 § 2.2)

Only the toggle's network leg runs under `withContext(NonCancellable)`, with the in-flight entry claimed before the launch. That way a pop mid-request does not abort a `POST`/`DELETE` the server may already have applied. Reads stay cancellable. The cancel-safety test follows the `PostDetailViewModelTest` pattern.

### D6 — Return-from-profile re-read (docs/11 § 2.3 "data the overlay itself edited")

The row's follow state is now shown on the list and editable on the overlay the row opens, so the list re-reads on return, using the registered silent `ON_RESUME` re-read:

1. The row tap records `lastOpenedUserId` in the VM, then navigates.
2. On `ON_RESUME`, the screen calls `onResumed()`. If a user was opened and is not in flight, the VM issues `ProfileFlow.loadProfile(id)` once and clears the marker.
3. It acts on the outcome:
   - `Loaded`: patch that user's rows with `followedByViewer`.
   - `NotFound` (the constant 404: the viewer blocked them from the profile, or they became hidden or gone): remove that user's rows from both tabs. This is exactly what a refresh would return, because the lists INNER JOIN `visible_users` and block-filter.
   - `NetworkError`: change nothing.

A single-user `GET` replaces a full re-read of every loaded page. A full re-read would reset pagination and scroll position for what is at most one changed row.

### D7 — Row button follows mockup frame 7's inline "Ikuti"

The mockup board has no follow-list frame. The closest canonical reference for a follow button inline in an identity row is frame 7 (post detail): `span.btn.tonal.sm` "Ikuti", measured as:

- `secondaryContainer` / `onSecondaryContainer` colours;
- 34 dp height, 16 dp horizontal padding, pill radius;
- 13 sp weight-700 label;
- a 12 dp gap from the identity column.

Mapping:

- **Not followed** → M3 `FilledTonalButton` ("Ikuti", `profile_follow`), per frame 7.
- **Followed** → an `OutlinedButton` at the same metrics ("Mengikuti", `profile_unfollow`). This mirrors the shipped profile toggle's followed state. Frame 7 shows only the not-followed state.

The M3 button's clickable `Surface` keeps the 48 dp minimum touch target, even though the visual height is 34 dp. Self rows render no button. The row itself stays the tap target for `ProfileRoute`, and the button consumes its own click.

### Standards conformance (docs/11)

The change builds on these registered patterns, with no fork:

- **§ 2.2 state**: one `StateFlow<FollowListUiState>`. The one-shot snackbar message is a nullable state field cleared by `onMessageShown()`, with no event bus. The write is `NonCancellable`.
- **§ 2.3**: the overlay-return refresh uses the silent `ON_RESUME` re-read.
- **§ 2.6 data layer**: VM → repository seams only. The DTO matches the route file.
- **§ 3.1 backend layering**: the route maps, the service paginates unchanged, the repository owns the SQL.
- **§ 3.2**: the existing pool-bounded dispatcher is unchanged.
- **§ 4 reuse-first**: `ProfileFlow`, `FollowToggleOutcome`, the profile message mapping, and `PremiumBadgeIcon` / `LetterAvatar` are already used by the row.

No Pattern-Registry amendment is needed.

## Risks / Trade-offs

- **[Stale refresh after settle]** A refresh request issued before a toggle's write commits but answered after it settles can overwrite the row with the pre-toggle server value. → This needs a concurrent pull-to-refresh and toggle within one round-trip. The in-flight overlay covers the common case (a load landing mid-toggle). A second refresh or the next return re-read corrects it. Accepted.
- **[Rate limit]** Inline toggles make it easier to spend the shared 50/h follow bucket. → 429 reverts with the existing follow-churn message, the same as the profile. The bucket is the server's anti-churn control, and no client throttle is added.
- **[Mixed-version wire]** The new app requires the two new fields. → Backend + mobile ship in one PR, and staging deploys the backend at merge. Older app builds tolerate the additive fields (`ignoreUnknownKeys = true` on both sides). A backend rollback against the new app would surface the list as the retryable error state, never a crash: a parse failure maps to `NetworkError` by the existing casing-drift guard.
- **[Deeper-stack staleness]** A toggle made in a nested `FollowListRoute` (list → profile → its list) for a user also shown in an outer list is not pushed outward. The outer list re-reads only the row it opened. → A pull-to-refresh corrects it. Accepted and noted as a non-goal.

## Migration Plan

No migration. Deploy order is the normal single-PR order: the backend's additive fields ship with the app change, and staging picks up the backend at merge. Rollback: revert the PR. Older app builds already ignore the extra fields.

## Open Questions

None.
