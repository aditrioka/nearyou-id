# Tasks: timeline-card-report-kebab

## 1. Card + list affordance

- [x] 1.1 `PostCard`: add `onReport: (() -> Unit)? = null`; restructure the identity header into an outer `Row` (identity clickable region at `weight(1f)` + trailing kebab), render the kebab (`ic_more_vert` `IconButton` + `DropdownMenu` with the single `profile_report_action` item, `stringResource` contentDescription, test tags for kebab + menu item) iff `onReport != null`; kebab/menu taps fire neither `onOpen` nor `onOpenProfile` (D1)
- [x] 1.2 `PostFeedList`: add `reportActionOf: (T) -> (() -> Unit)? = { null }` and pass `onReport = reportActionOf(item.post)` to each card (D2)
- [x] 1.3 Verify against mockup frame 1 (`.post .head .more` placement/treatment) per docs/11 § 2.8 — render the frame + measurement annex during manual verification

## 2. Shared report-flow controller + overlay

- [x] 2.1 New `ui/timeline/TimelineReportController` (compose-free): `reportingPostId` + `reportMessage` one-shot StateFlows, `onReportClicked`/`onDialogDismissed`/`onSubmitted`/`onMessageShown`, submission via the shared `ReportSubmitter` (`ReportTargetType.POST`), anti-enumeration outcome mapping (Submitted+Duplicate → SUCCESS) (D3)
- [x] 2.2 New `ui/timeline/TimelineReportOverlay` composable: `ReportDialog` (title `report_title_post`, host-supplied test tag) while a target is set + bottom-aligned `SnackbarHost` showing the one-shot message then clearing it (D5)

## 3. Feed wiring (×3)

- [x] 3.1 `NearbyTimelineViewModel` / `GlobalTimelineViewModel` / `FollowingTimelineViewModel`: inject `ReportSubmitter` (+ `SelfUserIdProvider` where missing), hold a `TimelineReportController`, resolve `selfUserId` once on init, expose `reportActionFor(postId, selfUserId): (() -> Unit)?` (the screen passes the collected self-id state back so kebabs recompose when it resolves) gated on `authorUserIdForPost(post.id) != selfUserId` with null/unresolved self id → null (fail-closed) (D4)
- [x] 3.2 The three feed screens: `koinInject` the new seams into the `viewModel { }` ctors, pass `reportActionOf` into `PostFeedList`, mount `TimelineReportOverlay` in the root `Box`

## 4. Tests

- [x] 4.1 Controller unit test: open/dismiss one-shot, Submitted and Duplicate → the same SUCCESS, RateLimited, NetworkError, message clear
- [x] 4.2 `PostCard` Robolectric test: kebab + "Laporkan" fire `onReport` only (not `onOpen`/`onOpenProfile`); `onReport = null` → no kebab node; five-vs-four clickable-node counts per the modified action-row scenario
- [x] 4.3 Feed-level wiring test (one feed as representative): non-authored post shows the kebab → the "Laporkan" menu entry; own post shows no kebab. (Amended mid-gate: the dialog body is NOT opened over the LazyColumn feed — the documented Robolectric never-settling measure pass, PostDetailScreenTest precedent, manifested as a 60s hang/OOM; dialog-open + submit + outcome mapping are locked in TimelineReportControllerTest, the dialog body in ReportDialogTest)
- [x] 4.4 Add any new `*ScreenTest`-shaped tests to the Release-variant exclude list; `:mobile:app:testDevDebugUnitTest` + `:mobile:app:testDevReleaseUnitTest` green

## 5. Gates + delivery

- [x] 5.1 Full pre-push gate: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test :mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest`
- [x] 5.2 Manual verification (verify-loop §B) with screenshot evidence in the PR body: kebab on another user's card, absent on own card, dialog + success snackbar (docs/11 §5 DoD)
- [x] 5.3 PR carries `Closes #363`; title/body current at each phase boundary
