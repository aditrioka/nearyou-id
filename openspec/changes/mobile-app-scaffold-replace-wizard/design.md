## Context

The `:mobile:app` module is the JetBrains Compose Multiplatform wizard scaffold — [`mobile/app/src/commonMain/kotlin/id/nearyou/app/App.kt`](../../../mobile/app/src/commonMain/kotlin/id/nearyou/app/App.kt) is a single 53-LOC `MaterialTheme { Column { Button("Click me!") + AnimatedVisibility } }`, [`MainActivity.kt`](../../../mobile/app/src/androidMain/kotlin/id/nearyou/app/MainActivity.kt) is the wizard `ComponentActivity` shell, [`MainViewController.kt`](../../../mobile/app/src/iosMain/kotlin/id/nearyou/app/MainViewController.kt) is the wizard `ComposeUIViewController { App() }`. There are no feature screens, no navigation, no DI, no networking. [`mobile/app/build.gradle.kts`](../../../mobile/app/build.gradle.kts) currently declares only the wizard's compose-runtime / foundation / material3 / ui / components.resources + AndroidX lifecycle deps + `:shared:tmp`.

[`openspec/project.md`](../../project.md) § Mobile + Admin Scaffolding Priority (added 2026-05-12, PR [#104](https://github.com/aditrioka/nearyou-id/pull/104)) names this change as Mobile #1 and the next four changes (#2 `shared-resources-moko-bootstrap`, #3 `mobile-auth-google-signin-flow`, #4 `mobile-age-gate-screen`, #5 `mobile-nearby-timeline-screen`) as the dependent chain. The flip-trigger condition — both Mobile #5 and Admin #4 squash-merged — is verifiably unmet (`git log | grep -E "(mobile-nearby-timeline-screen|admin-actions-log-viewer)"` returns zero matches as of this proposal's drafting date).

[`docs/03-UX-Design.md`](../../../docs/03-UX-Design.md) is the canonical user-experience spec source for the mobile flows that subsequent changes will implement — first app open lands on Global timeline (read-only, no login); login wall on Nearby/Following; auth flow with Google Sign-In (Android) / Apple Sign-In (iOS); age gate; analytics consent; location permission; etc. This change does NOT implement any of those flows — it only establishes the structural scaffolding (theming, navigation host, DI, entry composable) so the next four changes can ship their screens cleanly.

Solo-operator velocity context: the project's principal author (Adi/Oka) is one person. The scaffolding choices below optimize for low ceremony + fast iteration + minimal learning curve, accepting trade-offs against frameworks that scale to large teams or complex multi-stack apps.

## Goals / Non-Goals

**Goals:**
- Replace the wizard scaffold with a production-shaped Compose Multiplatform app structure.
- Establish a navigation framework with typed routes.
- Establish Koin DI for KMP with a placeholder `mobileModule` that subsequent changes register bindings into.
- Establish a Material 3 theme (light + dark) that follows the system preference.
- Establish ONE placeholder start-destination screen that will be replaced by `mobile-nearby-timeline-screen` (Mobile #5).
- Detach `:mobile:app` from `:shared:tmp` (the wizard's placeholder shared module).
- Keep both Android and iOS targets building green.

**Non-Goals:**
- No authentication flow (Mobile #3).
- No age gate (Mobile #4).
- No networking / Ktor client setup (lands in Mobile #3 when first needed).
- No Moko Resources / shared string catalog (Mobile #2; scaffold strings stay hardcoded English in this change, flipped to Moko in Mobile #2).
- No Sentry KMP wiring (split as follow-up `infra-sentry-kmp-module-isation` per project.md menu carve-out).
- No brand-specific theme tokens (colors, typography) — default Material 3 palette is sufficient for the scaffold.
- No actual product feature behavior (negative requirement enforced in the spec).
- No iOS CI wiring (Section 6 follow-up task; verify iOS locally during this change's lifecycle).
- No changes to `:backend:ktor`'s `:shared:tmp` reference — `:backend:ktor` continues to use `projects.shared.tmp`; only `:mobile:app` detaches.

## Decisions

### Decision 1: Navigation framework — **Voyager**

**Choice:** Adopt Voyager (`cafe.adriel.voyager:voyager-navigator` + supporting modules) as the navigation framework, pinned to the latest stable release at implementation time per the Pre-Phase 1 Version Pinning Decisions Log convention.

**Alternatives considered:**

- **Decompose** (`com.arkivanov.decompose:decompose`). More rigorous component model with child stacks, deep-link routing, state restoration, lifecycle integration. Strengths: industrial-grade for complex KMP apps; widely used in Arkadi-style architectures. Weaknesses: heavier ceremony (each screen is a `Component` with its own lifecycle); MVI-flavored which pushes a state-management style onto every feature; iOS state-restoration ceremony is non-trivial; learning curve is steep for a solo operator. Overkill at MVP scale (estimated <15 screens through Phase 3).
- **Vanilla state-based** (`var screen by mutableStateOf(Screen.Home)` + `when (screen) { Screen.Home -> HomeScreen() }`). Strengths: zero deps; trivially understandable. Weaknesses: doesn't scale past 3-4 screens; no back-stack management; would be re-replaced when Mobile #2-5 reach the timeline + auth + age-gate screens. Picking this now guarantees a churn rewrite within 4 changes.
- **AndroidX Navigation (Compose)**. Strengths: official AndroidX library, deep adoption on Android. Weaknesses: NOT KMP-compatible (Android-only as of this writing); using it would force iOS-specific navigation code that mirrors the Android side. Rejected on KMP grounds.

**Why Voyager:**
- KMP-native since 1.0 (commonMain APIs); no platform-specific glue beyond the entry composable.
- Small surface area: `Screen` interface + `Navigator { ... }` + `screenModelOf<T>()` from `voyager-koin` for Koin-backed view models.
- Typed routes via `Screen` implementations; adding a new screen is a one-file change (declare the `Screen`, navigate via `navigator.push(NewScreen())`).
- Integrates cleanly with Koin via `voyager-koin` module — `screenModelOf<T>()` resolves through the active Koin scope.
- Active maintenance and mature ecosystem (transitions, tab navigation, bottom-sheet navigation modules available as needed).
- Solo-operator-friendly: minimal boilerplate per screen; flat learning curve.

**Trade-off accepted:** Voyager's state-restoration on Android (especially for process-death) is less battle-tested than Decompose's. Mitigation: the placeholder screen has no state worth restoring; this concern matures alongside the apps' real screens as Mobile #5+ land. If state-restoration becomes load-bearing later, a swap-from-Voyager change is cheap because each screen is a small file.

**Implementation note:** the exact patch version is pinned in [`gradle/libs.versions.toml`](../../../gradle/libs.versions.toml) during `/opsx:apply` and recorded in the Version Pinning Decisions Log. Modules expected: `voyager-navigator`, `voyager-koin` (for the Koin integration; preferred over `voyager-screenmodel` so DI is consistent with the rest of the project).

### Decision 2: Koin DI startup pattern — **commonMain `initKoin()` helper + platform call sites**

**Choice:** Define an `initKoin(additionalConfig: KoinAppDeclaration? = null)` helper in commonMain that calls `startKoin { modules(mobileModule) }`. Android invokes it from `MainActivity.onCreate` BEFORE the first `setContent { App() }`. iOS invokes it from a top-level Kotlin function (commonMain) — `fun doInitKoin() = initKoin()` — that's callable from Swift. The Swift call site is the `init()` of the `iOSApp` struct in [`iosApp/iosApp/iOSApp.swift`](../../../iosApp/iosApp/iOSApp.swift), invoked **before** the `WindowGroup { ContentView() }` body builds. This ordering matters because [`ContentView.swift`](../../../iosApp/iosApp/ContentView.swift) bridges through `UIViewControllerRepresentable` to the KMP-side `MainViewController()` (which returns `ComposeUIViewController { App() }`); if Koin is uninitialized when `App()` first composes, any `koinInject` lookup added by a future change throws. The two-layer iOS bridge (Swift `iOSApp.init()` initializes Koin → Swift `ContentView` exposes `UIViewControllerRepresentable` → KMP `MainViewController()` returns `ComposeUIViewController { App() }`) is the canonical pre-`/opsx:apply` integration shape and the implementer MUST verify all three layers wire correctly before pushing.

**Alternatives considered:**

- **Android `Application` class.** Cleaner separation; standard Android pattern. Adds a new `NearYouApplication : Application` class and a manifest entry. Marginally more boilerplate; minimal benefit at scaffold scale. Defer to a later change if Application-class-level concerns emerge (e.g., FCM service registration in Mobile #3+).
- **Koin via Compose `KoinContext { ... }` only, no `startKoin`.** Embedded-Koin pattern works for compose-only apps but requires every Koin lookup site to be inside a `KoinContext`. Awkward for the eventual auth flow that needs Koin from non-composable code.
- **Manual DI / no Koin at all** (constructor injection through composables). Works for tiny scaffolds; falls apart when 3-4 view models need cross-screen state. Rejected — the project already uses Koin on the backend (`koin-ktor` 4.1.0), so the developer ergonomics are familiar.

**Why this pattern:**
- One canonical `initKoin()` callable from anywhere; idempotent guards via `getKoinOrNull()` if needed (handle Compose previews + tests that may double-init).
- `mobileModule = module { }` is the placeholder; subsequent changes add bindings inline (e.g., Mobile #3 adds `single<AuthClient> { ... }`).
- Voyager's `voyager-koin` integration resolves screen models through the active Koin scope without extra wiring.
- iOS-Swift call site is a one-liner; we don't need to expose the full Koin API to ObjC.

**Trade-off accepted:** Android `MainActivity.onCreate` is invoked once per activity instance, not once per process. If the user backgrounds + relaunches the app (process retained), `onCreate` may not re-fire — so init-from-`onCreate` is technically lazy-per-activity, not eager-per-process. Mitigation: `startKoin { ... }` is itself idempotent against a second call to `getKoinOrNull()`; we guard with `if (getKoinOrNull() == null) startKoin { ... }`. This is good enough for scaffold purposes; revisit when Mobile #3 lands and the auth flow has real init concerns.

### Decision 3: Theme — **default Material 3 light/dark, system-preference-driven; brand tokens fold into Mobile #2**

**Choice:** Define a `NearYouTheme(content: @Composable () -> Unit)` wrapper in commonMain (`mobile/app/src/commonMain/kotlin/id/nearyou/app/theme/NearYouTheme.kt`). The wrapper picks between `lightColorScheme()` (default Material 3 light) and `darkColorScheme()` (default Material 3 dark) based on `isSystemInDarkTheme()`. The `App()` composable wraps the navigation host in `NearYouTheme { ... }`. **Brand-specific color and typography tokens are explicitly deferred — and SHALL fold into Mobile #2 (`shared-resources-moko-bootstrap`)** alongside Moko Resources, not into a separate `mobile-brand-theme-tokens` change. Rationale: Moko Resources is the natural home for design tokens (colors, typography), and bundling avoids fragmenting brand scaffolding across two follow-ups. Mobile #2's eventual proposal MUST acknowledge this scope expansion when drafted.

**Alternatives considered:**

- **Custom brand-color tokens** (`primary = Color(0xFF...)`, etc.) inline in this scaffold change. Out of scope — keeps the scaffold focused on app structure rather than visual design decisions. Fold into Mobile #2 per the choice above.
- **MaterialTheme directly with no wrapper.** Works for one screen; doesn't scale for theme switching, dynamic-color support, or accessibility settings. The `NearYouTheme` wrapper is the standard pattern Compose apps grow into; spending five LOC now is cheaper than refactoring every screen later.
- **Dynamic Material You (Android 12+) `dynamicLightColorScheme(context)`.** Tempting for Android, but requires runtime-context plumbing and breaks the commonMain abstraction (Material You is Android-only). Defer until brand theming is decided.

**Trade-off accepted:** Default Material 3 colors will look generic and not "NearYouID-branded" until brand tokens land. This is intentional — the scaffold change should not be blocked on brand decisions.

### Decision 4: Placeholder start-destination screen — **`HomeScreen` rendering app name + version**

**Choice:** Define a Voyager `Screen` named `HomeScreen` at `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/home/HomeScreen.kt`. Its `@Composable Content()` renders a centered `Column` with a "NearYouID" title (Material 3 `MaterialTheme.typography.headlineLarge`) and a version string (read from `BuildConfig` on Android, hardcoded `"v1.0"` on iOS for the scaffold — version-string platform-helper deferred). No interactivity beyond Voyager's default `Navigator` plumbing.

**Alternatives considered:**

- **Empty screen** (just a blank Surface). Doesn't visually prove the theme + nav host are wired. Rejected — the placeholder should be visually verifiable.
- **Tab bar with Nearby/Following/Global stubs.** Premature — those flows ship in Mobile #5 + later. Adding tab stubs now would inflate scope.
- **Direct route to the eventual Global timeline.** Out of scope — Mobile #5 owns the timeline; this change must not preempt it.

### Decision 5: Sentry KMP wiring — **split as follow-up `infra-sentry-kmp-module-isation`**

**Choice:** Do NOT inline Sentry KMP wiring in this change. Open a focused follow-up change `infra-sentry-kmp-module-isation` that scaffolds the `:infra:sentry` module (per [`docs/04-Architecture.md`](../../../docs/04-Architecture.md) § Dependency Isolation Pattern), wires dSYM upload (iOS) + ProGuard mapping upload (Android) to the CI pipeline, configures the iOS framework integration, and adds the `SentryProvider expect/actual` per the doc's snippet.

**Rationale:**
- [`openspec/project.md`](../../project.md) § Mobile + Admin Scaffolding Priority menu Mobile #1 explicitly carves this out: "Sentry KMP wiring MAY split out as a focused follow-up `infra-sentry-kmp-module-isation` if scaffold scope grows beyond ~300 LOC."
- Adding `:infra:sentry` requires: new Gradle module + convention plugin alignment + `gradle/libs.versions.toml` Sentry SDK pins + dSYM upload CI step + ProGuard mapping upload CI step + iOS framework reconfig + Secret Manager DSN slot wiring. That's a meaningful chunk on its own.
- Keeping the scaffold change focused on app-structure concerns (theme, nav, DI) preserves a clean one-PR review surface. Sentry follow-up can land any time after this PR squash-merges.

### Decision 6: iOS CI — **defer to Section 6 follow-up task, verify locally during this change**

**Choice:** Do NOT block this change on CI running iOS framework link tasks. Verify iOS locally during implementation (via Xcode or `./gradlew :mobile:app:linkPodDebugFrameworkIosSimulatorArm64`). Add a Section 6 follow-up task to enable iOS CI later.

**Rationale:**
- [`.github/workflows/ci.yml`](../../../.github/workflows/ci.yml) does NOT currently run iOS framework link tasks (Linux runner; iOS toolchain absent). Wiring this up requires a macOS runner (paid GitHub Actions minutes), a Pod install step, and codesign infrastructure for any future archive task.
- That's a meaningful CI chunk on its own; bundling it with this scaffold inflates the PR.
- The `/next-change` skill instruction explicitly authorizes this deferral: "if iOS CI is not yet wired, do NOT block this change on it — open a Section 6 follow-up task to enable iOS CI later, and verify the iOS build locally instead."
- Risk window: iOS build can silently regress between this change and the iOS-CI follow-up. Mitigation: the author verifies iOS locally on every push during this change's lifecycle, and every subsequent mobile change verifies iOS locally before pushing the implementation commits.

### Decision 7: Test posture — **`kotlin.test` smoke + idempotency assertion; Compose UI test runner deferred to Mobile #5+**

**Choice:** Use `kotlin.test` assertions for all scaffold tests; do NOT wire the Compose UI test runner (`compose.uiTest` / `runComposeUiTest`) in this change. Delete the wizard's `ComposeAppCommonTest.kt`. Add two `commonTest` files: (a) `HomeScreenTest.kt` asserts `App()` composition runs without throwing; (b) `KoinInitTest.kt` asserts `initKoin()` is idempotent (two sequential invocations return the same Koin instance, no exception thrown). The light/dark color-scheme direct assertion is deferred to a follow-up because it requires Compose UI test runner wiring and the implementation (a 3-line `if/else` selecting between `lightColorScheme()` and `darkColorScheme()` based on `isSystemInDarkTheme()`) is low enough risk that visual verification during local Android + iOS smoke is acceptable for scaffold scope.

**Rationale:**
- The negative requirement guards against feature behavior, so there's little business logic to test. Smoke + idempotency are the two non-trivial assertions that warrant a test today.
- Wiring `compose.uiTest` adds Gradle config, dependency entries, and a learning-curve overhead that's not justified for two assertions. Mobile #5 (first real screen + state) is the natural moment to wire the Compose UI test runner because that change ships actually-testable rendering logic.
- The follow-up entry `mobile-theme-light-dark-direct-test` (added to `FOLLOW_UPS.md` in this change's Section 9) tracks the deferred theme assertion explicitly.

**Alternatives considered:**

- **Wire `compose.uiTest` in this change** to enable theme color-scheme direct assertion. Rejected — too much CI/Gradle infrastructure for two scaffold-stage assertions. Mobile #5 ships this naturally.
- **No tests at all (just verify Android + iOS build green).** Rejected — the idempotency guard in `initKoin()` is exactly the kind of invisible-on-refactor logic that needs a unit test, and the smoke test costs ~10 LOC.

### Decision 8: `:shared:tmp` retention — **leave the module intact; only detach `:mobile:app`'s dependency**

**Choice:** Keep `:shared:tmp` registered in [`settings.gradle.kts`](../../../settings.gradle.kts) and keep its `Greeting` class in place. Only remove `implementation(projects.shared.tmp)` from [`mobile/app/build.gradle.kts`](../../../mobile/app/build.gradle.kts) and the `Greeting().greet()` usage in `App.kt`. `:backend:ktor`'s reference is preserved as-is.

**Rationale:**
- The `module-structure` capability's "Shared scratch module" requirement says `:shared:tmp` "MUST NOT be referenced by any new feature code; consumers SHALL migrate to a real `:shared:<name>` module before adding logic." Backend's usage predates this change; refactoring `:backend:ktor` off `:shared:tmp` is out of scope here.
- Deleting `:shared:tmp` entirely would orphan the backend reference and is unnecessary churn.

### Decision 9: Package layout — **standard Compose feature-first layout under `id.nearyou.app/`**

**Choice:** Add subdirectories under `mobile/app/src/commonMain/kotlin/id/nearyou/app/`:

```
mobile/app/src/commonMain/kotlin/id/nearyou/app/
├── App.kt                          (top-level composable; hosts Navigator wrapped in NearYouTheme)
├── di/
│   ├── KoinInit.kt                 (initKoin() helper + iOS-callable doInitKoin())
│   └── MobileModule.kt             (mobileModule = module { } placeholder)
├── screens/
│   └── home/
│       └── HomeScreen.kt           (Voyager Screen placeholder; renders "NearYouID" + version)
└── theme/
    └── NearYouTheme.kt             (light + dark Material 3 ColorScheme + theme wrapper)
```

**Rationale:** standard "feature-first" layout that Mobile #2-#5 will extend (e.g., `screens/auth/`, `screens/agegate/`, `screens/timeline/`). The `di/`, `theme/`, `screens/` separation is conventional and discoverable.

## Risks / Trade-offs

- **Voyager's iOS state-restoration is less proven than Decompose's.** → Mitigation: at scaffold scope there's no state worth restoring. If real screens later need rigorous state restoration, a swap-from-Voyager is mechanical (one file per screen) and qualifies as its own change.
- **Koin double-initialization in Compose previews / unit tests.** → Mitigation: guard `initKoin()` with `if (getKoinOrNull() == null) { ... }`. Document the pattern in the `KoinInit.kt` KDoc.
- **iOS build silently regresses between this change and the iOS-CI follow-up.** → Mitigation: author verifies iOS locally on every push during this change's lifecycle; Section 6 follow-up to wire iOS CI is opened the moment this change archives.
- **Scope drift mid-implementation** ("let me just add login while I'm here"). → Mitigation: the negative requirement in the capability spec ("Scaffold does not introduce networking, auth, or feature behavior") gives the reviewer + author a clear line to police; multi-lens sub-agent review in Phase D catches violations.
- **Default Material 3 palette looks generic.** → Mitigation: this is intentional and time-boxed — brand theme tokens are a defined follow-up. Document the limitation in the placeholder screen's KDoc so a casual demo viewer understands.
- **Voyager-Koin module versioning drift from core Koin pin (4.1.0).** → Mitigation: pin `voyager-koin` to a version that explicitly supports Koin 4.x at implementation time; cross-check the Voyager release notes during `/opsx:apply`.
- **Detekt's "no hardcoded UI strings" lint rule may not yet exist for mobile** (transient hardcoded English in this change). → Mitigation: confirm during implementation whether such a Detekt rule applies to `:mobile:app`; if it does, file a follow-up to add a temporary suppression annotation that flips when Mobile #2 (Moko Resources) lands. If it does not yet exist, no action needed.

## Migration Plan

- **No runtime migration needed.** Pure mobile-side code change; no DB schema, no API contract change, no infrastructure provisioning, no backend deploy.
- **Pre-archive smoke** (per [`openspec/project.md`](../../project.md) § Change Delivery Workflow → Staging deploy timing): SKIP. This change has no backend or staging-deployed runtime impact; mark Section 6 N/A for the staging-deploy steps in `tasks.md` and document in the archive commit body.
- **Local verification gates** (must pass before push):
  - `./gradlew :mobile:app:assembleDebug` (Android assembly).
  - `./gradlew :mobile:app:linkPodDebugFrameworkIosSimulatorArm64` OR Xcode-side iOS framework link (iOS assembly).
  - `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` (project-wide lint + tests per CLAUDE.md pre-push verification).
- **Rollback strategy:** revert the squash-merge commit on `main`. No deployed state to undo; no data to clean up; subsequent mobile changes that depend on the scaffold will rebase onto the reverted state and re-stack.

## Open Questions

- **Exact Voyager version pin.** Resolve during `/opsx:apply` against the latest stable release at implementation time; record in the Version Pinning Decisions Log. Cross-check the chosen Voyager release notes for Koin-4.x compatibility.
- **iOS version-string source.** Resolve during `/opsx:apply` — hardcode `"v1.0"` for the scaffold (good enough for the placeholder); plumb through `Bundle.main.infoDictionary["CFBundleShortVersionString"]` via Kotlin/Native interop in a later change if version strings become user-facing.

Resolved during proposal review (no longer open):

- ~~Whether Android needs an `Application` class for the scaffold or `MainActivity.onCreate` is sufficient~~. **Resolved by Decision 2 trade-off paragraph** — start with `MainActivity.onCreate` plus `getKoinOrNull()` idempotency guard; add `Application` if a concrete need surfaces (e.g., FCM service registration when Mobile #3 lands). Not an open question; documented in Decision 2.
- ~~Whether to retain the wizard's `ComposeAppCommonTest.kt` or replace it~~. **Resolved by Decision 7** — delete the wizard test file outright (it asserts on the "Click me!" button); replace with `HomeScreenTest.kt` + `KoinInitTest.kt` per Decision 7's test posture. Tracked by `tasks.md` Section 6 + Section 7.
