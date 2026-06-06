## Context

The Android `dev` / `staging` / `production` flavors already install side-by-side (distinct `applicationIdSuffix`, [`mobile/app/build.gradle.kts:146`](../../../mobile/app/build.gradle.kts)), but every build shows the same cobalt launcher icon, so the operator can't tell which build a home-screen tap will open. The launcher icon is a `shared-resources`-governed surface — its background is the spec'd brand primary `#1E4FD6` ([`openspec/specs/shared-resources/spec.md`](../../../openspec/specs/shared-resources/spec.md) § App launcher icon). This change makes that background environment-conditional.

Current asset state (verified at proposal time):
- **Android:** single adaptive icon; `ic_launcher_background = #1E4FD6` ([`values/colors.xml`](../../../mobile/app/src/androidMain/res/values/colors.xml)); white hexagon glyph foreground; raster PNG fallbacks; dormant `MainActivityAlt` alternate icon. A `src/dev/` flavor source set already exists (manifest + `DevTestLoginActivity`), but no flavor `res/`.
- **iOS:** single `AppIcon.appiconset` (1024 light/dark/tinted). **Env-separation is unshipped** — only `Debug`/`Release` build configurations exist; no committed schemes; `Staging.xcconfig` / `Production.xcconfig` are orphaned (not a `baseConfigurationReference`); `ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon` is hardcoded in `project.pbxproj` (overriding xcconfig). The icon-generator source SVGs were removed in Mobile #2.5; `dev/scripts/generate-ios-app-icons.sh` survives and takes SVG paths as args.

## Goals / Non-Goals

**Goals:**
- Launcher icon background is per-environment: `production #1E4FD6` (unchanged), `staging #C2410C`, `dev #15803D`; foreground glyph identical everywhere.
- Android delivered with zero `build.gradle.kts` change (flavor `res/` overrides only).
- iOS delivered at full parity — including committing the env-separation layer (`Staging` build configuration + shared scheme) the `mobile-auth-signin` spec already presupposes, which also gives iOS staging a `.staging` bundle id for side-by-side install.
- Production end-user experience unchanged (production icon byte-identical).

**Non-Goals:**
- No in-app/runtime UI change, no user-facing app behavior, no backend/schema/API/security surface, no new library pin.
- Not touching the dormant `MainActivityAlt` alternate icon (reserved for a future user-selectable icon-theme feature).
- Not regenerating per-flavor raster PNG fallbacks (see Decision 3).
- Not introducing an in-app environment banner/ribbon (icon-only differentiation).

## Decisions

### Decision 1 — Background-only tint; foreground glyph unchanged
Only `ic_launcher_background` (Android) / the AppIcon background (iOS) changes per environment. The white hexagon-cluster glyph is identical across all envs. **Why:** keeps brand recognizable while making environment unmistakable; minimizes asset churn; the foreground vector already lives once in `androidMain` and is reused by every flavor.
Color contrast against the white glyph (computed): `#1E4FD6` ≈ 6.3:1, `#C2410C` ≈ 5.2:1, `#15803D` ≈ 5.0:1 — all clear WCAG AA (4.5:1). **Alternatives considered:** distinct glyph per env (rejected — dilutes brand, more assets); a small env-letter badge (rejected — illegible at launcher size).

### Decision 2 — Android via flavor resource overrides (no Gradle change)
Add `mobile/app/src/dev/res/values/colors.xml` (`#15803D`) and `mobile/app/src/staging/res/values/colors.xml` (`#C2410C`). AGP merges flavor `res/` over `androidMain` `res/` (flavor priority > main), so a same-named `<color name="ic_launcher_background">` in the flavor source set replaces the base value; `production` adds no override and inherits `#1E4FD6`. **Why:** smallest possible surface — the adaptive XML, foreground, monochrome, and raster fallbacks stay shared in `androidMain`; consistent with the already-present `src/dev/` source set; nothing in `build.gradle.kts` changes. **Alternatives considered:** `manifestPlaceholders` + per-flavor `buildConfigField` color (rejected — colors.xml override is the idiomatic AGP mechanism); generating a full per-flavor icon set (rejected — needless duplication).

