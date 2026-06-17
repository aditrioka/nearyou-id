## Why

Five `:mobile:app` list surfaces already parse and retain a pagination `nextCursor` but never consume it, so each shows only its first page (≤ 30 rows) forever — the Nearby / Following / Global timeline feeds, the Notifications list, and the post-detail Replies list. The backend has been keyset-paginated since each endpoint shipped; the cursor plumbing (DTO field + `cursor`-accepting client call) is already in place on every surface. Only the mobile load-more UX (scroll-to-end detection + append) is missing. This was deliberately deferred at scaffold time and is tracked as a single follow-up, GitHub issue [#188](https://github.com/aditrioka/nearyou-id/issues/188). A real feed and a real reply thread need pagination; this change closes that gap on all five surfaces at once, with ONE shared pattern so the surfaces don't drift.

## What Changes

- Add a **single canonical load-more (infinite-scroll) pattern** to the `mobile-design-system` substrate spec: a third loading dimension (an end-of-list **footer** spinner) layered on top of the existing initial-load-vs-refresh split, an **end-reached** terminal (no more requests once `nextCursor` is null), and a **non-destructive load-more error footer** (retry without tearing down the loaded list). Scroll-end detection uses `LazyListState` + `derivedStateOf` (docs/11 § 2.4); every `items()` keeps a stable `key` + `contentType`.
- Extract **one shared, generic, Compose-free load-more controller** in commonMain (the `ui/timeline/InlineLikeController` rule-of-three precedent) owning the appended list + current cursor + `isLoadingMore` + `endReached` + `loadMoreError`. Each surface's state holder holds its own instance — no per-surface copies of the lifecycle.
- Wire load-more onto each surface by consuming the shared controller:
  - **Nearby / Following / Global feeds** — extend each `*TimelineFlow` with a `loadMore(...)` member; the `*TimelineViewModel` appends pages. Nearby reuses its first-page anchor coordinate for every page (the timeline cursor is chronological, so ordering is anchor-independent; reuse is for radius stability + avoiding redundant GPS).
  - **Notifications list** — extend `NotificationsFlow.loadMore(cursor)`; `NotificationsViewModel` appends. Mark-read / mark-all-read in-place mutations continue to work over the grown list.
  - **Post-detail Replies** — introduce a `PostDetailViewModel` (NavEntry-scoped, mirroring the timeline-VM migration in [#167](https://github.com/aditrioka/nearyou-id/pull/167)) to own the replies list + paging robustly; today that state is composition-local. The optimistic reply **prepend** is preserved.
- No backend change. No new third-party dependency. No new Flyway migration.

## Capabilities

### New Capabilities

<!-- None. The cross-cutting load-more pattern + shared controller are added to the existing `mobile-design-system` substrate spec (its stated home for the canonical list loading/refresh pattern), not a new capability. -->

### Modified Capabilities

- `mobile-design-system`: ADD the canonical list load-more (infinite-scroll) pattern requirement (footer state, end-reached, non-destructive error, scroll-end detection) + the shared generic load-more controller requirement.
- `mobile-nearby-timeline`: flip "infinite scroll is deferred" → cursor load-more is wired (anchor-reuse paging) + the append/end-reached/error scenarios.
- `mobile-following-timeline`: flip "infinite scroll is deferred" → cursor load-more is wired + scenarios.
- `mobile-global-timeline`: flip "infinite scroll is deferred" → cursor load-more is wired + scenarios.
- `mobile-notifications-list`: flip "infinite scroll is deferred" → cursor load-more is wired (mark-read survives append) + scenarios.
- `mobile-post-detail`: PARTIAL flip — KEEP the by-id-fetch deferral ([#202](https://github.com/aditrioka/nearyou-id/issues/202), separate concern), remove ONLY the replies-load-more deferral; add the `PostDetailViewModel`-owned replies load-more requirement + scenarios.

## Impact

- **Code (`:mobile:app`, commonMain + androidUnitTest + iosTest):**
  - New: a shared generic load-more controller (`ui/timeline/`), a `PostDetailViewModel`.
  - Modified: `NearbyTimelineFlow` / `FollowingTimelineFlow` / `GlobalTimelineFlow` / `NotificationsFlow` (+ their repositories) gain a `loadMore(...)` member; the four existing feed/notifications ViewModels append pages; `NearbyTimelineViewModel` retains the first-page anchor; `PostDetailScreen` migrates replies state into the new ViewModel and adds scroll-end detection in its shared `LazyColumn`; each Screen renders the load-more footer states.
  - API clients are unchanged (they already accept `cursor`).
- **Specs:** the 6 deltas above.
- **Tests:** per surface — commonTest ViewModel/controller (append, end-reached, non-destructive load-more error, in-flight guard), commonTest UiState projections where present, Robolectric `*ScreenTest` (scroll-end triggers load-more; footer states) added to the Release-variant exclude, MockEngine client tests asserting cursor pass-back on the follow-up request.
- **Follow-ups:** closes [#188](https://github.com/aditrioka/nearyou-id/issues/188) (all five surfaces) at archive. The `PostDetailViewModel` migration of the remaining like + reply-composer composition-local state is explicitly out of scope (noted as a follow-up).
- **No** backend, dependency (`gradle/libs.versions.toml`), migration, or admin/iOS-native impact.
