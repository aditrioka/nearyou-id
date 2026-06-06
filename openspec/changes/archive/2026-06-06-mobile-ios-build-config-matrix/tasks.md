## 1. Confirm the config matrix (gate — before any heavy pbxproj/Pods work)

- [x] 1.1 Surface `design.md` Decision 1 to the user and get the final matrix. **CONFIRMED option (a):** `Dev Debug` / `Staging Debug` / `Prod Debug` / `Prod Release`; iOS dev = `.dev` bundle id + a real `AppIcon-Dev` `#15803D`. Recorded in `design.md` Decision 1.
- [x] 1.2 Generate `AppIcon-Dev.appiconset` (forest green `#15803D`, corner pixel verified rgb 21,128,61) from `dev/assets/icon-src/icon-dev.svg` via `dev/scripts/generate-ios-app-icons.sh` (single universal 1024 entry).

## 2. pbxproj + xcconfigs — add the matrix configs (scripted, headlessly verifiable)

- [x] 2.1 Scripted Python patch (regex with **captured indentation**, per #155) restructured `{Debug,Staging,Release}` → the 4 matrix configs (project + target, cloned from the debug/release templates). **Apply note:** config names contain spaces → old-style plist requires them QUOTED (`name = "Dev Debug";`, `relativePath = "Dev Debug.xcconfig";`). Unique 24-hex UUIDs; wired into both `XCConfigurationList`s; `defaultConfigurationName = "Prod Release"`.
- [x] 2.2 Removed the `Debug`/`Release` `ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon` hardcodes so the icon resolves per-config from xcconfig. (Pods pairing untouched — `Release`→release / `Debug`→debug is CORRECT, the "swap" is a recurring false positive.)
- [x] 2.3 Per-config resolution: `Dev.xcconfig` → `AppIcon-Dev` + `.dev` + `localhost:8080` (+ Info.plist `NSAllowsLocalNetworking` ATS exception); `Staging.xcconfig` → `AppIcon-Staging` + `.staging`; `Production.xcconfig` → `AppIcon` + `.nearyou.app` + placeholder. Per-config xcconfigs (`<Config>.xcconfig`) `#include` env + Pods.
- [x] 2.4 Committed 3 shared schemes (`iosApp (Dev/Staging/Production).xcscheme`) under `xcshareddata/xcschemes/` (Production: Run/Test=`Prod Debug`, Archive/Profile=`Prod Release`).
- [x] 2.5 Verified headlessly: `plutil -lint` OK; `xcodebuild -list` = 4 configs + 3 schemes; `xcodebuild -showBuildSettings` per config resolves the exact bundle id + API + APPICON (incl. `id.nearyou.app.dev`); zero `ASSETCATALOG_COMPILER_APPICON_NAME` in pbxproj.
- [x] 2.6 Extended `LauncherIconBackgroundTest` (F2): pbxproj-no-APPICON-hardcode assertion + `Dev.xcconfig`→`AppIcon-Dev` + `AppIcon-Dev` appiconset (Linux-CI guards). **Impl-review addendum (2026-06-06):** + `iosPerConfigXcconfigs_layerEnvXcconfigAndMatchingPodsBase` (each of the 4 leaf `<Config>.xcconfig`s `#include`s its env xcconfig + matching `Pods-iosApp.<config>.xcconfig`) + `buildGradle_mapsAllFourMatrixConfigsToNativeBuildType` (all 4 `xcodeConfigurationToNativeBuildType` mappings) — Linux-CI guards so a rename can't silently break the per-config Pods link / KMP framework sync (test-coverage lens). Mobile gate green.

## 3. CocoaPods per-config wiring + pod install (heavy)

- [x] 3.1 Added the per-config mapping to `iosApp/Podfile`: `project 'iosApp.xcodeproj', 'Dev Debug' => :debug, 'Staging Debug' => :debug, 'Prod Debug' => :debug, 'Prod Release' => :release`.
- [x] 3.2 Ran `pod install` via `dev/scripts/ios-pod-install.sh` — generated `Pods-iosApp.{dev debug,staging debug,prod debug,prod release}.xcconfig` (filenames match the per-config `#include`s exactly) + regenerated `iosApp.xcworkspace`.
- [x] 3.3 Rewired the Pods include to the leaf (F1): removed the hardcoded debug-Pods `#include` from `Config.xcconfig`; each per-config xcconfig `#include`s its OWN matching `Pods-iosApp.<config>.xcconfig`.
- [x] 3.4 `Pods/`, `Podfile.lock`, `*.xcworkspace/` are gitignored — confirmed none staged; only `project.pbxproj` + xcconfig + `Podfile` committed.
- [x] 3.5 **Apply discovery — base-config fix.** `pod install` couldn't read the anchor/relativePath base and set each target config's base directly to the Pods xcconfig (dropping env values → everything resolved staging). Fixed via the `xcodeproj` gem: set each target config's `baseConfigurationReference` to a **real `PBXFileReference`** for its per-config xcconfig (which `#include`s the Pods xcconfig). CocoaPods now detects + respects the custom base.
- [x] 3.6 **Apply discovery — KMP config-type mapping.** Added `xcodeConfigurationToNativeBuildType["<config>"] = NativeBuildType.DEBUG|RELEASE` (all 4) to `mobile/app/build.gradle.kts`'s cocoapods block — without it the `ComposeApp` framework sync fails with "Could not identify build type ... CONFIGURATION=Prod Release". (`build.gradle.kts` change; no `libs.versions.toml` pin.)

## 4. Real-build verification (the part #155 could NOT do)

- [x] 4.1 `xcodebuild build` the **workspace** for a debug-typed (`Staging Debug`) AND a release-typed (`Prod Release`) config (simulator destination, `CODE_SIGNING_ALLOWED=NO`) — **both BUILD SUCCEEDED**, proving the per-config Pods base links (KMP `ComposeApp` framework + GoogleSignIn et al.). **Impl-review addendum (2026-06-06):** also real-built `Dev Debug` (**BUILD SUCCEEDED**) — closes the test-coverage lens's novel-surface gap (the iOS-dev `localhost` API + `NSAllowsLocalNetworking` ATS exception + `AppIcon-Dev` are unique to `Dev Debug`, exercised by neither `Staging Debug` nor `Prod Release`).
- [ ] 4.2 (Optional) Launch the dev/staging/production configs on the iOS simulator and eyeball the 3 distinct icons (green/orange/cobalt). Build-setting + asset resolution already verified; this is a final visual confirmation.

## 5. Docs + specs + validate

- [x] 5.1 Amended [`docs/04-Architecture.md`](../../../docs/04-Architecture.md):333 — the env × build-type build-configuration matrix (4 configs, per-config bundle id + API + icon + Pods). Sanctioned divergence (user-authorized).
- [x] 5.2 `mobile-auth-signin` + `shared-resources` spec MODIFYs are in this change's deltas; they sync to `openspec/specs/` at `openspec archive`.
- [x] 5.3 `openspec validate mobile-ios-build-config-matrix --strict` is green.

## 6. FOLLOW_UPS + archive readiness (deploy smoke N/A)

- [x] 6.1 Deleted the `mobile-env-launcher-icons-ios-dev-icon` FOLLOW_UPS entry — this change ships BOTH halves (env-config completion + the iOS dev icon), so it is fully resolved (29 open).
- [x] 6.2 External-data sanity-check: N/A — no external open-data source.
- [x] 6.3 Staging deploy smoke: N/A — build-config is a client concern, no server/runtime impact.
- [x] 6.4 Production assets byte-unchanged — `AppIcon.appiconset`, `AppIcon-Staging.appiconset`, and Android `colors.xml` untouched (verified via `git diff --stat origin/main`).
