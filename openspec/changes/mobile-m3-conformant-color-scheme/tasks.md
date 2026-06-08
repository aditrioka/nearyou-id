## 1. Regenerate NearYouColorScheme (shared-resources)

- [x] 1.1 Replace `NearYouColorScheme.light` role values in `shared/resources/src/commonMain/kotlin/id/nearyou/resources/theme/NearYouColorScheme.kt` with the **light** column of `design.md` Decision 3 (primary pinned `#1E4FD6`, `surfaceTint = #1E4FD6`, secondary `#595D72`, tertiary `#75546F`, surface `#FAF8FF`, etc.), preserving `outline = #79747E` and `scrim = #8F0E1220`
- [x] 1.2 Replace `NearYouColorScheme.dark` role values with the **dark** column of `design.md` Decision 3 (dark `primary = #B7C4FF`, `inversePrimary = #1E4FD6` aliasing the light pinned primary, `scrim = #8F0E1220`)
- [x] 1.3 Rewrite the `NearYouColorScheme` KDoc: drop the "secondary/tertiary are NEUTRAL surfaceVariant" rationale (Decisions 2/9 of the old change); state the new architecture — MTB-tonal-spot scheme seeded from `#1E4FD6` (Color Match OFF), light `primary` pinned, `secondary`/`tertiary` are real accents, `scrim`/`outline` preserved
- [x] 1.4 Confirm `NearYouColors` (`NearYouColors.kt`) and the `ColorSchemeExtensions.kt` surface are **untouched** (locationPin/premiumBadge/success/warning/link byte-identical) — only `NearYouColorScheme.kt` changes in `:shared:resources`

## 2. Re-token the navbar override (mobile-design-system)

- [x] 2.1 In `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/shell/AppShellScreen.kt`, change `nearYouNavigationBarItemColors()` `selectedIconColor` from `onPrimaryContainer` to `MaterialTheme.colorScheme.primary` (keep `indicatorColor = primaryContainer`, `selectedTextColor = onSurface`, unselected = `onSurfaceVariant`)
- [x] 2.2 Rewrite the `nearYouNavigationBarItemColors()` KDoc and the `AppShellScreen` KDoc reference (D5): the override is now a deliberate brand-identity choice (selected nav uses the PRIMARY/cobalt family), NOT a fix for an invisible near-white `secondary` label

## 3. Update tests

- [x] 3.1 Rewrite the full-table light + dark assertions in `shared/resources/src/commonTest/kotlin/id/nearyou/resources/theme/NearYouColorSchemeTest.kt` to the `design.md` Decision 3 values (every role); keep the alias assertions that still hold (`surfaceTint`==primary, `background`==surface, `onBackground`==onSurface, dark `inversePrimary`==light primary)
- [x] 3.2 Relax the two incidental link-alias assertions in `NearYouColorSchemeTest.kt` (`nearYouColors_light_link_aliasesOnPrimaryContainer`, `nearYouColors_dark_link_matchesDarkPrimary`) to direct value checks (`link` keeps `#1740B8` / `#B3C5FF`; it no longer equals the changed `onPrimaryContainer` / dark `primary`)
- [x] 3.3 Update the navbar selected-token + WCAG-contrast assertions in `mobile/app/src/androidUnitTest/kotlin/id/nearyou/app/screens/shell/AppShellScreenTest.kt`: selected icon now resolves to `primary`; selected label contrast (`onSurface` on `surface`/`surfaceContainer`) still clears AA; add/verify the selected-icon-on-pill ≥ 3:1 check
- [x] 3.4 Grep `:shared:resources` + `:mobile:app` for any other hard-coded reference to a changed role value (e.g. `0xFFEEF0F4`, `0xFF1740B8` outside `NearYouColors`) and reconcile

## 4. Verify

- [x] 4.1 Run the mobile gate: `./gradlew :shared:resources:testDebugUnitTest :mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest` (`:shared:resources` is unflavored → `testDebugUnitTest`; `:mobile:app` is flavor-qualified) — all green
- [x] 4.2 Run lint: `./gradlew ktlintCheck detekt` — both frameworks pass
- [ ] 4.3 (Optional but recommended) Launch the app and screenshot Beranda/Notifikasi + a light/dark timeline to confirm the selected nav is brand cobalt and labels are readable (visual confirmation of the mockup-validated scheme D)

## 5. Spec + docs sync

- [x] 5.1 Run `openspec validate mobile-m3-conformant-color-scheme --strict` — passes
- [ ] 5.2 At archive: confirm `openspec/specs/{shared-resources,mobile-design-system,mobile-app-scaffold}/spec.md` reflect the MODIFIED requirements (secondary/tertiary as accents; navbar brand-family tokens; dark primary `#B7C4FF`) with no `TBD - created by archiving` placeholders left behind
