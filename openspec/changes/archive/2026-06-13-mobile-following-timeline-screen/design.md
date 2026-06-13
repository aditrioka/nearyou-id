# Design: mobile-following-timeline-screen

## Context

`mobile-home-tab-host` shipped the three home feeds as a `PrimaryTabRow` + `HorizontalPager` under the `HomeRoute` scope: Nearby (`NearbyTimelineScreen`) and Global (`GlobalTimelineScreen`) are live, the Following tab is a `FollowingPlaceholderScreen` that issues no fetch. The placeholder requirement explicitly anticipated this change ("which will MODIFY this requirement to introduce the live feed"). The backend `GET /api/v1/timeline/following` is fully shipped (`following-timeline` spec) and already carries the author-identity fields `mobile-timeline-card-redesign` added for exactly this consumer. The Global timeline mobile seam (`mobile-global-timeline`) is the closest precedent: Following is Global **minus the spatial filter** (no `lat`/`lng`/`radius_m`, no distance) **plus** a Following-specific directive empty state.

## Goals / Non-Goals

**Goals**

- Replace `FollowingPlaceholderScreen` with a live `FollowingTimelineScreen` consuming `GET /api/v1/timeline/following`, reusing the Global seam's layering (ApiClient → Repository → sealed Outcome → HomeRoute-scoped ViewModel → pure UiState projection → screen) verbatim where it applies.
- Reuse the shipped shared seams — `mobile-post-card`, `InlineLikeController` + `LikeFlow`, `SessionIdProvider`, `mobile-cap-upsell-dialog` — with **zero** duplication.
- Render the Following-specific directive empty state ("*Kamu belum mengikuti siapa pun…*" + "*Lihat Global*") per `docs/03-UX-Design.md` § Empty State.

**Non-Goals**

