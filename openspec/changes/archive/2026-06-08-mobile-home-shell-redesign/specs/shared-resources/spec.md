## ADDED Requirements

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
