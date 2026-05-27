## ADDED Requirements

### Requirement: Shared resources Gradle module exists with Moko Resources plugin

The repository SHALL contain a Gradle module `:shared:resources` declared in [`settings.gradle.kts`](../../../../settings.gradle.kts), with a `build.gradle.kts` configuring the Moko Resources plugin (`dev.icerock.moko.resources`) and the Kotlin Multiplatform plugin with `commonMain`, `androidMain` (via `androidTarget()`), and iOS source sets (`iosArm64`, `iosSimulatorArm64`) — matching the iOS target set declared by Mobile #1's `:mobile:app` consumer. The module SHALL be added to [`dev/module-descriptions.txt`](../../../../dev/module-descriptions.txt) with a one-line description, and the root [`README.md`](../../../../README.md) § What's in this repo block SHALL be auto-regenerated via `dev/scripts/sync-readme.sh --write` per [`openspec/project.md`](../../project.md) § Documentation Maintenance.

#### Scenario: Module is registered in settings.gradle.kts

- **WHEN** inspecting `settings.gradle.kts`
- **THEN** the file includes `include(":shared:resources")` (or equivalent typesafe accessor declaration)

#### Scenario: Moko Resources plugin is applied

- **WHEN** inspecting `shared/resources/build.gradle.kts`
- **THEN** the file applies the `dev.icerock.moko.resources` Gradle plugin (or equivalent typesafe ID) and declares `multiplatformResources { ... }` configuration block per Moko Resources convention

#### Scenario: Module description and README are in sync

- **WHEN** running `dev/scripts/sync-readme.sh --check`
- **THEN** the script exits with code 0 (no drift) — the `:shared:resources` entry appears in both `dev/module-descriptions.txt` AND the README's auto-generated module block between the `<!-- AUTOGEN:modules:start -->` / `<!-- AUTOGEN:modules:end -->` sentinels

#### Scenario: Common, Android, and iOS targets configured

- **WHEN** inspecting `shared/resources/build.gradle.kts`
- **THEN** the Kotlin Multiplatform extension declares `androidTarget()`, `iosArm64()`, AND `iosSimulatorArm64()` targets — matching the iOS target set declared by Mobile #1's `:mobile:app` consumer — with `commonMain` as the shared source set

### Requirement: Brand color scheme exposed as NearYouColorScheme

The `:shared:resources` module SHALL expose a `NearYouColorScheme` object (or equivalent named container) in commonMain with two Material 3 `ColorScheme` instances: `NearYouColorScheme.light` and `NearYouColorScheme.dark`. The light scheme SHALL use `primary = #1E4FD6` plus the full 30-role light palette documented in this change's [`design.md`](../../design.md) Decision 3 table. The dark scheme SHALL be mechanically derived from the light primary via the Material Theme Builder HCT tonal stop algorithm (primary tone 80, container tone 30, onPrimary tone 20, etc.) — derived values documented in [`design.md`](../../design.md) Decision 3 table.

#### Scenario: NearYouColorScheme.light has palette primary

- **WHEN** inspecting `shared/resources/src/commonMain/kotlin/id/nearyou/resources/theme/NearYouColorScheme.kt` (or equivalent commonMain path)
- **THEN** `NearYouColorScheme.light.primary` resolves to `Color(0xFF1E4FD6)` AND `NearYouColorScheme.light.onPrimary` resolves to `Color(0xFFFFFFFF)`

#### Scenario: NearYouColorScheme.light defines all required Material 3 1.3.x roles

- **WHEN** inspecting `NearYouColorScheme.light`
- **THEN** the `ColorScheme` is constructed with explicit values for ALL of: `primary`, `onPrimary`, `primaryContainer`, `onPrimaryContainer`, `inversePrimary`, `secondary`, `onSecondary`, `secondaryContainer`, `onSecondaryContainer`, `tertiary`, `onTertiary`, `tertiaryContainer`, `onTertiaryContainer`, `background`, `onBackground`, `surface`, `onSurface`, `surfaceVariant`, `onSurfaceVariant`, `surfaceTint`, `inverseSurface`, `inverseOnSurface`, `error`, `onError`, `errorContainer`, `onErrorContainer`, `outline`, `outlineVariant`, `scrim`, `surfaceBright`, `surfaceDim`, `surfaceContainerLowest`, `surfaceContainerLow`, `surfaceContainer`, `surfaceContainerHigh`, `surfaceContainerHighest`

