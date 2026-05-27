## Context

Mobile #1 (`mobile-app-scaffold-replace-wizard`, PR [#105](https://github.com/aditrioka/nearyou-id/pull/105), squash-merged 2026-05-14) shipped `:mobile:app` as a production-shaped Compose Multiplatform scaffold — single `App()` composable in commonMain wrapping a Voyager `Navigator` inside a `NearYouTheme` (Material 3 light + dark, system-preference-driven), idempotent Koin DI, and a placeholder `HomeScreen` rendering "NearYouID" + version. Two deliberate gaps remained, both recorded in Mobile #1 [design.md Decision 3](../archive/2026-05-14-mobile-app-scaffold-replace-wizard/design.md):

- `NearYouTheme` calls vanilla Material 3 `lightColorScheme()` / `darkColorScheme()` because no brand color/typography tokens exist yet — visually generic, not "NearYouID-branded."
- `HomeScreen` renders hardcoded literals (`"NearYouID"`, `"v1.0"`) because no Moko Resources module exists yet to satisfy the "no hardcoded UI strings" invariant in [`openspec/project.md`](../../project.md) § Coding Conventions.

Both gaps were explicitly deferred to this change (Mobile #2 in [`openspec/project.md`](../../project.md) § Mobile + Admin Scaffolding Priority menu). The visual inputs required by the menu — app icon, in-app brand logo, color palette, typography — were collected from the user before scaffolding:

- **Logo SVGs** (user-supplied 2026-05-27): `Asset Logo Anon Hive.svg` (white hexagon glyph on `#0B4FA8` blue bg) + `Asset Logo Anon Hive Blue.svg` (blue `#014CAB` glyph on white bg). Both 108×108 viewBox.
- **Color palette** (user-supplied 2026-05-27 from a private claude.ai/design session): Material 3 role map anchored at primary `#1E4FD6` — 30 light-scheme M3 roles + extended semantic accents (coral location pin, amber Premium badge, success/warning/link). Light-only by design; dark scheme deferred to "v2" per palette author's note.
- **Typography** (delegated to Claude — 2026-05-27): Plus Jakarta Sans variable `.ttf` (OFL-licensed, Tokotype foundry, designed for Pemprov DKI Jakarta).
- **App icon variant** (delegated to Claude — 2026-05-27): white-on-blue default + blue-on-white alternate.

Today's date: 2026-05-28. Phase-balance check: scaffolding priority is active (0 of 2 trigger milestones — `mobile-nearby-timeline-screen` + `admin-actions-log-viewer` — shipped). Last two feat commits: Admin #1 (`admin-schema-bootstrap`, PR #107) then Mobile #1; this change interleaves to keep mobile + admin progressing in parallel.

## Goals / Non-Goals

**Goals:**

- Ship `:shared:resources` as a scaffolded Gradle module with Moko Resources plugin configured for commonMain + Android + iOS targets.
- Replace Mobile #1's vanilla Material 3 defaults with brand color + typography tokens consumed from `:shared:resources`, so `NearYouTheme` renders the actual NearYouID brand.
- Close the "hardcoded UI strings" gap in `:mobile:app` by routing all UI text through Moko Resources, validating Detekt's existing lint rule against the first real consumption surface.
- Bundle both in-app logo variants (light + dark) selectable via `isSystemInDarkTheme()`.
- Ship the app launcher icon as platform-native assets (Android adaptive icon + iOS Asset Catalog), separate from Moko Resources.
- Provide a coherent dark color scheme (mechanically derived from primary) so the first user to system-toggle dark mode doesn't see an off-brand vanilla-purple palette.
- Unblock Mobile #3 (auth screens), Mobile #4 (age gate), Mobile #5 (nearby timeline) — all of which need brand strings + theme tokens.

**Non-Goals:**

- **Hand-tuned dark palette.** Mechanically derived dark ships; a dedicated `mobile-dark-palette-tuning` change can follow if visual review identifies issues. Doing it in this change would block on a second claude.ai/design pass.
- **Material 3 1.4-alpha + expressive components.** `WavyProgressIndicator`, `MaterialExpressiveTheme`, expressive button/FAB APIs all wait for a future change that actually needs them.
- **Sentry KMP module-isation.** Already split as `infra-sentry-kmp-module-isation` per Mobile #1 design.md Decision 5.
- **Feature screens, networking, auth, FCM.** Mobile #3/#4/#5+ own these. This change strictly extends the scaffold with brand tokens — the negative invariants in the existing `mobile-app-scaffold` spec (no networking, no auth, no FCM, no hardcoded URLs, no backend/infra deps) remain in force.
- **iOS `PrivacyInfo.xcprivacy` finalization.** Pre-Phase 1 task 33 + Phase 3 iOS block.
- **Detekt rule changes.** The "no hardcoded UI strings" rule already exists per [`openspec/project.md`](../../project.md) § Coding Conventions; this change merely validates it against the first real Moko Resources consumption — no new lint rule is authored here.

## Decisions

### Decision 1: Reuse the existing `material3 = "1.10.0-alpha05"` Compose Multiplatform pin

**Choice:** Do NOT introduce a new `material3` version entry in [`gradle/libs.versions.toml`](../../../gradle/libs.versions.toml). The repository already pins `material3 = "1.10.0-alpha05"` (line 34) for the Compose Multiplatform `org.jetbrains.compose.material3:material3` artifact, shipped under Mobile #1's pinning regime alongside other accepted alpha pins (Voyager 1.1.0-beta03, OTel `2.25.0-alpha`, semconv `1.30.0-rc.1`). That pin already exposes the full `ColorScheme` constructor surface this change needs: the 30+ standard roles, the `surfaceContainer*` family, the `Fixed` color roles, the modern `Color(0xAARRGGBB)` accessor pattern. No new Version Pinning Decisions Log entry is needed for `material3` — only for the new `moko-resources` plugin + library pin.

**Stream clarification:** Compose Multiplatform's `material3` artifact (`org.jetbrains.compose.material3:material3`) versions independently from Jetpack's `androidx.compose.material3:material3` — the two share API shape but NOT version numbers. The CMP `1.10.0-alpha05` pin is the current canonical for KMP projects in this repo; it has no direct mapping to a Jetpack version number. Any future Material 3 version bump must explicitly identify which stream is being bumped.

**Alternatives considered:**

- **Add a new pin for Jetpack `androidx.compose.material3` 1.3.x stable alongside the existing CMP pin.** Forces dual-stream maintenance (KMP commonMain + Android target referencing different artifact groups) — non-trivial Gradle config, no clear benefit since this change ships zero Android-only Compose code.
- **Downgrade the existing CMP pin to an earlier stable like 1.7.x.** Loses the `surfaceContainer*` family + `Fixed` roles that the palette requires; introduces a downgrade-risk for any other consumer of the existing pin. Rejected — gratuitous.
- **Bump the existing pin to a later alpha.** No current consumer requirement justifies the bump; alpha-version bumps risk breaking changes in API shape. Defer until a downstream feature change drives the bump.

**Trade-off accepted:** This change inherits whatever stability risk the existing alpha05 pin already carries; that risk was accepted at Mobile #1 ship time and remains the project's working posture. Defers all `material3` version decisions to a future change that needs them.

### Decision 2: Brand `secondary` and `tertiary` mapped to **neutral surfaceVariant family**; coral + amber exposed as `ColorScheme` extension properties

**Choice:** In the `lightColorScheme(...)` constructor, map M3 `secondary` to `#EEF0F4` (surfaceVariant family) and M3 `tertiary` to `#E8EAEF` (a slightly different neutral stop). Coral (`#FF7A5C`) and amber (`#F4B740`) are exposed as `ColorScheme` extension properties — `MaterialTheme.colorScheme.locationPin` and `MaterialTheme.colorScheme.premiumBadge` — together with their full container/on-color sets. Semantic status colors (success, warning) and the link alias follow the same extension-property pattern.

**Rationale:** The palette author's explicit note: *"`secondary` (coral) is reserved for location pins only — never for general secondary actions. If your M3 implementation expects free use, map `surface-variant` (`#EEF0F4`) to that role and treat coral as a single-purpose accent."* Material 3 default widgets consume `secondary` / `tertiary` automatically — `FloatingActionButton` uses `secondary`, `FilterChip` selected state uses `secondaryContainer`, `BadgedBox` uses `tertiary` in many recipes. Shipping coral as M3 `secondary` would leak the location-pin semantic across every default FAB in the app.

**Alternatives considered:**

- **Honor the palette verbatim** (secondary = coral, tertiary = amber). Forces every default M3 widget invocation to override the color param explicitly — brittle and easy to miss in a code review.
- **Derive secondary/tertiary as muted tonal variants of primary blue** (Material Theme Builder's standard fallback). Would produce visually coherent M3 defaults but ignores the palette author's explicit guidance + introduces colors that didn't go through the claude.ai/design pass.

**Trade-off accepted:** Code referencing the location-pin or Premium-badge color must use the extension property, not the standard `MaterialTheme.colorScheme.secondary` / `.tertiary`. Documented in the `shared-resources` capability spec + `design.md` (here). Documented Material 3 advanced-color-customization pattern per [m3.material.io/styles/color/advanced/apply-colors](https://m3.material.io/styles/color/advanced/apply-colors).

### Decision 3: Dark color scheme **mechanically derived from primary `#1E4FD6` via HCT tonal stops**

**Choice:** Generate the dark `ColorScheme` from the light primary using the Material Theme Builder algorithm (primary tone 80, container tone 30, onPrimary tone 20, onPrimaryContainer tone 90, surface dark neutral, etc.). Surface the full derived dark palette in this design doc (below) for proposal-review confirmation; the implementer can paste the values verbatim into `NearYouColorScheme.kt`.

**Derived dark palette (auto-generated; confirm during proposal review):**

| Role | Light | Dark |
|---|---|---|
| `primary` | `#1E4FD6` | `#B3C5FF` |
| `onPrimary` | `#FFFFFF` | `#002C7B` |
| `primaryContainer` | `#E8EEFB` | `#003DAB` |
| `onPrimaryContainer` | `#1740B8` | `#DBE1FF` |
| `inversePrimary` | `#8AAEF8` | `#1E4FD6` |
| `secondary` | `#EEF0F4` | `#44464F` |
| `onSecondary` | `#3E4557` | `#C4C6D0` |
| `secondaryContainer` | `#F5F6F8` | `#1D2024` |
| `onSecondaryContainer` | `#0E1220` | `#E2E2E9` |
| `tertiary` | `#E8EAEF` | `#32353A` |
| `onTertiary` | `#0E1220` | `#C4C6D0` |
| `tertiaryContainer` | `#F7F8FA` | `#272A2F` |
| `onTertiaryContainer` | `#0E1220` | `#E2E2E9` |
| `error` | `#E4443B` | `#FFB4AB` |
| `onError` | `#FFFFFF` | `#690005` |
| `errorContainer` | `#FDEAEA` | `#93000A` |
| `onErrorContainer` | `#B8342C` | `#FFDAD6` |
| `background` | `#FFFFFF` | `#111318` |
| `onBackground` | `#0E1220` | `#E2E2E9` |
| `surface` | `#FFFFFF` | `#111318` |
| `onSurface` | `#0E1220` | `#E2E2E9` |
| `surfaceVariant` | `#EEF0F4` | `#44464F` |
| `onSurfaceVariant` | `#3E4557` | `#C4C6D0` |
| `surfaceTint` | `#1E4FD6` | `#B3C5FF` |
| `inverseSurface` | `#1B2234` | `#E2E2E9` |
| `inverseOnSurface` | `#FFFFFF` | `#2F3036` |
| `outline` | `#79747E` | `#938F99` |
| `outlineVariant` | `#E8EAEF` | `#44464F` |
| `scrim` | `Color(0x8F0E1220)` | `Color(0x8F0E1220)` |
| `surfaceBright` | `#FFFFFF` | `#37393E` |
| `surfaceDim` | `#DCDFE5` | `#111318` |
| `surfaceContainerLowest` | `#FFFFFF` | `#0C0E13` |
| `surfaceContainerLow` | `#F7F8FA` | `#191C20` |
| `surfaceContainer` | `#F5F6F8` | `#1D2024` |
| `surfaceContainerHigh` | `#EEF0F4` | `#272A2F` |
| `surfaceContainerHighest` | `#E8EAEF` | `#32353A` |

**Extension-property dark counterparts:**

| Role | Light | Dark |
|---|---|---|
| `locationPin` | `#FF7A5C` | `#FFB59E` |
| `locationPinContainer` | `#FFEFEA` | `#7C2E22` |
| `onLocationPin` | `#FFFFFF` | `#561F18` |
| `onLocationPinContainer` | `#B8382A` | `#FFDAD2` |
| `premiumBadge` | `#F4B740` | `#E8B941` |
| `premiumBadgeContainer` | `#FFF8EC` | `#7A5400` |
| `onPremiumBadge` | `#FFFFFF` | `#412D00` |
| `onPremiumBadgeContainer` | `#C98915` | `#FFDEA8` |
| `success` | `#1F9D55` | `#7DDB9C` |
| `onSuccess` | `#FFFFFF` | `#003915` |
| `successContainer` | `#E8F7EE` | `#005321` |
| `onSuccessContainer` | `#126B38` | `#9CF8B7` |
| `warning` | `#E49317` | `#FFB874` |
| `onWarning` | `#FFFFFF` | `#4A2700` |
| `warningContainer` | `#FFF4E0` | `#693C00` |
| `onWarningContainer` | `#9C610A` | `#FFDDB9` |
| `link` | `#1740B8` | `#B3C5FF` |

**Rationale:** Branded-light + vanilla-purple-dark (the status quo from Mobile #1) would create a jarring brand disconnect the first time a user system-toggles dark mode. For an 18+ social app with high evening session time, this is a real cost. Mechanical derivation produces a coherent (if not hand-tuned) dark palette using the same algorithm Material Theme Builder uses — predictable, documented, no surprises.

**Derivation framing note:** Material Theme Builder's algorithm has two source-paths for dark-scheme values. The **primary-family roles** (`primary`, `primaryContainer`, `onPrimary`, `onPrimaryContainer`, `inversePrimary`, `secondaryContainer` when derived from primary, etc.) ARE derived from the source primary's HCT tonal stops (primary tone 80, container tone 30, on-color tone 20, etc.). The **neutral surface roles** (`surface`, `onSurface`, `surfaceVariant`, `surfaceContainer*` family, `outline`, `outlineVariant`, `inverseSurface`, `inverseOnSurface`) are derived from MTB's default neutral hue, NOT from the primary's HCT family — that neutral hue is a separate algorithm input. The dark values for those neutral roles in the table above reflect this split: primary-family rolls forward from `#1E4FD6`, neutrals from MTB's default neutral palette. Both halves are mechanically derived, but from different source colors.

**Alternatives considered:**

- **Defer Mobile #2 until claude.ai/design produces a v2 dark palette.** Blocks the entire change + downstream Mobile #3/#4/#5.
- **Ship branded-light + vanilla-purple-dark.** Honors the palette's "v1 is light-only" intent but leaves the dark-mode UX visibly off-brand. The cost is real and recurring (every dark-mode user, every session).
- **Hand-tune a dark palette in this change.** Out of scope per design budget; better as a follow-up `mobile-dark-palette-tuning` change with proper visual review.

**Trade-off accepted:** The derived dark palette is a starting point, not a final design. A future visual-review-driven follow-up may replace any of these values. Documented in `proposal.md` § Out of Scope as a tracked follow-up.

### Decision 4: Re-export logo SVGs with palette primary `#1E4FD6`

**Choice:** Modify the two user-supplied SVG files to use `#1E4FD6` everywhere the source files used `#0B4FA8` or `#014CAB`. Ship the modified files as `commonMain/moko-resources/images/logo_brand_dark.svg` (white logo on `#1E4FD6` bg, from `Asset Logo Anon Hive.svg`) and `commonMain/moko-resources/images/logo_brand_light.svg` (blue `#1E4FD6` logo on white bg, from `Asset Logo Anon Hive Blue.svg`). Tasks.md prescribes the exact `sed` operations.

**Rationale:** Launcher icon → splash screen → in-app primary visual consistency. If the launcher icon background is `#014CAB` and the splash/UI primary is `#1E4FD6`, there is visible color disjunction at the moment the app launches (icon = darker blue, splash = lighter brighter blue). For a brand-conscious 18+ social app where users see the launcher icon dozens of times per day, this discontinuity is a real cost. SVG re-export is a 1-color find-replace — trivial vs. re-deriving the entire 30-role palette from a `#014CAB` anchor.

**Alternatives considered:**

- **Shift palette primary to `#014CAB`.** Preserves source logos but invalidates the carefully-derived palette (every container, on-color, surfaceTint, inversePrimary depends on primary; tonal contrast relationships would need re-validation across all 30+ roles).
- **Accept the gap** (icon = dark variant, UI primary = bright variant). Two-tier brand. Less work; visible disjunction at cold-start; trains users that the icon and the app's brand color "don't quite match."

**Trade-off accepted:** The two source SVGs Oka generated are no longer the canonical logo files — the re-exported variants in `:shared:resources` are. Original files remain as historical artifacts in `~/Downloads/`; the canonical assets going forward live in the repo.

### Decision 5: Plus Jakarta Sans **single-family typography** across all 13 Material 3 roles

**Choice:** Bundle the Plus Jakarta Sans variable `.ttf` (weight axis 200–800) in `shared/resources/src/commonMain/moko-resources/fonts/plus_jakarta_sans.ttf`. Define `NearYouTypography` as a `Typography` instance applying Plus Jakarta Sans to all 13 Material 3 type roles (`displayLarge`/`displayMedium`/`displaySmall`, `headlineLarge`/`headlineMedium`/`headlineSmall`, `titleLarge`/`titleMedium`/`titleSmall`, `bodyLarge`/`bodyMedium`/`bodySmall`, `labelLarge`/`labelMedium`/`labelSmall`). Each role keeps the M3-standard size + weight pairing (e.g., `displayLarge` 57sp/400, `labelSmall` 11sp/500).

**Rationale:** Plus Jakarta Sans was designed by [Tokotype](https://fonts.google.com/specimen/Plus+Jakarta+Sans), an Indonesian foundry, originally commissioned for Pemprov DKI Jakarta city branding. Cultural fit for an Indonesia-only 18+ social app. OFL-licensed → bundleable with zero attribution surface area. Variable axis covers all M3 weights from a single `.ttf` file (smaller bundle than 7 static font files). Single-family-everything is the simplest Moko Resources config and keeps the visual system cohesive.

**Alternatives considered:**

- **Inter (or Roboto, or system default).** Inter leans editorial (Linear, Figma, GitHub) — fine for a productivity tool, less fitting for a social app. Roboto is M3 default — generic, no brand differentiation. System default → unpredictable on iOS (San Francisco) vs Android (varies by OEM).
- **Display/body font pair** (e.g., Plus Jakarta Sans body + Bricolage Grotesque or Space Grotesque display). Adds visual contrast at the cost of a second font file + more complex `Typography` instance. Defer to a future "brand voice" change if visual review calls for it.

**Trade-off accepted:** Pairing a display font later requires touching `NearYouTypography` + bundling a second `.ttf`. Small, isolated change when the need arises.

**Defensive fallback:** If Moko Resources font loading fails at runtime (rare — the .ttf is bundled, not network-fetched), the `FontFamily` declaration includes `FontFamily.SansSerif` as a fallback so text still renders.

### Decision 6: Both logo variants bundled, theme-aware selection via `isSystemInDarkTheme()`

**Choice:** Ship both `logo_brand_light.svg` (blue-on-white) and `logo_brand_dark.svg` (white-on-blue) in `commonMain/moko-resources/images/`. Compose call sites use the standard pattern:

```kotlin
val logo = if (isSystemInDarkTheme()) MR.images.logo_brand_dark else MR.images.logo_brand_light
Image(painter = painterResource(logo), contentDescription = stringResource(MR.strings.app_name))
```

**Rationale:** User explicitly said "dua-duanya bisa digunakan" (both can be used). Light-on-dark + dark-on-light contrast is preserved automatically across system theme toggle. Standard Compose theme-aware drawable selection pattern.

**Alternatives considered:**

- **Ship a single variant** (whichever the user picks as canonical). Forces poor contrast on the opposite theme — white logo on light background or blue logo on dark background, both unreadable.
- **Tint a single monochrome SVG via Compose `ColorFilter`.** Conceptually clean but the source SVG has both stroke + fill semantics (the dot is a filled circle, the hexagon is stroked) — tinting them uniformly loses the visual separation.

### Decision 7: App launcher icon = **white-on-blue (`#1E4FD6`) default + blue-on-white alternate**

**Choice:** Ship the white-on-blue variant as the default Android adaptive icon (`mipmap-anydpi-v26/ic_launcher.xml` + `ic_launcher_round.xml`) and the default iOS Asset Catalog `AppIcon.appiconset`. Ship the blue-on-white variant as an Android alternate (`mipmap-anydpi-v26/ic_launcher_alt.xml`, wired via an `<activity-alias>` in `AndroidManifest.xml` ready for a future user-selectable icon-theme feature). Android adaptive icon foreground = white hexagon glyph vector drawable (`drawable/ic_launcher_foreground.xml`, extracted from the modified SVG with no background rect); background = `@color/ic_launcher_background` referencing `#1E4FD6` in `values/colors.xml`. Android 13+ themed icons supported via the `<monochrome>` attribute pointing at a monochrome glyph drawable so the system can tint based on wallpaper colors.

**Rationale:** Per Android adaptive icon design guidance — the brand color does the heavy lifting against typical home-screen wallpapers; white-on-blue stands out against everything from solid dark wallpapers to colorful photos. Blue-on-white tends to disappear on light wallpapers. Alternate variant ships now to prepare for a future user-selectable icon-theme feature without requiring a follow-up scaffold change.

**Alternatives considered:**

- **Flip the default** (blue-on-white). Cleaner minimalist aesthetic but disappears on light wallpapers + loses brand color prominence.
- **Ship only one variant.** Locks in the default with no alternate; user-selectable icon themes would require a future scaffold change.

### Decision 8: App launcher icon lives in **platform-native locations**, replacing Mobile #1's wizard-default assets; iOS uses the **modern single-1024 universal idiom**

**Choice:** Replace Mobile #1's existing JetBrains-wizard-default launcher assets in-place:

**Android** — replace these existing files in `mobile/app/src/androidMain/res/`:
- `mipmap-anydpi-v26/ic_launcher.xml` + `ic_launcher_round.xml` (currently wizard adaptive icon)
- `drawable-v24/ic_launcher_foreground.xml` (currently wizard vector glyph)
- `drawable/ic_launcher_background.xml` (currently wizard vector gradient — replace with `@color/ic_launcher_background` reference + new `values/colors.xml`)
- 10 raster PNGs under `mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png` + `_round.png` (load-bearing under `min-sdk = 24` for Android 7.x devices that ignore the adaptive XML and fall back to rasters)

Plus NEW files: `drawable/ic_launcher_monochrome.xml` for Android 13+ themed-icon support; `drawable/ic_launcher_foreground_alt.xml` + `mipmap-anydpi-v26/ic_launcher_alt.xml` for the blue-on-white alternate (wired via `<activity-alias>` in `AndroidManifest.xml`, dormant).

**iOS** — preserve the existing **modern iOS 14+ single-1024 universal idiom** in [`iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/Contents.json`](../../../iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/Contents.json), which already declares 3 entries (default + `luminosity:dark` + `luminosity:tinted` appearance variants, all `universal` idiom at 1024×1024). Replace the existing wizard `app-icon-1024.png` with 3 NearYouID-branded 1024×1024 PNGs rasterized from the modified SVG: one default (white-on-blue), one dark variant (lighter primary for dark-mode home-screens), one tinted variant (monochrome-style for iOS 18+ tinted icons). The `Contents.json` shape stays intact — only the PNG bytes change.

In-app brand logos (consumed inside the running app's UI) DO live in `:shared:resources` via Moko Resources; launcher icons do not. The script `dev/scripts/generate-ios-app-icons.sh` generates the 3 iOS 1024 variants (uses `rsvg-convert` with `pdftocairo` fallback).

**Rationale:** Moko Resources is the contract for in-app drawables consumed via `Image(painter = painterResource(MR.images.X))`. Launcher icons are platform-native conventions — Android `mipmap-anydpi-v26/ic_launcher.xml` adaptive icon system + iOS Asset Catalog `AppIcon.appiconset/` — not addressable via a KMP shared module. Mobile #1's existing modern iOS Asset Catalog idiom (single 1024 + appearance variants) supports system-tinted iOS 18+ icons and dark-mode home-screens; regressing to the legacy 17-PNG multi-size pattern would strictly LOSE that capability while adding a brew-dep rasterizer requirement. Android's 10 raster fallbacks are NOT optional under `min-sdk = 24` (Android 7.x devices use them when the adaptive XML isn't available); they must be regenerated, not deleted.

**Alternatives considered:**

- **Regress iOS Asset Catalog to legacy 17-PNG multi-size pattern.** Strictly more maintenance (rasterizer script + 17 distinct PNGs) for strictly less capability (loses dark + tinted variant support). Rejected — Mobile #1's modern idiom is a strict improvement.
- **Skip the 10 Android raster fallbacks; rely on adaptive XML.** Breaks the launcher icon on Android 7.x devices (`min-sdk = 24` includes Android Nougat 7.0/7.1). Rejected — known regression.
- **Bundle the launcher source SVG in Moko Resources + generate platform-native assets at build time.** Conceptually clean but requires a Gradle build script extension that knows how to invoke `rsvg-convert` + rebuild the Android adaptive XML + rebuild the iOS Asset Catalog JSON. Adds a build-time dependency on `librsvg2` for any developer running `:mobile:app:assembleDebug`. Not worth the complexity for a one-time scaffold.
- **Generate launcher assets at CI time only.** Same complexity, plus the assets aren't in the repo so a fresh clone can't build without CI. Rejected.

### Decision 9: Outline color **darkened from palette's `#D9DDE5` to `#79747E`** to actually meet M3 3:1 contrast

**Choice:** Override the palette's `outline = #D9DDE5` with `#79747E` (the Material 3 default outline tone) — the only nearby value in the palette author's family that **actually passes** Material 3's 3:1 contrast guideline against `surface = #FFFFFF`. Keep the palette's `#D9DDE5` as the value for `outlineVariant`, which is for purely decorative tones (no contrast requirement).

**Rationale + math:** Per [Material 3 color roles spec](https://m3.material.io/styles/color/roles), `outline` is "used for important boundaries, such as a text field outline" and MUST pass 3:1 contrast against surface. Measured WCAG contrast ratios against `surface = #FFFFFF`:

| Candidate | Hex | Ratio vs white | Meets 3:1? |
|---|---|---|---|
| Palette author's value | `#D9DDE5` | 1.36:1 | No |
| Earlier proposal value (now rejected) | `#9CA3AF` | 2.54:1 | No — borderline but FAILS |
| **M3 default outline tone (this Decision)** | `#79747E` | **4.05:1** | **Yes** |
| Even darker option | `#6F7079` | 4.50:1 | Yes |

The earlier proposal value `#9CA3AF` was incorrectly cited as "~2.9:1 (borderline pass)" — the actual computed ratio is 2.54:1, which fails M3's 3:1 threshold. `#79747E` is the M3 default outline color and passes cleanly at 4.05:1; using it preserves palette intent (a neutral grey) while honoring the accessibility spec.

**Alternatives considered:**

- **Accept palette's `#D9DDE5` for outline.** Honors the palette literally but fails M3 accessibility guideline by a wide margin (1.36:1); text fields will look borderless, dividers nearly invisible. Rejected.
- **`#9CA3AF`** (earlier proposal value). Computes to 2.54:1 — does NOT meet 3:1; the rationale "borderline pass" was based on incorrect math. Rejected as the previous spec scenario header "outline meets M3 contrast guideline" would have been factually false.
- **A blue-tinted neutral like `#6C7B9A`** (would carry the brand blue cast more visibly). Adds chroma a small amount but cool-blue outline doesn't fit M3's typically-neutral outline convention; deviates from MTB norms without clear benefit. Defer to a future hand-tuned-palette change if visual review wants it.

**Trade-off accepted:** Two-hex deviation from the palette author's outline tone (uses M3 default `#79747E` for `outline`, palette author's `#D9DDE5` for `outlineVariant`). Documented for proposal-review confirmation — user may override during review if a cool-blue-tinted outline is preferred.

### Decision 10: "No hardcoded UI strings" verified via **grep-based check**, not Detekt — Detekt rule deferred as a follow-up

**Choice:** Verify the "no hardcoded UI strings in mobile source" convention via an **explicit grep step** in tasks.md, NOT via a Detekt rule. Mobile #1's task 9.4 deferred the Detekt rule as a `FOLLOW_UPS.md` entry `mobile-negative-requirement-ci-grep`; the rule still does not exist in `:lint:detekt-rules` (only 9 backend rules registered in `NearYouRuleSetProvider`). Treating the rule as if it existed (the earlier shape of this decision did) would produce a vacuously-true verification: `./gradlew :mobile:app:detekt` exits 0 because there's nothing to fire. This change instead ships a grep assertion + adds a new follow-up entry to upgrade the grep to a real Detekt rule in a focused future change.

**Grep shape (lives in tasks.md Section 8):**

```bash
# Pass: zero hardcoded UI string literals inside :mobile:app composable call sites.
# A "hardcoded UI string" is a string literal passed to one of these Compose UI text-rendering surfaces:
#   - Text("..."), Text(text = "...")
#   - Button(...) { Text("...") }, TextButton, OutlinedButton, IconButton, etc.
#   - TopAppBar(title = { Text("...") }), Snackbar, AlertDialog
#   - contentDescription = "..."
# Acceptable string sources:
#   - stringResource(MR.strings.X) — Moko Resources Compose accessor
#   - MR.strings.X.desc().localized() — Moko Resources direct accessor
#   - A local val whose initializer is one of the above
# The grep below catches the common offenders; exact-zero match is the gate.
grep -rEn 'Text\(\s*"[^"]+"' mobile/app/src/commonMain/ mobile/app/src/androidMain/ mobile/app/src/iosMain/ | \
    grep -vE '(stringResource|MR\.strings|//.*hardcoded-string-allow:)' && \
    { echo "FAIL: hardcoded UI string literals in mobile sources"; exit 1; } || \
    { echo "OK: no hardcoded UI string literals found"; exit 0; }
```

**Rationale:**

- **Honest about current enforcement state.** Mobile #1 deferred the Detekt rule; pretending it exists would be a documentation lie that future maintainers would have to debug.
- **Lightest-touch fix to the multi-lens-caught defect.** Grep is fast (sub-second), zero new dependencies, runs in CI exactly the same gate `./gradlew :mobile:app:detekt` would have run in (added as a verification step in `tasks.md` Section 8).
- **Preserves the architectural invariant.** The "no hardcoded UI strings" invariant in [`openspec/project.md`](../../project.md) § Coding Conventions is still real; this change just chooses a grep-shaped backstop instead of a Detekt-rule-shaped one.
- **Upgrade path is documented.** New `FOLLOW_UPS.md` entry `mobile-hardcoded-strings-detekt-rule` tracks the eventual Detekt rule (per the project's precedent of one rule per change — `RateLimitTtlRule` in `like-rate-limit`, `CoordinateJitterRule` in `coordinate-jitter-lint-rule`, etc.). The grep stays as the canonical backstop until the rule lands.

**Alternatives considered:**

- **Author the Detekt rule in this change.** Scope creep — Mobile #2's purpose is Moko Resources bootstrap, not lint-rule authoring. Project precedent is one Detekt rule per change. Rejected.
- **Drop the "no hardcoded strings" requirement entirely from this change's specs.** Hides the architectural invariant; future contributors might add hardcoded literals without anyone noticing. Rejected.
- **Defer Mobile #2 until the Detekt rule lands as a precondition change.** Blocks 2+ weeks of mobile work for a lint enhancement that grep covers in the interim. Rejected as too costly.
- **Use the existing `ktlintCheck` rule for string literal style** (a Kotlin-style linter, not a content-aware UI-strings linter). It doesn't have a "this string literal looks like a UI label" detector — wrong tool. Rejected.

**Trade-off accepted:** Grep is a coarser tool than a proper Detekt rule (false positives possible on string literals that happen to live in `Text(...)` for non-UI purposes, e.g., test fixtures, log labels, debug prints). The grep heuristic above excludes `// hardcoded-string-allow:` annotated lines to give an explicit escape hatch for the rare legitimate case. Until the Detekt rule lands, treat any annotation as a code-review smell.

## Risks / Trade-offs

| Risk | Mitigation |
|---|---|
| Mechanically derived dark palette looks off-brand or low-contrast in real-world rendering | Surface derived values in `design.md` (above) for user override during proposal review; track hand-tuned dark as `mobile-dark-palette-tuning` follow-up; document the trade-off in `proposal.md` § Out of Scope |
| Outline color override (`#79747E` M3 default instead of palette's `#D9DDE5`) deviates from claude.ai/design output without user explicit OK | Decision 9 documents the rationale (palette value fails 1.36:1 vs M3's 3:1 requirement) + alternatives (including the rejected `#9CA3AF` borderline value); user can override during proposal review |
| Plus Jakarta Sans variable `.ttf` doesn't load on iOS via Moko Resources due to font-format quirk | `NearYouTypography` declares `FontFamily.SansSerif` as fallback so text still renders; if it surfaces, fall back to bundling static `.ttf` per weight (small diff) |
| `material3 = "1.3.x"` stable lacks a future API the next mobile change needs | Bump to `1.4.x` stable in the change that adds the new component; low risk because 1.3 → 1.4 is non-breaking by semver |
| Re-exported logo SVGs lose visual fidelity vs source (e.g., introduced rendering artifacts) | Tasks.md prescribes hex-only `sed` substitution, no path/stroke restructuring; visual diff verified during build verification step |
| Android adaptive icon foreground glyph extraction (removing bg rect, isolating polylines) introduces shape error | Tasks.md prescribes vector drawable creation from the modified SVG with explicit coordinate preservation; visual verification step renders the launcher icon on a fresh Android emulator |
| iOS Asset Catalog generation script (`generate-ios-app-icons.sh`) requires `rsvg-convert`, blocking iOS-side build for devs without it | Script falls back to `pdftocairo` (typically bundled with macOS `poppler` via Homebrew); generated PNGs committed to the repo so the script doesn't need to run on every build |
| Moko Resources plugin version drift introduces breaking changes for `MR.images.*` / `MR.strings.*` consumption shape | Pin specific version in `gradle/libs.versions.toml` with Version Pinning Decisions Log entry; future bumps go through the Dependabot/Renovate flow with explicit review |
| Detekt no-hardcoded-strings rule produces false positives on legitimate Moko Resources call sites | If discovered during build verification, file separately as a `:lint:detekt-rules` bug-fix change; do not bypass with `@Suppress` (would defeat the invariant); confirm before merging |
| `surfaceVariant` (`#EEF0F4`) and `tertiary` (`#E8EAEF`) being near-identical neutrals confuses Material 3 default widgets that distinguish them visually | Acceptable trade-off — both roles are intentionally muted to honor the palette author's reserved-purpose constraint; brand-specific UI uses extension properties for color expression |

## Migration Plan

This is a scaffold change with **zero runtime impact** — no backend deploys, no database migrations, no live-user behavior change. The migration is purely build-time:

1. **Pre-merge** — `./gradlew :mobile:app:assembleDebug` + `:mobile:app:linkDebugFrameworkIosArm64` + `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` all green on the change branch.
2. **Squash-merge** to `main` — staging auto-deploys via `.github/workflows/deploy-staging.yml`, but the change has zero backend impact so the deploy is effectively a no-op (the only artifacts the backend cares about are unchanged). Mobile artifacts are not auto-deployed; QA testers pull the staging flavor from Firebase App Distribution / TestFlight internal as usual.
3. **Post-merge** — local dev verification: clone fresh, run `./gradlew :mobile:app:assembleDebug` + open in Android Studio + run on emulator → confirm `HomeScreen` renders with brand colors + Plus Jakarta Sans + the in-app logo; toggle system dark mode + confirm dark scheme + dark logo variant render.

**No rollback plan** beyond standard git revert — there is no live user surface to roll back.

### Decision 11: Ship Android 13+ themed-icon `monochrome` drawable in this change

**Choice:** Include `drawable/ic_launcher_monochrome.xml` (vector drawable of just the hexagon glyph in solid black) as part of this change, referenced by the `<monochrome>` attribute on `mipmap-anydpi-v26/ic_launcher.xml`. Android 13+ system uses this to render a wallpaper-tinted variant when the user enables themed icons.

**Rationale:** Trivial to add (~30 LOC vector drawable, mechanical extraction from the brand SVG with `strokeColor="#000000"`). Postponing it to a follow-up would require another change to touch the same `mipmap-anydpi-v26/ic_launcher.xml` file, wasting a PR cycle. The capability is already implied by the launcher icon work Mobile #2 is doing — separating it adds bookkeeping cost without any scoping benefit.

**Alternatives considered:**

- **Defer to a follow-up `mobile-themed-icon-monochrome` change.** Extra PR for trivial work; would re-touch files this change already modifies; no scoping benefit.
- **Skip themed-icon support entirely.** Loses Android 13+ home-screen integration UX (wallpaper-tinted icons) for no clear reason; this is a one-time mechanical drawable.

### Decision 12: `:shared:resources` Gradle module placed **inside the existing `if (includeMobile)` block** in `settings.gradle.kts`

**Choice:** Register `:shared:resources` via `include(":shared:resources")` placed inside [`settings.gradle.kts`](../../../settings.gradle.kts) line ~40's `if (includeMobile.toBoolean()) { ... }` block (alongside `include(":mobile:app")`), NOT at the top-level adjacent to `:shared:tmp` / `:shared:distance`.

**Rationale:** `:shared:resources` applies the `com.android.library` Gradle plugin to expose an Android target. The existing `:shared:*` modules (`:shared:tmp`, `:shared:distance`) are JVM-only — they sit at the top level because they don't need an Android SDK to evaluate. The `if (includeMobile)` block exists specifically so the Cloud Run JDK-only Docker builder (which sets `includeMobile=false`) can run Gradle without an Android SDK present. Placing `:shared:resources` outside this block would force every backend deploy to bundle the Android SDK — a real cost (~600MB) for zero benefit, and would break the Cloud Run Docker build entirely.

**Alternatives considered:**

- **Place at top level for visual consistency with `:shared:tmp` / `:shared:distance`.** Breaks Cloud Run JDK-only builder. Rejected.
- **Make `:shared:resources` JVM-only too** (drop Android target). Defeats the entire purpose — Moko Resources requires platform-specific targets to generate `R.class`-equivalents.

## Open Questions

1. **Should the in-app logo also be exposed as an Android adaptive icon source** (single source of truth, build-time generation)? *Decision deferred.* Current scaffold ships them as separate assets (Moko Resources for in-app, platform-native for launcher) per Decision 8. Revisit if maintenance burden grows.
2. **Should `NearYouTypography` ship M3 type-scale presets** (Display/Headline/Title/Body/Label) as Material 3 standard sizes + weights, or override with brand-tuned sizes? *Decision: M3-standard sizes for v1.* No brand spec exists for type scale; defer to a future "brand voice" change if visual review identifies need.
3. **Is the user OK with the M3-default `#79747E` outline override** vs the palette author's `#D9DDE5`? Surface for proposal review (this design's Decision 9). The override picks accessibility over palette literal-honoring; user may prefer a cool-blue-tinted outline if visual review wants the brand cast preserved.
4. **Is the user OK with the derived dark palette values** in Decision 3's table? Surface for proposal review.
5. **Moko Resources `home_placeholder_version` format-string portability** — Moko Resources reportedly normalizes `%1$s` to `%@` on iOS via its NSLocalizedString integration. Verify during tasks.md Section 4 that the round-trip works correctly (`stringResource(MR.strings.home_placeholder_version, "1.0")` renders `"Versi 1.0"` on both Android and iOS). If Moko surface-area differs from expectations, fall back to positional `%s` or hard-coded version-string substitution in commonMain Kotlin.
