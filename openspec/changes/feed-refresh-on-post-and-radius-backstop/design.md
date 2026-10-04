## Context

The three Home feeds (Nearby / Following / Global) keep their state in `HomeRoute`-scoped ViewModels, which `rememberViewModelStoreNavEntryDecorator` scopes to the HomeRoute entry. That state survives the composer being pushed on top, feed swipes and section switches, so none of those trips re-fetch (`mobile-nav-swap-to-navigation3` Decision 5). Retention is correct for every overlay except one: after a successful post the feed the author returns to is stale. `mobile-post-creation` design D8 deferred that refresh as #173.

Separately, `NearbyTimelineFlow` has two page-1 methods:

- `loadFirstPage(radiusM): NearbyTimelineOutcome`, used for the initial load, pull-to-refresh and error retry.
- `changeRadius(radiusM): RadiusChangeResult`, used only for a slider selection.

Only `changeRadius` checks for `403 radius_premium_only`. The other page-1 paths and `loadMore` send that 403 through the status mapping's "other non-2xx" branch, which yields `NetworkError`. The `mobile-nearby-radius-slider` requirement says *a Nearby fetch*, so this is a code-vs-spec gap (#518). It is reachable: the self-profile read degrades optimistically to Premium-known, a non-20 km selection then fails before any HTTP call (no location fix yet), and "Coba lagi" re-issues the gated radius.

## Goals / Non-Goals

**Goals**
- After a successful post, the viewer's own post appears in Nearby on return and in Global when it is next shown, with no manual refresh.
- Every other return to Home (composer cancelled, post detail, profile, search, chat, paywall) keeps reusing the loaded feed.
- A `radius_premium_only` 403 on any Nearby fetch path reaches one mapping and one ViewModel handler: the control goes back to 20 km and the radius upsell shows.

**Non-Goals**
- Refreshing Following. Own posts never appear there because self-follow is impossible (`following-timeline` § "Following carries NO own-content self-arm").
- Optimistic insertion of the new post into the list. The refresh re-reads page 1, which is the source of truth for ordering, distance and shadow-ban self-visibility.
- Forcing a scroll to the top after the refresh. A viewer at the top stays pinned there (D5); a scrolled-down viewer keeps their position, exactly like a pull-to-refresh.
- Re-gating `isPremiumKnown` to Free after a 403. A webhook-lag buyer must stay unlocked; the backstop only reverts the current selection, as it does today.
- Any backend, admin or wire change.

## Decisions

### D1 — Hoisted reload key, not an event bus or a Nav3 result

The composer already reports success to its host through `onPostCreated`, and `appEntryProvider` owns that host. The provider bumps a reload key, and the key flows down as plain state: `AppShellScreen` → `HomeScreen` → `NearbyTimelineScreen` / `GlobalTimelineScreen`. Each feed passes it to its ViewModel from a `LaunchedEffect(key)`.

The ViewModel records the first key it sees, because a freshly constructed ViewModel's own initial load is already current. On any later change it calls its existing `reload()`, so the list stays mounted and only `isRefreshing` toggles.