#### Scenario: NearYouColorScheme.dark is available

- **WHEN** inspecting `NearYouColorScheme.dark`
- **THEN** the value is a `ColorScheme` instance with the same 30+ roles as `light`, populated from the derived values in [`design.md`](../../design.md) Decision 3 table

#### Scenario: NearYouColorScheme.light maps M3 secondary to a neutral, not coral

- **WHEN** inspecting `NearYouColorScheme.light.secondary`
- **THEN** the value resolves to `Color(0xFFEEEFF4)` (or the documented surfaceVariant family neutral), explicitly NOT `Color(0xFFFF7A5C)` (coral) — per [`design.md`](../../design.md) Decision 2

#### Scenario: NearYouColorScheme.light maps M3 tertiary to a neutral

- **WHEN** inspecting `NearYouColorScheme.light.tertiary`
- **THEN** the value resolves to the documented neutral stop (per [`design.md`](../../design.md) Decision 3 table), explicitly NOT `Color(0xFFF4B740)` (amber) — per [`design.md`](../../design.md) Decision 2

#### Scenario: NearYouColorScheme.light outline meets M3 contrast guideline

- **WHEN** inspecting `NearYouColorScheme.light.outline`
- **THEN** the value resolves to `Color(0xFF9CA3AF)` (the contrast-adjusted value per [`design.md`](../../design.md) Decision 9), NOT the palette author's `Color(0xFFD9DDE5)` value (which is preserved on `outlineVariant` instead)

#### Scenario: NearYouColorScheme.light scrim is correctly encoded

- **WHEN** inspecting `NearYouColorScheme.light.scrim`
- **THEN** the value resolves to `Color(0x8F0E1220)` (alpha 0x8F ≈ 56% over the onSurface base color)

### Requirement: Reserved-purpose accents exposed as ColorScheme extension properties

The `:shared:resources` module SHALL expose the brand's reserved-purpose accent colors (coral location pin, amber Premium badge) plus semantic status colors (success, warning, link) as `androidx.compose.material3.ColorScheme` extension properties accessible at every Compose call site via `MaterialTheme.colorScheme.<name>`. Each accent SHALL ship both light + dark variants and the full container/on-color set documented in [`design.md`](../../design.md) Decision 3 extension-property table.

#### Scenario: ColorScheme.locationPin is defined

- **WHEN** inspecting `shared/resources/src/commonMain/kotlin/id/nearyou/resources/theme/ColorSchemeExtensions.kt` (or equivalent commonMain path)
- **THEN** an extension property `val ColorScheme.locationPin: Color` is declared, returning `Color(0xFFFF7A5C)` for the light scheme via `CompositionLocal` lookup (or equivalent theme-aware mechanism)

#### Scenario: ColorScheme.premiumBadge is defined

- **WHEN** inspecting commonMain
- **THEN** an extension property `val ColorScheme.premiumBadge: Color` is declared, returning `Color(0xFFF4B740)` for the light scheme via `CompositionLocal` lookup

#### Scenario: ColorScheme extension properties cover the full container/on-color set

- **WHEN** inspecting commonMain
- **THEN** the following extension properties are ALL declared: `locationPin`, `locationPinContainer`, `onLocationPin`, `onLocationPinContainer`, `premiumBadge`, `premiumBadgeContainer`, `onPremiumBadge`, `onPremiumBadgeContainer`, `success`, `onSuccess`, `successContainer`, `onSuccessContainer`, `warning`, `onWarning`, `warningContainer`, `onWarningContainer`, `link`

#### Scenario: Extension properties resolve theme-aware values

- **WHEN** a composable invokes `MaterialTheme.colorScheme.locationPin` inside a `NearYouTheme { ... }` wrapper with system dark mode = OFF
- **THEN** the resolved value is the light-scheme value (`Color(0xFFFF7A5C)` for locationPin)

