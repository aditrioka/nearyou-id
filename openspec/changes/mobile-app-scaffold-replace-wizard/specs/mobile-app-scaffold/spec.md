## ADDED Requirements

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

The `App()` composable SHALL wrap its content in a `MaterialTheme` whose active `ColorScheme` is selected between a light Material 3 scheme and a dark Material 3 scheme based on the platform's reported system dark-mode preference at composition time (via `isSystemInDarkTheme()` or the equivalent Compose Multiplatform API). The theme wrapper SHALL be defined as a reusable `NearYouTheme(content: @Composable () -> Unit)` composable in commonMain.

#### Scenario: Light mode applies light color scheme
- **WHEN** the device reports system dark-mode = OFF at the time `App()` is composed
- **THEN** `MaterialTheme.colorScheme` resolves to the configured light scheme (Material 3 default `lightColorScheme()` or a customised light scheme of equivalent role mapping)

#### Scenario: Dark mode applies dark color scheme
- **WHEN** the device reports system dark-mode = ON at the time `App()` is composed
- **THEN** `MaterialTheme.colorScheme` resolves to the configured dark scheme (Material 3 default `darkColorScheme()` or a customised dark scheme of equivalent role mapping)

#### Scenario: NearYouTheme is the single theming root
- **WHEN** grepping commonMain for `MaterialTheme {`
- **THEN** the only occurrence is inside `NearYouTheme`'s implementation (or `App()`'s direct invocation of `NearYouTheme`); no screen or component declares its own competing `MaterialTheme` wrapper

### Requirement: Typed navigation host with start destination

The `App()` composable SHALL host a navigation framework (declared in `design.md`) configured with at least one screen registered as the start destination. The navigation framework SHALL provide typed routes — each screen is represented as a typed entity (e.g., a Voyager `Screen` implementation), not a stringly-typed path. Adding a new screen in a subsequent change SHALL NOT require restructuring the navigation host wiring beyond declaring the new screen in the framework's registry pattern.

#### Scenario: Start destination renders on app launch
- **WHEN** `App()` is composed for the first time with no prior navigation state
- **THEN** the registered start-destination placeholder screen (named `HomeScreen` or the equivalent defined in `design.md`) is rendered; no other screen is shown

#### Scenario: Navigation host is declared in commonMain
- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/App.kt`
- **THEN** the navigation host is instantiated inside `App()` (or a commonMain helper invoked by `App()`); no platform-specific source set declares its own navigation host

#### Scenario: Placeholder screen renders app identity
- **WHEN** the start-destination placeholder is composed
- **THEN** the rendered content includes at minimum a "NearYouID" identifier label and no networking call, no auth lookup, and no feature-specific business logic is invoked

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

The `:mobile:app` module commonMain SHALL NOT contain Ktor HTTP-client setup, ad-hoc HTTP usage, authentication-flow wiring, FCM token registration, hardcoded API base URLs, or any feature-specific business logic. All such concerns ship in later mobile changes per [`openspec/project.md`](../../../project.md) § Mobile + Admin Scaffolding Priority (#2 Moko Resources, #3 Google Sign-In, #4 age gate, #5 first product screen, and beyond).

The negative scenarios below use case-insensitive grep patterns intentionally broadened to cover common identifier shapes. They are NOT exhaustive — the canonical defense against scope drift is the spec requirement itself, with grep as a CI-time backstop. Implementers SHOULD treat additions to mobile sources that match the spirit (auth flow, FCM token handling, ad-hoc network calls, hardcoded API hostnames) as requirement violations even if the specific identifier shape escapes a literal grep.

#### Scenario: No Ktor client dependency in mobile build
- **WHEN** inspecting `mobile/app/build.gradle.kts`
- **THEN** no `io.ktor:ktor-client-*` artifact is declared as an `implementation` / `api` / `commonMainImplementation` dependency of the mobile module

#### Scenario: No ad-hoc HTTP usage in mobile sources
- **WHEN** grepping `mobile/app/src/commonMain`, `mobile/app/src/androidMain`, and `mobile/app/src/iosMain` for any of the following identifiers (case-insensitive): `URLConnection`, `HttpURLConnection`, `URLSession`, `NSURLSession`, `okhttp3`, `OkHttpClient`, `WebSocket`, `WebSocketClient`
- **THEN** no matches are found in mobile-module sources (the Compose Multiplatform runtime's internal HTTP usage in transitive deps is permitted; this scenario targets first-party scaffold code only)

#### Scenario: No authentication code in mobile sources
- **WHEN** grepping `mobile/app/src/commonMain`, `mobile/app/src/androidMain`, and `mobile/app/src/iosMain` for any of the following identifiers (case-insensitive): `signIn`, `sign_in`, `signin`, `GoogleId`, `googleSignIn`, `google_sign_in`, `AppleAuth`, `appleSignIn`, `apple_sign_in`, `JwtToken`, `JWT_TOKEN`, `jwt_token`, `RefreshToken`, `refresh_token`, `authToken`, `auth_token`, `accessToken`, `access_token`, `idToken`, `id_token`, `Authenticator`, `oauthClient`, `loginClient`, `loginFlow`
- **THEN** no matches are found in mobile-module sources

#### Scenario: No FCM-token registration code in mobile sources
- **WHEN** grepping `mobile/app/src/commonMain`, `mobile/app/src/androidMain`, and `mobile/app/src/iosMain` for any of the following identifiers (case-insensitive): `FirebaseMessaging`, `fcmToken`, `fcm_token`, `registerFcmToken`, `register_fcm_token`, `messaging.token`, `pushToken`, `push_token`, `notificationToken`, `notification_token`
- **THEN** no matches are found in mobile-module sources

#### Scenario: No hardcoded API base URLs in mobile sources
- **WHEN** grepping `mobile/app/src/commonMain`, `mobile/app/src/androidMain`, and `mobile/app/src/iosMain` for any of the following patterns (case-insensitive): `nearyou\.id`, `api-staging`, `api\.nearyou`, `admin-staging`, `admin\.nearyou`, `img-staging`, `img\.nearyou`
- **THEN** no matches are found in mobile-module sources (hardcoded API hostnames belong in environment-aware config that lands alongside the first networking change, Mobile #3)

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
