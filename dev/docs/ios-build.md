# iOS Build & Run Runbook

Build and run `:mobile:app` on the iOS simulator/device from a clean checkout, plus the
build-ordering fix that keeps Compose Multiplatform resources (fonts, drawables) in the
`.app`. For Google Sign-In OAuth client + `GoogleService-Info.plist` + per-env xcconfig
wiring, see [`google-cloud-oauth-clients.md`](google-cloud-oauth-clients.md).

## TL;DR — clean clone to running app

```bash
# 1. CocoaPods needs a UTF-8 locale (see Troubleshooting) — export once per shell:
export LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8

# 2. Generate the workspace. This ALSO bootstraps the KMP framework stub + Compose
#    resources via Gradle (the Podfile does it for you) — no manual Gradle step needed.
cd iosApp && pod install

# 3. Open the WORKSPACE (never the bare .xcodeproj) and Run.
open iosApp.xcworkspace
```

Then pick an iOS Simulator destination and Run. A headless build is:

```bash
xcodebuild -workspace iosApp/iosApp.xcworkspace -scheme iosApp \
  -configuration Debug -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath /tmp/nearyou-ios-dd CODE_SIGNING_ALLOWED=NO build
```

## Prerequisites

- **Xcode** (16.2+ / current) with an iOS Simulator runtime installed.
- **CocoaPods** (`pod --version` ≥ 1.16). Install via Homebrew (`brew install cocoapods`).
  - If `pod --version` fails with a gem-shim error, see the PATH note in
    [`google-cloud-oauth-clients.md`](google-cloud-oauth-clients.md) §3.1.
- **JDK 17** on `PATH` (the Podfile invokes `./gradlew`; Xcode's pod build phase does too).

## Why `pod install` runs Gradle (the resource-bootstrap fix)

**Symptom (pre-fix):** a clean `git clone` → `pod install` → Run crashes at runtime with
`MissingResourceException` for `plus_jakarta_sans.ttf` (the brand font lives in
`:shared:resources` as a Compose Multiplatform resource).

**Root cause.** The `app` pod's podspec (`mobile/app/app.podspec`, **Gradle-generated** — do
not hand-edit) declares:

```ruby
spec.resources = ['build/compose/cocoapods/compose-resources']
```

CocoaPods globs that directory **at `pod install` time** and bakes the result into
`Pods-iosApp-resources.sh` + the resource `.xcfilelist`s. On a fresh clone the directory does
not exist yet (no Gradle build has populated it), so the glob is empty and **CocoaPods drops
the entire `[CP] Copy Pods Resources` build phase**. The app compiles and links fine but ships
with no compose resources; the font lookup throws at first render. A known fragility of
Compose-Multiplatform *multimodule* resources combined with the KMP CocoaPods integration.

**Fix (`iosApp/Podfile`).** Top-level Ruby in the Podfile runs, *before* CocoaPods evaluates
the podspec:

```
./gradlew :mobile:app:generateDummyFramework :mobile:app:syncPodComposeResourcesForIos
```

- `generateDummyFramework` creates the framework stub the podspec requires (otherwise the
  podspec `raise`s "Kotlin framework 'ComposeApp' doesn't exist…"), so `pod install` is
  self-bootstrapping from a bare clone — no manual Gradle step first.
- `syncPodComposeResourcesForIos` populates `build/compose/cocoapods/compose-resources/`, so
  the glob is non-empty and the `[CP] Copy Pods Resources` phase + `resources.sh` are
  generated. The font then reaches the `.app`.

Running at Podfile-eval time (before dependency analysis) fixes both the "framework missing"
precondition and the "resources missing" bug in one pass. `syncPodComposeResourcesForIos`
needs Xcode's `ARCHS`/`PLATFORM_NAME`/`CONFIGURATION` env to infer a target arch; the Podfile
sets placeholder values (`arm64`/`iphonesimulator`/`Debug`) only to satisfy that guard —
Compose resources are byte-identical across archs and configs, and the real per-arch framework
is (re)built later by the podspec's own `syncFramework` Xcode build phase.

> Re-run `pod install` whenever `:shared:resources` (or any module's `composeResources/`)
> changes, so the regenerated manifest captures the new/removed files.

## Troubleshooting

- **`MissingResourceException` / brand font renders as system sans-serif.** The compose
  resources never reached the `.app`. Confirm `pod install` ran the Gradle bootstrap (UTF-8
  locale set, `./gradlew` runnable), then re-run `pod install`. Manual equivalent:
  ```bash
  ./gradlew :mobile:app:generateDummyFramework
  ARCHS=arm64 PLATFORM_NAME=iphonesimulator CONFIGURATION=Debug \
    ./gradlew :mobile:app:syncPodComposeResourcesForIos
  cd iosApp && pod install
  ```
  Verify the manifest captured the font:
  ```bash
  grep compose-resources "iosApp/Pods/Target Support Files/Pods-iosApp/Pods-iosApp-resources.sh"
  ```

- **`pod install` aborts with `Unicode Normalization not appropriate for ASCII-8BIT`.** The
  shell locale is not UTF-8 (CocoaPods normalizes path strings). Fix:
  `export LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8`.

- **Sign-in shows "NetworkError" after pulling new code, but the code looks correct.**
  Observed during the Mobile #4 smoke; traced to **stale build artifacts**, not a code bug. A
  clean rebuild resolves it:
  ```bash
  rm -rf ~/Library/Developer/Xcode/DerivedData/iosApp-* mobile/app/build
  cd iosApp && pod install   # re-bootstraps resources
  ```

- **Need to skip the Gradle bootstrap** (e.g. JVM unavailable in a CI lane and resources are
  already generated): set `NEARYOU_SKIP_COMPOSE_RESOURCE_BOOTSTRAP=1` before `pod install`.
  You are then responsible for ensuring `build/compose/cocoapods/compose-resources/` is
  populated, or the crash returns.

## CI / automation

`iosApp/Pods/`, `iosApp/Podfile.lock`, and `iosApp/*.xcworkspace/` are gitignored. Any CI lane
that builds iOS must run `pod install` (which performs the Gradle bootstrap) before
`xcodebuild`. No separate "generate resources" step is required — the Podfile owns it.
