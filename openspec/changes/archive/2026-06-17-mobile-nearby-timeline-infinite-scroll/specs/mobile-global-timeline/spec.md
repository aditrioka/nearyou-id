## RENAMED Requirements

- FROM: `### Requirement: Pull-to-refresh re-fetches the first page; infinite scroll is deferred`
- TO: `### Requirement: Pull-to-refresh re-fetches the first page; cursor load-more is wired`

## MODIFIED Requirements

### Requirement: Pull-to-refresh re-fetches the first page; cursor load-more is wired

The screen SHALL provide pull-to-refresh (Material 3 `PullToRefreshBox` or equivalent) that re-invokes the first-page fetch via the ViewModel's `reload()`. During a refresh the already-loaded content SHALL remain mounted (the scrollable is never torn down); the `PullToRefreshBox` `isRefreshing` argument SHALL reflect the **refresh-of-existing-content** state only, NOT the initial load (per `mobile-design-system` § "Canonical list loading and refresh pattern"). `next_cursor` SHALL be parsed and retained on the `Loaded` outcome AND SHALL drive cursor-based load-more per the § "Global feed wires cursor load-more" requirement (which follows `mobile-design-system` § "Canonical list load-more (infinite-scroll) pattern"). A pull-to-refresh (or retry) reload re-fetches the first page and SHALL reset paging state — any appended later pages are dropped and the end-reached flag is cleared.

#### Scenario: Pull-to-refresh re-invokes the fetch and keeps content visible

- **GIVEN** a `FakeGlobalTimelineFlow` counting fetch invocations, the screen in the `Content` state
- **WHEN** the pull-to-refresh gesture is triggered after the initial load
- **THEN** the fetch is invoked again (invocation count increases) for the first page AND the existing post-card list remains rendered during the refresh (the list is not replaced by the loading skeleton)

#### Scenario: Initial load does not show the pull-to-refresh spinner

- **WHEN** the screen is in its initial-load state
- **THEN** the `PullToRefreshBox` `isRefreshing` argument is `false` (only the skeleton/initial indicator shows) — exactly one progress indicator total

#### Scenario: Pull-to-refresh works from the empty / error state

- **GIVEN** the screen in the empty or error state (a non-`Content` post-load state) with a counting `FakeGlobalTimelineFlow`
- **WHEN** the pull-to-refresh gesture is performed
- **THEN** the reload fetch is invoked (the empty/error state is rendered inside a scrollable so the gesture is recognized, per `mobile-design-system` § "Canonical list loading and refresh pattern") AND the state remains that same non-`Content` state during the refresh

#### Scenario: Refresh resets paging to the first page

- **GIVEN** the Global feed with a first page plus at least one appended load-more page
- **WHEN** a pull-to-refresh reload completes
- **THEN** the list shows a fresh first page (the previously appended later pages are dropped) AND the end-reached flag is cleared so load-more can run again

## ADDED Requirements

### Requirement: Global feed wires cursor load-more

`GlobalTimelineViewModel` SHALL append subsequent Global pages via the shared load-more controller following `mobile-design-system` § "Canonical list load-more (infinite-scroll) pattern": scrolling near the end issues a follow-up `GET /api/v1/timeline/global` carrying the retained `cursor` (the Global feed carries NO `lat`/`lng`/`radius_m` — cursor only), appends the page's posts below the existing list, advances the cursor, stops at a null cursor (end-reached), and shows the load-more footer / non-destructive retry-on-error per the canonical pattern. Load-more pages SHALL NOT re-evaluate `upsell` (the soft/hard rate-limit state is a first-page concern); a load-more page returning empty posts is treated as end-reached. The `HomeRoute`-scoped ViewModel scoping, the inline-like seam, and the PII discipline are unchanged.

#### Scenario: Scrolling near the end issues a cursor-bearing follow-up

- **GIVEN** the Global feed loaded with a first page whose `Loaded.nextCursor = "c1"` AND a MockEngine/fake capturing requests
- **WHEN** the user scrolls near the end of the list
- **THEN** exactly one follow-up `GET /api/v1/timeline/global` is issued carrying `cursor=c1` AND it carries NO `lat`/`lng`/`radius_m` parameter

#### Scenario: The second page appends below the first and advances the cursor

- **GIVEN** a fake returning a second page of posts with `nextCursor = "c2"` for `cursor = "c1"`
- **WHEN** load-more completes
- **THEN** the second page's posts are appended below the first page (page-1 posts retained) AND the feed's current cursor is `"c2"` AND the inline-like / open-detail affordances work on appended cards exactly as on first-page cards

#### Scenario: A null cursor stops further load-more

- **GIVEN** the Global feed whose latest page returned `nextCursor = null`
- **WHEN** the user scrolls to the end again
- **THEN** no further `GET /api/v1/timeline/global` request is issued AND no load-more footer spinner is shown (end-reached)

#### Scenario: A load-more failure keeps the loaded posts and offers retry

- **GIVEN** the Global feed with a loaded first page AND a load-more fetch that fails (network/5xx)
- **THEN** the first-page posts remain rendered AND a non-destructive load-more error footer with a retry control is shown AND retry re-issues the `cursor`-bearing follow-up for the same cursor
