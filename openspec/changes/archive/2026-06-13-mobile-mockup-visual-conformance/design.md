# Design: mobile-mockup-visual-conformance

## Context

The canonical mobile mockup board (`dev/mockups/nearyou-screens-mockup.html`, binding rule `docs/11` § 2.8) postdates several shipped screens. Three divergences are in scope: the boxed brand-logo drawables, the SignInScreen text title (frame 13 drops it), and the composer's missing location chip + privacy note (frame 6). The mockup renders its logo as an inline SVG with `viewBox="31 31 46 46"`, `stroke="currentColor"`, tinted `color:var(--primary)` — `#1E4FD6` in the light frame set, `#B7C4FF` in `.frame.dark`. Our shipped vector drawables instead bake in a full-canvas background plate (white / `#1E4FD6`) on a 108×108 canvas with the glyph occupying only ~x33.7–74.3 / y32.6–73.7, and the dark variant's glyph is white.

In-flight coordination: PR [#234](https://github.com/aditrioka/nearyou-id/pull/234) (`mobile-inline-post-actions`) owns the post-detail surface; this change deliberately excludes post detail (frame 7) to stay file-disjoint.

## Goals / Non-Goals

**Goals:**

- Logo drawables match the mockup glyph: transparent background, cropped canvas, scheme-primary tint per variant.
- SignInScreen matches frame 13 (logo-only header, no text title).
- Composer matches frame 6's location chip, privacy note, and counter placement — minus the attachment toolbar.
- Zero behavior change: no new state, navigation, data, or network surface.

**Non-Goals:**

- Post detail restyle (frame 7) — explicit follow-up after #234 merges.
- Attachment toolbar in the composer (media is Month 6 roadmap).
- Composer top-bar chrome restyle (frame 6 shows a ✕ + "Posting" app bar; the shipped screen's title + CTA arrangement is behavior-adjacent chrome and is NOT restructured here — candidate to fold into the frame-7 follow-up).
- Composer avatar (frame 6 shows the author avatar beside the input; no profile/avatar capability is shipped — travels with the chrome-restyle follow-up).
- Launcher icons (adaptive-icon safe-zone rules differ from in-app assets; `docs/11` § 2.8 scope is in-app surfaces).
- Reverse geocoding / city display in the composer (see Decision 2).

## Decisions

### Decision 1 — Crop via translate group; retint dark glyph to `#B7C4FF`

Android vector drawables have no viewBox origin offset, so the 108×108 → 46×46 crop is done by setting `viewportWidth/Height="46"` + intrinsic `android:width/height="46dp"` and wrapping all glyph paths in `<group android:translateX="-31" android:translateY="-31">`. Path data is byte-preserved (including the deliberate per-variant geometry differences: light `43.8,45.5` vs dark `43.8,43.5` top-hexagon tail, light `68.3,52.6` vs dark `68.9,53` right-hexagon tail). The background paths are deleted. The dark variant's stroke/fill changes `#FFFFFF` → `#B7C4FF` (dark-scheme `primary`), matching the mockup's `currentColor`/`var(--primary)` rendering — the white-on-blue plate idiom is superseded; there is no remaining rationale for white since the glyph now sits directly on dark `surface` (`#121318`), where `#B7C4FF` is the scheme's designed high-contrast accent. Alternative considered: a single drawable + runtime `ColorFilter` tint — rejected because the two-variant `isSystemInDarkTheme()` selection idiom is spec'd across 4 capabilities and consumers; changing the selection mechanism is a bigger blast radius for zero visual gain.

Crop-window safety: glyph stroke extent (geometry ± strokeWidth/2 = ±1.5) spans x 32.2–75.8, y 31.1–75.2, inside the 31–77 window. `strokeWidth=3` on the 46-unit viewport renders proportionally identical to the mockup's `stroke-width="3"` on its 46-unit viewBox.

### Decision 2 — Composer chip shows a static label, not a city name

Frame 6's chip reads "Jakarta Selatan", but the composer is device-location-only, no reverse-geocoding capability is shipped, and `mobile-post-creation`'s PII discipline forbids rendering the actual coordinate. A zero-new-behavior change cannot introduce a geocoding data dependency, so the chip renders a static localized label (`post_create_location_chip` = "Lokasi saat ini") with the mockup's visual treatment: `locationPinContainer` background, `onLocationPinContainer` text, `ic_post_location` glyph tinted `locationPin` (the `NearYouColors` reserved-purpose tokens — exactly the surface they were reserved for; mockup CSS vars `--pin-container`/`--on-pin-container` map 1:1 to these). Alternatives: (a) echo the city from the POST response — only available *after* submit, not at compose time; (b) reverse geocode on device — new behavior + platform dependency, out of scope. If a later change ships pre-submit city resolution, the chip upgrades to the live city with no layout change.

### Decision 3 — Privacy note ships a new bundled `verified_user` glyph

The note (mockup `.privacy-note`: `verified_user` glyph tinted `success`, 12sp `onSurfaceVariant` text) needs a glyph not in the bundled set. Per the `shared-resources` Material-glyph idiom (bundle exactly the glyphs used; never `material-icons-extended`), add `ic_privacy_shield.xml` (Material Symbols `verified_user`, Apache-2.0, provenance in the asset header) and bump `SharedDrawablesCatalogTest` 12 → 13. The note copy is a fixed string (`post_create_privacy_note` = "Lokasi kamu disamarkan hingga ±5 km sebelum tampil ke pengguna lain") — the "±5 km" is the canonical *user-facing* obfuscation framing: viewers never see a distance below the `distance-rendering` spec's 5 km display floor (on top of the `coordinate-jitter` spec's 50–500 m envelope). The "hingga" (up to) qualifier is the board's own phrasing from its onboarding privacy frame, adopted over frame 6's bare "(±5 km)" by operator decision at proposal review: the wire ships `display_location` coordinates within 500 m plus a kotamadya-accurate city name, so an unqualified "±5 km" could be read as a precise obfuscation-magnitude commitment the wire does not honor. It is informational UI copy, not a parameterized value. (The mockup caption's citation "docs/06 §spatial fuzzing" is stale — docs/06 has no such section; the shipped specs above are the canonical sources.)

### Decision 4 — SignInScreen title removal uses the retained-in-catalog pattern

`signin_screen_title` stays in `strings.xml` and in `SharedStringsCatalogTest` (precedent: the retained-in-catalog pattern from `mobile-timeline-card-redesign`'s glyph removals) — removing a catalog string for a render-only change would churn the count assertion and break the "earlier copy is byte-identical" requirement. The spec delta replaces the title-node scenario with a disclosure-only assertion plus an explicit negative guard (title text NOT rendered), so the follow-up trail is spec-visible.

### Decision 5 — Call-site dp sizes are re-derived from the mockup, not preserved

Removing ~60% dead padding means existing dp values would render the glyph visually ~2.3× larger. Each of the 4 consumers gets an explicit size from the mockup's redlines (frame 1 app bar: 40dp in the 56dp bar; frame 13 sign-in: 96dp; age gate + splash: re-eyeballed against the sign-in scale at apply time using `dev/scripts/mockup-measure.sh` annexes). No shared "logo size token" is introduced — 4 call sites with screen-specific sizes don't justify a new token surface (Pattern Registry anti-patchwork: no new pattern for an existing concern).

### Standards conformance

Builds on the shipped Pattern-Registry patterns unchanged: screen-level state holders (`SignInViewModel` / `PostCreationViewModel` untouched), Navigation 3 routing (no route changes), data layer (no repository/API changes), `:shared:resources` CMP Resources asset + string idiom (`Res.drawable.*` / `Res.string.*`, no hardcoded strings), `NearYouColors` reserved-purpose color tokens for the coral chip. No deviation from `docs/11` Pattern Registry; no new pattern introduced; no `libs.versions.toml` touch (propose-time WebSearch not applicable — no substrate change).

## Risks / Trade-offs

- **[Shared-file overlap with #234]** `strings.xml` + `SharedStringsCatalogTest` are shared mutation points; `mobile-inline-post-actions` also adds strings, so the later-merging branch takes a trivial rebase (assertion bump + key list). → Mitigation: assertion expressed as 88 tracked accessors (86 + this change's 2) in this change's tasks; reconcile the literal at rebase if #234 lands first. Note the test's tracked-accessor list has pre-existing drift (14 catalog keys untracked, incl. the unspec'd `post_create_error_rate_limited`) — out of scope here, filed as a follow-up issue. File-level footprints are otherwise disjoint (this change does not touch `PostDetailScreen`/`PostCard`).
- **[Visual-size regressions on iOS]** dp re-tuning is eyeballed per-platform; iOS renders via the same Compose canvas so drift risk is low, but DoD requires manual verification on both platforms (light + dark screenshots in the PR body per `docs/11` § 5).
- **[Dark-logo contrast]** `#B7C4FF` on dark `surface` `#121318` has ~10.9:1 contrast (well above the 3:1 graphics floor); on light `surface` it would fail — but the dark variant is only ever selected under `isSystemInDarkTheme()`, the same guarantee the white-glyph variant relied on.
- **[Mockup-vs-spec divergence on composer chrome]** Frame 6's ✕/"Posting" app bar differs from the shipped title arrangement; deliberately out of scope (Non-Goals) — on behavior conflicts specs win over mockups, and restructuring chrome is not visual conformance.

## Migration Plan

Single PR (#236) on branch `mobile-mockup-visual-conformance`; squash-merge at end-of-lifecycle. No data, schema, or API migration. Rollback = revert the squash commit (assets + screens are self-contained).

## Open Questions

(none — the one judgment call, dark glyph white → `#B7C4FF`, is resolved by the mockup's own dark-frame token rendering; flagged per the operator's request and answered in Decision 1)
