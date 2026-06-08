## Context

`NearYouColorScheme` (in `:shared:resources`) was authored by hand: `primary = #1E4FD6` anchored a claude.ai/design light palette, and the dark scheme was loosely HCT-derived. Two roles were deliberately taken **off** their M3 tonal scale — `secondary` and `tertiary` were set to near-white neutrals (`secondary = #EEF0F4`, `secondaryContainer = #F5F6F8`) so the brand's reserved-purpose accents (coral location pin, amber Premium badge) could live on `ColorScheme` extension properties instead of the standard accent roles (`design.md` Decisions 2/9). 

The cost surfaced on-device: Material 3 1.4.0 defaults `NavigationBarItem`'s `selectedTextColor` to `secondary` and `indicatorColor` to `secondaryContainer`, so the neutralized near-white roles collapsed the selected `Beranda` label into the background (invisible). The fix shipped was a `nearYouNavigationBarItemColors()` override. More broadly, any default M3 widget that consumes `secondary` (filter chips, tonal buttons) is at risk, and a near-white `secondary` forfeits M3's contrast-by-construction guarantee (a tone-95 role on a tone-98 surface ≈ 1:1).

The operator chose to regenerate the scheme through Material Theme Builder (seed `#1E4FD6`) and compared four candidates via side-by-side mockups; **"scheme D"** — MTB output (Color Match OFF) with light `primary` pinned back to the exact brand `#1E4FD6` — was selected as the "M3-conformant without losing identity" option.

## Goals / Non-Goals

**Goals:**
- Make `secondary`/`tertiary` (and the whole 30-role palette) conformant, contrast-safe M3 accents derived from the brand seed's tonal palettes.
- Preserve the brand identity: the dominant accent stays the exact cobalt `#1E4FD6`, and the selected bottom-nav state stays brand-cobalt.
- Keep the navbar readable (WCAG AA) — the original normative contract.
- Zero churn to the reserved-purpose accent surface (`NearYouColors`, the extension properties).

**Non-Goals:**
- Re-homing the coral/amber accents back onto `secondary`/`tertiary` (they stay on the extension properties — that architecture is intentional and unchanged).
- Introducing runtime/dynamic color, a theme picker, or any new dependency.
- Touching typography, logos, strings, or any non-color part of `:shared:resources`.
- Adopting MTB's "Color Match" mode (it parks the brand blue into `primaryContainer` and deepens `primary`; rejected in favor of pin-primary — see Decision 2).

## Decisions

### Decision 1 — Regenerate from the seed via Material Theme Builder (tonal-spot, Color Match OFF)

Generate all five tonal palettes from source color `#1E4FD6` and let MTB map tones→roles. **Why over the status quo:** hand-picked roles lose M3's tone-delta contrast guarantee; deriving from the seed restores accessible-by-construction roles (Δtone ≥ 50 within each on-pair). **Why Color Match OFF over ON:** Color Match pins the literal input into the *container* role and deepens the base `primary` to `#0038AF` (mockup column C) — a larger identity shift than pinning. OFF keeps light/dark/contrast harmonization intact; the only fidelity gap (a muted `primary = #4D5C92`) is corrected surgically by Decision 2.

### Decision 2 — Pin exactly one role: light `primary = #1E4FD6`

Override the generated light `primary` (`#4D5C92`) back to the brand `#1E4FD6`; set `surfaceTint = #1E4FD6` (M3 alias of primary) and dark `inversePrimary = #1E4FD6` (M3 alias of the light primary) to match. **Why safe:** `#1E4FD6` sits at ≈ tone 40 and clears 6.7:1 against white `onPrimary`, so it satisfies the same contrast the generated value would — this is a one-role, documented, contrast-verified pin, not a return to off-scale hand-editing. **Why not pin dark `primary`:** the generated dark `primary = #B7C4FF` is already the brand-blue palette's tone-80 (the correct light-on-dark variant), so no dark pin is needed.

### Decision 3 — The new 30-role palette (light + dark)

Source: the operator's MTB export (`material-theme (2).json`, seed `#1E4FD6`, Color Match OFF) with the Decision-2 pins and the Decision-4 preservations applied. This table is the canonical value source the implementation transcribes into `NearYouColorScheme.kt`.

