## Why

Two Home-feed rough edges are tracked as `follow-up` debt, and both come from the Nearby/Global feed state that the Home screen keeps across navigation:

- **#173.** After a successful post the composer pops back to Home, but the Nearby and Global feeds keep their already-loaded first page. The author's own post (Nearby and Global both carry an own-content self arm) stays invisible until a manual pull-to-refresh. `mobile-post-creation` design D8 deferred this explicitly.
- **#518.** `mobile-nearby-radius-slider` requires *any* Nearby fetch that returns `403 radius_premium_only` to raise the Premium radius upsell and revert to 20 km. Only the radius-change path does that. Pull-to-refresh, the error-state retry ("Coba lagi") and load-more go through a mapping that turns the same 403 into the generic "Tidak bisa terhubung" error and leave the control on the gated radius. The root cause is structural: `NearbyTimelineFlow` has two page-1 methods, and only `changeRadius` checks for the gate.

`premium-entitlement-lifecycle` (PR #512, which #518 was sequenced behind) has merged, so both can ship now. They touch the same ViewModel and the same feeds, so they ship as one change.

## What Changes

- **Feeds refresh after a successful post (#173).** `appEntryProvider` keeps a feed reload key and bumps it each time the composer reports success, then pops the composer as before. The key is held as saveable state in the `HomeRoute` entry and passed down `AppShellScreen` → `HomeScreen` → the Nearby and Global feed screens. Each HomeRoute-scoped feed ViewModel records the first key it sees and re-fetches page 1 (via its existing `reload()`, keeping the list mounted) whenever the key changes. The feed on screen refreshes on return; a feed that is off-screen refreshes the next time it is shown. No event bus, no Nav3 result API, no `NavKeys.kt` change.
- **Following is explicitly not refreshed.** Self-follow is impossible, so the viewer's own posts never appear in Following.
- **Other returns still reuse the loaded feed.** Leaving the composer without posting (back / close) and returning from post detail, profile, search or chat do not re-fetch, as before.
- **One `radius_premium_only` mapping for every Nearby fetch (#518).** The repository checks for the 403 inside the status mapping that every fetch already passes through, and returns a `NearbyFetchResult` (`Loaded(outcome)` | `PremiumGated`) from both `loadFirstPage` and `loadMore`. The separate `changeRadius` method is removed; radius change is now just a page-1 load at the newly selected radius. `NearbyTimelineOutcome` gains no member.
- **One gate handler in `NearbyTimelineViewModel`.** Every page-1 fetch (initial load, pull-to-refresh, error retry, radius change) goes through one private fetch. A `PremiumGated` result reverts the control to 20 km, raises the radius upsell and re-fetches at 20 km. A gated load-more drops the stale page and runs the same backstop through `reload()`, so no retry footer is left on a page that can never load.
- Tests: ViewModel unit tests for the reload key (Nearby + Global), for the 403 on the retry, pull-to-refresh and load-more paths, and for the repository mapping. Robolectric tests for the post → return refresh through the real `appEntryProvider`, and for the Following exclusion.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `mobile-post-creation`: the "Nearby auto-refresh on return is deferred" requirement is renamed and rewritten. A successful post now refreshes the Nearby and Global feeds on return, not Following; the signal is hoisted state in the entry provider, not an event bus.
- `mobile-nearby-timeline`: "Nearby feed load state … survives the composer round-trip". Returning without posting still does not re-fetch. A successful post re-fetches page 1 once through the reload key.
- `mobile-global-timeline`: "Global feed load state … survives tab switch and the composer round-trip". Same amendment as Nearby.
- `mobile-nearby-radius-slider`: "On-entry tier resolution and reactive 403 backstop". The backstop covers every Nearby fetch path (initial load, pull-to-refresh, error retry, radius change, load-more) through one mapping, with scenarios for the retry and load-more paths. A gated fetch at 20 km is a server fault: the retryable error, with no upsell.
- `mobile-nearby-timeline` (also): "Fetch outcome mapping is HTTP-status-driven …". This requirement gets the one `error.code` carve-out: `radius_premium_only` → `NearbyFetchResult.PremiumGated` ahead of the unchanged mapping.
- `mobile-home-tab-host`: "Tab switching preserves each tab's state and never re-fetches". A successful post is the one exception: an off-screen Nearby or Global page re-fetches once when next shown.
- `mobile-app-scaffold`: "NavDisplay scopes per-entry saveable state …". Its parenthetical now notes that a successful post re-fetches explicitly through the reload key, not through a lost ViewModel.
- `mobile-design-system` (ADDED): "A feed list stays pinned to the top when posts are prepended". Found during device verification: the refresh landed, but `LazyColumn` kept the previous first post's key in view, so the new post sat just above the viewport. The shared `PostFeedList` now calls `requestScrollToItem(0)` when the viewer was at the top, which also helps pull-to-refresh on every feed. A scrolled-down viewer keeps their position.

## Impact

- **Mobile only** (`:mobile:app` commonMain + tests). No backend, admin, wire, schema or string-resource change.
- Code: `screens/routing/AppEntryProvider.kt`, `screens/shell/AppShellScreen.kt`, `screens/home/HomeScreen.kt`, `screens/timeline/{Nearby,Global}TimelineScreen.kt`, `screens/timeline/{Nearby,Global}TimelineViewModel.kt`, `timeline/NearbyTimelineFlow.kt` (`RadiusChangeResult` → `NearbyFetchResult`, `changeRadius` removed), `timeline/NearbyTimelineRepository.kt`, `ui/components/PostFeedList.kt` (D5 pin-to-top), `mobile/app/build.gradle.kts` (Release-variant exclude). Comment-only: `App.kt`, `screens/post/PostCreationScreen.kt`.
- Tests: `FakeNearbyTimelineFlow` / `FakeGlobalTimelineFlow` (per-radius gate, in-flight gates), `NearbyTimelineViewModelTest`, `GlobalTimelineViewModelTest`, `NearbyTimelineRepositoryTest`, `HomeScreenFabTest`, `HomeTabHostScreenTest`, `PostCreationSourceGuardTest`, `PostFeedListTest` (new).
- Docs: `docs/11-Engineering-Standards.md` §2.3 registers the "refresh a screen after an overlay returns" boundary.
- Not touched: `NavKeys.kt` (a parallel session for #516/#517 owns it) and the composer screen. `PostCreationScreen` stays feed-agnostic and still just calls `onPostCreated()`.
- Read budget: a post costs at most one extra page-1 read per refreshed feed (Nearby on return, Global when next shown). That is the same read a manual pull-to-refresh would have spent.
- Closes #173 and #518.
