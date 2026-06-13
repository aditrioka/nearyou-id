# mobile-home-tab-host — Delta Specification

## MODIFIED Requirements

### Requirement: Bottom navigation is a top-level section shell (Home / Notifikasi / Profil)

The authenticated root surface SHALL be an app **section shell** (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/shell/AppShellScreen.kt` or equivalent) rendering a Material 3 `Scaffold` whose `bottomBar` is a `NavigationBar` with exactly three top-level **sections** — **Home**, **Notifikasi**, **Profil** — each labelled via `stringResource` (`Res.string.section_home`, `Res.string.section_notifications`, `Res.string.section_profile`) with a **Material icon** (per `mobile-design-system` § "Material 3 icons …" — a bundled vector drawable, NOT a brand-tinted dot) + `contentDescription` via `stringResource`. This shell `Scaffold` SHALL be the **single inset-owning Scaffold** for the authenticated surface, running edge-to-edge (per `mobile-design-system` § "The app shell owns a single Scaffold and window insets"); section/feed/screen content rendered in its body is inset-free. As of `mobile-timeline-card-redesign`, when the **Home** section is selected the shell `Scaffold`'s `topBar` slot SHALL render a **`CenterAlignedTopAppBar` with the brand logo centered** (per mockup frames 1 + 19): the logo is the bundled vector `Res.drawable.logo_brand_light` under the light scheme and `Res.drawable.logo_brand_dark` under the dark scheme, with `contentDescription = stringResource(Res.string.app_name)`; the app bar is **pinned** (no scroll-collapse behavior). As of `mobile-search-screen`, when the **Home** section is selected that same `CenterAlignedTopAppBar` SHALL render a trailing **search action icon** in its `actions` slot — a Material search icon with `contentDescription = stringResource(Res.string.search_icon_cd)` — that invokes a hoisted `onOpenSearch` lambda (the actual `SearchRoute` root-stack push is wired at the `appEntryProvider` call site per § "The tab host hoists onOpenSearch, wired at the call site to a root-stack SearchRoute push"). The search action icon SHALL be shown only on the Home section (the Notifikasi and Profil sections render NO shell top app bar, so they carry no search action), mirroring the Home-only scoping of the composer FAB. The Notifikasi and Profil sections render NO shell top app bar (their surfaces keep their existing in-body headers). Each section item's label SHALL be **visible in both the selected and unselected states** via readable M3 item theming — the shell's `nearYouNavigationBarItemColors()`, NOT the bare `NavigationBarItemDefaults.colors()` default (per `mobile-design-system` § "Navigation and tab labels are visible in selected and unselected states"). The shell body SHALL render the selected section's content (Home → `HomeScreen`; Notifikasi → the notifications surface per `mobile-notifications-list`; Profil → the deferred placeholder per § "The Profil section renders a deferred placeholder"). The selected section SHALL be a `@Serializable` `Section` enum held in `rememberSaveable` (iOS-safe), defaulting to Home. The shell SHALL be the authenticated root surface (mapped from the root `NavDisplay` via `AppEntryProvider`); the Home section's content (`HomeScreen`) SHALL render under the `HomeRoute` NavEntry scope so the feed ViewModels continue to resolve to `HomeRoute` scope (per `mobile-app-scaffold` § entry decorators), preserving the no-re-fetch invariant. No hardcoded UI string literals SHALL appear in the shell source. The shell SHALL render under `NearYouTheme`.

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

## ADDED Requirements

### Requirement: The tab host hoists onOpenSearch, wired at the call site to a root-stack SearchRoute push

The shell SHALL hoist an `onOpenSearch` callback (no payload — `SearchRoute` is parameterless) invoked by the Home app-bar search action icon (per § "Bottom navigation is a top-level section shell"). Its actual **root** back-stack append SHALL be wired at the `appEntryProvider` call site as `onOpenSearch = { backStack.add(SearchRoute) }`, NOT inside `HomeScreen.kt` / `AppShellScreen.kt` (neither holds a back-stack reference) — exactly the call-site mechanism the existing `onOpenComposer` / `onOpenPost` wiring uses (per § "The tab host hoists onOpenPost, wired at the call site to a root-stack PostDetailRoute push"). `AppShellScreen` SHALL forward `onOpenSearch` to the Home app bar; the appended `SearchRoute` SHALL overlay the section `NavigationBar` (a root-stack entry above `HomeRoute`, mirroring `PostDetailRoute` / `PostCreationRoute`), and SHALL NOT introduce a per-tab `NavDisplay` back stack. This requirement is additive to (and does not modify) the existing `onOpenPost` / `onOpenPostReply` hoist requirement.

#### Scenario: Invoking onOpenSearch pushes SearchRoute onto the root stack

- **GIVEN** the `AppShellScreen` call site (`appEntryProvider`) composed over a test root back stack with the Home section selected
- **WHEN** the Home app bar's search action invokes `onOpenSearch`
- **THEN** a parameterless `SearchRoute` is appended to the **root** back stack, becoming the current entry over `HomeRoute` (overlaying the section `NavigationBar`)

#### Scenario: onOpenSearch is hoisted, not back-stack-bound inside the screens

- **WHEN** inspecting `screens/home/HomeScreen.kt`, `screens/shell/AppShellScreen.kt`, and `screens/routing/AppEntryProvider.kt`
- **THEN** `AppShellScreen` takes/forwards `onOpenSearch` as a hoisted parameter and holds no back-stack reference, AND the `backStack.add(SearchRoute)` append lives at the `AppShellScreen(...)` call site in `appEntryProvider` (the same mechanism as `onOpenComposer`), AND no per-tab `NavDisplay` / tab-root `NavKey` is introduced

#### Scenario: The existing onOpenPost hoist requirement is unchanged

- **WHEN** comparing the live `mobile-home-tab-host` § "The tab host hoists onOpenPost, wired at the call site to a root-stack PostDetailRoute push" requirement before and after this change
- **THEN** that requirement's header and its `onOpenPost` / `onOpenPostReply` scenarios are unmodified by `mobile-search-screen` (the search entry point is added as this separate `onOpenSearch` requirement, avoiding a renamed-header MODIFIED that would not match on archive and that would collide with concurrent feed-tab changes)
