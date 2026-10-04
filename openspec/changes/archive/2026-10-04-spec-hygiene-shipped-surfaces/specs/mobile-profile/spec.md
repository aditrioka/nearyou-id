## ADDED Requirements

### Requirement: The profile re-reads on resume

`ProfileScreen` SHALL re-read its profile on every `ON_RESUME` (a `LifecycleEventEffect(Lifecycle.Event.ON_RESUME)` calling `ProfileViewModel.refresh()`), so a return from another surface shows fresh data — e.g. back from Settings after a username change (`mobile-premium-username` § "A successful change shows the success toast and returns to Settings"), a section switch, or a foreground return. The re-read SHALL use the same `ProfileFlow.loadProfile(userId)` as the initial load (no cache, no new endpoint), SHALL be silent (the current phase stays mounted; no Loading flash), and SHALL obey these rules:

- **Initial load** — a refresh while the initial load is in flight SHALL be a no-op (the first resume lands during the `init` load; no second `GET`).
- **Dedupe** — a refresh while another refresh is running SHALL be a no-op.
- **Follow interplay** — a refresh while a follow/unfollow is in flight SHALL be a no-op (the optimistic toggle value stands), and a follow tap SHALL cancel a running refresh, so a read issued before the follow can never land after it and undo the toggle. Once no follow is in flight, a refresh's result is authoritative: it replaces the profile and clears the optimistic follow overlay (adopting the server's `followedByViewer`).
- **Failure** — a refresh that yields the transport-failure outcome (`ProfileOutcome.NetworkError`) SHALL leave the current phase unchanged. Any other outcome replaces it: `Loaded` refreshes the content (and recovers an `Error` phase to `Content`), and `NotFound` switches to the not-found phase.

#### Scenario: A resume re-read shows a changed username

- **GIVEN** a loaded self profile whose username has since changed on the server
- **WHEN** the screen resumes (e.g. back from Settings)
- **THEN** the profile is re-read for the same user id AND the content renders the new username

#### Scenario: A refresh during the initial load is a no-op

- **GIVEN** the initial profile load is still in flight
- **WHEN** `refresh()` is invoked (the first resume)
- **THEN** no second `loadProfile` call is issued AND the initial load's result renders when it completes

#### Scenario: A refresh while another runs is deduped

- **GIVEN** a loaded profile and a refresh already in flight
- **WHEN** `refresh()` is invoked again
- **THEN** no additional `loadProfile` call is issued

#### Scenario: An in-flight follow survives a refresh

- **GIVEN** an other-user profile with an optimistic follow still in flight
- **WHEN** `refresh()` is invoked
- **THEN** `followedByViewer` keeps the optimistic value AND it settles to the follow's result once the follow completes

#### Scenario: A follow tap cancels a running refresh

- **GIVEN** a refresh in flight that will return the pre-follow state
- **WHEN** the user taps follow before that refresh completes
- **THEN** the refresh is cancelled AND its stale result never replaces the followed state

#### Scenario: After a settled follow the re-read is authoritative

- **GIVEN** a follow that has settled as followed, while the server now reports `followedByViewer = false` (unfollowed elsewhere)
- **WHEN** `refresh()` is invoked
- **THEN** `followedByViewer` becomes `false`

#### Scenario: A NetworkError re-read keeps the current phase

- **GIVEN** a profile in the `Content` phase
- **WHEN** a refresh yields `ProfileOutcome.NetworkError`
- **THEN** the phase stays `Content` with the previously loaded profile

#### Scenario: A re-read recovers from the Error phase

- **GIVEN** a profile in the `Error` phase (the initial load failed)
- **WHEN** a refresh yields `Loaded`
- **THEN** the phase becomes `Content`

#### Scenario: A NotFound re-read replaces the content

- **GIVEN** a profile in the `Content` phase
- **WHEN** a refresh yields `NotFound`
- **THEN** the phase becomes the not-found phase
