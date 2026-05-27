## Why

Mobile #1 (`mobile-app-scaffold-replace-wizard`, PR [#105](https://github.com/aditrioka/nearyou-id/pull/105), squash-merged 2026-05-14) shipped a production-shaped Compose Multiplatform scaffold (Voyager nav + Koin DI + Material 3 theme + placeholder `HomeScreen`) with two deliberate gaps recorded as Mobile #1 [design.md Decision 3](../archive/2026-05-14-mobile-app-scaffold-replace-wizard/design.md): (a) `NearYouTheme` falls back to vanilla Material 3 `lightColorScheme()` / `darkColorScheme()` defaults because no brand color/typography tokens exist yet, and (b) `HomeScreen` renders hardcoded literals (`"NearYouID"`, `"v1.0"`) because no Moko Resources module exists yet to satisfy the "no hardcoded UI strings" invariant in [`openspec/project.md`](../../project.md) § Coding Conventions. Both gaps were explicitly deferred to this change (Mobile #2 in [`openspec/project.md`](../../project.md) § Mobile + Admin Scaffolding Priority menu). Closing them now unblocks Mobile #3 (`mobile-auth-google-signin-flow`, which needs branded auth-screen strings + theme tokens), Mobile #4 (`mobile-age-gate-screen`), and Mobile #5 (`mobile-nearby-timeline-screen` — the first product surface that sets the visual pattern every subsequent screen inherits).

## What Changes

