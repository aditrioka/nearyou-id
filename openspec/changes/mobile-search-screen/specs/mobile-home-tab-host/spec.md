# mobile-home-tab-host — Delta Specification

## MODIFIED Requirements

### Requirement: Bottom navigation is a top-level section shell (Home / Notifikasi / Profil)

The authenticated root surface SHALL be an app **section shell** (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/shell/AppShellScreen.kt` or equivalent) rendering a Material 3 `Scaffold` whose `bottomBar` is a `NavigationBar` with exactly three top-level **sections** — **Home**, **Notifikasi**, **Profil** — each labelled via `stringResource` (`Res.string.section_home`, `Res.string.section_notifications`, `Res.string.section_profile`) with a **Material icon** (per `mobile-design-system` § "Material 3 icons …" — a bundled vector drawable, NOT a brand-tinted dot) + `contentDescription` via `stringResource`. This shell `Scaffold` SHALL be the **single inset-owning Scaffold** for the authenticated surface, running edge-to-edge (per `mobile-design-system` § "The app shell owns a single Scaffold and window insets"); section/feed/screen content rendered in its body is inset-free. As of `mobile-timeline-card-redesign`, when the **Home** section is selected the shell `Scaffold`'s `topBar` slot SHALL render a **`CenterAlignedTopAppBar` with the brand logo centered** (per mockup frames 1 + 19): the logo is the bundled vector `Res.drawable.logo_brand_light` under the light scheme and `Res.drawable.logo_brand_dark` under the dark scheme, with `contentDescription = stringResource(Res.string.app_name)`; the app bar is **pinned** (no scroll-collapse behavior). As of `mobile-search-screen`, when the **Home** section is selected that same `CenterAlignedTopAppBar` SHALL render a trailing **search action icon** in its `actions` slot — a Material search icon with `contentDescription = stringResource(Res.string.search_icon_cd)` — that invokes a hoisted `onOpenSearch` lambda (the actual `SearchRoute` root-stack push is wired at the `appEntryProvider` call site per § "The tab host hoists onOpenPost / onOpenSearch, wired at the call site to root-stack pushes"). The search action icon SHALL be shown only on the Home section (the Notifikasi and Profil sections render NO shell top app bar, so they carry no search action), mirroring the Home-only scoping of the composer FAB. The Notifikasi and Profil sections render NO shell top app bar (their surfaces keep their existing in-body headers). Each section item's label SHALL be **visible in both the selected and unselected states** via readable M3 item theming — the shell's `nearYouNavigationBarItemColors()`, NOT the bare `NavigationBarItemDefaults.colors()` default (per `mobile-design-system` § "Navigation and tab labels are visible in selected and unselected states"). The shell body SHALL render the selected section's content (Home → `HomeScreen`; Notifikasi → the notifications surface per `mobile-notifications-list`; Profil → the deferred placeholder per § "The Profil section renders a deferred placeholder"). The selected section SHALL be a `@Serializable` `Section` enum held in `rememberSaveable` (iOS-safe), defaulting to Home. The shell SHALL be the authenticated root surface (mapped from the root `NavDisplay` via `AppEntryProvider`); the Home section's content (`HomeScreen`) SHALL render under the `HomeRoute` NavEntry scope so the feed ViewModels continue to resolve to `HomeRoute` scope (per `mobile-app-scaffold` § entry decorators), preserving the no-re-fetch invariant. No hardcoded UI string literals SHALL appear in the shell source. The shell SHALL render under `NearYouTheme`.

#### Scenario: Shell renders three labelled sections with Material icons and defaults to Home

- **WHEN** a test composes the shell under `NearYouTheme` with fakes
- **THEN** the rendered tree contains a bottom `NavigationBar` with selectable nodes whose text matches `stringResource(Res.string.section_home)`, `stringResource(Res.string.section_notifications)`, and `stringResource(Res.string.section_profile)` — each with a Material icon affordance — AND the Home section is selected by default (the Home feed surface is rendered)

#### Scenario: Selected section label is visible (not collapsed to the background)

- **GIVEN** the shell composed with the Home section selected
- **THEN** the selected section's label node is present AND its resolved content color is a non-background Material 3 token (the label is not invisible)

#### Scenario: Selecting a section swaps the shell body

- **GIVEN** the shell composed with the Home section selected (default)
- **WHEN** the test activates the Notifikasi section
- **THEN** the shell body renders the notifications surface AND no longer renders the Home feed surface

#### Scenario: Selected section survives a saved-state round-trip

- **GIVEN** a commonTest that sets the selected `Section` and saves + restores it via the `rememberSaveable` saver
- **WHEN** the saved value is restored
- **THEN** the restored selection equals the original `Section` (no `SerializationException`) — proving the iOS-safe saved-state path

#### Scenario: Home section shows the centered brand-logo app bar

- **GIVEN** the shell composed with the Home section selected
- **THEN** the tree contains a top app bar node whose logo image has `contentDescription = stringResource(Res.string.app_name)` AND the app bar is rendered by the shell `Scaffold`'s `topBar` slot (no Scaffold/TopAppBar is declared by `HomeScreen` or the timeline screens)

#### Scenario: Logo asset follows the active scheme

- **WHEN** the shell is composed under `NearYouTheme` light and again under `NearYouTheme` dark with the Home section selected
- **THEN** the light composition renders `logo_brand_light` and the dark composition renders `logo_brand_dark`

#### Scenario: Non-Home sections render no shell top app bar

- **WHEN** the test activates the Notifikasi section (and again the Profil section)
- **THEN** the shell renders no top app bar node (the notifications surface's own in-body header is unaffected) AND no search action icon is rendered (the search action lives only on the Home app bar)

#### Scenario: Home app bar renders the search action icon that invokes onOpenSearch

- **GIVEN** the shell composed with the Home section selected and a recording `onOpenSearch` callback
- **WHEN** the search action icon (`contentDescription = stringResource(Res.string.search_icon_cd)`) in the Home `CenterAlignedTopAppBar` `actions` slot is activated
- **THEN** the recording `onOpenSearch` fires exactly once AND the search action icon is present only while the Home section is selected

### Requirement: The tab host hoists onOpenPost / onOpenSearch, wired at the call site to root-stack pushes

`HomeScreen` SHALL hoist an `onOpenPost(...)` callback (taking a card's non-PII display fields) and pass it into BOTH the Nearby tab content (`NearbyTimelineScreen`) and the Global tab content (`GlobalTimelineScreen`) — exactly as it already hoists `onOpenComposer`. As of `mobile-inline-post-actions` it SHALL additionally hoist an `onOpenPostReply(...)` callback (same non-PII display-field payload — the feed cards' reply shortcut) into both feed tabs. As of `mobile-search-screen`, the shell SHALL additionally hoist an `onOpenSearch` callback (no payload — `SearchRoute` is parameterless) invoked by the Home app-bar search action icon (per § "Bottom navigation is a top-level section shell"); its actual **root** back-stack append SHALL be wired at the `appEntryProvider` call site as `onOpenSearch = { backStack.add(SearchRoute) }`, NOT inside `HomeScreen.kt` / `AppShellScreen.kt` (neither holds a back-stack reference), exactly like `onOpenComposer`. The actual `PostDetailRoute` **root** back-stack appends SHALL be wired at the **shell** call site (in `screens/routing/AppEntryProvider.kt`, where — after the section-shell restructure of § "Bottom navigation is a top-level section shell" — `appEntryProvider` maps `HomeRoute` → `AppShellScreen(onOpenComposer = { backStack.add(PostCreationRoute) }, onOpenPost = { … backStack.add(PostDetailRoute(...)) }, onOpenPostReply = { … backStack.add(PostDetailRoute(..., focusReplyComposer = true)) }, onOpenSearch = { backStack.add(SearchRoute) })`; `AppShellScreen` forwards them to the Home section's `HomeScreen` / Home app bar), NOT inside `HomeScreen.kt` / `AppShellScreen.kt` (neither holds a back-stack reference, matching the existing composer-FAB wiring). The appended `PostDetailRoute` SHALL be constructed from exactly the card fields (`postId`, `content`, `cityName`, `distanceM`, `createdAtIso`, `likedByViewer`, `replyCount`, `authorUsername`, `authorDisplayName`; never `latitude`/`longitude`, never the author UUID) — with `focusReplyComposer = true` when constructed from `onOpenPostReply` and the default `false` when constructed from the whole-card `onOpenPost` — so the detail surface overlays the section `NavigationBar`, NOT introducing a per-tab `NavDisplay` back stack (still deferred per GitHub issue [#189](https://github.com/aditrioka/nearyou-id/issues/189) `mobile-home-tab-host-per-tab-backstacks` (label `follow-up`)). The Following tab (a deferred placeholder) wires no `onOpenPost` and no `onOpenPostReply` (it has no feed/cards).

#### Scenario: Invoking onOpenPost in either feed tab pushes PostDetailRoute onto the root stack

- **GIVEN** the `AppShellScreen` call site (`appEntryProvider`) composed over a test root back stack, or `HomeScreen` composed with a recording `onOpenPost` callback, with the Nearby tab selected
- **WHEN** the Nearby card's `onOpenPost` is invoked (and again with the Global tab selected and its card's `onOpenPost`)
- **THEN** in both cases a `PostDetailRoute` carrying the card's display fields including `authorUsername`/`authorDisplayName` with `focusReplyComposer = false` (and no `latitude`/`longitude`, no author UUID) is appended to the **root** back stack, becoming the current entry over `HomeRoute`

#### Scenario: Invoking onOpenPostReply pushes the route with focusReplyComposer = true

- **GIVEN** the `AppShellScreen` call site (`appEntryProvider`) composed over a test root back stack with the Nearby tab selected
- **WHEN** the Nearby card's `onOpenPostReply` is invoked (and again from the Global tab)
- **THEN** in both cases a `PostDetailRoute` carrying the same non-PII display fields with `focusReplyComposer = true` is appended to the **root** back stack

#### Scenario: Invoking onOpenSearch pushes SearchRoute onto the root stack

- **GIVEN** the `AppShellScreen` call site (`appEntryProvider`) composed over a test root back stack with the Home section selected
- **WHEN** the Home app bar's search action invokes `onOpenSearch`
- **THEN** a parameterless `SearchRoute` is appended to the **root** back stack, becoming the current entry over `HomeRoute` (overlaying the section `NavigationBar`)

#### Scenario: HomeScreen hoists the callbacks; the appends live at the call site; no per-tab NavDisplay

- **WHEN** inspecting `screens/home/HomeScreen.kt`, `screens/shell/AppShellScreen.kt`, and `screens/routing/AppEntryProvider.kt`
- **THEN** `HomeScreen` takes `onOpenPost` and `onOpenPostReply` as hoisted parameters and holds no back-stack reference, `AppShellScreen` forwards them (and `onOpenSearch`) to the Home-section `HomeScreen` / Home app bar, AND the `backStack.add(PostDetailRoute(...))` and `backStack.add(SearchRoute)` appends live at the `AppShellScreen(...)` call site in `appEntryProvider` (the same mechanism as `onOpenComposer`), AND no per-tab `NavDisplay` / tab-root `NavKey` is introduced
