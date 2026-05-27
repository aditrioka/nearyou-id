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

### Decision 1: Material 3 version pin — **`material3 = "1.3.x"` stable**

**Choice:** Pin `androidx.compose.material3:material3` (and the Compose Multiplatform `org.jetbrains.compose.material3:material3` equivalent) to the latest stable in the **1.3.x** line. Record the pin + rationale in [`docs/09-Versions.md`](../../../docs/09-Versions.md) § Version Decisions per the project's Version Pinning policy.

**Alternatives considered:**

- **`material3 = "1.4.0-beta01"` alpha** (with expressive components). Adds `MaterialExpressiveTheme`, `expressiveLightColorScheme`, `WavyProgressIndicator`, FAB Menu APIs, promoted ToggleButtons. But Compose Multiplatform 1.9.x flags Expressive APIs as `@ExperimentalMaterial3ExpressiveApi` and requires the alpha artifact; the scaffold has no consumer that needs them. Pinning a beta for a foundational scaffold violates the project's "minimize maintenance" principle — a near-term breaking change in the beta line would force a re-touch.
- **`material3 = "1.2.x"`.** Lacks the `surfaceContainer*` family (added in 1.2-late) and the `Fixed` color roles (added in 1.3). The palette requires `surfaceContainerLowest`/`Low`/`Container`/`High`/`Highest` — pinning 1.2 would force us to drop those roles.

**Trade-off accepted:** Defers expressive components until a future change explicitly needs them. When that change lands, it will bump to the then-stable 1.4.x line and add expressive consumers in one PR rather than two.

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
| `outline` | `#9CA3AF` | `#8E9099` |
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

### Decision 8: App launcher icon lives in **platform-native locations**, NOT inside Moko Resources

