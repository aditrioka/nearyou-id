# Google Cloud OAuth Clients + iOS Sign-In Integration Runbook

Provisioning + wiring runbook for the Google Sign-In flow shipped by
`mobile-auth-google-signin-flow` (Mobile #3): Android + iOS OAuth client IDs, the iOS
CocoaPods integration, per-environment xcconfig/scheme wiring, dev-workstation preconditions.

> **Public-repo posture (CLAUDE.md).** Android OAuth client IDs are non-sensitive — the
> SHA-1 signing-cert binding is the security boundary — so they may be committed verbatim to
> the per-flavor `buildConfigField` declarations once provisioned. The iOS
> `GoogleService-Info.plist` IS sensitive when paired with the bundle ID (Apple app-binding
> model) — it is gitignored and pulled from GCP Secret Manager at build time; only the
> `GoogleService-Info.plist.template` placeholder is tracked.

## 1. OAuth client IDs (Google Cloud Console, project `nearyou-staging`)

Create under **APIs & Services → Credentials → Create credentials → OAuth client ID**:

| Client | Type | Bundle / package | Binding |
|---|---|---|---|
| Android — staging | Android | `id.nearyou.app.staging` | SHA-1 of the debug **and** release signing certs |
| Android — dev | Android | `id.nearyou.app.dev` | SHA-1 of the debug signing cert |
| iOS — staging | iOS | `id.nearyou.app.staging` | (bundle ID only) |
| **Web/server** | Web application | — | used as the `audience` of the Google ID token |

- **Server client ID** is the single most important one: it is the `aud` the backend
  `/api/v1/auth/signin` endpoint validates. Put it in the Android per-flavor
  `GOOGLE_SERVER_CLIENT_ID` `buildConfigField` (in `mobile/app/build.gradle.kts`) and pass it
  to `GetGoogleIdOption.Builder().setServerClientId(...)`. The iOS SDK uses the iOS client ID
  for the ceremony, but the backend still validates against the same server/web client ID's
  audience — confirm the backend's audience allow-list includes it.
- Debug SHA-1: `./gradlew :mobile:app:signingReport` (look for the `debug` variant).
- Release SHA-1: from the upload/release keystore (Play App Signing).

After provisioning, replace the `REPLACE_WITH_*_SERVER_CLIENT_ID` placeholders in
`mobile/app/build.gradle.kts`.

## 2. iOS `GoogleService-Info.plist`

1. Download the iOS client's `GoogleService-Info.plist` from the Console.
2. Store it in GCP Secret Manager (e.g. secret `staging-ios-googleservice-info-plist`).
3. At Xcode build time (or via a pre-build script / CI step) drop the real file at
   `iosApp/iosApp/GoogleService-Info.plist` — gitignored; never commit it.
4. `CLIENT_ID` → Info.plist `GIDClientID` (`$(GID_CLIENT_ID)`); `REVERSED_CLIENT_ID` → the
   `CFBundleURLTypes` scheme in `iosApp/iosApp/Info.plist`. Replace the
   `com.googleusercontent.apps.PLACEHOLDER-REVERSED-CLIENT-ID` placeholder with the real
   reversed client ID (it MUST be the reversed-client-ID shape, NOT an arbitrary custom
   scheme — arbitrary schemes are squattable).

## 3. iOS CocoaPods integration (one-time, required by the KMP cocoapods plugin)

> **Canonical build/run runbook:** [`ios-build.md`](ios-build.md). It documents the committed
> `iosApp/Podfile` (which auto-bootstraps the KMP framework stub + Compose resources so a clean
> clone does not crash with `MissingResourceException`), the UTF-8-locale precondition, and the
> `xcodebuild` invocation. The steps below are the original Mobile #3 setup narrative.

`mobile/app/build.gradle.kts` applies `kotlin("native.cocoapods")` with
`pod("GoogleSignIn")`; the iosApp Xcode project consumes the KMP framework + the GoogleSignIn
pod through a CocoaPods workspace:

1. **Dev-workstation precondition — working `pod` CLI on PATH.** This repo was developed on a
   machine where `~/.gem/bin/pod` was a **broken** gem-shim that shadowed a working
   `/opt/homebrew/bin/pod` (CocoaPods 1.16.2). If `pod --version` fails, remove the broken
   shim or ensure Homebrew's bin precedes `~/.gem/bin` on PATH. All iOS Gradle tasks
   (`linkPodDebugFrameworkIosSimulatorArm64`, etc.) invoke `pod install` and will fail
   otherwise. (Interim: prefix Gradle invocations with `PATH=/opt/homebrew/bin:$PATH`.)
2. Generate the synthetic pod spec once: `./gradlew :mobile:app:podInstallSyntheticIos` (the
   framework-link tasks do this automatically).
3. Create `iosApp/Podfile` (the committed file is canonical — it also bootstraps Compose
   resources; see [`ios-build.md`](ios-build.md)). The minimal shape is:
   ```ruby
   platform :ios, '13.0'
   target 'iosApp' do
     use_frameworks!
     pod 'app', :path => '../mobile/app'   # pod name = the `:mobile:app` Gradle project;
                                           # `ComposeApp` is the framework module name.
                                           # GoogleSignIn 8.0.0 is pulled transitively.
   end
   ```
4. `cd iosApp && pod install` → produces `iosApp.xcworkspace` + `Pods/` (both gitignored).
5. **Open `iosApp.xcworkspace`** in Xcode from now on (NOT `iosApp.xcodeproj`).
6. The existing "Run Script" build phase that calls
   `./gradlew :mobile:app:embedAndSignAppleFrameworkForXcode` is replaced by the CocoaPods
   integration — remove or adjust it per the standard KMP-CocoaPods setup so the framework is
   consumed via Pods rather than embedded directly.

## 4. Per-environment xcconfig + scheme wiring (Xcode GUI — task 7.5)

The repo ships three xcconfig files under `iosApp/Configuration/`:
`Config.xcconfig` (base; staging-valued defaults), `Staging.xcconfig`, `Production.xcconfig`.
Each sets `APP_API_BASE_URL`, `GID_CLIENT_ID`, and `PRODUCT_BUNDLE_IDENTIFIER`; `Info.plist`
reads `$(APP_API_BASE_URL)` (→ `ApiBaseUrl`, consumed by the iosMain `apiBaseUrl` actual) and
`$(GID_CLIENT_ID)` (→ `GIDClientID`).

In Xcode (GUI/pbxproj work, not a source edit):
1. **Project → Info → Configurations**: duplicate `Debug`/`Release` into `Staging` +
   `Production` configurations.
2. Set each configuration's **Based on Configuration File** to the matching xcconfig
   (`Staging` → `Staging.xcconfig`, `Production` → `Production.xcconfig`; leave the default
   local-dev `Debug`/`Release` on `Config.xcconfig`).
3. **Product → Scheme → Manage Schemes → +**: add `iosApp (Staging)` + `iosApp (Production)`
   schemes, each pointing its Run/Archive actions at the matching configuration. Keep the
   default `iosApp` scheme on the local-dev (`Debug`) configuration.

## 5. Build verification (task 7.6)

After §3 + §4:
```bash
xcodebuild -workspace iosApp/iosApp.xcworkspace -scheme "iosApp (Staging)" \
  -configuration Staging -sdk iphonesimulator build
```
A staging-configured build should bake `https://api-staging.nearyou.id` into the built
`Info.plist`'s `ApiBaseUrl`. Verify with:
```bash
/usr/libexec/PlistBuddy -c 'Print :ApiBaseUrl' \
  "$(xcodebuild -showBuildSettings -scheme 'iosApp (Staging)' | awk '/ BUILT_PRODUCTS_DIR/{print $3}')/iosApp.app/Info.plist"
```

## 6. Status (Mobile #3 ship)

- §7.1–§7.4, §7.7 (this doc): the source files (`Info.plist`, `iOSApp.swift`,
  `Config/Staging/Production.xcconfig`, `GoogleService-Info.plist.template`) are committed.
- §7.5 (pbxproj scheme/config wiring) + §7.6 (xcodebuild) are **operator/Xcode actions** —
  they require the GUI steps above + a provisioned `GoogleService-Info.plist`, so they are
  performed on a Mac workstation as part of the §10 device smoke, not in the headless apply
  session. Tracked in `tasks.md` §7.
