# mobile-block-from-content (delta)

## RENAMED Requirements

- FROM: `### Requirement: Timeline-card block entry point is deferred`
- TO: `### Requirement: Timeline card exposes a block entry point`

## MODIFIED Requirements

### Requirement: Timeline card exposes a block entry point

The shared timeline post card (`PostCard`, `mobile-post-card`) SHALL expose a block entry point on all three feed surfaces (Nearby / Global / Following): the overflow kebab's menu carries a "Blokir @{username}" item (resource `profile_block_action`), supplied by the feed host ONLY for **non-authored** posts. Authorship SHALL be resolved in the feed ViewModel by comparing the raw timeline DTO's `authorUserId` against the viewer's `SelfUserIdProvider` id (fail-closed while unresolved — the shipped report-kebab gate) — never on the PII-free `PostCardModel`, which stays UUID-free. Selecting the item SHALL open the shared `BlockConfirmDialog` (canonical copy, unchanged); a confirmed block SHALL go through the shared `BlockSubmitter` seam with the raw DTO's `authorUserId` as the path param. The outcome SHALL map onto the canonical block result contract: `Blocked` → the success toast (`profile_block_success_toast`) AND the feed locally removes every currently-loaded post whose `authorUserId` is the blocked author (the timeline's surface-specific effect — mutual invisibility made immediate; paging cursor/anchor untouched, later pages are server-side block-excluded); `RateLimited` → the block rate-limit copy with NO removal; `NetworkError` → the generic action-failed copy with NO removal. Results surface as one-shot snackbar messages held as nullable state cleared via a shown-callback (docs/11 § 2.2). The block-flow state (dialog target + one-shot message) SHALL exist exactly once, in a shared controller instantiated per feed ViewModel (the `TimelineReportController` precedent) — not three per-feed copies — and the dialog target SHALL carry the author UUID only as the un-rendered submit param (the display identity shown is the `@username` handle). This supersedes the prior "Timeline-card block entry point is deferred" posture ([#354](https://github.com/aditrioka/nearyou-id/pull/354) merged and the card kebab shipped with `timeline-card-report-kebab`; issue [#456](https://github.com/aditrioka/nearyou-id/issues/456) is closed by this change).

#### Scenario: Another user's post is blockable from the feed

- **WHEN** a feed renders a post NOT authored by the viewer and the viewer opens the card kebab, picks "Blokir @{username}", and confirms the dialog
- **THEN** the shared `BlockConfirmDialog` is shown with the canonical copy AND exactly one block is submitted through the shared `BlockSubmitter` against the post author's UUID

#### Scenario: The viewer's own post exposes no block entry point

- **WHEN** a feed renders a post whose `authorUserId` equals the viewer's `SelfUserIdProvider` id
- **THEN** that card offers no "Blokir" item AND no block affordance is reachable for it from the timeline

#### Scenario: A confirmed block removes the author's loaded posts and shows the success toast

- **GIVEN** a loaded feed containing three posts by author A and two by author B
- **WHEN** the viewer blocks author A from one of A's cards and the submission returns `Blocked`
- **THEN** the success toast (`profile_block_success_toast`) is surfaced exactly once AND all three of A's posts leave the rendered list AND B's posts remain

#### Scenario: Rate-limited and network-failed blocks leave the feed unchanged

- **WHEN** a timeline-card block submission resolves as `RateLimited` (429), and separately as `NetworkError`
- **THEN** the rate-limit copy renders for the former and the generic action-failed copy for the latter, each as a one-shot snackbar cleared after showing AND no post is removed from the list in either case

#### Scenario: Dismissing the dialog issues no block

- **WHEN** the viewer opens the block dialog from a timeline card and taps "Batal"
- **THEN** no block submission is issued AND the feed is unchanged

#### Scenario: One shared block-flow implementation across the three feeds

- **WHEN** inspecting the mobile source tree
- **THEN** the timeline block-flow state machine (dialog target + one-shot message + submission + removal signal) is defined exactly once (a shared controller under `ui/timeline/`), instantiated by the Nearby, Global, AND Following feed ViewModels, consuming the existing shared `BlockSubmitter`/`BlockConfirmDialog` seam (no second block path)

## ADDED Requirements

### Requirement: Test coverage for the timeline-card block entry point

The change SHALL ship: (1) unit coverage for the shared timeline block controller — dialog-target open/dismiss one-shot, confirm → exactly one `BlockSubmitter.submit` with the target UUID, outcome→message mapping (`Blocked`→success + the removal signal fired with the author UUID, `RateLimited`, `NetworkError`→no removal signal), and message clear; (2) Robolectric coverage for the card-level affordance — the "Blokir @{username}" item present with a block action, each kebab item gated on its own action, kebab absent when both actions are null — and for the feed-level entry point (kebab → "Blokir" item → shared dialog → confirmed submission → the author's cards leave the list); (3) the `PostDetailSourceGuardTest` negative guard asserting `PostCard` stays block-free SHALL flip to assert the timeline card DOES expose the block item (superseding the deferral it guarded). New `*ScreenTest`-shaped tests SHALL be added to the Release-variant test-exclude list, keeping `:mobile:app:testDevReleaseUnitTest` green.

#### Scenario: Controller and affordance tests exist and are discoverable

- **WHEN** running `./gradlew :mobile:app:testDevDebugUnitTest`
- **THEN** the timeline block controller tests and the card/feed block-affordance tests are discovered AND each documented outcome mapping corresponds to at least one `@Test`

#### Scenario: Release variant stays green

- **WHEN** running `./gradlew :mobile:app:testDevReleaseUnitTest`
- **THEN** the task passes, with any new screen-shaped tests listed in the Release-variant exclude block of `mobile/app/build.gradle.kts`
