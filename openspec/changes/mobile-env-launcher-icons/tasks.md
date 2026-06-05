## 1. Android — per-flavor launcher icon background

- [ ] 1.1 Create `mobile/app/src/staging/res/values/colors.xml` with `<color name="ic_launcher_background">#C2410C</color>` (burnt orange). The `src/staging/` flavor source set is new — the `dev` one already exists.
- [ ] 1.2 Create `mobile/app/src/dev/res/values/colors.xml` with `<color name="ic_launcher_background">#15803D</color>` (forest green). `src/dev/` already holds `AndroidManifest.xml` + `DevTestLoginActivity`; adding `res/` is consistent.
- [ ] 1.3 Confirm NO `production` flavor override is added — `production` inherits `androidMain`'s `#1E4FD6` (verify `mobile/app/src/production/` has no `res/values/colors.xml`, or no `src/production/` at all).
- [ ] 1.4 Confirm no flavor overrides the foreground / monochrome / adaptive XML — only `ic_launcher_background` differs (grep each `src/<flavor>/res` tree; it must contain only `values/colors.xml`).
- [ ] 1.5 Assemble each flavor debug to prove the overrides build cleanly: `./gradlew :mobile:app:assembleDevDebug :mobile:app:assembleStagingDebug :mobile:app:assembleProductionDebug`.
- [ ] 1.6 Verify the resolved per-flavor color after resource merge: inspect the merged resources (e.g. `mobile/app/build/intermediates/incremental/.../merged.dir` or `merged_res/<variant>/`) — `ic_launcher_background` resolves to `#15803D` (dev), `#C2410C` (staging), `#1E4FD6` (production).

## 2. iOS — commit the environment-separation layer (MANUAL Xcode; NOT headlessly verifiable)

> ⚠️ Steps 2.x require Xcode-project edits (`project.pbxproj` / schemes) that cannot be verified in headless CI. Do them in Xcode (or via a pbxproj-aware tool), commit the resulting diffs, and verify per Section 5's simulator pass. The Android half (Section 1) lands and verifies independently of this section.

- [ ] 2.1 Add a `Staging` build configuration to `iosApp/iosApp.xcodeproj` (duplicate `Release`), and set its `baseConfigurationReference` to `iosApp/Configuration/Staging.xcconfig` so `PRODUCT_BUNDLE_IDENTIFIER = id.nearyou.app.staging` + `APP_API_BASE_URL` resolve from that xcconfig (gives iOS staging a side-by-side `.staging` bundle id).
- [ ] 2.2 Wire `Config.xcconfig` as the base for the `Debug`/`Release`/`Production` configurations' `baseConfigurationReference` if not already (production resolves the cobalt `AppIcon`).
- [ ] 2.3 Create and commit a shared scheme under `iosApp/iosApp.xcodeproj/xcshareddata/xcschemes/` that builds the `Staging` configuration (e.g. `iosApp (Staging).xcscheme`), so the staging build is reproducible from a fresh checkout (no reliance on local `xcuserdata`).
- [ ] 2.4 Confirm `xcodebuild -showBuildSettings -scheme '<staging-scheme>' -configuration Staging | grep -E 'PRODUCT_BUNDLE_IDENTIFIER|APP_API_BASE_URL'` resolves the staging values.

## 3. iOS — staging-tinted AppIcon generation + per-configuration selection

- [ ] 3.1 Recreate a parameterized build-input source SVG under a tooling path (e.g. `dev/assets/icon-src/icon-staging.svg`): `viewBox="0 0 108 108"`, the white hexagon-glyph paths from `mobile/app/src/androidMain/res/drawable-v24/ic_launcher_foreground.xml`, over a single background `<rect>` filled `#C2410C`. (These SVGs are tooling inputs, NOT shipped `Res` resources — do not place them under `shared/resources/.../composeResources/`.)
- [ ] 3.2 Run `dev/scripts/generate-ios-app-icons.sh <staging-svg> <staging-svg> iosApp/iosApp/Assets.xcassets/AppIcon-Staging.appiconset` to rasterize the 1024×1024 PNG variant(s); add the new `AppIcon-Staging.appiconset` with a `Contents.json` mirroring the existing `AppIcon.appiconset` shape.
- [ ] 3.3 Remove/relocate the hardcoded `ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon` from `iosApp/iosApp.xcodeproj/project.pbxproj` build configs so the xcconfig value wins (xcconfig is otherwise the lowest-precedence layer).
- [ ] 3.4 Set `ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon` in `Config.xcconfig` (and/or `Production.xcconfig`) and `ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon-Staging` in `Staging.xcconfig`.
- [ ] 3.5 Confirm `xcodebuild -showBuildSettings -configuration Staging | grep APPICON` resolves `AppIcon-Staging`, and the default/`Release` build resolves `AppIcon`.

## 4. Guard test (plain JVM unit test — no Robolectric/Compose host needed)

- [ ] 4.1 Add a plain JVM unit test in `mobile/app/src/androidUnitTest/kotlin/id/nearyou/app/...` (e.g. `LauncherIconBackgroundTest`) that reads the three colors.xml files and asserts: `src/staging/res/values/colors.xml` → `#C2410C`, `src/dev/res/values/colors.xml` → `#15803D`, `src/androidMain/res/values/colors.xml` → `#1E4FD6`. It is a file-content assertion (NOT `runComposeUiTest`), so it needs no `ui-test-manifest` host activity and requires NO entry in the release-variant test exclude in `build.gradle.kts`.
- [ ] 4.2 If the assertion strips/scans file text, strip XML comments first so the file's own comment text can't trip a literal match (per the source-scan guard precedent).
- [ ] 4.3 Run the mobile gate: `./gradlew :mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest` (flavor-qualified) + root `./gradlew ktlintCheck detekt` — all green.

## 5. Verification + documentation

- [ ] 5.1 Android visual check: install `devDebug` + `stagingDebug` + `productionDebug` on an API 26+ emulator/device and confirm three distinct launcher backgrounds (green / orange / cobalt), same glyph, all installed side-by-side.
- [ ] 5.2 iOS visual check (per the iOS-sim verification recipe): build the staging scheme on the simulator (pod-install ordering per `dev/scripts/ios-pod-install.sh`), confirm the burnt-orange `AppIcon-Staging` renders; build the default/production scheme, confirm the cobalt `AppIcon` is unchanged.
- [ ] 5.3 Add a `FOLLOW_UPS.md` entry for the deferred **iOS dev launcher icon** (Decision 6 default = none) so it's tracked if a distinct iOS dev build later needs visual separation.
- [ ] 5.4 Resolve the Phase D review gate on the `mobile-auth-signin` MODIFIED scenario (design Decision 7): keep it, or downgrade to implementation-completion + a `FOLLOW_UPS.md` note and drop the delta from `specs/mobile-auth-signin/`. Update the spec delta + PR body to match the decision.

## 6. Validate + archive readiness (deploy smoke N/A)

- [ ] 6.1 `openspec validate mobile-env-launcher-icons --strict` is green.
- [ ] 6.2 External-data sanity-check: N/A — this change pulls from no external open-data source (colors are hardcoded brand decisions).
- [ ] 6.3 Staging deploy smoke: N/A — the launcher icon is a client build artifact with no server/runtime impact (mark Section 6 deploy steps N/A in the archive commit body per `openspec/project.md` § Staging deploy timing).
- [ ] 6.4 Production icon is byte-unchanged — confirm the production `AppIcon` PNGs and `androidMain` cobalt assets are untouched by `git diff --stat`.
