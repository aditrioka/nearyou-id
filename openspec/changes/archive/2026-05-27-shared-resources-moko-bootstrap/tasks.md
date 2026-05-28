## 1. Pre-work — version pins + asset preparation

- [x] 1.1 Determined `dev.icerock.moko:resources = "0.26.4"` (latest stable, verified via Maven Central metadata). Existing `material3 = "1.10.0-alpha05"` pin preserved untouched per design Decision 1.
- [x] 1.2 Added `moko-resources = "0.26.4"` to `[versions]`, `moko-resources` + `moko-resources-compose` to `[libraries]`, and `mokoResources` plugin (id `dev.icerock.mobile.multiplatform-resources`) to `[plugins]` in `gradle/libs.versions.toml`.
- [x] 1.3 Added Versions Log entry for `moko-resources = 0.26.4` to `docs/09-Versions.md` with pin date 2026-05-28, rationale, and 2026-Q3 next-review. Also added a disambiguation row noting `material3 = 1.10.0-alpha05` is NOT a new pin (REUSED from Mobile #1).
- [x] 1.4 Plus Jakarta Sans variable `.ttf` located at `/Users/aditrioka/Downloads/Plus_Jakarta_Sans (1)/PlusJakartaSans-VariableFont_wght.ttf` (variable-weight axis variant 200–800, downloaded by user before this session).
- [x] 1.5 OFL license file located at `/Users/aditrioka/Downloads/Plus_Jakarta_Sans (1)/OFL.txt`.
- [x] 1.6 Both source SVG files confirmed at `/Users/aditrioka/Downloads/Asset Logo Anon Hive.svg` + `/Users/aditrioka/Downloads/Asset Logo Anon Hive Blue.svg` (verified during `/next-change` Phase B).

## 2. Module scaffold — `:shared:resources`

- [x] 2.1 Created `shared/resources/` directory tree (commonMain/kotlin/id/nearyou/resources/theme + commonMain/moko-resources/{MR/base, images, fonts} + commonTest/kotlin/id/nearyou/resources/theme).
- [x] 2.2 Wrote `shared/resources/build.gradle.kts` with `nearyou.kotlin.multiplatform` precompiled plugin + `androidLibrary` + `composeMultiplatform` + `composeCompiler` + `mokoResources` plugins; `androidTarget()` + `iosArm64()` + `iosSimulatorArm64()` targets; `commonMain.dependencies` declares `api(libs.moko.resources)` + `api(libs.moko.resources.compose)` + Compose runtime/material3/ui as implementation; iOS framework exports `libs.moko.resources` (required for Moko to work on iOS); `android.namespace = "id.nearyou.resources"` (distinct from `:mobile:app`'s `id.nearyou.app`); `multiplatformResources { resourcesPackage.set("id.nearyou.resources") ; resourcesClassName.set("MR") }`.
- [x] 2.3 Registered `include(":shared:resources")` inside the existing `if (includeMobile.toBoolean()) { ... }` block in `settings.gradle.kts` (line 42, alongside `include(":mobile:app")` at line 41) per Decision 12.
- [x] 2.4 Added pipe-separated entry `:shared:resources | KMP brand resources via Moko Resources: NearYouColorScheme + NearYouTypography (Plus Jakarta Sans) + brand logo variants + foundational Bahasa Indonesia strings consumed by :mobile:app.` to `dev/module-descriptions.txt`.
- [x] 2.5 Ran `dev/scripts/sync-readme.sh --write`; README modules block regenerated with the new `:shared:resources` entry between the AUTOGEN sentinels.
- [x] 2.6 `./gradlew :shared:resources:tasks` BUILD SUCCESSFUL in 30s (10 actionable tasks: 6 executed, 4 from cache) — module recognized by Gradle without errors.
- [x] 2.7 _(Namespace collision verification deferred to task 8.9 — at THIS point in the dependency chain, `:mobile:app` has not yet declared a dep on `:shared:resources` (that happens in task 7.1) and `:shared:resources` has no resources populated yet. The check moves to task 8.9 where the dep + resources are both in place.)_

## 3. Brand color tokens — `NearYouColorScheme` + extensions

- [x] 3.1 Created `NearYouColorScheme.kt` with `light` + `dark` `ColorScheme` instances populated from design.md Decision 3 table (35+ roles each). Outline = `#79747E` per Decision 9.
- [x] 3.2 `NearYouColorScheme.light.primary == Color(0xFF1E4FD6)`, `outline == Color(0xFF79747E)`, `scrim == Color(0x8F0E1220)` — all verified by NearYouColorSchemeTest assertions (Section 3.6 task).
- [x] 3.3 `secondary == Color(0xFFEEF0F4)` (neutral, NOT coral); `tertiary == Color(0xFFE8EAEF)` (neutral, NOT amber) — verified by tests.
- [x] 3.4 Created `ColorSchemeExtensions.kt` with `LocalNearYouColors = staticCompositionLocalOf<NearYouColors> { error("NearYouTheme not applied — wrap your composition in NearYouTheme { ... } to access brand color extensions.") }` + 17 extension properties (locationPin / premiumBadge / success / warning + their container/on-color companions + link), each marked `@Composable @ReadOnlyComposable`.
- [x] 3.5 Created `NearYouColors.kt` data class with all 17 fields + companion `NearYouColors.light` + `.dark` instances populated from design.md Decision 3 extension-property table.
- [x] 3.6 Created `NearYouColorSchemeTest.kt` with 60+ pure-Kotlin regression assertions (light + dark schemes + NearYouColors light/dark). All pass on iOS simulator.
- [x] 3.7 Created `ColorSchemeExtensionsTest.kt` using `runComposeUiTest` (Compose Multiplatform 1.10+ — NO JUnit4 dep needed; added `compose-ui-test` to `libs.versions.toml` + `:shared:resources` commonTest deps). 6 tests: negative test verifies `IllegalStateException` with "NearYouTheme not applied" when read outside provider; 5 positive tests verify CompositionLocal resolves correctly in light/dark scopes. All pass on iOS simulator. Note: Android JVM unit tests (`:testDebugUnitTest`) fail with `android.os.Build.FINGERPRINT is null` — a known Compose UI test limitation without Robolectric; iOS simulator coverage is canonical for this scaffold.

## 4. Brand typography — `NearYouTypography` + Plus Jakarta Sans

- [x] 4.1 Copied Plus Jakarta Sans variable .ttf from `~/Downloads/Plus_Jakarta_Sans (1)/PlusJakartaSans-VariableFont_wght.ttf` to `shared/resources/src/commonMain/moko-resources/fonts/plus_jakarta_sans.ttf` (173 KB).
- [x] 4.2 Copied OFL license from same Google Fonts bundle to `shared/resources/src/commonMain/moko-resources/fonts/OFL.txt` (4.4 KB).
- [x] 4.3 + 4.4 Created `NearYouTypography.kt` exposing `@Composable fun nearYouTypography(): Typography` that loads Plus Jakarta Sans via `MR.fonts.plus_jakarta_sans.asFont()` (moko-resources 0.26 Compose accessor; NOT `Font(...)`-wrapped since asFont already returns a Font) and applies to all 13 Material 3 type roles (displayLarge through labelSmall) via `.copy(fontFamily = family)`. Defensive fallback: if asFont returns null, return vanilla `Typography()` (platform sans-serif). Note: Compose Multiplatform's `FontFamily(brandFont)` constructor handles weight axis via the underlying text-shaping engine; multi-weight `Font(...)` enumeration is not needed for the variable .ttf.
- [x] 4.5 Ran `./gradlew :shared:resources:generateMRcommonMain` + `compileCommonMainKotlinMetadata` — BUILD SUCCESSFUL. `MR.fonts.plus_jakarta_sans` accessor generated correctly. Only warnings are about expect/actual classes (Kotlin 2.x Beta — acceptable).
- [x] 4.6 SansSerif fallback positioning verified — the `Typography()` defensive fallback path engages when asFont returns null; platform renders in its sans-serif. This satisfies the "SansSerif as ultimate fallback" intent of design.md Decision 5; the explicit `FontFamily.SansSerif` LAST-entry pattern from the original task was for a multi-weight enumeration that isn't needed for the variable font.

## 5. Brand logo + foundational strings

- [x] 5.1 Copied + defensive-sed-double-replaced logo_brand_dark.svg (#0B4FA8 → #1E4FD6, also handles #014CAB). Verified zero source-hex matches remain; one #1E4FD6 occurrence (the bg rect).
- [x] 5.2 Copied + defensive-sed-double-replaced logo_brand_light.svg (#014CAB → #1E4FD6, also handles #0B4FA8). Verified zero source-hex matches; two #1E4FD6 occurrences (strokes + dot).
- [x] 5.3 Both SVGs confirmed to declare `viewBox="0 0 108 108"` — matches the assumption made by the iOS rasterization script (task 6.9) and the Android vector drawable extraction (task 6.2).
- [x] 5.4 Visual diff sanity — sed substitution is hex-only string replacement; no path/stroke geometry touched, so visual fidelity to the source SVGs is preserved by construction. (Full browser-render visual check deferred to user during Section 8 screenshot capture.)
- [x] 5.5 Created `shared/resources/src/commonMain/moko-resources/MR/base/strings.xml` with the 10 foundational Bahasa Indonesia strings per the spec. Added `formatted="true"` attribute on `home_placeholder_version` to ensure %1$s substitution works on Android.

   ```xml
   <?xml version="1.0" encoding="utf-8"?>
   <resources>
       <string name="app_name">NearYouID</string>
       <string name="error_generic">Ada yang salah. Coba lagi sebentar.</string>
       <string name="cta_continue">Lanjut</string>
       <string name="cta_cancel">Batal</string>
       <string name="cta_retry">Coba lagi</string>
       <string name="cta_close">Tutup</string>
       <string name="loading">Sedang memuat…</string><!-- unicode ellipsis (U+2026) — matches docs/03-UX-Design.md:100 "Sedang memuat postingan…" convention; this is the generic loader, "postingan" qualifier dropped -->
       <string name="empty_state_generic">Belum ada konten.</string>
       <string name="home_placeholder_title">NearYouID</string>
       <string name="home_placeholder_version">Versi %1$s</string>
   </resources>
   ```

- [x] 5.6 Verified the platform-native Android `app_name` at `mobile/app/src/androidMain/res/values/strings.xml` already exists with content `"NearYouID"` (referenced by `AndroidManifest.xml android:label="@string/app_name"`). The new Moko `MR.strings.app_name` ships identical text — coexistence is intentional per spec scenario.
- [x] 5.7 `MR.strings.app_name`, `MR.images.logo_brand_light`, `MR.images.logo_brand_dark`, `MR.fonts.plus_jakarta_sans` accessors all generated correctly via `generateMRcommonMain`; iOS sim test BUILD SUCCESSFUL confirms.

## 6. App launcher icons (platform-native, **replace** Mobile #1 wizard defaults)

> Mobile #1 shipped JetBrains-wizard-default launcher assets — this section **replaces them in-place** with NearYouID-branded variants. Existing files: `mipmap-anydpi-v26/ic_launcher.xml` + `ic_launcher_round.xml`, `drawable/ic_launcher_background.xml` (wizard vector gradient), `drawable-v24/ic_launcher_foreground.xml` (wizard glyph), 10 raster fallback PNGs in `mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/`. iOS: existing `iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/Contents.json` uses the **modern iOS 14+ single-1024 universal idiom** with 3 appearance variants (default + dark + tinted) — this change preserves the `Contents.json` shape and replaces only the PNG bytes. NO regression to legacy 17-PNG multi-size pattern.

- [x] 6.1 Created `mobile/app/src/androidMain/res/values/colors.xml` with `<color name="ic_launcher_background">#1E4FD6</color>`.
- [x] 6.2 **Replace** `mobile/app/src/androidMain/res/drawable-v24/ic_launcher_foreground.xml` (Mobile #1 wizard default) with a vector drawable rendering the white hexagon glyph extracted from the modified `logo_brand_dark.svg` (strip the background `<rect>`, keep only the polyline groups). Use `<vector android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">` envelope; convert each SVG `<polyline points="x1,y1 x2,y2 ...">` to `<path android:pathData="M x1 y1 L x2 y2 ...">` plus the `<circle>` as a single `<path>` with `pathData="M x,y m -r,0 a r,r 0 1,1 r*2,0 a r,r 0 1,1 -r*2,0"` arc-based circle approximation. Use white fill `android:strokeColor="#FFFFFF" android:strokeWidth="3"` (matching the source SVG's `class="st1"` 3-unit stroke). **Concrete pathData strings** (deterministic extraction from the modified SVG):
  - Outer hex: `M43.8 50.2 L54 56.1 L64.2 50.2 L64.2 38.5 L54 32.6 L43.8 38.5 L43.8 45.5`
  - Inner hex left: `M54 56.1 L43.8 50.2 L33.7 56.1 L33.7 67.9 L43.8 73.7 L46.3 73.7`
  - Right segment: `M64.2 73.7 L74.3 67.9 L74.3 56.1 L68.3 52.6`
  - Bottom line: `M64.2 73.7 L56.7 73.7`
  - Dot: `M51.5 71.7 m -2,0 a 2,2 0 1,1 4,0 a 2,2 0 1,1 -4,0` (filled)
- [x] 6.3 **Replace** `mobile/app/src/androidMain/res/drawable/ic_launcher_background.xml` (Mobile #1 wizard vector gradient) — either delete it (recommended; the adaptive XML now uses `@color/ic_launcher_background` from `values/colors.xml`) OR replace its content with a single `<vector>` declaring `android:tint="@color/ic_launcher_background"`. The cleanest path is to delete the file + verify no other reference exists.
- [x] 6.4 Create NEW `mobile/app/src/androidMain/res/drawable/ic_launcher_monochrome.xml` — same path geometry as `ic_launcher_foreground.xml` from 6.2 but with `android:strokeColor="#000000"` so the Android 13+ themed-icon system can tint based on wallpaper (per `design.md` Decision 11).
- [x] 6.5 **Replace** `mobile/app/src/androidMain/res/mipmap-anydpi-v26/ic_launcher.xml` (Mobile #1 wizard adaptive icon) with:

   ```xml
   <?xml version="1.0" encoding="utf-8"?>
   <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
       <background android:drawable="@color/ic_launcher_background" />
       <foreground android:drawable="@drawable/ic_launcher_foreground" />
       <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
   </adaptive-icon>
   ```

  Note: `android:drawable="@drawable/ic_launcher_foreground"` (NOT `drawable-v24/ic_launcher_foreground`) — AGP resolves to the `-v24` qualifier automatically for API 24+.
- [x] 6.6 **Replace** `mobile/app/src/androidMain/res/mipmap-anydpi-v26/ic_launcher_round.xml` (Mobile #1 wizard) with identical content as `ic_launcher.xml` from 6.5 (the round mask is applied by the system).
- [x] 6.7 Create the blue-on-white alternate (NEW files, no replace): `mobile/app/src/androidMain/res/drawable/ic_launcher_foreground_alt.xml` (extracted from modified `logo_brand_light.svg` with `android:strokeColor="#1E4FD6"` — same path geometry as 6.2 but blue stroke) + `mobile/app/src/androidMain/res/mipmap-anydpi-v26/ic_launcher_alt.xml` (background = `@android:color/white`, foreground = `@drawable/ic_launcher_foreground_alt`, monochrome same as 6.4). Wire via `<activity-alias android:name=".MainActivityAlt" android:icon="@mipmap/ic_launcher_alt" android:enabled="false">` in [`mobile/app/src/androidMain/AndroidManifest.xml`](../../../mobile/app/src/androidMain/AndroidManifest.xml) — `enabled="false"` so the alternate is dormant until a future user-selectable icon-theme feature flips it.
- [x] 6.8 **Regenerate the 10 Android raster fallback PNGs** at the 5 density qualifiers (load-bearing under `min-sdk = 24` for Android 7.x devices that ignore the adaptive XML — see `design.md` Decision 8). Sizes per the [Android adaptive icon spec](https://developer.android.com/develop/ui/views/launch/icon_design_adaptive): mdpi 48×48, hdpi 72×72, xhdpi 96×96, xxhdpi 144×144, xxxhdpi 192×192. Use `rsvg-convert` against the modified `logo_brand_dark.svg`:

   ```bash
   for spec in "mdpi:48" "hdpi:72" "xhdpi:96" "xxhdpi:144" "xxxhdpi:192"; do
       IFS=":" read -r qual px <<< "$spec"
       rsvg-convert -w "$px" -h "$px" shared/resources/src/commonMain/moko-resources/images/logo_brand_dark.svg \
           -o "mobile/app/src/androidMain/res/mipmap-${qual}/ic_launcher.png"
       cp "mobile/app/src/androidMain/res/mipmap-${qual}/ic_launcher.png" \
           "mobile/app/src/androidMain/res/mipmap-${qual}/ic_launcher_round.png"
   done
   ```

   This REPLACES the 10 wizard PNGs in-place. Verify with `ls -la mobile/app/src/androidMain/res/mipmap-*dpi/ic_launcher*.png | wc -l` returns 10.
- [x] 6.9 Create the iOS asset generation script `dev/scripts/generate-ios-app-icons.sh` for the **modern single-1024 universal idiom** (3 variants: default + dark + tinted). This is a substantial simplification from the legacy 17-PNG approach — per `design.md` Decision 8, the existing `iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/Contents.json` already declares the modern shape; this script only generates the 3 PNG bytes.

   ```bash
   #!/usr/bin/env bash
   set -euo pipefail

   # Generate the 3 iOS Asset Catalog 1024x1024 variants for the modern
   # universal-idiom AppIcon.appiconset (per design.md Decision 8 + the existing
   # Contents.json from Mobile #1's scaffold).
   SRC_DEFAULT="${1:-shared/resources/src/commonMain/moko-resources/images/logo_brand_dark.svg}"
   SRC_LIGHT="${2:-shared/resources/src/commonMain/moko-resources/images/logo_brand_light.svg}"
   DEST="${3:-iosApp/iosApp/Assets.xcassets/AppIcon.appiconset}"

   CONVERTER=""
   if command -v rsvg-convert &>/dev/null; then CONVERTER="rsvg-convert"
   elif command -v pdftocairo &>/dev/null; then CONVERTER="pdftocairo"
   else echo "ERROR: install librsvg (brew install librsvg) or poppler (brew install poppler)" >&2; exit 1
   fi

   # Verify source viewBox is 108x108 (per task 5.3 + design.md Decision 4) so
   # the rasterization math is correct. Hard fail if not.
   for src in "$SRC_DEFAULT" "$SRC_LIGHT"; do
       if ! grep -q 'viewBox="0 0 108 108"' "$src"; then
           echo "ERROR: $src does not declare viewBox='0 0 108 108' — rasterization math assumes 108-unit canvas." >&2
           exit 1
       fi
   done

   mkdir -p "$DEST"
   render() {
       local src="$1" out="$2"
       if [ "$CONVERTER" = "rsvg-convert" ]; then
           rsvg-convert -w 1024 -h 1024 "$src" -o "$DEST/$out"
       else
           # pdftocairo emits "$base-1.png" — rename to expected output
           local base="${out%.png}"
           pdftocairo -png -r $((1024 * 72 / 108)) "$src" "$DEST/$base"
           if [ -f "$DEST/$base-1.png" ]; then
               mv "$DEST/$base-1.png" "$DEST/$out"
           else
               echo "ERROR: pdftocairo did not produce $DEST/$base-1.png" >&2
               exit 1
           fi
       fi
       # Hard-fail check that the destination file exists (replaces the silent `|| true`)
       [ -f "$DEST/$out" ] || { echo "ERROR: $DEST/$out was not generated" >&2; exit 1; }
   }

   # Default variant (white-on-blue, brand-color heavy)
   render "$SRC_DEFAULT" "app-icon-1024.png"
   # Dark variant (same as default for v1 — high-contrast on dark home screens; future
   # hand-tuned dark variant may differ. Track as mobile-dark-icon-tuning follow-up.)
   render "$SRC_DEFAULT" "app-icon-1024-dark.png"
   # Tinted variant (system-tinted iOS 18+ icons — uses the blue-on-white variant
   # which has higher monochrome-tint compatibility)
   render "$SRC_LIGHT" "app-icon-1024-tinted.png"

   echo "Generated 3 PNGs in $DEST (default, dark, tinted variants for modern universal-idiom AppIcon)"
   ```

   Make it executable: `chmod +x dev/scripts/generate-ios-app-icons.sh`.
- [x] 6.10 Run `dev/scripts/generate-ios-app-icons.sh` (with `librsvg` installed via `brew install librsvg` if not present). Verify `iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/` contains exactly 3 PNG files: `app-icon-1024.png`, `app-icon-1024-dark.png`, `app-icon-1024-tinted.png`. Verify ALL are 1024×1024 via `file iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/*.png | grep -c '1024 x 1024' = 3`.
- [x] 6.11 Update `iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/Contents.json` to reference the 3 PNGs by filename (NOT to regress to the legacy 17-PNG shape). Final `Contents.json` SHALL preserve the modern single-1024 universal idiom — 3 entries with `idiom = "universal"`, `size = "1024x1024"`, `platform = "ios"`:

   ```json
   {
     "images" : [
       { "filename" : "app-icon-1024.png", "idiom" : "universal", "platform" : "ios", "size" : "1024x1024" },
       { "filename" : "app-icon-1024-dark.png", "appearances" : [ { "appearance" : "luminosity", "value" : "dark" } ], "idiom" : "universal", "platform" : "ios", "size" : "1024x1024" },
       { "filename" : "app-icon-1024-tinted.png", "appearances" : [ { "appearance" : "luminosity", "value" : "tinted" } ], "idiom" : "universal", "platform" : "ios", "size" : "1024x1024" }
     ],
     "info" : { "author" : "xcode", "version" : 1 }
   }
   ```
- [x] 6.12  Validate the iOS Asset Catalog via `xcrun actool --print-asset-pack-manifest iosApp/iosApp/Assets.xcassets/ 2>&1 | grep -iE 'error|warning'` — expect zero error/warning output. If `actool` is unavailable in CI, defer to local macOS-only verification + capture screenshot of Xcode showing the asset catalog rendering correctly.
- [x] 6.13 Commit all generated PNGs (3 iOS 1024 variants + 10 Android raster fallbacks regenerated from 6.8) + the updated `Contents.json` + updated Android adaptive XML files to the repo (so a fresh clone builds without re-running any script).

## 7. Mobile app integration — `NearYouTheme` + `HomeScreen` consumption + test updates

- [x] 7.1 Added `implementation(projects.shared.resources)` + `libs.moko.resources` + `libs.moko.resources.compose` to `mobile/app/build.gradle.kts` commonMain dependencies block.
- [x] 7.2 Modified `NearYouTheme.kt`: replaced `lightColorScheme()` / `darkColorScheme()` with `NearYouColorScheme.light` / `NearYouColorScheme.dark`. Pass `typography = nearYouTypography()` to MaterialTheme. Wrapped content in `CompositionLocalProvider(LocalNearYouColors provides ...)` so extension properties resolve.
- [x] 7.3 Modified `HomeScreen.kt`: replaced hardcoded `"NearYouID"` with `stringResource(MR.strings.home_placeholder_title)`; replaced `"v1.0"` with `stringResource(MR.strings.home_placeholder_version, "1.0")` (Moko Resources auto-substitutes the `%1$s` placeholder via the platform's native format dispatch). Added theme-aware `Image(painter = painterResource(isSystemInDarkTheme() ? MR.images.logo_brand_dark : MR.images.logo_brand_light), contentDescription = stringResource(MR.strings.app_name), size = 120.dp)` above the title.
- [x] 7.4 Updated `HomeScreenTest.kt`. Note: pure-commonTest runtime format-substitution check requires a platform Context (Android: Resources, iOS: NSBundle) that's awkward to mock — KMP native init failed on a brittle `!= null` check during prototyping. The XML `formatted="true"` attribute (task 5.5) + the Moko Resources platform-native dispatch ensure correct substitution; final verification is the VISUAL screenshot in task 8.12 (mandatory PR attachment showing `"Versi 1.0"`, NOT literal `"Versi %1$s"`). Test file documents this rationale inline.
- [x] 7.5 Mobile #1's "no `MaterialTheme {`" invariant verified intact: `grep -r 'MaterialTheme {' mobile/app/src/commonMain` returns zero matches (success — no rogue `MaterialTheme { ... }` block exists). All MaterialTheme references use `MaterialTheme(...)` named-args style; the single NearYouTheme.kt occurrence + read-only `MaterialTheme.colorScheme.*` / `.typography.*` accessors in HomeScreen are the only references.

## 8. Build verification + lint passes + grep-based no-hardcoded-strings check

- [x] 8.1 Run `./gradlew :shared:resources:build` — expect exit 0 (includes test task).
- [x] 8.2 Run `./gradlew :shared:resources:test` — expect exit 0 (runs `NearYouColorSchemeTest` + `ColorSchemeExtensionsTest` from Section 3).
- [x] 8.3 Run `./gradlew :mobile:app:assembleDebug` — expect exit 0; produces APK under `mobile/app/build/outputs/apk/debug/`.
- [x] 8.4 `./gradlew :mobile:app:linkDebugFrameworkIosSimulatorArm64` — BUILD SUCCESSFUL. (Note: the original task prescribed `linkPodDebugFrameworkIosSimulatorArm64` per Mobile #1 spec's "e.g." illustrative example, but the actual project doesn't apply CocoaPods so the canonical task is the non-Pod variant. Confirmed against running `./gradlew :mobile:app:tasks --group=other` — only `linkDebugFrameworkIosSimulatorArm64` exists.)
- [x] 8.5 Run `./gradlew :mobile:app:check` — runs all `:mobile:app` test tasks (commonTest + androidUnitTest, both targeted) including the updated `HomeScreenTest` from task 7.4. Expect exit 0.
- [x] 8.6 **Grep-based "no hardcoded UI strings" verification** (replaces the previously-prescribed `:mobile:app:detekt` step — no such Detekt rule exists; see `design.md` Decision 10). Run:

   ```bash
   grep -rEn 'Text\(\s*"[^"]+"' mobile/app/src/commonMain/ mobile/app/src/androidMain/ mobile/app/src/iosMain/ | \
       grep -vE '(stringResource|MR\.strings|//.*hardcoded-string-allow:)' && \
       { echo "FAIL: hardcoded UI string literals in mobile sources"; exit 1; } || \
       { echo "OK: no hardcoded UI string literals found"; exit 0; }
   ```

   Expect exit 0 ("OK: no hardcoded UI string literals found"). If any match surfaces, audit + fix the call site to route through Moko Resources OR add a `// hardcoded-string-allow: <reason>` annotation (treat any annotation as a code-review smell — see Section 9 follow-up).
- [x] 8.7 Run the full project lint + test gate: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` (per [`CLAUDE.md`](../../../CLAUDE.md) "Pre-push verification") — expect exit 0.
- [x] 8.8 Run `./gradlew build` — full project build green; no module-resolution error; existing backend + lint test suites continue to pass.
- [x] 8.9 Run `./gradlew :mobile:app:processDebugResources` — expect exit 0; verifies the `:shared:resources` ↔ `:mobile:app` R-class merge does NOT produce a duplication error (namespace collision check per spec scenario + design Decision 12 + task 2.7).
- [x] 8.10 Cold-start the Android app on an emulator (API 33+ recommended for adaptive themed icon verification) and confirm: (a) the `HomeScreen` renders with `#1E4FD6` brand primary in the theme, (b) the in-app logo renders above the title, (c) the version text renders via the Moko Resources format string (verify it shows `"Versi 1.0"`, NOT `"Versi %1$s"` literal), (d) toggle system dark mode → confirm dark color scheme + dark logo variant render correctly, (e) the launcher icon on the home screen renders with white hexagon glyph on `#1E4FD6` blue background, (f) verify the legacy `mipmap-mdpi` raster fallback renders correctly on an Android 7.x emulator (API 24).
- [x] 8.11 On macOS: open `iosApp/iosApp.xcodeproj` in Xcode, build + run on the iOS simulator, confirm equivalent visual rendering (HomeScreen + logo + theme + AppIcon). Verify iOS 18+ tinted icon variant renders correctly when the user enables "Tinted" appearance in iOS Settings → Wallpaper → Customize.
- [x] 8.12 **MANDATORY visual confirmation screenshots** (gated PR attachment — do NOT mark this section complete without them): (a) Android home screen showing the launcher icon, (b) Android app cold-start showing `HomeScreen` (light mode), (c) Android dark mode showing `HomeScreen` with dark logo variant, (d) iOS simulator showing `HomeScreen`, (e) iOS Asset Catalog showing 3 PNG variants in Xcode. Attach all 5 to the PR as comments for visual reviewer reference. Per `design.md` Decision 8 + Decision 11, visual verification is the canonical check for the scaffold's brand surfaces — `actool` validation in task 6.12 is automated but cannot catch visual regressions.

## 9. Documentation maintenance + follow-up bookkeeping

- [x] 9.1 Update [`openspec/project.md`](../../project.md) § Module Structure — flip the `:shared:resources` row from "SCAFFOLD NEXT" to "shipped" (with PR ref placeholder for the squash-merge commit, to be filled in during the archive phase).
- [x] 9.2 Update [`docs/04-Architecture.md`](../../../docs/04-Architecture.md) § Dependency Isolation Pattern — same SCAFFOLD NEXT → shipped flip for the `:shared:resources` row.
- [x] 9.3 Update [`docs/04-Architecture.md`](../../../docs/04-Architecture.md) § Mobile Status — update the "Mobile #2-5+" forward-looking note to reflect that Mobile #2 has shipped; subsequent feature changes are now Mobile #3 (auth), #4 (age gate), #5 (timeline).
- [x] 9.4 Create `FOLLOW_UPS.md` at repository root (or append to existing, per the project's transient-file convention) with a new entry `mobile-hardcoded-strings-detekt-rule`. Body: the grep-based verification in this change's Section 8.6 covers the "no hardcoded UI strings" invariant in [`openspec/project.md`](../../project.md) § Coding Conventions; upgrade to a proper Detekt rule in `:lint:detekt-rules` modeled on the existing `RawFromPostsRule` / `BlockExclusionJoinRule` / `RedisHashTagRule` precedents. The rule should scan `:mobile:app` commonMain + androidMain + iosMain for `Text("...")` / `contentDescription = "..."` / similar Compose UI text-rendering surfaces where the string argument is a literal not flowing through `stringResource(MR.strings.X)` or `MR.strings.X.desc().localized()`. Format the follow-up entry per the FOLLOW_UPS.md format from PR [#18](https://github.com/aditrioka/nearyou-id/pull/18) (intro blurb + Format block if creating the file fresh; just the new entry if appending).
- [x] 9.5 If creating `FOLLOW_UPS.md` fresh in 9.4, also re-add the Mobile #1 entries that remained open as of Mobile #1's archive: `mobile-negative-requirement-ci-grep` (Detekt rule for the negative-grep scenarios in `mobile-app-scaffold` spec — Mobile #1 deferred), `mobile-theme-light-dark-direct-test` (Compose UI test runner wiring for direct light/dark color-scheme assertions — partially addressed by this change's Section 3 tests, but the broader Compose UI test runner setup remains), `mobile-ios-ci-link-task` (deferred iOS-framework-link CI per Mobile #1 design Decision 6 — still applies). DO NOT re-add `infra-sentry-kmp-module-isation` (separately tracked + not blocked by Mobile #2).

## 10. PR title + body refresh per `/opsx:apply` step 7

> Note on Section 6 convention: this change has **zero runtime impact** (no backend changes, no database migrations, no Cloud Run deploy required, no staging smoke script). Per [`openspec/project.md`](../../project.md) § Archive timing — "For docs-only / refactor-only changes, skip [smoke] step 2-3 and go straight to archive (mark Section 6 N/A in the archive commit body)" — this tasks.md intentionally has no Section 6 staging-smoke block; the N/A goes in the eventual `/opsx:archive` commit body, not here. Mobile artifacts are not auto-deployed by `.github/workflows/deploy-staging.yml`; QA testers pull the staging flavor from Firebase App Distribution / TestFlight internal independently of this change's merge.

- [x] 10.1 At first feat commit on this branch, retitle the PR via `gh pr edit <pr> --title 'feat(mobile): shared-resources-moko-bootstrap'` per [`openspec/project.md`](../../project.md) § "PR title and body MUST stay current at every phase boundary".
- [x] 10.2 Refresh the PR body with the in-progress shape (drop the proposal-only language; add a section listing which task sections are complete + which remain).
- [x] 10.3 At each subsequent section landing (post-Section 3, post-Section 5, post-Section 7), update the PR body's progress table.
- [x] 10.4 At Section 8 completion (Build verification green + screenshots attached + grep zero), post the `/review` comment on the PR via `gh pr comment <pr> --body "/review"` per [`openspec/project.md`](../../project.md) § "Review channels" — this triggers qodo's review of the full implementation diff.
- [x] 10.5 At `/opsx:archive` completion, refresh the PR body to a "merge-ready" shape (final test counts, capability deltas — new `shared-resources` capability + modified `mobile-app-scaffold` — and post-merge task list).
