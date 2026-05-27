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
- **THEN** the value resolves to `Color(0xFFEEF0F4)` (the surfaceVariant family neutral per [`design.md`](../../design.md) Decision 3 table), explicitly NOT `Color(0xFFFF7A5C)` (coral) — per [`design.md`](../../design.md) Decision 2

#### Scenario: NearYouColorScheme.light maps M3 tertiary to a neutral

- **WHEN** inspecting `NearYouColorScheme.light.tertiary`
- **THEN** the value resolves to the documented neutral stop (per [`design.md`](../../design.md) Decision 3 table), explicitly NOT `Color(0xFFF4B740)` (amber) — per [`design.md`](../../design.md) Decision 2

#### Scenario: NearYouColorScheme.light outline meets M3 3:1 contrast guideline

- **WHEN** inspecting `NearYouColorScheme.light.outline`
- **THEN** the value resolves to `Color(0xFF79747E)` (the M3 default outline tone, which passes WCAG 4.05:1 against `surface = #FFFFFF` per [`design.md`](../../design.md) Decision 9), NOT the palette author's `Color(0xFFD9DDE5)` value (1.36:1, fails) and NOT the earlier proposal value `Color(0xFF9CA3AF)` (2.54:1, also fails); the palette author's `Color(0xFFD9DDE5)` is preserved on `outlineVariant` instead (purely decorative, no contrast requirement)

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

### Requirement: App launcher icon replaces wizard-default assets with NearYouID-branded variants

The `:mobile:app` module SHALL ship NearYouID-branded launcher icons by **replacing in-place** Mobile #1's existing JetBrains-wizard-default launcher assets (no file additions to net-new mipmap locations). Both platforms maintain their existing structural conventions — Android uses adaptive icon + raster fallbacks; iOS uses the modern single-1024 universal Asset Catalog idiom (NOT the legacy multi-size pattern).

