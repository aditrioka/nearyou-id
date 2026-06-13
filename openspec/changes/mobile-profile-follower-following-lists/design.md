## Context

`mobile-profile-screen` (PR #245) shipped `ProfileScreen` rendering the follower/following counts as **static numbers**, deliberately avoiding dead controls and deferring the tappable list screens to follow-up [#260](https://github.com/aditrioka/nearyou-id/issues/260). The backend half is already done: `GET /api/v1/users/{id}/followers` + `/following` (`follow-system`) were enriched by `social-list-profile-summaries` (archived 2026-06-11) to embed a profile summary per row, filter bidirectionally against the viewer, and paginate by keyset — and they have **no mobile consumer yet** (`grep` of `mobile/` confirms). This change is the pure `:mobile:app` consumer: make the counts tappable and add the paginated member-list surface. No backend, no Flyway migration, no V-number.

This is a profile **deepening** pick: all five numbered mobile-first live-menu rows (#1–#5) are shipped, and follower/following lists are not part of the defined demoable core loop — so this is a polish-tier (but operator-directed) mobile pick, not a demo blocker.

## Goals / Non-Goals

**Goals:**
- Make `ProfileScreen`'s follower and following counts tappable, navigating to the respective member list.
- Ship one tabbed `FollowListScreen` (Pengikut / Mengikuti) over the shipped endpoints, with keyset pagination, the canonical list loading/refresh/empty/error states, and rows that tap through to `ProfileRoute`.
- Conform to the `mobile-design-system` substrate and the docs/11 Pattern Registry, reusing existing patterns end-to-end (no new pattern).

**Non-Goals:**
- No backend / API / DB change (the contract is frozen; this is a consumer).
- No inline follow/unfollow on list rows (deferred — rows are navigational only; the follow toggle lives on `ProfileScreen`).
- No in-list search / filter / sort.
- No edit-profile, suspension-countdown, or other `mobile-profile` deferrals (those remain separate follow-ups).

## Decisions

### D1 — One tabbed screen, not two routes (operator decision, 2026-06-14)

`FollowListScreen` is a single root-stack overlay with a `PrimaryTabRow` + `HorizontalPager` (Pengikut / Mengikuti), reached via `FollowListRoute(userId, initialTab)`; the tapped count deep-links to its tab via `initialTab`. **Alternatives considered:** two separate routes/screens (`FollowerListRoute` + `FollowingListRoute`). **Why tabbed:** it mirrors the shipped Home feed top-tabs idiom (one screen, swipeable, `PrimaryTabRow` + pager — the pattern `mobile-home-tab-host` already proves on `:mobile:app`), gives swipe-between for free, and adds one NavKey instead of two. The operator confirmed this shape at the pick gate.

### D2 — Hidden members are excluded server-side; the list never placeholds (reconciliation correction)

Issue #260's scope text says these lists carry `akun_dihapus` placeholders. That is **incorrect for `/followers` + `/following`**: per `social-list-profile-summaries` + `follow-system`, those endpoints source rows via INNER JOIN `visible_users`, so shadow-banned / soft-deleted / viewer-blocked edges are excluded entirely and never reach the client. The `akun_dihapus` / "Akun Dihapus" COALESCE masking is a **`GET /blocks`-only** behavior (block rows must survive hidden targets so the owner can unblock them) and a posts/chats/replies tombstone (docs/03 § Profile/Account UX) — neither applies here. `username` / `displayName` are NOT NULL on the wire (since V2). **Decision:** the mobile list renders only the rows the endpoint returns; it implements **no** placeholder, COALESCE masking, or null-identity fallback. A spec requirement with a negative guard locks this in so a future reader doesn't "restore" placeholder logic.

### D3 — Count value (raw aggregate) may exceed the rendered list length, by design

`followerCount` / `followingCount` are **raw public aggregates** (`user-profile-read` design D1 — they are NOT viewer-filtered). The **lists**, however, are visibility-filtered + bidirectionally viewer-block-filtered. So a profile showing "12 followers" can legitimately render a list of fewer than 12 rows (some followers are hidden from, or have blocked, the viewer). **This is expected, not a bug** — do not "reconcile" the count to the list length, and do not flag the mismatch in review. The count stays a snapshot of the read; the list stays viewer-filtered.

### D4 — Pattern Registry conformance (docs/11 § Pattern Registry — reuse only, no new pattern → no docs/11 amendment)

- **Data layer:** `FollowListApiClient` + `FollowListRepository` behind a `FollowListFlow` seam, Koin singletons reusing the shared `HttpClient`; `single<FollowListFlow> { get<FollowListRepository>() }`. Dependency direction `UI → ViewModel → Repository → ApiClient` (the registered data-layer pattern; same shape as `ProfileRepository`/`ProfileFlow` and the timeline flows). No new `HttpClient`, no `X-Session-Id`.
- **State holder:** a Compose-free `FollowListUiState` produced by a pure projection (the registered state-holder pattern; mirrors `NearbyTimelineUiState` / `ProfileUiState`), with separate `isInitialLoad` / `isRefreshing` flags per the `mobile-design-system` canonical list pattern. `FollowListViewModel` via `koinViewModel()` scoped to the Nav3 entry.
- **Navigation:** `FollowListRoute` is a `@Serializable` `NavKey` registered in the `navSavedStateConfiguration` polymorphic `SerializersModule` (the registered Navigation-3 pattern; same as `ProfileRoute` / `PostDetailRoute`), carrying only `userId` + `initialTab` — no coordinates, no token.

Because every concern reuses an already-registered pattern, this change introduces **no** new Pattern-Registry entry and requires **no** docs/11 amendment.

### D5 — Mockup-gap: reuse the established identity-row idiom

The screens mockup board (`dev/mockups/nearyou-screens-mockup.html`, 19 frames) has **no dedicated follower/following list frame**; frame **3 ("Profil — profil sendiri")** shows only the counts entry point. **Decision:** the list-row visual reuses the established identity-row idiom (the `mobile-post-card` letter-avatar + deterministic-color + `@handle` treatment already reused by `mobile-profile`) inside the `mobile-design-system` canonical list pattern — an in-pattern translation, not a new visual. Per docs/11 § 2.8, `/opsx:apply` will render frame 3 (+ its measurement annex) to confirm the **counts-tap affordance** translation on `ProfileScreen`. The board gap (no list frame) is noted as a non-blocking follow-up for the operator to add a frame later if desired.

### D6 — Per-tab independent fetch; NotFound is target-consistent

Each tab issues its first page on first display (initial tab immediately; the other on first reveal), and retains its pages for the screen's lifetime. Both tabs target the **same** profile `userId`, so if that target becomes unresolvable (blocked / deleted / shadow-banned since the profile load) both tabs map to the same `NotFound` state via the constant-404 contract — consistent, no per-cause branch. The minor cost (the second tab re-confirming the 404 on reveal) is acceptable and avoids a screen-level target pre-resolution step.

## Risks / Trade-offs

- **No mockup frame for the list screen** → reuse the established identity-row idiom + canonical list pattern (D5); render frame 3 for the counts-tap affordance; note the board gap as a follow-up. Low risk — the row is a well-worn pattern.
- **Count vs list-length mismatch looks like a bug** → documented as intended (D3) in this design + a spec note; reviewers should not flag it. Risk is a spurious review finding, not a defect.
- **Casing-drift trap** (the recurring `mobile-*` wire bug, PR #128) → the DTO is bare camelCase matching the shipped `FollowRoutes.kt`, with an explicit snake_case **negative-guard** test. Mitigated by test.
- **HorizontalPager + per-tab pagination interaction** (two independent paginated lists in one pager) → each tab owns its own state + load-more guard (no duplicate in-flight); the `mobile-home-tab-host` feed pager already proves three independent paginated feeds in one pager, so the pattern is de-risked.
- **Empty-vs-loading flicker on a fast empty response** → the separate `isInitialLoad` flag (not a generic `inFlight`) prevents a skeleton→empty flash; covered by the projection unit tests.

## Migration Plan

Not applicable — no schema, no data migration, no API change. Ships as a single mobile feature branch under the one-PR-per-change lifecycle (proposal → `/opsx:apply` → `/opsx:archive`), squash-merged once. Rollback is a plain revert of the PR (no persisted state, no migration to undo).

## Open Questions

None blocking. One **resolved-pending-operator** item (not a gate): a dedicated follower/following list mockup frame is deliberately NOT added in this change — the row reuses the established identity-row idiom (D5). This is a settled decision; the operator MAY later request a board frame, tracked as a non-blocking note, but it is not a prerequisite for `/opsx:apply` or merge.