- **NEW** Gradle module `:shared:resources` configured with the Moko Resources plugin (`dev.icerock.moko:resources`) for `commonMain` + Android (`androidTarget()`) + iOS (`iosArm64`, `iosSimulatorArm64`) targets — matching Mobile #1's existing iOS target set — registered in [`settings.gradle.kts`](../../../settings.gradle.kts) and described in [`dev/module-descriptions.txt`](../../../dev/module-descriptions.txt) (the root `README.md` § What's in this repo block auto-syncs via [`dev/scripts/sync-readme.sh --write`](../../../dev/scripts/sync-readme.sh) per [`openspec/project.md`](../../project.md) § Documentation Maintenance).
- **NEW** brand color tokens — Material 3 `ColorScheme` defined in `:shared:resources` commonMain, consumed by `NearYouTheme`. Light scheme uses the claude.ai/design palette (primary `#1E4FD6` and 29 other Material 3 roles). Dark scheme mechanically derived from primary via HCT tonal stops (Material Theme Builder algorithm). Reserved-purpose accents (coral location pin, amber Premium badge, semantic success/warning/link) exposed as `ColorScheme` extension properties — NOT mapped to M3 `secondary`/`tertiary` so default M3 widgets stay visually coherent.
- **NEW** brand typography — Plus Jakarta Sans variable `.ttf` (OFL-licensed, Indonesian foundry — designed by Tokotype for Pemprov DKI Jakarta) bundled in `commonMain/moko-resources/fonts/`. Single family across all 13 Material 3 typography roles via variable weight axis 200–800. `NearYouTypography` defined in commonMain as a `Typography` instance.
- **NEW** in-app brand logo — both variants of the user-supplied SVG re-exported with palette primary `#1E4FD6` (replacing source `#0B4FA8` / `#014CAB` for icon → splash → UI consistency); bundled as `logo_brand_light.svg` (blue-on-white) + `logo_brand_dark.svg` (white-on-blue) in `commonMain/moko-resources/images/`, selected via `isSystemInDarkTheme()`.
- **REPLACED** app launcher icon — Mobile #1 shipped JetBrains wizard-default launcher assets (adaptive `mipmap-anydpi-v26/ic_launcher.xml` + `ic_launcher_round.xml`, vector `drawable/ic_launcher_background.xml` + `drawable-v24/ic_launcher_foreground.xml`, plus 10 raster fallback PNGs in `mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/`). This change **replaces all of them in-place** with NearYouID-branded assets: Android adaptive icon with white-on-blue (`#1E4FD6`) default + blue-on-white alternate, Android 13+ themed-icon `<monochrome>` support, regenerated raster fallbacks (load-bearing under `min-sdk = 24` for Android 7.x devices). For iOS, preserves Mobile #1's existing **modern iOS 14+ single-1024 universal `AppIcon.appiconset/`** idiom (3 variants: default + `luminosity:dark` + `luminosity:tinted`) — replaces the wizard-default `app-icon-1024.png` with 3 NearYouID-branded 1024×1024 variants rasterized from the modified SVG. Does NOT regress to the legacy multi-size 17-PNG pattern (which would drop the dark/tinted variant capability).
- **NEW** foundational Bahasa Indonesia string surface — 10 strings in `commonMain/moko-resources/MR/base/strings.xml` (`app_name`, `error_generic`, `cta_continue`/`cta_cancel`/`cta_retry`/`cta_close`, `loading`, `empty_state_generic`, `home_placeholder_title`, `home_placeholder_version`) covering the placeholder `HomeScreen` plus the foundational vocabulary reserved for Mobile #3–#5.
- **MODIFIED** `NearYouTheme` (in `:mobile:app`) now consumes `NearYouColorScheme` + `NearYouTypography` + ColorScheme extension properties from `:shared:resources` instead of vanilla Material 3 defaults; extension properties wired via `CompositionLocal` so `MaterialTheme.colorScheme.locationPin` resolves at every call site.
- **MODIFIED** `HomeScreen` (in `:mobile:app`) now consumes `MR.strings.home_placeholder_title` + `MR.strings.home_placeholder_version` via Moko Resources `desc().localized()` (or `stringResource()` equivalent) instead of hardcoded literals — first real consumer of the resources module.
- **REUSE** the existing `material3 = "1.10.0-alpha05"` Compose Multiplatform pin (`org.jetbrains.compose.material3:material3`) already in [`gradle/libs.versions.toml`](../../../gradle/libs.versions.toml) — that alpha pin already exposes all `ColorScheme` roles this change needs (surfaceContainer\*, Fixed roles, full 30+ constructor). No new pin entry required. **NEW** `moko-resources` plugin + library coordinates (with Version Pinning Decisions Log entry in [`docs/09-Versions.md`](../../../docs/09-Versions.md)).
- Verify the "no hardcoded UI strings" mobile convention via **explicit grep step in tasks.md** (NOT via a Detekt rule — Mobile #1 deferred the rule as `mobile-negative-requirement-ci-grep` in `FOLLOW_UPS.md`; the rule still doesn't exist in `:lint:detekt-rules`, which currently registers 9 backend rules and none for hardcoded UI strings). This change ships a grep-based assertion that `:mobile:app` UI strings flow through Moko Resources; upgrade to a real Detekt rule (modeled on existing `RawFromPostsRule` / `BlockExclusionJoinRule` precedents) is tracked as a new follow-up entry `mobile-hardcoded-strings-detekt-rule` in `FOLLOW_UPS.md`.

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
- Android launcher icon files **replaced in-place** under `mobile/app/src/androidMain/res/mipmap-anydpi-v26/`, `drawable/`, `drawable-v24/`, `values/colors.xml`, plus 10 regenerated raster fallbacks under `mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/` (load-bearing for Android 7.x under `min-sdk = 24`)
- iOS launcher icon: 3 NearYouID-branded 1024×1024 PNG variants (default + `luminosity:dark` + `luminosity:tinted`) replace Mobile #1's wizard `app-icon-1024.png` under `iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/`; existing modern iOS 14+ single-1024 universal `Contents.json` shape preserved

**Build / dependencies:**
- `gradle/libs.versions.toml` adds `moko-resources` plugin + library coordinates; Plus Jakarta Sans variable `.ttf` vendored in repo under `:shared:resources` moko-resources fonts dir. **Does NOT add a new `material3` pin** — the existing `material3 = "1.10.0-alpha05"` Compose Multiplatform pin is reused.
- `gradle/libs.versions.toml` records the `moko-resources` pin (Version Pinning Decisions Log entry in [`docs/09-Versions.md`](../../../docs/09-Versions.md)). No new `material3` Decisions Log entry — the existing alpha05 entry is sufficient.
- `settings.gradle.kts` registers `:shared:resources` **inside the existing `if (includeMobile.toBoolean()) { ... }` block** (because the module applies `com.android.library` — placing it outside that block would break the JDK-only Cloud Run Docker builder that sets `includeMobile=false`).
- `dev/module-descriptions.txt` gets a one-line entry for `:shared:resources`; root `README.md` § What's in this repo block auto-regenerated via `dev/scripts/sync-readme.sh --write`
- New helper script `dev/scripts/generate-ios-app-icons.sh` (rsvg-convert with pdftocairo fallback) — generates the 3 NearYouID-branded 1024×1024 variants (default + dark + tinted) for the modern iOS Asset Catalog idiom

**Docs (no edits required during this change — already point to Mobile #2 as the consumer):**
- [`openspec/project.md`](../../project.md) § Module Structure (`:shared:resources` SCAFFOLD NEXT row — flipped to shipped post-archive)
- [`docs/04-Architecture.md`](../../../docs/04-Architecture.md) § Dependency Isolation Pattern (same SCAFFOLD NEXT row)
- [`docs/03-UX-Design.md`](../../../docs/03-UX-Design.md) — string copy already lives here verbatim; this change starts consuming it
- [`docs/08-Roadmap-Risk.md`](../../../docs/08-Roadmap-Risk.md) Pre-Phase 1 item 31 (`:shared:resources` in the abstraction-scaffolding list)

**CI / lint:**
- Grep-based "no hardcoded UI strings" verification (the Detekt rule for this invariant per [`openspec/project.md`](../../project.md) § Coding Conventions does NOT yet exist — `:lint:detekt-rules` registers 9 backend rules, none for hardcoded UI strings; Mobile #1 deferred the rule as `mobile-negative-requirement-ci-grep` in `FOLLOW_UPS.md`). This change verifies via an explicit grep step in `tasks.md` Section 8 + adds a new follow-up entry `mobile-hardcoded-strings-detekt-rule` tracking the eventual rule upgrade.
- Both Android + iOS build verification: `./gradlew :mobile:app:assembleDebug` produces APK; `./gradlew :mobile:app:linkPodDebugFrameworkIosSimulatorArm64` produces iOS framework (the canonical iOS link task per Mobile #1's `mobile-app-scaffold` spec § "Android and iOS targets build green")
- No runtime change → no staging deploy required → no Section 6 staging-smoke block in `tasks.md`; the "Section 6 N/A" note goes in the eventual `/opsx:archive` commit body per [`openspec/project.md`](../../project.md) § Archive timing under the one-PR convention

**Out of scope (tracked):**
- Dedicated hand-tuned dark palette (mechanically derived dark ships; follow-up `mobile-dark-palette-tuning` if visual review identifies issues)
- Material 3 1.4-alpha + expressive components (`WavyProgressIndicator`, `MaterialExpressiveTheme`, expressive button/FAB APIs)
- Sentry KMP module-isation (already split as `infra-sentry-kmp-module-isation` per Mobile #1 design.md Decision 5)
- All feature screens (Mobile #3 auth, #4 age gate, #5 nearby timeline)
- iOS `PrivacyInfo.xcprivacy` finalization (Pre-Phase 1 task 33 + Phase 3 iOS block)
