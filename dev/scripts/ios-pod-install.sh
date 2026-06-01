#!/usr/bin/env bash
#
# Canonical `pod install` for `iosApp/` — run THIS, never raw `pod install`.
#
# WHY THIS EXISTS
# --------------
# The Compose Multiplatform `compose-resources` (the Plus Jakarta Sans font, `strings.xml`,
# drawables) are a BUILD artifact. The `:mobile:app:syncPodComposeResourcesForIos` Gradle task
# materialises them at `mobile/app/build/compose/cocoapods/compose-resources`, and `app.podspec`
# declares `spec.resources = ['build/compose/cocoapods/compose-resources']`. CocoaPods only wires
# the `[CP] Copy Pods Resources` build phase (which copies that directory into the `.app` bundle)
# when the directory EXISTS + is populated AT `pod install` TIME. `generateDummyFramework` alone
# does NOT populate it (the dummy framework carries no resources).
#
# So running raw `pod install` before any resource sync leaves the resources uncopied, and the iOS
# app crashes on launch with:
#   org.jetbrains.compose.resources.MissingResourceException:
#     Missing resource ... /NearYouID.app/compose-resources/.../font/plus_jakarta_sans.ttf
# (NearYouTheme's FontFamilyResolver.preload throws when the bundled brand font is absent.)
#
# This script enforces the canonical order — **sync resources, THEN pod install** — so the copy
# phase is always wired. CMP 1.5+ auto-manages `spec.resources`; the only gotcha is this ordering.
# Refs: JetBrains/compose-multiplatform #4303 / #4720; CMP iOS resource-sync runs before syncFramework.
#
# USAGE:  dev/scripts/ios-pod-install.sh
# THEN:   open iosApp/iosApp.xcworkspace  (scheme `iosApp`, Debug config = staging defaults)
#         — or build headless with xcodebuild against an iOS Simulator destination.
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

echo "[ios-pod-install] 1/2 — syncing CMP iOS compose-resources + dummy framework…"
# `syncPodComposeResourcesForIos` reads the target arch from the Xcode-set `ARCHS` env var (not a
# `-P` prop — that is for `syncFramework`); without it the task aborts with "Could not infer iOS
# target architectures". The synced resource CONTENT (fonts/strings/drawables) is arch-independent,
# so any sim/arch/config populates the directory correctly — that is all `pod install` needs to wire
# the Copy Pods Resources phase. Respect the env if already inside an Xcode build; else default to
# the iOS-simulator-debug target.
ARCHS="${ARCHS:-arm64}" PLATFORM_NAME="${PLATFORM_NAME:-iphonesimulator}" CONFIGURATION="${CONFIGURATION:-Debug}" \
  ./gradlew :mobile:app:generateDummyFramework :mobile:app:syncPodComposeResourcesForIos

if [ ! -e "mobile/app/build/compose/cocoapods/compose-resources" ]; then
  echo "[ios-pod-install] ERROR: compose-resources were not produced — pod install would skip the" >&2
  echo "                 Copy Pods Resources phase and the app would crash at launch. Aborting." >&2
  exit 1
fi

echo "[ios-pod-install] 2/2 — pod install (UTF-8 locale; '//' in xcconfig URLs needs UTF-8)…"
cd "$ROOT/iosApp"
LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8 pod install

echo "[ios-pod-install] ✓ done — compose-resources are wired into the [CP] Copy Pods Resources phase."
echo "[ios-pod-install]   Build iosApp.xcworkspace (scheme iosApp, Debug = staging) on a Simulator."
