## 1. Pin dependencies and Gradle wiring

- [ ] 1.1 Pick a Voyager stable release that supports Koin 4.x (cross-check Voyager release notes during `/opsx:apply`). Record the chosen version in the Version Pinning Decisions Log notes (`docs/08-Roadmap-Risk.md` Pre-Phase 1 entry).
- [ ] 1.2 Add `voyager-navigator` and `voyager-koin` entries (version, library) to [`gradle/libs.versions.toml`](../../../gradle/libs.versions.toml).
- [ ] 1.3 Add `koin-compose` and `koin-compose-viewmodel` entries to [`gradle/libs.versions.toml`](../../../gradle/libs.versions.toml), aligned with the existing Koin 4.1.0 pin.
- [ ] 1.4 Update [`mobile/app/build.gradle.kts`](../../../mobile/app/build.gradle.kts) `commonMain.dependencies { ... }` to add `voyager-navigator`, `voyager-koin`, `koin-core`, `koin-compose`, `koin-compose-viewmodel`.
- [ ] 1.5 Remove `implementation(projects.shared.tmp)` from [`mobile/app/build.gradle.kts`](../../../mobile/app/build.gradle.kts).
- [ ] 1.6 Run `./gradlew :mobile:app:dependencies` and confirm Voyager + Koin Compose resolve cleanly; `:shared:tmp` no longer appears in the mobile dependency tree.

## 2. Theme

- [ ] 2.1 Create `mobile/app/src/commonMain/kotlin/id/nearyou/app/theme/NearYouTheme.kt`.
- [ ] 2.2 Implement `NearYouTheme(content: @Composable () -> Unit)`: read `isSystemInDarkTheme()`; pick between Material 3 default `lightColorScheme()` and `darkColorScheme()`; wrap `content` in `MaterialTheme(colorScheme = ..., content = content)`.

## 3. Koin DI scaffolding

- [ ] 3.1 Create `mobile/app/src/commonMain/kotlin/id/nearyou/app/di/MobileModule.kt` declaring `val mobileModule = module { }` (empty placeholder; subsequent mobile changes register bindings into this).
- [ ] 3.2 Create `mobile/app/src/commonMain/kotlin/id/nearyou/app/di/KoinInit.kt` with `fun initKoin(additionalConfig: KoinAppDeclaration? = null)` that guards via `if (getKoinOrNull() == null)` and invokes `startKoin { modules(mobileModule); additionalConfig?.invoke(this) }`. Add `fun doInitKoin() = initKoin()` as the Swift-callable top-level shim (commonMain).
- [ ] 3.3 Add KDoc to `initKoin` documenting the idempotency guard and platform call-site conventions (Android `MainActivity.onCreate` before `setContent`; iOS Swift `@main` / `AppDelegate` at app launch).

## 4. Navigation host and placeholder screen

- [ ] 4.1 Create `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/home/HomeScreen.kt` as a Voyager `Screen` rendering a centered Column with `"NearYouID"` (Material 3 `headlineLarge`) and a small version label (hardcoded `"v1.0"` for now; resolve open question in `design.md` § Open Questions about iOS version-string source).
- [ ] 4.2 Rewrite [`mobile/app/src/commonMain/kotlin/id/nearyou/app/App.kt`](../../../mobile/app/src/commonMain/kotlin/id/nearyou/app/App.kt): replace the wizard `Click me!` body with `NearYouTheme { Navigator(HomeScreen()) }`. Remove all references to `Greeting`, `compose_multiplatform` drawable, and the `Click me!` button.

## 5. Platform entry points

- [ ] 5.1 Update [`mobile/app/src/androidMain/kotlin/id/nearyou/app/MainActivity.kt`](../../../mobile/app/src/androidMain/kotlin/id/nearyou/app/MainActivity.kt): in `onCreate`, call `initKoin()` before `setContent { App() }`. Keep `enableEdgeToEdge()` and `super.onCreate(...)` as-is.
- [ ] 5.2 Keep [`mobile/app/src/iosMain/kotlin/id/nearyou/app/MainViewController.kt`](../../../mobile/app/src/iosMain/kotlin/id/nearyou/app/MainViewController.kt) returning `ComposeUIViewController { App() }`. Add KDoc that documents Swift must invoke `doInitKoin()` at app launch.
- [ ] 5.3 Update [`iosApp/iosApp/iOSApp.swift`](../../../iosApp/iosApp/iOSApp.swift) (the Swift `@main` entry) to call the `doInitKoin()` function from the compiled `ComposeApp.framework` once at app launch (e.g., inside the `init()` of the `iOSApp` struct). Verify the function is reachable from Swift via the standard KMP framework binding.

