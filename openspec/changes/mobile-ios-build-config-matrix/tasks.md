## 1. Confirm the config matrix (gate — before any heavy pbxproj/Pods work)

- [ ] 1.1 Surface `design.md` Decision 1 to the user and get the final matrix: the config set (recommend option b — `Dev Debug` / `Staging Debug` / `Staging Release` / `Prod Release`), the iOS `dev` bundle id (`.dev` vs reuse `.staging`), and the Decision 3 open question (add a real `AppIcon-Dev` `#15803D` now, or keep dev reusing the staging tint). Record the decision in `design.md`.
- [ ] 1.2 If the user adds an iOS `dev` icon: generate `AppIcon-Dev.appiconset` (forest green `#15803D`) from a recreated `dev/assets/icon-src/icon-dev.svg` via `dev/scripts/generate-ios-app-icons.sh` (single universal 1024 entry), mirroring the #155 staging approach. Otherwise dev reuses `AppIcon-Staging`.

## 2. pbxproj + xcconfigs — add the matrix configs (scripted, headlessly verifiable)

- [ ] 2.1 Add a `dev/scripts/`-style Python patch (regex with **captured indentation**, per #155) that adds the confirmed matrix build configurations (project + target level) cloned from the right base, each basing off its env xcconfig (`Config`/`Staging`/`Production.xcconfig`) via `baseConfigurationReferenceAnchor` + `relativePath`; wire each into both `XCConfigurationList`s. Use unique 24-hex UUIDs; assert they don't collide.
- [ ] 2.2 Remove the `Debug`/`Release` `ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon` hardcodes (`project.pbxproj:307` / `:336`) so the icon resolves from each config's xcconfig. (Do NOT touch the Pods `baseConfigurationReference` pairing — `Release`→release.xcconfig / `Debug`→debug.xcconfig is CORRECT, NOT swapped; see archived `2026-06-06-mobile-env-launcher-icons/design.md` Decision 4 — recurring false positive.)
- [ ] 2.3 Set `ASSETCATALOG_COMPILER_APPICON_NAME` per env xcconfig: `Production.xcconfig` → `AppIcon`; `Staging.xcconfig` + `Config.xcconfig` → `AppIcon-Staging` (already from #155); `Dev.xcconfig` → `AppIcon-Dev` only if 1.2 added it, else inherit `AppIcon-Staging`. Add `Production.xcconfig` env values if missing (`.nearyou.app` + placeholder API are already there).
- [ ] 2.4 Commit a shared `.xcscheme` per matrix configuration under `xcshareddata/xcschemes/` (reuse the #155 scheme template; set `buildConfiguration` per action).
- [ ] 2.5 Verify headlessly (NO Pods needed): `plutil -lint project.pbxproj` parses; `xcodebuild -list` shows every matrix config + scheme; `xcodebuild -showBuildSettings -configuration '<each>'` resolves the expected `PRODUCT_BUNDLE_IDENTIFIER` + `APP_API_BASE_URL` + `ASSETCATALOG_COMPILER_APPICON_NAME` (staging→AppIcon-Staging+.staging+staging API; production→AppIcon+.nearyou.app+placeholder). Grep pbxproj: zero `ASSETCATALOG_COMPILER_APPICON_NAME` assignments remain.

## 3. CocoaPods per-config wiring + pod install (heavy)

- [ ] 3.1 Add the per-config mapping to `iosApp/Podfile`: `project 'iosApp/iosApp.xcodeproj', '<Config>' => :debug|:release` for every matrix config (debug-typed → `:debug`, release-typed → `:release`).
- [ ] 3.2 Run `pod install` via `dev/scripts/ios-pod-install.sh` (self-bootstraps Gradle compose-resources — heavy). Confirm it generates `Pods-iosApp.<config>.xcconfig` per configuration and regenerates `iosApp.xcworkspace`.
- [ ] 3.3 Ensure each env xcconfig `#include`s its matching `Pods-iosApp.<config>.xcconfig` (debug- vs release-typed) so env settings layer on the correct Pods base (replacing today's single `Config.xcconfig → Pods-iosApp.debug.xcconfig` for everything).
- [ ] 3.4 Confirm what's tracked vs gitignored under `iosApp/Pods/` + `iosApp.xcworkspace`; commit only the intended `project.pbxproj` + xcconfig + `Podfile` + `Podfile.lock` changes (do NOT commit a regenerated `Pods/` if it's gitignored).

## 4. Real-build verification (the part #155 could NOT do)

- [ ] 4.1 `xcodebuild build` the **workspace** for a debug-typed AND a release-typed matrix configuration (simulator destination) — proves the per-config Pods base links (frameworks + GoogleSignIn). This is the load-bearing verification that the Pods wiring is correct.
- [ ] 4.2 (Optional) Launch the staging + production configs on the iOS simulator and confirm the `AppIcon-Staging` (orange) vs `AppIcon` (cobalt) render — closes the iOS visual gap left by #155.

## 5. Docs + specs + validate

- [ ] 5.1 Amend [`docs/04-Architecture.md`](../../../docs/04-Architecture.md):333 — replace "iOS xcconfig schemes: `Staging`, `Production`" with the env × build-type build-configuration matrix (the confirmed set), noting bundle id + API + icon per config. (Sanctioned divergence — user-authorized.)
- [ ] 5.2 The `mobile-auth-signin` spec MODIFY (env-aware-config requirement → matrix) + the `shared-resources` spec MODIFY (iOS icon-resolution scenario → cobalt via `Production.xcconfig`, no hardcode) land via this change's deltas + `openspec archive` sync.
- [ ] 5.3 `openspec validate mobile-ios-build-config-matrix --strict` is green.

## 6. FOLLOW_UPS + archive readiness (deploy smoke N/A)

- [ ] 6.1 Update `FOLLOW_UPS.md` `mobile-env-launcher-icons-ios-dev-icon`: tick the **env-config-completion** action item (this change ships it). The **iOS dev icon** item stays open UNLESS 1.2 added `AppIcon-Dev` (then resolve it too); delete the entry only if both halves ship.
- [ ] 6.2 External-data sanity-check: N/A — no external open-data source.
- [ ] 6.3 Staging deploy smoke: N/A — build-config is a client concern, no server/runtime impact (mark Section 6 deploy steps N/A in the archive commit body).
- [ ] 6.4 Production assets byte-unchanged — confirm `AppIcon.appiconset`, `AppIcon-Staging.appiconset`, and Android `colors.xml` are untouched (`git diff --stat`); this change is pure build-config plumbing.
