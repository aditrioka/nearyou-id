## ADDED Requirements

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
