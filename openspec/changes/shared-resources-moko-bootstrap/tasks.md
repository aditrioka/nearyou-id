## 1. Pre-work — version pins + asset preparation

- [ ] 1.1 Determine the latest stable `dev.icerock.moko:resources` plugin + library versions (check Maven Central via `gradle dependencyInsight` or `https://search.maven.org/`). Record as candidate pin. **Do NOT add a new `material3` pin** — the existing pin `material3 = "1.10.0-alpha05"` ([`gradle/libs.versions.toml:34`](../../../gradle/libs.versions.toml)) is reused per `design.md` Decision 1.
- [ ] 1.2 Add `moko-resources = "X.Y.Z"` (exact patch) version + `[libraries]` entry `moko-resources = { group = "dev.icerock.moko", name = "resources", version.ref = "moko-resources" }` + `[libraries]` entry `moko-resources-compose = { group = "dev.icerock.moko", name = "resources-compose", version.ref = "moko-resources" }` + `[plugins]` entry `moko-resources = { id = "dev.icerock.mobile.multiplatform-resources", version.ref = "moko-resources" }` to [`gradle/libs.versions.toml`](../../../gradle/libs.versions.toml).
- [ ] 1.3 Record the `moko-resources` pin (and ONLY moko-resources — NOT material3) in [`docs/09-Versions.md`](../../../docs/09-Versions.md) § Version Decisions table with pin date `2026-05-28`, rationale (introduces Moko Resources for the new `:shared:resources` module per `design.md` Decision 1), and `2026-Q4` next-review.
- [ ] 1.4 Download Plus Jakarta Sans variable `.ttf` from [Google Fonts](https://fonts.google.com/specimen/Plus+Jakarta+Sans) (Download family → extract `PlusJakartaSans-VariableFont_wght.ttf`). Verify the `.ttf` is the variable-weight axis variant (200–800), not a static-weight bundle. Note the upstream filename for the destination commit message.
- [ ] 1.5 Download the OFL license file from the same Google Fonts download. Store it alongside the `.ttf` for OFL compliance.
- [ ] 1.6 Confirm both source SVG files are accessible at `/Users/aditrioka/Downloads/Asset Logo Anon Hive.svg` + `/Users/aditrioka/Downloads/Asset Logo Anon Hive Blue.svg`. If not, ask the user to re-supply before continuing.

## 2. Module scaffold — `:shared:resources`

- [ ] 2.1 Create `shared/resources/` directory tree: `shared/resources/build.gradle.kts`, `shared/resources/src/commonMain/kotlin/id/nearyou/resources/`, `shared/resources/src/commonMain/moko-resources/MR/base/`, `shared/resources/src/commonMain/moko-resources/images/`, `shared/resources/src/commonMain/moko-resources/fonts/`.
- [ ] 2.2 Write `shared/resources/build.gradle.kts`: apply `kotlin("multiplatform")` + `id("com.android.library")` + `id("dev.icerock.mobile.multiplatform-resources")` plugins; declare `androidTarget()`, `iosArm64()`, `iosSimulatorArm64()` targets (matching Mobile #1's `:mobile:app` iOS target set — no `iosX64()` per the consumer); declare `commonMain.dependencies { api(libs.moko.resources) }`; add `android { namespace = "id.nearyou.resources" ; compileSdk = <existing project compileSdk> ; defaultConfig { minSdk = <existing project minSdk> } }` (namespace MUST differ from `:mobile:app`'s `id.nearyou.app` to avoid R-class merge collision per `design.md` Decision 12 — confirm `id.nearyou.resources` is unique); add `multiplatformResources { resourcesPackage.set("id.nearyou.resources") ; resourcesClassName.set("MR") }`. Mirror conventions from existing modules like `:shared:distance` and `:mobile:app`.
- [ ] 2.3 Register the new module in [`settings.gradle.kts`](../../../settings.gradle.kts) via `include(":shared:resources")` placed **inside the existing `if (includeMobile.toBoolean()) { ... }` block** (alongside `include(":mobile:app")` at line ~41) per `design.md` Decision 12. Placing it outside the block would force the Cloud Run JDK-only Docker builder (which sets `includeMobile=false`) to bundle the Android SDK (~600MB) and would break that build.
- [ ] 2.4 Add a one-line description to [`dev/module-descriptions.txt`](../../../dev/module-descriptions.txt) for `:shared:resources` using the **pipe-separated** format documented in the file header: `:shared:resources | Brand color, typography, logo, and string resources via Moko Resources` (one entry per line, pipe `|` as field separator, no pipes inside the description).
- [ ] 2.5 Run `dev/scripts/sync-readme.sh --write` to regenerate the root [`README.md`](../../../README.md) § What's in this repo block between the `<!-- AUTOGEN:modules:start -->` / `<!-- AUTOGEN:modules:end -->` sentinels. Verify the new `:shared:resources` row is rendered.
- [ ] 2.6 Run `./gradlew :shared:resources:tasks` to verify the module is recognized by Gradle without errors.
- [ ] 2.7 Run `./gradlew :mobile:app:processDebugResources` to verify the Android `R` class merge between `:mobile:app` and `:shared:resources` does NOT produce a `R class duplication` error — namespace collision check per spec scenario "AGP merge step does not report R-class collision."

## 3. Brand color tokens — `NearYouColorScheme` + extensions

- [ ] 3.1 Create `shared/resources/src/commonMain/kotlin/id/nearyou/resources/theme/NearYouColorScheme.kt` with an object `NearYouColorScheme` exposing two `androidx.compose.material3.ColorScheme` instances: `light` and `dark`. Populate ALL 30+ Material 3 roles from the `design.md` Decision 3 table — exact hex values per the table (light column for `light`, dark column for `dark`). Use `Color(0xFF<hex>)` constructor (or `Color(0x<aarrggbb>)` for `scrim`). Note: `outline` light value is `#79747E` (M3 default, per Decision 9 — passes 4.05:1 WCAG against white surface), NOT the palette author's `#D9DDE5` (which goes on `outlineVariant`).
- [ ] 3.2 Verify `NearYouColorScheme.light.primary == Color(0xFF1E4FD6)` and `NearYouColorScheme.light.outline == Color(0xFF79747E)` (the M3-default contrast-passing value per `design.md` Decision 9, NOT the earlier `#9CA3AF` value which fails at 2.54:1) and `NearYouColorScheme.light.scrim == Color(0x8F0E1220)`.
- [ ] 3.3 Verify `NearYouColorScheme.light.secondary == Color(0xFFEEF0F4)` (the neutral surfaceVariant value per `design.md` Decision 2, NOT coral `Color(0xFFFF7A5C)`) and `NearYouColorScheme.light.tertiary == Color(0xFFE8EAEF)` (the neutral, NOT amber).
- [ ] 3.4 Create `shared/resources/src/commonMain/kotlin/id/nearyou/resources/theme/ColorSchemeExtensions.kt` with extension properties for the reserved-purpose accents + semantic status colors. Wire via `CompositionLocal`: declare `internal val LocalNearYouColors = staticCompositionLocalOf<NearYouColors> { error("NearYouTheme not applied") }` where `NearYouColors` is a data class holding all 17 extension values (locationPin + 3 companions × 2 accents + success/warning trios × 4 + link = locationPin/Container/onPin/onPinContainer + premiumBadge/Container/onBadge/onBadgeContainer + success/onSuccess/successContainer/onSuccessContainer + warning/onWarning/warningContainer/onWarningContainer + link). The `error("NearYouTheme not applied")` default is MANDATORY (NOT a fabricated default `NearYouColors` instance) per spec scenario "Accessing locationPin outside NearYouTheme throws." Declare `val ColorScheme.locationPin: Color @Composable @ReadOnlyComposable get() = LocalNearYouColors.current.locationPin` for each extension.
- [ ] 3.5 Create `shared/resources/src/commonMain/kotlin/id/nearyou/resources/theme/NearYouColors.kt` data class with all 17 extension values + `companion object { val light = NearYouColors(...) ; val dark = NearYouColors(...) }` populated from `design.md` Decision 3 extension-property table.
- [ ] 3.6 Create `shared/resources/src/commonTest/kotlin/id/nearyou/resources/theme/NearYouColorSchemeTest.kt` with full-table regression assertions: every value in `design.md` Decision 3 table (35+ roles × 2 schemes = 70+ assertions) verified `NearYouColorScheme.light.<role> == Color(0xFF<hex>)`. This is the regression test that catches accidental drift if a future change touches `NearYouColorScheme.kt`. Pure Kotlin assertions, no Compose UI test runner required.
- [ ] 3.7 Create `shared/resources/src/commonTest/kotlin/id/nearyou/resources/theme/ColorSchemeExtensionsTest.kt` with a negative test: using `runComposeUiTest` (or Compose UI test runner equivalent), invoke `setContent { Text("${MaterialTheme.colorScheme.locationPin}") }` WITHOUT a `NearYouTheme { ... }` wrapper and assert the composition throws `IllegalStateException` whose message contains `"NearYouTheme not applied"` — per spec scenario "Accessing locationPin outside NearYouTheme throws."

## 4. Brand typography — `NearYouTypography` + Plus Jakarta Sans

- [ ] 4.1 Copy the Plus Jakarta Sans variable `.ttf` from step 1.4 to `shared/resources/src/commonMain/moko-resources/fonts/plus_jakarta_sans.ttf`.
- [ ] 4.2 Copy the OFL license from step 1.5 to `shared/resources/src/commonMain/moko-resources/fonts/OFL.txt` for OFL compliance.
- [ ] 4.3 Create `shared/resources/src/commonMain/kotlin/id/nearyou/resources/theme/NearYouTypography.kt` exposing a `NearYouTypography: Typography` value. Build a `FontFamily` via `FontFamily(Font(MR.fonts.plus_jakarta_sans.regular, FontWeight.W200) [...] Font(MR.fonts.plus_jakarta_sans.regular, FontWeight.W800), FontFamily.SansSerif)` covering the variable axis weights via Moko Resources' `FontResource` accessor (or the platform-equivalent). The `FontFamily.SansSerif` entry MUST be LAST in the constructor argument list per `design.md` Decision 5 — placing it first would silently never use Plus Jakarta Sans even when the .ttf loads successfully.
- [ ] 4.4 Populate `NearYouTypography` with all 13 Material 3 type roles (`displayLarge` through `labelSmall`) using M3-standard sizes/weights per [Material 3 typography spec](https://m3.material.io/styles/typography/type-scale-tokens). Each `TextStyle.fontFamily` SHALL be the Plus Jakarta Sans `FontFamily` declared in 4.3.
- [ ] 4.5 Run `./gradlew :shared:resources:generateMRcommonMain` (the Moko Resources code-generation task) to verify `MR.fonts.plus_jakarta_sans` is generated correctly. Confirm the generated typesafe accessor compiles by running `./gradlew :shared:resources:compileKotlinAndroid`.
- [ ] 4.6 Verify SansSerif fallback positioning via grep + inspection: `grep -A 20 'FontFamily(' shared/resources/src/commonMain/kotlin/id/nearyou/resources/theme/NearYouTypography.kt | tail -3` — confirm `FontFamily.SansSerif` (or `Font(...)` backed by it) is the LAST entry before the closing `)`. Per spec scenario "FontFamily declaration places SansSerif as the LAST fallback."

## 5. Brand logo + foundational strings

- [ ] 5.1 Copy `/Users/aditrioka/Downloads/Asset Logo Anon Hive.svg` to `shared/resources/src/commonMain/moko-resources/images/logo_brand_dark.svg`. Modify the copied file with a **defensive double-replace** sed pass to handle either source hex value: `sed -i '' -e 's/#0B4FA8/#1E4FD6/g' -e 's/#014CAB/#1E4FD6/g' shared/resources/src/commonMain/moko-resources/images/logo_brand_dark.svg`. Verify with `grep -iE '#0B4FA8|#014CAB' shared/resources/src/commonMain/moko-resources/images/logo_brand_dark.svg` returns no matches. (The defensive double-replace defends against future logo revisions that may drift either hex into either file.)
- [ ] 5.2 Copy `/Users/aditrioka/Downloads/Asset Logo Anon Hive Blue.svg` to `shared/resources/src/commonMain/moko-resources/images/logo_brand_light.svg`. Modify the copied file with the same defensive double-replace: `sed -i '' -e 's/#0B4FA8/#1E4FD6/g' -e 's/#014CAB/#1E4FD6/g' shared/resources/src/commonMain/moko-resources/images/logo_brand_light.svg`. Verify with `grep -iE '#0B4FA8|#014CAB' shared/resources/src/commonMain/moko-resources/images/logo_brand_light.svg` returns no matches.
- [ ] 5.3 Verify the source SVG `viewBox` is exactly `108 108` (the existing `Asset Logo Anon Hive*.svg` files declare `viewBox="0 0 108 108"` per the user-supplied originals — confirm post-sed): `grep 'viewBox' shared/resources/src/commonMain/moko-resources/images/logo_brand_*.svg`. The Android adaptive icon foreground (task 6.2) + the iOS rasterization script (task 6.7) both depend on the 108-unit viewBox; a different viewBox produces wrong-size rasterized PNGs.
- [ ] 5.4 Visual diff sanity check: render both modified SVGs in a browser (e.g., open with `file://` URL) and visually confirm the hexagon glyph and color render correctly with no introduced artifacts.
- [ ] 5.5 Create `shared/resources/src/commonMain/moko-resources/MR/base/strings.xml` with the 10 foundational strings per the `shared-resources` spec capability:

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

- [ ] 5.6 Confirm the Moko `app_name` from 5.5 matches the existing platform-native Android `app_name` in [`mobile/app/src/androidMain/res/values/strings.xml`](../../../mobile/app/src/androidMain/res/values/strings.xml) (per spec scenario "Both app_name resources hold identical text"). The platform-native `app_name` is REQUIRED by `AndroidManifest.xml`'s `android:label="@string/app_name"` reference; this change does NOT remove it. Two parallel `app_name` resources (Moko for in-app Compose, platform-native for launcher label) are intentional and required.
- [ ] 5.7 Run `./gradlew :shared:resources:generateMRcommonMain` to verify `MR.strings.app_name`, `MR.images.logo_brand_light`, `MR.images.logo_brand_dark` accessors are generated correctly. Confirm `./gradlew :shared:resources:build` compiles cleanly.

## 6. App launcher icons (platform-native, **replace** Mobile #1 wizard defaults)

> Mobile #1 shipped JetBrains-wizard-default launcher assets — this section **replaces them in-place** with NearYouID-branded variants. Existing files: `mipmap-anydpi-v26/ic_launcher.xml` + `ic_launcher_round.xml`, `drawable/ic_launcher_background.xml` (wizard vector gradient), `drawable-v24/ic_launcher_foreground.xml` (wizard glyph), 10 raster fallback PNGs in `mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/`. iOS: existing `iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/Contents.json` uses the **modern iOS 14+ single-1024 universal idiom** with 3 appearance variants (default + dark + tinted) — this change preserves the `Contents.json` shape and replaces only the PNG bytes. NO regression to legacy 17-PNG multi-size pattern.

- [ ] 6.1 Create `mobile/app/src/androidMain/res/values/colors.xml` (or merge if existing) with `<color name="ic_launcher_background">#1E4FD6</color>`.
- [ ] 6.2 **Replace** `mobile/app/src/androidMain/res/drawable-v24/ic_launcher_foreground.xml` (Mobile #1 wizard default) with a vector drawable rendering the white hexagon glyph extracted from the modified `logo_brand_dark.svg` (strip the background `<rect>`, keep only the polyline groups). Use `<vector android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">` envelope; convert each SVG `<polyline points="x1,y1 x2,y2 ...">` to `<path android:pathData="M x1 y1 L x2 y2 ...">` plus the `<circle>` as a single `<path>` with `pathData="M x,y m -r,0 a r,r 0 1,1 r*2,0 a r,r 0 1,1 -r*2,0"` arc-based circle approximation. Use white fill `android:strokeColor="#FFFFFF" android:strokeWidth="3"` (matching the source SVG's `class="st1"` 3-unit stroke). **Concrete pathData strings** (deterministic extraction from the modified SVG):
  - Outer hex: `M43.8 50.2 L54 56.1 L64.2 50.2 L64.2 38.5 L54 32.6 L43.8 38.5 L43.8 45.5`
  - Inner hex left: `M54 56.1 L43.8 50.2 L33.7 56.1 L33.7 67.9 L43.8 73.7 L46.3 73.7`
  - Right segment: `M64.2 73.7 L74.3 67.9 L74.3 56.1 L68.3 52.6`
  - Bottom line: `M64.2 73.7 L56.7 73.7`
  - Dot: `M51.5 71.7 m -2,0 a 2,2 0 1,1 4,0 a 2,2 0 1,1 -4,0` (filled)
- [ ] 6.3 **Replace** `mobile/app/src/androidMain/res/drawable/ic_launcher_background.xml` (Mobile #1 wizard vector gradient) — either delete it (recommended; the adaptive XML now uses `@color/ic_launcher_background` from `values/colors.xml`) OR replace its content with a single `<vector>` declaring `android:tint="@color/ic_launcher_background"`. The cleanest path is to delete the file + verify no other reference exists.
- [ ] 6.4 Create NEW `mobile/app/src/androidMain/res/drawable/ic_launcher_monochrome.xml` — same path geometry as `ic_launcher_foreground.xml` from 6.2 but with `android:strokeColor="#000000"` so the Android 13+ themed-icon system can tint based on wallpaper (per `design.md` Decision 11).
- [ ] 6.5 **Replace** `mobile/app/src/androidMain/res/mipmap-anydpi-v26/ic_launcher.xml` (Mobile #1 wizard adaptive icon) with:

   ```xml
   <?xml version="1.0" encoding="utf-8"?>
   <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
       <background android:drawable="@color/ic_launcher_background" />
       <foreground android:drawable="@drawable/ic_launcher_foreground" />
       <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
   </adaptive-icon>
   ```

  Note: `android:drawable="@drawable/ic_launcher_foreground"` (NOT `drawable-v24/ic_launcher_foreground`) — AGP resolves to the `-v24` qualifier automatically for API 24+.
- [ ] 6.6 **Replace** `mobile/app/src/androidMain/res/mipmap-anydpi-v26/ic_launcher_round.xml` (Mobile #1 wizard) with identical content as `ic_launcher.xml` from 6.5 (the round mask is applied by the system).
- [ ] 6.7 Create the blue-on-white alternate (NEW files, no replace): `mobile/app/src/androidMain/res/drawable/ic_launcher_foreground_alt.xml` (extracted from modified `logo_brand_light.svg` with `android:strokeColor="#1E4FD6"` — same path geometry as 6.2 but blue stroke) + `mobile/app/src/androidMain/res/mipmap-anydpi-v26/ic_launcher_alt.xml` (background = `@android:color/white`, foreground = `@drawable/ic_launcher_foreground_alt`, monochrome same as 6.4). Wire via `<activity-alias android:name=".MainActivityAlt" android:icon="@mipmap/ic_launcher_alt" android:enabled="false">` in [`mobile/app/src/androidMain/AndroidManifest.xml`](../../../mobile/app/src/androidMain/AndroidManifest.xml) — `enabled="false"` so the alternate is dormant until a future user-selectable icon-theme feature flips it.
- [ ] 6.8 **Regenerate the 10 Android raster fallback PNGs** at the 5 density qualifiers (load-bearing under `min-sdk = 24` for Android 7.x devices that ignore the adaptive XML — see `design.md` Decision 8). Sizes per the [Android adaptive icon spec](https://developer.android.com/develop/ui/views/launch/icon_design_adaptive): mdpi 48×48, hdpi 72×72, xhdpi 96×96, xxhdpi 144×144, xxxhdpi 192×192. Use `rsvg-convert` against the modified `logo_brand_dark.svg`:

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
- [ ] 6.9 Create the iOS asset generation script `dev/scripts/generate-ios-app-icons.sh` for the **modern single-1024 universal idiom** (3 variants: default + dark + tinted). This is a substantial simplification from the legacy 17-PNG approach — per `design.md` Decision 8, the existing `iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/Contents.json` already declares the modern shape; this script only generates the 3 PNG bytes.

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
- [ ] 6.10 Run `dev/scripts/generate-ios-app-icons.sh` (with `librsvg` installed via `brew install librsvg` if not present). Verify `iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/` contains exactly 3 PNG files: `app-icon-1024.png`, `app-icon-1024-dark.png`, `app-icon-1024-tinted.png`. Verify ALL are 1024×1024 via `file iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/*.png | grep -c '1024 x 1024' = 3`.
- [ ] 6.11 Update `iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/Contents.json` to reference the 3 PNGs by filename (NOT to regress to the legacy 17-PNG shape). Final `Contents.json` SHALL preserve the modern single-1024 universal idiom — 3 entries with `idiom = "universal"`, `size = "1024x1024"`, `platform = "ios"`:

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
- [ ] 6.12 Validate the iOS Asset Catalog via `xcrun actool --print-asset-pack-manifest iosApp/iosApp/Assets.xcassets/ 2>&1 | grep -iE 'error|warning'` — expect zero error/warning output. If `actool` is unavailable in CI, defer to local macOS-only verification + capture screenshot of Xcode showing the asset catalog rendering correctly.
- [ ] 6.13 Commit all generated PNGs (3 iOS 1024 variants + 10 Android raster fallbacks regenerated from 6.8) + the updated `Contents.json` + updated Android adaptive XML files to the repo (so a fresh clone builds without re-running any script).

## 7. Mobile app integration — `NearYouTheme` + `HomeScreen` consumption + test updates

- [ ] 7.1 Add `implementation(projects.shared.resources)` (or the equivalent typesafe accessor for `:shared:resources`) to [`mobile/app/build.gradle.kts`](../../../mobile/app/build.gradle.kts) `commonMain.dependencies` block.
- [ ] 7.2 Modify [`mobile/app/src/commonMain/kotlin/id/nearyou/app/theme/NearYouTheme.kt`](../../../mobile/app/src/commonMain/kotlin/id/nearyou/app/theme/NearYouTheme.kt): replace `lightColorScheme()` / `darkColorScheme()` invocations with `NearYouColorScheme.light` / `NearYouColorScheme.dark` from `:shared:resources`. Pass `typography = NearYouTypography` to the `MaterialTheme` invocation. Wrap content in `CompositionLocalProvider(LocalNearYouColors provides if (darkTheme) NearYouColors.dark else NearYouColors.light) { ... }` so the `ColorScheme.locationPin` / `.premiumBadge` extension properties resolve correctly at every call site.
- [ ] 7.3 Modify [`mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/home/HomeScreen.kt`](../../../mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/home/HomeScreen.kt): replace the hardcoded `"NearYouID"` literal with `stringResource(MR.strings.home_placeholder_title)` (using Moko Resources' Compose accessor); replace the hardcoded `"v1.0"` literal with `stringResource(MR.strings.home_placeholder_version, "1.0")` (or whatever runtime version is sourced). Add an `Image(painter = painterResource(if (isSystemInDarkTheme()) MR.images.logo_brand_dark else MR.images.logo_brand_light), contentDescription = stringResource(MR.strings.app_name))` rendering the brand logo above the title.
- [ ] 7.4 **Update [`mobile/app/src/commonTest/kotlin/id/nearyou/app/HomeScreenTest.kt`](../../../mobile/app/src/commonTest/kotlin/id/nearyou/app/HomeScreenTest.kt)** to add a new `@Test fun homeScreen_versionFormatRendersAtRuntime()` that exercises the `MR.strings.home_placeholder_version` format substitution. Pure Kotlin assertion (no Compose UI runner needed): invoke the Moko Resources accessor with arg `"1.0"` and assert the result equals `"Versi 1.0"` exactly (NOT `"Versi %1$s"` literal). On Android: `MR.strings.home_placeholder_version.toString(resources, "1.0") == "Versi 1.0"`. On iOS commonTest: the equivalent Moko Resources accessor. This catches subtle Moko-vs-Android format-string differences per spec scenario "Runtime substitution test renders Versi 1.0."
- [ ] 7.5 Verify Mobile #1's "no `MaterialTheme {`" invariant remains intact: `grep -r 'MaterialTheme {' mobile/app/src/commonMain` returns only the single occurrence inside `NearYouTheme.kt`.

## 8. Build verification + lint passes + grep-based no-hardcoded-strings check

- [ ] 8.1 Run `./gradlew :shared:resources:build` — expect exit 0 (includes test task).
- [ ] 8.2 Run `./gradlew :shared:resources:test` — expect exit 0 (runs `NearYouColorSchemeTest` + `ColorSchemeExtensionsTest` from Section 3).
- [ ] 8.3 Run `./gradlew :mobile:app:assembleDebug` — expect exit 0; produces APK under `mobile/app/build/outputs/apk/debug/`.
- [ ] 8.4 Run `./gradlew :mobile:app:linkPodDebugFrameworkIosSimulatorArm64` — the canonical iOS framework link task per Mobile #1's `mobile-app-scaffold` spec § "Android and iOS targets build green" (NOT `:linkDebugFrameworkIosArm64` device-arm64 — the canonical smoke target is the simulator framework). Expect exit 0; produces `ComposeApp.framework` in the KMP build output directory.
- [ ] 8.5 Run `./gradlew :mobile:app:check` — runs all `:mobile:app` test tasks (commonTest + androidUnitTest, both targeted) including the updated `HomeScreenTest` from task 7.4. Expect exit 0.
- [ ] 8.6 **Grep-based "no hardcoded UI strings" verification** (replaces the previously-prescribed `:mobile:app:detekt` step — no such Detekt rule exists; see `design.md` Decision 10). Run:

   ```bash
   grep -rEn 'Text\(\s*"[^"]+"' mobile/app/src/commonMain/ mobile/app/src/androidMain/ mobile/app/src/iosMain/ | \
       grep -vE '(stringResource|MR\.strings|//.*hardcoded-string-allow:)' && \
       { echo "FAIL: hardcoded UI string literals in mobile sources"; exit 1; } || \
       { echo "OK: no hardcoded UI string literals found"; exit 0; }
   ```

   Expect exit 0 ("OK: no hardcoded UI string literals found"). If any match surfaces, audit + fix the call site to route through Moko Resources OR add a `// hardcoded-string-allow: <reason>` annotation (treat any annotation as a code-review smell — see Section 9 follow-up).
- [ ] 8.7 Run the full project lint + test gate: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` (per [`CLAUDE.md`](../../../CLAUDE.md) "Pre-push verification") — expect exit 0.
- [ ] 8.8 Run `./gradlew build` — full project build green; no module-resolution error; existing backend + lint test suites continue to pass.
- [ ] 8.9 Run `./gradlew :mobile:app:processDebugResources` — expect exit 0; verifies the `:shared:resources` ↔ `:mobile:app` R-class merge does NOT produce a duplication error (namespace collision check per spec scenario + design Decision 12 + task 2.7).
- [ ] 8.10 Cold-start the Android app on an emulator (API 33+ recommended for adaptive themed icon verification) and confirm: (a) the `HomeScreen` renders with `#1E4FD6` brand primary in the theme, (b) the in-app logo renders above the title, (c) the version text renders via the Moko Resources format string (verify it shows `"Versi 1.0"`, NOT `"Versi %1$s"` literal), (d) toggle system dark mode → confirm dark color scheme + dark logo variant render correctly, (e) the launcher icon on the home screen renders with white hexagon glyph on `#1E4FD6` blue background, (f) verify the legacy `mipmap-mdpi` raster fallback renders correctly on an Android 7.x emulator (API 24).
- [ ] 8.11 On macOS: open `iosApp/iosApp.xcodeproj` in Xcode, build + run on the iOS simulator, confirm equivalent visual rendering (HomeScreen + logo + theme + AppIcon). Verify iOS 18+ tinted icon variant renders correctly when the user enables "Tinted" appearance in iOS Settings → Wallpaper → Customize.
- [ ] 8.12 **MANDATORY visual confirmation screenshots** (gated PR attachment — do NOT mark this section complete without them): (a) Android home screen showing the launcher icon, (b) Android app cold-start showing `HomeScreen` (light mode), (c) Android dark mode showing `HomeScreen` with dark logo variant, (d) iOS simulator showing `HomeScreen`, (e) iOS Asset Catalog showing 3 PNG variants in Xcode. Attach all 5 to the PR as comments for visual reviewer reference. Per `design.md` Decision 8 + Decision 11, visual verification is the canonical check for the scaffold's brand surfaces — `actool` validation in task 6.12 is automated but cannot catch visual regressions.

## 9. Documentation maintenance + follow-up bookkeeping

- [ ] 9.1 Update [`openspec/project.md`](../../project.md) § Module Structure — flip the `:shared:resources` row from "SCAFFOLD NEXT" to "shipped" (with PR ref placeholder for the squash-merge commit, to be filled in during the archive phase).
- [ ] 9.2 Update [`docs/04-Architecture.md`](../../../docs/04-Architecture.md) § Dependency Isolation Pattern — same SCAFFOLD NEXT → shipped flip for the `:shared:resources` row.
- [ ] 9.3 Update [`docs/04-Architecture.md`](../../../docs/04-Architecture.md) § Mobile Status — update the "Mobile #2-5+" forward-looking note to reflect that Mobile #2 has shipped; subsequent feature changes are now Mobile #3 (auth), #4 (age gate), #5 (timeline).
- [ ] 9.4 Create `FOLLOW_UPS.md` at repository root (or append to existing, per the project's transient-file convention) with a new entry `mobile-hardcoded-strings-detekt-rule`. Body: the grep-based verification in this change's Section 8.6 covers the "no hardcoded UI strings" invariant in [`openspec/project.md`](../../project.md) § Coding Conventions; upgrade to a proper Detekt rule in `:lint:detekt-rules` modeled on the existing `RawFromPostsRule` / `BlockExclusionJoinRule` / `RedisHashTagRule` precedents. The rule should scan `:mobile:app` commonMain + androidMain + iosMain for `Text("...")` / `contentDescription = "..."` / similar Compose UI text-rendering surfaces where the string argument is a literal not flowing through `stringResource(MR.strings.X)` or `MR.strings.X.desc().localized()`. Format the follow-up entry per the FOLLOW_UPS.md format from PR [#18](https://github.com/aditrioka/nearyou-id/pull/18) (intro blurb + Format block if creating the file fresh; just the new entry if appending).
- [ ] 9.5 If creating `FOLLOW_UPS.md` fresh in 9.4, also re-add the Mobile #1 entries that remained open as of Mobile #1's archive: `mobile-negative-requirement-ci-grep` (Detekt rule for the negative-grep scenarios in `mobile-app-scaffold` spec — Mobile #1 deferred), `mobile-theme-light-dark-direct-test` (Compose UI test runner wiring for direct light/dark color-scheme assertions — partially addressed by this change's Section 3 tests, but the broader Compose UI test runner setup remains), `mobile-ios-ci-link-task` (deferred iOS-framework-link CI per Mobile #1 design Decision 6 — still applies). DO NOT re-add `infra-sentry-kmp-module-isation` (separately tracked + not blocked by Mobile #2).

## 10. PR title + body refresh per `/opsx:apply` step 7

> Note on Section 6 convention: this change has **zero runtime impact** (no backend changes, no database migrations, no Cloud Run deploy required, no staging smoke script). Per [`openspec/project.md`](../../project.md) § Archive timing — "For docs-only / refactor-only changes, skip [smoke] step 2-3 and go straight to archive (mark Section 6 N/A in the archive commit body)" — this tasks.md intentionally has no Section 6 staging-smoke block; the N/A goes in the eventual `/opsx:archive` commit body, not here. Mobile artifacts are not auto-deployed by `.github/workflows/deploy-staging.yml`; QA testers pull the staging flavor from Firebase App Distribution / TestFlight internal independently of this change's merge.

- [ ] 10.1 At first feat commit on this branch, retitle the PR via `gh pr edit <pr> --title 'feat(mobile): shared-resources-moko-bootstrap'` per [`openspec/project.md`](../../project.md) § "PR title and body MUST stay current at every phase boundary".
- [ ] 10.2 Refresh the PR body with the in-progress shape (drop the proposal-only language; add a section listing which task sections are complete + which remain).
- [ ] 10.3 At each subsequent section landing (post-Section 3, post-Section 5, post-Section 7), update the PR body's progress table.
- [ ] 10.4 At Section 8 completion (Build verification green + screenshots attached + grep zero), post the `/review` comment on the PR via `gh pr comment <pr> --body "/review"` per [`openspec/project.md`](../../project.md) § "Review channels" — this triggers qodo's review of the full implementation diff.
- [ ] 10.5 At `/opsx:archive` completion, refresh the PR body to a "merge-ready" shape (final test counts, capability deltas — new `shared-resources` capability + modified `mobile-app-scaffold` — and post-merge task list).
