## 1. Pre-implementation library re-check confirmation (per PR #118 rule)

- [ ] 1.1 Verify the pre-impl library re-check WAS performed for this change. Evidence: the 2026-05-28 conversation that authored this proposal (transcript surfaced CMP 1.10 production-stable + CMP 1.11 maturity + JetBrains directional commitment + already-pinned-but-unused `compose-components-resources` coordinate). Outcome: confirmed CMP Resources is the right substrate; no further alternative surfaced.
- [ ] 1.2 Drop a one-liner in the first apply commit body referencing this re-check: e.g., `Pre-impl re-check 2026-05-28: confirmed compose-components-resources is the right substrate per PR #118 rule; no ecosystem shift since proposal authorship.`
- [ ] 1.3 Pre-flight verification of file-path references still valid (anchor-text grep, not line-number-pinned): grep canonical-doc files for "Moko Resources" / "MR.strings" / "MR.images" / "MR.fonts" / "moko-resources" to confirm the 5 doc-surface anchors (per `design.md` Risks table line "Doc-wording updates ... accidentally drift") have not shifted on main during proposal-review. If anchors moved, update tasks.md doc paths before proceeding to Section 7.

## 2. `gradle/libs.versions.toml` cleanup

- [ ] 2.1 Remove from `[versions]`: `moko-resources = "0.26.4"` (line 35 per Mobile #2's shipped state).
- [ ] 2.2 Remove from `[libraries]`: the 6-line preamble comment block (lines 134-138, "Moko Resources: KMP resource codegen ...") AND the 2 library entries `moko-resources` + `moko-resources-compose` (lines 139-140).
- [ ] 2.3 Remove from `[plugins]`: `mokoResources = { id = "dev.icerock.mobile.multiplatform-resources", version.ref = "moko-resources" }` (line 166).
- [ ] 2.4 Verify the `compose-components-resources` library entry (currently at line 61 — `compose-components-resources = { module = "org.jetbrains.compose.components:components-resources", version.ref = "composeMultiplatform" }`) is preserved unchanged.
- [ ] 2.5 Verify the `composeMultiplatform = "1.10.3"` version variable (line 14) is preserved unchanged — this is what supplies the version for the active CMP Resources library.
- [ ] 2.6 Verify no other module reference to `libs.moko.*` remains: `grep -rn 'libs\.moko' --include='*.kts' .` should return zero matches after Sections 3 + 6 are complete (this task re-runs the check as a final guard).

## 3. `:shared:resources` build config swap

- [ ] 3.1 Edit [`shared/resources/build.gradle.kts`](../../../shared/resources/build.gradle.kts) `plugins { ... }` block: remove `alias(libs.plugins.mokoResources)` (line 8). Keep all other plugin aliases (`nearyou.kotlin.multiplatform`, `androidLibrary`, `composeMultiplatform`, `composeCompiler`).
- [ ] 3.2 Edit the iOS framework block (lines 18-27): remove the `export(libs.moko.resources)` line (line 25) AND the `isStatic = true` line (line 24 — see `design.md` Decision 6 conservative-restore note if iOS link fails). The block becomes `iosTarget.binaries.framework { baseName = "SharedResources" }` (3 lines instead of 5).
- [ ] 3.3 Edit `commonMain.dependencies { ... }` block (lines 30-36): remove `api(libs.moko.resources)` (line 31) AND `api(libs.moko.resources.compose)` (line 32). Add `implementation(libs.compose.components.resources)` to wire the CMP Resources library.
- [ ] 3.4 Remove the entire `multiplatformResources { ... }` block at lines 79-82 (Moko-specific DSL block declaring `resourcesPackage` + `resourcesClassName`).
- [ ] 3.5 Remove the `testOptions { ... }` `it.exclude("**/ColorSchemeExtensionsTest*")` workaround block at lines 72-77 IF the CMP Resources path no longer requires the Android-JVM-test exclusion (the comment cites the Moko-specific `Build.FINGERPRINT` null issue under Robolectric absence; CMP Resources may not have the same constraint). Verify by running `./gradlew :shared:resources:testDebugUnitTest` after the swap — if green, drop the exclusion; if still failing, keep with an updated comment explaining the new failure mode. The canonical iOS test path stays via `iosSimulatorArm64Test`.
- [ ] 3.6 Run `./gradlew :shared:resources:tasks` — BUILD SUCCESSFUL expected; module recognized by Gradle without the Moko plugin.

## 4. Resource layout migration (Moko → CMP Resources convention)

- [ ] 4.1 Create the CMP Resources directory tree: `shared/resources/src/commonMain/composeResources/{values,drawable,font}/`.
- [ ] 4.2 `git mv shared/resources/src/commonMain/moko-resources/MR/base/strings.xml shared/resources/src/commonMain/composeResources/values/strings.xml` — preserve git blame history.
- [ ] 4.3 `git mv shared/resources/src/commonMain/moko-resources/images/logo_brand_light.svg shared/resources/src/commonMain/composeResources/drawable/logo_brand_light.svg`.
- [ ] 4.4 `git mv shared/resources/src/commonMain/moko-resources/images/logo_brand_dark.svg shared/resources/src/commonMain/composeResources/drawable/logo_brand_dark.svg`.
- [ ] 4.5 `git mv shared/resources/src/commonMain/moko-resources/fonts/plus_jakarta_sans.ttf shared/resources/src/commonMain/composeResources/font/plus_jakarta_sans.ttf`.
- [ ] 4.6 `git mv shared/resources/src/commonMain/moko-resources/fonts/OFL.txt shared/resources/src/commonMain/composeResources/font/OFL.txt` (keep license with the font; CMP Resources doesn't care about the file living next to the .ttf, but OFL terms require attribution proximity).
- [ ] 4.7 Verify the now-empty `shared/resources/src/commonMain/moko-resources/` directory tree is removed: `find shared/resources/src/commonMain/moko-resources -type f` should return zero results. Then `rm -rf shared/resources/src/commonMain/moko-resources/` to clean up empty directories.
- [ ] 4.8 Verify content preservation post-move: `git status` should show pure file renames (R100) not modifications (M); `git diff --stat HEAD` for the moved files should show zero added/removed lines.

## 5. Update `NearYouTypography.kt` to CMP Resources font accessor

- [ ] 5.1 Run `./gradlew :shared:resources:generateComposeResClass` (or whichever CMP Resources codegen task the plugin exposes — verify against `./gradlew :shared:resources:tasks --all | grep -i resource`). Capture the generated `Res` class import path — likely `id.nearyou.shared.resources.generated.resources.Res` or equivalent (resolves Open Question #1 in `design.md`).
- [ ] 5.2 Edit [`shared/resources/src/commonMain/kotlin/id/nearyou/resources/theme/NearYouTypography.kt`](../../../shared/resources/src/commonMain/kotlin/id/nearyou/resources/theme/NearYouTypography.kt): replace the Moko import (`import id.nearyou.resources.MR` or whichever shape Mobile #2 used) with the CMP Resources `Res` import resolved in 5.1. Update the font-loading call from `MR.fonts.plus_jakarta_sans.asFont()` to the CMP equivalent — `Font(Res.font.plus_jakarta_sans)` is the canonical Compose accessor for a single bundled font file.
- [ ] 5.3 Preserve the defensive guard: the existing `if (brandFont == null) return Typography()` early-return semantically still applies if the CMP Resources `Font(...)` call can return a null-equivalent (verify at apply time — if CMP's API doesn't return nullable, wrap in try/catch around `Font(...)` returning vanilla `Typography()` on exception; either way, document the chosen idiom inline).
- [ ] 5.4 Verify `./gradlew :shared:resources:compileKotlinIosSimulatorArm64` BUILD SUCCESSFUL — the new import resolves cleanly on iOS.
- [ ] 5.5 Verify `./gradlew :shared:resources:test` BUILD SUCCESSFUL — `NearYouColorSchemeTest` + `ColorSchemeExtensionsTest` continue to pass with no edits beyond the typography file's import path.

## 6. `:mobile:app` consumer-side cleanup

- [ ] 6.1 Edit [`mobile/app/build.gradle.kts`](../../../mobile/app/build.gradle.kts) `plugins { ... }` block: remove `alias(libs.plugins.mokoResources)` (line 13) AND the 7-line comment block above it (lines 8-14 explaining the dual-plugin workaround — now obsolete).
- [ ] 6.2 Remove from same file the `multiplatformResources { resourcesPackage.set("id.nearyou.app.frameworkresources") }` block at lines 67-70 (the empty-MR-class workaround for the iOS framework copy task — no longer needed with CMP Resources native iOS framework integration per `design.md` Decision 4).
- [ ] 6.3 Edit `commonMain.dependencies { ... }` block (lines 38-54): remove `implementation(libs.moko.resources)` (line 52) AND `implementation(libs.moko.resources.compose)` (line 53). The `implementation(projects.shared.resources)` line (line 51) is preserved unchanged — `:shared:resources` is still the resource source.
- [ ] 6.4 Edit [`mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/home/HomeScreen.kt`](../../../mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/home/HomeScreen.kt): swap import from Moko `MR` to CMP Resources `Res` (use the import path resolved in 5.1). Update the 5 call sites:
  - Line 36: `MR.images.logo_brand_dark` → `Res.drawable.logo_brand_dark`.
  - Line 38: `MR.images.logo_brand_light` → `Res.drawable.logo_brand_light`.
  - Line 42: `stringResource(MR.strings.app_name)` → `stringResource(Res.string.app_name)`.
  - Line 46: `stringResource(MR.strings.home_placeholder_title)` → `stringResource(Res.string.home_placeholder_title)`.
  - Line 51: `stringResource(MR.strings.home_placeholder_version, "1.0")` → `stringResource(Res.string.home_placeholder_version, "1.0")` — verify the CMP Resources `stringResource` overload accepts positional format args identically to Moko's.
- [ ] 6.5 Edit [`mobile/app/src/commonTest/kotlin/id/nearyou/app/HomeScreenTest.kt:29`](../../../mobile/app/src/commonTest/kotlin/id/nearyou/app/HomeScreenTest.kt:29): update the comment line referencing `MR.strings.home_placeholder_version` to `Res.string.home_placeholder_version` so the test code's documentation stays accurate.
- [ ] 6.6 Verify zero `MR.*` references remain in mobile sources: `grep -rEn '\bMR\.(strings|images|fonts)\.' mobile/app/src/` should return zero matches.
- [ ] 6.7 Verify `./gradlew :mobile:app:assembleDebug` BUILD SUCCESSFUL — Android APK produced with bundled CMP Resources content.
- [ ] 6.8 Verify `./gradlew :mobile:app:linkDebugFrameworkIosSimulatorArm64` BUILD SUCCESSFUL — iOS framework produced. If the link task fails due to the dropped `isStatic = true` (per `design.md` Decision 6 Risks table), restore that single line in `shared/resources/build.gradle.kts` and document the conservative-restore in tasks.md.
- [ ] 6.9 Inspect the produced iOS `.framework` bundle (under `mobile/app/build/bin/iosSimulatorArm64/.../ComposeApp.framework/`): confirm `Resources/` (or whichever CMP Resources subdirectory contains bundled assets) contains `logo_brand_light.svg` (or its compiled form), `logo_brand_dark.svg`, `plus_jakarta_sans.ttf`, and the `strings.xml` (or compiled equivalent).

## 7. Canonical-doc wording updates

- [ ] 7.1 Edit [`CLAUDE.md:51`](../../../CLAUDE.md:51): change "Mobile strings via Moko Resources only" → "Mobile strings via Compose Multiplatform Resources only".
- [ ] 7.2 Edit [`openspec/project.md:150`](../../project.md:150): same wording change as 7.1.
- [ ] 7.3 Edit [`openspec/project.md:68`](../../project.md:68): `:shared:resources` row in Module Structure — update description from "Moko Resources `MR` codegen" / "Moko" references → "Compose Multiplatform Resources `Res` codegen" wording; reference [PR #118](https://github.com/aditrioka/nearyou-id/pull/118) (this change) as the swap source.
- [ ] 7.4 Edit [`openspec/project.md:102`](../../project.md:102): Mobile #2 menu row — update description to reflect post-swap state (e.g., note that Mobile #2 shipped Moko initially + was swapped to CMP Resources via this interstitial Mobile #2.5 change).
- [ ] 7.5 Edit [`docs/04-Architecture.md:37`](../../../docs/04-Architecture.md:37): table row "Localization | Moko Resources or Compose MP Resources" → "Localization | Compose Multiplatform Resources" (drop the "or").
- [ ] 7.6 Edit [`docs/04-Architecture.md:132`](../../../docs/04-Architecture.md:132): `:shared:resources` description — update "Moko Resources `MR` accessors" → "Compose Multiplatform Resources `Res` accessors"; keep the PR #116 reference but add a sibling reference to this change's PR (TBD at apply time) explaining the swap.
- [ ] 7.7 Edit [`docs/09-Versions.md`](../../../docs/09-Versions.md) Version Decisions table: (a) amend the existing `dev.icerock.moko:resources` row to add `**SUPERSEDED 2026-05-28**` note + cite this change's PR; (b) ADD a new row documenting the swap with date 2026-05-28+, citing PR [#118](https://github.com/aditrioka/nearyou-id/pull/118)'s pre-implementation library re-check rule as the trigger, and noting that no NEW version pin is introduced (CMP Resources inherits from existing `composeMultiplatform` variable). Per `design.md` Open Question #4 — lean toward option (b) amend-plus-new for historical preservation.
- [ ] 7.8 Edit [`FOLLOW_UPS.md`](../../../FOLLOW_UPS.md) `mobile-hardcoded-strings-detekt-rule` entry: retarget the accepted-accessor regex from `MR\.strings\.` to `Res\.string\.` and update example accessor pattern strings from `MR.strings.X` to `Res.string.X`.

## 8. Verification + grep heuristic

- [ ] 8.1 Run `openspec validate shared-resources-swap-to-cmp-resources --strict` — change validates green.
- [ ] 8.2 Run `./gradlew ktlintCheck` — lint green across all modules including the modified `:shared:resources` + `:mobile:app`.
- [ ] 8.3 Run `./gradlew detekt` — lint green; verify no new Detekt complaints surfaced by the swap.
- [ ] 8.4 Run `./gradlew :shared:resources:test` — both `NearYouColorSchemeTest` + `ColorSchemeExtensionsTest` pass on iOS simulator; the Android-JVM-test path either passes (if Section 3.5 dropped the exclusion) or remains explicitly excluded with updated comment.
- [ ] 8.5 Run `./gradlew :mobile:app:check` — Android lint + tests green.
- [ ] 8.6 Run `./gradlew :backend:ktor:test :lint:detekt-rules:test` — both unchanged-but-still-must-pass test suites green (this change touches mobile only, but the project's full-gate convention runs all 4 lanes).
- [ ] 8.7 Grep heuristic verification for "no hardcoded UI strings" (per `design.md` Decision 7 + retargeted from Mobile #2's Section 8 grep):

   ```bash
   # Pass: zero hardcoded UI string literals inside :mobile:app composable call sites.
   # Accept: stringResource(Res.string.X), Res.string.X (direct), or // hardcoded-string-allow: annotated.
   grep -rEn 'Text\(\s*"[^"]+"' mobile/app/src/commonMain/ mobile/app/src/androidMain/ mobile/app/src/iosMain/ | \
       grep -vE '(stringResource\(Res\.string|Res\.string\.|//.*hardcoded-string-allow:)' && \
       { echo "FAIL: hardcoded UI string literals in mobile sources"; exit 1; } || \
       { echo "OK: no hardcoded UI string literals found"; exit 0; }
   ```

   Expected exit code: 0 (no offending matches).

- [ ] 8.8 Grep heuristic for residual Moko references (catches forgotten import paths, comment references, etc.):

   ```bash
   # Pass: zero residual MR.* / moko-resources / mokoResources references across the project.
   # Acceptable carveouts: archived OpenSpec changes (openspec/changes/archive/**) — those are historical record.
   grep -rEn '\b(MR\.(strings|images|fonts)\b|dev\.icerock\.moko|libs\.moko\.|libs\.plugins\.mokoResources)' \
       --include='*.kt' --include='*.kts' --include='*.toml' --include='*.md' \
       --exclude-dir='openspec/changes/archive' \
       --exclude-dir='build' \
       --exclude-dir='.gradle' \
       . | grep -v 'feedback_\|MEMORY' && \
       { echo "FAIL: residual Moko references in non-archive sources"; exit 1; } || \
       { echo "OK: no residual Moko references"; exit 0; }
   ```

   Expected exit code: 0 (no offending matches outside `openspec/changes/archive/**`).

- [ ] 8.9 Visual rendering verification on Android emulator: `./gradlew :mobile:app:installDebug` + launch on emulator + screenshot. Expected: `HomeScreen` renders with brand colors (primary `#1E4FD6`) + Plus Jakarta Sans typography + correct theme-appropriate logo (light or dark variant matching emulator's system theme) + version string "Versi 1.0" (substituted correctly).
- [ ] 8.10 (Optional) Visual rendering verification on iOS simulator if the macOS workstation is available: open `iosApp/iosApp.xcodeproj` + build/run on iPhone 15 Pro simulator + screenshot. Expected: same as 8.9.

## 9. Archive readiness

- [ ] 9.1 No staging deploy / smoke needed (zero backend impact per `proposal.md` § Impact). Section 6 of standard archive checklist marked **N/A** in the eventual `/opsx:archive` commit body.
- [ ] 9.2 PR title + body at archive time: title likely retitled from `feat(mobile): ...` to `chore(mobile): swap shared-resources from Moko to CMP Resources` (or similar — substrate swap with zero runtime impact is more "chore" than "feat"; confirm during `/opsx:archive` step).
- [ ] 9.3 Verify `openspec validate --specs shared-resources --strict` AND `openspec validate --specs mobile-app-scaffold --strict` BOTH pass after the archive commit applies the delta to canonical specs.
