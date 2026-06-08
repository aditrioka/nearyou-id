## Why

The authenticated mobile home surface looks and behaves rough: a status-bar gap above the tab row, a post list that doesn't fill the screen, two loading indicators at once, broken pull-to-refresh, no real icons (brand-tinted dots), an invisible selected nav label, a redundant "Post dari lokasi ini" screen header, no swipe between feeds, a text-labelled post button, and mixed-language labels (English tabs, Indonesian sections). Almost all of it traces to **one architectural root cause**: three Material 3 `Scaffold`s nested inside each other — `AppShellScreen` (Scaffold + bottom `NavigationBar`) → `HomeScreen` (Scaffold + FAB + `PrimaryTabRow`) → `NearbyTimelineContent` (Scaffold + `TopAppBar`) — introduced by the [#162](https://github.com/aditrioka/nearyou-id/pull/162) bottom-nav restructure. Nested Scaffolds each re-apply window insets (the gap), each own padding (list won't fill), and the timeline's in-flight state collapses the list to a full-screen loader during refresh (the double indicator + the broken pull-to-refresh).

Each mobile screen so far was built as an isolated atomic change with **no shared layout/loading/icon/copy contract**, so every screen reinvented its own Scaffold, loading state, dot-icons, and copy language — and the result drifts. This change fixes the home surface AND, in the same cohesive pass, establishes a **reusable Material 3 design-system substrate** that every subsequent screen (profile → following → chat → search → settings) consumes — so future atomic `/next-change` picks stay atomic *and* visually/architecturally sound. The app has to look good and be performant before more is built on top of it.

## What Changes

- **NEW substrate contract (`mobile-design-system`)** — the durable, screen-agnostic rules the rest of the app inherits:
  - The app **shell owns a single `Scaffold` + edge-to-edge window insets**; section/feed/screen composables are inset-free and MUST NOT nest their own `Scaffold`/`TopAppBar`. **BREAKING** (internal): reverses the current nested-Scaffold arrangement.
  - **Material 3 icons** become the canonical affordance for bottom-nav sections, feed tabs, and the composer action — reversing the deliberate "no material-icons dependency / brand-dot" idiom.
  - `NavigationBar` items + feed `Tab`s render a **visible label in both selected and unselected states** (fixes the invisible selected label).
  - A **canonical list loading/refresh pattern**: initial load → skeleton (no pull-to-refresh spinner); refresh → pull-to-refresh spinner over retained content; never two progress indicators at once.
  - User-facing labels are **single-language Bahasa Indonesia** (no EN/ID mix), all via `:shared:resources`.
- **Home tab host (`mobile-home-tab-host`)** — `HomeScreen` drops its own Scaffold; real Material icons on sections (Home/Notifications/Person) + tabs (LocationOn/People/Public); **swipe between Nearby/Following/Global** via a `HorizontalPager` bidirectionally synced with the `PrimaryTabRow` (preserving the no-per-tab-NavDisplay / no-re-fetch invariants); the composer FAB becomes **icon-only** (`contentDescription = cta_post`, no visible text); tab labels normalized to Bahasa Indonesia (`Sekitar` / `Mengikuti` / `Global`).
- **Nearby + Global timelines (`mobile-nearby-timeline`, `mobile-global-timeline`)** — remove the inner `Scaffold` + `TopAppBar` title ("Post dari lokasi ini"); the list fills the available space; split `isInitialLoad` from `isRefreshing` so refresh keeps content visible (fixes the double indicator + restores pull-to-refresh).
- **Post creation (`mobile-post-creation`)** — the home FAB requirement changes from a labelled FAB to an icon-only `FloatingActionButton`.
- **Shared resources (`shared-resources`)** — deliver the Material icon set as **bundled XML vector drawables** in `:shared:resources` (the existing `logo_brand_*.xml` idiom), avoiding the heavy `material-icons-extended` artifact and the core-set gaps (People/Public are extended-only as of CMP 1.8.2+); change the tab string *values* to Bahasa Indonesia; retain `timeline_nearby_title` in the catalog (now unreferenced). (Bundle-vs-`material-icons-core` is settled in `design.md` with a dated-research evidence note.)
- **Docs amended** (explicit, not silent divergence): `docs/02-Product.md` + `docs/03-UX-Design.md` § UX Copy Strategy note that the location disambiguation moves from a redundant screen header to the canonical one-time onboarding hint + per-card "Diposting dari {city}" context; `docs/03-UX-Design.md` gains a "Material 3 Design System / Foundation" section codifying the substrate so future screens cite it as canonical.

