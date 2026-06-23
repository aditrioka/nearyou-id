---
name: mobile-ui-foundation
description: Build or polish a nearyou-id mobile screen (:mobile:app, Compose Multiplatform / Material 3) against the shared design-system substrate so screens stay consistent. Encodes the UI/UX fundamentals checklist (layout/insets, theming/tokens, icons, typography, contrast, motion, touch targets, a11y, performance, copy/localization, the loading/refresh + empty/error state contract), the per-screen procedure, and the verification loop. Use when implementing/polishing/reviewing a mobile Compose screen or fixing a UI defect. NOT for backend/admin (non-Compose) work, DI plumbing with no UI, or copy-only string edits. Self-improving: append every new fundamental/anti-pattern you learn.
---

Per-screen enforcement checklist that makes each screen *consume* the shared substrate instead of reinventing layout, loading state, icons, and copy.

**Canonical contract (read first; both authoritative):**
- `openspec/specs/mobile-design-system/spec.md` — machine-checkable substrate rules (single-Scaffold inset ownership, Material icon affordances, label visibility, loading/refresh pattern, single-language Bahasa Indonesia). On disagreement with this skill, the spec wins — update this skill.
- `docs/03-UX-Design.md` § "Material 3 Design System / Foundation" + § UX Copy Strategy / Empty State (canonical copy).
- Before adopting any M3 API pattern, run a **dated WebSearch** (m3.material.io + developer.android.com); pretrained "canonical pattern" knowledge can be 1–2 years stale (see `openspec/project.md` § Pre-implementation library re-check).

Pair with the OpenSpec change carrying the behavior (`/next-change` → `/opsx:apply`); this skill is the *look + behave* layer, the spec is the *what*.

## The per-screen loop

1. Read the contract (both sources) + skim a recently-polished screen (`screens/home/HomeScreen.kt`, `screens/timeline/*`) as reference.
2. **Mockup board first.** Find your screen's frame in `dev/mockups/nearyou-screens-mockup.html` (+ `nearyou-premium-tenure-badges.html` for premium visuals) and **render it** (browser/preview/screenshot tool) — canonical look-and-feel target (docs/11 § 2.8). Translate to CMP idioms (M3 composables + theme tokens), not literal CSS; on behavior conflicts the spec wins — flag it. Only if no frame covers the screen: ask the user for inspiration screenshots/Figma *before* building — don't invent the look.
3. Build to the checklist, consuming the substrate (single Scaffold from the shell, tokens not literals, the loading/refresh pattern, `Res.drawable.*` icons, `stringResource` copy).
4. Verify on a real surface (Android emulator AND iOS sim) — screenshot, don't call it done until you've watched it work.
5. Test to the conventions below.

## Fundamentals checklist (apply to EVERY screen)

**Layout & window insets**
- [ ] The section shell owns the **single** `Scaffold` + edge-to-edge insets. Screen is **inset-free**: no own `Scaffold`/`TopAppBar`; consume the shell's `innerPadding` via `Modifier.padding(innerPadding)` + `consumeWindowInsets(innerPadding)`. (Nested Scaffolds re-apply insets → the gap bug.)
- [ ] Content fills available space (`fillMaxSize` under shell padding); no unexplained gaps.
- [ ] No redundant header duplicating the selected section/tab.

**Theming & tokens**
- [ ] Everything renders under `NearYouTheme` (light + dark) from `MaterialTheme.colorScheme`/`.typography` — never hardcoded `Color(0x...)`/`sp`. Brand accents via `ColorScheme.locationPin`/`.premiumBadge` inside a `NearYouTheme { }` scope. Verify both light and dark.

**Icons**
- [ ] Nav (bottom-nav + tabs) and action affordances (FAB) use **Material icons** via `painterResource(Res.drawable.*)` (from `:shared:resources`) — never placeholder dots. M3: unselected = outlined, selected = filled. Icon-only buttons carry a `contentDescription`, not visible text.

