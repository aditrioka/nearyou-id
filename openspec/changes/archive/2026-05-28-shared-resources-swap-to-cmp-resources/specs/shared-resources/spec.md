## RENAMED Requirements

- FROM: `### Requirement: Shared resources Gradle module exists with Moko Resources plugin`
- TO: `### Requirement: Shared resources Gradle module exists with Compose Multiplatform Resources`

- FROM: `### Requirement: Reuse existing Compose Multiplatform material3 pin; add only moko-resources pin`
- TO: `### Requirement: Reuse existing Compose Multiplatform material3 pin; remove moko-resources pin`

- FROM: `### Requirement: Moko app_name string coexists with platform-native Android strings.xml app_name`
- TO: `### Requirement: app_name string coexists with platform-native Android strings.xml app_name`

## MODIFIED Requirements

### Requirement: Shared resources Gradle module exists with Compose Multiplatform Resources

The repository SHALL contain a Gradle module `:shared:resources` declared in [`settings.gradle.kts`](../../../../settings.gradle.kts), with a `build.gradle.kts` configuring the Compose Multiplatform plugin (`org.jetbrains.compose`) — which provides the built-in `compose-components-resources` resource subsystem — and the Kotlin Multiplatform plugin with `commonMain`, `androidMain` (via `androidTarget()`), and iOS source sets (`iosArm64`, `iosSimulatorArm64`) matching the iOS target set declared by `:mobile:app`. The `dev.icerock.mobile.multiplatform-resources` (Moko) plugin SHALL NOT be applied. The module SHALL be added to [`dev/module-descriptions.txt`](../../../../dev/module-descriptions.txt) with a one-line description, and the root [`README.md`](../../../../README.md) § What's in this repo block SHALL be auto-regenerated via `dev/scripts/sync-readme.sh --write` per [`openspec/project.md`](../../project.md) § Documentation Maintenance.

#### Scenario: Module is registered in settings.gradle.kts

- **WHEN** inspecting `settings.gradle.kts`
- **THEN** the file includes `include(":shared:resources")` (or equivalent typesafe accessor declaration) within the `if (includeMobile.toBoolean()) { ... }` block

#### Scenario: Compose Multiplatform plugin is applied; Moko Resources plugin is NOT applied

- **WHEN** inspecting `shared/resources/build.gradle.kts`
- **THEN** the file applies the `org.jetbrains.compose` Gradle plugin (or `libs.plugins.composeMultiplatform` typesafe alias) AND does NOT apply `dev.icerock.mobile.multiplatform-resources` (no `libs.plugins.mokoResources` alias, no raw plugin ID reference, no `multiplatformResources { ... }` Moko-specific DSL block)

#### Scenario: compose-components-resources is wired in commonMain

- **WHEN** inspecting `shared/resources/build.gradle.kts`'s `commonMain.dependencies { ... }` block
- **THEN** the dependency `libs.compose.components.resources` (or equivalent typesafe accessor for `org.jetbrains.compose.components:components-resources`) is declared as `implementation(...)`; the Moko Resources library entries (`libs.moko.resources` / `libs.moko.resources.compose`) are NOT declared anywhere in the file

#### Scenario: Module description and README are in sync

- **WHEN** running `dev/scripts/sync-readme.sh --check`
- **THEN** the script exits with code 0 (no drift) — the `:shared:resources` entry appears in both `dev/module-descriptions.txt` AND the README's auto-generated module block between the `<!-- AUTOGEN:modules:start -->` / `<!-- AUTOGEN:modules:end -->` sentinels

#### Scenario: Common, Android, and iOS targets configured

- **WHEN** inspecting `shared/resources/build.gradle.kts`
- **THEN** the Kotlin Multiplatform extension declares `androidTarget()`, `iosArm64()`, AND `iosSimulatorArm64()` targets — matching the iOS target set declared by `:mobile:app`'s consumer — with `commonMain` as the shared source set

### Requirement: Brand typography exposed as NearYouTypography backed by Plus Jakarta Sans

The `:shared:resources` module SHALL bundle the Plus Jakarta Sans variable `.ttf` file (OFL-licensed) at `shared/resources/src/commonMain/composeResources/font/plus_jakarta_sans.ttf` (Compose Multiplatform Resources canonical layout) and SHALL expose a `NearYouTypography` value (`androidx.compose.material3.Typography` instance) in commonMain that applies Plus Jakarta Sans to ALL 13 Material 3 type roles. The font SHALL be loaded via the Compose Multiplatform Resources accessor `Font(Res.font.plus_jakarta_sans)` (the canonical CMP Resources font composable).

