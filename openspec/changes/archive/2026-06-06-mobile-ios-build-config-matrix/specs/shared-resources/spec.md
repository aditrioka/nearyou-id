## MODIFIED Requirements

### Requirement: Launcher icon background is environment-differentiated

The mobile app launcher icon background SHALL be **environment-differentiated** so `dev` / `staging` / `production` builds are distinguishable at a glance on a device, while the **foreground glyph is identical across all environments** (the brand stays recognizable; only the environment cue changes). The background colors SHALL be: `production` → `#1E4FD6` (brand cobalt, inherited base — no override), `staging` → `#C2410C` (burnt orange), `dev` → `#15803D` (forest green). Each non-production background SHALL preserve at least 4.5:1 WCAG contrast against the white foreground glyph.

On **Android** the differentiation SHALL be delivered via gradle product-flavor resource overrides (`mobile/app/src/<flavor>/res/values/colors.xml` overriding `ic_launcher_background`), with `production` adding no override so it inherits the `androidMain` value. On **iOS** the differentiation SHALL be delivered via a per-build-configuration Asset-Catalog selection (`ASSETCATALOG_COMPILER_APPICON_NAME` resolved from each configuration's env xcconfig — with NO `ASSETCATALOG_COMPILER_APPICON_NAME` hardcode in `project.pbxproj`), with dedicated `AppIcon-Staging.appiconset` (`#C2410C`) and `AppIcon-Dev.appiconset` (`#15803D`) sets; the **production build configuration resolves the existing `AppIcon` (cobalt) via `Production.xcconfig`** (per the `mobile-ios-build-config-matrix` env × build-type matrix). The dormant `MainActivityAlt` alternate icon SHALL NOT be touched by this differentiation.

#### Scenario: Android dev flavor overrides launcher background to forest green

- **WHEN** inspecting `mobile/app/src/dev/res/values/colors.xml`
- **THEN** the file contains `<color name="ic_launcher_background">#15803D</color>`

#### Scenario: Android staging flavor overrides launcher background to burnt orange

- **WHEN** inspecting `mobile/app/src/staging/res/values/colors.xml`
- **THEN** the file contains `<color name="ic_launcher_background">#C2410C</color>`

#### Scenario: Android production inherits the brand-primary background with no flavor override

- **WHEN** inspecting the `mobile/app/src/` tree for a `production` flavor `res/values/colors.xml`
- **THEN** there is NO `production`-flavor override of `ic_launcher_background`, so the `production` flavor resolves the `androidMain` value `#1E4FD6`

#### Scenario: Resolved Android launcher background differs per flavor at resource-merge time

- **WHEN** the merged Android resources are produced for each flavor (e.g., via the `process{Dev,Staging,Production}DebugResources` tasks or an equivalent merged-`colors.xml` inspection of the variant's intermediates)
- **THEN** the resolved `ic_launcher_background` equals `#15803D` for `dev`, `#C2410C` for `staging`, AND `#1E4FD6` for `production`

#### Scenario: Foreground glyph is identical across environments (only the background changes)

- **WHEN** comparing the foreground drawable referenced by each flavor's adaptive icon (`mipmap-anydpi-v26/ic_launcher.xml`)
- **THEN** every flavor references the same `@drawable/ic_launcher_foreground` (white hexagon glyph) — no flavor source set overrides the foreground, the monochrome layer, or the adaptive-icon XML; only `ic_launcher_background` differs between flavors

#### Scenario: iOS dev/staging builds select env-tinted AppIcons; production resolves cobalt via xcconfig

- **WHEN** building the iOS app under a `dev`-environment vs a `staging`-environment vs a `production`-environment build configuration (per the `mobile-ios-build-config-matrix` matrix)
- **THEN** a staging configuration's `ASSETCATALOG_COMPILER_APPICON_NAME` resolves to `AppIcon-Staging` (from `iosApp/Configuration/Staging.xcconfig`) AND `iosApp/iosApp/Assets.xcassets/AppIcon-Staging.appiconset/` exists containing a single universal 1024×1024 PNG whose background is the staging tint `#C2410C`; a `dev` configuration's resolves to `AppIcon-Dev` (from `iosApp/Configuration/Dev.xcconfig`) AND `iosApp/iosApp/Assets.xcassets/AppIcon-Dev.appiconset/` exists containing a single universal 1024×1024 PNG whose background is the dev tint `#15803D`; a production configuration resolves `AppIcon` (cobalt) from `iosApp/Configuration/Production.xcconfig` — and there is **NO `ASSETCATALOG_COMPILER_APPICON_NAME` hardcode in `project.pbxproj`** (the icon resolves only from each configuration's xcconfig); all verified via `xcodebuild -showBuildSettings` for the respective configuration

#### Scenario: Pre-API-26 Android raster fallback is shared, not per-flavor (accepted limitation)

- **WHEN** a non-production flavor is installed on an Android API 24–25 device that ignores the adaptive `mipmap-anydpi-v26` XML and falls back to a raster `mipmap-*/ic_launcher.png`
- **THEN** the raster fallback renders the shared `androidMain` cobalt icon for every flavor (the per-environment tint applies only to adaptive-icon-capable API 26+ devices); this change intentionally does NOT regenerate per-flavor raster PNGs, and the limitation is documented in `design.md`

#### Scenario: The dormant alternate icon is not modified by environment differentiation

- **WHEN** inspecting `mobile/app/src/androidMain/res/mipmap-anydpi-v26/ic_launcher_alt.xml` and the `MainActivityAlt` `<activity-alias>` in `mobile/app/src/androidMain/AndroidManifest.xml`
- **THEN** both are unchanged by this change — the environment differentiation does not touch the reserved user-selectable alternate icon