**Choice:** Android launcher icon under `mobile/app/src/androidMain/res/mipmap-anydpi-v26/`, `drawable/`, `values/colors.xml`. iOS launcher icon under `iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/` (17 PNG sizes generated via `dev/scripts/generate-ios-app-icons.sh` using `rsvg-convert` with `pdftocairo` fallback). The in-app brand logo (consumed inside the running app's UI) DOES live in `:shared:resources` via Moko Resources, but the launcher icon does not.

**Rationale:** Moko Resources is the contract for in-app drawables consumed via `Image(painter = painterResource(MR.images.X))`. Launcher icons are platform-native conventions — Android `mipmap-anydpi-v26/ic_launcher.xml` adaptive icon system + iOS Asset Catalog `AppIcon.appiconset/` — not addressable via a KMP shared module. This split mirrors how `mobile-app-scaffold-replace-wizard` already handles `AndroidManifest.xml` + iOS `Info.plist` + `iOSApp.swift` as platform-native files outside commonMain.

**Alternatives considered:**

- **Bundle the launcher source SVG in Moko Resources + generate platform-native assets at build time.** Conceptually clean but requires a Gradle build script extension that knows how to invoke `rsvg-convert` + rebuild the Android adaptive XML + rebuild the iOS Asset Catalog JSON. Adds a build-time dependency on `librsvg2` for any developer running `:mobile:app:assembleDebug`. Not worth the complexity for a one-time scaffold.
- **Generate launcher assets at CI time only.** Same complexity, plus the assets aren't in the repo so a fresh clone can't build without CI. Rejected.

### Decision 9: Outline color **darkened from palette's `#D9DDE5` to `#9CA3AF`**

**Choice:** Override the palette's `outline = #D9DDE5` with `#9CA3AF` to meet Material 3's 3:1 contrast guideline for non-text decorative elements (form field borders, dividers, etc.) against `surface = #FFFFFF`. Keep the palette's `#D9DDE5` as the value for `outlineVariant`, which is for purely decorative tones (no contrast requirement).

**Rationale:** Per [Material 3 color roles spec](https://m3.material.io/styles/color/roles), `outline` is "used for important boundaries, such as a text field outline" and should pass 3:1 contrast against surface. `#D9DDE5` against white surface yields ~1.27:1, well below the threshold. `#9CA3AF` yields ~2.9:1 (borderline pass) — preferred over palette's overly-subtle value while preserving palette intent for `outlineVariant`.

**Alternatives considered:**

- **Accept palette's `#D9DDE5` for outline.** Honors the palette literally but fails M3 accessibility guideline; text fields will look borderless, dividers nearly invisible.
- **Use Material 3 default outline tone** (~`#79747E`). Too dark, doesn't carry the cool-blue cast of the brand palette.

**Trade-off accepted:** Single hex deviation from the palette author's value, documented here for proposal-review confirmation.

### Decision 10: Detekt no-hardcoded-UI-strings rule verification **scope = `:mobile:app` only**

**Choice:** The Detekt rule "no hardcoded UI strings in mobile source" (per [`openspec/project.md`](../../project.md) § Coding Conventions) is already in `:lint:detekt-rules` from a prior change. This change validates the rule against `:mobile:app` (the first module to consume Moko Resources) — verifies it correctly accepts `stringResource(MR.strings.X)` / `desc().localized()` call sites and correctly rejects any remaining hardcoded UI literals. No new lint rule is authored; no rule scope is expanded.

**Rationale:** Mobile #1 shipped `HomeScreen` with hardcoded `"NearYouID"` and `"v1.0"` literals because no Moko Resources module existed — the Detekt rule effectively had no real-world validation target. Mobile #2 is the first opportunity to test the rule end-to-end. If the rule mis-classifies a Moko Resources call site as a hardcoded literal, that's a rule bug to file separately (out of scope for this change).

**Alternatives considered:**

- **Expand rule scope to other modules in this change.** Out of scope — other modules (`:backend:ktor`, `:shared:distance`, etc.) don't have UI strings; expanding scope risks false positives.
- **Author a new related rule** (e.g., enforce Moko Resources for all `Text(...)` composables). Out of scope — the existing rule's coverage is sufficient; new rules go through their own dedicated changes per the project's Detekt rule-authoring precedent (`like-rate-limit` → `RateLimitTtlRule`; `coordinate-jitter-lint-rule` → `CoordinateJitterRule`).

## Risks / Trade-offs

| Risk | Mitigation |
|---|---|
| Mechanically derived dark palette looks off-brand or low-contrast in real-world rendering | Surface derived values in `design.md` (above) for user override during proposal review; track hand-tuned dark as `mobile-dark-palette-tuning` follow-up; document the trade-off in `proposal.md` § Out of Scope |
| Outline color override (`#9CA3AF` instead of palette's `#D9DDE5`) deviates from claude.ai/design output without user explicit OK | Single decision documented in this design with rationale + alternative; user can override during proposal review |
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

## Open Questions

1. **Should the in-app logo also be exposed as an Android adaptive icon source** (single source of truth, build-time generation)? *Decision deferred.* Current scaffold ships them as separate assets (Moko Resources for in-app, platform-native for launcher) per Decision 8. Revisit if maintenance burden grows.
2. **Should `NearYouTypography` ship M3 type-scale presets** (Display/Headline/Title/Body/Label) as Material 3 standard sizes + weights, or override with brand-tuned sizes? *Decision: M3-standard sizes for v1.* No brand spec exists for type scale; defer to a future "brand voice" change if visual review identifies need.
3. **Should the `monochrome` themed-icon drawable for Android 13+** ship as part of this scaffold, or defer to a follow-up? *Decision: ship in this change.* Trivial to add (vector drawable of just the hexagon glyph in solid black, the system tints based on wallpaper). No reason to defer.
4. **Is the user OK with `#9CA3AF` outline override** vs the palette's `#D9DDE5`? Surface for proposal review (this design's Decision 9).
5. **Is the user OK with the derived dark palette values** in Decision 3's table? Surface for proposal review.
6. **Compose Multiplatform `material3 = "1.3.x"` exact stable version** — pick the latest stable patch available on Maven Central at build time (e.g., `1.3.2`). Recorded in `gradle/libs.versions.toml` + Version Pinning Decisions Log.