| Role | Light | Dark | Note |
|---|---|---|---|
| primary | `#1E4FD6` | `#B7C4FF` | light **pinned** (Decision 2) |
| onPrimary | `#FFFFFF` | `#002780` | |
| primaryContainer | `#DCE1FF` | `#354479` | |
| onPrimaryContainer | `#354479` | `#DCE1FF` | |
| inversePrimary | `#B7C4FF` | `#1E4FD6` | dark **pinned** = light primary |
| secondary | `#595D72` | `#C2C5DD` | now a **real accent** (was neutral) |
| onSecondary | `#FFFFFF` | `#2B3042` | |
| secondaryContainer | `#DEE1F9` | `#424659` | |
| onSecondaryContainer | `#424659` | `#DEE1F9` | |
| tertiary | `#75546F` | `#E3BADA` | now a **real accent** (was neutral) |
| onTertiary | `#FFFFFF` | `#43273F` | |
| tertiaryContainer | `#FFD7F5` | `#5B3D57` | |
| onTertiaryContainer | `#5B3D57` | `#FFD7F5` | |
| background | `#FAF8FF` | `#121318` | alias of surface |
| onBackground | `#1A1B21` | `#E3E1E9` | alias of onSurface |
| surface | `#FAF8FF` | `#121318` | |
| onSurface | `#1A1B21` | `#E3E1E9` | |
| surfaceVariant | `#E2E1EC` | `#45464F` | |
| onSurfaceVariant | `#45464F` | `#C6C5D0` | |
| surfaceTint | `#1E4FD6` | `#B7C4FF` | alias of primary |
| inverseSurface | `#2F3036` | `#E3E1E9` | |
| inverseOnSurface | `#F1F0F7` | `#2F3036` | |
| error | `#BA1A1A` | `#FFB4AB` | |
| onError | `#FFFFFF` | `#690005` | |
| errorContainer | `#FFDAD6` | `#93000A` | |
| onErrorContainer | `#93000A` | `#FFDAD6` | |
| outline | `#79747E` | `#8E90A0` | light **preserved** WCAG-safe (Decision 4) |
| outlineVariant | `#C4C5D7` | `#45464F` | |
| scrim | `#8F0E1220` | `#8F0E1220` | **preserved** translucent (Decision 4) |
| surfaceBright | `#FAF8FF` | `#38393F` | |
| surfaceDim | `#DAD9E0` | `#121318` | |
| surfaceContainerLowest | `#FFFFFF` | `#0D0E13` | |
| surfaceContainerLow | `#F4F2FA` | `#1A1B21` | |
| surfaceContainer | `#EFEDF4` | `#1E1F25` | |
| surfaceContainerHigh | `#E9E7EF` | `#292A2F` | |
| surfaceContainerHighest | `#E3E1E9` | `#34343A` | |

### Decision 4 — Preserve two intentional non-MTB values

`scrim` stays the app's translucent `#8F0E1220` (a 56%-ink modal overlay) in both schemes, NOT MTB's opaque `#000000`. Light `outline` stays `#79747E` (the M3-default tone the previous change selected for WCAG ≥ 4.5:1 against surface), rather than MTB's `#767680`, to keep the existing `shared-resources` outline-contrast scenario satisfied without re-derivation.

### Decision 5 — Re-justify and re-token the navbar override

`nearYouNavigationBarItemColors()` is retained, but its purpose changes: with `secondary` now readable, the bare M3 default would render a visible selected label — so the override is no longer a readability band-aid. It becomes a deliberate **brand-identity** choice: the selected bottom-nav state uses the PRIMARY (cobalt) family rather than M3's default `secondary` accent. Tokens: selected icon `primary` (`#1E4FD6`, was `onPrimaryContainer`), indicator pill `primaryContainer` (`#DCE1FF`), selected label `onSurface` (`#1A1B21`), unselected `onSurfaceVariant`. Selected icon `primary` on `primaryContainer` ≈ 5.2:1 (passes). The KDoc and the `mobile-design-system` requirement are updated to state this.

### Decision 6 — `NearYouColors` accents unchanged; two incidental link aliases relaxed

The reserved-purpose accent palette (`NearYouColors` light + dark — `locationPin`, `premiumBadge`, success/warning/`link`) is byte-identical after this change. Consequence: `NearYouColors.light.link = #1740B8` and `.dark.link = #B3C5FF` were *incidentally* asserted equal to `onPrimaryContainer` / dark `primary`; those roles now change (`onPrimaryContainer → #354479`, dark `primary → #B7C4FF`), so the two equality assertions in `NearYouColorSchemeTest` are relaxed to direct value assertions. `link` keeps its values — it is simply no longer pinned to a scheme role.

## Risks / Trade-offs

- **Broad test-value churn** (every light + dark role assertion in `NearYouColorSchemeTest`) → Mitigation: the Decision-3 table is the single transcription source; rewrite the full-table regression from it in one pass.
- **Selected-nav icon contrast drops** from the `onPrimaryContainer`-on-`primaryContainer` pairing (a guaranteed on-pair) to `primary`-on-`primaryContainer` (≈ 5.2:1) → Mitigation: still clears AA (≥ 4.5:1); the `AppShellScreenTest` contrast assertion is updated to verify it, not just assert inequality.
- **Visible behavior shift**: surface goes pure-white `#FFFFFF` → very-slightly-tinted `#FAF8FF`, and any default-M3 widget that consumes `secondary` now shows a real blue-grey accent instead of near-white → Mitigation: intended; the mockup-validated identity (cobalt primary, brand accents) is preserved, and readability strictly improves.
- **Spec/code drift if only code changes** → Mitigation: this is exactly why the change is routed through OpenSpec — the `shared-resources` and `mobile-design-system` requirements are MODIFIED in lockstep with the code.