### Decision 3 — Pre-API-26 raster fallback stays cobalt for all flavors (accepted limitation)
The raster `mipmap-*/ic_launcher.png` fallbacks (consulted only by API 24–25 devices that ignore adaptive XML; `minSdk` permits them and the existing spec marks them load-bearing) are NOT regenerated per flavor. Pre-API-26 devices therefore render the shared cobalt icon for every flavor; API 26+ (adaptive-capable, the overwhelming majority of the install base) get the per-env tint. **Why:** regenerating 10 PNGs × 2 flavors for a vanishing legacy slice that only the operator would ever see is not worth the maintenance; the dev/staging builds are operator-facing, and operators run modern devices/emulators. Captured as an explicit spec scenario so it's a documented decision, not silent scope-cutting. **Revisit if** a staging device in the fleet is actually API ≤ 25.

### Decision 4 — iOS: complete the presupposed env-separation layer, then select icon per build configuration
iOS per-env icons require an env-aligned build configuration (xcconfig is the lowest-precedence layer and is currently overridden by the pbxproj hardcode). So this change, in order:
1. Adds a **`Staging` build configuration** whose `baseConfigurationReference` is `Staging.xcconfig`, and commits a **shared scheme** under `xcshareddata/xcschemes/` that builds it (closing the gap the `mobile-auth-signin` spec presupposes; also yields the `.staging` bundle id for side-by-side iOS install).
2. **Relocates** `ASSETCATALOG_COMPILER_APPICON_NAME` out of the hardcoded `project.pbxproj` target-level slot (`project.pbxproj:307` / `:336`) so the per-configuration xcconfig value wins: production/`Release` → `AppIcon`, `Staging` → `AppIcon-Staging`.
3. Adds a `AppIcon-Staging.appiconset` (staging-tinted 1024 PNG).

**CocoaPods base-config caveat (verified at proposal time).** The app **target's** `Debug`/`Release` configurations base off the CocoaPods-generated `Pods-iosApp.{debug,release}.xcconfig` (`project.pbxproj:304` / `:333`) — *not* `Config.xcconfig`, which is only the **project-level** base (`project.pbxproj:362–363`). (The `Debug`→debug / `Release`→release pairing is correct — there is no swap.) So the new `Staging` target configuration's xcconfig (`Staging.xcconfig`) must itself `#include` the relevant `Pods-iosApp.*.xcconfig` (or the implementer sets `ASSETCATALOG_COMPILER_APPICON_NAME` per-configuration directly in the target `buildSettings`) for the value to actually resolve at the target level. Either mechanism is acceptable; the `xcodebuild -showBuildSettings | grep APPICON` check (tasks.md §3.5) is the mechanism-agnostic gate that proves the resolved value per configuration.