## 6. Cleanup wizard residue

- [ ] 6.1 Replace `mobile/app/src/commonTest/kotlin/id/nearyou/app/ComposeAppCommonTest.kt` (the wizard scaffolding test): retain the file path if it remains relevant; rewrite the assertion to target the new HomeScreen (drops any reference to the `Click me!` button).
- [ ] 6.2 Audit `mobile/app/src/commonMain/composeResources/` (the wizard drawable assets such as `compose-multiplatform.xml`). Delete any drawable no longer referenced from the rewritten `App.kt` / `HomeScreen.kt`. Skip if the resource is generic enough to keep for later use.
- [ ] 6.3 Verify `mobile/app/build.gradle.kts` no longer references `compose.components.resources` IF that artifact is unused after step 6.2; otherwise leave it. (Mobile #2 will replace the Compose Resources dependency with Moko Resources.)

## 7. Tests

- [ ] 7.1 Add `mobile/app/src/commonTest/kotlin/id/nearyou/app/HomeScreenTest.kt`: a Compose Multiplatform smoke test that asserts `App()` composes without throwing and the "NearYouID" label is present in the composition.
- [ ] 7.2 If `compose.uiTest` or equivalent is not yet wired in `commonTest.dependencies`, add the necessary test library entry to `gradle/libs.versions.toml` + `mobile/app/build.gradle.kts`. Use `kotlin.test` baseline assertions if a Compose UI-test runner is overkill for a smoke test.
- [ ] 7.3 Run `./gradlew :mobile:app:check` (or the equivalent commonTest task); assert green.

## 8. Local verification (pre-push gates)

- [ ] 8.1 Run `./gradlew :mobile:app:assembleDebug` from repo root; assert exit code 0 and APK appears under `mobile/app/build/outputs/`.
- [ ] 8.2 Run `./gradlew :mobile:app:linkPodDebugFrameworkIosSimulatorArm64` (or the project's canonical iOS framework link task) on macOS; assert exit code 0 and `ComposeApp.framework` appears under `mobile/app/build/bin/`.
- [ ] 8.3 Open the [`iosApp/iosApp.xcodeproj`](../../../iosApp/iosApp.xcodeproj) in Xcode; verify the iOS Simulator build still launches and shows the new `HomeScreen` (Material 3 themed; "NearYouID" label centered).
- [ ] 8.4 Run `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` from repo root per CLAUDE.md pre-push verification; assert green.
- [ ] 8.5 Run `./gradlew build` from repo root; assert exit 0 (whole-project build remains green per spec).
- [ ] 8.6 Confirm `openspec validate mobile-app-scaffold-replace-wizard --strict` is green.

## 9. Documentation + follow-up bookkeeping

- [ ] 9.1 Add a `FOLLOW_UPS.md` entry `infra-sentry-kmp-module-isation` (the explicit carve-out from this scaffold per design Decision 5). Cite the [`openspec/project.md`](../../project.md) menu carve-out + [`docs/04-Architecture.md`](../../../docs/04-Architecture.md) § Dependency Isolation Pattern.
- [ ] 9.2 Add a `FOLLOW_UPS.md` entry `mobile-ios-ci-link-task` (deferred iOS-framework-link CI per design Decision 6). Cite [`.github/workflows/ci.yml`](../../../.github/workflows/ci.yml) lack of macOS runner.
- [ ] 9.3 Add a `FOLLOW_UPS.md` entry `mobile-brand-theme-tokens` (deferred brand color/typography tokens per design Decision 3). Note that Mobile #2 (`shared-resources-moko-bootstrap`) MAY fold this in if its scope expands.
- [ ] 9.4 Update [`docs/04-Architecture.md`](../../../docs/04-Architecture.md) § Mobile Status text from "JetBrains Compose Multiplatform wizard template" to a one-paragraph description of the new scaffold (navigation + Koin + theme + placeholder screen). May land as part of this change's commits OR as a small docs-only PR after this change archives — author's call.
- [ ] 9.5 After archive: [`openspec/project.md`](../../project.md) § Mobile + Admin Scaffolding Priority menu Mobile #1 entry will be implicitly complete once this PR squash-merges; no edit to that menu is required (the menu lists the change names, not their status; the flip-trigger condition checks for Mobile #5 + Admin #4 merges, not Mobile #1).
