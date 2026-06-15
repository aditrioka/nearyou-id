## 1. Shared load-more substrate

- [ ] 1.1 Add a generic, Compose-free `LoadMoreController<T>` in commonMain `ui/timeline/` (the `InlineLikeController` precedent): owns the appended list, current cursor, `isLoadingMore`, `endReached`, `loadMoreError`, a per-instance in-flight guard; takes a `suspend (cursor) -> PageResult<T>` fetch lambda; exposes `loadMore()` (no-op when in-flight / end-reached) and a `retryLoadMore()` + a `reset(firstPage, cursor)` for refresh.
- [ ] 1.2 Add a small `PageResult<T>(items: List<T>, nextCursor: String?)` mapping type + per-surface adapters (feed `Loaded(posts,…)`, notifications `Loaded(items,…)`, replies `Loaded(replies,…)` → `PageResult`; `upsell` mapped out per design D6).
- [ ] 1.3 Add a reusable Compose load-more **footer** + scroll-end detector in `ui/components/` (or `ui/timeline/`): a footer composable (spinner / non-destructive retry-on-error / nothing-when-end-reached) and a `LazyListState`+`derivedStateOf` near-end detector (threshold-based, keys off the paginated items region), with stable `key`/`contentType` for the footer item.
- [ ] 1.4 commonTest `LoadMoreControllerTest`: append + advance cursor; null cursor → end-reached + no further fetch; in-flight guard ignores a concurrent `loadMore`; `loadMoreError` set on failure then cleared + re-fetched on retry; `reset` clears the appended tail + end-reached.

## 2. Design-system spec conformance

- [ ] 2.1 Confirm the implementation satisfies `mobile-design-system` § "Canonical list load-more (infinite-scroll) pattern" + § "Shared generic load-more controller" (footer never co-occurs with the skeleton or the pull-to-refresh indicator; loaded list never torn down on load-more error).

## 3. Nearby feed (anchor-reuse load-more)

- [ ] 3.1 Extend `NearbyTimelineFlow` with `loadMore(cursor, anchor)` and expose the resolved first-page anchor to the VM (carry it on the `Loaded` outcome, VM-held, never projected/rendered); `NearbyTimelineRepository.loadMore` forwards the retained anchor to `NearbyTimelineApiClient.fetchNearby(cursor=…)` (client already accepts `lat`/`lng`/`cursor`).
- [ ] 3.2 `NearbyTimelineViewModel`: retain the page-1 anchor; hold a `LoadMoreController` instance; expose appended posts + `isLoadingMore`/`endReached`/`loadMoreError`; reset paging on `reload()`. Keep the inline-like controller operating over the grown list.
- [ ] 3.3 `NearbyTimelineScreen`: add the footer + scroll-end detector to the `Content`/`SoftLimit` post list (`nearbyTimelineList`); wire `onLoadMore` / `onRetryLoadMore` to the VM.
- [ ] 3.4 Tests: VM (append below page 1, cursor advance, end-reached, non-destructive error, anchor reused + never logged, **`reload()` resets paging — clears appended tail + end-reached**, **load-more suppressed while a refresh is in flight**); MockEngine `NearbyTimelineApiClient`/`Repository` (follow-up carries `cursor` + the page-1 `lat`/`lng`/`radius_m`); **load-more error logs exception TYPE only (no `cause.message`/coordinate) — extend the `DiagnosticSinkWiringTest` source-scan to the new `loadMore` diagnostic call sites on all three timeline repositories (incl. `FollowingTimelineRepository`)**; extend `NearbyTimelineScreenTest` (scroll-end triggers load-more exactly once; footer states; footer never co-occurs with skeleton/refresh).

## 4. Following feed (cursor-only load-more)

- [ ] 4.1 Extend `FollowingTimelineFlow`/`Repository` with `loadMore(cursor)` → `FollowingTimelineApiClient.fetchFollowing(cursor=…)` (no spatial params).
- [ ] 4.2 `FollowingTimelineViewModel`: `LoadMoreController` instance; appended posts + footer flags; reset on `reload()`.
- [ ] 4.3 `FollowingTimelineScreen`: footer + scroll-end detector on the post list.
- [ ] 4.4 Tests: VM (append, end-reached, non-destructive error, **`reload()` resets paging**) + MockEngine client (follow-up carries `cursor`, no `lat`/`lng`) + extend `FollowingTimelineScreenTest`.

## 5. Global feed (cursor-only load-more)

- [ ] 5.1 Extend `GlobalTimelineFlow`/`Repository` with `loadMore(cursor)` → `GlobalTimelineApiClient.fetchGlobal(cursor=…)`.
- [ ] 5.2 `GlobalTimelineViewModel`: `LoadMoreController` instance; appended posts + footer flags; reset on `reload()`.
- [ ] 5.3 `GlobalTimelineScreen`: footer + scroll-end detector on the post list.
- [ ] 5.4 Tests: VM (append, end-reached, non-destructive error, **`reload()` resets paging**) + MockEngine client + extend `GlobalTimelineScreenTest`.

