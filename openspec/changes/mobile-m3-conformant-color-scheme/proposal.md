## Why

`NearYouColorScheme` deliberately neutralizes the Material 3 `secondary`/`tertiary` roles to near-white surfaceVariant tones (`secondary = #EEF0F4`). That choice (a) makes every default M3 component that consumes `secondary` unreadable — the on-device invisible-`Beranda` nav-label defect, which forced a navbar color override as a band-aid — and (b) puts `secondary` off its tonal scale, violating M3's contrast-by-construction guarantee. Regenerating the scheme from the brand seed through Material Theme Builder's tonal-palette pipeline makes `secondary`/`tertiary` readable accents by construction, while pinning only `primary` preserves the brand cobalt identity. The approach (and the exact "scheme D" values) was validated with the operator via side-by-side mockups.

## What Changes

- Replace the hand-picked `NearYouColorScheme.light` + `.dark` with a Material-Theme-Builder-generated, M3-conformant tonal scheme seeded from brand blue `#1E4FD6` (MTB default "tonal spot" variant, Color Match OFF).
- **One deliberate override**: light `primary` is pinned back to the exact brand `#1E4FD6` (and `surfaceTint` aliases it); dark `inversePrimary` keeps aliasing light `primary`. No other role is hand-edited off the generated tonal scale.
- Net effect: `secondary`/`tertiary` stop being neutral near-white and become real M3 accents (light `secondary = #595D72`, `secondaryContainer = #DEE1F9`, `tertiary = #75546F`); the whole 30-role light + dark palette is regenerated.
- Preserve two intentional non-MTB values: the translucent `scrim = #8F0E1220` (not MTB's opaque `#000000`) and a WCAG-safe `outline = #79747E`.
- Retain `nearYouNavigationBarItemColors()` but **re-justify and re-token it**: the selected bottom-nav state now uses the PRIMARY (brand cobalt) family as a deliberate brand-identity choice — selected icon = `primary` (was `onPrimaryContainer`), indicator pill = `primaryContainer`, selected label = `onSurface` — rather than as a fix for an invisible near-white label. The WCAG-AA readability contract is unchanged.
- **Unchanged**: the reserved-purpose brand accents (`NearYouColors` light + dark — `locationPin = #FF7A5C`, `premiumBadge = #F4B740`, success/warning/link), the `ColorScheme` extension-property surface, and the "extension properties throw outside `NearYouTheme`" contract.

## Capabilities

### New Capabilities

<!-- none -->

### Modified Capabilities

- `shared-resources`: requirement "Brand color scheme exposed as NearYouColorScheme" — the `secondary`-is-neutral and `tertiary`-is-neutral scenarios are reversed (they become real accents, still explicitly NOT coral/amber), and the full 30-role light + dark palette values change. The reserved-purpose extension-property requirement is unchanged.
- `mobile-design-system`: requirement "Navigation and tab labels are visible in selected and unselected states" — the rationale (bare default unsafe because `secondary` is neutralized near-white) and the mandated selected-state tokens change (selected icon `onPrimaryContainer` → `primary`); the normative WCAG-AA readability scenarios are kept.

## Impact

- **Code**: `shared/resources/.../theme/NearYouColorScheme.kt` (all 30+ role values + KDoc), `mobile/app/.../screens/shell/AppShellScreen.kt` (`nearYouNavigationBarItemColors()` tokens + KDoc).
- **Tests**: `shared/resources/.../theme/NearYouColorSchemeTest.kt` (full-table regression — every light + dark role assertion), `mobile/app/.../screens/shell/AppShellScreenTest.kt` (navbar selected-token + WCAG-contrast assertions).
- **No new dependencies**: reuses `org.jetbrains.compose.material3:material3` 1.10.0-alpha05; `lightColorScheme()`/`darkColorScheme()` already expose the full role surface.
- **No runtime/API/schema impact**; mobile-only, visual. Dark-mode and high-contrast derivations now come from the tonal pipeline (contrast-safe by construction).
