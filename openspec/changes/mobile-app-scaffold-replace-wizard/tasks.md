## 1. Pin dependencies and Gradle wiring

- [ ] 1.1 Pick a Voyager stable release that supports Koin 4.x (cross-check Voyager release notes during `/opsx:apply`). Record the chosen version in the Version Pinning Decisions Log notes (`docs/08-Roadmap-Risk.md` Pre-Phase 1 entry).
- [ ] 1.2 Add `voyager-navigator` and `voyager-koin` entries (version, library) to [`gradle/libs.versions.toml`](../../../gradle/libs.versions.toml).
- [ ] 1.3 Add `koin-compose` (module `io.insert-koin:koin-compose`, version aligned with the existing Koin 4.1.0 pin) and `koin-compose-viewmodel` (module `io.insert-koin:koin-compose-viewmodel`, same pin) entries to [`gradle/libs.versions.toml`](../../../gradle/libs.versions.toml). Verbatim group:artifact coordinates are stated here to forestall typosquat risk during implementation.
- [ ] 1.4 Update [`mobile/app/build.gradle.kts`](../../../mobile/app/build.gradle.kts) `commonMain.dependencies { ... }` to add `voyager-navigator`, `voyager-koin`, `koin-core`, `koin-compose`, `koin-compose-viewmodel`.
- [ ] 1.5 Remove `implementation(projects.shared.tmp)` from [`mobile/app/build.gradle.kts`](../../../mobile/app/build.gradle.kts).
- [ ] 1.6 Run `./gradlew :mobile:app:dependencies` and confirm Voyager + Koin Compose resolve cleanly; `:shared:tmp` no longer appears in the mobile dependency tree.

## 2. Theme

- [ ] 2.1 Create `mobile/app/src/commonMain/kotlin/id/nearyou/app/theme/NearYouTheme.kt`.
- [ ] 2.2 Implement `NearYouTheme(content: @Composable () -> Unit)`: read `isSystemInDarkTheme()`; pick between Material 3 default `lightColorScheme()` and `darkColorScheme()`; wrap `content` in `MaterialTheme(colorScheme = ..., content = content)`.

## 3. Koin DI scaffolding

- [ ] 3.1 Create `mobile/app/src/commonMain/kotlin/id/nearyou/app/di/MobileModule.kt` declaring `val mobileModule = module { }` (empty placeholder; subsequent mobile changes register bindings into this).
- [ ] 3.2 Create `mobile/app/src/commonMain/kotlin/id/nearyou/app/di/KoinInit.kt` with `fun initKoin(additionalConfig: KoinAppDeclaration? = null)` that guards via `if (getKoinOrNull() == null)` and invokes `startKoin { modules(mobileModule); additionalConfig?.invoke(this) }`. Add `fun doInitKoin() = initKoin()` as the Swift-callable top-level shim (commonMain).
- [ ] 3.3 Add KDoc to `initKoin` documenting (a) the idempotency guard, (b) platform call-site conventions (Android `MainActivity.onCreate` before `setContent`; iOS Swift `iOSApp.init()` before the `WindowGroup` body builds), AND (c) the main-thread expectation: the `getKoinOrNull() == null` check-then-act guard is safe only when invoked from the main thread. Future non-main-thread call sites (e.g., a background-init service) MUST use external synchronization (`synchronized(KoinInit::class)`) — document this caveat so a future engineer reading the KDoc understands the constraint without re-deriving it.

## 4. Navigation host and placeholder screen

- [ ] 4.1 Create `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/home/HomeScreen.kt` as a Voyager `Screen` rendering a centered Column with `"NearYouID"` (Material 3 `headlineLarge`) and a small version label (hardcoded `"v1.0"` for now; resolve open question in `design.md` § Open Questions about iOS version-string source).
- [ ] 4.2 Rewrite [`mobile/app/src/commonMain/kotlin/id/nearyou/app/App.kt`](../../../mobile/app/src/commonMain/kotlin/id/nearyou/app/App.kt): replace the wizard `Click me!` body with `NearYouTheme { Navigator(HomeScreen()) }`. Remove all references to `Greeting`, `compose_multiplatform` drawable, and the `Click me!` button.

