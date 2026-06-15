## RENAMED Requirements

- FROM: `### Requirement: Pull-to-refresh re-fetches the first page; infinite scroll is deferred`
- TO: `### Requirement: Pull-to-refresh re-fetches the first page; cursor load-more is wired`

## MODIFIED Requirements

### Requirement: Pull-to-refresh re-fetches the first page; cursor load-more is wired

The screen SHALL provide pull-to-refresh (Material 3 `PullToRefreshBox` or equivalent) that re-invokes the first-page fetch. `next_cursor` SHALL be parsed and retained on the `Loaded` outcome AND SHALL drive cursor-based load-more per the § "Notifications list wires cursor load-more" requirement (which follows `mobile-design-system` § "Canonical list load-more (infinite-scroll) pattern"). A pull-to-refresh (or retry) reload re-fetches the first page and SHALL reset paging state — any appended later pages are dropped and the end-reached flag is cleared.

#### Scenario: Pull-to-refresh re-invokes the fetch

- **GIVEN** a `FakeNotificationsFlow` counting fetch invocations
- **WHEN** the pull-to-refresh gesture is triggered after the initial load
- **THEN** the fetch is invoked again (invocation count increases) for the first page

#### Scenario: Refresh resets paging to the first page

- **GIVEN** the notifications list with a first page plus at least one appended load-more page
- **WHEN** a pull-to-refresh reload completes
- **THEN** the list shows a fresh first page (the previously appended later pages are dropped) AND the end-reached flag is cleared so load-more can run again

## ADDED Requirements

### Requirement: Notifications list wires cursor load-more

`NotificationsViewModel` SHALL append subsequent notification pages via the shared load-more controller following `mobile-design-system` § "Canonical list load-more (infinite-scroll) pattern": scrolling near the end of the notifications `LazyColumn` (tag `notificationsList`) issues a follow-up `GET /api/v1/notifications` carrying the retained `cursor` (and the same `unread` filter the first page used), appends the page's notification rows below the existing list, advances the cursor, stops at a null cursor (end-reached), and shows the load-more footer / non-destructive retry-on-error per the canonical pattern. The in-place **mark-read** and **mark-all-read** optimistic mutations SHALL continue to operate over the GROWN list (a row from any appended page can be marked read, and mark-all-read flips every loaded row including appended ones). The unread-badge count remains the shell's separate one-shot concern (not recomputed here). The PII discipline (no `actor_user_id` / `target_id` / `body_data` rendered beyond the existing row copy; none logged) is unchanged on appended rows.

#### Scenario: Scrolling near the end issues a cursor-bearing follow-up

- **GIVEN** the notifications list loaded with a first page whose `Loaded.nextCursor = "c1"` AND a MockEngine/fake capturing requests
- **WHEN** the user scrolls near the end of the list
- **THEN** exactly one follow-up `GET /api/v1/notifications` is issued carrying `cursor=c1`

#### Scenario: The second page appends below the first and advances the cursor

- **GIVEN** a fake returning a second page of notification rows with `nextCursor = "c2"` for `cursor = "c1"`
- **WHEN** load-more completes
- **THEN** the second page's rows are appended below the first page (page-1 rows retained) AND the list's current cursor is `"c2"`

#### Scenario: Mark-read works on an appended row and survives append

- **GIVEN** the notifications list with a first page and an appended second page (both holding unread rows)
- **WHEN** an unread row from the appended second page is tapped (mark-read) AND, separately, mark-all-read is invoked
- **THEN** the tapped appended row flips to read in place AND mark-all-read flips every loaded row (first- and second-page) to read AND no appended row is lost or reordered by the mutation

#### Scenario: A null cursor stops further load-more

- **GIVEN** the notifications list whose latest page returned `nextCursor = null`
- **WHEN** the user scrolls to the end again
- **THEN** no further `GET /api/v1/notifications` request is issued AND no load-more footer spinner is shown (end-reached)

#### Scenario: A load-more failure keeps the loaded rows and offers retry

- **GIVEN** the notifications list with a loaded first page AND a load-more fetch that fails (network/5xx)
- **THEN** the first-page rows remain rendered AND a non-destructive load-more error footer with a retry control is shown AND retry re-issues the `cursor`-bearing follow-up for the same cursor