**Why:** there is no lighter hook — without an env-aligned configuration/scheme, iOS cannot select a different icon for staging. The user explicitly chose full iOS parity in this PR (the alternative, deferring iOS behind a separate `ios-build-configuration-env-separation` change, was offered and declined). **Alternatives considered:** per-`Debug`/`Release` icon (rejected — Debug/Release aren't env-aligned); runtime icon swap via `setAlternateIconName` (rejected — that's a launched-app API, doesn't differentiate the installed icon at rest and needs `CFBundleAlternateIcons` plumbing).

### Decision 5 — Recreate a parameterized build-input SVG for iOS rasterization
The icon-generator's source SVGs were deleted in Mobile #2.5 (shipped logos are now Android XML vector drawables). To rasterize a staging-tinted 1024 PNG, recreate a minimal source SVG (`viewBox="0 0 108 108"`, white hexagon glyph paths from the existing `ic_launcher_foreground.xml` over a single background `<rect>` set to the env color) and feed it to `dev/scripts/generate-ios-app-icons.sh`. These SVGs are **build-tooling inputs**, not shipped `Res` resources — store them under the tooling path `dev/assets/icon-src/` (concrete, not illustrative), not under `shared/resources/.../composeResources/` (which would reintroduce the SVG-on-Android crash Mobile #2.5 removed).

`AppIcon-Staging.appiconset` ships a **single** universal `1024×1024` entry — it does NOT mirror the production `AppIcon`'s 3-variant (`default` + `luminosity:dark` + `luminosity:tinted`) shape. The 3-variant shape exists to give the production icon system dark/tinted treatments; a staging build is operator-facing and needs only the one tinted icon, so a single-entry `Contents.json` avoids two missing-PNG asset-catalog warnings. The production `AppIcon.appiconset` keeps its 3-variant shape unchanged. **Why:** the generator already exists and enforces the `viewBox` contract; recreating a 108-unit source SVG is mechanical. **Alternatives considered:** pixel-editing the existing cobalt PNG's background (rejected — brittle, anti-aliased edges); generating the PNG from the Android XML vector drawable (rejected — `rsvg-convert` doesn't read Android vector XML).

### Decision 6 — iOS "dev" has no separate icon (default)
iOS ships `production` (cobalt `AppIcon`) + `staging` (`AppIcon-Staging`). iOS "dev" = the local Debug-on-simulator build; it does **not** get a dedicated configuration/scheme/icon in this change. **Why:** iOS local development runs from Xcode against a chosen config; adding a third committed iOS configuration + scheme + icon set triples the manual Xcode surface for marginal value (the simulator is unambiguous about which build is running). Android remains the canonical 3-environment surface. **Revisit if** TestFlight distributes a distinct iOS dev build that needs visual separation — tracked as a `FOLLOW_UPS.md` candidate, not built here.

### Decision 7 — Spec home: `shared-resources` (primary) + minimal `mobile-auth-signin` (review-gated)
The icon behavior lives in `shared-resources` (MODIFIED launcher-icon requirement to mark the base as production/default + ADDED *environment-differentiated* requirement). The committed iOS `Staging` build configuration + shared scheme are recorded as a minimal MODIFIED scenario on `mobile-auth-signin`'s env-aware-config requirement (it already presupposes "per scheme"; this change makes it reproducible). **This `mobile-auth-signin` delta is explicitly flagged for Phase D sub-agent review** as possible over-reach — if review judges it implementation-completion rather than a requirement change, drop it to a `FOLLOW_UPS.md` note and keep the committed-scheme fact recorded under the `shared-resources` ADDED scenario alone. **Why surface it:** silently wiring a layer an existing spec presupposes, without recording it, is the spec/code drift this repo guards against.

## Risks / Trade-offs

- **iOS pbxproj/scheme surgery is manual and not headlessly verifiable** → Mitigation: keep the Android half fully CI-verifiable and independent (it lands and verifies on its own); flag every iOS Xcode step in `tasks.md` as manual; verify iOS via the simulator visual check (iOS-sim verification recipe). The Android value of the change is realizable even if iOS needs a manual pass.
- **`ASSETCATALOG_COMPILER_APPICON_NAME` precedence mistake** (xcconfig silently ignored because pbxproj still hardcodes it) → Mitigation: the spec scenario asserts the value is no longer hardcoded in pbxproj; the implementer verifies the resolved build setting (`xcodebuild -showBuildSettings | grep APPICON`) per configuration.
- **`mobile-auth-signin` MODIFY perceived as scope creep / coupling** → Mitigation: kept to one added scenario + one clause; explicitly review-gated (Decision 7); trivially droppable.
- **Archive-time spec conflict** if a parallel in-flight change also edits `shared-resources` or `mobile-auth-signin` specs → Mitigation: in-flight survey shows the only mobile change in flight (`mobile-home-tab-host`) is nav/screen-scoped, not spec-overlapping; if that changes, sequence the squash-merges per `openspec/project.md` § "Archive commits touching shared specs."
- **AGP flavor-res merge assumption wrong** (override doesn't apply) → Mitigation: low risk (standard AGP priority: flavor > main); verified by the resource-merge scenario before archive.

## Migration Plan

No runtime migration, no rollback concern — production assets are unchanged (cobalt icon byte-identical). Rollback = revert the flavor `res/` files + the iOS project/asset additions; nothing is persisted, deployed, or user-visible in production. Staging deploy is unaffected (icon is a client build artifact, not a server config).

## Open Questions

- **iOS dev icon** (Decision 6 default = none) — confirm at review whether a dedicated iOS dev configuration is wanted now or deferred to a `FOLLOW_UPS.md` entry.
- **`mobile-auth-signin` delta** (Decision 7) — confirm at Phase D review whether to keep the MODIFIED scenario or downgrade to implementation-completion + FOLLOW_UP.