- No backend change, no Flyway migration, no new library pin (read-only consumer of a shipped endpoint).
- No infinite scroll / load-more (deferred, issue [#188](https://github.com/aditrioka/nearyou-id/issues/188) extended to Following).
- No tap-author → profile navigation (the in-flight `mobile-profile-screen`, issue [#196](https://github.com/aditrioka/nearyou-id/issues/196)); the card renders identity only.
- No per-tab `NavDisplay` back stack / new `NavKey` (issue [#189](https://github.com/aditrioka/nearyou-id/issues/189)); the Following page composes directly under `HomeRoute`.

## Decisions

### D1 — Mirror the Global seam, not the Nearby seam

Following has no spatial filter, so it mirrors **Global** (no `LocationProvider`, no `lat`/`lng`/`radius_m`, no `distanceM`, no `DistanceRenderer`) rather than Nearby. The `FollowingTimelineApiClient` / `FollowingTimelineRepository` / `FollowingTimelineFlow` trio, the `FollowingTimelineOutcome` sealed type, the `FollowingTimelineViewModel`, and the `FollowingTimelineUiState` projection are 1:1 analogues of their `Global*` counterparts, differing only in the endpoint path (`/api/v1/timeline/following`) and the empty-state copy (D3). This keeps the Pattern Registry honest — one timeline seam, three instances.

### D2 — Package shape: colocate with the Nearby/Global siblings (legacy `screens/timeline/` + `timeline/`), not the docs/11 §2.1 target shape

docs/11 §2.1 prescribes the target package shape (`ui/<feature>/`, `data/<feature>/`). The **entire** timeline family (`NearbyTimeline*`, `GlobalTimeline*`, their UiState/ViewModel, the ApiClient/Repository/Flow trios) lives in the legacy flat `screens/timeline/` + `timeline/` packages, and `mobile-home-tab-host`'s pager references those paths directly. Moving only Following to the target shape would **orphan** it from its two siblings and fork the naming-coherence rule (docs/11 §4: "follow the existing feature's naming when extending it"). Decision: colocate `FollowingTimeline*` with its siblings in `screens/timeline/` + `timeline/`. This is **not** a Pattern-Registry deviation requiring a docs/11 amendment — it is consistency with an existing feature family; the eventual target-shape migration of the whole timeline family is a separate mechanical-move change (out of scope here).

### D3 — Following-specific directive empty state (the one behavioral divergence from Global)

Global-empty is an edge case rendered as the loading skeleton (`timeline_loading`). Following-empty is a **real, expected** state (caller follows nobody, or follows people with no recent posts) and `docs/03-UX-Design.md` § Empty State mandates "direct user to Nearby/Global." So the `Empty` `FollowingTimelineUiState` member renders the directive copy `timeline_following_placeholder` (the same string the retired placeholder used) **plus** a `cta_see_global` ("*Lihat Global*") control. Both strings already exist in `:shared:resources` (verified). The empty state is rendered inside a scrollable so pull-to-refresh still works from it (canonical loading/refresh pattern).

### D4 — The "Lihat Global" CTA switches the Home pager via a hoisted `onSeeGlobal` callback

The empty state's "*Lihat Global*" control must move the user to the Global feed. The pager state lives in `HomeScreen` (the Following page composes under it), so `FollowingTimelineScreen` hoists an `onSeeGlobal: () -> Unit` lambda; `HomeScreen` wires it to `pagerState.animateScrollToPage(GLOBAL_PAGE)` (the same mechanism the tab row already uses). The screen stays navigation-free and pager-agnostic. This is additive to the home-tab-host wiring (no new `NavKey`).

### D5 — Inline like, reply shortcut, and whole-card tap reuse the shipped seams verbatim

The Following surface MUST NOT introduce a second copy of the optimistic-like lifecycle or a second like client/repository. `FollowingTimelineViewModel` delegates to the **shared** `InlineLikeController` driving the `LikeFlow` Koin singleton (the existing `PostDetailRepository`), exactly as `NearbyTimelineViewModel` / `GlobalTimelineViewModel` do. `onOpenPost` / `onOpenPostReply` are hoisted lambdas carrying the card's non-PII display fields with `distanceM = null`; the home-tab-host call site pushes `PostDetailRoute` (`focusReplyComposer = false` for the whole-card tap, `true` for the reply shortcut). The cap-upsell reuses `mobile-cap-upsell-dialog` with the `post_detail_likes_cap_upsell` body copy; the one-shot cap state is cleared via an `onLikeCapDialogDismissed()` callback (docs/11 §2.2 — nullable state, no `Channel`/`SharedFlow`).

### D6 — `mobile-home-tab-host` MODIFY scope

The Following pager page swaps from `FollowingPlaceholderScreen` to `FollowingTimelineScreen`; the host hoists `onOpenPost`/`onOpenPostReply` (and `onSeeGlobal`) into the Following page; the HomeRoute-scoped no-re-fetch invariant now enumerates Following. The `FollowingTabNoFetchScanTest` (which asserts the Following tab issues no fetch) is **removed** — the live feed now fetches on first display. The "deferred placeholder, no fetch" requirement is REMOVED and replaced by the live-feed requirement.

### Standards conformance (docs/11)

Builds on the listed Pattern-Registry patterns with **no new pattern**: **state** (§2.2) — HomeRoute-scoped ViewModel via `viewModel { }`, `isInitialLoad`/`isRefreshing` as separate fields, one-shot cap state as nullable cleared via callback (no event bus); **navigation** (§2.3) — no new `NavKey`, the Following page composes directly under `HomeRoute`, tabs are pager state not nav destinations; **data layer** (§2.6) — `FollowingTimelineApiClient` (DTOs colocated, wire-truth field names from `TimelineRoutes.kt`) + `FollowingTimelineRepository` + sealed `FollowingTimelineOutcome`, one shared `HttpClient`/`Auth` plugin, no ad-hoc client; **testing** (§2.7) — pure projection + repository in commonTest, screen behavior in Robolectric `*ScreenTest` (Release-variant excluded), iOS behavior in `iosTest`; **UI substrate** (`mobile-design-system`) — single shell `Scaffold`, inset-free screen, M3 icons, visible labels, canonical loading/refresh, single-language Bahasa Indonesia; **mockup** (§2.8) — `nearyou-screens-mockup.html` frame 1 (Beranda feed) governs look/layout, Following reuses the already-conformant shared `mobile-post-card`. The only design nuance declared is the package-shape choice (D2), which is consistency-with-an-existing-family, **not** a Pattern-Registry fork — so no docs/11 amendment is required.

## Risks / Trade-offs

- **Overlap with the in-flight `mobile-profile-screen` (PR #245)** — both may touch `AppEntryProvider.kt` / `HomeScreen.kt` (profile builds the Profil bottom section + a profile route; this builds the Following top-tab body). The regions are distinct; whichever merges second does a routine rebase. No shared migration.
- **Demo dependency on the follow action** — until `mobile-profile-screen` lands the follow UI, a real device shows the directive empty state (caller follows nobody). This is the **correct** state, fully tested against fakes; it is not a blocker for shipping this change.
- **Re-stating five `mobile-home-tab-host` requirements** in the delta carries transcription risk; mitigated by mirroring the shipped spec text and validating before push.

## Migration Plan

Additive on the mobile side: `FollowingPlaceholderScreen.kt` is deleted and its sole call site (the Following pager page) is repointed to `FollowingTimelineScreen`. No data migration. The retired `timeline_following_placeholder` string is **reused** by the live feed's empty state (not removed).

## Open Questions

None blocking. The "Lihat Global" empty-state CTA (D4) is included because both strings exist and `docs/03` mandates the directive; if review prefers a copy-only empty state without the CTA, dropping the `onSeeGlobal` wiring is a trivial scope reduction.
