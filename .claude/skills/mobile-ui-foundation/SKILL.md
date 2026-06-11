---
name: mobile-ui-foundation
description: Build or polish any nearyou-id mobile screen (:mobile:app, Compose Multiplatform / Material 3) to the shared design-system substrate so every screen looks good, performs well, and stays consistent. Encodes the UI/UX fundamentals checklist (layout/insets, theming/tokens, icons, typography, color/contrast, motion, touch targets, accessibility, performance, copy/localization, the loading/refresh + empty/error state contract) + the per-screen application procedure + the verification loop. Use whenever implementing a new mobile screen, polishing an existing one, reviewing mobile Compose UI, or fixing a UI/UX defect — so atomic per-screen changes inherit a sound scaffold instead of each reinventing layout. Self-improving: append every new fundamental/anti-pattern you learn.
---

This is nearyou-id's **mobile UI/UX foundation** skill. Atomic per-screen changes drift into a mess when each screen reinvents its own layout, loading state, icons, and copy. This skill is the per-screen enforcement checklist that makes every screen *consume* the shared substrate instead — so the app stays good-looking, performant, and consistent as it grows.

**Canonical contract (read first, both are authoritative):**
- OpenSpec capability `openspec/specs/mobile-design-system/spec.md` — the machine-checkable substrate rules (single-Scaffold inset ownership, Material icon affordances, label visibility, the loading/refresh pattern, single-language Bahasa Indonesia). When a rule here and the spec disagree, the spec wins; update this skill.
- `docs/03-UX-Design.md` § "Material 3 Design System / Foundation" — the human-readable design reference, plus § UX Copy Strategy / Empty State for canonical copy.
- Up-to-date Material 3 API guidance: `m3.material.io` + `developer.android.com/develop/ui/compose/*`. Do a **dated WebSearch** before adopting an M3 API pattern (the AI's "canonical pattern for X" can be 1–2 years stale — see `openspec/project.md` § Pre-implementation library re-check).

## When to use / when not

- **Use** when: implementing a new screen, polishing an existing one, reviewing mobile Compose UI, or fixing a UI/UX defect. Pair it with the OpenSpec change that carries the behavior (`/next-change` → `/opsx:apply`); this skill is the *how it should look + behave* layer, the spec is the *what*.
- **Don't use** for: backend/admin (non-Compose) work, pure data/DI plumbing with no UI surface, or copy-only string edits with no layout change.

## The per-screen loop

1. **Read the contract** (the two canonical sources above) + skim a recently-polished screen (`screens/home/HomeScreen.kt`, `screens/timeline/*`) as the reference pattern.
2. **Get visual input — the mockup board first.** Check `dev/mockups/nearyou-screens-mockup.html` (+ `nearyou-premium-tenure-badges.html` for premium-tier visuals) for the frame covering your screen, and **render it** (open in a browser / preview panel, or capture via a browser screenshot tool — whichever reads clearest); it is the canonical look-and-feel target (docs/11 § 2.8), with captions citing the governing spec per element and tagging shipped vs proposed. Translate to CMP idioms (M3 composables + theme tokens), not literal CSS; on behavior conflicts the spec wins — flag it. Only when no frame covers the screen: ask the user for inspiration screenshots/Figma *before* building — don't invent the look. (Scaffolding-menu precedent: "Visual input required before proposing.")
3. **Build to the checklist below**, consuming the substrate (single Scaffold from the shell, tokens not literals, the loading/refresh pattern, `Res.drawable.*` icons, `stringResource` Bahasa Indonesia copy).
4. **Verify on a real surface** (see Verification) — Android emulator AND iOS simulator. Screenshot. Don't call it done until you've watched it work.
5. **Test** to the conventions below.

## Fundamentals checklist (apply to EVERY screen)

**Layout & window insets**
- [ ] The app section shell owns the **single** `Scaffold` + edge-to-edge insets. Your screen is **inset-free**: no own `Scaffold`, no `TopAppBar`; consume the shell's `innerPadding` with `Modifier.padding(innerPadding)` + `consumeWindowInsets(innerPadding)`. (Nested Scaffolds re-apply insets — the gap bug.)
- [ ] Content fills the available space (`fillMaxSize` under the shell padding); no unexplained gaps or unfilled regions.
- [ ] Don't add a redundant screen header that duplicates the selected section/tab.

**Theming & tokens**
- [ ] Everything renders under `NearYouTheme` (light + dark), built from `MaterialTheme.colorScheme` / `.typography` tokens — never hardcoded `Color(0x...)` / `sp` literals. Brand accents via the `ColorScheme.locationPin` / `.premiumBadge` extensions inside a `NearYouTheme { }` scope.
- [ ] Verify both light and dark.

**Icons**
- [ ] Navigation (bottom-nav + tabs) and action affordances (FAB) use **Material icons** via `painterResource(Res.drawable.*)` (bundled in `:shared:resources`) — never brand-tinted placeholder dots. M3 convention: unselected = outlined, selected = filled. Icon-only buttons carry a `contentDescription` (a11y), not visible text.

**Typography & color contrast**
- [ ] Use the M3 type roles (`titleLarge`, `bodyMedium`, …) from `NearYouTypography`; don't invent sizes.
- [ ] Text meets WCAG 4.5:1 on its background (the palette's `outline` is tuned for this; don't downgrade). Selected nav/tab labels stay visible — use `NavigationBarItemDefaults.colors()` / default `Tab` color, never a custom color that collapses to the background.

**Motion**
- [ ] Animations are token-driven and purposeful (M3 motion); prefer the standard easings/durations. No janky/instant state swaps where a transition reads better.

**Touch targets & interaction**
- [ ] Interactive targets ≥ 48dp. Use the standard components (`Button`, `FloatingActionButton`, `NavigationBarItem`, `Tab`).
- [ ] Swipeable feed tabs use a `HorizontalPager` bidirectionally synced with the `TabRow` (shared `pagerState`, `selectedTabIndex = pagerState.currentPage`, two `LaunchedEffect`s). Don't introduce a per-tab `NavDisplay`/`NavKey`.

**The states contract (every list/data screen)**
- [ ] Distinguish **initial load** (skeleton, no pull-to-refresh spinner) from **refresh** (PTR spinner over *retained* content — the list stays mounted). **Never two progress indicators at once.** `PullToRefreshBox.isRefreshing` = refresh-only.
- [ ] Cover loading / content / empty / error explicitly (no generic fallthrough); model state as a pure, Compose-free projection so it's unit-testable.
- [ ] Empty / error / rate-limit states are rendered inside a **scrollable** so pull-to-refresh works from them too; a refresh from a non-Content state retains that state.

**Copy & localization**
- [ ] All user-facing text via `stringResource(Res.string.*)` — zero hardcoded UI strings. **Single language: Bahasa Indonesia** (no EN/ID mix). Match the canonical copy in `docs/03-UX-Design.md` / `docs/02-Product.md` byte-for-byte where pinned; flag new derived copy for UX review. (Runtime language switching is deferred — `FOLLOW_UPS.md mobile-localization-language-switching`.)

**Accessibility**
- [ ] Every icon/affordance has a `contentDescription` (decorative-only → `null`). Verify TalkBack (Android) + VoiceOver (iOS) announce navigation, tab/page changes, and state changes sensibly.

**Performance**
- [ ] `LazyColumn`/`LazyRow` with stable `key`s; hoist state correctly; avoid recomposition hotspots (don't read state higher than needed). Feed load-state lives in a `HomeRoute`-scoped ViewModel so swipe/section-switch doesn't re-fetch.

## Test conventions (nearyou-id specifics)

- Robolectric `*ScreenTest` MUST be added to the **Release-variant exclude** in `mobile/app/build.gradle.kts` (the `ui-test-manifest` host activity is debug-only) — verify with `:mobile:app:testDevReleaseUnitTest`, not just Debug.
- Pure state projections → commonTest (deterministic, no Compose). Serializable nav state (`Tab`/`Section`) → a `rememberSaveable` round-trip test (iOS-safe).
- iOS flow test under `src/iosTest` (NOT commonTest) with **Kotlin/Native-legal** function names (no `,()#`); run `:mobile:app:iosSimulatorArm64Test`.
- Real-flow async screen tests (MockEngine) need `waitUntil` polling — `waitForIdle` doesn't await the network submit (Fake flows are synchronous, real ones aren't).
- Source-scan guard tests must **strip comments first** (so the file's own KDoc — e.g. "MUST NOT …" — doesn't trip the forbidden-token scan).
- Mobile gate before push: `./gradlew ktlintCheck detekt :mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest` (worktrees need a copied `local.properties` SDK pointer).

## Verification

Defer to the `verify-loop` skill for bring-up. Minimum: run on the Android emulator AND the iOS simulator, screenshot, and eyeball the checklist (status-bar flush, content fills, single indicator on refresh, working pull-to-refresh, real icons, visible selected label, swipe between feeds, icon-only FAB, all-Indonesian labels, light + dark). Physical-device mobile testing uses the staging flavor; iOS pod-install via `dev/scripts/ios-pod-install.sh` (sync resources before pod install or fonts crash).

## Anti-patterns (this project actually hit these — don't repeat)

- ❌ Nested `Scaffold`s (shell → screen → content) → status-bar gap, list won't fill, broken pull-to-refresh. ✅ One Scaffold at the shell; screens inset-free.
- ❌ Brand-tinted dots standing in for icons. ✅ Material icons via `Res.drawable.*`.
- ❌ One `inFlight` boolean for both initial load and refresh → double indicator + the list collapsing to a loader mid-gesture (PTR "breaks"). ✅ Split `isInitialLoad` / `isRefreshing`; keep content mounted during refresh.
- ❌ `ExtendedFloatingActionButton` with a text label for the composer. ✅ Icon-only `FloatingActionButton` + `contentDescription`.
- ❌ Mixed-language labels (English tabs, Indonesian sections). ✅ Single-language Bahasa Indonesia.
- ❌ Hardcoded strings / `Color(0x...)` / `sp` literals. ✅ `stringResource` + theme tokens.

## Self-improving rule (do this every run)

When you discover a new UI/UX fundamental, a Compose Multiplatform gotcha, or an anti-pattern this project hit, **append it here** (and, if it's a machine-checkable behavior, propose it as a `mobile-design-system` requirement so it's enforced, not just documented). Keep the two in sync: durable rules belong in the spec; the application procedure + project-specific gotchas belong here.
