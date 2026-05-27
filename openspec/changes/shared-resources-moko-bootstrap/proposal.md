## Why

Mobile #1 (`mobile-app-scaffold-replace-wizard`, PR [#105](https://github.com/aditrioka/nearyou-id/pull/105), squash-merged 2026-05-14) shipped a production-shaped Compose Multiplatform scaffold (Voyager nav + Koin DI + Material 3 theme + placeholder `HomeScreen`) with two deliberate gaps recorded as Mobile #1 [design.md Decision 3](../archive/2026-05-14-mobile-app-scaffold-replace-wizard/design.md): (a) `NearYouTheme` falls back to vanilla Material 3 `lightColorScheme()` / `darkColorScheme()` defaults because no brand color/typography tokens exist yet, and (b) `HomeScreen` renders hardcoded literals (`"NearYouID"`, `"v1.0"`) because no Moko Resources module exists yet to satisfy the "no hardcoded UI strings" invariant in [`openspec/project.md`](../../project.md) § Coding Conventions. Both gaps were explicitly deferred to this change (Mobile #2 in [`openspec/project.md`](../../project.md) § Mobile + Admin Scaffolding Priority menu). Closing them now unblocks Mobile #3 (`mobile-auth-google-signin-flow`, which needs branded auth-screen strings + theme tokens), Mobile #4 (`mobile-age-gate-screen`), and Mobile #5 (`mobile-nearby-timeline-screen` — the first product surface that sets the visual pattern every subsequent screen inherits).

## What Changes

- **NEW** Gradle module `:shared:resources` configured with the Moko Resources plugin (`dev.icerock.moko:resources`) for `commonMain` + Android (`androidTarget()`) + iOS (`iosArm64`, `iosSimulatorArm64`) targets — matching Mobile #1's existing iOS target set — registered in [`settings.gradle.kts`](../../../settings.gradle.kts) and described in [`dev/module-descriptions.txt`](../../../dev/module-descriptions.txt) (the root `README.md` § What's in this repo block auto-syncs via [`dev/scripts/sync-readme.sh --write`](../../../dev/scripts/sync-readme.sh) per [`openspec/project.md`](../../project.md) § Documentation Maintenance).
- **NEW** brand color tokens — Material 3 `ColorScheme` defined in `:shared:resources` commonMain, consumed by `NearYouTheme`. Light scheme uses the claude.ai/design palette (primary `#1E4FD6` and 29 other Material 3 roles). Dark scheme mechanically derived from primary via HCT tonal stops (Material Theme Builder algorithm). Reserved-purpose accents (coral location pin, amber Premium badge, semantic success/warning/link) exposed as `ColorScheme` extension properties — NOT mapped to M3 `secondary`/`tertiary` so default M3 widgets stay visually coherent.
- **NEW** brand typography — Plus Jakarta Sans variable `.ttf` (OFL-licensed, Indonesian foundry — designed by Tokotype for Pemprov DKI Jakarta) bundled in `commonMain/moko-resources/fonts/`. Single family across all 13 Material 3 typography roles via variable weight axis 200–800. `NearYouTypography` defined in commonMain as a `Typography` instance.
- **NEW** in-app brand logo — both variants of the user-supplied SVG re-exported with palette primary `#1E4FD6` (replacing source `#0B4FA8` / `#014CAB` for icon → splash → UI consistency); bundled as `logo_brand_light.svg` (blue-on-white) + `logo_brand_dark.svg` (white-on-blue) in `commonMain/moko-resources/images/`, selected via `isSystemInDarkTheme()`.
- **NEW** app launcher icon — Android adaptive icon (`mipmap-anydpi-v26/ic_launcher.xml` + `ic_launcher_round.xml`) with white-on-blue (`#1E4FD6`) default + blue-on-white alternate; Android 13+ themed icon support via `monochrome` attribute; iOS `Assets.xcassets/AppIcon.appiconset/` with all 17 required PNG sizes generated via `dev/scripts/generate-ios-app-icons.sh`.
- **NEW** foundational Bahasa Indonesia string surface — 10 strings in `commonMain/moko-resources/MR/base/strings.xml` (`app_name`, `error_generic`, `cta_continue`/`cta_cancel`/`cta_retry`/`cta_close`, `loading`, `empty_state_generic`, `home_placeholder_title`, `home_placeholder_version`) covering the placeholder `HomeScreen` plus the foundational vocabulary reserved for Mobile #3–#5.
- **MODIFIED** `NearYouTheme` (in `:mobile:app`) now consumes `NearYouColorScheme` + `NearYouTypography` + ColorScheme extension properties from `:shared:resources` instead of vanilla Material 3 defaults; extension properties wired via `CompositionLocal` so `MaterialTheme.colorScheme.locationPin` resolves at every call site.
- **MODIFIED** `HomeScreen` (in `:mobile:app`) now consumes `MR.strings.home_placeholder_title` + `MR.strings.home_placeholder_version` via Moko Resources `desc().localized()` (or `stringResource()` equivalent) instead of hardcoded literals — first real consumer of the resources module.
- **NEW** `material3 = "1.3.x"` stable pin in [`gradle/libs.versions.toml`](../../../gradle/libs.versions.toml) (with Version Pinning Decisions Log entry in [`docs/09-Versions.md`](../../../docs/09-Versions.md)) plus `moko-resources` plugin coordinates; Material 3 1.4-alpha + expressive components explicitly deferred.
- Verify Detekt's "no hardcoded UI strings" rule passes against `:mobile:app` (this is the first opportunity to validate the rule on real Moko Resources consumption — Mobile #1 shipped with temporary hardcoded literals because no resource module existed).

