# mobile-home-tab-host — Delta Specification

## MODIFIED Requirements

### Requirement: The tab host hoists onOpenPost, wired at the call site to a root-stack PostDetailRoute push

`HomeScreen` SHALL hoist an `onOpenPost(...)` callback (taking a card's non-PII display fields) and pass it into BOTH the Nearby tab content (`NearbyTimelineScreen`) and the Global tab content (`GlobalTimelineScreen`) — exactly as it already hoists `onOpenComposer`. As of `mobile-inline-post-actions` it SHALL additionally hoist an `onOpenPostReply(...)` callback (same non-PII display-field payload — the feed cards' reply shortcut) into both feed tabs. The actual `PostDetailRoute` **root** back-stack appends SHALL be wired at the **shell** call site (in `screens/routing/AppEntryProvider.kt`, where — after the section-shell restructure of § "Bottom navigation is a top-level section shell" — `appEntryProvider` maps `HomeRoute` → `AppShellScreen(onOpenComposer = { backStack.add(PostCreationRoute) }, onOpenPost = { … backStack.add(PostDetailRoute(...)) }, onOpenPostReply = { … backStack.add(PostDetailRoute(..., focusReplyComposer = true)) })`; `AppShellScreen` forwards both to the Home section's `HomeScreen`), NOT inside `HomeScreen.kt` / `AppShellScreen.kt` (neither holds a back-stack reference, matching the existing composer-FAB wiring). The appended `PostDetailRoute` SHALL be constructed from exactly the card fields (`postId`, `content`, `cityName`, `distanceM`, `createdAtIso`, `likedByViewer`, `replyCount`, `authorUsername`, `authorDisplayName`; never `latitude`/`longitude`, never the author UUID) — with `focusReplyComposer = true` when constructed from `onOpenPostReply` and the default `false` when constructed from the whole-card `onOpenPost` — so the detail surface overlays the section `NavigationBar`, NOT introducing a per-tab `NavDisplay` back stack (still deferred per GitHub issue [#189](https://github.com/aditrioka/nearyou-id/issues/189) `mobile-home-tab-host-per-tab-backstacks` (label `follow-up`)). The Following tab (a deferred placeholder) wires no `onOpenPost` and no `onOpenPostReply` (it has no feed/cards).

#### Scenario: Invoking onOpenPost in either feed tab pushes PostDetailRoute onto the root stack

- **GIVEN** the `AppShellScreen` call site (`appEntryProvider`) composed over a test root back stack, or `HomeScreen` composed with a recording `onOpenPost` callback, with the Nearby tab selected
- **WHEN** the Nearby card's `onOpenPost` is invoked (and again with the Global tab selected and its card's `onOpenPost`)
- **THEN** in both cases a `PostDetailRoute` carrying the card's display fields including `authorUsername`/`authorDisplayName` with `focusReplyComposer = false` (and no `latitude`/`longitude`, no author UUID) is appended to the **root** back stack, becoming the current entry over `HomeRoute`

#### Scenario: Invoking onOpenPostReply pushes the route with focusReplyComposer = true

- **GIVEN** the `AppShellScreen` call site (`appEntryProvider`) composed over a test root back stack with the Nearby tab selected
- **WHEN** the Nearby card's `onOpenPostReply` is invoked (and again from the Global tab)
- **THEN** in both cases a `PostDetailRoute` carrying the same non-PII display fields with `focusReplyComposer = true` is appended to the **root** back stack

#### Scenario: HomeScreen hoists both callbacks; the appends live at the call site; no per-tab NavDisplay

- **WHEN** inspecting `screens/home/HomeScreen.kt`, `screens/shell/AppShellScreen.kt`, and `screens/routing/AppEntryProvider.kt`
- **THEN** `HomeScreen` takes `onOpenPost` and `onOpenPostReply` as hoisted parameters and holds no back-stack reference, `AppShellScreen` forwards both to the Home-section `HomeScreen`, AND the `backStack.add(PostDetailRoute(...))` appends live at the `AppShellScreen(...)` call site in `appEntryProvider` (the same mechanism as `onOpenComposer`), AND no per-tab `NavDisplay` / tab-root `NavKey` is introduced
