## ADDED Requirements

### Requirement: The Profil section renders the live self profile

The Profil bottom-nav section SHALL render the live self `ProfileScreen` (per `mobile-profile`) — replacing `ProfilePlaceholderScreen` — for the **authenticated self user**, whose `userId` is resolved from the session/auth layer (NOT a nav arg). The self profile SHALL be rendered **inset-free** in the shell body (it declares no own `Scaffold`/`TopAppBar`; the shell renders no top app bar on the Profil section per § "Bottom navigation is a top-level section shell"), and SHALL issue the profile read (`GET /api/v1/users/{self_id}`) so the section shows real identity / bio / counts. Because the read returns `isSelf = true`, the self profile shows no follow/block/report actions (per `mobile-profile` § "Self vs other-user rendering is driven by isSelf"). The Profil section continues to render NO composer FAB (per § "FAB is absent on the Notifikasi and Profil sections").

#### Scenario: The Profil section renders the self profile, not the placeholder

- **GIVEN** the shell composed with the Profil section selected and a MockEngine returning the self profile (`isSelf = true`)
- **WHEN** the Profil section is rendered
- **THEN** the live self `ProfileScreen` is shown (identity / bio / counts) AND the "Profil segera hadir." placeholder copy is NOT present AND no follow/block/report action is rendered

#### Scenario: The Profil section still shows no composer FAB

- **GIVEN** the shell composed with the Profil section selected
- **WHEN** the section is rendered
- **THEN** no composer `FloatingActionButton` is present (the FAB remains Home-section-only)

### Requirement: The tab host hoists onOpenProfile, wired at the call site to a root-stack ProfileRoute push

`HomeScreen` SHALL hoist an `onOpenProfile(authorUserId: String)` callback and pass it into BOTH the Nearby tab content (`NearbyTimelineScreen`) and the Global tab content (`GlobalTimelineScreen`) — exactly as it already hoists `onOpenPost` / `onOpenComposer`. The actual `ProfileRoute(authorUserId)` **root** back-stack append SHALL be wired at the `HomeScreen` call site (in `screens/routing/AppEntryProvider.kt`, where `appEntryProvider` maps `HomeRoute` → `HomeScreen(...)`), NOT inside `HomeScreen.kt` (which holds no back-stack reference, matching the existing `onOpenPost` wiring). The appended `ProfileRoute` SHALL carry only the `authorUserId` (the navigation resource key — never `latitude`/`longitude`, never a token) so the profile surface overlays the `NavigationBar`. `appEntryProvider` SHALL also map `ProfileRoute` → `ProfileScreen` (other-user mode). The Following tab (a deferred placeholder) and the Notifikasi / Profil sections wire no `onOpenProfile` for cards (they host no feed cards).

#### Scenario: Invoking onOpenProfile in either feed tab pushes ProfileRoute onto the root stack

- **GIVEN** the `HomeScreen` call site (`appEntryProvider`) composed over a test root back stack, or `HomeScreen` composed with a recording `onOpenProfile` callback, with the Nearby tab selected
- **WHEN** a card's `onOpenProfile` is invoked with an `authorUserId` (and again with the Global tab selected)
- **THEN** in both cases a `ProfileRoute` carrying that `authorUserId` (and no coordinate, no token) is appended to the **root** back stack, becoming the current entry over `HomeRoute`

#### Scenario: HomeScreen hoists onOpenProfile; the append lives at the call site

- **WHEN** inspecting `screens/home/HomeScreen.kt` and `screens/routing/AppEntryProvider.kt`
- **THEN** `HomeScreen` takes `onOpenProfile` as a hoisted parameter and holds no back-stack reference, AND the `backStack.add(ProfileRoute(...))` append plus the `ProfileRoute` → `ProfileScreen` mapping live at the `appEntryProvider` call site (the same mechanism as `onOpenPost`)

## REMOVED Requirements

### Requirement: The Profil section renders a deferred placeholder

**Reason**: Un-deferred — this change ships the live profile surface (`mobile-profile`), fulfilling the original requirement's own stated intent ("a separate future change … will MODIFY this requirement to introduce the live surface"; GitHub issue [#196](https://github.com/aditrioka/nearyou-id/issues/196)).
**Migration**: Replaced by § "The Profil section renders the live self profile" (ADDED above). `ProfilePlaceholderScreen.kt` is removed; the Profil section now renders the live self `ProfileScreen`.