## Capabilities

### New Capabilities

- `shared-resources`: covers the `:shared:resources` Gradle module contract — Moko Resources convention (`MR.strings.*`, `MR.images.*`, `MR.fonts.*` consumption from commonMain); brand color token surface (`NearYouColorScheme` + `ColorScheme.locationPin` / `.premiumBadge` / `.success` / `.warning` / `.link` extension properties); brand typography surface (`NearYouTypography` built on Plus Jakarta Sans); brand logo provision (light/dark variants); foundational Bahasa Indonesia string surface; dark scheme derivation policy (mechanically derived from primary HCT tonal stops, hand-tuned dark deferred).

### Modified Capabilities

- `mobile-app-scaffold`: `NearYouTheme` now consumes brand tokens from `:shared:resources` rather than Material 3 defaults; `HomeScreen` placeholder now consumes strings via Moko Resources rather than hardcoded literals; the scaffold's "Trade-off accepted: Default Material 3 colors will look generic and not 'NearYouID-branded' until brand tokens land" note (Mobile #1 design.md Decision 3) is now satisfied.

## Impact

**Code:**
- New module `:shared:resources` (`shared/resources/build.gradle.kts`, `shared/resources/src/commonMain/`, `shared/resources/src/commonMain/moko-resources/`)
- `:mobile:app` gains `implementation(projects.shared.resources)` dependency
- `mobile/app/src/commonMain/kotlin/id/nearyou/app/theme/NearYouTheme.kt` reads from `:shared:resources` (NearYouColorScheme + NearYouTypography)
- `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/home/HomeScreen.kt` reads strings via Moko Resources
- Android launcher icon files under `mobile/app/src/androidMain/res/mipmap-anydpi-v26/`, `drawable/`, `values/colors.xml`
- iOS launcher icon under `iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/` (17 PNGs)

**Build / dependencies:**
- `gradle/libs.versions.toml` adds `material3` (1.3.x stable), `moko-resources` (latest stable), `plus-jakarta-sans` font binary (vendored in repo)
- `gradle/libs.versions.toml` records BOTH version pins (`material3`, `moko-resources`) with Version Pinning Decisions Log entries in [`docs/09-Versions.md`](../../../docs/09-Versions.md)
- `settings.gradle.kts` registers `:shared:resources`
- `dev/module-descriptions.txt` gets a one-line entry for `:shared:resources`; root `README.md` § What's in this repo block auto-regenerated via `dev/scripts/sync-readme.sh --write`
- New helper script `dev/scripts/generate-ios-app-icons.sh` (rsvg-convert with pdftocairo fallback)

**Docs (no edits required during this change — already point to Mobile #2 as the consumer):**
- [`openspec/project.md`](../../project.md) § Module Structure (`:shared:resources` SCAFFOLD NEXT row — flipped to shipped post-archive)
- [`docs/04-Architecture.md`](../../../docs/04-Architecture.md) § Dependency Isolation Pattern (same SCAFFOLD NEXT row)
- [`docs/03-UX-Design.md`](../../../docs/03-UX-Design.md) — string copy already lives here verbatim; this change starts consuming it
- [`docs/08-Roadmap-Risk.md`](../../../docs/08-Roadmap-Risk.md) Pre-Phase 1 item 31 (`:shared:resources` in the abstraction-scaffolding list)

**CI / lint:**
- Detekt "no hardcoded UI strings" rule (per [`openspec/project.md`](../../project.md) § Coding Conventions) gets its first real-consumption validation pass — verify it correctly accepts Moko Resources call sites + correctly rejects any remaining hardcoded literals in `:mobile:app`
- Both Android + iOS build verification: `./gradlew :mobile:app:assembleDebug` produces APK; `./gradlew :mobile:app:linkDebugFrameworkIosArm64` produces iOS framework
- No runtime change → no staging deploy required → no Section 6 staging-smoke block in `tasks.md`; the "Section 6 N/A" note goes in the eventual `/opsx:archive` commit body per [`openspec/project.md`](../../project.md) § Archive timing under the one-PR convention

**Out of scope (tracked):**
- Dedicated hand-tuned dark palette (mechanically derived dark ships; follow-up `mobile-dark-palette-tuning` if visual review identifies issues)
- Material 3 1.4-alpha + expressive components (`WavyProgressIndicator`, `MaterialExpressiveTheme`, expressive button/FAB APIs)
- Sentry KMP module-isation (already split as `infra-sentry-kmp-module-isation` per Mobile #1 design.md Decision 5)
- All feature screens (Mobile #3 auth, #4 age gate, #5 nearby timeline)
- iOS `PrivacyInfo.xcprivacy` finalization (Pre-Phase 1 task 33 + Phase 3 iOS block)
