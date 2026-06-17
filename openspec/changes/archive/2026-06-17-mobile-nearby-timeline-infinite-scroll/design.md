## Context

Five `:mobile:app` paginated list surfaces render only their first page and never consume the `nextCursor` they already parse + retain: the Nearby / Following / Global timeline feeds, the Notifications list, and the post-detail Replies list. The backend keyset-paginates all five; the mobile API clients already accept an optional `cursor`. The load-more UX (scroll-to-end detection + append + footer state) is the only missing piece. This was deferred at scaffold time and consolidated under one tracking issue, [#188](https://github.com/aditrioka/nearyou-id/issues/188).

Verified current state (in-repo):
- **Timeline cursor is chronological** — `NearbyTimelineService.kt:53` / `GlobalTimelineService.kt:41` / `FollowingTimelineService.kt:40` all build `Cursor(createdAt = last.createdAt, id = last.id)`. Pagination ordering is therefore **independent of the Nearby query anchor**; the `lat`/`lng` only drive the 20 km radius filter.
- **Four surfaces already have a ViewModel** with the `isInitialLoad` / `isRefreshing` two-flag split + `reload()` (the #167 migration): `NearbyTimelineViewModel`, `FollowingTimelineViewModel`, `GlobalTimelineViewModel`, `NotificationsViewModel`. Nearby + Global already share `ui/timeline/InlineLikeController` — the rule-of-three extraction precedent this change mirrors.
- **Post-detail has NO ViewModel** — replies state is composition-local `var`s in `PostDetailScreen.kt`; replies render as `items()` inside the SAME `LazyColumn` as the post header + like row (the composer is a separate `bottomBar`). A new reply is optimistically **prepended**.

## Goals / Non-Goals

**Goals:**
- One canonical load-more (infinite-scroll) pattern, declared once in `mobile-design-system`, applied uniformly to all five surfaces (anti-patchwork).
- Append-on-scroll-end with a non-destructive footer (spinner / retry-on-error / end-reached), never colliding with the initial-load skeleton or the pull-to-refresh indicator.
- Fully close [#188](https://github.com/aditrioka/nearyou-id/issues/188).

**Non-Goals:**
- No backend change, no new third-party dependency, no Flyway migration (cursor plumbing already ships).
- No change to the existing first-page-load, pull-to-refresh, inline-like, mark-read, or reply-post behaviors beyond what append requires.
- **Out of scope:** migrating the remaining post-detail like + reply-composer composition-local state into `PostDetailViewModel` (this change moves only the replies-list + paging state). The residual migration is noted as a follow-up.
- No load-more for `search` (deliberately OFFSET-paginated per docs/11 §3.3) — not a `#188` surface.

## Decisions

### D1 — Scope = all 5 surfaces; name retained as `mobile-nearby-timeline-infinite-scroll`
The operator confirmed full-#188 scope. The "nearby" in the change name is a historical artifact (the change was named when Nearby was the only feed); the 3 timeline specs, all the `*Flow.kt` deferral comments, and #188 itself reference this exact name, so it is retained for continuity rather than renamed to `mobile-timeline-infinite-scroll`. **Alternatives:** rename to `mobile-list-pagination` (rejected — breaks the 4+ canonical deferral pointers + #188 for cosmetic gain).

### D2 — One shared, generic, Compose-free load-more controller
Extract a single `LoadMoreController<T>` (target package `ui/timeline/`, commonMain — the `InlineLikeController` precedent) owning: the appended item list, current cursor, `isLoadingMore`, `endReached`, `loadMoreError`, and a per-instance in-flight guard. It takes a `suspend (cursor) -> PageResult<T>` fetch lambda. Each of the five ViewModels holds its own instance. **Alternatives:** per-surface copies of the append/guard/cursor logic (rejected — five-way drift is exactly the patchwork this change exists to prevent; rule-of-three is decisively met at five sites).

### D3 — The canonical load-more pattern is ADDED to the `mobile-design-system` spec
The pattern's home is the `mobile-design-system` substrate spec (its stated purpose already owns "the canonical list loading/refresh pattern"). Adding the load-more sub-pattern THERE — not in a per-surface spec — means the pattern is extended in its registry home, satisfying the docs/11 §Pattern-Registry anti-patchwork gate (no second/forked pattern). It layers a **third loading dimension** on the existing `isInitialLoad`/`isRefreshing` split: a list-end **footer** (spinner while a page loads; a non-destructive retry footer on error that keeps the loaded items; nothing when end-reached), shown never-simultaneously with the skeleton or the pull-to-refresh indicator. Scroll-end detection uses `LazyListState` + `derivedStateOf` (rate-limited reads, docs/11 §2.4); every `items()` keeps a stable `key` + `contentType`.

### D4 — Nearby load-more reuses the first-page anchor coordinate
`GET /api/v1/timeline/nearby` requires `lat`/`lng`/`radius_m` on every request. Because the backend cursor is chronological `(createdAt, id)` (anchor-independent ordering — verified at `NearbyTimelineService.kt:53`), reusing the page-1 anchor for every subsequent page is ordering-safe and is preferred over re-acquiring a fresh device fix per page: it gives a stable 20 km radius across pages (no boundary churn) and avoids a GPS acquisition per scroll. Mechanism: the Nearby flow exposes the resolved anchor to `NearbyTimelineViewModel` (carried on the `Loaded` outcome, the same place the raw per-post coordinates already live), the VM retains it, and `loadMore(cursor, anchor)` forwards it to the ApiClient (which already accepts `lat`/`lng`). The retained anchor is **never rendered or logged** (unchanged PII discipline). Global + Following carry no spatial params, so their `loadMore(cursor)` is cursor-only. **Alternatives:** re-acquire the coordinate per page (rejected — extra latency/battery + radius-boundary flicker for no correctness gain); cache the anchor in the repository singleton (rejected — mutable singleton request-state smell; the VM is the correct owner).

### D5 — Introduce `PostDetailViewModel` for the replies list + paging
To make replies paging robust and keep the load-more pattern uniform across all five surfaces, introduce a `PostDetailViewModel` (resolved via `viewModel { … }` scoped to the post-detail NavEntry, mirroring the #167 timeline-VM migration) owning the replies list + cursor + load-more state. The existing optimistic reply **prepend** is preserved (prepend to the VM-held list). **Scroll-end detection risk:** replies render as `items()` after the post-header + like-row items in the shared `LazyColumn`; the end-detection threshold MUST key off the replies items (not absolute list index) so it doesn't mis-fire while the header is on screen, and the footer item sits after the reply items. **Alternatives:** keep replies paging in composition-local state (rejected — fragile across recomposition/config-change, and it would make post-detail the lone surface doing load-more without a VM = patchwork); migrate ALL post-detail state (like + composer) into the VM now (rejected — needlessly enlarges the change + risks shipped, tested like/compose behavior; the residual migration is a noted follow-up).

### D6 — Load-more pages ignore `upsell`
The rate-limit `upsell` (soft/hard) is a first-page concern (the timelines' `Loaded` carries it; notifications/replies have none). A load-more page SHALL NOT re-evaluate `upsell`; a load-more page that returns empty posts is treated as **end-reached**. The first-page soft/hard-limit states are unchanged.

### Standards conformance (docs/11 §Pattern Registry — required note)
- **State management (§2.2):** load-more state is VM-held `StateFlow` (the appended list + `isLoadingMore`/`endReached`/`loadMoreError`), not composition-local — this is precisely why D5 introduces `PostDetailViewModel`. One-shot nature is N/A (these are durable list states, not events).
- **Compose performance (§2.4):** scroll-end detection via `LazyListState` + `derivedStateOf`; stable `key` + `contentType` on every `items()` incl. the footer.
- **Data layer (§2.6):** each `*Flow` gains a `loadMore(cursor[, anchor])` member; ViewModels call flows, never ApiClients; the API clients are unchanged.
- **Pattern Registry:** the load-more pattern is added to its canonical home (the `mobile-design-system` spec) and reused via the shared `LoadMoreController` — **no second pattern is introduced**, so no docs/11 §Pattern-Registry amendment is required (the registry points to the `mobile-design-system` spec for the UI-substrate concern, which this change extends in place).
- **UI substrate / mockups (§2.8):** load-more is a behavioral addition with no new visual element beyond a standard M3 footer `CircularProgressIndicator` + an inline retry affordance; it consumes the existing list frames (no new mockup frame needed). The footer reuses existing M3 tokens.

## Risks / Trade-offs

- **[Scroll-end mis-fire on the shared post-detail `LazyColumn`]** → threshold keys off the replies `items()` region (D5), and the in-flight guard + `endReached` terminal prevent duplicate/again-at-end requests; covered by a `PostDetailScreenTest` scenario.
- **[Duplicate or skipped rows at a page boundary if the list mutates mid-scroll]** → the chronological keyset cursor is stable under appends; mark-read (notifications) and inline-like (feeds) mutate items in place by `key`, not by reordering, so append stays consistent. Covered by VM tests (append preserves earlier pages; mark-read survives append).
- **[Concurrent load-more + pull-to-refresh]** → the shared controller's in-flight guard + the existing reload reentrancy guard serialize them; a refresh resets paging to page 1 (clears `endReached`/appended tail), consistent with "refresh re-fetches the first page".
- **[`PostDetailViewModel` introduction touches a screen with shipped tested behavior]** → migrate ONLY replies-list + paging state; like + composer state stay as-is (D5); the existing `PostDetailScreenTest` + `PostDetailUiStateTest` guard the unchanged behaviors; new scenarios cover paging.
- **[Generic controller over 5 differing item/outcome types]** → the controller is generic over `T` with a thin per-surface adapter (feed `Loaded(posts,…)` / notifications `Loaded(items,…)` / replies `Loaded(replies,…)` → a uniform `PageResult<T>(items, nextCursor)`); upsell is mapped out per D6.

## Open Questions

- None blocking. Apply-time detail: the exact shape by which the Nearby anchor reaches `loadMore` (Loaded-outcome field vs. a small `loadFirstPage` return record) is settled in implementation per D4; either keeps the anchor VM-held and unrendered.