## 6. Notifications list (load-more; mark-read survives append)

- [ ] 6.1 Extend `NotificationsFlow`/`Repository` with `loadMore(cursor)` → `NotificationsApiClient.fetch(cursor=…, unreadOnly=<same filter as page 1>)`.
- [ ] 6.2 `NotificationsViewModel`: `LoadMoreController` instance; appended rows + footer flags; reset on `reload()`. Ensure `markRead`/`markAllRead` in-place mutations operate over the grown list.
- [ ] 6.3 `NotificationsScreen`: footer + scroll-end detector on the `notificationsList` `LazyColumn`.
- [ ] 6.4 Tests: VM (append; mark-read on an appended row; mark-all-read over all loaded rows; end-reached; non-destructive error; **`reload()` resets paging**); MockEngine client (follow-up carries `cursor` **AND the same `unread` filter the first page used**); extend `NotificationsScreenTest`.

## 7. Post-detail replies (introduce PostDetailViewModel + load-more)

- [ ] 7.1 Introduce `PostDetailViewModel` (NavEntry-scoped via `viewModel { … }`, mirrors #167) owning the replies list + cursor + load-more state (shared `LoadMoreController`); move ONLY the replies-list/paging state out of `PostDetailScreen`'s composition-local `var`s (like + composer state stay as-is — noted follow-up).
- [ ] 7.2 Extend `PostDetailFlow`/`PostDetailRepository` with `loadMoreReplies(postId, cursor)` → `GET /api/v1/posts/{post_id}/replies?cursor=…` (reply DTO `next_cursor` is `@SerialName` snake_case).
- [ ] 7.3 `PostDetailScreen`: drive replies from the VM; add the footer + scroll-end detector to the replies `items()` region of the shared `LazyColumn` (threshold keyed off the replies items, after the header + like-row); preserve the optimistic new-reply **prepend** + reply-count increment.
- [ ] 7.4 Tests: replies-paging in the VM (append below page 1; cursor advance; end-reached; non-destructive error; new-reply prepend leaves appended pages undisturbed; **`reloadReplies()` retry resets replies paging**); MockEngine reply client (follow-up carries `cursor`); extend `PostDetailScreenTest` (scroll-end on replies triggers load-more; footer states; header/like-row unaffected) + `PostDetailUiStateTest` if the replies projection changes.

## 8. Wiring, strings, lint

- [ ] 8.1 Koin: register `PostDetailViewModel` (and any new shared footer/controller bindings) in `mobileModule` per the existing per-feed VM pattern.
- [ ] 8.2 Add any new Bahasa Indonesia string for the load-more error footer to `:shared:resources` (reuse `cta_retry` for the retry control; add a footer-error label only if one is needed — no hardcoded literals).
- [ ] 8.3 Ensure every `items()` (incl. footer) has a stable `key` + `contentType`; no hardcoded UI strings; `derivedStateOf` used for the near-end read.

## 9. Verification (local — mobile unit tests are local-only; CI mobile = device-run APK build)

- [ ] 9.1 `./gradlew :mobile:app:testDevDebugUnitTest` (the docs/11 §5 DoD + spec-scenario variant) + `:mobile:app:testDevReleaseUnitTest` (confirm `*ScreenTest` Release-variant excludes still hold) — all green.
- [ ] 9.2 `./gradlew :mobile:app:iosSimulatorArm64Test` — the existing `*TimelineFlowIosTest` + any touched iosTest pass (the load-more additions are commonMain; confirm no K/N break).
- [ ] 9.3 Manual (verify-loop, local device/emulator): scroll each of the five surfaces past page 1 → appended pages load; footer spinner shows then clears; reaching the true end shows no further requests; a forced load-more error shows the retry footer without losing the list; pull-to-refresh resets to page 1.
- [ ] 9.4 Pre-push gate for the touched non-mobile files (none expected) — N/A here; mobile lint is `ktlintCheck`/`detekt` on `:mobile:app` if wired, else the unit-test gate above.

## 10. Archive

- [ ] 10.1 At `/opsx:archive`: confirm `openspec validate --strict` green; verify the 5 RENAMED `FROM:` headers resolved (no "TBD - created by archiving" placeholders left in `openspec/specs/`).
- [ ] 10.1a Spec-sync the descriptive Purpose prose that requirement deltas can't reach: update `openspec/specs/mobile-notifications-list/spec.md` Purpose (line ~4) to drop "infinite scroll" from its "explicitly deferred" list; grep the other four touched specs' Purpose/overview prose for any stale "infinite scroll deferred" mention and fix in the same archive commit.
- [ ] 10.2 Close GitHub issue [#188](https://github.com/aditrioka/nearyou-id/issues/188) (all five surfaces shipped) and verify it shows `state=CLOSED` via `gh issue view 188 --json state`.