## 5. Platform entry points

- [ ] 5.1 Update [`mobile/app/src/androidMain/kotlin/id/nearyou/app/MainActivity.kt`](../../../mobile/app/src/androidMain/kotlin/id/nearyou/app/MainActivity.kt): in `onCreate`, call `initKoin()` before `setContent { App() }`. Keep `enableEdgeToEdge()` and `super.onCreate(...)` as-is.
- [ ] 5.2 Keep [`mobile/app/src/iosMain/kotlin/id/nearyou/app/MainViewController.kt`](../../../mobile/app/src/iosMain/kotlin/id/nearyou/app/MainViewController.kt) returning `ComposeUIViewController { App() }`. Add KDoc that documents the iOS two-layer bridge: Swift `iOSApp.init()` MUST call `doInitKoin()` before the `WindowGroup` body builds; Swift `ContentView` MUST instantiate `MainViewController()` via `UIViewControllerRepresentable`.
- [ ] 5.3 Update [`iosApp/iosApp/iOSApp.swift`](../../../iosApp/iosApp/iOSApp.swift): add an `init()` block to the `iOSApp` struct that calls `doInitKoin()` (imported from the compiled `ComposeApp.framework`). The call MUST happen before the `WindowGroup { ContentView() }` body declaration executes — Swift `struct` initializers run before the SwiftUI scene body is composed, which is the correct ordering to guarantee Koin is initialized when `App()` first composes inside `ContentView`'s `UIViewControllerRepresentable` bridge.
- [ ] 5.4 Verify [`iosApp/iosApp/ContentView.swift`](../../../iosApp/iosApp/ContentView.swift) bridges to the KMP-side `MainViewController()` via `UIViewControllerRepresentable` (or equivalent SwiftUI/UIKit bridge). The wizard scaffold's `ContentView` may currently render placeholder SwiftUI content directly — replace that with a `UIViewControllerRepresentable` that returns `MainViewController()`. This file IS in scope for this change because spec scenario "iOS Swift host bridges to the KMP ViewController" requires it. Confirm the bridge contains no SwiftUI product UI of its own.

## 6. Cleanup wizard residue

