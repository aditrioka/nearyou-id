# mobile-app-scaffold Specification

## Purpose

Defines the cross-cutting structure of the `:mobile:app` Compose Multiplatform application: a single shared `App()` composable in commonMain, a Material 3 theming root that follows the system dark-mode preference, a typed navigation host (Navigation 3) with a placeholder start destination (`HomeScreen`), a Koin DI initializer that is idempotent across Android Activity recreations + iOS Swift `iOSApp.init()` re-entries, and the iOS two-layer bridge (Swift `iOSApp` → `ContentView` → Kotlin `MainViewController()` → KMP `App()`). The capability's negative requirement explicitly forbids the scaffold from introducing networking, authentication, FCM token registration, hardcoded API base URLs, ad-hoc HTTP usage, backend or infra module dependencies, or any feature behavior — those concerns ship in subsequent mobile changes per [`openspec/project.md`](../../project.md) § Mobile + Admin Scaffolding Priority (Mobile #2 / #2.5 Resources scaffolding — Moko initially via [PR #116](https://github.com/aditrioka/nearyou-id/pull/116), swapped to Compose Multiplatform Resources via [PR #119](https://github.com/aditrioka/nearyou-id/pull/119); #3 Google Sign-In, #4 age gate, #5 first product screen, and beyond).

See [`docs/04-Architecture.md`](../../../docs/04-Architecture.md) § Mobile Status for the current shipped scaffold shape and [`docs/03-UX-Design.md`](../../../docs/03-UX-Design.md) for the user-facing flows that subsequent mobile changes will implement on this foundation.
## Requirements
### Requirement: Shared App entry composable

