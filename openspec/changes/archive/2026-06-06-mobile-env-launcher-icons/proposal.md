## Why

The Android `dev` / `staging` / `production` flavors already install side-by-side on one device (distinct `applicationIdSuffix` — `.dev` / `.staging` / none), but their launcher icons are visually identical, so you cannot tell which build you just tapped. A per-environment icon tint is standard mobile practice and removes that ambiguity for the solo operator dogfooding multiple builds. The launcher icon is a `shared-resources`-governed surface (its background color is already a spec requirement), so the differentiation ships as an OpenSpec change rather than an untracked build-config tweak.

## What Changes

- **Launcher-icon background becomes environment-conditional.** Only the icon **background** changes per environment; the white hexagon-cluster **foreground glyph is identical everywhere** (brand stays recognizable, environment becomes obvious):
  - `production` → `#1E4FD6` (UNCHANGED — brand cobalt; the current `ic_launcher_background`)
  - `staging` → `#C2410C` (burnt orange — "pre-prod, caution")
  - `dev` → `#15803D` (forest green — "local sandbox")

  All three pass ≈5:1+ WCAG contrast against the white foreground glyph (verified).

- **Android** (flavors already wired): add `mobile/app/src/dev/res/values/colors.xml` and `mobile/app/src/staging/res/values/colors.xml`, each overriding `ic_launcher_background`. `production` adds no override and inherits `src/androidMain`'s `#1E4FD6`. AGP merges flavor `res/` over `androidMain`; the adaptive-icon XML, foreground glyph, monochrome layer, and raster PNG fallbacks stay shared. **No `build.gradle.kts` changes.**

- **Android accepted limitation:** the raster `mipmap-*/ic_launcher.png` fallbacks (only consulted by API 24–25 devices that ignore adaptive XML) bake in the cobalt background and are NOT regenerated per flavor — pre-API-26 devices render cobalt for every flavor. API 26+ (adaptive-capable, the overwhelming majority) get the per-environment tint. Documented as an explicit accepted limitation, not silently dropped.

- **iOS — completes the presupposed env-separation layer (full parity, this PR).** iOS environment separation is currently **unshipped**: only `Debug`/`Release` build configurations exist, no schemes are committed, and `Staging.xcconfig` / `Production.xcconfig` are orphaned (not referenced as a `baseConfigurationReference`). This change wires that layer — a `Staging` build configuration referencing `Staging.xcconfig` plus a committed shared scheme — then adds a staging-tinted `AppIcon-Staging` asset set and selects it via `ASSETCATALOG_COMPILER_APPICON_NAME` (relocating the value out of the hardcoded `pbxproj` slot, which currently overrides xcconfig). Source SVGs for icon rasterization were removed in Mobile #2.5, so a parameterized build-input SVG is recreated (build tooling, not a shipped `Res` asset) and rasterized via the existing `dev/scripts/generate-ios-app-icons.sh`.

- **iOS dev scope** is a design sub-decision (default: ship `production` + `staging` icons; `dev` maps to the local Debug-on-simulator build, no separate iOS dev icon unless a `Dev` configuration proves cheap). Android remains the canonical 3-environment surface.

- **Not touched:** the dormant `MainActivityAlt` blue-on-white alternate icon (reserved for a future user-selectable icon-theme feature). No in-app/runtime UI, no backend, no schema, no API, no security surface, no new library pins.

## Capabilities

### New Capabilities

- _(none)_

### Modified Capabilities

- `shared-resources`: the **App launcher icon** requirement changes from a single brand-primary background (`#1E4FD6` everywhere) to an **environment-conditional** background — `production` keeps `#1E4FD6`; `dev` / `staging` override to env tints via flavor `res/` (Android) and per-scheme `AppIcon` (iOS). The foreground glyph and the dormant alternate icon are unchanged.
- `mobile-auth-signin`: its iOS env-config scenario already **presupposes** "an xcconfig variable per scheme," but the `Staging` build configuration + shared scheme were never committed. This change makes that presupposition real, so the iOS env-separation scenario is strengthened to assert the committed `Staging` build configuration + shared scheme (closing the aspirational gap rather than leaving the spec stale).

## Impact

- **Android assets:** `mobile/app/src/dev/res/values/colors.xml` (new), `mobile/app/src/staging/res/values/colors.xml` (new). No Gradle change.
- **iOS project:** `iosApp/iosApp.xcodeproj/project.pbxproj` (add `Staging` build configuration; relocate `ASSETCATALOG_COMPILER_APPICON_NAME`), `iosApp/iosApp.xcodeproj/xcshareddata/xcschemes/*` (new committed scheme(s)), `iosApp/Configuration/{Config,Staging,Production}.xcconfig` (add `ASSETCATALOG_COMPILER_APPICON_NAME`), `iosApp/iosApp/Assets.xcassets/AppIcon-Staging.appiconset/*` (new), a recreated build-input source SVG under a tooling path.
- **Specs:** `openspec/specs/shared-resources/spec.md` (launcher-icon requirement), `openspec/specs/mobile-auth-signin/spec.md` (iOS env-separation scenario).
- **Verification asymmetry:** Android resource merge is CI-verifiable; the iOS build-configuration/scheme wiring requires manual Xcode edits + a simulator visual check (per the iOS-sim verification recipe) and is **not headlessly verifiable** — flagged explicitly in `tasks.md`.
- **No** new dependencies, no `gradle/libs.versions.toml` change, no runtime behavior change for end users (production icon unchanged).