- *Alternative: a shared `MutableSharedFlow` / Koin-singleton signal.* Rejected. docs/11 §2.2 names `Channel`/`SharedFlow` event buses an anti-pattern, and the operator asked for no new bus.
- *Alternative: the `PremiumEntitlementSession.purchaseConfirmed` precedent* (a Koin single holding a `StateFlow`, injected into `NearbyTimelineViewModel`). It is state, not a bus, but it is process-wide mutable state that outlives the back stack. Here the signal only matters to the HomeRoute entry, and the composer already reports success to its host through `onPostCreated`. Hoisting through the host keeps the signal scoped to the entry that consumes it, lets an off-screen Global refresh lazily when shown, and adds no new singleton. `purchaseConfirmed` stays the pattern for app-wide facts such as "this account is Premium"; per-navigation results go through the host.
- *Alternative: a Nav3 result-passing mechanism (the `ResultEventBus` recipe).* Rejected. No screen in this app passes nav results, so adopting it for one signal would add a second navigation pattern (docs/11 Pattern Registry).
- *Alternative: `ON_RESUME` → `reload()`.* Rejected. HomeRoute re-enters composition, and so fires `ON_RESUME`, on every overlay pop and every foreground return. That would undo Decision 5's retention and spend a timeline read each time.
- *Alternative: a `HomeRoute` NavKey parameter.* Rejected. It needs a `NavKeys.kt` change, and a parallel session (#516/#517) owns that file.

### D2 — Where the key lives: a saveable counter in the HomeRoute entry, fed by a provider-level flag

The feed ViewModels survive a configuration change, but `appEntryProvider` is rebuilt when the Activity is recreated. A counter held at provider level would reset to 0 while the ViewModels remembered 1, and that mismatch would trigger a spurious read after rotation, which counts against the Free daily read cap. So:

- The **counter** is `rememberSaveable` inside the `HomeRoute` entry. The saveable-state decorator restores it together with the HomeRoute ViewModels on rotation. After process death it comes back while the ViewModels are new, and the ViewModels' first-key-recorded rule keeps that consistent.
- The provider holds only a **one-shot pending flag** (`postCreated: MutableState<Boolean>`). `onPostCreated` sets it and pops. The HomeRoute entry consumes it in a `LaunchedEffect` and increments the counter.
- The flag is a defaulted parameter of `appEntryProvider`, so `App.kt` and `TestNavHost` are unchanged. They already call the provider once per back stack, inside `remember(backStack)`.

### D3 — One gate mapping in the repository, one handler in the ViewModel (#518)

The root cause is that page-1 and load-more each have their own mapping, and only one of them knows about the gate. The fix:

- **Repository.** The private `toOutcome` status mapping, which `loadFirstPage` and `loadMore` both use, is wrapped by a private `toResult`. `toResult` checks `HttpError(403, "radius_premium_only")` first and returns `NearbyFetchResult.PremiumGated`. Every other result is `NearbyFetchResult.Loaded(toOutcome(…))`, the frozen mapping unchanged. `changeRadius` is deleted. A radius change is just `loadFirstPage(newRadius)`, so the duplicate method that let the bug happen no longer exists.
- **Interface.** `RadiusChangeResult` is renamed `NearbyFetchResult`, because it now describes every Nearby fetch and not just a radius change. `NearbyTimelineOutcome` gains no member, which the spec requires. The 403 stays a ViewModel-level interpretation.
- **ViewModel.** One private `fetchFirstPage()` serves `init`, `reload()` (pull-to-refresh and retry) and `selectRadius`, which now just sets the radius and calls `reload()`. `changeRadiusAndReload` is deleted. On `PremiumGated`, a single `applyPremiumGate()` sets 20 km and raises the upsell, and the page is re-fetched at 20 km. The load-more fetch maps `PremiumGated` to `applyPremiumGate()` + `reload()`. Because `reload()` resets the `LoadMoreController` generation, the in-flight load-more result is dropped as stale, and no retry footer appears for a page that can never load.
- *Alternative: also map the 403 inside `loadFirstPage`'s `NearbyTimelineOutcome`.* Rejected; the spec forbids a new outcome member.
- *Alternative: patch `reload()` to call `changeRadius`.* Rejected. It fixes the one caller the issue names, but leaves load-more and the duplicate method in place: a per-caller patch, not the root cause.

### D4 — The 20 km re-fetch is non-recursive, and a gated 20 km is a fault

After a `PremiumGated` result at a Premium radius, the ViewModel re-fetches once at `NEARBY_RADIUS_M`. The Free anchor is never gated, so a gated fetch *at* 20 km is a server fault. That covers both the re-fetch and an original 20 km fetch (for example the load-more backstop's `reload()`). It maps straight to the retryable `NetworkError`, with no upsell and no further fetch. Recursing could loop, and a Premium upsell for a server fault would be misleading.

### D5 — The refreshed list stays pinned to the top (found on device)

The first emulator run showed the reload landing, yet the new post was invisible. `LazyColumn` keeps the previous first item's key in view across a data change, so a post prepended at index 0 sat just above the viewport, with only its bottom edge peeking in. The fix lives in the ONE shared list (`PostFeedList`, `mobile-design-system`), not in each feed:

- When the first post's key changes while the viewer is at the very top (index 0, offset 0), the list calls `LazyListState.requestScrollToItem(0)`. This is the documented Compose API for "stay at the top when items are prepended".
- It is called from a `SideEffect`, which runs after the new posts are applied and before the remeasure, so "was at the top" still reads the pre-change position.
- A scrolled-down viewer is never jumped.
- This also benefits pull-to-refresh on all three feeds, which had the same latent behaviour.
- *Alternative: a ViewModel one-shot "scroll to top" plus `scrollToItem(0)` after the data lands.* Rejected. It adds per-feed state plumbing and a visible one-frame jump. It also fixes only the post path, not the shared list.

### Standards conformance (docs/11)

- **State (§2.2).** The reload key is state passed down and the gate is a one-shot state field (`radiusUpsell`). No `Channel`/`SharedFlow` is added. The ViewModels keep their single `uiState` and separate `isRefreshing`, unchanged.
- **Navigation (§2.3).** Back-stack operations stay in `appEntryProvider`. No new NavKey. Tabs stay pager state.
- **Data (§2.6).** The repository still returns a sealed result at its boundary, and exceptions still never cross into ViewModels. The single gate-aware mapping replaces a duplicated one.
- **Shared load-more controller (`mobile-design-system`).** Reused unchanged. The generation reset already drops stale results.
- No Pattern Registry deviation, so no docs/11 amendment.

### Cross-layer scope (docs/12)

Mobile only. Neither issue changes a backend, admin or wire contract: `POST /api/v1/posts` and `GET /api/v1/timeline/nearby` with its existing `403 radius_premium_only` are already shipped. Nothing is deferred, so no deferred-layer requirement is needed.

## Risks / Trade-offs

- [A reload is suppressed while that feed's initial load is still in flight, so a post made during a very slow first location fix may miss that load] → Rare: the author must open the composer and post before the first Nearby load finishes. Pull-to-refresh still recovers. The ViewModel records the key either way, so there is no reload loop.
- [Each post spends up to two extra page-1 reads (Nearby, Global)] → It replaces the manual pull-to-refresh the user would have done anyway. Global only re-reads when it is next shown.
- [`NearbyFetchResult` changes the `loadFirstPage`/`loadMore` return types] → The only implementations are the repository and `FakeNearbyTimelineFlow`. The fake keeps its constructor, so the screen and iOS tests that build it are unaffected.
- [Diagnostic tags `nearby_radius_*` disappear with `changeRadius`] → A radius change now logs under the page-1 tags (`nearby_network_error` / `nearby_invalid_request` / `nearby_position_unavailable`), which `DiagnosticSinkWiringTest` already pins.

## Migration Plan

Client-only, no data. Rollback is a revert of the squash commit.
