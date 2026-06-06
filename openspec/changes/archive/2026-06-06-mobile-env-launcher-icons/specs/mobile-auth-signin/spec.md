## MODIFIED Requirements

### Requirement: Environment-aware API base URL via expect/actual config

The mobile app SHALL declare `expect val apiBaseUrl: String` in commonMain (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/config/ApiBaseUrl.kt`). The Android actual SHALL read from a generated `BuildConfig.API_BASE_URL` field injected per gradle product flavor (`dev` / `staging` / `production`). The iOS actual SHALL read from `NSBundle.mainBundle.objectForInfoDictionaryKey("ApiBaseUrl")` driven by an xcconfig variable per scheme.

The `dev` flavor's URL field SHALL be `"http://10.0.2.2:8080"` (Android emulator host loopback) OR an equivalent local-development URL. The `staging` flavor's URL field SHALL be `"https://api-staging.nearyou.id"` (per [`openspec/project.md`](../../project.md) § Environments). The `production` flavor's URL field SHALL be a deliberately-broken placeholder value (e.g., `"https://api.nearyou.id.PLACEHOLDER"`) so a misconfigured production build fails fast; a future change replaces the placeholder when production infra is provisioned.

For the iOS per-scheme resolution to be reproducible from the repository (and not depend on uncommitted local `xcuserdata`), the Xcode project SHALL commit a `Staging` build configuration whose `baseConfigurationReference` is `iosApp/Configuration/Staging.xcconfig`, plus a shared scheme under `iosApp/iosApp.xcodeproj/xcshareddata/xcschemes/` that selects it. (This requirement previously presupposed "an xcconfig variable per scheme" without the build configuration / shared scheme being committed; the `mobile-env-launcher-icons` change wires that layer so the iOS staging build is reproducible — and so it can also select the staging launcher icon.)

#### Scenario: apiBaseUrl is declared in commonMain config package

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/config/ApiBaseUrl.kt`
- **THEN** the file declares `expect val apiBaseUrl: String`

#### Scenario: Android flavors inject API_BASE_URL BuildConfig field

- **WHEN** inspecting `mobile/app/build.gradle.kts`
- **THEN** the `android { ... productFlavors { ... } }` block declares `dev`, `staging`, AND `production` flavors; each flavor sets `buildConfigField("String", "API_BASE_URL", "\"<env-specific-url>\"")` with the respective URL per the convention above; the staging flavor's URL equals `"https://api-staging.nearyou.id"` AND the production flavor's URL contains the substring `PLACEHOLDER`

#### Scenario: iOS xcconfig drives Info.plist injection

- **WHEN** inspecting `iosApp/iosApp/Configuration/Staging.xcconfig` (or equivalent path)
- **THEN** the file declares an `APP_API_BASE_URL = https://api-staging.nearyou.id` assignment; the `Info.plist` source contains an `ApiBaseUrl` key with value `${APP_API_BASE_URL}` (or equivalent xcconfig substitution); the production xcconfig declares the placeholder URL

#### Scenario: iOS Staging build configuration and shared scheme are committed

- **WHEN** inspecting `iosApp/iosApp.xcodeproj/project.pbxproj` and `iosApp/iosApp.xcodeproj/xcshareddata/xcschemes/`
- **THEN** a `Staging` build configuration exists whose `baseConfigurationReference` resolves to `iosApp/Configuration/Staging.xcconfig` (so `PRODUCT_BUNDLE_IDENTIFIER = id.nearyou.app.staging` and `APP_API_BASE_URL` resolve from that xcconfig) AND at least one shared `.xcscheme` is committed under `xcshareddata/xcschemes/` that builds the staging configuration — making the iOS staging build reproducible from a fresh checkout without relying on local `xcuserdata`