**Deferred (tracked as explicit deferred requirements + `FOLLOW_UPS.md`):** runtime user-selectable language switching (`mobile-localization-language-switching`); the one-time location-disambiguation onboarding-hint implementation (`mobile-location-disambiguation-onboarding-hint`). Aesthetic refinement (exact card spacing/elevation/color from the operator's inspiration screenshots) is a design Open Question pending those screenshots — structural work proceeds now.

## Capabilities

### New Capabilities
- `mobile-design-system`: The cross-cutting Material 3 substrate contract for `:mobile:app` — single-Scaffold inset ownership (edge-to-edge), the Material icon set as the canonical navigation/action affordance, navigation/tab label visibility, the canonical list loading/refresh pattern, and the single-language Bahasa Indonesia rule. Future screen capabilities consume it.

### Modified Capabilities
- `mobile-home-tab-host`: Drop `HomeScreen`'s Scaffold; real Material icons on sections + tabs; visible selected labels; `HorizontalPager` swipe synced with the tab row (invariants preserved); icon-only composer FAB; Bahasa Indonesia tab labels.
- `mobile-nearby-timeline`: Remove the inner Scaffold + `TopAppBar` header; list fills space; separate initial-load vs refresh state (fix double indicator + pull-to-refresh); content stays visible during refresh.
- `mobile-global-timeline`: Mirror the Nearby loading/refresh + nested-Scaffold-removal + inset-free changes.
- `mobile-post-creation`: The home FAB becomes icon-only (no visible label; `contentDescription = cta_post`).
- `shared-resources`: Add the Material icons dependency; Bahasa Indonesia tab string values; retain `timeline_nearby_title` unreferenced.

## Impact

- **Modules:** `:mobile:app` (shell, home tab host, timeline screens, theme), `:shared:resources` (icons dep, tab strings). No backend, no Flyway migration, mobile-only footprint — disjoint from the only open PR ([#165](https://github.com/aditrioka/nearyou-id/pull/165), docs).
- **Dependencies:** the Material icon set is delivered as bundled vector drawables in `:shared:resources` (no `material-icons-extended` artifact). If `design.md`'s dated re-check instead favors a `material-icons-core` dependency for the in-core glyphs, that adds one `gradle/libs.versions.toml` entry; the bundled-drawable default adds none.
- **Files (indicative):** `screens/shell/AppShellScreen.kt`, `screens/home/HomeScreen.kt`, `screens/timeline/NearbyTimelineScreen.kt` + `GlobalTimelineScreen.kt`, `theme/NearYouTheme.kt`, the ViewModels' in-flight state, `shared/resources/.../values/strings.xml`, `gradle/libs.versions.toml`, the consuming `build.gradle.kts`.
- **Docs:** `docs/02-Product.md` + `docs/03-UX-Design.md` amended (UX Copy Strategy + new Design System section).
- **Tests:** Robolectric `*ScreenTest` (Release-variant exclude), commonTest state-projection (initial-load vs refresh), iOS flow test (K/N-legal names).
- **Risk:** internal layout/behavior change to the most-trafficked surface; mitigated by the preserved no-re-fetch / navigation-free / PII invariants and full test coverage. Visual aesthetics gated on operator screenshots before the apply phase lands the visual layer.
