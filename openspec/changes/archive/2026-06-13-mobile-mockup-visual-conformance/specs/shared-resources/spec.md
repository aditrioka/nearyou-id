# shared-resources — delta for mobile-mockup-visual-conformance

## MODIFIED Requirements

### Requirement: Brand logo bundled in light + dark variants with palette primary

The `:shared:resources` module SHALL bundle two in-app brand logo **Android XML vector drawable** variants in `shared/resources/src/commonMain/composeResources/drawable/` (Compose Multiplatform Resources canonical layout): `logo_brand_light.xml` (`#1E4FD6` scheme-primary glyph, transparent background, for light UI surfaces) and `logo_brand_dark.xml` (`#B7C4FF` dark-scheme-primary glyph, transparent background, for dark UI surfaces). As of `mobile-mockup-visual-conformance` both variants conform to the canonical mockup board's logo rendering (`dev/mockups/nearyou-screens-mockup.html`, `stroke=currentColor` tinted `var(--primary)`, viewBox `31 31 46 46`): NO background path, canvas cropped to the 46×46 glyph window via `android:viewportWidth/Height="46"` + intrinsic `android:width/height="46dp"` + a `<group android:translateX="-31" android:translateY="-31">` wrapping all glyph paths (vector drawables have no viewBox origin offset), glyph path data byte-preserved from the prior assets including the deliberate per-variant geometric differences (light `43.8,45.5` / dark `43.8,43.5` top-hexagon tail; light `68.3,52.6` / dark `68.9,53` right-hexagon tail). The prior background-plate idiom (white plate in light, `#1E4FD6` plate with white glyph in dark) is superseded. Format note: CMP Resources rejects SVG on Android with `IllegalStateException: Android platform doesn't support SVG format` per JetBrains issues [#4715](https://github.com/JetBrains/compose-multiplatform/issues/4715) / [#4670](https://github.com/JetBrains/compose-multiplatform/issues/4670); Android XML vector drawables are the canonical cross-platform format (work on iOS / desktop / web too via `VectorPainter`). Compose call sites SHALL select the variant via `isSystemInDarkTheme()` and access via `Res.drawable.logo_brand_{light,dark}`.

#### Scenario: Both logo variants are present in composeResources/drawable/ as XML vector drawables

- **WHEN** inspecting `shared/resources/src/commonMain/composeResources/drawable/`
- **THEN** the directory contains both `logo_brand_light.xml` AND `logo_brand_dark.xml` (Android XML vector drawable format, `<vector>` root element); NO `.svg` variant of either logo remains in the directory

#### Scenario: Neither variant contains a background path

- **WHEN** inspecting the path elements of `logo_brand_light.xml` and `logo_brand_dark.xml`
- **THEN** neither file contains a full-canvas background path (no `pathData` rectangle spanning the viewport); every path is a glyph stroke or the glyph dot fill, wrapped in the `<group android:translateX="-31" android:translateY="-31">` crop group; `android:viewportWidth`/`android:viewportHeight` are `46` and intrinsic size is `46dp`

#### Scenario: Light variant uses palette primary blue only

- **WHEN** grepping `shared/resources/src/commonMain/composeResources/drawable/logo_brand_light.xml` for hex color values
- **THEN** the only color value referenced is `#1E4FD6` (in `android:strokeColor` / `android:fillColor` attributes); NO occurrence of `#FFFFFF`, `#014CAB`, or `#0B4FA8`

#### Scenario: Dark variant uses dark-scheme primary only

- **WHEN** grepping `shared/resources/src/commonMain/composeResources/drawable/logo_brand_dark.xml` for hex color values
- **THEN** the only color value referenced is `#B7C4FF` (the `NearYouColorScheme.dark` `primary` token, in `android:strokeColor` / `android:fillColor` attributes); NO occurrence of `#FFFFFF` or `#1E4FD6`

#### Scenario: Both variants accessible via CMP Resources

- **WHEN** the Compose Multiplatform Resources Gradle codegen task generates the `Res` accessor class for `:shared:resources`
- **THEN** `Res.drawable.logo_brand_light` AND `Res.drawable.logo_brand_dark` are both available for consumption from `:mobile:app` commonMain via `painterResource(Res.drawable.X)`

### Requirement: Material icon vector drawables for navigation, the composer action, and post-card affordances are bundled

The `:shared:resources` module SHALL bundle the Material icon glyphs used by the bottom-nav sections, the composer FAB, the composer privacy note, and the post-card affordances as XML vector drawables under `shared/resources/src/commonMain/composeResources/drawable/` (the existing `logo_brand_*.xml` Compose Multiplatform Resources idiom), accessed from `:mobile:app` via `painterResource(Res.drawable.<name>)`. This delivers exactly the glyphs the app uses without the heavy `material-icons-extended` artifact (which "should not be included directly"). The source glyphs are the official Material Symbols (Apache-2.0); their provenance SHALL be recorded alongside the asset (asset header comment or `design.md`). The drawable set SHALL cover, at minimum: bottom-nav Home, Notifications, Profile (outlined + filled per the Material 3 unselected/selected convention); the composer action (add); the composer privacy-note shield (`verified_user`, bundled as `ic_privacy_shield` per `mobile-mockup-visual-conformance`); and the post-card affordances location (place/pin), like (outlined + filled), reply (chat-bubble), and time (schedule/clock). **Feed tabs are text-only and therefore need NO icon drawable** (per `mobile-design-system` § "Material 3 icons …" — feed-tab exception). (If `design.md`'s apply-time dated re-check instead adopts a `material-icons-core` dependency for the in-core glyphs, any glyph not in the core set SHALL still be supplied as a bundled drawable — every affordance is a Material icon either way.)

#### Scenario: Navigation, action, and card icon drawables exist and are accessible via CMP Resources

- **WHEN** inspecting `shared/resources/src/commonMain/composeResources/drawable/` and the generated `Res.drawable.*` accessors
- **THEN** vector-drawable assets for the bottom-nav (Home / Notifications / Profile), the composer action (add), the privacy-note shield (`ic_privacy_shield`), and the post-card affordances (location / like / reply / time) are present AND each is accessible from `:mobile:app` commonMain via `painterResource(Res.drawable.<name>)` AND no feed-tab icon drawable is required (tabs are text-only)

#### Scenario: No material-icons-extended dependency is introduced

- **WHEN** inspecting `gradle/libs.versions.toml` and the consuming `build.gradle.kts`
- **THEN** no `material-icons-extended` library entry is added (the icon set ships as bundled vector drawables; at most a `material-icons-core` entry may be added per the `design.md` re-check, never `material-icons-extended`)