#### Scenario: Plus Jakarta Sans .ttf is bundled in Compose Resources layout

- **WHEN** inspecting `shared/resources/src/commonMain/composeResources/font/`
- **THEN** the directory contains a `plus_jakarta_sans.ttf` file (the variable-font variant from Mobile #2's bundled asset, byte-identical) AND the OFL license file is preserved (per OFL terms — the license file may live at the module root or inside the font directory; either is acceptable)

#### Scenario: NearYouTypography covers all Material 3 type roles

- **WHEN** inspecting `shared/resources/src/commonMain/kotlin/id/nearyou/resources/theme/NearYouTypography.kt` (or equivalent commonMain path)
- **THEN** the `Typography` instance assigns explicit `TextStyle` values for ALL of: `displayLarge`, `displayMedium`, `displaySmall`, `headlineLarge`, `headlineMedium`, `headlineSmall`, `titleLarge`, `titleMedium`, `titleSmall`, `bodyLarge`, `bodyMedium`, `bodySmall`, `labelLarge`, `labelMedium`, `labelSmall`

#### Scenario: All type roles use Plus Jakarta Sans via CMP Resources

- **WHEN** inspecting any `TextStyle` declared in `NearYouTypography`
- **THEN** its `fontFamily` resolves to a `FontFamily` constructed from `Font(Res.font.plus_jakarta_sans, ...)` (CMP Resources composable accessor); NO reference to `MR.fonts.*` or `asFont()` (Moko-specific accessors) appears in the file

### Requirement: Brand logo bundled in light + dark variants with palette primary

The `:shared:resources` module SHALL bundle two in-app brand logo SVG variants in `shared/resources/src/commonMain/composeResources/drawable/` (Compose Multiplatform Resources canonical layout): `logo_brand_light.svg` (blue `#1E4FD6` glyph on white background, for use on light UI backgrounds) and `logo_brand_dark.svg` (white glyph on `#1E4FD6` blue background, for use on dark UI backgrounds). Both files SHALL be byte-identical to the variants Mobile #2 shipped — this change is a layout move, NOT a re-export. Compose call sites SHALL select the variant via `isSystemInDarkTheme()` and access via `Res.drawable.logo_brand_{light,dark}`.

#### Scenario: Both logo variants are present in composeResources/drawable/

- **WHEN** inspecting `shared/resources/src/commonMain/composeResources/drawable/`
- **THEN** the directory contains both `logo_brand_light.svg` AND `logo_brand_dark.svg`; the Moko-convention directory `shared/resources/src/commonMain/moko-resources/images/` no longer exists OR is empty

#### Scenario: Light variant uses palette primary blue

- **WHEN** grepping `shared/resources/src/commonMain/composeResources/drawable/logo_brand_light.svg` for hex color values
- **THEN** the only blue value referenced is `#1E4FD6` (or its rgb-decomposed equivalent); NO occurrence of `#014CAB` or `#0B4FA8`

#### Scenario: Dark variant uses palette primary blue

- **WHEN** grepping `shared/resources/src/commonMain/composeResources/drawable/logo_brand_dark.svg` for hex color values
- **THEN** the only blue value referenced is `#1E4FD6` (or its rgb-decomposed equivalent); NO occurrence of `#0B4FA8` or `#014CAB`

#### Scenario: Both variants accessible via CMP Resources

- **WHEN** the Compose Multiplatform Resources Gradle codegen task generates the `Res` accessor class for `:shared:resources`
- **THEN** `Res.drawable.logo_brand_light` AND `Res.drawable.logo_brand_dark` are both available for consumption from `:mobile:app` commonMain via `painterResource(Res.drawable.X)`

### Requirement: Foundational Bahasa Indonesia string surface

The `:shared:resources` module SHALL provide a foundational set of Bahasa Indonesia UI strings in `shared/resources/src/commonMain/composeResources/values/strings.xml` (Compose Multiplatform Resources canonical layout — `values/` is the base locale, matching Android resource convention), accessible from commonMain via the Compose `stringResource(Res.string.<name>)` accessor. The string keys and text content SHALL be byte-identical to Mobile #2's shipped strings (this change is a layout move, NOT a content change). The initial set SHALL include at minimum: `app_name`, `error_generic`, `cta_continue`, `cta_cancel`, `cta_retry`, `cta_close`, `loading`, `empty_state_generic`, `home_placeholder_title`, `home_placeholder_version`. Text content SHALL match the Bahasa Indonesia copy in [`docs/03-UX-Design.md`](../../../../docs/03-UX-Design.md) for any string that has a documented canonical wording.

#### Scenario: strings.xml is present at the expected CMP Resources path

- **WHEN** inspecting `shared/resources/src/commonMain/composeResources/values/`
- **THEN** the directory contains a `strings.xml` file; the Moko-convention directory `shared/resources/src/commonMain/moko-resources/MR/base/` no longer exists OR contains no `strings.xml`

#### Scenario: All foundational strings are declared

- **WHEN** inspecting `shared/resources/src/commonMain/composeResources/values/strings.xml`
- **THEN** the file contains `<string>` entries for ALL of: `app_name`, `error_generic`, `cta_continue`, `cta_cancel`, `cta_retry`, `cta_close`, `loading`, `empty_state_generic`, `home_placeholder_title`, `home_placeholder_version`

#### Scenario: Foundational strings are in Bahasa Indonesia

- **WHEN** reading the `<string name="error_generic">` value
- **THEN** the text is `"Ada yang salah. Coba lagi sebentar."` (matching Mobile #2's shipped content exactly — this change does NOT rewrite copy)

- **WHEN** reading the `<string name="cta_cancel">` value
- **THEN** the text is `"Batal"` (matching the user-facing label canonical in [`docs/03-UX-Design.md`](../../../../docs/03-UX-Design.md))

#### Scenario: home_placeholder_version supports format substitution

- **WHEN** reading the `<string name="home_placeholder_version">` value
- **THEN** the text contains exactly one `%1$s` placeholder so the rendered version string can be supplied at composition time via `stringResource(Res.string.home_placeholder_version, "1.0")`

### Requirement: Reuse existing Compose Multiplatform material3 pin; remove moko-resources pin

The repository SHALL NOT introduce a new `material3` version pin in [`gradle/libs.versions.toml`](../../../../gradle/libs.versions.toml). The existing pin `material3 = "1.10.0-alpha05"` (`org.jetbrains.compose.material3:material3`, Compose Multiplatform stream) is reused as-is — it already exposes the full `ColorScheme` constructor surface this change requires. The repository SHALL REMOVE the `moko-resources` version pin + library entries + plugin entry that Mobile #2 added (per Mobile #2 `shared-resources-moko-bootstrap` design.md Decision-1-equivalent). The `compose-components-resources` library entry (already pinned via `composeMultiplatform = "1.10.3"` version variable, currently unused) becomes the actively-wired resources library — no new top-level version pin is introduced (the version is inherited from the existing `composeMultiplatform` variable).

#### Scenario: Existing material3 pin is preserved untouched

- **WHEN** inspecting `gradle/libs.versions.toml`
- **THEN** the `[versions]` section contains exactly one `material3` entry valued `"1.10.0-alpha05"` (preserved from Mobile #1's shipped state) AND no new `material3-jetpack` / `material3-android` / similar Jetpack-stream variant entry has been added

#### Scenario: moko-resources entries are removed from libs.versions.toml

- **WHEN** inspecting `gradle/libs.versions.toml`
- **THEN** the `[versions]` section contains NO `moko-resources` entry, the `[libraries]` section contains NO `moko-resources` or `moko-resources-compose` entries, and the `[plugins]` section contains NO `mokoResources` entry

#### Scenario: compose-components-resources entry is preserved (unchanged shape, now actively wired)

- **WHEN** inspecting `gradle/libs.versions.toml`
- **THEN** the `[libraries]` section contains `compose-components-resources = { module = "org.jetbrains.compose.components:components-resources", version.ref = "composeMultiplatform" }` — same shape as Mobile #2 left it, but the catalog accessor is now referenced from `:shared:resources/build.gradle.kts` (no longer dormant)

#### Scenario: Version Pinning Decisions Log records the swap

- **WHEN** inspecting `docs/09-Versions.md` § Version Decisions table
- **THEN** the table contains an entry documenting the moko-resources → CMP Resources swap on date 2026-05-28+, citing PR [#118](https://github.com/aditrioka/nearyou-id/pull/118)'s pre-implementation library re-check rule as the trigger; the previous Moko Resources row from Mobile #2 is either AMENDED with a "SUPERSEDED 2026-05-28" note OR removed, with the new swap row supplying the historical context either way

### Requirement: No hardcoded UI strings in :mobile:app verified by grep (Detekt rule deferred)

After this change is applied, the "no hardcoded UI strings in mobile source" convention from [`openspec/project.md`](../../project.md) § Coding Conventions SHALL be verified via an **explicit grep step** documented in this change's `tasks.md` Section 8 — NOT via a Detekt rule (the rule does not yet exist in `:lint:detekt-rules` and is still tracked as a `FOLLOW_UPS.md` entry `mobile-negative-requirement-ci-grep` (FOLLOW_UPS.md:735, proposing future OpenSpec change `mobile-negative-requirement-detekt-rule`)). Every UI string in `mobile/app/src/commonMain/`, `mobile/app/src/androidMain/`, and `mobile/app/src/iosMain/` SHALL be sourced via Compose Multiplatform Resources (`stringResource(Res.string.<name>)` Compose accessor), with no remaining hardcoded UI string literals. The future upgrade to a real Detekt rule SHALL be tracked by the existing `FOLLOW_UPS.md` entry (retargeted to accept `Res.string.X` instead of `MR.strings.X` as part of this change).

#### Scenario: Grep verification reports zero hardcoded UI string literals

- **WHEN** running the documented grep step from `tasks.md` Section 8 against `mobile/app/src/commonMain/`, `mobile/app/src/androidMain/`, and `mobile/app/src/iosMain/`
- **THEN** the grep finds zero offending matches (i.e., zero `Text("...")` / `Text(text = "...")` / `contentDescription = "..."` literal-string call sites that are not flowing through `stringResource(Res.string.X)` or `Res.string.X` direct access or an explicitly annotated `// hardcoded-string-allow:` line); the grep exit code is 0

#### Scenario: FOLLOW_UPS.md tracks the Detekt rule upgrade with retargeted accessor

- **WHEN** inspecting `FOLLOW_UPS.md` (in the repository root) after this change is applied
- **THEN** the `mobile-negative-requirement-ci-grep` (FOLLOW_UPS.md:735, proposing future OpenSpec change `mobile-negative-requirement-detekt-rule`) entry (or equivalent kebab-case identifier) notes that the grep-based verification in this change should eventually be replaced by a `:lint:detekt-rules` rule modeled on the existing `RawFromPostsRule` / `BlockExclusionJoinRule` precedent — AND the entry's example accessor pattern references `Res.string.X` (CMP Resources), NOT the legacy `MR.strings.X` (Moko Resources) wording Mobile #2 originally used

### Requirement: Plus Jakarta Sans falls back to platform sans-serif when font loading fails

The brand theme SHALL load Plus Jakarta Sans via the **canonical JetBrains CMP Resources preload pattern**: a `FontFamilyResolver.preload(brandFamily)` call inside a `LaunchedEffect` coroutine scope (where `try`/`catch` is legal — `preload()` is `suspend` and the Compose-compiler invariant against exception-catching around `@Composable` calls does NOT apply to suspend coroutines). On preload failure (`MissingResourceException` / `IllegalStateException` / any throwable), state flips to a `brandFontFailed` flag and recomposition supplies vanilla `Typography()` so platform sans-serif renders text reliably. This pattern preserves the Mobile #2 spec contract ("font issues never reach users as blank/crashed text") while honoring Compose's runtime constraints + the documented JetBrains-canonical pattern per [CMP Resources usage docs](https://kotlinlang.org/docs/multiplatform/compose-multiplatform-resources-usage.html) and the precedent of real-world bundled-font runtime failures in [JetBrains issue #4111](https://github.com/JetBrains/compose-multiplatform/issues/4111), [#3472](https://github.com/JetBrains/compose-multiplatform/issues/3472), [#4387](https://github.com/JetBrains/compose-multiplatform/issues/4387) (build-time codegen validation alone does NOT eliminate runtime-missing-font failure mode).

The Moko-era in-function null-guard pattern (`if (brandFont == null) return Typography()` inside `nearYouTypography()`) is REMOVED — CMP's `Font(...)` is `@Composable` and returns non-null, so the null check is impossible AND irrelevant. The defensive responsibility moves UP the stack to `NearYouTheme` where the suspend-scope preload allows proper exception capture.

#### Scenario: brandFontFamily is exposed as a Composable helper

- **WHEN** inspecting `shared/resources/src/commonMain/kotlin/id/nearyou/resources/theme/NearYouTypography.kt`
- **THEN** the file declares `@Composable fun brandFontFamily(): FontFamily = FontFamily(Font(Res.font.plus_jakarta_sans))` (or equivalent shape exposing the brand `FontFamily` as a `@Composable` so the caller can preload it); the Font construction is NOT wrapped in `runCatching` / `try` / `catch` (forbidden by Compose's no-exception-catching-around-Composable invariant)

#### Scenario: nearYouTypography is a pure transformation taking the family as a parameter

- **WHEN** inspecting the `nearYouTypography(brandFamily: FontFamily): Typography` signature
- **THEN** the function takes a `FontFamily` parameter (not constructed in-function); all 13 Material 3 type roles (`displayLarge` through `labelSmall`) have `fontFamily` set to `brandFamily` via `.copy(fontFamily = brandFamily)`; the function is a pure transformation with no font-loading concerns of its own

#### Scenario: NearYouTheme preloads the brand font in a LaunchedEffect with try-catch fallback

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/theme/NearYouTheme.kt`
- **THEN** the `NearYouTheme` composable contains: (1) `val resolver = LocalFontFamilyResolver.current` to access the platform's font resolver; (2) `val brandFamily = brandFontFamily()` to construct the family; (3) `LaunchedEffect(brandFamily) { try { resolver.preload(brandFamily); brandFontReady = true } catch (_: Throwable) { brandFontFailed = true } }` (or equivalent suspend-scope try/catch) so the preload failure is captured at the coroutine level, NOT at composition level; (4) state-conditional `typography` selection — `nearYouTypography(brandFamily)` only when preload completed successfully, `Typography()` (vanilla / platform sans-serif fallback) otherwise

#### Scenario: Font load failure produces vanilla Typography fallback, NOT a crash

- **WHEN** the `FontFamilyResolver.preload(brandFamily)` call throws (e.g., `MissingResourceException` or `IllegalStateException` from `FontFamilyResolver` per JetBrains issues #4111 / #3472 / #4387)
- **THEN** the caught exception sets `brandFontFailed = true`; the next recomposition uses `Typography()` (platform sans-serif); text in the app continues rendering — the app does NOT crash; the failure should still surface in logs (Compose's default failure log) for diagnosis

### Requirement: home_placeholder_version format substitution renders correctly at runtime

The `Res.string.home_placeholder_version` string with format placeholder (declared as `Versi %1$s` in `strings.xml`) SHALL render `"Versi 1.0"` (or whatever runtime version is supplied) when invoked via `stringResource(Res.string.home_placeholder_version, "1.0")` on both Android and iOS targets. The XML-level shape assertion (`%1$s` placeholder present) is necessary but not sufficient — a separate runtime test SHALL exercise the substitution to catch subtle CMP Resources format-string differences between Android (`String.format`-style) and iOS (`NSString stringWithFormat:`-style) platform delegations.

#### Scenario: Runtime substitution test renders Versi 1.0

- **WHEN** a `commonTest` invokes `stringResource(Res.string.home_placeholder_version, "1.0")` from inside a Compose UI test composition (`runComposeUiTest { setContent { Text(stringResource(...)) } }`)
- **THEN** the returned string equals `"Versi 1.0"` exactly (NOT `"Versi %1$s"` literal); the runtime substitution works on both platforms

### Requirement: app_name string coexists with platform-native Android strings.xml app_name

`:shared:resources` SHALL declare `app_name` in its Compose Resources `strings.xml` (for in-app Compose consumption via `stringResource(Res.string.app_name)`). The pre-existing platform-native Android resource `mobile/app/src/androidMain/res/values/strings.xml` `<string name="app_name">NearYouID</string>` (referenced by `AndroidManifest.xml` `android:label`) SHALL be PRESERVED in place — Android requires it for the launcher label and cannot consume Compose Multiplatform Resources from the manifest. These two `app_name` resources are intentional parallel surfaces (CMP Resources = in-app UI; platform-native = launcher label); both SHALL hold the same text content (`"NearYouID"`) so the user experience is consistent.

#### Scenario: Platform-native Android app_name is preserved

- **WHEN** inspecting `mobile/app/src/androidMain/res/values/strings.xml`
- **THEN** the file still contains `<string name="app_name">NearYouID</string>` — this change does NOT remove the platform-native string (the `AndroidManifest.xml` `android:label="@string/app_name"` reference would break otherwise)

#### Scenario: Both app_name resources hold identical text

- **WHEN** comparing `mobile/app/src/androidMain/res/values/strings.xml` `app_name` AND `shared/resources/src/commonMain/composeResources/values/strings.xml` `app_name`
- **THEN** both resolve to the exact same text value `"NearYouID"`; drift would produce a confusing UX where the launcher label and in-app brand identifier diverge
