## MODIFIED Requirements

### Requirement: PostCreationScreen renders the composer surface

The mobile app SHALL ship a composable `PostCreationScreen` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/post/PostCreationScreen.kt`), mapped from the `PostCreationRoute` `NavKey` by the `entryProvider`, that renders the post composer. The screen SHALL display: (a) a top-bar/title via `stringResource(Res.string.post_create_title)`; (b) a multiline content input field whose placeholder is `stringResource(Res.string.post_create_content_placeholder)`; (c) a live character counter via `stringResource(Res.string.post_create_char_counter)` formatted with the current Unicode-code-point count; (d) a "Posting" CTA via `stringResource(Res.string.cta_post)` that is disabled while the content is empty/over-limit/in-flight; (e) the loading / success / per-error states per the § "Screen state mapping" requirement. No hardcoded UI string literals SHALL appear in the screen source. The screen SHALL render under `NearYouTheme` (light/dark).

#### Scenario: Initial render shows title, placeholder, zero counter, disabled CTA

- **WHEN** a test composes the `PostCreationScreen` composable under `NearYouTheme` with a `FakeCreatePostFlow` and no text entered
- **THEN** the rendered tree contains a node whose text matches `stringResource(Res.string.post_create_title)` AND a field showing the `post_create_content_placeholder` text AND a counter node reflecting a count of `0` AND the "Posting" CTA is present in a disabled state

#### Scenario: No hardcoded UI strings in PostCreationScreen source

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/post/PostCreationScreen.kt`
- **THEN** every `Text(...)` / placeholder / `contentDescription = ...` / similar UI-string-bearing call site sources its text via `stringResource(Res.string.<name>)`; zero literal string arguments appear in such call sites

### Requirement: A home-surface FAB opens the composer; existing routing and the Nearby screen are unchanged

`HomeScreen` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/home/HomeScreen.kt`) SHALL render a `FloatingActionButton` that, when activated, appends `PostCreationRoute` to the navigation back stack (the Nav3 equivalent of a push). `HomeScreen` SHALL continue to host the Nearby feed as its body (the `mobile-nearby-timeline` § "HomeScreen hosts NearbyTimelineScreen" requirement is preserved) and the authenticated path SHALL continue to route to `HomeRoute` (the `mobile-auth-signin` routing **target** is unchanged; only the back-stack mechanism is migrated by `mobile-nav-swap-to-navigation3`). `NearbyTimelineScreen` SHALL remain **navigation-free** — it gains no back-stack reference; the FAB + back-stack append live in `HomeScreen` (the `mobile-nearby-timeline` capability gains no behavioral delta beyond the `Screen`→composable conversion the nav swap applies uniformly).

#### Scenario: HomeScreen renders a compose FAB that pushes the composer

- **WHEN** a test composes the `HomeScreen` composable under `NearYouTheme` hosted in a `NavDisplay` over a test back stack (or with a recording navigate-to callback) and activates the compose FAB
- **THEN** a `FloatingActionButton` node is present AND activating it appends `PostCreationRoute` to the back stack (the composer surface becomes the current entry)

#### Scenario: NearbyTimelineScreen remains navigation-free

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/timeline/NearbyTimelineScreen.kt`
- **THEN** `NearbyTimelineScreen` holds no back-stack reference and contains no FAB / navigation affordance (the FAB + back-stack append live in `HomeScreen`); the nav swap only converts it from a Voyager `Screen` to a plain composable, adding no navigation logic

### Requirement: Screen state mapping covers loading, success, and each error, all copy via stringResource

The screen SHALL render the projected state with all copy via `stringResource`:
- **Loading** (`inFlight`) → the CTA shows `stringResource(Res.string.post_create_loading)` and is disabled.
- **Success** → the screen removes its own entry from the back stack (`backStack.removeLastOrNull()`, the Nav3 equivalent of pop) to return to the home surface; no coordinate is rendered.
- **ContentEmpty** → a banner with `stringResource(Res.string.post_create_error_empty)`.
- **ContentTooLong** → a banner with `stringResource(Res.string.post_create_error_too_long)`.
- **LocationOutOfBounds** → a banner with `stringResource(Res.string.post_create_error_location)`.
- **ContentRejected** → a banner with `stringResource(Res.string.post_create_error_moderated)` (generic; MUST NOT echo any matched keyword).
- **LocationUnavailable** → a banner with `stringResource(Res.string.post_create_location_unavailable)` AND a "Buka Pengaturan" control with `stringResource(Res.string.location_open_settings)` that invokes `LocationPermissionController.openAppSettings()`.
- **NetworkError / Error** → a banner with `stringResource(Res.string.signin_error_network)` AND a retry control with `stringResource(Res.string.cta_retry)`.

#### Scenario: Loading shows the loading copy and a disabled CTA
- **WHEN** the screen is in the in-flight state
- **THEN** the CTA node's text matches `stringResource(Res.string.post_create_loading)` AND the CTA is disabled

#### Scenario: ContentRejected shows the keyword-free moderation copy
- **WHEN** the outcome is `ContentRejected`
- **THEN** the rendered tree contains a node whose text matches `stringResource(Res.string.post_create_error_moderated)` AND contains no matched-keyword substring

#### Scenario: LocationUnavailable shows enable-location copy and a settings CTA
- **GIVEN** the outcome is `LocationUnavailable`
- **WHEN** the screen renders AND the "Buka Pengaturan" control is activated
- **THEN** the rendered tree contains a node whose text matches `stringResource(Res.string.post_create_location_unavailable)` AND activating the control invokes `LocationPermissionController.openAppSettings()`

#### Scenario: NetworkError shows network copy and a retry control
- **WHEN** the outcome is `NetworkError`
- **THEN** the rendered tree contains a node whose text matches `stringResource(Res.string.signin_error_network)` AND a clickable node whose text matches `stringResource(Res.string.cta_retry)`

#### Scenario: Success pops back to the home surface
- **GIVEN** a `FakeCreatePostFlow` returning `Success`
- **WHEN** the composer submits successfully within a `NavDisplay` over a test back stack (or with a recording pop callback)
- **THEN** the composer's entry is removed from the back stack (`backStack.removeLastOrNull()`) and the home surface becomes current again

### Requirement: Successful post returns to Home; Nearby auto-refresh on return is deferred

On a `Success` outcome the composer SHALL remove its own entry from the back stack (`backStack.removeLastOrNull()`, the Nav3 equivalent of pop) to return to the home surface, and SHALL NOT signal the Nearby feed to re-fetch; the newly-created post becomes visible on the next manual pull-to-refresh / `ON_RESUME`. Cross-screen auto-refresh-on-return is NOT implemented in this change and is deferred to a follow-up `mobile-post-creation-refresh-nearby-on-return`.

#### Scenario: No Nearby reload is signalled on success

- **WHEN** inspecting the composer's `Success` handling
- **THEN** it removes the composer entry from the back stack (`backStack.removeLastOrNull()`) AND does NOT invoke any Nearby reload / re-fetch trigger (no shared reload signal, and no Nav3 `ResultEventBus` / nav result consumed by the Nearby feed)

#### Scenario: FOLLOW_UPS tracks the Nearby-refresh follow-up

- **WHEN** inspecting `FOLLOW_UPS.md`
- **THEN** the file contains an entry `mobile-post-creation-refresh-nearby-on-return`
