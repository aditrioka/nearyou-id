# Tasks: timeline-card-block-kebab

## 1. Card + feed-list seam

- [ ] 1.1 `PostCard`: add `onBlock: (() -> Unit)? = null`; kebab renders iff `onReport != null || onBlock != null`; menu carries "Laporkan" (iff `onReport`) then "Blokir @{username}" (`profile_block_action` + `model.authorUsername`, iff `onBlock`); new `POST_CARD_BLOCK_ITEM_TAG`; update the kebab KDoc (D1)
- [ ] 1.2 `PostFeedList`: add `blockActionOf: (T) -> (() -> Unit)? = { null }`, thread `onBlock = blockActionOf(item.post)` (D2)

## 2. Shared block controller

- [ ] 2.1 New `ui/timeline/TimelineBlockController.kt`: `TimelineBlockTarget(authorUserId, authorUsername)`, `TimelineBlockMessage { SUCCESS, RATE_LIMITED, FAILED }`, `blockTarget`/`blockMessage` one-shot state, `onBlockClicked`/`onDialogDismissed`/`onConfirmed`/`onMessageShown`, ctor-injected `BlockSubmitter` + `removeAuthorPosts: (String) -> Unit`; `Blocked` → removal + SUCCESS (D3, D4)
- [ ] 2.2 Controller unit tests: open/dismiss one-shot, confirm submits exactly once with the target UUID, `Blocked` → removal fired + SUCCESS, `RateLimited`/`NetworkError` → typed message + NO removal, message clear

## 3. Feed hosts (VMs + screens + overlay)

- [ ] 3.1 Rename `TimelineReportOverlay` → `TimelineActionsOverlay`: add `blockTarget`/`blockMessage`/`onBlockConfirm`/`onBlockDismiss` params, render `BlockConfirmDialog` (per-feed test tag), feed block messages into the SAME `SnackbarHost` (D6)
- [ ] 3.2 `NearbyTimelineViewModel`: ctor gains `BlockSubmitter`; `blockController` instance with removal filtering the retained `Loaded.posts` by `authorUserId`; `blockActionFor(postId, selfUserId)` (fail-closed, D5); screen wires `blockActionOf` + overlay params
- [ ] 3.3 Same for `GlobalTimelineViewModel` + screen
- [ ] 3.4 Same for `FollowingTimelineViewModel` + screen

## 4. Tests

- [ ] 4.1 `PostCardTest`: block item renders + routes (`onBlock` fires once, `onOpen`/`onOpenProfile` don't); either-action-alone shows the kebab with only its item; both-null → no kebab (byte-identical baseline)
- [ ] 4.2 Feed-level Robolectric test (one feed): non-authored card kebab → "Blokir @{username}" → shared dialog → confirm → exactly one `BlockSubmitter.submit(authorUserId)` AND the author's cards leave the list; own post offers no block item
- [ ] 4.3 Flip the `PostDetailSourceGuardTest` PostCard-stays-block-free negative guard to the fulfilled posture (the deferral it guarded is superseded)
- [ ] 4.4 Add any new `*ScreenTest`-shaped files to the Release-variant exclude in `mobile/app/build.gradle.kts`; keep `testDevReleaseUnitTest` green

## 5. Verification

- [ ] 5.1 Gate: `./gradlew ktlintCheck :mobile:app:ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test :mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest`
- [ ] 5.2 Manual verify (UI-affecting, verify-loop §B): block an author from a feed card on the emulator — dialog copy, success toast, author's cards gone; screenshot evidence in the PR body (docs/11 §5 DoD)
