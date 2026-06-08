# shared-resources Specification

## Purpose

The `:shared:resources` capability provides the canonical brand-asset surface for the Compose Multiplatform mobile app, bundling Material 3 color tokens (`NearYouColorScheme` light + HCT-derived dark + reserved-purpose extension properties for location pin / Premium badge / semantic success / warning / link), typography (`NearYouTypography` built on Plus Jakarta Sans, Indonesian foundry, OFL-licensed), in-app brand logo variants (light/dark theme-switchable via `isSystemInDarkTheme()`), and the foundational Bahasa Indonesia string catalog via Compose Multiplatform Resources (`Res.string.*`, `Res.drawable.*`, `Res.font.*`). It satisfies the [`openspec/project.md`](../../project.md) § Coding Conventions invariant "Mobile strings: no hardcoded UI strings; must go through Compose Multiplatform Resources" and unblocks every subsequent mobile capability that needs branded UI surfaces (Mobile #3 auth, Mobile #4 age gate, Mobile #5 nearby timeline, and every later product screen). Brand `secondary`/`tertiary` Material 3 roles are mapped to neutral surfaceVariant tones (NOT to coral/amber) so default M3 widgets stay visually coherent; coral + amber are surgically exposed as `ColorScheme.locationPin` / `.premiumBadge` extension properties per the palette author's reserved-purpose constraint. Historical context: this capability initially shipped with Moko Resources (Mobile #2, [PR #116](https://github.com/aditrioka/nearyou-id/pull/116), archived 2026-05-27) and was swapped to Compose Multiplatform Resources one day later (Mobile #2.5, [PR #119](https://github.com/aditrioka/nearyou-id/pull/119)) — first test case for the pre-implementation library re-check rule from PR [#118](https://github.com/aditrioka/nearyou-id/pull/118).
## Requirements
### Requirement: Brand color scheme exposed as NearYouColorScheme

The `:shared:resources` module SHALL expose a `NearYouColorScheme` object (or equivalent named container) in commonMain with two Material 3 `ColorScheme` instances: `NearYouColorScheme.light` and `NearYouColorScheme.dark`. The light scheme SHALL use `primary = #1E4FD6` plus the full 30-role light palette documented in this change's [`design.md`](../../design.md) Decision 3 table. The dark scheme SHALL be mechanically derived from the light primary via the Material Theme Builder HCT tonal stop algorithm (primary tone 80, container tone 30, onPrimary tone 20, etc.) — derived values documented in [`design.md`](../../design.md) Decision 3 table.

#### Scenario: NearYouColorScheme.light has palette primary

- **WHEN** inspecting `shared/resources/src/commonMain/kotlin/id/nearyou/resources/theme/NearYouColorScheme.kt` (or equivalent commonMain path)
- **THEN** `NearYouColorScheme.light.primary` resolves to `Color(0xFF1E4FD6)` AND `NearYouColorScheme.light.onPrimary` resolves to `Color(0xFFFFFFFF)`

#### Scenario: NearYouColorScheme.light defines all required Material 3 ColorScheme roles

- **WHEN** inspecting `NearYouColorScheme.light`
- **THEN** the `ColorScheme` is constructed with explicit values for ALL of: `primary`, `onPrimary`, `primaryContainer`, `onPrimaryContainer`, `inversePrimary`, `secondary`, `onSecondary`, `secondaryContainer`, `onSecondaryContainer`, `tertiary`, `onTertiary`, `tertiaryContainer`, `onTertiaryContainer`, `background`, `onBackground`, `surface`, `onSurface`, `surfaceVariant`, `onSurfaceVariant`, `surfaceTint`, `inverseSurface`, `inverseOnSurface`, `error`, `onError`, `errorContainer`, `onErrorContainer`, `outline`, `outlineVariant`, `scrim`, `surfaceBright`, `surfaceDim`, `surfaceContainerLowest`, `surfaceContainerLow`, `surfaceContainer`, `surfaceContainerHigh`, `surfaceContainerHighest`

#### Scenario: NearYouColorScheme.dark is available

- **WHEN** inspecting `NearYouColorScheme.dark`
- **THEN** the value is a `ColorScheme` instance with the same 30+ roles as `light`, populated from the derived values in [`design.md`](../../design.md) Decision 3 table

#### Scenario: NearYouColorScheme.light maps M3 secondary to a neutral, not coral

- **WHEN** inspecting `NearYouColorScheme.light.secondary`
- **THEN** the value resolves to `Color(0xFFEEF0F4)` (the surfaceVariant family neutral per [`design.md`](../../design.md) Decision 3 table), explicitly NOT `Color(0xFFFF7A5C)` (coral) — per [`design.md`](../../design.md) Decision 2

#### Scenario: NearYouColorScheme.light maps M3 tertiary to a neutral

- **WHEN** inspecting `NearYouColorScheme.light.tertiary`
- **THEN** the value resolves to the documented neutral stop (per [`design.md`](../../design.md) Decision 3 table), explicitly NOT `Color(0xFFF4B740)` (amber) — per [`design.md`](../../design.md) Decision 2

#### Scenario: NearYouColorScheme.light outline meets M3 3:1 contrast guideline

- **WHEN** inspecting `NearYouColorScheme.light.outline`
- **THEN** the value resolves to `Color(0xFF79747E)` (the M3 default outline tone, which passes WCAG 4.5:1 against `surface = #FFFFFF` per [`design.md`](../../design.md) Decision 9), NOT the palette author's `Color(0xFFD9DDE5)` value (1.36:1, fails) and NOT the earlier proposal value `Color(0xFF9CA3AF)` (2.54:1, also fails); the palette author's `Color(0xFFD9DDE5)` is preserved on `outlineVariant` instead (purely decorative, no contrast requirement)

#### Scenario: NearYouColorScheme.light scrim is correctly encoded

- **WHEN** inspecting `NearYouColorScheme.light.scrim`
- **THEN** the value resolves to `Color(0x8F0E1220)` (alpha 0x8F ≈ 56% over the onSurface base color)

### Requirement: Reserved-purpose accents exposed as ColorScheme extension properties

The `:shared:resources` module SHALL expose the brand's reserved-purpose accent colors (coral location pin, amber Premium badge) plus semantic status colors (success, warning, link) as `androidx.compose.material3.ColorScheme` extension properties accessible at every Compose call site via `MaterialTheme.colorScheme.<name>`. Each accent SHALL ship both light + dark variants and the full container/on-color set documented in [`design.md`](../../design.md) Decision 3 extension-property table.

#### Scenario: ColorScheme.locationPin is defined

- **WHEN** inspecting `shared/resources/src/commonMain/kotlin/id/nearyou/resources/theme/ColorSchemeExtensions.kt` (or equivalent commonMain path)
- **THEN** an extension property `val ColorScheme.locationPin: Color` is declared, returning `Color(0xFFFF7A5C)` for the light scheme via `CompositionLocal` lookup (or equivalent theme-aware mechanism)

#### Scenario: ColorScheme.premiumBadge is defined

- **WHEN** inspecting commonMain
- **THEN** an extension property `val ColorScheme.premiumBadge: Color` is declared, returning `Color(0xFFF4B740)` for the light scheme via `CompositionLocal` lookup

#### Scenario: ColorScheme extension properties cover the full container/on-color set

- **WHEN** inspecting commonMain
- **THEN** the following extension properties are ALL declared: `locationPin`, `locationPinContainer`, `onLocationPin`, `onLocationPinContainer`, `premiumBadge`, `premiumBadgeContainer`, `onPremiumBadge`, `onPremiumBadgeContainer`, `success`, `onSuccess`, `successContainer`, `onSuccessContainer`, `warning`, `onWarning`, `warningContainer`, `onWarningContainer`, `link`

#### Scenario: Extension properties resolve theme-aware values

- **WHEN** a composable invokes `MaterialTheme.colorScheme.locationPin` inside a `NearYouTheme { ... }` wrapper with system dark mode = OFF
- **THEN** the resolved value is the light-scheme value (`Color(0xFFFF7A5C)` for locationPin)

- **WHEN** the same composable is rendered with system dark mode = ON
- **THEN** the resolved value is the dark-scheme value (`Color(0xFFFFB59E)` for locationPin) per [`design.md`](../../design.md) Decision 3 extension-property table

### Requirement: Brand typography exposed as NearYouTypography backed by Plus Jakarta Sans

The `:shared:resources` module SHALL bundle the Plus Jakarta Sans variable `.ttf` file (OFL-licensed) at `shared/resources/src/commonMain/composeResources/font/plus_jakarta_sans.ttf` (Compose Multiplatform Resources canonical layout) and SHALL expose a `NearYouTypography` value (`androidx.compose.material3.Typography` instance) in commonMain that applies Plus Jakarta Sans to ALL 13 Material 3 type roles. The font SHALL be loaded via the Compose Multiplatform Resources accessor `Font(Res.font.plus_jakarta_sans)` (the canonical CMP Resources font composable).

#### Scenario: Plus Jakarta Sans .ttf is bundled in Compose Resources layout

- **WHEN** inspecting `shared/resources/src/commonMain/composeResources/font/`
- **THEN** the directory contains a `plus_jakarta_sans.ttf` file (the variable-font variant from Mobile #2's bundled asset, byte-identical) AND the OFL license file is preserved (per OFL terms — the license file may live at the module root or inside the font directory; either is acceptable)

#### Scenario: NearYouTypography covers all Material 3 type roles

- **WHEN** inspecting `shared/resources/src/commonMain/kotlin/id/nearyou/resources/theme/NearYouTypography.kt` (or equivalent commonMain path)
- **THEN** the `Typography` instance assigns explicit `TextStyle` values for ALL of: `displayLarge`, `displayMedium`, `displaySmall`, `headlineLarge`, `headlineMedium`, `headlineSmall`, `titleLarge`, `titleMedium`, `titleSmall`, `bodyLarge`, `bodyMedium`, `bodySmall`, `labelLarge`, `labelMedium`, `labelSmall`

#### Scenario: All type roles use Plus Jakarta Sans via CMP Resources

- **WHEN** inspecting any `TextStyle` declared in `NearYouTypography`
- **THEN** its `fontFamily` resolves to a `FontFamily` constructed from `Font(Res.font.plus_jakarta_sans, ...)` (CMP Resources composable accessor); NO reference to `MR.fonts.*` or `asFont()` (Moko-specific accessors) appears in the file

### Requirement: Brand logo bundled in light + dark variants with palette primary

The `:shared:resources` module SHALL bundle two in-app brand logo **Android XML vector drawable** variants in `shared/resources/src/commonMain/composeResources/drawable/` (Compose Multiplatform Resources canonical layout): `logo_brand_light.xml` (blue `#1E4FD6` glyph on white background, for use on light UI backgrounds) and `logo_brand_dark.xml` (white glyph on `#1E4FD6` blue background, for use on dark UI backgrounds). Format note: CMP Resources rejects SVG on Android with `IllegalStateException: Android platform doesn't support SVG format` per JetBrains issues [#4715](https://github.com/JetBrains/compose-multiplatform/issues/4715) / [#4670](https://github.com/JetBrains/compose-multiplatform/issues/4670); Android XML vector drawables are the canonical cross-platform format (work on iOS / desktop / web too via `VectorPainter`). The two `.xml` files are functional equivalents of Mobile #2's source SVGs — same `viewBox`, same glyph geometry, same colors — converted via manual SVG-element-to-vector-drawable mapping (polyline → path with pathData, circle → arc-based path) preserving the per-variant geometric differences from the source assets. Compose call sites SHALL select the variant via `isSystemInDarkTheme()` and access via `Res.drawable.logo_brand_{light,dark}`.

#### Scenario: Both logo variants are present in composeResources/drawable/ as XML vector drawables

- **WHEN** inspecting `shared/resources/src/commonMain/composeResources/drawable/`
- **THEN** the directory contains both `logo_brand_light.xml` AND `logo_brand_dark.xml` (Android XML vector drawable format, `<vector>` root element); NO `.svg` variant of either logo remains in the directory; the Moko-convention directory `shared/resources/src/commonMain/moko-resources/images/` no longer exists OR is empty

#### Scenario: Light variant uses palette primary blue

- **WHEN** grepping `shared/resources/src/commonMain/composeResources/drawable/logo_brand_light.xml` for hex color values
- **THEN** the only blue value referenced is `#1E4FD6` (in `android:strokeColor` / `android:fillColor` attributes); NO occurrence of `#014CAB` or `#0B4FA8`

#### Scenario: Dark variant uses palette primary blue

- **WHEN** grepping `shared/resources/src/commonMain/composeResources/drawable/logo_brand_dark.xml` for hex color values
- **THEN** the only blue value referenced is `#1E4FD6` (in `android:fillColor` attribute on the background path); NO occurrence of `#0B4FA8` or `#014CAB`

#### Scenario: Both variants accessible via CMP Resources

- **WHEN** the Compose Multiplatform Resources Gradle codegen task generates the `Res` accessor class for `:shared:resources`
- **THEN** `Res.drawable.logo_brand_light` AND `Res.drawable.logo_brand_dark` are both available for consumption from `:mobile:app` commonMain via `painterResource(Res.drawable.X)`

### Requirement: Foundational Bahasa Indonesia string surface

The `:shared:resources` module SHALL provide a foundational set of Bahasa Indonesia UI strings in `shared/resources/src/commonMain/composeResources/values/strings.xml` (Compose Multiplatform Resources canonical layout — `values/` is the base locale, matching Android resource convention), accessible from commonMain via the Compose `stringResource(Res.string.<name>)` accessor. The string keys and text content of the **Mobile #2 / #2.5 and Mobile #3 foundational sets** SHALL be byte-identical to the shipped strings (this change does NOT rewrite earlier copy). The full set, with Mobile #4 additions, SHALL include at minimum:

**Mobile #2 / #2.5 foundational strings (preserved byte-identical):**
- `app_name`, `error_generic`, `cta_continue`, `cta_cancel`, `cta_retry`, `cta_close`, `loading`, `empty_state_generic`, `home_placeholder_title`, `home_placeholder_version`

**Mobile #3 sign-in flow strings (preserved byte-identical):**
- `cta_signin_google`: "Masuk dengan Google" (the user-facing primary CTA per [`docs/03-UX-Design.md`](../../../../docs/03-UX-Design.md) § Auth Flow)
- `signin_screen_title`: "Masuk ke NearYouID"
- `signin_error_no_account`: "Akun belum terdaftar. Daftar dulu lewat pembaruan aplikasi berikutnya." (Mobile #3 temporary copy; **as of Mobile #4 this string is no longer rendered on the `404` path** — the `mobile-auth-signin` `404` handler now navigates to `AgeGateScreen`. The string is retained in the catalog for now; full removal or repurpose-to-network-edge is an implementation-time decision per the `mobile-age-gate-screen` design Open Questions)
- `signin_error_banned`: "Akun kamu telah dinonaktifkan. Hubungi support jika ini keliru." (matching [`docs/03-UX-Design.md`](../../../../docs/03-UX-Design.md) § Suspension UX permanent-ban wording byte-identical)
- `signin_error_network`: "Tidak bisa terhubung. Periksa koneksi internet kamu." (REUSED by the Mobile #4 signup flow for its `5xx` / network / `503` retryable-error path — generic network copy, no new key)
- `signin_error_token_invalid`: "Sesi Google bermasalah. Coba lagi." (REUSED by the Mobile #4 signup flow for its terminal `invalid_id_token` path — generic Google-session copy, no new key)
- `signin_loading`: "Sedang masuk…"
- `account_separation_disclosure`: "Akun Google dan akun Apple terpisah. Satu identifier = satu akun NearYouID" (matching [`docs/03-UX-Design.md`](../../../../docs/03-UX-Design.md) § Auth Flow wording byte-identical)

**Mobile #4 age-gate / signup flow strings (new in this change):**
- `age_gate_title`: "Verifikasi usia kamu" (the `AgeGateScreen` title)
- `age_gate_explainer`: "NearYouID hanya untuk pengguna berusia 18 tahun ke atas. Masukkan tanggal lahir kamu untuk melanjutkan." (states the 18+ minimum clearly per the PP 17/2025 "clear minimum-age information" obligation; `docs/06-Security-Privacy.md` § Age Gate)
- `age_gate_dob_label`: "Tanggal lahir" (the date-of-birth field label)
- `age_gate_dob_picker_cta`: "Pilih tanggal lahir" (the affordance that opens the Material 3 DatePicker)
- `cta_create_account`: "Buat akun" (the primary create-account CTA)
- `age_gate_under18_blocked`: "Platform ini hanya tersedia untuk pengguna usia 18 tahun ke atas." (the generic `403 user_blocked` copy; **byte-identical** to the under-18 reject wording in [`docs/06-Security-Privacy.md`](../../../../docs/06-Security-Privacy.md) § Age Gate, [`docs/02-Product.md`](../../../../docs/02-Product.md) § Age Gate, and [`docs/03-UX-Design.md`](../../../../docs/03-UX-Design.md) § Age Gate Screen)
- `signup_error_account_exists`: "Akun sudah terdaftar. Silakan masuk." (the `409 user_exists` copy that routes the user to sign in)
- `signup_loading`: "Sedang membuat akun…" (the in-flight signup state)

Text content for all strings SHALL match the Bahasa Indonesia copy in [`docs/03-UX-Design.md`](../../../../docs/03-UX-Design.md) / [`docs/06-Security-Privacy.md`](../../../../docs/06-Security-Privacy.md) for any string that has a documented canonical wording.

#### Scenario: strings.xml is present at the expected CMP Resources path

- **WHEN** inspecting `shared/resources/src/commonMain/composeResources/values/`
- **THEN** the directory contains a `strings.xml` file; the Moko-convention directory `shared/resources/src/commonMain/moko-resources/MR/base/` no longer exists OR contains no `strings.xml`

#### Scenario: All Mobile #2 + #3 + #4 strings are declared

- **WHEN** inspecting `shared/resources/src/commonMain/composeResources/values/strings.xml`
- **THEN** the file contains `<string>` entries for ALL of: `app_name`, `error_generic`, `cta_continue`, `cta_cancel`, `cta_retry`, `cta_close`, `loading`, `empty_state_generic`, `home_placeholder_title`, `home_placeholder_version`, `cta_signin_google`, `signin_screen_title`, `signin_error_no_account`, `signin_error_banned`, `signin_error_network`, `signin_error_token_invalid`, `signin_loading`, `account_separation_disclosure`, `age_gate_title`, `age_gate_explainer`, `age_gate_dob_label`, `age_gate_dob_picker_cta`, `cta_create_account`, `age_gate_under18_blocked`, `signup_error_account_exists`, `signup_loading`

#### Scenario: Mobile #2 strings remain byte-identical to shipped content

- **WHEN** reading the `<string name="error_generic">` value
- **THEN** the text is `"Ada yang salah. Coba lagi sebentar."` (matching Mobile #2's shipped content exactly — this change does NOT rewrite copy)

- **WHEN** reading the `<string name="cta_cancel">` value
- **THEN** the text is `"Batal"` (matching the user-facing label canonical in [`docs/03-UX-Design.md`](../../../../docs/03-UX-Design.md))

#### Scenario: Mobile #3 sign-in strings carry the canonical Bahasa Indonesia copy

- **WHEN** reading the `<string name="cta_signin_google">` value
- **THEN** the text is exactly `"Masuk dengan Google"` (matching [`docs/03-UX-Design.md`](../../../../docs/03-UX-Design.md) § Auth Flow paragraph beginning `1. Android: "Masuk dengan Google"`)

- **WHEN** reading the `<string name="signin_error_banned">` value
- **THEN** the text is exactly `"Akun kamu telah dinonaktifkan. Hubungi support jika ini keliru."` (matching [`docs/03-UX-Design.md`](../../../../docs/03-UX-Design.md) § Suspension UX byte-identical)

- **WHEN** reading the `<string name="account_separation_disclosure">` value
- **THEN** the text is exactly `"Akun Google dan akun Apple terpisah. Satu identifier = satu akun NearYouID"` (matching [`docs/03-UX-Design.md`](../../../../docs/03-UX-Design.md) § Auth Flow paragraph beginning `**Account separation disclosure**` byte-identical)

- **WHEN** reading the `<string name="signin_error_network">` value
- **THEN** the text is exactly `"Tidak bisa terhubung. Periksa koneksi internet kamu."`

- **WHEN** reading the `<string name="signin_error_token_invalid">` value
- **THEN** the text is exactly `"Sesi Google bermasalah. Coba lagi."`

- **WHEN** reading the `<string name="signin_error_no_account">` value
- **THEN** the text is exactly `"Akun belum terdaftar. Daftar dulu lewat pembaruan aplikasi berikutnya."` (per the `mobile-auth-google-signin-flow` Decision 7 temporary copy)

- **WHEN** reading the `<string name="signin_screen_title">` value
- **THEN** the text is exactly `"Masuk ke NearYouID"`

- **WHEN** reading the `<string name="signin_loading">` value
- **THEN** the text is exactly `"Sedang masuk…"`

#### Scenario: Mobile #4 age-gate strings carry the canonical Bahasa Indonesia copy

- **WHEN** reading the `<string name="age_gate_under18_blocked">` value
- **THEN** the text is exactly `"Platform ini hanya tersedia untuk pengguna usia 18 tahun ke atas."` (byte-identical to the under-18 reject copy in [`docs/06-Security-Privacy.md`](../../../../docs/06-Security-Privacy.md) § Age Gate, [`docs/02-Product.md`](../../../../docs/02-Product.md) § Age Gate, and [`docs/03-UX-Design.md`](../../../../docs/03-UX-Design.md) § Age Gate Screen)

- **WHEN** reading the `<string name="cta_create_account">` value
- **THEN** the text is exactly `"Buat akun"`

- **WHEN** reading the `<string name="signup_error_account_exists">` value
- **THEN** the text is exactly `"Akun sudah terdaftar. Silakan masuk."`

- **WHEN** reading the `<string name="age_gate_title">` value
- **THEN** the text is exactly `"Verifikasi usia kamu"`

#### Scenario: home_placeholder_version supports format substitution

- **WHEN** reading the `<string name="home_placeholder_version">` value
- **THEN** the text contains exactly one `%1$s` placeholder so the rendered version string can be supplied at composition time via `stringResource(Res.string.home_placeholder_version, "1.0")`

### Requirement: App launcher icon replaces wizard-default assets with NearYouID-branded variants

The `:mobile:app` module SHALL ship NearYouID-branded launcher icons by **replacing in-place** Mobile #1's existing JetBrains-wizard-default launcher assets (no file additions to net-new mipmap locations). Both platforms maintain their existing structural conventions — Android uses adaptive icon + raster fallbacks; iOS uses the modern single-1024 universal Asset Catalog idiom (NOT the legacy multi-size pattern). The `androidMain` / default-scheme assets described here are the **production / base** brand identity; non-production builds override the icon **background** per the *Launcher icon background is environment-differentiated* requirement below, while reusing every other asset (foreground glyph, monochrome layer, raster fallbacks) unchanged.

**Android** SHALL ship: (a) replaced `mipmap-anydpi-v26/ic_launcher.xml` + `ic_launcher_round.xml` adaptive icons with foreground = white hexagon glyph vector drawable + background = `@color/ic_launcher_background` (`#1E4FD6` in `values/colors.xml`); (b) replaced `drawable/ic_launcher_background.xml` (Mobile #1 shipped a wizard vector gradient there — this change converts it to a color reference) + replaced `drawable-v24/ic_launcher_foreground.xml` (Mobile #1 shipped the wizard vector); (c) regenerated 10 raster fallback PNGs in `mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png` + `ic_launcher_round.png` (LOAD-BEARING under `min-sdk = 24` for Android 7.x devices that ignore the adaptive XML); (d) NEW `drawable/ic_launcher_monochrome.xml` for the Android 13+ themed-icon `<monochrome>` attribute; (e) NEW blue-on-white alternate (`drawable/ic_launcher_foreground_alt.xml` + `mipmap-anydpi-v26/ic_launcher_alt.xml`) wired via dormant `<activity-alias>` in `AndroidManifest.xml`.

**iOS** SHALL ship: replaced `iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/app-icon-1024.png` with 3 NearYouID-branded 1024×1024 PNG variants (default + `luminosity:dark` + `luminosity:tinted`) rasterized from the modified SVG via `dev/scripts/generate-ios-app-icons.sh` (which uses `rsvg-convert` with `pdftocairo` fallback). The existing `Contents.json` shape (modern iOS 14+ single-size universal idiom with appearance variants) is preserved — only the PNG bytes change.

#### Scenario: Android adaptive icon files retain their existing paths

- **WHEN** inspecting `mobile/app/src/androidMain/res/mipmap-anydpi-v26/`
- **THEN** the directory contains `ic_launcher.xml` AND `ic_launcher_round.xml` (both replaced in-place — same filenames as Mobile #1) AND a NEW `ic_launcher_alt.xml` for the blue-on-white alternate

#### Scenario: Android adaptive icon background is brand primary

- **WHEN** inspecting `mobile/app/src/androidMain/res/values/colors.xml`
- **THEN** the file contains `<color name="ic_launcher_background">#1E4FD6</color>`

#### Scenario: Android adaptive icon foreground vector drawable is replaced and monochrome glyph is present

- **WHEN** inspecting `mobile/app/src/androidMain/res/drawable/` and `drawable-v24/`
- **THEN** `drawable-v24/ic_launcher_foreground.xml` exists (replaced in-place from Mobile #1's wizard default, rendering the white hexagon glyph) AND `drawable/ic_launcher_monochrome.xml` exists (NEW, referenced by `ic_launcher.xml`'s `<monochrome>` attribute for Android 13+ themed-icon support)

#### Scenario: Android raster fallback PNGs are regenerated for legacy Android 7.x

- **WHEN** inspecting `mobile/app/src/androidMain/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/`
- **THEN** each density directory contains both `ic_launcher.png` AND `ic_launcher_round.png` (10 PNGs total — replaced in-place from Mobile #1's wizard defaults), each rasterized at the density-appropriate resolution from the modified white-on-blue brand SVG

#### Scenario: iOS Asset Catalog preserves modern single-1024 universal idiom

- **WHEN** inspecting `iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/Contents.json`
- **THEN** the file declares 3 entries with `idiom = "universal"`, `size = "1024x1024"`, `platform = "ios"` — one default + one with `appearance = "luminosity" / value = "dark"` + one with `appearance = "luminosity" / value = "tinted"`; the `Contents.json` shape matches Mobile #1's shipped structure (only the PNG bytes change)

#### Scenario: iOS launcher icon PNG is replaced with NearYouID brand variants

- **WHEN** inspecting `iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/`
- **THEN** `app-icon-1024.png` is present (replaced from Mobile #1's wizard default, rasterized from the modified brand SVG) AND 2 additional 1024×1024 PNGs corresponding to the `luminosity:dark` and `luminosity:tinted` Contents.json entries are present

#### Scenario: iOS icon generation script is present and supports both rasterizers

- **WHEN** inspecting `dev/scripts/generate-ios-app-icons.sh`
- **THEN** the script is executable AND auto-detects either `rsvg-convert` (preferred) or `pdftocairo` (fallback) AND produces exactly 3 1024×1024 PNGs (default + dark + tinted variants) AND fails with a clear error if neither rasterizer is available

### Requirement: No hardcoded UI strings in :mobile:app verified by grep (Detekt rule deferred)

After this change is applied, the "no hardcoded UI strings in mobile source" convention from [`openspec/project.md`](../../project.md) § Coding Conventions SHALL be verified via an **explicit grep step** documented in this change's `tasks.md` Section 8 — NOT via a Detekt rule (the rule does not yet exist in `:lint:detekt-rules` and is still tracked as a `FOLLOW_UPS.md` entry `mobile-negative-requirement-ci-grep` (proposing future OpenSpec change `mobile-negative-requirement-detekt-rule`)). Every UI string in `mobile/app/src/commonMain/`, `mobile/app/src/androidMain/`, and `mobile/app/src/iosMain/` SHALL be sourced via Compose Multiplatform Resources (`stringResource(Res.string.<name>)` Compose accessor), with no remaining hardcoded UI string literals. The future upgrade to a real Detekt rule SHALL be tracked by the existing `FOLLOW_UPS.md` entry (retargeted to accept `Res.string.X` instead of `MR.strings.X` as part of this change).

#### Scenario: Grep verification reports zero hardcoded UI string literals

- **WHEN** running the documented grep step from `tasks.md` Section 8 against `mobile/app/src/commonMain/`, `mobile/app/src/androidMain/`, and `mobile/app/src/iosMain/`
- **THEN** the grep finds zero offending matches (i.e., zero `Text("...")` / `Text(text = "...")` / `contentDescription = "..."` literal-string call sites that are not flowing through `stringResource(Res.string.X)` or `Res.string.X` direct access or an explicitly annotated `// hardcoded-string-allow:` line); the grep exit code is 0

#### Scenario: FOLLOW_UPS.md tracks the Detekt rule upgrade with retargeted accessor

- **WHEN** inspecting `FOLLOW_UPS.md` (in the repository root) after this change is applied
- **THEN** the `mobile-negative-requirement-ci-grep` (proposing future OpenSpec change `mobile-negative-requirement-detekt-rule`) entry (or equivalent kebab-case identifier) notes that the grep-based verification in this change should eventually be replaced by a `:lint:detekt-rules` rule modeled on the existing `RawFromPostsRule` / `BlockExclusionJoinRule` precedent — AND the entry's example accessor pattern references `Res.string.X` (CMP Resources), NOT the legacy `MR.strings.X` (Moko Resources) wording Mobile #2 originally used

### Requirement: ColorScheme extension properties throw outside NearYouTheme scope

If a composable accesses `MaterialTheme.colorScheme.locationPin` (or any other NearYouColors-backed extension property) without being wrapped in a `NearYouTheme { ... }` provider, the extension property SHALL throw a clear runtime error rather than silently returning a default value. The `staticCompositionLocalOf<NearYouColors>` declaration in `ColorSchemeExtensions.kt` SHALL use `error("NearYouTheme not applied")` as the default-value lambda, NOT a fabricated default `NearYouColors` instance.

#### Scenario: Accessing locationPin outside NearYouTheme throws

- **WHEN** a `commonTest` runs `runComposeUiTest { setContent { Text("${MaterialTheme.colorScheme.locationPin}") } }` (no `NearYouTheme` wrapper)
- **THEN** the composition fails with an `IllegalStateException` whose message contains "NearYouTheme not applied" (or equivalent), proving that the absent `CompositionLocal` provider raises a loud error instead of returning a silent default

### Requirement: Plus Jakarta Sans falls back to platform sans-serif when font loading fails

The brand theme SHALL load Plus Jakarta Sans via the **canonical JetBrains CMP Resources preload pattern**: a `FontFamilyResolver.preload(brandFamily)` call inside a `LaunchedEffect` coroutine scope (where `try`/`catch` is legal — `preload()` is `suspend` and the Compose-compiler invariant against exception-catching around `@Composable` calls does NOT apply to suspend coroutines). On preload failure (`MissingResourceException` / `IllegalStateException` / any throwable), state flips to a `brandFontFailed` flag and recomposition supplies vanilla `Typography()` so platform sans-serif renders text reliably. This pattern preserves the Mobile #2 spec contract ("font issues never reach users as blank/crashed text") while honoring Compose's runtime constraints + the documented JetBrains-canonical pattern per [CMP Resources usage docs](https://kotlinlang.org/docs/multiplatform/compose-multiplatform-resources-usage.html) and the precedent of real-world bundled-font runtime failures in [JetBrains issue #4111](https://github.com/JetBrains/compose-multiplatform/issues/4111), [#3472](https://github.com/JetBrains/compose-multiplatform/issues/3472), [#4387](https://github.com/JetBrains/compose-multiplatform/issues/4387) (build-time codegen validation alone does NOT eliminate runtime-missing-font failure mode).

The Moko-era in-function null-guard pattern (`if (brandFont == null) return Typography()` inside `nearYouTypography()`) is REMOVED — CMP's `Font(...)` is `@Composable` and returns non-null, so the null check is impossible AND irrelevant. The defensive responsibility moves UP the stack to `NearYouTheme` where the suspend-scope preload allows proper exception capture.

#### Scenario: brandFontFamily is exposed as a Composable helper

- **WHEN** inspecting `shared/resources/src/commonMain/kotlin/id/nearyou/resources/theme/NearYouTypography.kt`
- **THEN** the file declares `@Composable fun brandFontFamily(): FontFamily = FontFamily(Font(Res.font.plus_jakarta_sans))` (or equivalent shape exposing the brand `FontFamily` as a `@Composable` so the caller can preload it); the Font construction is NOT wrapped in `runCatching` / `try` / `catch` (forbidden by Compose's no-exception-catching-around-Composable invariant)

#### Scenario: nearYouTypography is a pure transformation taking the family as a parameter

- **WHEN** inspecting the `nearYouTypography(brandFamily: FontFamily): Typography` signature
- **THEN** the function takes a `FontFamily` parameter (not constructed in-function); all 13 Material 3 type roles (`displayLarge` through `labelSmall`) have `fontFamily` set to `brandFamily` via `.copy(fontFamily = brandFamily)`; the function is a pure transformation with no font-loading concerns of its own

#### Scenario: NearYouTheme preloads the brand font in a LaunchedEffect with try-catch fallback

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/theme/NearYouTheme.kt`
- **THEN** the `NearYouTheme` composable contains: (1) `val resolver = LocalFontFamilyResolver.current` to access the platform's font resolver; (2) `val brandFamily = brandFontFamily()` to construct the family; (3) `LaunchedEffect(brandFamily) { try { resolver.preload(brandFamily); brandFontReady = true } catch (_: Throwable) { brandFontFailed = true } }` (or equivalent suspend-scope try/catch) so the preload failure is captured at the coroutine level, NOT at composition level; (4) state-conditional `typography` selection — `nearYouTypography(brandFamily)` only when preload completed successfully, `Typography()` (vanilla / platform sans-serif fallback) otherwise

#### Scenario: Font load failure produces vanilla Typography fallback, NOT a crash

- **WHEN** the `FontFamilyResolver.preload(brandFamily)` call throws (e.g., `MissingResourceException` or `IllegalStateException` from `FontFamilyResolver` per JetBrains issues #4111 / #3472 / #4387)
- **THEN** the caught exception sets `brandFontFailed = true`; the next recomposition uses `Typography()` (platform sans-serif); text in the app continues rendering — the app does NOT crash; the failure should still surface in logs (Compose's default failure log) for diagnosis

### Requirement: home_placeholder_version format substitution renders correctly at runtime

The `Res.string.home_placeholder_version` string with format placeholder (declared as `Versi %1$s` in `strings.xml`) SHALL render `"Versi 1.0"` (or whatever runtime version is supplied) when invoked via `stringResource(Res.string.home_placeholder_version, "1.0")` on both Android and iOS targets. The XML-level shape assertion (`%1$s` placeholder present) is necessary but not sufficient — a separate runtime test SHALL exercise the substitution to catch subtle CMP Resources format-string differences between Android (`String.format`-style) and iOS (`NSString stringWithFormat:`-style) platform delegations.

#### Scenario: Runtime substitution test renders Versi 1.0

- **WHEN** a `commonTest` invokes `stringResource(Res.string.home_placeholder_version, "1.0")` from inside a Compose UI test composition (`runComposeUiTest { setContent { Text(stringResource(...)) } }`)
- **THEN** the returned string equals `"Versi 1.0"` exactly (NOT `"Versi %1$s"` literal); the runtime substitution works on both platforms

### Requirement: shared:resources namespace does not collide with mobile:app

The `:shared:resources` module's Android library `namespace` SHALL be set to `id.nearyou.resources` (or any string that is NOT `id.nearyou.app`), so the merged Android `R.class` produced when `:mobile:app` consumes `:shared:resources` does not collide. The R-class generation merges resource namespaces; collision causes a fatal `R.class` merge error at the AGP merge step.

#### Scenario: Android namespace declaration is distinct from :mobile:app

- **WHEN** inspecting `shared/resources/build.gradle.kts` `android { namespace = ... }` block
- **THEN** the namespace value is `"id.nearyou.resources"` (or any other string distinct from `:mobile:app`'s `id.nearyou.app` namespace per its existing `build.gradle.kts`)

#### Scenario: AGP merge step does not report R-class collision

- **WHEN** running `./gradlew :mobile:app:processDebugResources` (which merges `:shared:resources`'s resources into `:mobile:app`'s)
- **THEN** the task completes with exit 0 — no `R class duplication` / `resource merge conflict` error is reported

### Requirement: Shared resources Gradle module exists with Compose Multiplatform Resources

The repository SHALL contain a Gradle module `:shared:resources` declared in [`settings.gradle.kts`](../../../../settings.gradle.kts), with a `build.gradle.kts` configuring the Compose Multiplatform plugin (`org.jetbrains.compose`) — which provides the built-in `compose-components-resources` resource subsystem — and the Kotlin Multiplatform plugin with `commonMain`, `androidMain` (via `androidTarget()`), and iOS source sets (`iosArm64`, `iosSimulatorArm64`) matching the iOS target set declared by `:mobile:app`. The `dev.icerock.mobile.multiplatform-resources` (Moko) plugin SHALL NOT be applied. The module SHALL be added to [`dev/module-descriptions.txt`](../../../../dev/module-descriptions.txt) with a one-line description, and the root [`README.md`](../../../../README.md) § What's in this repo block SHALL be auto-regenerated via `dev/scripts/sync-readme.sh --write` per [`openspec/project.md`](../../project.md) § Documentation Maintenance.

#### Scenario: Module is registered in settings.gradle.kts

- **WHEN** inspecting `settings.gradle.kts`
- **THEN** the file includes `include(":shared:resources")` (or equivalent typesafe accessor declaration) within the `if (includeMobile.toBoolean()) { ... }` block

#### Scenario: Compose Multiplatform plugin is applied; Moko Resources plugin is NOT applied

- **WHEN** inspecting `shared/resources/build.gradle.kts`
- **THEN** the file applies the `org.jetbrains.compose` Gradle plugin (or `libs.plugins.composeMultiplatform` typesafe alias) AND does NOT apply `dev.icerock.mobile.multiplatform-resources` (no `libs.plugins.mokoResources` alias, no raw plugin ID reference, no `multiplatformResources { ... }` Moko-specific DSL block)

#### Scenario: compose-components-resources is wired in commonMain

- **WHEN** inspecting `shared/resources/build.gradle.kts`'s `commonMain.dependencies { ... }` block
- **THEN** the dependency `libs.compose.components.resources` (or equivalent typesafe accessor for `org.jetbrains.compose.components:components-resources`) is declared as `implementation(...)`; the Moko Resources library entries (`libs.moko.resources` / `libs.moko.resources.compose`) are NOT declared anywhere in the file

#### Scenario: Module description and README are in sync

- **WHEN** running `dev/scripts/sync-readme.sh --check`
- **THEN** the script exits with code 0 (no drift) — the `:shared:resources` entry appears in both `dev/module-descriptions.txt` AND the README's auto-generated module block between the `<!-- AUTOGEN:modules:start -->` / `<!-- AUTOGEN:modules:end -->` sentinels

#### Scenario: Common, Android, and iOS targets configured

- **WHEN** inspecting `shared/resources/build.gradle.kts`
- **THEN** the Kotlin Multiplatform extension declares `androidTarget()`, `iosArm64()`, AND `iosSimulatorArm64()` targets — matching the iOS target set declared by `:mobile:app`'s consumer — with `commonMain` as the shared source set

### Requirement: Reuse existing Compose Multiplatform material3 pin; remove moko-resources pin

The repository SHALL NOT introduce a new `material3` version pin in [`gradle/libs.versions.toml`](../../../../gradle/libs.versions.toml). The existing pin `material3 = "1.10.0-alpha05"` (`org.jetbrains.compose.material3:material3`, Compose Multiplatform stream) is reused as-is — it already exposes the full `ColorScheme` constructor surface this change requires. The repository SHALL REMOVE the `moko-resources` version pin + library entries + plugin entry that Mobile #2 added (per Mobile #2 `shared-resources-moko-bootstrap` design.md Decision-1-equivalent). The `compose-components-resources` library entry (already pinned via `composeMultiplatform = "1.10.3"` version variable, currently unused) becomes the actively-wired resources library — no new top-level version pin is introduced (the version is inherited from the existing `composeMultiplatform` variable).

#### Scenario: Existing material3 pin is preserved untouched

- **WHEN** inspecting `gradle/libs.versions.toml`
- **THEN** the `[versions]` section contains exactly one `material3` entry valued `"1.10.0-alpha05"` (preserved from Mobile #1's shipped state) AND no new `material3-jetpack` / `material3-android` / similar Jetpack-stream variant entry has been added

#### Scenario: moko-resources entries are removed from libs.versions.toml

- **WHEN** inspecting `gradle/libs.versions.toml`
- **THEN** the `[versions]` section contains NO `moko-resources` entry, the `[libraries]` section contains NO `moko-resources` or `moko-resources-compose` entries, and the `[plugins]` section contains NO `mokoResources` entry

#### Scenario: compose-components-resources entry is preserved (unchanged shape, now actively wired)

- **WHEN** inspecting `gradle/libs.versions.toml`
- **THEN** the `[libraries]` section contains `compose-components-resources = { module = "org.jetbrains.compose.components:components-resources", version.ref = "composeMultiplatform" }` — same shape as Mobile #2 left it, but the catalog accessor is now referenced from `:shared:resources/build.gradle.kts` (no longer dormant)

#### Scenario: Version Pinning Decisions Log records the swap

- **WHEN** inspecting `docs/09-Versions.md` § Version Decisions table
- **THEN** the table contains an entry documenting the moko-resources → CMP Resources swap on date 2026-05-28+, citing PR [#118](https://github.com/aditrioka/nearyou-id/pull/118)'s pre-implementation library re-check rule as the trigger; the previous Moko Resources row from Mobile #2 is either AMENDED with a "SUPERSEDED 2026-05-28" note OR removed, with the new swap row supplying the historical context either way

### Requirement: app_name string coexists with platform-native Android strings.xml app_name

`:shared:resources` SHALL declare `app_name` in its Compose Resources `strings.xml` (for in-app Compose consumption via `stringResource(Res.string.app_name)`). The pre-existing platform-native Android resource `mobile/app/src/androidMain/res/values/strings.xml` `<string name="app_name">NearYouID</string>` (referenced by `AndroidManifest.xml` `android:label`) SHALL be PRESERVED in place — Android requires it for the launcher label and cannot consume Compose Multiplatform Resources from the manifest. These two `app_name` resources are intentional parallel surfaces (CMP Resources = in-app UI; platform-native = launcher label); both SHALL hold the same text content (`"NearYouID"`) so the user experience is consistent.

#### Scenario: Platform-native Android app_name is preserved

- **WHEN** inspecting `mobile/app/src/androidMain/res/values/strings.xml`
- **THEN** the file still contains `<string name="app_name">NearYouID</string>` — this change does NOT remove the platform-native string (the `AndroidManifest.xml` `android:label="@string/app_name"` reference would break otherwise)

#### Scenario: Both app_name resources hold identical text

- **WHEN** comparing `mobile/app/src/androidMain/res/values/strings.xml` `app_name` AND `shared/resources/src/commonMain/composeResources/values/strings.xml` `app_name`
- **THEN** both resolve to the exact same text value `"NearYouID"`; drift would produce a confusing UX where the launcher label and in-app brand identifier diverge

### Requirement: Nearby-timeline Bahasa Indonesia strings

The `:shared:resources` module SHALL additionally provide the following Bahasa Indonesia UI strings in `shared/resources/src/commonMain/composeResources/values/strings.xml`, accessible from commonMain via `stringResource(Res.string.<name>)`. These are additive to the existing § "Foundational Bahasa Indonesia string surface" set (which is unchanged); no earlier string key or text is altered. The strings with a documented canonical wording SHALL match that wording byte-identically; the rate-limit strings are derived copy (consistent with the Mobile #3/#4 register) pending UX review.

- `timeline_nearby_title`: "Post dari lokasi ini" (the Nearby top-bar title — byte-identical to the "Timeline header" copy in `docs/02-Product.md` § UX Copy Strategy (Avoid Misinterpretation))
- `timeline_loading`: "Sedang memuat postingan…" (the loading state — byte-identical to `docs/03-UX-Design.md` § Empty State loading-skeleton copy)
- `timeline_empty_nearby`: "Area kamu belum ramai. Sementara lihat dari seluruh Indonesia dulu?" (the sparse-area empty state — byte-identical to `docs/03-UX-Design.md` § Empty State "Nearby is sparse")
- `timeline_limit_hard`: derived Bahasa Indonesia copy for the rolling-hourly read-cap reached state (`upsell.hard`) — flagged for UX review
- `timeline_limit_soft`: derived Bahasa Indonesia copy for the non-blocking session soft-cap nudge (`upsell.soft`) — flagged for UX review

The `home_placeholder_title` and `home_placeholder_version` strings SHALL be RETAINED in `strings.xml` (no longer rendered by `HomeScreen`, but kept in the catalog — consistent with the retention of `signin_error_no_account` after Mobile #4 stopped rendering it). This change does NOT remove them.

#### Scenario: Nearby-timeline strings are declared
- **WHEN** inspecting `shared/resources/src/commonMain/composeResources/values/strings.xml`
- **THEN** the file contains `<string>` entries for ALL of: `timeline_nearby_title`, `timeline_loading`, `timeline_empty_nearby`, `timeline_limit_hard`, `timeline_limit_soft`

#### Scenario: Docs-canonical Nearby strings are byte-identical
- **WHEN** reading the `<string name="timeline_empty_nearby">` value
- **THEN** the text is exactly `"Area kamu belum ramai. Sementara lihat dari seluruh Indonesia dulu?"` (byte-identical to `docs/03-UX-Design.md` § Empty State)

- **WHEN** reading the `<string name="timeline_loading">` value
- **THEN** the text is exactly `"Sedang memuat postingan…"` (byte-identical to `docs/03-UX-Design.md` § Empty State loading skeleton)

- **WHEN** reading the `<string name="timeline_nearby_title">` value
- **THEN** the text is exactly `"Post dari lokasi ini"` (byte-identical to the "Timeline header" line in `docs/02-Product.md` § UX Copy Strategy (Avoid Misinterpretation))

#### Scenario: Foundational set and home_placeholder strings are preserved
- **WHEN** inspecting `strings.xml` after this change
- **THEN** every string declared by the § "Foundational Bahasa Indonesia string surface" requirement remains present and byte-identical — including `home_placeholder_title` and `home_placeholder_version` (retained though no longer rendered) — AND no earlier string's text is altered

### Requirement: Launcher icon background is environment-differentiated

The mobile app launcher icon background SHALL be **environment-differentiated** so `dev` / `staging` / `production` builds are distinguishable at a glance on a device, while the **foreground glyph is identical across all environments** (the brand stays recognizable; only the environment cue changes). The background colors SHALL be: `production` → `#1E4FD6` (brand cobalt, inherited base — no override), `staging` → `#C2410C` (burnt orange), `dev` → `#15803D` (forest green). Each non-production background SHALL preserve at least 4.5:1 WCAG contrast against the white foreground glyph.

On **Android** the differentiation SHALL be delivered via gradle product-flavor resource overrides (`mobile/app/src/<flavor>/res/values/colors.xml` overriding `ic_launcher_background`), with `production` adding no override so it inherits the `androidMain` value. On **iOS** the differentiation SHALL be delivered via a per-build-configuration Asset-Catalog selection (`ASSETCATALOG_COMPILER_APPICON_NAME` resolved from each configuration's env xcconfig — with NO `ASSETCATALOG_COMPILER_APPICON_NAME` hardcode in `project.pbxproj`), with dedicated `AppIcon-Staging.appiconset` (`#C2410C`) and `AppIcon-Dev.appiconset` (`#15803D`) sets; the **production build configuration resolves the existing `AppIcon` (cobalt) via `Production.xcconfig`** (per the `mobile-ios-build-config-matrix` env × build-type matrix). The dormant `MainActivityAlt` alternate icon SHALL NOT be touched by this differentiation.

#### Scenario: Android dev flavor overrides launcher background to forest green

- **WHEN** inspecting `mobile/app/src/dev/res/values/colors.xml`
- **THEN** the file contains `<color name="ic_launcher_background">#15803D</color>`

#### Scenario: Android staging flavor overrides launcher background to burnt orange

- **WHEN** inspecting `mobile/app/src/staging/res/values/colors.xml`
- **THEN** the file contains `<color name="ic_launcher_background">#C2410C</color>`

#### Scenario: Android production inherits the brand-primary background with no flavor override

- **WHEN** inspecting the `mobile/app/src/` tree for a `production` flavor `res/values/colors.xml`
- **THEN** there is NO `production`-flavor override of `ic_launcher_background`, so the `production` flavor resolves the `androidMain` value `#1E4FD6`

#### Scenario: Resolved Android launcher background differs per flavor at resource-merge time

- **WHEN** the merged Android resources are produced for each flavor (e.g., via the `process{Dev,Staging,Production}DebugResources` tasks or an equivalent merged-`colors.xml` inspection of the variant's intermediates)
- **THEN** the resolved `ic_launcher_background` equals `#15803D` for `dev`, `#C2410C` for `staging`, AND `#1E4FD6` for `production`

#### Scenario: Foreground glyph is identical across environments (only the background changes)

- **WHEN** comparing the foreground drawable referenced by each flavor's adaptive icon (`mipmap-anydpi-v26/ic_launcher.xml`)
- **THEN** every flavor references the same `@drawable/ic_launcher_foreground` (white hexagon glyph) — no flavor source set overrides the foreground, the monochrome layer, or the adaptive-icon XML; only `ic_launcher_background` differs between flavors

#### Scenario: iOS dev/staging builds select env-tinted AppIcons; production resolves cobalt via xcconfig

- **WHEN** building the iOS app under a `dev`-environment vs a `staging`-environment vs a `production`-environment build configuration (per the `mobile-ios-build-config-matrix` matrix)
- **THEN** a staging configuration's `ASSETCATALOG_COMPILER_APPICON_NAME` resolves to `AppIcon-Staging` (from `iosApp/Configuration/Staging.xcconfig`) AND `iosApp/iosApp/Assets.xcassets/AppIcon-Staging.appiconset/` exists containing a single universal 1024×1024 PNG whose background is the staging tint `#C2410C`; a `dev` configuration's resolves to `AppIcon-Dev` (from `iosApp/Configuration/Dev.xcconfig`) AND `iosApp/iosApp/Assets.xcassets/AppIcon-Dev.appiconset/` exists containing a single universal 1024×1024 PNG whose background is the dev tint `#15803D`; a production configuration resolves `AppIcon` (cobalt) from `iosApp/Configuration/Production.xcconfig` — and there is **NO `ASSETCATALOG_COMPILER_APPICON_NAME` hardcode in `project.pbxproj`** (the icon resolves only from each configuration's xcconfig); all verified via `xcodebuild -showBuildSettings` for the respective configuration

#### Scenario: Pre-API-26 Android raster fallback is shared, not per-flavor (accepted limitation)

- **WHEN** a non-production flavor is installed on an Android API 24–25 device that ignores the adaptive `mipmap-anydpi-v26` XML and falls back to a raster `mipmap-*/ic_launcher.png`
- **THEN** the raster fallback renders the shared `androidMain` cobalt icon for every flavor (the per-environment tint applies only to adaptive-icon-capable API 26+ devices); this change intentionally does NOT regenerate per-flavor raster PNGs, and the limitation is documented in `design.md`

#### Scenario: The dormant alternate icon is not modified by environment differentiation

- **WHEN** inspecting `mobile/app/src/androidMain/res/mipmap-anydpi-v26/ic_launcher_alt.xml` and the `MainActivityAlt` `<activity-alias>` in `mobile/app/src/androidMain/AndroidManifest.xml`
- **THEN** both are unchanged by this change — the environment differentiation does not touch the reserved user-selectable alternate icon

### Requirement: Analytics-consent Bahasa Indonesia strings

The `:shared:resources` module SHALL declare the Bahasa Indonesia UI strings for the analytics-consent onboarding screen in `shared/resources/src/commonMain/composeResources/values/strings.xml`, accessible via `stringResource(Res.string.<name>)`. No earlier (Mobile #2–#7) string SHALL be altered. The three per-category **description** strings SHALL be **byte-identical** to the data-summary copy in [`docs/03-UX-Design.md`](../../../../docs/03-UX-Design.md) § "Analytics & Tracking Consent Screen (UU PDP)" (the only consent strings the doc pins verbatim); the title, explainer, labels, CTA, error, and skip strings are new wording consistent with the doc's intent. The set SHALL include:

- `consent_title`: "Privasi & data" (the `ConsentScreen` title)
- `consent_explainer`: "Pilih data yang boleh kami kumpulkan untuk meningkatkan NearYouID. Kamu bisa mengubahnya kapan saja di Pengaturan." (states that the choice is changeable later, per the doc's "Settings page allows the user to change the toggle")
- `consent_analytics_label`: "Analitik penggunaan" (the Analytics toggle label)
- `consent_analytics_desc`: "Bantu kami perbaiki aplikasi dengan data penggunaan anonim (Amplitude)" (**byte-identical** to `docs/03-UX-Design.md`)
- `consent_crash_label`: "Laporan crash" (the Crash Reporting toggle label)
- `consent_crash_desc`: "Laporkan crash otomatis untuk perbaikan bug (Sentry)" (**byte-identical** to `docs/03-UX-Design.md`)
- `consent_ads_label`: "Personalisasi iklan" (the Ads Personalization toggle label)
- `consent_ads_desc`: "Iklan dapat disesuaikan dengan minat kamu (Google AdMob UMP)" (**byte-identical** to `docs/03-UX-Design.md`)
- `consent_cta_continue`: "Simpan & lanjutkan" (the primary continue CTA — names the persistence action)
- `consent_error_retryable`: "Gagal menyimpan preferensi. Coba lagi." (the retryable submit-error copy)
- `consent_skip`: "Lewati untuk sekarang" (the proceed-anyway affordance shown only after a failed submit, per the `mobile-analytics-consent` non-trapping requirement)

#### Scenario: All analytics-consent strings are declared at the CMP Resources path

- **WHEN** inspecting `shared/resources/src/commonMain/composeResources/values/strings.xml`
- **THEN** the file contains `<string>` entries for ALL of: `consent_title`, `consent_explainer`, `consent_analytics_label`, `consent_analytics_desc`, `consent_crash_label`, `consent_crash_desc`, `consent_ads_label`, `consent_ads_desc`, `consent_cta_continue`, `consent_error_retryable`, `consent_skip`

#### Scenario: The three category descriptions are byte-identical to docs/03-UX-Design.md

- **WHEN** comparing `consent_analytics_desc`, `consent_crash_desc`, and `consent_ads_desc` against the data-summary bullets in `docs/03-UX-Design.md` § "Analytics & Tracking Consent Screen (UU PDP)"
- **THEN** each string's text is byte-identical to its corresponding documented bullet ("Bantu kami perbaiki aplikasi dengan data penggunaan anonim (Amplitude)", "Laporkan crash otomatis untuk perbaikan bug (Sentry)", "Iklan dapat disesuaikan dengan minat kamu (Google AdMob UMP)")

#### Scenario: No earlier string is altered

- **WHEN** diffing `strings.xml` against its pre-change state
- **THEN** the only changes are additions of the `consent_*` keys above; no existing `<string>` entry's name or text is modified or removed

### Requirement: Home-section feed tab labels are Bahasa Indonesia

The `:shared:resources` module's `shared/resources/src/commonMain/composeResources/values/strings.xml` SHALL hold the three Home-section feed tab labels in **Bahasa Indonesia**, replacing the prior English placeholder values ("Nearby"/"Following"/"Global"), so the feed tabs match the Bahasa Indonesia bottom-nav section labels (Beranda/Notifikasi/Profil) and satisfy `mobile-design-system` § "User-facing labels are single-language Bahasa Indonesia". The values SHALL be: `tab_nearby` = "Sekitar", `tab_following` = "Mengikuti", `tab_global` = "Global". These are derived copy (the canonical docs pin the timeline header + empty-state copy, not the tab labels) and are flagged for UX review, consistent with how `timeline_limit_hard`/`timeline_limit_soft` were introduced. No other string key or value is altered by this requirement.

#### Scenario: Tab label values are Bahasa Indonesia

- **WHEN** reading the `tab_nearby`, `tab_following`, and `tab_global` values in `shared/resources/src/commonMain/composeResources/values/strings.xml`
- **THEN** `tab_nearby` = "Sekitar", `tab_following` = "Mengikuti", `tab_global` = "Global" (no English label remains among the three feed tab strings)

#### Scenario: Section labels are unchanged

- **WHEN** reading the `section_home`, `section_notifications`, `section_profile` values
- **THEN** they remain "Beranda", "Notifikasi", "Profil" respectively (this change does not alter the already-Indonesian section labels)

### Requirement: Material icon vector drawables for navigation, the composer action, and post-card affordances are bundled

The `:shared:resources` module SHALL bundle the Material icon glyphs used by the bottom-nav sections, the composer FAB, and the post-card affordances as XML vector drawables under `shared/resources/src/commonMain/composeResources/drawable/` (the existing `logo_brand_*.xml` Compose Multiplatform Resources idiom), accessed from `:mobile:app` via `painterResource(Res.drawable.<name>)`. This delivers exactly the glyphs the app uses without the heavy `material-icons-extended` artifact (which "should not be included directly"). The source glyphs are the official Material Symbols (Apache-2.0); their provenance SHALL be recorded alongside the asset (asset header comment or `design.md`). The drawable set SHALL cover, at minimum: bottom-nav Home, Notifications, Profile (outlined + filled per the Material 3 unselected/selected convention); the composer action (add); and the post-card affordances location (place/pin), like (outlined + filled), reply (chat-bubble), and time (schedule/clock). **Feed tabs are text-only and therefore need NO icon drawable** (per `mobile-design-system` § "Material 3 icons …" — feed-tab exception). (If `design.md`'s apply-time dated re-check instead adopts a `material-icons-core` dependency for the in-core glyphs, any glyph not in the core set SHALL still be supplied as a bundled drawable — every affordance is a Material icon either way.)

#### Scenario: Navigation, action, and card icon drawables exist and are accessible via CMP Resources

- **WHEN** inspecting `shared/resources/src/commonMain/composeResources/drawable/` and the generated `Res.drawable.*` accessors
- **THEN** vector-drawable assets for the bottom-nav (Home / Notifications / Profile), the composer action (add), and the post-card affordances (location / like / reply / time) are present AND each is accessible from `:mobile:app` commonMain via `painterResource(Res.drawable.<name>)` AND no feed-tab icon drawable is required (tabs are text-only)

#### Scenario: No material-icons-extended dependency is introduced

- **WHEN** inspecting `gradle/libs.versions.toml` and the consuming `build.gradle.kts`
- **THEN** no `material-icons-extended` library entry is added (the icon set ships as bundled vector drawables; at most a `material-icons-core` entry may be added per the `design.md` re-check, never `material-icons-extended`)