The `:mobile:app` module SHALL expose a single `App()` `@Composable` function in commonMain (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/App.kt`). The Android platform entry (`MainActivity`) SHALL invoke `App()` directly via `setContent { App() }`. The iOS platform path is a two-layer bridge: a Kotlin `MainViewController()` function in `iosMain` returns `ComposeUIViewController { App() }`, and the Swift host (`iosApp/iosApp/ContentView.swift` exposing a `UIViewControllerRepresentable` wrapper that the Swift `@main` `iOSApp` struct consumes via `WindowGroup { ContentView() }`) instantiates the KMP-side `MainViewController()` and presents it. All `@Composable` UI logic SHALL live in commonMain — neither platform entry SHALL render its own UI beyond the framework-integration call and any Koin-init glue.

#### Scenario: Android entry calls App() from commonMain
- **WHEN** inspecting `mobile/app/src/androidMain/kotlin/id/nearyou/app/MainActivity.kt`
- **THEN** the `onCreate` body contains `setContent { App() }` (or an equivalent thin wrapper) and contains no `@Composable` UI declarations of its own beyond `App()` and any Koin-init glue

#### Scenario: iOS KMP-bridge ViewController calls App() from commonMain
- **WHEN** inspecting `mobile/app/src/iosMain/kotlin/id/nearyou/app/MainViewController.kt`
- **THEN** the file returns `ComposeUIViewController { App() }` (or an equivalent thin wrapper) and contains no `@Composable` UI declarations of its own beyond `App()`

#### Scenario: iOS Swift host bridges to the KMP ViewController
- **WHEN** inspecting `iosApp/iosApp/iOSApp.swift` and `iosApp/iosApp/ContentView.swift`
- **THEN** the Swift entry-point chain (`@main` `iOSApp` → `WindowGroup` → `ContentView`) ultimately instantiates the KMP-side `MainViewController()` (via `UIViewControllerRepresentable` or equivalent SwiftUI bridge) and contains no SwiftUI views that render product UI of their own beyond the bridge

#### Scenario: Greeting from :shared:tmp is removed from mobile
- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/App.kt`
- **THEN** the file contains no reference to `Greeting`, `greet()`, or any other symbol from the `:shared:tmp` module

### Requirement: Material 3 theme follows system preference

The `App()` composable SHALL wrap its content in a `MaterialTheme` whose active `ColorScheme` is selected between the **NearYouID brand light scheme** (`NearYouColorScheme.light` from `:shared:resources`) and the **NearYouID brand dark scheme** (`NearYouColorScheme.dark` from `:shared:resources`) based on the platform's reported system dark-mode preference at composition time (via `isSystemInDarkTheme()` or the equivalent Compose Multiplatform API). The theme wrapper SHALL be defined as a reusable `NearYouTheme(content: @Composable () -> Unit)` composable in commonMain. The same wrapper SHALL also apply `NearYouTypography` from `:shared:resources` as the active `Typography` and SHALL provide the `ColorScheme` extension properties (e.g., `MaterialTheme.colorScheme.locationPin`, `.premiumBadge`) via `CompositionLocal` so they resolve at every call site within the theme's scope.

#### Scenario: Light mode applies brand light color scheme

- **WHEN** the device reports system dark-mode = OFF at the time `App()` is composed
- **THEN** `MaterialTheme.colorScheme` resolves to `NearYouColorScheme.light` from `:shared:resources` (NOT vanilla Material 3 `lightColorScheme()`); `MaterialTheme.colorScheme.primary` resolves to `Color(0xFF1E4FD6)`

#### Scenario: Dark mode applies brand dark color scheme

- **WHEN** the device reports system dark-mode = ON at the time `App()` is composed
- **THEN** `MaterialTheme.colorScheme` resolves to `NearYouColorScheme.dark` from `:shared:resources` (NOT vanilla Material 3 `darkColorScheme()`); `MaterialTheme.colorScheme.primary` resolves to `Color(0xFFB3C5FF)` (the mechanically-derived dark primary per `shared-resources-moko-bootstrap` design.md Decision 3)

#### Scenario: NearYouTheme applies brand typography

- **WHEN** `App()` is composed inside `NearYouTheme { ... }`
- **THEN** `MaterialTheme.typography` resolves to `NearYouTypography` from `:shared:resources` — every type role (`displayLarge` through `labelSmall`) uses Plus Jakarta Sans (with `FontFamily.SansSerif` as fallback)

#### Scenario: NearYouTheme exposes ColorScheme extension properties

- **WHEN** a composable invokes `MaterialTheme.colorScheme.locationPin` inside `NearYouTheme { ... }`
- **THEN** the property resolves to the theme-aware value (light: `Color(0xFFFF7A5C)`, dark: `Color(0xFFFFB59E)`) via the `CompositionLocal` wired by `NearYouTheme`

- **WHEN** a composable invokes `MaterialTheme.colorScheme.premiumBadge` inside `NearYouTheme { ... }`
- **THEN** the property resolves to the theme-aware value (light: `Color(0xFFF4B740)`, dark: `Color(0xFFE8B941)`)

#### Scenario: NearYouTheme is the single theming root

- **WHEN** grepping commonMain for `MaterialTheme {`
- **THEN** the only occurrence is inside `NearYouTheme`'s implementation (or `App()`'s direct invocation of `NearYouTheme`); no screen or component declares its own competing `MaterialTheme` wrapper

