## 1. Pre-work — version pins + asset preparation

- [ ] 1.1 Determine the latest stable `material3` version on the `1.3.x` line (check Maven Central via `gradle dependencyInsight` or `https://search.maven.org/`) and the latest stable `dev.icerock.moko:resources` plugin + library versions. Record both as candidate pins.
- [ ] 1.2 Add `material3 = "1.3.X"` (exact patch) entry to [`gradle/libs.versions.toml`](../../../gradle/libs.versions.toml) `[versions]` block. Add `[libraries]` entry `compose-material3 = { group = "org.jetbrains.compose.material3", name = "material3", version.ref = "material3" }` (or the equivalent for whatever artifact coordinate the project's Compose Multiplatform setup expects — confirm via existing `:mobile:app` build file).
- [ ] 1.3 Add `moko-resources = "X.Y.Z"` (exact patch) version + `[libraries]` entry `moko-resources = { group = "dev.icerock.moko", name = "resources", version.ref = "moko-resources" }` + `[libraries]` entry `moko-resources-compose = { group = "dev.icerock.moko", name = "resources-compose", version.ref = "moko-resources" }` + `[plugins]` entry `moko-resources = { id = "dev.icerock.mobile.multiplatform-resources", version.ref = "moko-resources" }` to `gradle/libs.versions.toml`.
- [ ] 1.4 Record both pins (`material3` + `moko-resources`) in [`docs/09-Versions.md`](../../../docs/09-Versions.md) § Version Decisions table with pin date `2026-05-28`, rationale citations from `design.md` Decision 1, and `2026-Q4` next-review.
- [ ] 1.5 Download Plus Jakarta Sans variable `.ttf` from [Google Fonts](https://fonts.google.com/specimen/Plus+Jakarta+Sans) (Download family → extract `PlusJakartaSans-VariableFont_wght.ttf`). Verify the `.ttf` is the variable-weight axis variant (200–800), not a static-weight bundle. Note the upstream filename for the destination commit message.
- [ ] 1.6 Download the OFL license file from the same Google Fonts download. Store it alongside the `.ttf` for OFL compliance.
- [ ] 1.7 Confirm both source SVG files are accessible at `/Users/aditrioka/Downloads/Asset Logo Anon Hive.svg` + `/Users/aditrioka/Downloads/Asset Logo Anon Hive Blue.svg`. If not, ask the user to re-supply before continuing.

## 2. Module scaffold — `:shared:resources`

- [ ] 2.1 Create `shared/resources/` directory tree: `shared/resources/build.gradle.kts`, `shared/resources/src/commonMain/kotlin/id/nearyou/resources/`, `shared/resources/src/commonMain/moko-resources/MR/base/`, `shared/resources/src/commonMain/moko-resources/images/`, `shared/resources/src/commonMain/moko-resources/fonts/`.
- [ ] 2.2 Write `shared/resources/build.gradle.kts`: apply `kotlin("multiplatform")` + `id("com.android.library")` + `id("dev.icerock.mobile.multiplatform-resources")` plugins; declare `androidTarget()`, `iosArm64()`, `iosSimulatorArm64()` targets (matching Mobile #1's `:mobile:app` iOS target set — no `iosX64()` per the consumer); declare `commonMain.dependencies { api(libs.moko.resources) }`; add `android { namespace = "id.nearyou.resources" ; compileSdk = <existing project compileSdk> ; defaultConfig { minSdk = <existing project minSdk> } }`; add `multiplatformResources { resourcesPackage.set("id.nearyou.resources") ; resourcesClassName.set("MR") }`. Mirror conventions from existing modules like `:shared:distance` and `:mobile:app`.
- [ ] 2.3 Register the new module in [`settings.gradle.kts`](../../../settings.gradle.kts) via `include(":shared:resources")` placed near the existing `:shared:*` entries (current file has `include(":shared:tmp")` then `include(":shared:distance")`; insert `include(":shared:resources")` adjacent — exact ordering not load-bearing, the existing entries aren't alphabetical).
- [ ] 2.4 Add a one-line description to [`dev/module-descriptions.txt`](../../../dev/module-descriptions.txt) for `:shared:resources` using the **pipe-separated** format documented in the file header: `:shared:resources | Brand color, typography, logo, and string resources via Moko Resources` (one entry per line, pipe `|` as field separator, no pipes inside the description).
- [ ] 2.5 Run `dev/scripts/sync-readme.sh --write` to regenerate the root [`README.md`](../../../README.md) § What's in this repo block between the `<!-- AUTOGEN:modules:start -->` / `<!-- AUTOGEN:modules:end -->` sentinels. Verify the new `:shared:resources` row is rendered.
- [ ] 2.6 Run `./gradlew :shared:resources:tasks` to verify the module is recognized by Gradle without errors.

## 3. Brand color tokens — `NearYouColorScheme` + extensions

- [ ] 3.1 Create `shared/resources/src/commonMain/kotlin/id/nearyou/resources/theme/NearYouColorScheme.kt` with an object `NearYouColorScheme` exposing two `androidx.compose.material3.ColorScheme` instances: `light` and `dark`. Populate ALL 30+ Material 3 roles from the `design.md` Decision 3 table — exact hex values per the table (light column for `light`, dark column for `dark`). Use `Color(0xFF<hex>)` constructor (or `Color(0x<aarrggbb>)` for `scrim`).
- [ ] 3.2 Verify `NearYouColorScheme.light.primary == Color(0xFF1E4FD6)` and `NearYouColorScheme.light.outline == Color(0xFF9CA3AF)` (the contrast-adjusted value per `design.md` Decision 9) and `NearYouColorScheme.light.scrim == Color(0x8F0E1220)`.
- [ ] 3.3 Verify `NearYouColorScheme.light.secondary == Color(0xFFEEF0F4)` (the neutral surfaceVariant value per `design.md` Decision 2, NOT coral `Color(0xFFFF7A5C)`) and `NearYouColorScheme.light.tertiary == Color(0xFFE8EAEF)` (the neutral, NOT amber).
- [ ] 3.4 Create `shared/resources/src/commonMain/kotlin/id/nearyou/resources/theme/ColorSchemeExtensions.kt` with extension properties for the reserved-purpose accents + semantic status colors. Wire via `CompositionLocal`: declare `internal val LocalNearYouColors = staticCompositionLocalOf<NearYouColors> { error("NearYouTheme not applied") }` where `NearYouColors` is a data class holding all 17 extension values (locationPin + 3 companions × 2 accents + success/warning trios × 4 + link = locationPin/Container/onPin/onPinContainer + premiumBadge/Container/onBadge/onBadgeContainer + success/onSuccess/successContainer/onSuccessContainer + warning/onWarning/warningContainer/onWarningContainer + link). Declare `val ColorScheme.locationPin: Color @Composable @ReadOnlyComposable get() = LocalNearYouColors.current.locationPin` for each.
- [ ] 3.5 Create `shared/resources/src/commonMain/kotlin/id/nearyou/resources/theme/NearYouColors.kt` data class with all 17 extension values + `companion object { val light = NearYouColors(...) ; val dark = NearYouColors(...) }` populated from `design.md` Decision 3 extension-property table.

## 4. Brand typography — `NearYouTypography` + Plus Jakarta Sans

- [ ] 4.1 Copy the Plus Jakarta Sans variable `.ttf` from step 1.5 to `shared/resources/src/commonMain/moko-resources/fonts/plus_jakarta_sans.ttf`.
- [ ] 4.2 Copy the OFL license from step 1.6 to `shared/resources/src/commonMain/moko-resources/fonts/OFL.txt` for OFL compliance.
- [ ] 4.3 Create `shared/resources/src/commonMain/kotlin/id/nearyou/resources/theme/NearYouTypography.kt` exposing a `NearYouTypography: Typography` value. Build a `FontFamily` via `FontFamily(Font(MR.fonts.plus_jakarta_sans.regular, FontWeight.W200) [...] Font(MR.fonts.plus_jakarta_sans.regular, FontWeight.W800))` covering the variable axis weights via Moko Resources' `FontResource` accessor (or the platform-equivalent). Add `FontFamily.SansSerif` as a fallback entry per `design.md` Decision 5.
- [ ] 4.4 Populate `NearYouTypography` with all 13 Material 3 type roles (`displayLarge` through `labelSmall`) using M3-standard sizes/weights per [Material 3 typography spec](https://m3.material.io/styles/typography/type-scale-tokens). Each `TextStyle.fontFamily` SHALL be the Plus Jakarta Sans `FontFamily` declared in 4.3.
- [ ] 4.5 Run `./gradlew :shared:resources:generateMRcommonMain` (the Moko Resources code-generation task) to verify `MR.fonts.plus_jakarta_sans` is generated correctly. Confirm the generated typesafe accessor compiles by running `./gradlew :shared:resources:compileKotlinAndroid`.

## 5. Brand logo + foundational strings

- [ ] 5.1 Copy `/Users/aditrioka/Downloads/Asset Logo Anon Hive.svg` to `shared/resources/src/commonMain/moko-resources/images/logo_brand_dark.svg`. Modify the copied file: replace `#0B4FA8` with `#1E4FD6` (use sed: `sed -i '' 's/#0B4FA8/#1E4FD6/g' shared/resources/src/commonMain/moko-resources/images/logo_brand_dark.svg`). Verify with `grep -i '#0B4FA8\|#014CAB' shared/resources/src/commonMain/moko-resources/images/logo_brand_dark.svg` returns no matches.
- [ ] 5.2 Copy `/Users/aditrioka/Downloads/Asset Logo Anon Hive Blue.svg` to `shared/resources/src/commonMain/moko-resources/images/logo_brand_light.svg`. Modify the copied file: replace `#014CAB` with `#1E4FD6` (use sed: `sed -i '' 's/#014CAB/#1E4FD6/g' shared/resources/src/commonMain/moko-resources/images/logo_brand_light.svg`). Verify with `grep -i '#0B4FA8\|#014CAB' shared/resources/src/commonMain/moko-resources/images/logo_brand_light.svg` returns no matches.
- [ ] 5.3 Visual diff sanity check: render both modified SVGs in a browser (e.g., open with `file://` URL) and visually confirm the hexagon glyph and color render correctly with no introduced artifacts.
- [ ] 5.4 Create `shared/resources/src/commonMain/moko-resources/MR/base/strings.xml` with the 10 foundational strings per the `shared-resources` spec capability:

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
- [ ] 5.5 Run `./gradlew :shared:resources:generateMRcommonMain` to verify `MR.strings.app_name`, `MR.images.logo_brand_light`, `MR.images.logo_brand_dark` accessors are generated correctly. Confirm `./gradlew :shared:resources:build` compiles cleanly.

## 6. App launcher icons (platform-native, NOT inside Moko Resources)

- [ ] 6.1 Create `mobile/app/src/androidMain/res/values/colors.xml` (or merge if existing) with `<color name="ic_launcher_background">#1E4FD6</color>`.
- [ ] 6.2 Create `mobile/app/src/androidMain/res/drawable/ic_launcher_foreground.xml` — a vector drawable rendering the white hexagon glyph extracted from the modified `logo_brand_dark.svg` (strip the background `<rect>`, keep only the polyline groups). Use `<vector android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">` envelope; convert each SVG `<polyline points="x1,y1 x2,y2 ...">` to `<path android:pathData="M x1 y1 L x2 y2 ...">` plus the `<circle>` as a single `<path>` with `pathData="M x,y m -r,0 a r,r 0 1,1 r*2,0 a r,r 0 1,1 -r*2,0"` arc-based circle approximation. Use white fill `android:strokeColor="#FFFFFF" android:strokeWidth="3"` (matching the source SVG's `class="st1"` 3-unit stroke).
- [ ] 6.3 Create `mobile/app/src/androidMain/res/drawable/ic_launcher_monochrome.xml` — same as `ic_launcher_foreground.xml` but with `android:strokeColor="#000000"` so the Android 13+ themed-icon system can tint based on wallpaper.
- [ ] 6.4 Create `mobile/app/src/androidMain/res/mipmap-anydpi-v26/ic_launcher.xml`:

   ```xml
   <?xml version="1.0" encoding="utf-8"?>
   <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
       <background android:drawable="@color/ic_launcher_background" />
       <foreground android:drawable="@drawable/ic_launcher_foreground" />
       <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
   </adaptive-icon>
   ```
- [ ] 6.5 Create `mobile/app/src/androidMain/res/mipmap-anydpi-v26/ic_launcher_round.xml` with identical content as `ic_launcher.xml` (the round mask is applied by the system).
- [ ] 6.6 Create the blue-on-white alternate: `mobile/app/src/androidMain/res/drawable/ic_launcher_foreground_alt.xml` (extracted from modified `logo_brand_light.svg` with `android:strokeColor="#1E4FD6"`) + `mobile/app/src/androidMain/res/mipmap-anydpi-v26/ic_launcher_alt.xml` (background = `@android:color/white`, foreground = `@drawable/ic_launcher_foreground_alt`, monochrome same as default). Wire via `<activity-alias android:name=".MainActivityAlt" android:icon="@mipmap/ic_launcher_alt" android:enabled="false">` in [`mobile/app/src/androidMain/AndroidManifest.xml`](../../../mobile/app/src/androidMain/AndroidManifest.xml) — `enabled="false"` so the alternate is dormant until a future user-selectable icon-theme feature flips it.
- [ ] 6.7 Create the iOS asset generation script `dev/scripts/generate-ios-app-icons.sh`:

   ```bash
   #!/usr/bin/env bash
   set -euo pipefail
   SRC="${1:-shared/resources/src/commonMain/moko-resources/images/logo_brand_dark.svg}"
   DEST="${2:-iosApp/iosApp/Assets.xcassets/AppIcon.appiconset}"
   CONVERTER=""
   if command -v rsvg-convert &>/dev/null; then CONVERTER="rsvg-convert"
   elif command -v pdftocairo &>/dev/null; then CONVERTER="pdftocairo"
   else echo "ERROR: install librsvg (brew install librsvg) or poppler (brew install poppler)" >&2; exit 1
   fi
   mkdir -p "$DEST"
   for spec in "20:2:Icon-App-20x20@2x.png" "20:3:Icon-App-20x20@3x.png" "29:2:Icon-App-29x29@2x.png" "29:3:Icon-App-29x29@3x.png" "40:2:Icon-App-40x40@2x.png" "40:3:Icon-App-40x40@3x.png" "60:2:Icon-App-60x60@2x.png" "60:3:Icon-App-60x60@3x.png" "76:1:Icon-App-76x76@1x.png" "76:2:Icon-App-76x76@2x.png" "83.5:2:Icon-App-83.5x83.5@2x.png" "1024:1:Icon-App-1024x1024@1x.png"; do
       IFS=":" read -r pt scale name <<< "$spec"
       px=$(awk "BEGIN { printf \"%d\", $pt * $scale }")
       if [ "$CONVERTER" = "rsvg-convert" ]; then rsvg-convert -w "$px" -h "$px" "$SRC" -o "$DEST/$name"
       else pdftocairo -png -r $((px * 72 / 108)) "$SRC" "$DEST/${name%.png}"; mv "$DEST/${name%.png}-1.png" "$DEST/$name" 2>/dev/null || true
       fi
   done
   echo "Generated $(ls -1 "$DEST"/*.png | wc -l) PNGs in $DEST"
   ```

   Make it executable: `chmod +x dev/scripts/generate-ios-app-icons.sh`.
- [ ] 6.8 Run `dev/scripts/generate-ios-app-icons.sh` (with `librsvg` installed via `brew install librsvg` if not present). Verify `iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/` contains 12 PNG files (17 if all `Contents.json` sizes are populated — script generates 12 distinct sizes; the iOS Asset Catalog's `Contents.json` then references them across the 17 idiom slots).
- [ ] 6.9 Update `iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/Contents.json` to reference the generated PNGs with correct `idiom` / `scale` / `size` entries per the Apple [Asset Catalog spec](https://developer.apple.com/documentation/xcode/configuring-your-app-icon).
- [ ] 6.10 Commit all generated PNGs + the updated `Contents.json` to the repo (so a fresh clone builds without re-running the script).

## 7. Mobile app integration — `NearYouTheme` + `HomeScreen` consumption

- [ ] 7.1 Add `implementation(projects.shared.resources)` (or the equivalent typesafe accessor for `:shared:resources`) to [`mobile/app/build.gradle.kts`](../../../mobile/app/build.gradle.kts) `commonMain.dependencies` block.
- [ ] 7.2 Modify [`mobile/app/src/commonMain/kotlin/id/nearyou/app/theme/NearYouTheme.kt`](../../../mobile/app/src/commonMain/kotlin/id/nearyou/app/theme/NearYouTheme.kt): replace `lightColorScheme()` / `darkColorScheme()` invocations with `NearYouColorScheme.light` / `NearYouColorScheme.dark` from `:shared:resources`. Pass `typography = NearYouTypography` to the `MaterialTheme` invocation. Wrap content in `CompositionLocalProvider(LocalNearYouColors provides if (darkTheme) NearYouColors.dark else NearYouColors.light) { ... }` so the `ColorScheme.locationPin` / `.premiumBadge` extension properties resolve correctly at every call site.
- [ ] 7.3 Modify [`mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/home/HomeScreen.kt`](../../../mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/home/HomeScreen.kt): replace the hardcoded `"NearYouID"` literal with `stringResource(MR.strings.home_placeholder_title)` (using Moko Resources' Compose accessor); replace the hardcoded `"v1.0"` literal with `stringResource(MR.strings.home_placeholder_version, "1.0")` (or whatever runtime version is sourced). Add an `Image(painter = painterResource(if (isSystemInDarkTheme()) MR.images.logo_brand_dark else MR.images.logo_brand_light), contentDescription = stringResource(MR.strings.app_name))` rendering the brand logo above the title.
- [ ] 7.4 Verify Mobile #1's "no `MaterialTheme {`" invariant remains intact: `grep -r 'MaterialTheme {' mobile/app/src/commonMain` returns only the single occurrence inside `NearYouTheme.kt`.

## 8. Build verification + lint passes

- [ ] 8.1 Run `./gradlew :shared:resources:build` — expect exit 0.
- [ ] 8.2 Run `./gradlew :mobile:app:assembleDebug` — expect exit 0; produces APK under `mobile/app/build/outputs/apk/debug/`.
- [ ] 8.3 Run `./gradlew :mobile:app:linkDebugFrameworkIosArm64` (or whichever canonical iOS link task the project conventionally smokes per Mobile #1's `mobile-app-scaffold` spec § "Android and iOS targets build green") — expect exit 0; produces the iOS framework artifact.
- [ ] 8.4 Run `./gradlew :mobile:app:detekt` — expect exit 0 (no violation of the no-hardcoded-UI-strings rule per [`openspec/project.md`](../../project.md) § Coding Conventions).
- [ ] 8.5 Run the full project lint + test gate: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` (per [`CLAUDE.md`](../../../CLAUDE.md) "Pre-push verification") — expect exit 0.
- [ ] 8.6 Run `./gradlew build` — full project build green; no module-resolution error; existing backend + lint test suites continue to pass.
- [ ] 8.7 Cold-start the Android app on an emulator (API 33+ recommended for adaptive themed icon verification) and confirm: (a) the `HomeScreen` renders with `#1E4FD6` brand primary in the theme, (b) the in-app logo renders above the title, (c) the version text renders via the Moko Resources format string, (d) toggle system dark mode → confirm dark color scheme + dark logo variant render correctly, (e) the launcher icon on the home screen renders with white hexagon glyph on `#1E4FD6` blue background.
- [ ] 8.8 On macOS: open `iosApp/iosApp.xcodeproj` in Xcode, build + run on the iOS simulator, confirm equivalent visual rendering (HomeScreen + logo + theme + AppIcon).
- [ ] 8.9 Visual confirmation: take a screenshot of (a) Android home screen showing the launcher icon, (b) Android app cold-start showing `HomeScreen`, (c) Android dark mode showing `HomeScreen`. Attach to the PR for visual reviewer reference.

## 9. Documentation maintenance

- [ ] 9.1 Update [`openspec/project.md`](../../project.md) § Module Structure — flip the `:shared:resources` row from "SCAFFOLD NEXT" to "shipped" (with PR ref placeholder for the squash-merge commit, to be filled in during the archive phase).
- [ ] 9.2 Update [`docs/04-Architecture.md`](../../../docs/04-Architecture.md) § Dependency Isolation Pattern — same SCAFFOLD NEXT → shipped flip for the `:shared:resources` row.
- [ ] 9.3 Update [`docs/04-Architecture.md`](../../../docs/04-Architecture.md) § Mobile Status — update the "Mobile #2-5+" forward-looking note to reflect that Mobile #2 has shipped; subsequent feature changes are now Mobile #3 (auth), #4 (age gate), #5 (timeline).

## 10. PR title + body refresh per `/opsx:apply` step 7

> Note on Section 6 convention: this change has **zero runtime impact** (no backend changes, no database migrations, no Cloud Run deploy required, no staging smoke script). Per [`openspec/project.md`](../../project.md) § Archive timing — "For docs-only / refactor-only changes, skip [smoke] step 2-3 and go straight to archive (mark Section 6 N/A in the archive commit body)" — this tasks.md intentionally has no Section 6 staging-smoke block; the N/A goes in the eventual `/opsx:archive` commit body, not here. Mobile artifacts are not auto-deployed by `.github/workflows/deploy-staging.yml`; QA testers pull the staging flavor from Firebase App Distribution / TestFlight internal independently of this change's merge.

- [ ] 10.1 At first feat commit on this branch, retitle the PR via `gh pr edit <pr> --title 'feat(mobile): shared-resources-moko-bootstrap'` per [`openspec/project.md`](../../project.md) § "PR title and body MUST stay current at every phase boundary".
- [ ] 10.2 Refresh the PR body with the in-progress shape (drop the proposal-only language; add a section listing which task sections are complete + which remain).
- [ ] 10.3 At each subsequent section landing (post-Section 3, post-Section 5, post-Section 7), update the PR body's progress table.
- [ ] 10.4 At Section 9 completion (Build verification green), post the `/review` comment on the PR via `gh pr comment <pr> --body "/review"` per [`openspec/project.md`](../../project.md) § "Review channels" — this triggers qodo's review of the full implementation diff.
- [ ] 10.5 At `/opsx:archive` completion, refresh the PR body to a "merge-ready" shape (final test counts, capability deltas — new `shared-resources` capability + modified `mobile-app-scaffold` — and post-merge task list).
