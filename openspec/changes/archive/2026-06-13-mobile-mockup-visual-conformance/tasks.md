# Tasks: mobile-mockup-visual-conformance

## 1. Mockup grounding (docs/11 § 2.8 — before any UI work)

- [x] 1.1 Render the relevant frames of `dev/mockups/nearyou-screens-mockup.html` via headless Chrome (extract standalone frame to `/tmp` per the §2.8 render rule) and generate the measurement annex for frames 1 (app bar), 6 (composer), and 13 (Masuk): `dev/scripts/mockup-measure.sh nearyou-screens-mockup.html <frame-no>` (on-demand output, never committed)
- [x] 1.2 Record the extracted redlines used (logo sizes, chip paddings/typography, privacy-note spacing, counter position) as a checklist for tasks 2–4

## 2. Shared resources (`:shared:resources` — logo assets, new glyph, strings)

- [x] 2.1 Rewrite `logo_brand_light.xml`: delete the white background path; wrap all glyph paths in `<group android:translateX="-31" android:translateY="-31">`; set `viewportWidth/Height="46"` + `android:width/height="46dp"`; glyph pathData byte-preserved; colors stay `#1E4FD6` only
- [x] 2.2 Rewrite `logo_brand_dark.xml`: same crop treatment; delete the `#1E4FD6` background path; retint all glyph strokes + the dot fill `#FFFFFF` → `#B7C4FF`; preserve the per-variant geometry differences (`43.8,43.5` / `68.9,53`)
- [x] 2.3 Add `ic_privacy_shield.xml` (Material Symbols `verified_user`, Apache-2.0 provenance in the asset header comment, 24dp glyph per the existing `ic_*` idiom)
- [x] 2.4 Update `SharedDrawablesCatalogTest`: add `Res.drawable.ic_privacy_shield`, count assertion 12 → 13
- [x] 2.5 Add `post_create_location_chip` ("Lokasi saat ini") + `post_create_privacy_note` ("Lokasi kamu disamarkan hingga ±5 km sebelum tampil ke pengguna lain") to `strings.xml`; add both accessors to `SharedStringsCatalogTest` + tracked-accessor count assertion 86 → 88 (the test asserts its referenced-accessor list size, NOT the catalog size — re-verify the literal against `main` at apply time; #234 also adds strings)
- [x] 2.6 Amend `docs/03-UX-Design.md` § canonical glyph paragraph to include the composer privacy-note shield (`verified_user` → `ic_privacy_shield`) in the bundled-glyph inventory (same-PR doc sync, per the `mobile-timeline-card-redesign` precedent)

## 3. Logo call sites (`:mobile:app`)

- [x] 3.1 `SignInScreen`: remove the `signin_screen_title` `Text` node (string retained in catalog); size the logo 96dp per frame 13; verify vertical rhythm against the frame-13 annex
- [x] 3.2 `AgeGateScreen`: re-tune the logo dp against the new full-bleed glyph (eyeball to the sign-in scale; the age-gate spec needs no delta)
- [x] 3.3 `AppShellScreen` `HomeBrandTopBar`: size the logo 40dp inside the 56dp pinned app bar per frame 1
- [x] 3.4 `RootRouterScreen` splash: re-tune the centered logo dp (splash requirement in `mobile-auth-signin` § splash composition is layout-generic — no spec delta)
- [x] 3.5 Update `SignInScreenTest`: replace the title-node assertion with the disclosure-present + title-absent pair per the modified scenario (note: the test hardcodes `TITLE = "Masuk ke NearYouID"` as a constant rather than referencing the key — a key-name grep misses it)

## 4. Composer (frame 6)

- [x] 4.1 `PostCreationScreen`: add the location chip — `locationPinContainer` container, `onLocationPinContainer` label (`post_create_location_chip`), `ic_post_location` glyph tinted `locationPin`; no coordinate/city/location-derived value rendered
- [x] 4.2 Add the privacy note below the chip: `ic_privacy_shield` tinted `success` + `post_create_privacy_note` in small `onSurfaceVariant` text
- [x] 4.3 Move the existing `N/280` counter to the bottom composer bar, right-aligned, per the frame-6 annex; no attachment toolbar
- [x] 4.4 Update `PostCreationScreenTest`: chip + privacy-note presence (static copy, no coordinate), counter bottom-bar placement via bounds comparison (the `AppShellScreenTest` bounds-math idiom), no-attachment-toolbar negative guard; existing CTA scenarios unchanged

## 5. Verification + DoD (docs/11 § 5)

- [x] 5.1 `./gradlew ktlintCheck detekt :mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest` green locally (the module is flavored — docs/11 §5 names the Dev-variant tasks; the Release run also exercises the `*ScreenTest` exclude list) plus the full pre-push gate before any push: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test`
- [x] 5.2 `./gradlew :mobile:app:linkDebugFrameworkIosSimulatorArm64` (K/N compile guard — CI is Linux-only and cannot catch iOS-specific breakage)
- [x] 5.3 Manual verification on Android emulator: sign-in, age gate, home app bar, splash, composer — light + dark; screenshots captured
- [x] 5.4 Manual verification on iOS simulator: same surfaces, light + dark; screenshots captured
- [x] 5.5 PR body updated with the light + dark screenshot evidence (DoD for UI-affecting changes) + retitle via `gh pr edit` at the feat-commit boundary