- **WHEN** the same composable is rendered with system dark mode = ON
- **THEN** the resolved value is the dark-scheme value (`Color(0xFFFFB59E)` for locationPin) per [`design.md`](../../design.md) Decision 3 extension-property table

### Requirement: Brand typography exposed as NearYouTypography backed by Plus Jakarta Sans

The `:shared:resources` module SHALL bundle the Plus Jakarta Sans variable `.ttf` file (OFL-licensed) at `shared/resources/src/commonMain/moko-resources/fonts/plus_jakarta_sans.ttf` and SHALL expose a `NearYouTypography` value (`androidx.compose.material3.Typography` instance) in commonMain that applies Plus Jakarta Sans to ALL 13 Material 3 type roles. The `FontFamily` declaration SHALL include `FontFamily.SansSerif` as a fallback so text renders even if Moko Resources font loading fails at runtime.

#### Scenario: Plus Jakarta Sans .ttf is bundled in Moko Resources

- **WHEN** inspecting `shared/resources/src/commonMain/moko-resources/fonts/`
- **THEN** the directory contains a `plus_jakarta_sans.ttf` file (or the variable-font variant filename Plus Jakarta Sans ships with) AND the OFL license file (per OFL terms)

#### Scenario: NearYouTypography covers all Material 3 type roles

- **WHEN** inspecting `shared/resources/src/commonMain/kotlin/id/nearyou/resources/theme/NearYouTypography.kt` (or equivalent commonMain path)
- **THEN** the `Typography` instance assigns explicit `TextStyle` values for ALL of: `displayLarge`, `displayMedium`, `displaySmall`, `headlineLarge`, `headlineMedium`, `headlineSmall`, `titleLarge`, `titleMedium`, `titleSmall`, `bodyLarge`, `bodyMedium`, `bodySmall`, `labelLarge`, `labelMedium`, `labelSmall`

#### Scenario: All type roles use Plus Jakarta Sans

- **WHEN** inspecting any `TextStyle` declared in `NearYouTypography`
- **THEN** its `fontFamily` resolves to a `FontFamily` constructed from `MR.fonts.plus_jakarta_sans.regular` (or equivalent Moko Resources `FontResource` accessor) followed by `FontFamily.SansSerif` as a fallback entry

### Requirement: Brand logo bundled in light + dark variants with palette primary

The `:shared:resources` module SHALL bundle two in-app brand logo SVG variants in `shared/resources/src/commonMain/moko-resources/images/`: `logo_brand_light.svg` (blue `#1E4FD6` glyph on white background, for use on light UI backgrounds) and `logo_brand_dark.svg` (white glyph on `#1E4FD6` blue background, for use on dark UI backgrounds). Both SHALL be re-exported from the user-supplied source SVGs (`Asset Logo Anon Hive.svg` + `Asset Logo Anon Hive Blue.svg`) with the source blue values `#0B4FA8` and `#014CAB` replaced by `#1E4FD6` per [`design.md`](../../design.md) Decision 4. Compose call sites SHALL select the variant via `isSystemInDarkTheme()`.

#### Scenario: Both logo variants are present

- **WHEN** inspecting `shared/resources/src/commonMain/moko-resources/images/`
- **THEN** the directory contains both `logo_brand_light.svg` AND `logo_brand_dark.svg`

#### Scenario: Light variant uses palette primary blue

- **WHEN** grepping `shared/resources/src/commonMain/moko-resources/images/logo_brand_light.svg` for hex color values
- **THEN** the only blue value referenced is `#1E4FD6` (or its rgb-decomposed equivalent); NO occurrence of `#014CAB` or `#0B4FA8`

#### Scenario: Dark variant uses palette primary blue

- **WHEN** grepping `shared/resources/src/commonMain/moko-resources/images/logo_brand_dark.svg` for hex color values
- **THEN** the only blue value referenced is `#1E4FD6` (or its rgb-decomposed equivalent); NO occurrence of `#0B4FA8` or `#014CAB`

