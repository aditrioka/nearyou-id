## Why

`mobile-env-launcher-icons` (#155) deliberately shipped a **safe subset** on iOS: it added a single `Staging` build configuration (resolving `AppIcon-Staging` + `id.nearyou.app.staging`) but left the `Debug`/`Release` `ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon` hardcodes in place — a global removal would have regressed production, since `Config.xcconfig` is the staging-flavored base. The consequences today:

- iOS `Release` still carries the **`.staging` bundle id + cobalt icon** (an env/icon mismatch), and there is **no dedicated `.nearyou.app` production build configuration** — you cannot build a true production iOS app from the committed project.
- Per-configuration CocoaPods wiring is tangled: `Config.xcconfig` `#include`s `Pods-iosApp.debug.xcconfig` for every config that routes through it.
- iOS lacks Android's environment build matrix. Android already ships `dev`/`staging`/`production` flavors × `debug`/`release` build types.

This change finishes the **env-config-completion** half of the `FOLLOW_UPS.md` entry `mobile-env-launcher-icons-ios-dev-icon` by establishing a proper iOS build-configuration matrix.

## What Changes

- **Establish an env × build-type iOS build-configuration matrix** (Android-parity). The exact set is a `design.md` decision (`Dev/Staging/Production × Debug/Release`, a pragmatic subset, or the full 6) — **recommended set surfaced to the user for confirmation before `/opsx:apply`**.
- **Add a dedicated production resolution** wired to `Production.xcconfig` (`id.nearyou.app` + cobalt `AppIcon` + placeholder API), each as a committed shared scheme reproducible from a fresh checkout.
- **Remove the `Debug`/`Release` `ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon` hardcodes** (now safe once a production config resolves cobalt) so each configuration's xcconfig drives the launcher icon: `staging-*` → `AppIcon-Staging`, `production-*` → `AppIcon`, `dev-*` → `AppIcon-Staging` (the iOS dev icon stays deferred per the FOLLOW_UP unless the user opts to add `AppIcon-Dev` here — a `design.md` open question).
- **Fix per-configuration CocoaPods wiring**: add a `project 'iosApp', '<Config>' => :debug|:release` mapping to `iosApp/Podfile` so `pod install` generates `Pods-iosApp.<config>.xcconfig` per configuration, and each env xcconfig `#include`s its matching (debug- or release-typed) Pods xcconfig. Requires `pod install` (via `dev/scripts/ios-pod-install.sh`).
- **Amend [`docs/04-Architecture.md`](../../../docs/04-Architecture.md):332-334** — the canonical doc currently specifies iOS env separation as env-level `Staging` + `Production` xcconfig schemes (no env×build-type matrix). This change **deliberately diverges** to the richer matrix, so the doc is updated to document it. This is the sanctioned "amend docs" path, not silent divergence.
- **No icon assets change.** `AppIcon-Staging.appiconset`, `AppIcon.appiconset` (cobalt `#1E4FD6`, byte-identical to #155), and the Android flavor `colors.xml` files are untouched — this change only reworks how iOS *configurations* resolve the existing icons.

## Capabilities

### New Capabilities

- _(none)_

### Modified Capabilities

- `mobile-auth-signin`: its **Environment-aware API base URL via expect/actual config** requirement — extend the iOS scenarios from a single committed `Staging` build configuration (added by #155) to the full env × build-type matrix: each environment's build configuration(s) + committed shared scheme(s) resolve the env's bundle id + API base URL + launcher icon, reproducible from a fresh checkout, with the per-config CocoaPods base wired so a real build links.
- `shared-resources`: its **Launcher icon background is environment-differentiated** requirement — the iOS icon-resolution scenario is updated because removing the `Debug`/`Release` APPICON hardcode makes its "Release resolves `AppIcon` via the retained hardcode" clause stale; cobalt now resolves via `Production.xcconfig` with NO `project.pbxproj` hardcode. (Icon assets are byte-identical; only the resolution mechanism changes.)

## Impact

- **iOS project:** `iosApp/iosApp.xcodeproj/project.pbxproj` (new build configurations + config lists; relocate/remove `ASSETCATALOG_COMPILER_APPICON_NAME` hardcodes), `iosApp/iosApp.xcodeproj/xcshareddata/xcschemes/*` (new committed schemes), `iosApp/Configuration/*.xcconfig` (per-config env + Pods-include layering), `iosApp/Podfile` (per-config CocoaPods mapping), regenerated `Pods/` (via `pod install`).
- **Docs:** [`docs/04-Architecture.md`](../../../docs/04-Architecture.md) § Deployment (iOS build-configuration matrix).
- **Specs:** `openspec/specs/mobile-auth-signin/spec.md` (env-aware-config requirement) + `openspec/specs/shared-resources/spec.md` (iOS launcher-icon-resolution scenario).
- **`mobile/app/build.gradle.kts`** — the cocoapods block gains an `xcodeConfigurationToNativeBuildType[...]` mapping (apply-discovered: the KMP cocoapods plugin cannot auto-detect the Kotlin framework build type from the custom Xcode config names and fails the `ComposeApp` framework sync without it). **No `gradle/libs.versions.toml` change** (no new library pin → no pre-implementation library re-check needed), no new dependencies, no backend/schema/API/security surface. Production end-user experience unchanged (the production icon + assets are byte-identical).
- **Verification asymmetry:** `xcodebuild -showBuildSettings` per config (bundle id + API + APPICON) is headless/CI-friendly; the **real-build verification** (`pod install` + `xcodebuild build` to prove per-config Pods linking — the part #155 could not verify) requires a Mac with the iOS toolchain and is flagged in `tasks.md`.