#### Scenario: No vanilla Material 3 default color schemes are referenced from NearYouTheme

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/theme/NearYouTheme.kt`
- **THEN** the file contains NO references to `lightColorScheme(` or `darkColorScheme(` (the vanilla Material 3 default-color constructors) — both schemes are sourced exclusively from `NearYouColorScheme` in `:shared:resources`

### Requirement: Typed navigation host with start destination

The `App()` composable SHALL host **Navigation 3** (`org.jetbrains.androidx.navigation3`, declared in `design.md`) configured with a developer-owned back stack seeded with a start-destination route. The navigation host SHALL be a `NavDisplay` rendering a `rememberNavBackStack`, where each destination is a typed **`NavKey`** entity mapped to its screen composable by an `entryProvider` — routes are typed entities, not stringly-typed paths. Navigation SHALL be expressed as back-stack list operations (add to push, `removeLastOrNull()` to pop, a `replaceAll(key)` extension to clear-and-set across auth boundaries); no Voyager `Navigator` / `Screen` / `LocalNavigator` API SHALL remain in mobile sources. Adding a new screen in a subsequent change SHALL NOT require restructuring the navigation host wiring beyond declaring the new screen's `NavKey` and adding one `entry<…>` mapping to the `entryProvider`.

#### Scenario: Start destination renders on app launch

- **WHEN** `App()` is composed for the first time with no prior navigation state
- **THEN** the back stack is seeded with the start-destination route (`RootRoute`, defined in `design.md`) and `NavDisplay` renders that route's mapped composable; no other entry is shown

#### Scenario: Navigation host is declared in commonMain

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/App.kt`
- **THEN** the navigation host (`NavDisplay` over a `rememberNavBackStack`) is instantiated inside `App()` (or a commonMain helper invoked by `App()`); no platform-specific source set declares its own navigation host

#### Scenario: Placeholder screen renders app identity via Compose Multiplatform Resources

- **WHEN** the start-destination placeholder is composed
- **THEN** the rendered content includes a "NearYouID" identifier label consumed via `stringResource(Res.string.home_placeholder_title)` from `:shared:resources` (NOT a hardcoded string literal, NOT the legacy `MR.strings.home_placeholder_title` Moko accessor), AND a version label consumed via `stringResource(Res.string.home_placeholder_version, "1.0")` with the runtime version supplied as the format argument, AND no networking call, no auth lookup, and no feature-specific business logic is invoked

#### Scenario: HomeScreen consumes brand logo via CMP Resources accessor

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/home/HomeScreen.kt`
- **THEN** the brand-logo selection uses `Res.drawable.logo_brand_dark` (when `isSystemInDarkTheme()` is true) and `Res.drawable.logo_brand_light` (when false), consumed via `painterResource(...)` from the Compose Multiplatform Resources accessor; the file contains NO references to `MR.images.*` or `MR.strings.*` (the legacy Moko accessors)

### Requirement: Koin DI initialized once per process

The `:mobile:app` module SHALL initialize Koin via a commonMain `initKoin(additionalConfig: KoinAppDeclaration? = null)` helper. The helper SHALL register a `mobileModule` Koin module (defined in commonMain as `mobileModule = module { }`) that subsequent changes extend with bindings. The helper SHALL be idempotent: invoking `initKoin()` after Koin is already started SHALL be a no-op (guarded via `getKoinOrNull()`). Android SHALL invoke `initKoin()` from its entry path (e.g., `MainActivity.onCreate` before the first `setContent`). iOS SHALL invoke `initKoin()` via a top-level Kotlin shim (commonMain, callable from Swift) wired into the Swift app-launch path — specifically the `init()` block of the SwiftUI `@main` `iOSApp` struct, invoked before the `WindowGroup` scene body builds (see `design.md` Decision 2 for the rationale; if a UIKit `AppDelegate` is introduced in a later change, the same shim moves into `AppDelegate.application(_:didFinishLaunchingWithOptions:)`).

#### Scenario: Android invokes initKoin at startup
- **WHEN** inspecting Android entry-point code (`MainActivity` or an `Application` subclass if introduced)
- **THEN** `initKoin()` is invoked at least once during app startup before the first `setContent { App() }` call

#### Scenario: iOS invokes initKoin via Swift-callable shim
- **WHEN** inspecting iOS entry-point code (`iosApp/iosApp/iOSApp.swift`)
- **THEN** a top-level Kotlin function (commonMain, callable from Swift such as `fun doInitKoin()`) is declared, and the Swift `iOSApp` struct's `init()` block invokes it before the `WindowGroup { ContentView() }` scene body runs (or, if a UIKit `AppDelegate` is later introduced, the same shim is invoked from `AppDelegate.application(_:didFinishLaunchingWithOptions:)`)

#### Scenario: mobileModule placeholder is registered
- **WHEN** inspecting the commonMain Koin module file (e.g., `MobileModule.kt`)
- **THEN** a `mobileModule = module { }` (or equivalent name) is declared, and `initKoin()` registers it via `modules(mobileModule, ...)`

#### Scenario: initKoin is idempotent
- **WHEN** `initKoin()` is invoked twice in the same process (e.g., during Compose previews or after an Activity recreation)
- **THEN** the second invocation MUST NOT throw and MUST NOT replace the existing Koin application; verified by a guard such as `if (getKoinOrNull() == null) { startKoin { ... } }`

### Requirement: Scaffold does not introduce networking, auth, or feature behavior

The `:mobile:app` module commonMain SHALL NOT contain Ktor HTTP-client setup, ad-hoc HTTP usage, authentication-flow wiring, FCM token registration, hardcoded API base URLs, or any feature-specific business logic — **EXCEPT** for the substrate landed by the `mobile-auth-google-signin-flow` change (Mobile #3) per the carve-outs below. All other such concerns ship in subsequent mobile changes per [`openspec/project.md`](../../project.md) § Mobile + Admin Scaffolding Priority (#4 age gate, #5 first product screen, and beyond).

**Carve-outs introduced by `mobile-auth-google-signin-flow` (Mobile #3):**

- **Ktor HTTP client** is now permitted in commonMain via the canonical KMP coordinates (`io.ktor:ktor-client-core`, `io.ktor:ktor-client-content-negotiation`, `io.ktor:ktor-serialization-kotlinx-json` in commonMain; `io.ktor:ktor-client-okhttp` in androidMain; `io.ktor:ktor-client-darwin` in iosMain). The non-KMP `-jvm` artifact set (e.g., `io.ktor:ktor-client-okhttp-jvm`) remains forbidden in mobile sources (those are backend-only).
- **Auth-flow identifiers** (`SignIn`, `GoogleId`, `signIn`, `signin`, `googleSignIn`, `idToken`, `accessToken`, `refreshToken`, `authToken`, `JwtToken`, `jwt_token`, `Authenticator`, `oauthClient`, `loginClient`, `loginFlow`) are now permitted inside files added by this change — specifically: `mobile/app/src/commonMain/kotlin/id/nearyou/app/auth/**`, `mobile/app/src/commonMain/kotlin/id/nearyou/app/network/**`, `mobile/app/src/commonMain/kotlin/id/nearyou/app/di/**`, `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/auth/**`, `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/routing/**`, `mobile/app/src/commonMain/kotlin/id/nearyou/app/config/**`, `mobile/app/src/{androidMain,iosMain}/kotlin/id/nearyou/app/auth/**`, `mobile/app/src/{androidMain,iosMain}/kotlin/id/nearyou/app/di/**`, `mobile/app/src/{androidMain,iosMain}/kotlin/id/nearyou/app/config/**`. They remain forbidden everywhere else in mobile sources. (`network/**` houses the shared `HttpClientFactory` — the Bearer-token interceptor + refresh queue is general networking infrastructure that all future feature requests share. `di/**` houses the Koin modules (`MobileModule`, `PlatformModule`) that assemble the auth dependency graph — wiring inherently names every binding, including `GoogleSignInGateway` / `AuthRepository`. Both are carved out alongside `auth/**`.)
- **Apple Sign-In identifiers** (`AppleAuth`, `appleSignIn`, `apple_sign_in`) remain forbidden across all mobile sources (Apple Sign-In iOS is a separate later change).
- **Environment-aware API base URL** is now permitted inside the `id.nearyou.app.config` package only — specifically: `mobile/app/src/commonMain/kotlin/id/nearyou/app/config/ApiBaseUrl.kt`, `mobile/app/src/{androidMain,iosMain}/kotlin/id/nearyou/app/config/ApiBaseUrl.kt`. The Android actual reads from `BuildConfig.API_BASE_URL` (via gradle product flavor injection) and the iOS actual reads from `NSBundle` (via xcconfig). Hardcoded API hostnames remain forbidden everywhere else in mobile sources.
- **FCM-token registration identifiers** (`FirebaseMessaging`, `fcmToken`, `fcm_token`, `registerFcmToken`, `register_fcm_token`, `messaging.token`, `pushToken`, `push_token`, `notificationToken`, `notification_token`) remain forbidden across all mobile sources.
- **Direct HTTP-client usage** (`URLConnection`, `HttpURLConnection`, `URLSession`, `NSURLSession`, `okhttp3.OkHttpClient`, `WebSocket`, `WebSocketClient`) remains forbidden. The Ktor `HttpClient` is the ONLY permitted client substrate.
- **Backend / infra module dependencies** (`projects.backend.*`, `projects.infra.*`, `project(":backend:...")`, `project(":infra:...")`) remain forbidden in `mobile/app/build.gradle.kts`.

The negative scenarios below use case-insensitive grep patterns intentionally broadened to cover common identifier shapes. They are NOT exhaustive — the canonical defense against scope drift is the spec requirement itself, with grep as a CI-time backstop. Implementers SHOULD treat additions to mobile sources that match the spirit (auth flow OUTSIDE the carved-out paths, FCM token handling, ad-hoc network calls, hardcoded API hostnames OUTSIDE the config package) as requirement violations even if the specific identifier shape escapes a literal grep.

#### Scenario: Ktor KMP client dependencies are permitted in mobile build

- **WHEN** inspecting `mobile/app/build.gradle.kts`
- **THEN** the file MAY declare `io.ktor:ktor-client-core`, `io.ktor:ktor-client-content-negotiation`, `io.ktor:ktor-serialization-kotlinx-json` as `commonMain` dependencies AND `io.ktor:ktor-client-okhttp` as an `androidMain` dependency AND `io.ktor:ktor-client-darwin` as an `iosMain` dependency; NO `io.ktor:ktor-client-*-jvm` artifact (the `-jvm` suffix variant) is declared as a mobile-module dependency

#### Scenario: No ad-hoc HTTP usage in mobile sources

- **WHEN** grepping `mobile/app/src/commonMain`, `mobile/app/src/androidMain`, and `mobile/app/src/iosMain` for any of the following identifiers (case-insensitive): `URLConnection`, `HttpURLConnection`, `URLSession`, `NSURLSession`, `okhttp3.OkHttpClient`, `WebSocket`, `WebSocketClient`
- **THEN** no matches are found in mobile-module sources (Ktor's internal transitive use is permitted; this scenario targets first-party scaffold code only)

#### Scenario: Auth-flow identifiers are permitted only inside this change's carved-out paths

- **WHEN** grepping `mobile/app/src/commonMain`, `mobile/app/src/androidMain`, and `mobile/app/src/iosMain` for any of the following identifiers (case-insensitive): `SignIn`, `GoogleId`, `signIn`, `signin`, `googleSignIn`, `google_sign_in`, `JwtToken`, `JWT_TOKEN`, `jwt_token`, `RefreshToken`, `refresh_token`, `authToken`, `auth_token`, `accessToken`, `access_token`, `idToken`, `id_token`, `Authenticator`, `oauthClient`, `loginClient`, `loginFlow`
- **THEN** every match resides under one of the carved-out paths declared above (`auth/**`, `network/**`, `di/**`, `screens/auth/**`, `screens/routing/**`, `config/**` in any source set); no match resides outside those paths

#### Scenario: Apple Sign-In identifiers remain forbidden in mobile sources

- **WHEN** grepping `mobile/app/src/commonMain`, `mobile/app/src/androidMain`, and `mobile/app/src/iosMain` for any of the following identifiers (case-insensitive): `AppleAuth`, `appleSignIn`, `apple_sign_in`, `ASAuthorization`
- **THEN** no matches are found in mobile-module sources (Apple Sign-In iOS is a separate later change)

#### Scenario: No FCM-token registration code in mobile sources

- **WHEN** grepping `mobile/app/src/commonMain`, `mobile/app/src/androidMain`, and `mobile/app/src/iosMain` for any of the following identifiers (case-insensitive): `FirebaseMessaging`, `fcmToken`, `fcm_token`, `registerFcmToken`, `register_fcm_token`, `messaging.token`, `pushToken`, `push_token`, `notificationToken`, `notification_token`
- **THEN** no matches are found in mobile-module sources

#### Scenario: Hardcoded API base URL is permitted only inside the config package

- **WHEN** grepping `mobile/app/src/commonMain`, `mobile/app/src/androidMain`, and `mobile/app/src/iosMain` for any of the following patterns (case-insensitive): `nearyou\.id`, `api-staging`, `api\.nearyou`, `admin-staging`, `admin\.nearyou`, `img-staging`, `img\.nearyou`
- **THEN** every match resides under `mobile/app/src/{commonMain,androidMain,iosMain}/kotlin/id/nearyou/app/config/**`; no match resides outside the `config` package. The Android `BuildConfig.API_BASE_URL` value injected via gradle product flavor IS the canonical Android resolution path; the iOS `NSBundle.objectForInfoDictionaryKey("ApiBaseUrl")` value injected via xcconfig IS the canonical iOS resolution path

#### Scenario: No backend or infra module dependencies

- **WHEN** inspecting `mobile/app/build.gradle.kts`
- **THEN** the file contains no `projects.backend.*` / `projects.infra.*` Gradle-module-accessor references AND no `project(":backend:..."` / `project(":infra:..."` legacy-syntax references; neither form may smuggle a backend or infra module into the mobile dependency graph

### Requirement: Android and iOS targets build green

The Android assemble task (`./gradlew :mobile:app:assembleDebug`) and the canonical iOS framework link task (`./gradlew :mobile:app:linkPodDebugFrameworkIosSimulatorArm64`, or whichever iOS link task the project conventionally smokes — confirmed in `design.md`) SHALL each exit with code 0 against the change as merged.

#### Scenario: Android assembleDebug passes
- **WHEN** running `./gradlew :mobile:app:assembleDebug` from the repository root after this change is applied
- **THEN** the task completes with exit code 0 and produces an APK in the standard Android build output directory

#### Scenario: iOS framework link passes locally
- **WHEN** running the canonical iOS framework link task (e.g., `./gradlew :mobile:app:linkPodDebugFrameworkIosSimulatorArm64`) on a macOS workstation against this change
- **THEN** the task completes with exit code 0 and produces the `ComposeApp.framework` artifact in the standard KMP build output directory

#### Scenario: Whole-project build remains green
- **WHEN** running `./gradlew build` from the repository root after this change is applied
- **THEN** the build completes with exit code 0; no module-resolution error is reported; existing backend and lint test suites continue to pass

### Requirement: Back stack uses serializable NavKey routes for cross-platform state restoration

Every navigation route SHALL be a `@Serializable` type implementing `NavKey`, and ALL route types SHALL be registered in a single polymorphic `SerializersModule` (`polymorphic(NavKey::class) { subclass(...) }`) supplied to the back stack via `SavedStateConfiguration`. The back stack SHALL be created with `rememberNavBackStack(<config>, <startRoute>)` so it is saveable on non-JVM targets (iOS, where reflection-based serialization is unavailable). The serialization module SHALL live in `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/routing/`.

#### Scenario: Every route key round-trips through the polymorphic module

- **WHEN** a `commonTest` serializes each declared `NavKey` route via the polymorphic `SavedStateConfiguration` module and deserializes the result back
- **THEN** each route deserializes to a value equal to the original (every route type is registered in the `SerializersModule` — a missing `subclass(...)` registration fails this test)

#### Scenario: Back stack is created with the polymorphic configuration

- **WHEN** inspecting the navigation host in `mobile/app/src/commonMain/kotlin/id/nearyou/app/`
- **THEN** the back stack is created via `rememberNavBackStack` passed the `SavedStateConfiguration` carrying the polymorphic `NavKey` `SerializersModule` (NOT a reflection-defaulted back stack that would fail to save on iOS)

### Requirement: NavDisplay scopes per-entry saveable state and ViewModels via entry decorators

The `NavDisplay` SHALL include, in its `entryDecorators` (in this order), `rememberSaveableStateHolderNavEntryDecorator()` so each `NavEntry` receives its own `SaveableStateRegistry` (per-screen `rememberSaveable` state — e.g. the composer draft — is scoped to its entry and retained while that entry remains in the back stack) **and** `rememberViewModelStoreNavEntryDecorator()` (from the `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-navigation3` artifact) so each `NavEntry` receives its own `ViewModelStore`. A screen MAY scope a `ViewModel` to its entry via `viewModel { … }`; that ViewModel SHALL survive the entry going off-screen (e.g. while another destination is on top) and SHALL be cleared only when the entry is popped off the back stack. The Nearby feed is the first such screen (its load state is held in a `HomeRoute`-scoped ViewModel so returning from the composer does not re-fetch — see `mobile-nearby-timeline`).

#### Scenario: NavDisplay wires both entry decorators

- **WHEN** inspecting the `NavDisplay` declaration in `mobile/app/src/commonMain/kotlin/id/nearyou/app/App.kt`
- **THEN** its `entryDecorators` list includes `rememberSaveableStateHolderNavEntryDecorator()` AND `rememberViewModelStoreNavEntryDecorator()`

#### Scenario: The per-entry ViewModel-store artifact is declared

- **WHEN** inspecting the version catalog (`gradle/libs.versions.toml`) and `mobile/app/build.gradle.kts`
- **THEN** the `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-navigation3` dependency is declared (pinned to the project's `androidx-lifecycle` version) and added to the `:mobile:app` `commonMain` dependencies

