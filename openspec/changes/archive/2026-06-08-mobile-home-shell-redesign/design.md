## Context

The authenticated mobile surface (`:mobile:app`) is built from screens that each shipped as an isolated atomic change with no shared layout/loading/icon/copy contract. The [#162](https://github.com/aditrioka/nearyou-id/pull/162) bottom-nav restructure left **three nested Material 3 `Scaffold`s** — `AppShellScreen` (Scaffold + bottom `NavigationBar`) → `HomeScreen` (Scaffold + FAB + `PrimaryTabRow`) → `NearbyTimelineContent` (Scaffold + `TopAppBar`). Because a Compose `Scaffold` applies but does **not consume** window insets ([Android insets docs](https://developer.android.com/develop/ui/compose/system/insets)), each nested Scaffold re-applies `systemBars`, producing the status-bar gap; each owns its own content padding, so the list never fills; and the timeline's single `inFlight` boolean collapses the list into a full-screen loader during refresh, which both stacks a second indicator on the pull-to-refresh spinner and removes the scrollable the gesture needs (the "broken pull-to-refresh").

This change fixes the home surface and, in the same pass, extracts a reusable **`mobile-design-system`** substrate (the durable home for the rules that keep future atomic screen changes sound) and codifies it in `docs/03-UX-Design.md`. Constraints: KMP/Compose Multiplatform (CMP 1.10.x stream, `material3 = 1.10.0-alpha05`), Navigation 3 + Koin + `:shared:resources` CMP Resources, iOS (Kotlin/Native) is a first-class target, and the existing `HomeRoute`-scoped-ViewModel / navigation-free / PII / no-re-fetch invariants must be preserved.

## Goals / Non-Goals

**Goals:**
- One architectural fix (single-Scaffold + edge-to-edge insets) that resolves the status-bar gap, the non-filling list, the double indicator, and the broken pull-to-refresh together.
- A reusable substrate (`mobile-design-system`) every later screen consumes: inset ownership, the Material icon set, label visibility, the canonical loading/refresh pattern, single-language copy.
- Swipe between Nearby/Following/Global, icon-only composer FAB, real Material icons, visible selected labels, consistent Bahasa Indonesia labels.
- Preserve every existing invariant (no per-tab `NavDisplay`, no new tab-root `NavKey`, `HomeRoute`-scoped VMs survive swipe/section-switch with no re-fetch, navigation-free feed screens, PII discipline).

**Non-Goals:**
- Runtime user-selectable language switching (full i18n) — deferred (`mobile-localization-language-switching`).
- The live Following feed, profile, chat, search, settings screens — separate live-menu picks that will *consume* this substrate.
- Backend/API/schema changes — none (mobile-only).
- Final pixel-level aesthetic tuning — gated on the operator's inspiration screenshots (Open Question).

## Decisions

### D1 — The shell owns one `Scaffold` + edge-to-edge insets; inner composables are inset-free
`AppShellScreen` keeps the single `Scaffold` (its `bottomBar` = `NavigationBar`); `HomeScreen`, `NearbyTimelineContent`, `GlobalTimelineContent` **drop their `Scaffold`/`TopAppBar`** and render their body under the shell's `innerPadding`, calling `Modifier.consumeWindowInsets(innerPadding)` so insets are not re-applied deeper. The Android entry already calls `enableEdgeToEdge()`; the shell `Scaffold`'s `contentWindowInsets` resolves system bars once.
*Evidence (verified 2026-06-08, [Android insets](https://developer.android.com/develop/ui/compose/system/insets) + [Material insets](https://developer.android.com/develop/ui/compose/system/material-insets)):* Scaffold applies-but-does-not-consume insets; the canonical fix is one inset-owning Scaffold + `consumeWindowInsets` on nested content, and to avoid redundant inset modifiers. *Alternative rejected:* keep nested Scaffolds and manually subtract insets — fragile, exactly the current bug.

### D2 — `HorizontalPager` synced bidirectionally with `PrimaryTabRow`
`HomeScreen` wraps the three feed bodies in a `HorizontalPager` (page order = tab order: 0 Sekitar, 1 Mengikuti, 2 Global). `PrimaryTabRow(selectedTabIndex = pagerState.currentPage)`; a `LaunchedEffect` on a tab-click target calls `pagerState.animateScrollToPage(...)`; a second `LaunchedEffect(pagerState.currentPage, pagerState.isScrollInProgress)` writes the settled page back to the serializable `Tab` selection. The existing `@Serializable Tab` + `rememberSaveable` saved-state contract is preserved (the `Tab` remains the durable selection; the pager is the gesture surface). All three pages compose directly under the `HomeRoute` scope (NO per-tab `NavDisplay`, NO new `NavKey`); the `HomeRoute`-scoped feed ViewModels are untouched, so swipe/section-switch never re-fetches.
*Evidence (verified 2026-06-08, [Compose Pager docs](https://developer.android.com/develop/ui/compose/layouts/pager)):* shared `pagerState` + `selectedTabIndex = pagerState.currentPage` + two `LaunchedEffect`s is the documented two-way TabRow↔Pager binding. *Note:* the Nearby page nests a vertical `LazyColumn` inside the horizontal pager — standard, no nested-scroll conflict.

### D3 — Split `isInitialLoad` from `isRefreshing`; content stays mounted during refresh
The timeline ViewModels expose two flags instead of one `inFlight`: `isInitialLoad` (no content yet) and `isRefreshing` (a user-initiated reload while content exists). The pure projection maps initial-load → a skeleton state (no pull-to-refresh spinner) and, during a refresh of loaded content, **keeps returning the `Content` state** (the `LazyColumn` stays mounted so the gesture target persists). `PullToRefreshBox(isRefreshing = isRefreshing)` (refresh-only) shows exactly one indicator; the in-content `CircularProgressIndicator` is shown only on the empty initial load. Result: never two indicators; pull-to-refresh works because the scrollable is never torn down.
*Evidence (verified 2026-06-08, [Material 3 PullToRefresh API](https://developer.android.com/reference/kotlin/androidx/compose/material3/pulltorefresh/package-summary)):* `PullToRefreshBox` "expects a scrollable layout as content"; `isRefreshing` drives only the indicator and must be set false on completion. *Alternative rejected:* keep one `inFlight` and special-case in the composable — the bug is structural in the projection, so the fix belongs there (it's the substrate loading contract).

### D4 — Deliver the Material icon set as bundled vector drawables in `:shared:resources`
Add the needed glyphs as XML vector drawables under `shared/resources/src/commonMain/composeResources/drawable/` (e.g. `ic_nav_home`, `ic_nav_notifications`, `ic_nav_profile`, `ic_tab_nearby`, `ic_tab_following`, `ic_tab_global`, `ic_action_compose`), each with a filled + outlined variant where the M3 selected/unselected convention needs it, accessed via `painterResource(Res.drawable.*)`. This mirrors the existing `logo_brand_*.xml` idiom, ships exactly the glyphs used (best performance, tree-shaken by construction), and sidesteps the core-set gaps.
*Evidence (verified 2026-06-08, [CMP 1.8.2 release notes](https://kotlinlang.org/docs/multiplatform/whats-new-compose-180.html) + [JetBrains material-icons-extended](https://central.sonatype.com/artifact/org.jetbrains.compose.material/material-icons-extended)):* as of CMP 1.8.2+ there is no transitive `material-icons-core`; `material-icons-extended` is "very large and should not be included directly"; **People and Public (Following/Global tab icons) are not in the core set** — so a core dependency alone cannot supply the tab icons, and extended is discouraged. *Alternatives:* (a) `material-icons-core` for the 5 in-core glyphs + bundle the 2 gaps — viable but mixes two delivery mechanisms; (b) `material-icons-extended` — one line but a heavy artifact, weak iOS/K-Native tree-shaking. Bundled drawables are the cleanest + most performant + convention-aligned. The source glyphs are the official Material Symbols (Apache-2.0), recorded in `design.md`/asset provenance. **Open Question OQ1** confirms bundle-vs-core at apply-time with a dated re-check.

**Apply-time re-check (OQ1 — 2026-06-08, WebSearch "Compose Multiplatform material icons 2026 core vs extended"):** confirmed the original Material Icons library is being discontinued for build-performance reasons (≈10k Kotlin files) and the ecosystem now recommends shipping the exact glyphs you need as **Android XML vector drawables** (cross-platform via `VectorPainter`/`painterResource`), which is the bundled-drawable approach. **Verdict: adopt the bundled vector-drawable default (D4) — add NO `material-icons-core` dependency, NO `gradle/libs.versions.toml` entry** (task 2.3 → no-op). Since the feed tabs are now text-only (D10), the People/Public core-set gap is moot anyway. No divergence from D4.

### D5 — Use M3 default `NavigationBar`/`Tab` theming for guaranteed label visibility
Replace the custom brand-dot icon + ad-hoc colors with real icons + `NavigationBarItemDefaults.colors()` / default `Tab` content color, so the selected label uses `onSurface`/`onSecondaryContainer` tokens and is always visible. The invisible-selected-label bug came from the custom composition; M3 defaults fix it and become the substrate rule.

### D6 — Remove the redundant timeline header; preserve disambiguation via the onboarding hint (amend docs)
Drop the `NearbyTimelineContent` `TopAppBar` title ("Post dari lokasi ini"). It duplicates the selected **Beranda** section + **Sekitar** tab — a Material 3 redundancy. The string's *purpose* in `docs/02-Product.md` + `docs/03-UX-Design.md` § UX Copy Strategy is **disambiguation** ("posts from this location" vs "people around you"), not labeling — so removing the header without replacing the disambiguation would silently drop an anti-misinterpretation measure. We therefore (a) AMEND both docs to record that the disambiguation moves to the canonical one-time onboarding hint (`docs/03-UX-Design.md` line 16) + the per-card "Diposting dari {city}" context, and (b) retain `timeline_nearby_title` in the catalog (unreferenced), matching prior retention precedent. Implementing the onboarding hint itself is deferred (`mobile-location-disambiguation-onboarding-hint`) — captured as an explicit deferred requirement so the disambiguation isn't lost, only relocated.

### D7 — Single-language Bahasa Indonesia; tab wording flagged for UX review
All user-facing labels become Bahasa Indonesia. Tab values change to `tab_nearby="Sekitar"`, `tab_following="Mengikuti"`, `tab_global="Global"` (section labels Beranda/Notifikasi/Profil already Indonesian, unchanged). These tab strings are **derived copy flagged for UX review** (the docs pin the timeline *header* + empty-state copy, not the tab labels), consistent with how `timeline_limit_hard/soft` were introduced. **Open Question OQ2.**

### D8 — `mobile-design-system` is the substrate's spec home; `docs/03` gains a Design System section
The cross-cutting rules (D1, D3, D4, D5, D7) live in the new `mobile-design-system` capability so future screen changes MODIFY-by-consuming them rather than re-deriving. `docs/03-UX-Design.md` gains a "Material 3 Design System / Foundation" section as the human-readable canonical reference. This is the mechanism that keeps OpenSpec docs in sync across the multi-phase UI effort.

### D9 — Aesthetic layer informed by operator screenshots
Structural work proceeds now; the aesthetic target is set by D10 (the operator's inspiration screenshots arrived 2026-06-08). OQ3 is resolved.

### D10 — Aesthetic spec from the operator's inspiration screenshots (resolved OQ3)
The operator supplied 5 screenshots: 2 of the current broken nearyou-id state (confirming the diagnosis — incl. the invisible "Beranda" label under the orange dot), and 3 inspiration references: **X (Twitter)** (text feed, text tabs + underline, circular `+` FAB, Material bottom-nav), and **Niche/"W"** (image-masonry feed, text tabs + underline, distance slider, Material bottom-nav with center `+`). Decisions:

- **Critical content constraint:** the inspiration references 3 & 4 are **image-feed masonry grids**, but **nearyou-id posts are text-only** (image upload is Phase 4 / Month 6, not built). We therefore adopt the references' **layout language** (clean, content-forward, real Material icons, M3 polish) applied to **text cards** — i.e. the **X text-feed model (reference 5)**, NOT the photo-masonry. The image-masonry look is revisited when image posts ship (Phase 4). This is stated so there is no expectation that the feed becomes a photo grid now.
- **Feed tabs:** text-only with the M3 `PrimaryTabRow` underline indicator (selected = primary text + underline; unselected = `onSurfaceVariant`) — matching all three references, which use text tabs with no icons. This **reverses** the earlier tab-icon decision (the operator's "add icons" request was specifically for the bottom navigation).
- **Bottom nav:** M3 `NavigationBar` with real Material icons (Home / Notifications-bell / Person), `NavigationBarItemDefaults` colors, the default M3 selected-indicator pill, and **visible labels in both states** (fixes the invisible "Beranda").
- **Composer FAB:** circular **icon-only** `FloatingActionButton` (`+` / add) at the bottom-end (X-style, reference 5). (A center-nav `+`, reference 4, is a viable alternative but a larger restructure — deferred.)
- **Post card (text):** X-style content-forward layout — content (`bodyLarge`), a metadata row with a Material **location** icon + city + distance + relative time, and a counts row with Material **like** + **reply** icons + counts — replacing the brand dots (within the existing PII-safe display fields; no new fields, no coordinates). Matches references 3 (♡/💬/🕐 footer) + 5 (action row).
- **Color:** retain brand cobalt `#1E4FD6` (primary) + coral `locationPin` — already consistent with the current app's blue; the references' purple is their brand, not adopted.
- **Explicitly deferred from the references:** the **distance slider** ("CLOSER—FARTHER", references 3 & 4) → the `mobile-nearby-radius-slider` follow-up (Free tier is fixed 20 km); the **image-masonry feed** → Phase 4 image posts; **filter chips** (reference 3 Hot/Global/Local) → not adopted (we use tabs).

## Risks / Trade-offs

- **Touching the most-trafficked surface** → Mitigation: every existing invariant (no-re-fetch, navigation-free, PII, saved-state) is re-asserted as a preserved scenario in the specs + full Robolectric/common/iOS test coverage; the change is behavior-preserving except where explicitly specified.
- **Pager state persistence on Kotlin/Native** → Mitigation: keep the `@Serializable Tab` in `rememberSaveable` as the durable selection source and verify `rememberPagerState` survives process death on iOS sim during apply (`:mobile:app:iosSimulatorArm64Test`).
- **Bundled icon drawables are manual to author** (SVG→vector-drawable, like the logo conversion) → Mitigation: small fixed set (~7 glyphs), provenance recorded; OQ1 keeps the core-dependency fallback open if conversion proves costly.
- **Removing the disambiguation header** → Mitigation: D6 relocates (not drops) the disambiguation + amends docs + files the onboarding-hint follow-up.
- **Nested vertical list inside horizontal pager** → low risk; standard Compose pattern, but verify fling/scroll feel on device during apply.

## Migration Plan

No data migration (mobile UI only; no Flyway, no API). Rollout is the normal feature-branch → squash-merge → staging auto-deploy. Rollback = revert the squash commit. No feature flag needed; the change is self-contained to `:mobile:app` + `:shared:resources`.

## Open Questions

- **OQ1 (icon delivery):** Confirm bundled vector drawables vs a `material-icons-core` dependency at apply-kickoff with a dated re-check (per `openspec/project.md` § Pre-implementation library re-check). Default: bundled drawables (D4).
- **OQ2 (tab wording):** Confirm `Sekitar` / `Mengikuti` / `Global` (or operator-preferred Bahasa Indonesia tab labels) — derived copy, low-cost to change.
- **OQ3 (aesthetics): RESOLVED** by D10 — operator screenshots received 2026-06-08; aesthetic target set (X-style text feed + text tabs + Material bottom-nav/FAB/card icons; image-masonry + distance-slider deferred). Final pixel-level spacing/elevation tuning happens during apply against D10.
- **OQ4 (pager + accessibility):** Confirm TalkBack/VoiceOver announce tab + page changes correctly with the synced pager (verify during apply per [Compose accessibility](https://developer.android.com/develop/ui/compose/accessibility)).

**Apply-time M3 API re-check (task 1.2 — 2026-06-08, WebSearch + developer.android.com/m3.material.io):** no divergence found from the patterns this design assumes —
- `PullToRefreshBox(isRefreshing = …, onRefresh = …)` still "expects a scrollable layout as content"; `isRefreshing` drives only the indicator. The non-`Content` states (empty/error/hard-limit) are therefore rendered inside a single-item scrollable so the gesture is recognized from them (D3 / task 7.5).
- `HorizontalPager` ↔ `PrimaryTabRow` two-way binding is still the documented pattern: shared `rememberPagerState`, `PrimaryTabRow(selectedTabIndex = pagerState.currentPage)`, a `rememberCoroutineScope` tab-tap → `animateScrollToPage`, and a settled-page write-back (D2).
- Edge-to-edge: `enableEdgeToEdge()` (already in `MainActivity`) + the single shell `Scaffold` owning `contentWindowInsets`, with `Modifier.consumeWindowInsets(innerPadding)` on the body so nested content does not re-apply system-bar insets (D1).
- `NavigationBarItemDefaults.colors()` remains the default item-theming entry point for guaranteed selected-label visibility (D5).
- `rememberPagerState` uses a `Saver` (not reflection), so saved-state survives process death on Kotlin/Native; the durable selection is still the `@Serializable Tab` in `rememberSaveable` (verified on the iOS sim during apply, task 10.4).

**OQ2 (tab wording) + OQ3 (aesthetics):** RESOLVED in `design.md` (D7 `Sekitar`/`Mengikuti`/`Global`; D10 operator screenshots received 2026-06-08). Proceeding with the documented defaults (task 1.3).