**Android** SHALL ship: (a) replaced `mipmap-anydpi-v26/ic_launcher.xml` + `ic_launcher_round.xml` adaptive icons with foreground = white hexagon glyph vector drawable + background = `@color/ic_launcher_background` (`#1E4FD6` in `values/colors.xml`); (b) replaced `drawable/ic_launcher_background.xml` (Mobile #1 shipped a wizard vector gradient there — this change converts it to a color reference) + replaced `drawable-v24/ic_launcher_foreground.xml` (Mobile #1 shipped the wizard vector); (c) regenerated 10 raster fallback PNGs in `mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png` + `ic_launcher_round.png` (LOAD-BEARING under `min-sdk = 24` for Android 7.x devices that ignore the adaptive XML); (d) NEW `drawable/ic_launcher_monochrome.xml` for the Android 13+ themed-icon `<monochrome>` attribute; (e) NEW blue-on-white alternate (`drawable/ic_launcher_foreground_alt.xml` + `mipmap-anydpi-v26/ic_launcher_alt.xml`) wired via dormant `<activity-alias>` in `AndroidManifest.xml`.

**iOS** SHALL ship: replaced `iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/app-icon-1024.png` with 3 NearYouID-branded 1024×1024 PNG variants (default + `luminosity:dark` + `luminosity:tinted`) rasterized from the modified SVG via `dev/scripts/generate-ios-app-icons.sh` (which uses `rsvg-convert` with `pdftocairo` fallback). The existing `Contents.json` shape (modern iOS 14+ single-size universal idiom with appearance variants) is preserved — only the PNG bytes change.

#### Scenario: Android adaptive icon files retain their existing paths

- **WHEN** inspecting `mobile/app/src/androidMain/res/mipmap-anydpi-v26/`
- **THEN** the directory contains `ic_launcher.xml` AND `ic_launcher_round.xml` (both replaced in-place — same filenames as Mobile #1) AND a NEW `ic_launcher_alt.xml` for the blue-on-white alternate

#### Scenario: Android adaptive icon background is brand primary

- **WHEN** inspecting `mobile/app/src/androidMain/res/values/colors.xml`
- **THEN** the file contains `<color name="ic_launcher_background">#1E4FD6</color>`

#### Scenario: Android adaptive icon foreground vector drawable is replaced and monochrome glyph is present

- **WHEN** inspecting `mobile/app/src/androidMain/res/drawable/` and `drawable-v24/`
- **THEN** `drawable-v24/ic_launcher_foreground.xml` exists (replaced in-place from Mobile #1's wizard default, rendering the white hexagon glyph) AND `drawable/ic_launcher_monochrome.xml` exists (NEW, referenced by `ic_launcher.xml`'s `<monochrome>` attribute for Android 13+ themed-icon support)

#### Scenario: Android raster fallback PNGs are regenerated for legacy Android 7.x

- **WHEN** inspecting `mobile/app/src/androidMain/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/`
- **THEN** each density directory contains both `ic_launcher.png` AND `ic_launcher_round.png` (10 PNGs total — replaced in-place from Mobile #1's wizard defaults), each rasterized at the density-appropriate resolution from the modified white-on-blue brand SVG

#### Scenario: iOS Asset Catalog preserves modern single-1024 universal idiom

- **WHEN** inspecting `iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/Contents.json`
- **THEN** the file declares 3 entries with `idiom = "universal"`, `size = "1024x1024"`, `platform = "ios"` — one default + one with `appearance = "luminosity" / value = "dark"` + one with `appearance = "luminosity" / value = "tinted"`; the `Contents.json` shape matches Mobile #1's shipped structure (only the PNG bytes change)

#### Scenario: iOS launcher icon PNG is replaced with NearYouID brand variants

- **WHEN** inspecting `iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/`
- **THEN** `app-icon-1024.png` is present (replaced from Mobile #1's wizard default, rasterized from the modified brand SVG) AND 2 additional 1024×1024 PNGs corresponding to the `luminosity:dark` and `luminosity:tinted` Contents.json entries are present

#### Scenario: iOS icon generation script is present and supports both rasterizers

- **WHEN** inspecting `dev/scripts/generate-ios-app-icons.sh`
- **THEN** the script is executable AND auto-detects either `rsvg-convert` (preferred) or `pdftocairo` (fallback) AND produces exactly 3 1024×1024 PNGs (default + dark + tinted variants) AND fails with a clear error if neither rasterizer is available

### Requirement: Reuse existing Compose Multiplatform material3 pin; add only moko-resources pin

The repository SHALL NOT introduce a new `material3` version pin in [`gradle/libs.versions.toml`](../../../../gradle/libs.versions.toml). The existing pin `material3 = "1.10.0-alpha05"` (`org.jetbrains.compose.material3:material3`, Compose Multiplatform stream) is reused as-is — it already exposes the full `ColorScheme` constructor surface this change requires. The repository SHALL add new `moko-resources` plugin + library entries with a Version Pinning Decisions Log entry in [`docs/09-Versions.md`](../../../../docs/09-Versions.md) per the project's Version Pinning policy.

#### Scenario: Existing material3 pin is preserved untouched

- **WHEN** inspecting `gradle/libs.versions.toml`
- **THEN** the `[versions]` section contains exactly one `material3` entry valued `"1.10.0-alpha05"` (preserved from Mobile #1's shipped state) AND no new `material3-jetpack` / `material3-android` / similar Jetpack-stream variant entry has been added

#### Scenario: moko-resources is pinned in libs.versions.toml

- **WHEN** inspecting `gradle/libs.versions.toml`
- **THEN** the `[versions]` section contains a `moko-resources` entry with a specific version (latest stable), AND `[libraries]` entries exist for both `moko-resources` and `moko-resources-compose`, AND a `[plugins]` entry exists for the `dev.icerock.mobile.multiplatform-resources` Gradle plugin

#### Scenario: Version Pinning Decisions Log has a moko-resources entry

- **WHEN** inspecting `docs/09-Versions.md` § Version Decisions table
- **THEN** the table contains a row for `moko-resources` listing the pinned version, pin date (2026-05-28), rationale (per [`design.md`](../../design.md) Decision 1 reference to the new module's Moko Resources dependency), and next review date

#### Scenario: No Version Pinning Decisions Log entry is added for material3

- **WHEN** inspecting `docs/09-Versions.md` § Version Decisions table
- **THEN** no NEW row is added for `material3` (the existing pin is preserved untouched, so no decision is being recorded here) — per [`design.md`](../../design.md) Decision 1 the existing alpha05 pin is reused, not re-decided

### Requirement: No hardcoded UI strings in :mobile:app verified by grep (Detekt rule deferred)

After this change is applied, the "no hardcoded UI strings in mobile source" convention from [`openspec/project.md`](../../project.md) § Coding Conventions SHALL be verified via an **explicit grep step** documented in this change's `tasks.md` Section 8 — NOT via a Detekt rule (the rule does not yet exist in `:lint:detekt-rules` and was deferred at Mobile #1 as the `FOLLOW_UPS.md` entry `mobile-negative-requirement-ci-grep`). Every UI string in `mobile/app/src/commonMain/`, `mobile/app/src/androidMain/`, and `mobile/app/src/iosMain/` SHALL be sourced via Moko Resources (`MR.strings.<name>.desc().localized()` or `stringResource(MR.strings.X)` Compose accessor), with no remaining hardcoded UI string literals. The future upgrade to a real Detekt rule SHALL be tracked as a separate follow-up entry in `FOLLOW_UPS.md`.

#### Scenario: Grep verification reports zero hardcoded UI string literals

- **WHEN** running the documented grep step from `tasks.md` Section 8 against `mobile/app/src/commonMain/`, `mobile/app/src/androidMain/`, and `mobile/app/src/iosMain/`
- **THEN** the grep finds zero offending matches (i.e., zero `Text("...")` / `Text(text = "...")` / `contentDescription = "..."` literal-string call sites that are not flowing through `stringResource(MR.strings.X)` or `MR.strings.X.desc().localized()` or an explicitly annotated `// hardcoded-string-allow:` line); the grep exit code is 0

#### Scenario: FOLLOW_UPS.md tracks the Detekt rule upgrade

- **WHEN** inspecting `FOLLOW_UPS.md` (in the repository root) after this change is applied
- **THEN** the file contains an entry named `mobile-hardcoded-strings-detekt-rule` (or equivalent kebab-case identifier) noting that the grep-based verification in this change should eventually be replaced by a `:lint:detekt-rules` rule modeled on the existing `RawFromPostsRule` / `BlockExclusionJoinRule` precedent

### Requirement: ColorScheme extension properties throw outside NearYouTheme scope

If a composable accesses `MaterialTheme.colorScheme.locationPin` (or any other NearYouColors-backed extension property) without being wrapped in a `NearYouTheme { ... }` provider, the extension property SHALL throw a clear runtime error rather than silently returning a default value. The `staticCompositionLocalOf<NearYouColors>` declaration in `ColorSchemeExtensions.kt` SHALL use `error("NearYouTheme not applied")` as the default-value lambda, NOT a fabricated default `NearYouColors` instance.

#### Scenario: Accessing locationPin outside NearYouTheme throws

- **WHEN** a `commonTest` runs `runComposeUiTest { setContent { Text("${MaterialTheme.colorScheme.locationPin}") } }` (no `NearYouTheme` wrapper)
- **THEN** the composition fails with an `IllegalStateException` whose message contains "NearYouTheme not applied" (or equivalent), proving that the absent `CompositionLocal` provider raises a loud error instead of returning a silent default

### Requirement: Plus Jakarta Sans falls back to FontFamily.SansSerif at runtime when font loading fails

The `NearYouTypography` `FontFamily` declaration SHALL include `FontFamily.SansSerif` as the LAST fallback entry (after the Plus Jakarta Sans `Font` declarations), so a runtime font-load failure (rare — the .ttf is bundled, not network-fetched) produces visible text in the platform sans-serif rather than empty glyphs. The fallback's position-as-last is mandatory: placing it first would silently never use Plus Jakarta Sans even when the .ttf loads successfully.

#### Scenario: FontFamily declaration places SansSerif as the LAST fallback

- **WHEN** inspecting `shared/resources/src/commonMain/kotlin/id/nearyou/resources/theme/NearYouTypography.kt`
- **THEN** the `FontFamily(...)` constructor's argument list ends with `FontFamily.SansSerif` (or a `Font(...)` declaration backed by `FontFamily.SansSerif`); all Plus Jakarta Sans `Font(MR.fonts.plus_jakarta_sans.*)` entries precede it

### Requirement: home_placeholder_version format substitution renders correctly at runtime

The `MR.strings.home_placeholder_version` string with format placeholder (declared as `Versi %1$s` in `strings.xml`) SHALL render `"Versi 1.0"` (or whatever runtime version is supplied) when invoked via `stringResource(MR.strings.home_placeholder_version, "1.0")` on both Android and iOS targets. The XML-level shape assertion (`%1$s` placeholder present) is necessary but not sufficient — a separate runtime test SHALL exercise the substitution to catch subtle Moko-vs-Android format-string differences.

#### Scenario: Runtime substitution test renders Versi 1.0

- **WHEN** a `commonTest` invokes `stringResource(MR.strings.home_placeholder_version, "1.0")` from inside a Compose UI test composition (or platform-equivalent on Android: `MR.strings.home_placeholder_version.toString(Resources, "1.0")`; on iOS: the equivalent Moko Resources accessor)
- **THEN** the returned string equals `"Versi 1.0"` exactly (NOT `"Versi %1$s"` literal); the runtime substitution works on both platforms

### Requirement: shared:resources namespace does not collide with mobile:app

The `:shared:resources` module's Android library `namespace` SHALL be set to `id.nearyou.resources` (or any string that is NOT `id.nearyou.app`), so the merged Android `R.class` produced when `:mobile:app` consumes `:shared:resources` does not collide. The R-class generation merges resource namespaces; collision causes a fatal `R.class` merge error at the AGP merge step.

#### Scenario: Android namespace declaration is distinct from :mobile:app

- **WHEN** inspecting `shared/resources/build.gradle.kts` `android { namespace = ... }` block
- **THEN** the namespace value is `"id.nearyou.resources"` (or any other string distinct from `:mobile:app`'s `id.nearyou.app` namespace per its existing `build.gradle.kts`)

#### Scenario: AGP merge step does not report R-class collision

- **WHEN** running `./gradlew :mobile:app:processDebugResources` (which merges `:shared:resources`'s resources into `:mobile:app`'s)
- **THEN** the task completes with exit 0 — no `R class duplication` / `resource merge conflict` error is reported

### Requirement: Moko app_name string coexists with platform-native Android strings.xml app_name

`:shared:resources` SHALL declare `app_name` in its Moko Resources `strings.xml` (for in-app Compose consumption via `stringResource(MR.strings.app_name)`). The pre-existing platform-native Android resource `mobile/app/src/androidMain/res/values/strings.xml` `<string name="app_name">NearYouID</string>` (referenced by `AndroidManifest.xml` `android:label`) SHALL be PRESERVED in place — Android requires it for the launcher label and cannot consume Moko Resources from the manifest. These two `app_name` resources are intentional parallel surfaces (Moko = in-app UI; platform-native = launcher label); both SHALL hold the same text content (`"NearYouID"`) so the user experience is consistent.

#### Scenario: Platform-native Android app_name is preserved

- **WHEN** inspecting `mobile/app/src/androidMain/res/values/strings.xml`
- **THEN** the file still contains `<string name="app_name">NearYouID</string>` — this change does NOT remove the platform-native string (the `AndroidManifest.xml` `android:label="@string/app_name"` reference would break otherwise)

#### Scenario: Both app_name resources hold identical text

- **WHEN** comparing `mobile/app/src/androidMain/res/values/strings.xml` `app_name` AND `shared/resources/src/commonMain/moko-resources/MR/base/strings.xml` `app_name`
- **THEN** both resolve to the exact same text value `"NearYouID"`; drift would produce a confusing UX where the launcher label and in-app brand identifier diverge
