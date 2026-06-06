## ADDED Requirements

### Requirement: The tab host wires onOpenPost to a root-stack PostDetailRoute push

The tab host SHALL pass an `onOpenPost(...)` callback into BOTH the Nearby tab content (`NearbyTimelineScreen`) and the Global tab content (`GlobalTimelineScreen`). When invoked with a card's non-PII display fields, the host SHALL append a `PostDetailRoute` (constructed from exactly those fields — `postId`, `content`, `cityName`, `distanceM`, `createdAtIso`, `likedByViewer`, `replyCount`; never `latitude`/`longitude`) to the **root** back stack (above `HomeRoute`), so the detail surface overlays the `NavigationBar` — mirroring the composer FAB's existing root-stack push and NOT introducing a per-tab `NavDisplay` back stack (still deferred per `FOLLOW_UPS mobile-home-tab-host-per-tab-backstacks`). The Following tab (a deferred placeholder) wires no `onOpenPost` (it has no feed/cards).

#### Scenario: Tapping a card in either feed tab pushes PostDetailRoute onto the root stack

- **GIVEN** the tab host composed over a test root back stack with the Nearby tab selected
- **WHEN** the Nearby card's `onOpenPost` is invoked (and again with the Global tab selected and its card's `onOpenPost`)
- **THEN** in both cases a `PostDetailRoute` carrying the card's display fields (and no `latitude`/`longitude`) is appended to the **root** back stack, becoming the current entry over `HomeRoute`

#### Scenario: No per-tab NavDisplay is introduced for detail navigation

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/home/HomeScreen.kt`
- **THEN** the detail push targets the root back stack (the same mechanism as the composer FAB) AND no per-tab `NavDisplay` / tab-root `NavKey` is introduced