#### Scenario: Both variants accessible via Moko Resources

- **WHEN** the Moko Resources Gradle task generates `MR` (typesafe resource accessor) for `:shared:resources`
- **THEN** `MR.images.logo_brand_light` AND `MR.images.logo_brand_dark` are both available for consumption from `:mobile:app` commonMain

### Requirement: Foundational Bahasa Indonesia string surface

The `:shared:resources` module SHALL provide a foundational set of Bahasa Indonesia UI strings in `shared/resources/src/commonMain/moko-resources/MR/base/strings.xml` (Moko Resources `base` locale convention), accessible from commonMain via `MR.strings.<name>.desc().localized()` or the platform-equivalent Compose `stringResource()` accessor. The initial set SHALL include at minimum: `app_name`, `error_generic`, `cta_continue`, `cta_cancel`, `cta_retry`, `cta_close`, `loading`, `empty_state_generic`, `home_placeholder_title`, `home_placeholder_version`. Text content SHALL match the Bahasa Indonesia copy in [`docs/03-UX-Design.md`](../../../../docs/03-UX-Design.md) for any string that has a documented canonical wording.

#### Scenario: strings.xml is present at the expected path

- **WHEN** inspecting `shared/resources/src/commonMain/moko-resources/MR/base/`
- **THEN** the directory contains a `strings.xml` file

#### Scenario: All foundational strings are declared

- **WHEN** inspecting `shared/resources/src/commonMain/moko-resources/MR/base/strings.xml`
- **THEN** the file contains `<string>` entries for ALL of: `app_name`, `error_generic`, `cta_continue`, `cta_cancel`, `cta_retry`, `cta_close`, `loading`, `empty_state_generic`, `home_placeholder_title`, `home_placeholder_version`

#### Scenario: Foundational strings are in Bahasa Indonesia

