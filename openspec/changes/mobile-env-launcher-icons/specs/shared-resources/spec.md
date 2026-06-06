## MODIFIED Requirements

### Requirement: App launcher icon replaces wizard-default assets with NearYouID-branded variants

The `:mobile:app` module SHALL ship NearYouID-branded launcher icons by **replacing in-place** Mobile #1's existing JetBrains-wizard-default launcher assets (no file additions to net-new mipmap locations). Both platforms maintain their existing structural conventions — Android uses adaptive icon + raster fallbacks; iOS uses the modern single-1024 universal Asset Catalog idiom (NOT the legacy multi-size pattern). The `androidMain` / default-scheme assets described here are the **production / base** brand identity; non-production builds override the icon **background** per the *Launcher icon background is environment-differentiated* requirement below, while reusing every other asset (foreground glyph, monochrome layer, raster fallbacks) unchanged.

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

## ADDED Requirements

### Requirement: Launcher icon background is environment-differentiated

The mobile app launcher icon background SHALL be **environment-differentiated** so `dev` / `staging` / `production` builds are distinguishable at a glance on a device, while the **foreground glyph is identical across all environments** (the brand stays recognizable; only the environment cue changes). The background colors SHALL be: `production` → `#1E4FD6` (brand cobalt, inherited base — no override), `staging` → `#C2410C` (burnt orange), `dev` → `#15803D` (forest green). Each non-production background SHALL preserve at least 4.5:1 WCAG contrast against the white foreground glyph.

On **Android** the differentiation SHALL be delivered via gradle product-flavor resource overrides (`mobile/app/src/<flavor>/res/values/colors.xml` overriding `ic_launcher_background`), with `production` adding no override so it inherits the `androidMain` value. On **iOS** the differentiation SHALL be delivered via a per-build-configuration Asset-Catalog selection (`ASSETCATALOG_COMPILER_APPICON_NAME` resolved from the env xcconfig), with a dedicated `AppIcon-Staging.appiconset`; the default/production build retains the existing `AppIcon`. The dormant `MainActivityAlt` alternate icon SHALL NOT be touched by this differentiation.

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

#### Scenario: iOS staging build selects a staging-tinted AppIcon

- **WHEN** building the iOS app under the `Staging` build configuration / scheme
- **THEN** `ASSETCATALOG_COMPILER_APPICON_NAME` resolves to `AppIcon-Staging` (provided by `iosApp/Configuration/Staging.xcconfig` and no longer hardcoded to `AppIcon` in `project.pbxproj`) AND `iosApp/iosApp/Assets.xcassets/AppIcon-Staging.appiconset/` exists containing a single universal 1024×1024 PNG whose background is the staging tint `#C2410C`; the **`Release` (production) build configuration** resolves `AppIcon` (the existing cobalt icon, unchanged) — note the base default is staging-flavored, so this is asserted of the production *configuration*, not of an unqualified "default" build

#### Scenario: Pre-API-26 Android raster fallback is shared, not per-flavor (accepted limitation)

- **WHEN** a non-production flavor is installed on an Android API 24–25 device that ignores the adaptive `mipmap-anydpi-v26` XML and falls back to a raster `mipmap-*/ic_launcher.png`
- **THEN** the raster fallback renders the shared `androidMain` cobalt icon for every flavor (the per-environment tint applies only to adaptive-icon-capable API 26+ devices); this change intentionally does NOT regenerate per-flavor raster PNGs, and the limitation is documented in `design.md`

#### Scenario: The dormant alternate icon is not modified by environment differentiation

- **WHEN** inspecting `mobile/app/src/androidMain/res/mipmap-anydpi-v26/ic_launcher_alt.xml` and the `MainActivityAlt` `<activity-alias>` in `mobile/app/src/androidMain/AndroidManifest.xml`
- **THEN** both are unchanged by this change — the environment differentiation does not touch the reserved user-selectable alternate icon
