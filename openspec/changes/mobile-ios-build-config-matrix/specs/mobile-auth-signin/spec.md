## MODIFIED Requirements

### Requirement: Environment-aware API base URL via expect/actual config

The mobile app SHALL declare `expect val apiBaseUrl: String` in commonMain (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/config/ApiBaseUrl.kt`). The Android actual SHALL read from a generated `BuildConfig.API_BASE_URL` field injected per gradle product flavor (`dev` / `staging` / `production`). The iOS actual SHALL read from `NSBundle.mainBundle.objectForInfoDictionaryKey("ApiBaseUrl")` driven by an xcconfig variable per scheme.

The `dev` flavor's URL field SHALL be `"http://10.0.2.2:8080"` (Android emulator host loopback) OR an equivalent local-development URL. The `staging` flavor's URL field SHALL be `"https://api-staging.nearyou.id"` (per [`openspec/project.md`](../../project.md) § Environments). The `production` flavor's URL field SHALL be a deliberately-broken placeholder value (e.g., `"https://api.nearyou.id.PLACEHOLDER"`) so a misconfigured production build fails fast; a future change replaces the placeholder when production infra is provisioned.

For the iOS per-environment resolution to be reproducible from the repository (and not depend on uncommitted local `xcuserdata`), the Xcode project SHALL commit an **environment × build-type build-configuration matrix** (the exact set per the `mobile-ios-build-config-matrix` `design.md` Decision 1 — e.g. `Dev Debug` / `Staging Debug` / `Staging Release` / `Prod Release`). Each configuration SHALL base off (or `#include`) its environment xcconfig (`Config.xcconfig` / `Staging.xcconfig` / `Production.xcconfig`) so its `PRODUCT_BUNDLE_IDENTIFIER`, `APP_API_BASE_URL`, and `ASSETCATALOG_COMPILER_APPICON_NAME` resolve from that xcconfig; a `Production`-environment configuration SHALL resolve `id.nearyou.app` + the placeholder production API + `AppIcon` (cobalt). For each committed configuration there SHALL be a committed shared scheme under `iosApp/iosApp.xcodeproj/xcshareddata/xcschemes/` that builds it. The per-configuration **CocoaPods base** SHALL be wired (via an `iosApp/Podfile` `project ... '<Config>' => :debug|:release` mapping so `pod install` generates a `Pods-iosApp.<config>.xcconfig` per configuration) so a real build links the correct (debug- or release-typed) Pods for each configuration. No `ASSETCATALOG_COMPILER_APPICON_NAME` hardcode SHALL remain in `project.pbxproj` (the launcher icon resolves from each configuration's xcconfig). (This requirement previously committed only a single `Staging` build configuration + scheme via `mobile-env-launcher-icons`; this change completes the matrix + the dedicated production resolution + the per-config Pods wiring.)

#### Scenario: apiBaseUrl is declared in commonMain config package

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/config/ApiBaseUrl.kt`
- **THEN** the file declares `expect val apiBaseUrl: String`

#### Scenario: Android flavors inject API_BASE_URL BuildConfig field

- **WHEN** inspecting `mobile/app/build.gradle.kts`
- **THEN** the `android { ... productFlavors { ... } }` block declares `dev`, `staging`, AND `production` flavors; each flavor sets `buildConfigField("String", "API_BASE_URL", "\"<env-specific-url>\"")` with the respective URL per the convention above; the staging flavor's URL equals `"https://api-staging.nearyou.id"` AND the production flavor's URL contains the substring `PLACEHOLDER`

#### Scenario: iOS xcconfig drives Info.plist injection

- **WHEN** inspecting `iosApp/iosApp/Configuration/Staging.xcconfig` (or equivalent path)
- **THEN** the file declares an `APP_API_BASE_URL = https://api-staging.nearyou.id` assignment; the `Info.plist` source contains an `ApiBaseUrl` key with value `${APP_API_BASE_URL}` (or equivalent xcconfig substitution); the production xcconfig declares the placeholder URL

#### Scenario: iOS env build configurations and shared schemes are committed for the matrix

- **WHEN** inspecting `iosApp/iosApp.xcodeproj/project.pbxproj` and `iosApp/iosApp.xcodeproj/xcshareddata/xcschemes/`
- **THEN** the env × build-type build configurations from `design.md` Decision 1 exist (covering at least a staging-environment configuration AND a production-environment configuration), each with a committed shared `.xcscheme` that builds it; `xcodebuild -list` shows every matrix configuration + its scheme; the staging-environment build resolves `PRODUCT_BUNDLE_IDENTIFIER = id.nearyou.app.staging` + `https://api-staging.nearyou.id`, and the production-environment build resolves `PRODUCT_BUNDLE_IDENTIFIER = id.nearyou.app` + the placeholder production API — making both reproducible from a fresh checkout without local `xcuserdata`

#### Scenario: No ASSETCATALOG_COMPILER_APPICON_NAME hardcode remains in pbxproj

- **WHEN** grepping `iosApp/iosApp.xcodeproj/project.pbxproj` for `ASSETCATALOG_COMPILER_APPICON_NAME`
- **THEN** there is NO `ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon` (or any value) assignment in the target `buildSettings` — the launcher icon resolves only from each configuration's xcconfig (`Production.xcconfig` → `AppIcon`, `Staging.xcconfig` → `AppIcon-Staging`)

#### Scenario: Per-configuration CocoaPods base is wired so a real build links

- **WHEN** inspecting `iosApp/Podfile` and running `pod install` (via `dev/scripts/ios-pod-install.sh`) then `xcodebuild build` for a debug-typed AND a release-typed matrix configuration
- **THEN** the `Podfile` declares a `project ... '<Config>' => :debug|:release` mapping for every matrix configuration; `pod install` generates a `Pods-iosApp.<config>.xcconfig` per configuration (debug-typed → the debug Pods variant, release-typed → the release Pods variant); and `xcodebuild build` of both configurations succeeds (the framework / Pods link), proving the per-config Pods base resolves correctly
