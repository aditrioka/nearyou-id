## ADDED Requirements

### Requirement: Canonical list load-more (infinite-scroll) pattern

Every scrollable list surface in `:mobile:app` that paginates via a cursor SHALL implement load-more uniformly, layering a **third loading dimension** on top of the existing initial-load-vs-refresh split (§ "Canonical list loading and refresh pattern") without ever displaying two indicators of the same dimension at once:

- **Trigger.** A load-more fetch SHALL fire when the user scrolls near the end of the list — detected from `LazyListState` via `derivedStateOf` (so the read is rate-limited to composition, not computed inline every frame, per docs/11 §2.4) — and ONLY when ALL of: a next cursor is available (not end-reached), no load-more is already in flight, the initial load has completed, and no refresh is in flight.
- **Append.** A successful load-more page SHALL be **appended** to the retained list (the scrollable is NEVER torn down) and the current cursor SHALL advance to that page's `nextCursor`. Earlier pages SHALL remain.
- **End-reached.** When a page returns a null/absent cursor (or an empty page), the surface SHALL mark the list end-reached and issue NO further load-more requests; no footer spinner SHALL be shown thereafter.
- **Footer states.** While a load-more page is in flight, a load-more **footer** progress indicator SHALL be shown at the list end — and SHALL NOT be shown simultaneously with the initial-load skeleton or the pull-to-refresh indicator. On a load-more failure, a **non-destructive** load-more error footer with a retry affordance SHALL be shown while the already-loaded items REMAIN rendered (the surface MUST NOT replace the loaded list with a full-screen error); retrying SHALL re-issue the load-more for the same cursor.
- **List keys.** Every `items()` over a paginated list (and the footer item) SHALL declare a stable `key` + `contentType`.
- **Refresh interaction.** A pull-to-refresh (or retry) reload re-fetches the first page and SHALL reset paging state (cleared appended tail, cleared end-reached) — consistent with "pull-to-refresh re-fetches the first page".

#### Scenario: Scroll-near-end triggers exactly one load-more when eligible

- **GIVEN** a paginated list surface in the `Content` state with a non-null next cursor and no load-more in flight
- **WHEN** the user scrolls near the end of the list
- **THEN** exactly one load-more fetch is issued for the retained cursor AND no second load-more is issued while that one is in flight (the in-flight guard holds)

#### Scenario: A loaded page appends and advances the cursor

- **GIVEN** a load-more fetch returns a page of items with a new next cursor
- **THEN** the new items are appended below the existing list (earlier items retained) AND the surface's current cursor advances to the new page's cursor

#### Scenario: A null/empty page marks end-reached and stops further requests

- **GIVEN** a load-more fetch returns a null/absent cursor (or an empty page)
- **THEN** the surface marks the list end-reached, shows no footer spinner, and issues no further load-more requests even on subsequent scroll-to-end

#### Scenario: A load-more error keeps the loaded list and offers retry

- **GIVEN** a list with a loaded first page AND a load-more fetch that fails
- **THEN** the already-loaded items remain rendered AND a non-destructive load-more error footer with a retry affordance is shown (the list is NOT replaced by a full-screen error) AND activating retry re-issues the load-more for the same cursor

#### Scenario: Load-more footer never co-occurs with the skeleton or refresh indicator

- **WHEN** a load-more page is in flight
- **THEN** the load-more footer indicator is shown AND the initial-load skeleton is NOT shown AND the `PullToRefreshBox` `isRefreshing` argument is `false` (the three loading dimensions are mutually exclusive in their indicators)

### Requirement: Shared generic load-more controller

The load-more lifecycle SHALL be implemented in ONE shared, Compose-free controller in commonMain (the `ui/timeline/InlineLikeController` rule-of-three precedent) — generic over the list item type — owning the appended item list, the current cursor, an `isLoadingMore` flag, an `endReached` terminal, a `loadMoreError` footer flag, and a per-instance in-flight guard. Each paginated surface's state holder (ViewModel) SHALL hold its own instance of this controller and supply a `suspend (cursor) -> <page result>` fetch lambda mapping that surface's fetch outcome to a uniform `(items, nextCursor)` page result. Surfaces MUST NOT each re-implement the append / in-flight-guard / cursor-advance / end-reached lifecycle.

#### Scenario: The controller appends, advances the cursor, and terminates on a null cursor

- **GIVEN** the shared controller seeded with a first page (cursor `c1`)
- **WHEN** `loadMore()` runs and the fetch returns a page with cursor `c2`, then a later `loadMore()` returns a page with a null cursor
- **THEN** after the first the list grows and the cursor is `c2`; after the second the list grows and the controller is `endReached` (no further fetch is issued)

#### Scenario: The in-flight guard ignores a concurrent load-more

- **GIVEN** the shared controller with a `loadMore()` in flight (suspending fetch)
- **WHEN** `loadMore()` is invoked again before the first completes
- **THEN** the fetch lambda is invoked exactly once (the second call is ignored by the in-flight guard)

#### Scenario: All five paginated surfaces delegate to the shared controller

- **WHEN** inspecting the load-more implementation and the ViewModels for the Nearby, Following, Global, Notifications, and post-detail-Replies surfaces
- **THEN** ONE shared commonMain controller class implements the append/guard/cursor/end-reached lifecycle AND each of the five ViewModels delegates to its own instance of that class (no per-surface duplicate of the lifecycle)
