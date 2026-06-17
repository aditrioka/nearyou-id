# mobile-design-system Specification

## Purpose

`mobile-design-system` is the cross-cutting Material 3 substrate contract for `:mobile:app` — the durable, screen-agnostic rules every authenticated screen inherits so that atomic per-screen changes stay visually and architecturally sound instead of each reinventing (and drifting on) its own layout, loading, icon, and copy conventions. It governs: single-`Scaffold` window-inset ownership (the app shell owns the only inset-applying `Scaffold`, running edge-to-edge; all section/feed/screen content is inset-free), the Material 3 icon set as the canonical navigation/action/card affordance (bundled vector drawables, not placeholder dots), navigation- and tab-label visibility (readable selected AND unselected states), the canonical list loading/refresh pattern (initial-load skeleton vs. refresh-over-retained-content, never two progress indicators), and the single-language Bahasa Indonesia rule. Future screen capabilities (profile, following feed, chat, search, settings) MODIFY-by-consuming these requirements rather than re-deriving them; the human-readable companion is `docs/03-UX-Design.md` § "Material 3 Design System / Foundation".
## Requirements
### Requirement: The app shell owns a single Scaffold and window insets

The authenticated `:mobile:app` surface SHALL apply window insets in exactly **one** place: the app section shell's `Scaffold` (`AppShellScreen`), running edge-to-edge (the Android entry's `enableEdgeToEdge()` plus the shell `Scaffold`'s `contentWindowInsets`). For the **shell-rendered section surfaces**, the shell `Scaffold`'s `topBar` slot is the only place a top app bar may exist (as of `mobile-timeline-card-redesign` it hosts the Home section's centered brand-logo `CenterAlignedTopAppBar` per `mobile-home-tab-host` § "Bottom navigation is a top-level section shell") — a `topBar` on the single shell Scaffold keeps insets applied exactly once. Root-stack **overlay** screens pushed above `HomeRoute` (e.g. `PostDetailScreen`'s back bar, audit rationale 06-#4) are outside the shell body and continue to own their own surface chrome; this requirement governs the shell and everything rendered inside its body. Every composable rendered inside the shell body — section content, the Home feed tab host, and each feed/timeline screen — SHALL be **inset-free**: it MUST NOT wrap its body in its own `Scaffold` or `TopAppBar`, and it MUST consume the shell's `innerPadding` (via `Modifier.padding(innerPadding)` + `Modifier.consumeWindowInsets(innerPadding)`) so system-bar insets are applied once and not re-applied deeper. This is the substrate fix for the nested-Scaffold defect (a Compose `Scaffold` applies but does not consume insets, so nesting them re-adds the status-bar inset and re-owns content padding).

#### Scenario: Only the shell declares a Scaffold

- **WHEN** inspecting `screens/shell/AppShellScreen.kt`, `screens/home/HomeScreen.kt`, `screens/timeline/NearbyTimelineScreen.kt`, `screens/timeline/GlobalTimelineScreen.kt`, and `screens/notifications/NotificationsScreen.kt`
- **THEN** exactly one `Scaffold` is declared among these files (in `AppShellScreen`) AND the only top app bar among them is the one in that Scaffold's `topBar` slot; `HomeScreen`, the timeline content composables, and the Notifikasi section content (`NotificationsScreen`) declare no `Scaffold` and no `TopAppBar`

#### Scenario: The feed surface applies the system-bar inset exactly once under the shell app bar

- **GIVEN** the authenticated shell composed edge-to-edge with the Home section + a feed tab selected
- **THEN** the shell app bar sits below the status-bar inset, the top of the feed tab row aligns flush under the shell app bar, and no additional status-bar-height gap is introduced by a nested Scaffold or TopAppBar (a single system-bar inset is applied by the shell `Scaffold`)

### Requirement: Material 3 icons are the canonical navigation, action, and card affordance

Bottom-navigation sections, primary actions (the composer FAB), and post-card affordances (location, like, reply) in `:mobile:app` SHALL use Material 3 icon glyphs as their affordance — NOT brand-tinted placeholder dots. The icon glyphs SHALL be delivered as bundled vector-drawable assets in `:shared:resources` (the `logo_brand_*.xml` idiom) accessed via `painterResource(Res.drawable.*)`, so the app ships exactly the glyphs it uses without the heavy `material-icons-extended` artifact. The prior "no material-icons dependency / brand-dot" idiom is superseded for these affordances. As of `mobile-timeline-card-redesign`, the post **time** label renders as plain text in the card's identity header (after the @-handle, per mockup frames 1 + 19) — the clock glyph is REMOVED from the card affordance set (`docs/03-UX-Design.md` § canonical glyph list is amended in the same PR). **Feed tabs are the exception: they are text-only** with the Material 3 `PrimaryTabRow` underline indicator (NO icon, NO dot) — matching the operator's inspiration references (X / Niche-style text tabs).

#### Scenario: Navigation, action, and card affordances render Material icon drawables, not dots

- **WHEN** inspecting `screens/shell/AppShellScreen.kt` (section items), `screens/home/HomeScreen.kt` (composer FAB), and the shared `ui/components` post card
- **THEN** each section item, the composer FAB, and each post-card affordance (location / like / reply) renders a Material icon via `painterResource(Res.drawable.<icon>)` (or an `ImageVector` icon) AND no such affordance is a `Box(...).background(..., CircleShape)` placeholder dot

#### Scenario: Card time label is text-only in the identity header

- **WHEN** the shared post card is rendered
- **THEN** the time label appears as text in the identity header row AND no clock icon node is rendered on the card

#### Scenario: Feed tabs are text-only with an underline indicator (no icons)

- **WHEN** inspecting the `screens/home/HomeScreen.kt` feed-tab composable
- **THEN** each feed `Tab` renders its `stringResource` label as text under a `PrimaryTabRow` underline indicator AND renders NO icon and NO `CircleShape` dot

### Requirement: Navigation and tab labels are visible in selected and unselected states

`NavigationBarItem` and feed `Tab` labels SHALL remain visible in BOTH the selected and unselected states. Feed `Tab`s SHALL use the default `Tab` content color (selected = `primary` = brand cobalt, readable as-is). The bottom-nav `NavigationBarItem`s SHALL apply **readable, brand-aligned M3 content-color tokens explicitly** via `NavigationBarItemDefaults.colors(...)` — a single shared `nearYouNavigationBarItemColors()` helper (`AppShellScreen.kt`). As of Material 3 1.4 the bare `NavigationBarItemDefaults.colors()` default resolves `selectedTextColor` to `secondary` and `indicatorColor` to `secondaryContainer`; now that `NearYouColorScheme` defines those as genuine readable accents (no longer neutralized near-white), the bare default would render a *visible* selected label — so the override is **no longer a readability band-aid**. It is RETAINED as a deliberate **brand-identity** choice: the selected bottom-nav state SHALL use the PRIMARY (brand cobalt) family rather than M3's default `secondary` accent. The applied tokens are `primaryContainer` (indicator pill), `primary` (selected icon — brand cobalt), `onSurface` (selected label), and `onSurfaceVariant` (unselected icon + label) — readable in light and dark. A selected bottom-nav or tab item SHALL never render an invisible (background-colored) label, and the selected nav label SHALL clear WCAG AA contrast (≥ 4.5:1) against the nav surface.

#### Scenario: Selected nav item label is readable (WCAG contrast)

- **GIVEN** a `NavigationBarItem(selected = true)` composed under `NearYouTheme` with the shell's `nearYouNavigationBarItemColors()`
- **THEN** the selected label's resolved content color (read via `LocalContentColor` in the label slot) clears **WCAG AA contrast (≥ 4.5:1)** against BOTH the `surface` and the `surfaceContainer` — a contrast check, NOT a mere inequality (a neutralized near-white-on-white selected label is unequal to the background yet unreadable, and would fail this)

#### Scenario: Bottom-nav applies explicit brand-family tokens; tabs use the default

- **WHEN** inspecting the `NavigationBarItem` and `Tab` call sites
- **THEN** the bottom-nav items get their colors from `nearYouNavigationBarItemColors()` (built on `NavigationBarItemDefaults.colors(...)` with the brand-family tokens above — selected icon = `primary`, indicator = `primaryContainer`, selected label = `onSurface`, unselected = `onSurfaceVariant` — NOT the bare default, which would resolve the selected label to `secondary`), and the feed `Tab`s use the default `Tab` content color (no custom color that resolves to the container/background)

#### Scenario: Selected nav icon uses the brand cobalt primary

- **WHEN** inspecting `nearYouNavigationBarItemColors()` in `AppShellScreen.kt`
- **THEN** `selectedIconColor` resolves to `MaterialTheme.colorScheme.primary` (the brand cobalt `#1E4FD6`), `indicatorColor` to `primaryContainer`, `selectedTextColor` to `onSurface`, and the unselected icon + label to `onSurfaceVariant`; the selected icon color (`primary`) clears at least 3:1 against the indicator pill (`primaryContainer`)

### Requirement: Canonical list loading and refresh pattern

Every scrollable list surface in `:mobile:app` SHALL distinguish **initial load** (no content yet) from **refresh** (a reload while content already exists), and SHALL never display two progress indicators simultaneously:
- **Initial load** → a skeleton/placeholder presentation with at most one in-content progress indicator; the pull-to-refresh indicator is NOT shown.
- **Refresh of existing content** → the pull-to-refresh indicator is shown over the **retained** content list; the list (the scrollable the gesture is attached to) MUST stay mounted, and the in-content initial-load indicator is NOT shown.

A `PullToRefreshBox`'s `isRefreshing` argument SHALL reflect the refresh-of-existing-content state only (not the initial load). The **empty, error, and rate-limit states** (the non-`Content` post-initial-load states) SHALL be rendered inside a scrollable container so the pull-to-refresh gesture remains available from them (a `PullToRefreshBox` requires a scrollable child to recognize the gesture). A refresh triggered from a non-`Content` state SHALL **retain that state** (it MUST NOT flip back to the initial-load skeleton) while showing the pull-to-refresh indicator.

#### Scenario: Pull-to-refresh is available from a non-Content state

- **GIVEN** a list surface in a non-`Content` post-load state (e.g. empty or error) with a counting fake
- **WHEN** the pull-to-refresh gesture is performed
- **THEN** the reload fetch is invoked (the empty/error state is rendered inside a scrollable so the gesture is recognized) AND the state remains the same non-`Content` state during the refresh (it does NOT flip to the initial-load skeleton), with the pull-to-refresh indicator shown

#### Scenario: Initial load shows one indicator, no pull-to-refresh spinner

- **WHEN** a list surface is in its initial-load state (no content yet)
- **THEN** the rendered tree shows the skeleton/loading presentation with a single in-content indicator AND the `PullToRefreshBox` `isRefreshing` argument is `false`

#### Scenario: Refresh keeps content mounted and shows only the pull-to-refresh spinner

- **GIVEN** a list surface with loaded content
- **WHEN** a pull-to-refresh (or retry) reload is in flight
- **THEN** the content list remains mounted (the scrollable is not torn down) AND the `PullToRefreshBox` `isRefreshing` argument is `true` AND no separate in-content full-screen `CircularProgressIndicator` is rendered (exactly one progress indicator total)

### Requirement: User-facing labels are single-language Bahasa Indonesia

All user-facing labels across `:mobile:app` SHALL be a single language — Bahasa Indonesia — with no mixed English/Indonesian within the same surface, and all SHALL be sourced via `:shared:resources` `stringResource(Res.string.<name>)`. In particular the feed tab labels (previously English: "Nearby"/"Following"/"Global") SHALL be Bahasa Indonesia to match the Bahasa Indonesia bottom-nav section labels (Beranda/Notifikasi/Profil).

#### Scenario: Feed tabs and nav sections are the same language

- **WHEN** inspecting the rendered feed tab labels and bottom-nav section labels
- **THEN** all are Bahasa Indonesia (no English label remains among the navigation/tab labels) AND each is sourced via `stringResource(Res.string.<name>)`

### Requirement: Runtime user-selectable language switching is deferred

This capability SHALL NOT introduce runtime user-selectable language switching (locale resource variants, a language-preference store, or a settings toggle). The single-language Bahasa Indonesia rule above is satisfied by normalizing the catalog copy, NOT by an in-app language picker. Runtime language switching is **deferred** and tracked by GitHub issue [#203](https://github.com/aditrioka/nearyou-id/issues/203) `mobile-localization-language-switching` (label `follow-up`), which will MODIFY this requirement to introduce the live capability.

#### Scenario: No language picker or locale-variant infrastructure is wired

- **WHEN** inspecting `:mobile:app` and `:shared:resources`
- **THEN** there is no user-facing language selector, no `values-en`/`values-id` locale-variant split, and no language-preference persistence AND GitHub issue [#203](https://github.com/aditrioka/nearyou-id/issues/203) (label `follow-up`) tracks `mobile-localization-language-switching`

### Requirement: Canonical list load-more (infinite-scroll) pattern

Every scrollable list surface in `:mobile:app` that paginates via a cursor SHALL implement load-more uniformly, layering a **third loading dimension** on top of the existing initial-load-vs-refresh split (§ "Canonical list loading and refresh pattern") without ever displaying two indicators of the same dimension at once:

- **Trigger.** A load-more fetch SHALL fire when the user scrolls near the end of the list — detected from `LazyListState` via `derivedStateOf` (so the read is rate-limited to composition, not computed inline every frame, per docs/11 §2.4) — and ONLY when ALL of: a next cursor is available (not end-reached), no load-more is already in flight, the initial load has completed, and no refresh is in flight.
- **Append.** A successful load-more page SHALL be **appended** to the retained list (the scrollable is NEVER torn down) and the current cursor SHALL advance to that page's `nextCursor`. Earlier pages SHALL remain.
- **End-reached.** When a page returns a null/absent cursor (or an empty page), the surface SHALL mark the list end-reached and issue NO further load-more requests; no footer spinner SHALL be shown thereafter.
- **Footer states.** While a load-more page is in flight, a load-more **footer** progress indicator SHALL be shown at the list end — and SHALL NOT be shown simultaneously with the initial-load skeleton or the pull-to-refresh indicator. On a load-more failure, a **non-destructive** load-more error footer with a retry affordance SHALL be shown while the already-loaded items REMAIN rendered (the surface MUST NOT replace the loaded list with a full-screen error); retrying SHALL re-issue the load-more for the same cursor.
- **Diagnostic discipline.** A load-more failure's diagnostic logging SHALL follow the surface's existing first-page discipline — exception **type** / HTTP **status** only, never `cause.message`, the response body, a coordinate, or a token. The load-more path MUST NOT widen logging (e.g. a load-more timeout whose message embeds a request URL carrying `?lat=&lng=` MUST be logged by type, not message).
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

