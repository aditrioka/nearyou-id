# mobile-auth-signin — delta for mobile-mockup-visual-conformance

## MODIFIED Requirements

### Requirement: SignInScreen renders Google Sign-In entry point

The mobile app SHALL ship a composable `SignInScreen` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/auth/SignInScreen.kt`), mapped from the `SignInRoute` `NavKey` by the `entryProvider`, that renders the unauthenticated entry surface. The screen SHALL display: (a) the brand logo via `painterResource(Res.drawable.logo_brand_{light,dark})` (theme-aware per `isSystemInDarkTheme()` consistent with [`HomeScreen`](../../../../mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/home/HomeScreen.kt)), rendered large (96dp per mockup frame 13) as the screen's sole header — as of `mobile-mockup-visual-conformance` the screen renders NO text title (mockup frame 13: "Logo brand tanpa teks"); the `signin_screen_title` string REMAINS in the `:shared:resources` catalog (retained-in-catalog pattern) but is not rendered by this screen; (b) a primary call-to-action button consumed via `stringResource(Res.string.cta_signin_google)`; (c) a footnote consumed via `stringResource(Res.string.account_separation_disclosure)`. No hardcoded UI string literals SHALL appear in the screen source.

#### Scenario: Initial render shows the Google Sign-In CTA

- **WHEN** a `commonTest` runs `runComposeUiTest { setContent { NearYouTheme { SignInScreen(...) } } }` against a fresh composition with no in-flight auth state
- **THEN** the rendered tree contains a node whose text matches the runtime value of `stringResource(Res.string.cta_signin_google)` (i.e., "Masuk dengan Google") AND the node is clickable

#### Scenario: Initial render shows the disclosure and no screen title

- **WHEN** `SignInScreen` is composed
- **THEN** the rendered tree contains a node whose text matches the runtime value of `stringResource(Res.string.account_separation_disclosure)` AND contains NO node whose text matches the runtime value of `stringResource(Res.string.signin_screen_title)` (the title is no longer rendered per mockup frame 13)

#### Scenario: signin_screen_title is retained in the shared catalog

- **WHEN** inspecting `shared/resources/src/commonMain/composeResources/values/strings.xml` and `SharedStringsCatalogTest`
- **THEN** the `<string name="signin_screen_title">` entry still exists with its shipped value `"Masuk ke NearYouID"` AND the catalog test still references its accessor (removal from the rendered surface does not remove the string from the catalog)

#### Scenario: No hardcoded UI strings in SignInScreen source

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/auth/SignInScreen.kt`
- **THEN** every `Text(...)` / `contentDescription = ...` / similar UI-string-bearing call site sources its text via `stringResource(Res.string.<name>)` (Compose Multiplatform Resources accessor); zero literal string arguments appear in such call sites

#### Scenario: SignInScreen brand logo swaps on system-theme change at recomposition

- **GIVEN** `SignInScreen` is composed in light mode (`isSystemInDarkTheme() == false`) — the rendered brand logo node uses `Res.drawable.logo_brand_light`
- **WHEN** the system theme is toggled to dark mode AND the screen is recomposed
- **THEN** the rendered brand logo node uses `Res.drawable.logo_brand_dark` (no crash, no stale-logo retention); recomposition is triggered automatically because `isSystemInDarkTheme()` is observable Compose state
