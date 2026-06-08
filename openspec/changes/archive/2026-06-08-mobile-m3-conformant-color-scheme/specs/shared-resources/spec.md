## MODIFIED Requirements

### Requirement: Brand color scheme exposed as NearYouColorScheme

The `:shared:resources` module SHALL expose a `NearYouColorScheme` object (or equivalent named container) in commonMain with two Material 3 `ColorScheme` instances: `NearYouColorScheme.light` and `NearYouColorScheme.dark`. Both schemes SHALL be generated from the brand seed color `#1E4FD6` through the Material Theme Builder tonal-palette pipeline (default "tonal spot" scheme, Color Match OFF) so every role sits on its M3 tonal scale and is contrast-safe by construction — with exactly two documented exceptions: (1) the light `primary` (and its aliases `surfaceTint` and dark `inversePrimary`) is **pinned** to the exact brand `#1E4FD6` for identity, and (2) `scrim` keeps the app's translucent `#8F0E1220` and light `outline` keeps the WCAG-safe `#79747E`. The full 30-role light + dark palette is documented in this change's [`design.md`](../../design.md) Decision 3 table. `NearYouColorScheme.light.secondary` and `.tertiary` are now genuine Material 3 accents drawn from their tonal palettes (NO LONGER neutralized near-white surfaceVariant tones); the reserved-purpose coral/amber brand accents remain on the `ColorScheme.locationPin` / `.premiumBadge` extension properties, not on `secondary`/`tertiary`.

#### Scenario: NearYouColorScheme.light has palette primary

- **WHEN** inspecting `shared/resources/src/commonMain/kotlin/id/nearyou/resources/theme/NearYouColorScheme.kt` (or equivalent commonMain path)
- **THEN** `NearYouColorScheme.light.primary` resolves to `Color(0xFF1E4FD6)` AND `NearYouColorScheme.light.onPrimary` resolves to `Color(0xFFFFFFFF)` AND `NearYouColorScheme.light.surfaceTint` resolves to `Color(0xFF1E4FD6)` (alias of the pinned primary)

#### Scenario: NearYouColorScheme.light defines all required Material 3 ColorScheme roles

- **WHEN** inspecting `NearYouColorScheme.light`
- **THEN** the `ColorScheme` is constructed with explicit values for ALL of: `primary`, `onPrimary`, `primaryContainer`, `onPrimaryContainer`, `inversePrimary`, `secondary`, `onSecondary`, `secondaryContainer`, `onSecondaryContainer`, `tertiary`, `onTertiary`, `tertiaryContainer`, `onTertiaryContainer`, `background`, `onBackground`, `surface`, `onSurface`, `surfaceVariant`, `onSurfaceVariant`, `surfaceTint`, `inverseSurface`, `inverseOnSurface`, `error`, `onError`, `errorContainer`, `onErrorContainer`, `outline`, `outlineVariant`, `scrim`, `surfaceBright`, `surfaceDim`, `surfaceContainerLowest`, `surfaceContainerLow`, `surfaceContainer`, `surfaceContainerHigh`, `surfaceContainerHighest`

#### Scenario: NearYouColorScheme.dark is available

- **WHEN** inspecting `NearYouColorScheme.dark`
- **THEN** the value is a `ColorScheme` instance with the same 30+ roles as `light`, populated from the dark values in this change's [`design.md`](../../design.md) Decision 3 table (the seed's dark tonal stops; dark `primary` = `Color(0xFFB7C4FF)` = the brand-blue palette tone 80, and dark `inversePrimary` = `Color(0xFF1E4FD6)` aliases the light pinned primary)

#### Scenario: NearYouColorScheme.light maps M3 secondary to a readable accent, not coral

- **WHEN** inspecting `NearYouColorScheme.light.secondary`
- **THEN** the value resolves to `Color(0xFF595D72)` — a genuine Secondary tonal-palette accent (≈ tone 40) that clears WCAG AA against the `surface`, NOT the previously-neutralized near-white `Color(0xFFEEF0F4)` — AND it is explicitly NOT `Color(0xFFFF7A5C)` (coral, which remains reserved as the `ColorScheme.locationPin` extension per [`design.md`](../../design.md))

#### Scenario: NearYouColorScheme.light maps M3 tertiary to a readable accent, not amber

- **WHEN** inspecting `NearYouColorScheme.light.tertiary`
- **THEN** the value resolves to `Color(0xFF75546F)` — a genuine Tertiary tonal-palette accent, NOT the previously-neutralized near-white tone — AND it is explicitly NOT `Color(0xFFF4B740)` (amber, which remains reserved as the `ColorScheme.premiumBadge` extension per [`design.md`](../../design.md))

#### Scenario: NearYouColorScheme.light outline meets M3 contrast guideline

- **WHEN** inspecting `NearYouColorScheme.light.outline`
- **THEN** the value resolves to `Color(0xFF79747E)` (the M3 default outline tone, preserved through this change because it passes WCAG 4.5:1 against the near-white `surface = #FAF8FF`, satisfying M3's outline contrast requirement), NOT a value that fails the guideline; `outlineVariant` carries the lower-contrast decorative tone (`Color(0xFFC4C5D7)`, no contrast requirement)

#### Scenario: NearYouColorScheme.light scrim is correctly encoded

- **WHEN** inspecting `NearYouColorScheme.light.scrim`
- **THEN** the value resolves to `Color(0x8F0E1220)` (the app's translucent 56%-ink modal overlay, preserved through this change — NOT the opaque `#000000` the tonal pipeline would emit)