- [ ] 6.1 Delete the wizard's `mobile/app/src/commonTest/kotlin/id/nearyou/app/ComposeAppCommonTest.kt` outright (its assertion targets the "Click me!" button which is removed by this change). The replacement test `HomeScreenTest.kt` is created in task 7.1 to keep test-creation work in Section 7.
- [ ] 6.2 Delete the wizard drawable resources under `mobile/app/src/commonMain/composeResources/` (e.g., `compose-multiplatform.xml`) — they are no longer referenced once `App.kt` is rewritten in task 4.2. Verify by grep that no remaining mobile-app source references `compose_multiplatform` or `Res.drawable` after step 4.2.
- [ ] 6.3 Remove `implementation(libs.compose.components.resources)` from `mobile/app/build.gradle.kts`. The wizard imported this dep but the rewritten `App.kt` does not consume it (grep confirms zero `Res.` / `stringResource` uses in `:mobile:app` today, and Moko Resources will own resource bundling once Mobile #2 lands). If the `composeResources/` directory ends up empty after task 6.2, delete the directory too.

## 7. Tests

Per design.md Decision 7, use `kotlin.test` only for this change; do NOT wire the Compose UI test runner. Mobile #5 (first real product screen) is the natural moment to wire `compose.uiTest`.

- [ ] 7.1 Add `mobile/app/src/commonTest/kotlin/id/nearyou/app/HomeScreenTest.kt`: a `kotlin.test`-based smoke test that asserts `App()` composition runs without throwing. The test SHOULD compose `App()` twice in sequence to additionally cover the "no exception on second composition" (Compose preview double-init + Activity recreation safety) scenario implicit in design.md Risk #2.
- [ ] 7.2 Add `mobile/app/src/commonTest/kotlin/id/nearyou/app/di/KoinInitTest.kt`: a `kotlin.test`-based unit test that asserts `initKoin()` is idempotent. Implementation: call `initKoin()` twice in sequence; assert the second call does not throw; assert `getKoin()` returns the same Koin application instance after both calls. This directly verifies the spec scenario "initKoin is idempotent" — without this test, a future refactor that removes the `getKoinOrNull()` guard would ship green.
- [ ] 7.3 If the negative-requirement grep scenarios in `specs/mobile-app-scaffold/spec.md` are to be enforced inside this change (rather than deferred via task 9.4), add a `mobile/app/src/commonTest/kotlin/id/nearyou/app/ScaffoldNegativeRequirementsTest.kt` that walks `mobile/app/src/{commonMain,androidMain,iosMain}` directories at test time and asserts no source file contains any forbidden identifier from the spec. NOTE: this task is optional during `/opsx:apply` — the canonical defense is a Detekt rule (deferred to follow-up `mobile-negative-requirement-ci-grep` per task 9.4); a runtime file-walk test is a lighter-weight backstop the implementer MAY add if they want CI-time enforcement before the Detekt rule lands.
- [ ] 7.4 Run `./gradlew :mobile:app:check` (or the equivalent commonTest task); assert green.

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
- [ ] 9.3 Add a `FOLLOW_UPS.md` entry `mobile-theme-light-dark-direct-test` (deferred Compose UI test runner wiring + direct light/dark color-scheme assertions per design Decision 7). Note that Mobile #5 is the natural moment to wire `compose.uiTest` because that change ships actually-testable rendering logic; this follow-up may fold in there.
- [ ] 9.4 Add a `FOLLOW_UPS.md` entry `mobile-negative-requirement-ci-grep` (deferred Detekt rule enforcing the negative grep scenarios in `specs/mobile-app-scaffold/spec.md` — no Ktor client, no ad-hoc HTTP, no auth identifiers, no FCM identifiers, no hardcoded API URLs, no backend/infra module deps). Cite the existing `RawFromPostsRule` / `BlockExclusionJoinRule` / `RedisHashTagRule` patterns in `lint/detekt-rules/` as canonical precedents for negative-grep Detekt rules. The deferral is explicit: without a Detekt rule, three of the spec's negative requirements (No Ktor client, No auth identifiers, No FCM identifiers) plus the new scenarios (No ad-hoc HTTP, No hardcoded API base URLs, No backend or infra module dependencies) regress silently. Task 7.3 in Section 7 OFFERS a lighter-weight commonTest-level file-walk as an interim backstop — implementer's choice whether to ship that now or wait for the Detekt rule.
- [ ] 9.5 Add a follow-up note about the **brand theme tokens fold-in to Mobile #2**: when Mobile #2 (`shared-resources-moko-bootstrap`) is proposed, its `proposal.md` MUST acknowledge that brand color and typography tokens are pulled into its scope (per design Decision 3 of this change). NOT a separate `FOLLOW_UPS.md` entry — this is a fold-in, not a deferral — but the note in the next change's design is the bookkeeping that prevents the scope from getting lost.
- [ ] 9.6 Update [`docs/04-Architecture.md`](../../../docs/04-Architecture.md) § Mobile Status text from "JetBrains Compose Multiplatform wizard template" to a one-paragraph description of the new scaffold (navigation + Koin + theme + placeholder screen). May land as part of this change's commits OR as a small docs-only PR after this change archives — author's call.
- [ ] 9.7 After archive: [`openspec/project.md`](../../project.md) § Mobile + Admin Scaffolding Priority menu Mobile #1 entry will be implicitly complete once this PR squash-merges; no edit to that menu is required (the menu lists the change names, not their status; the flip-trigger condition checks for Mobile #5 + Admin #4 merges, not Mobile #1).