**Typography & contrast**
- [ ] M3 type roles (`titleLarge`, `bodyMedium`, …) from `NearYouTypography`; don't invent sizes.
- [ ] Text meets WCAG 4.5:1 (the palette's `outline` is tuned for this). Selected nav/tab labels stay visible — use `NavigationBarItemDefaults.colors()`/default `Tab` color, never a custom color that collapses to the background.

**Motion**
- [ ] Token-driven, purposeful (M3 motion); standard easings/durations; no instant state swaps where a transition reads better.

**Touch targets & interaction**
- [ ] Interactive targets ≥ 48dp; use standard components (`Button`, `FloatingActionButton`, `NavigationBarItem`, `Tab`).
- [ ] Swipeable feed tabs use a `HorizontalPager` bidirectionally synced with the `TabRow` (shared `pagerState`, `selectedTabIndex = pagerState.currentPage`, two `LaunchedEffect`s). No per-tab `NavDisplay`/`NavKey`.

**The states contract (every list/data screen)**
- [ ] Distinguish **initial load** (skeleton, no PTR spinner) from **refresh** (PTR spinner over *retained* content — list stays mounted). **Never two progress indicators at once.** `PullToRefreshBox.isRefreshing` = refresh-only.
- [ ] Cover loading/content/empty/error explicitly (no generic fallthrough); model state as a pure, Compose-free projection so it's unit-testable.
- [ ] Empty/error/rate-limit states render inside a **scrollable** so PTR works from them too; a refresh from a non-Content state retains that state.

**Copy & localization**
- [ ] All user-facing text via `stringResource(Res.string.*)` — zero hardcoded strings. **Single language: Bahasa Indonesia** (no EN/ID mix). Match canonical copy in `docs/03-UX-Design.md`/`docs/02-Product.md` byte-for-byte where pinned; flag new derived copy for UX review. (Runtime language switching is deferred — follow-up `mobile-localization-language-switching`.)

**Accessibility**
- [ ] Every icon/affordance has a `contentDescription` (decorative-only → `null`). Verify TalkBack (Android) + VoiceOver (iOS) announce nav, tab/page changes, and state changes sensibly.

**Performance**
- [ ] `LazyColumn`/`LazyRow` with stable `key`s; hoist state correctly; avoid recomposition hotspots (don't read state higher than needed). Feed load-state lives in a `HomeRoute`-scoped ViewModel so swipe/section-switch doesn't re-fetch.

## Test conventions (nearyou-id specifics)

- Robolectric `*ScreenTest` MUST be added to the **Release-variant exclude** in `mobile/app/build.gradle.kts` (`ui-test-manifest` host activity is debug-only) — verify with `:mobile:app:testDevReleaseUnitTest`, not just Debug.
- Pure state projections → commonTest (deterministic, no Compose). Serializable nav state (`Tab`/`Section`) → a `rememberSaveable` round-trip test (iOS-safe).
- iOS flow test under `src/iosTest` (NOT commonTest) with Kotlin/Native-legal function names (no `,()#`); run `:mobile:app:iosSimulatorArm64Test`.
- Real-flow async screen tests (MockEngine) need `waitUntil` polling — `waitForIdle` doesn't await the network submit (Fake flows are synchronous, real ones aren't).
- Source-scan guard tests must **strip comments first** (so the file's own KDoc doesn't trip the forbidden-token scan).
- Mobile gate before push: `./gradlew ktlintCheck detekt :mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest` (worktrees need a copied `local.properties` SDK pointer).

## Verification

Defer to `verify-loop` for bring-up — it **context-routes the surface automatically** (local → Android emulator + iOS sim; cloud sandbox → real device via Firebase Test Lab through `scripts/test_android.sh`/`scripts/run_on_device.sh`/the `device-run.yml` PR comment). Screenshot and eyeball the checklist (status-bar flush, content fills, single indicator on refresh, working PTR, real icons, visible selected label, swipe between feeds, icon-only FAB, all-Indonesian labels, light + dark). Physical-device + Test Lab use the staging flavor; iOS pod-install via `dev/scripts/ios-pod-install.sh` (sync resources before pod install or fonts crash).

## Anti-patterns (this project actually hit these)

- ❌ Nested `Scaffold`s → status-bar gap, list won't fill, broken PTR. ✅ One Scaffold at the shell; screens inset-free.
- ❌ Brand-tinted dots for icons. ✅ Material icons via `Res.drawable.*`.
- ❌ One `inFlight` boolean for both initial load and refresh → double indicator + list collapsing to a loader mid-gesture. ✅ Split `isInitialLoad`/`isRefreshing`; keep content mounted during refresh.
- ❌ `ExtendedFloatingActionButton` with a text label for the composer. ✅ Icon-only `FloatingActionButton` + `contentDescription`.
- ❌ Mixed-language labels. ✅ Single-language Bahasa Indonesia.
- ❌ Hardcoded strings/`Color(0x...)`/`sp` literals. ✅ `stringResource` + theme tokens.

## Safety

The gate command runs tests only (no mutation). The one mutating verification step — temporarily editing `App.kt` to boot straight into a target screen (see `verify-loop` §B) — MUST be reverted with `git restore App.kt` before commit.

## Self-improving rule (every run)

On discovering a new UI/UX fundamental, CMP gotcha, or anti-pattern this project hit, **append it here**. If it's machine-checkable, also propose it as a `mobile-design-system` requirement so it's enforced — durable rules belong in the spec, the procedure + project gotchas belong here.
