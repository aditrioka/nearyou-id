# Proposal: mobile-mockup-visual-conformance

## Why

The canonical mobile mockup board ([`dev/mockups/nearyou-screens-mockup.html`](../../../dev/mockups/README.md), binding rule `docs/11` § 2.8) is the visual reference every UI-affecting change must conform to, but three already-shipped surfaces predate it (or predate its latest refinements) and visibly diverge: (1) the brand-logo vector drawables still carry an opaque background plate + ~60% dead padding from the original SVG conversion, so every call site renders a boxed logo instead of the mockup's clean transparent glyph; (2) `SignInScreen` renders a text title the mockup explicitly drops ("Logo brand tanpa teks (clean)", frame 13); (3) the post composer is missing the coral location chip and the UU-PDP location-fuzzing privacy note (frame 6). Closing these gaps now keeps the demo surface consistent while the remaining critical-path screens are built against the same board. Zero new behavior — visual conformance only.

## What Changes

- **Logo assets (`:shared:resources`)**: rewrite `logo_brand_light.xml` / `logo_brand_dark.xml` — remove the full-canvas background path, crop the 108×108 canvas to the mockup's 46×46 glyph window (via `<group android:translateX="-31" android:translateY="-31">` — vector drawables have no viewBox origin offset), and retint the dark variant's glyph from white to `#B7C4FF` (dark-scheme `primary`, matching the mockup's `stroke=currentColor` + `color:var(--primary)` rendering). Per-variant stroke-geometry differences are preserved. Launcher icons are NOT touched (adaptive-icon safe-zone rules differ).
- **Logo call sites (`:mobile:app`)**: re-tune the explicit dp size at all four consumers — `SignInScreen`, `AgeGateScreen`, `AppShellScreen` (`HomeBrandTopBar`, frame 1: 40dp logo in the 56dp bar), `RootRouterScreen` splash — because the glyph now fills the full canvas instead of ~40% of it.
- **Sign-in screen (frame 13)**: stop rendering the `signin_screen_title` text node; the screen shows the large brand logo (96dp), CTA, and `account_separation_disclosure` footnote only. The string stays in the shared catalog (retained-in-catalog pattern).
- **Composer (frame 6)**: add a coral location chip (`NearYouColors` `locationPinContainer`/`onLocationPinContainer`/`locationPin` tokens + `ic_post_location` glyph) with a static label string (new: `post_create_location_chip`) — NOT a live city name (see design.md Decision 2); add the privacy note (new string `post_create_privacy_note` "Lokasi kamu disamarkan (±5 km) sebelum tampil ke pengguna lain" + new bundled `verified_user` glyph `ic_privacy_shield`); move the existing `N/280` counter to the mockup's bottom composer-bar position (right-aligned). NO attachment toolbar (media is Month 6 roadmap — the mockup's image/camera buttons are explicitly out of scope).
- **Out of scope**: post detail (frame 7) — deliberately disjoint from in-flight PR [#234](https://github.com/aditrioka/nearyou-id/pull/234) (`mobile-inline-post-actions`); its restyle is an explicit follow-up after #234 merges. No schema, no backend, no new endpoints, no new dependencies.

## Capabilities

### New Capabilities

(none — visual conformance of shipped capabilities)

### Modified Capabilities

- `shared-resources`: the brand-logo requirement changes from "blue glyph on white background / white glyph on blue background, same viewBox as source SVGs" to transparent-background scheme-primary glyphs (light `#1E4FD6`, dark `#B7C4FF`) on a cropped 46×46 canvas; the hex-grep scenarios change accordingly. The bundled Material-glyph set gains the privacy-shield (`verified_user`) drawable.
- `mobile-auth-signin`: `SignInScreen` requirement item (b) (rendered screen title) is removed from the rendered surface; `signin_screen_title` is retained in the catalog. The title-node THEN-scenario is replaced.
- `mobile-post-creation`: the composer screen requirement gains the location chip + privacy note and pins the counter to the bottom composer bar; the composer-strings requirement gains 2 new keys (catalog count assertion 100 → 102; drawables assertion 12 → 13).

## Impact

- **Code**: `shared/resources/src/commonMain/composeResources/drawable/logo_brand_{light,dark}.xml` (rewrite), new `ic_privacy_shield.xml`, `strings.xml` (+2 keys); `mobile/app` — `SignInScreen.kt`, `AgeGateScreen.kt`, `AppShellScreen.kt`, `RootRouterScreen.kt` (logo sizing), `PostCreationScreen.kt` (chip + note + counter placement).
- **Tests**: `SharedStringsCatalogTest` (100→102), `SharedDrawablesCatalogTest` (12→13), `SignInScreenTest` (title-node assertion replaced), `PostCreationScreenTest` (chip + privacy-note presence; counter/CTA scenarios unchanged).
- **Specs**: deltas for `shared-resources`, `mobile-auth-signin`, `mobile-post-creation`. `mobile-home-tab-host` / `mobile-age-gate` reference the logo assets generically — no spec delta.
- **Delivery**: one PR ([#236](https://github.com/aditrioka/nearyou-id/pull/236), already open as the claim), branch `mobile-mockup-visual-conformance`. DoD per `docs/11` § 5: manual verification Android + iOS, light + dark screenshots in the PR body.
