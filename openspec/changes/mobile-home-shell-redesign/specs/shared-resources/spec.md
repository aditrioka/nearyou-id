## ADDED Requirements

### Requirement: Home-section feed tab labels are Bahasa Indonesia

The `:shared:resources` module's `shared/resources/src/commonMain/composeResources/values/strings.xml` SHALL hold the three Home-section feed tab labels in **Bahasa Indonesia**, replacing the prior English placeholder values ("Nearby"/"Following"/"Global"), so the feed tabs match the Bahasa Indonesia bottom-nav section labels (Beranda/Notifikasi/Profil) and satisfy `mobile-design-system` § "User-facing labels are single-language Bahasa Indonesia". The values SHALL be: `tab_nearby` = "Sekitar", `tab_following` = "Mengikuti", `tab_global` = "Global". These are derived copy (the canonical docs pin the timeline header + empty-state copy, not the tab labels) and are flagged for UX review, consistent with how `timeline_limit_hard`/`timeline_limit_soft` were introduced. No other string key or value is altered by this requirement.

#### Scenario: Tab label values are Bahasa Indonesia

- **WHEN** reading the `tab_nearby`, `tab_following`, and `tab_global` values in `shared/resources/src/commonMain/composeResources/values/strings.xml`
- **THEN** `tab_nearby` = "Sekitar", `tab_following` = "Mengikuti", `tab_global` = "Global" (no English label remains among the three feed tab strings)

#### Scenario: Section labels are unchanged

- **WHEN** reading the `section_home`, `section_notifications`, `section_profile` values
- **THEN** they remain "Beranda", "Notifikasi", "Profil" respectively (this change does not alter the already-Indonesian section labels)

### Requirement: Material icon vector drawables for navigation, tabs, and the composer action are bundled

The `:shared:resources` module SHALL bundle the Material icon glyphs used by the bottom-nav sections, the feed tabs, and the composer FAB as XML vector drawables under `shared/resources/src/commonMain/composeResources/drawable/` (the existing `logo_brand_*.xml` Compose Multiplatform Resources idiom), accessed from `:mobile:app` via `painterResource(Res.drawable.<name>)`. This delivers exactly the glyphs the app uses without the heavy `material-icons-extended` artifact (which "should not be included directly") and without the `material-icons-core` gap (People/Public are not in the core set as of CMP 1.8.2+). The source glyphs are the official Material Symbols (Apache-2.0); their provenance SHALL be recorded alongside the asset (asset header comment or `design.md`). The drawable set SHALL cover, at minimum: bottom-nav Home, Notifications, Profile; feed-tab Nearby (location), Following (people), Global (public/globe); and the composer action (add/compose), with filled + outlined variants where the Material 3 selected/unselected convention requires both. (If `design.md`'s apply-time dated re-check instead adopts a `material-icons-core` dependency for the in-core glyphs, the People/Public/Following/Global gaps SHALL still be supplied as bundled drawables — the navigation/tab/action affordances are Material icons either way.)

#### Scenario: Navigation, tab, and action icon drawables exist and are accessible via CMP Resources

- **WHEN** inspecting `shared/resources/src/commonMain/composeResources/drawable/` and the generated `Res.drawable.*` accessors
- **THEN** vector-drawable assets for the bottom-nav (Home / Notifications / Profile), feed tabs (Nearby / Following / Global), and the composer action are present AND each is accessible from `:mobile:app` commonMain via `painterResource(Res.drawable.<name>)`

#### Scenario: No material-icons-extended dependency is introduced

- **WHEN** inspecting `gradle/libs.versions.toml` and the consuming `build.gradle.kts`
- **THEN** no `material-icons-extended` library entry is added (the icon set ships as bundled vector drawables; at most a `material-icons-core` entry may be added per the `design.md` re-check, never `material-icons-extended`)
