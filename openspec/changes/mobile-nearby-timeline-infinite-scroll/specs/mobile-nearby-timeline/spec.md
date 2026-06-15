## RENAMED Requirements

- FROM: `### Requirement: Pull-to-refresh re-fetches the first page; infinite scroll is deferred`
- TO: `### Requirement: Pull-to-refresh re-fetches the first page; cursor load-more is wired`

## MODIFIED Requirements

### Requirement: Pull-to-refresh re-fetches the first page; cursor load-more is wired

The screen SHALL provide pull-to-refresh (Material 3 `PullToRefreshBox` or equivalent) that re-invokes the first-page fetch via the ViewModel's `reload()`. During a refresh the already-loaded content SHALL remain mounted (the scrollable the gesture is attached to is never torn down — the prior bug, where the in-flight state collapsed the list to a full-screen loader, is removed); the `PullToRefreshBox` `isRefreshing` argument SHALL reflect the **refresh-of-existing-content** state only, NOT the initial load (per `mobile-design-system` § "Canonical list loading and refresh pattern"). `next_cursor` SHALL be parsed and retained on the `Loaded` outcome AND SHALL drive cursor-based load-more per the § "Nearby feed wires cursor load-more reusing the first-page anchor" requirement (which follows `mobile-design-system` § "Canonical list load-more (infinite-scroll) pattern"). A pull-to-refresh (or retry) reload re-fetches the first page and SHALL reset paging state — any appended later pages are dropped and the end-reached flag is cleared.

#### Scenario: Pull-to-refresh re-invokes the fetch and keeps content visible

- **GIVEN** a `FakeNearbyTimelineFlow` counting fetch invocations, the screen in the `Content` state
- **WHEN** the pull-to-refresh gesture is triggered after the initial load
- **THEN** the fetch is invoked again (invocation count increases) for the first page AND the existing post-card list remains rendered during the refresh (the list is not replaced by the loading skeleton)

#### Scenario: Initial load does not show the pull-to-refresh spinner

- **WHEN** the screen is in its initial-load state
- **THEN** the `PullToRefreshBox` `isRefreshing` argument is `false` (only the skeleton/initial indicator shows) — exactly one progress indicator total

#### Scenario: Pull-to-refresh works from the empty / error state

- **GIVEN** the screen in the empty or error state (a non-`Content` post-load state) with a counting `FakeNearbyTimelineFlow`
- **WHEN** the pull-to-refresh gesture is performed
- **THEN** the reload fetch is invoked (the empty/error state is rendered inside a scrollable so the gesture is recognized, per `mobile-design-system` § "Canonical list loading and refresh pattern") AND the state remains that same non-`Content` state during the refresh

#### Scenario: Refresh resets paging to the first page

- **GIVEN** the Nearby feed with a first page plus at least one appended load-more page (so the list holds >1 page and may be end-reached)
- **WHEN** a pull-to-refresh reload completes
- **THEN** the list shows a fresh first page (the previously appended later pages are dropped) AND the end-reached flag is cleared so load-more can run again

## ADDED Requirements

### Requirement: Nearby feed wires cursor load-more reusing the first-page anchor

`NearbyTimelineViewModel` SHALL append subsequent Nearby pages via the shared load-more controller following `mobile-design-system` § "Canonical list load-more (infinite-scroll) pattern": scrolling near the end issues a follow-up `GET /api/v1/timeline/nearby` carrying the retained `cursor`, appends the page's posts below the existing list, advances the cursor, stops at a null cursor (end-reached), and shows the load-more footer / non-destructive retry-on-error per the canonical pattern. Because `GET /api/v1/timeline/nearby` requires `lat`/`lng`/`radius_m` on every request, the load-more request SHALL reuse the **first-page anchor coordinate** (the `lat`/`lng` used for page 1) for every subsequent page rather than acquiring a fresh device fix — the backend cursor is chronological (`createdAt`, `id`), so ordering is anchor-independent and reuse gives a stable radius across pages while avoiding redundant location acquisition. The retained anchor SHALL be held in the ViewModel and SHALL NEVER be rendered or logged (the same PII discipline that already strips the per-post `latitude`/`longitude` from the projection). Load-more pages SHALL NOT re-evaluate `upsell` (the soft/hard rate-limit state is a first-page concern); a load-more page returning empty posts is treated as end-reached. The per-feed `HomeRoute`-scoped ViewModel scoping, the inline-like seam, and the PII discipline are otherwise unchanged.

#### Scenario: Scrolling near the end issues a cursor-bearing follow-up reusing the page-1 anchor

- **GIVEN** the Nearby feed loaded with a first page (anchor `lat=-6.2,lng=106.8`, `radius_m=20000`) whose `Loaded.nextCursor = "c1"` AND a MockEngine/fake capturing requests
- **WHEN** the user scrolls near the end of the list
- **THEN** exactly one follow-up `GET /api/v1/timeline/nearby` is issued carrying `cursor=c1` AND `lat=-6.2`, `lng=106.8`, `radius_m=20000` (the page-1 anchor reused, NOT a re-acquired fix)

#### Scenario: The second page appends below the first and advances the cursor

- **GIVEN** a fake returning a second page of posts with `nextCursor = "c2"` for `cursor = "c1"`
- **WHEN** load-more completes
- **THEN** the second page's posts are appended below the first page (page-1 posts retained) AND the feed's current cursor is `"c2"` AND the inline-like / open-detail affordances work on appended cards exactly as on first-page cards

#### Scenario: A null cursor stops further load-more

- **GIVEN** the Nearby feed whose latest page returned `nextCursor = null`
- **WHEN** the user scrolls to the end again
- **THEN** no further `GET /api/v1/timeline/nearby` request is issued AND no load-more footer spinner is shown (end-reached)

#### Scenario: The retained anchor is never rendered or logged

- **GIVEN** the Nearby feed paginating with a retained anchor coordinate
- **WHEN** inspecting the rendered tree and the diagnostic sink during load-more
- **THEN** no node renders the anchor `latitude`/`longitude` AND no coordinate is passed to the diagnostic log (the anchor is VM-held request state only)

#### Scenario: A load-more error logs exception type only, never the coordinate-bearing message

- **GIVEN** a Nearby load-more request that fails with a timeout whose exception message embeds the request URL (which carries `?lat=&lng=`)
- **THEN** the diagnostic sink logs the exception **type** / status only (e.g. `nearby_loadmore_error: <ExceptionClass>`) AND does NOT log `cause.message`, the response body, or any coordinate (mirroring the shipped first-page `NearbyTimelineRepository` discipline + its `DiagnosticSinkWiringTest` source-scan guard)

#### Scenario: A load-more failure keeps the loaded posts and offers retry

- **GIVEN** the Nearby feed with a loaded first page AND a load-more fetch that fails (network/5xx)
- **THEN** the first-page posts remain rendered AND a non-destructive load-more error footer with a retry control is shown AND retry re-issues the `cursor`-bearing follow-up for the same cursor
