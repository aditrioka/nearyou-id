## MODIFIED Requirements

### Requirement: A home-surface FAB opens the composer; existing routing and the Nearby screen are unchanged

`HomeScreen` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/home/HomeScreen.kt`) SHALL render a single `FloatingActionButton` at the **home (tab-host) level** that, when activated, appends `PostCreationRoute` to the **root** navigation back stack (above `HomeRoute`, so the composer overlays the entire surface including the `NavigationBar`). `HomeScreen` SHALL host the Nearby/Following/Global **tab host** as its body (per the `mobile-home-tab-host` capability + the `mobile-nearby-timeline` § "HomeScreen hosts NearbyTimelineScreen" requirement); the Nearby feed is rendered as the Nearby tab's content. The authenticated path SHALL continue to route to `HomeRoute` (the `mobile-auth-signin` routing **target** is unchanged). The FAB is shared across all three tabs (one composer affordance) and MUST NOT be duplicated per tab nor push into a per-tab back stack. `NearbyTimelineScreen` SHALL remain **navigation-free** — it holds no back-stack reference; the FAB + root-back-stack append live in `HomeScreen`. `NearbyTimelineScreen` MAY receive a hoisted `onSeeGlobal` lambda (the empty-state "lihat Global" tab-switch CTA from `mobile-nearby-timeline`), which is host-level tab state, NOT a back-stack reference — so the navigation-free property is preserved.

#### Scenario: HomeScreen renders a compose FAB that pushes the composer onto the root stack

- **WHEN** a test composes the `HomeScreen` tab-host composable under `NearYouTheme` over a test root back stack (or with a recording navigate-to callback) and activates the compose FAB (with any tab selected)
- **THEN** a single `FloatingActionButton` node is present AND activating it appends `PostCreationRoute` to the **root** back stack (the composer surface becomes the current entry, overlaying the tab bar) — never into a per-tab back stack

#### Scenario: NearbyTimelineScreen remains navigation-free

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/timeline/NearbyTimelineScreen.kt`
- **THEN** `NearbyTimelineScreen` holds no back-stack reference and contains no FAB; the FAB + back-stack append live in `HomeScreen`. Any navigation it triggers is via a hoisted lambda (`onSeeGlobal` tab switch) — a host-level tab-state callback, NOT a back-stack push/pop — so the screen adds no back-stack navigation logic