- **WHEN** reading the `<string name="error_generic">` value
- **THEN** the text is `"Ada yang salah. Coba lagi sebentar."` (or equivalent Bahasa Indonesia copy matching the project's canonical voice in [`docs/03-UX-Design.md`](../../../../docs/03-UX-Design.md))

- **WHEN** reading the `<string name="cta_cancel">` value
- **THEN** the text is `"Batal"` (matching the user-facing label canonical in [`docs/03-UX-Design.md`](../../../../docs/03-UX-Design.md))

#### Scenario: home_placeholder_version supports format substitution

- **WHEN** reading the `<string name="home_placeholder_version">` value
- **THEN** the text contains exactly one `%1$s` placeholder (or `%s` if Moko Resources uses positional-only substitution) so the rendered version string can be supplied at composition time

### Requirement: App launcher icon ships as platform-native assets with white-on-blue default

The `:mobile:app` module SHALL ship the Android adaptive launcher icon at `mobile/app/src/androidMain/res/mipmap-anydpi-v26/ic_launcher.xml` + `ic_launcher_round.xml`, with the foreground = white hexagon glyph vector drawable and background = `@color/ic_launcher_background` referencing `#1E4FD6` in `mobile/app/src/androidMain/res/values/colors.xml`. The `:mobile:app` module SHALL also ship the iOS launcher icon at `iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/` with all 17 required PNG sizes derived from the white-on-blue source SVG. A blue-on-white alternate Android variant SHALL ship at `mipmap-anydpi-v26/ic_launcher_alt.xml` referencing a blue-on-white foreground + white background, wired via an `<activity-alias>` in `AndroidManifest.xml` ready for a future user-selectable icon-theme feature. Android 13+ themed-icon support SHALL be enabled via the `<monochrome>` attribute on `ic_launcher.xml`.

#### Scenario: Android adaptive icon files are present

- **WHEN** inspecting `mobile/app/src/androidMain/res/mipmap-anydpi-v26/`
- **THEN** the directory contains both `ic_launcher.xml` AND `ic_launcher_round.xml` AND `ic_launcher_alt.xml`

#### Scenario: Android adaptive icon background is brand primary

- **WHEN** inspecting `mobile/app/src/androidMain/res/values/colors.xml`
- **THEN** the file contains `<color name="ic_launcher_background">#1E4FD6</color>`

#### Scenario: Android adaptive icon foreground vector drawable is present

- **WHEN** inspecting `mobile/app/src/androidMain/res/drawable/`
- **THEN** the directory contains `ic_launcher_foreground.xml` (vector drawable rendering the white hexagon glyph) AND a `monochrome` glyph drawable referenced by `ic_launcher.xml`'s `<monochrome>` attribute

#### Scenario: iOS Asset Catalog has the required icon sizes

- **WHEN** inspecting `iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/`
- **THEN** the directory contains a valid `Contents.json` plus PNG files covering at minimum the Apple-required sizes (20pt @ 2x/3x, 29pt @ 2x/3x, 40pt @ 2x/3x, 60pt @ 2x/3x, 76pt @ 1x/2x, 83.5pt @ 2x, 1024pt @ 1x) — 17 PNGs total

#### Scenario: iOS icon generation script is present

- **WHEN** inspecting `dev/scripts/`
- **THEN** an executable `generate-ios-app-icons.sh` script exists, invoking `rsvg-convert` (with `pdftocairo` fallback) against the source SVG to produce all required iOS Asset Catalog sizes

### Requirement: Material 3 version pinned to stable 1.3.x

The repository SHALL pin `material3` (Compose Multiplatform variant `org.jetbrains.compose.material3:material3` or Jetpack Compose variant `androidx.compose.material3:material3`) to a specific stable 1.3.x version in [`gradle/libs.versions.toml`](../../../../gradle/libs.versions.toml). The version pin SHALL be recorded in [`docs/09-Versions.md`](../../../../docs/09-Versions.md) § Version Decisions per the project's Version Pinning policy.

#### Scenario: material3 is pinned in libs.versions.toml

- **WHEN** inspecting `gradle/libs.versions.toml`
- **THEN** the `[versions]` section contains an entry of the form `material3 = "1.3.X"` where X is a specific patch number (e.g., `"1.3.2"`), AND a corresponding `[libraries]` entry references that version via `version.ref`

#### Scenario: Version Pinning Decisions Log has a corresponding entry

- **WHEN** inspecting `docs/09-Versions.md` § Version Decisions table
- **THEN** the table contains a row for `material3` listing the pinned version, pin date (2026-05-28), rationale (per [`design.md`](../../design.md) Decision 1), and next review date

#### Scenario: material3 1.4-alpha is not pulled in

- **WHEN** running `./gradlew :mobile:app:dependencies --configuration releaseRuntimeClasspath` (or equivalent multiplatform variant)
- **THEN** no resolved `material3` artifact has a version on the `1.4.X` line (no `-alpha`, no `-beta`, no `-rc`) — Material 3 1.4-alpha expressive components are explicitly out of scope per [`design.md`](../../design.md) Decision 1

### Requirement: Detekt no-hardcoded-UI-strings rule passes against :mobile:app

After this change is applied, the existing Detekt "no hardcoded UI strings in mobile source" rule (per [`openspec/project.md`](../../project.md) § Coding Conventions) SHALL pass cleanly against `:mobile:app` — every UI string in `mobile/app/src/commonMain/`, `mobile/app/src/androidMain/`, and `mobile/app/src/iosMain/` SHALL be sourced via Moko Resources (`MR.strings.<name>.desc().localized()` or platform equivalents), with no remaining hardcoded UI string literals.

#### Scenario: Detekt lint passes on :mobile:app

- **WHEN** running `./gradlew :mobile:app:detekt` from the repository root after this change is applied
- **THEN** the task completes with exit code 0 — no violation of the "no hardcoded UI strings" rule is reported

#### Scenario: No bare UI string literals in mobile sources

- **WHEN** grepping `mobile/app/src/commonMain`, `mobile/app/src/androidMain`, and `mobile/app/src/iosMain` for `Text("` followed by any non-`MR` / non-`stringResource` literal
- **THEN** no matches are found in first-party scaffold code (Compose runtime / framework internals are exempt; this targets app code only)
