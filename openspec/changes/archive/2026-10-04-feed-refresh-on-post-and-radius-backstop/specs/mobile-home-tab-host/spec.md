## MODIFIED Requirements

### Requirement: Tab switching preserves each tab's state and never re-fetches

Switching between **feed tabs** within the Home section SHALL preserve the selected-feed-tab value and each feed's already-loaded state. Because the Nearby, Following, and Global feed load-state ViewModels are scoped to the `HomeRoute` NavEntry (`mobile-nearby-timeline` § "Nearby feed load state is scoped …", `mobile-following-timeline` § "Following feed load state is scoped …", and `mobile-global-timeline` § "Global feed load state is scoped …") and each feed screen composes directly under that scope, leaving a feed tab and returning SHALL NOT trigger a re-fetch (the previously loaded posts render immediately). The one exception is a successful post: the HomeRoute feed reload key changes, and a Nearby or Global page that was off-screen re-fetches page 1 once when it is next shown (`mobile-post-creation` § "Successful post returns to Home and refreshes the Nearby and Global feeds"). Following never does. Switching **bottom-nav sections** (Home ↔ Notifikasi ↔ Profil) and returning to Home SHALL likewise preserve the Home feeds' loaded state (the Home section content is not torn down and re-fetched on section switch).

#### Scenario: Returning to a feed tab does not re-fetch

- **GIVEN** a commonTest with a `FakeNearbyTimelineFlow` + `FakeFollowingTimelineFlow` + `FakeGlobalTimelineFlow` counting fetch invocations, the Home section composed with Nearby selected (one Nearby fetch having occurred)
- **WHEN** the test switches to the Following feed tab (first Following fetch occurs), then the Global feed tab (first Global fetch occurs), then back to Nearby, then back to Following, then back to Global
- **THEN** the Nearby fetch count remains 1 AND the Following fetch count remains 1 AND the Global fetch count remains 1 (no re-fetch on feed-tab return)

#### Scenario: Returning to the Home section does not re-fetch the feeds

- **GIVEN** the shell composed with the Home section selected and the Nearby feed loaded once
- **WHEN** the test switches to the Notifikasi section and back to Home
- **THEN** the Nearby feed's fetch count is unchanged (the Home feeds are not re-fetched on section return)
