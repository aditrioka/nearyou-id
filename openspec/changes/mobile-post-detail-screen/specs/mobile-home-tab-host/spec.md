## ADDED Requirements

### Requirement: The tab host hoists onOpenPost, wired at the call site to a root-stack PostDetailRoute push

`HomeScreen` SHALL hoist an `onOpenPost(...)` callback (taking a card's non-PII display fields) and pass it into BOTH the Nearby tab content (`NearbyTimelineScreen`) and the Global tab content (`GlobalTimelineScreen`) — exactly as it already hoists `onOpenComposer`. The actual `PostDetailRoute` **root** back-stack append SHALL be wired at the `HomeScreen` call site (in `screens/routing/AppEntryProvider.kt`, where `appEntryProvider` maps `HomeRoute` → `HomeScreen(onOpenComposer = { backStack.add(PostCreationRoute) }, onOpenPost = { … backStack.add(PostDetailRoute(...)) })`), NOT inside `HomeScreen.kt` (which holds no back-stack reference, matching the existing composer-FAB wiring). The appended `PostDetailRoute` SHALL be constructed from exactly the card fields (`postId`, `content`, `cityName`, `distanceM`, `createdAtIso`, `likedByViewer`, `replyCount`; never `latitude`/`longitude`) so the detail surface overlays the `NavigationBar`, NOT introducing a per-tab `NavDisplay` back stack (still deferred per `FOLLOW_UPS mobile-home-tab-host-per-tab-backstacks`). The Following tab (a deferred placeholder) wires no `onOpenPost` (it has no feed/cards).

#### Scenario: Invoking onOpenPost in either feed tab pushes PostDetailRoute onto the root stack

- **GIVEN** the `HomeScreen` call site (`appEntryProvider`) composed over a test root back stack, or `HomeScreen` composed with a recording `onOpenPost` callback, with the Nearby tab selected
- **WHEN** the Nearby card's `onOpenPost` is invoked (and again with the Global tab selected and its card's `onOpenPost`)
- **THEN** in both cases a `PostDetailRoute` carrying the card's display fields (and no `latitude`/`longitude`) is appended to the **root** back stack, becoming the current entry over `HomeRoute`

#### Scenario: HomeScreen hoists onOpenPost; the append lives at the call site; no per-tab NavDisplay

- **WHEN** inspecting `screens/home/HomeScreen.kt` and `screens/routing/AppEntryProvider.kt`
- **THEN** `HomeScreen` takes `onOpenPost` as a hoisted parameter and holds no back-stack reference, AND the `backStack.add(PostDetailRoute(...))` append lives at the `HomeScreen(...)` call site in `appEntryProvider` (the same mechanism as `onOpenComposer`), AND no per-tab `NavDisplay` / tab-root `NavKey` is introduced
