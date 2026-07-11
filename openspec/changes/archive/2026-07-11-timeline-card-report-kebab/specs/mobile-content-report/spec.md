# mobile-content-report (delta)

## RENAMED Requirements

- FROM: `### Requirement: Timeline-card report entry point is deferred`
- TO: `### Requirement: Timeline card exposes a report entry point`

## MODIFIED Requirements

### Requirement: Timeline card exposes a report entry point

The shared timeline post card (`PostCard`, `mobile-post-card`) SHALL expose a report entry point on all three feed surfaces (Nearby / Global / Following): an overflow kebab whose menu carries a "Laporkan" item (resource `profile_report_action`), supplied by the feed host ONLY for **non-authored** posts. Authorship SHALL be resolved in the feed ViewModel by comparing the raw timeline DTO's `authorUserId` against the viewer's `SelfUserIdProvider` id — never on the PII-free `PostCardModel`, which stays UUID-free. Selecting "Laporkan" SHALL open the shared `ReportDialog` (title `report_title_post`); submission SHALL go through the shared `ReportSubmitter` with `target_type = "post"` and the tapped post's id as `target_id`. The outcome→message mapping SHALL keep the anti-enumeration posture (design D3 / `docs/03`:234): `Submitted` AND `Duplicate` (409) map to the SAME success copy (`profile_report_success_toast`); `RateLimited` (429) and `NetworkError` map to their typed copy. The result SHALL surface as a one-shot snackbar message held as nullable state cleared via a shown-callback (docs/11 § 2.2 — no `Channel`/`SharedFlow` bus). The report-flow state (dialog target + one-shot message) SHALL exist exactly once, in a shared controller instantiated per feed ViewModel (the `InlineLikeController` precedent) — not three per-feed copies. This supersedes the prior "Timeline-card report entry point is deferred" posture ([#354](https://github.com/aditrioka/nearyou-id/pull/354) has merged; issue [#363](https://github.com/aditrioka/nearyou-id/issues/363) is closed by this change).

#### Scenario: Another user's post is reportable from the feed

- **WHEN** a feed renders a post NOT authored by the viewer and the viewer opens the card kebab, picks "Laporkan", selects a reason category, and submits
- **THEN** the shared `ReportDialog` is shown AND exactly one report is submitted through the shared `ReportSubmitter` with `target_type = "post"` and the post's id as `target_id`

#### Scenario: The viewer's own post exposes no report entry point

- **WHEN** a feed renders a post whose `authorUserId` equals the viewer's `SelfUserIdProvider` id
- **THEN** that card renders no report kebab AND no report affordance is reachable for it from the timeline

#### Scenario: Submitted and duplicate reports render the same success message

- **WHEN** a timeline-card report submission resolves as `Submitted`, and separately as `Duplicate` (409)
- **THEN** both render the identical success snackbar copy (`profile_report_success_toast`) — the reporter cannot distinguish a prior report (anti-enumeration)

#### Scenario: Rate-limited and network-failed submissions render typed messages

- **WHEN** a timeline-card report submission resolves as `RateLimited` (429), and separately as `NetworkError`
- **THEN** the rate-limit copy renders for the former and the retryable failure copy for the latter, each as a one-shot snackbar cleared after showing (no re-fire on recomposition)

#### Scenario: One shared report-flow implementation across the three feeds

- **WHEN** inspecting the mobile source tree
- **THEN** the timeline report-flow state machine (dialog target + one-shot message + submission) is defined exactly once (a shared controller under `ui/timeline/`), instantiated by the Nearby, Global, AND Following feed ViewModels, consuming the existing shared `ReportSubmitter`/`ReportDialog` seam (no duplicated report path)

## ADDED Requirements

### Requirement: Test coverage for the timeline-card report entry point

The change SHALL ship: (1) `commonTest` (or JVM unit) coverage for the shared timeline report controller — dialog open/dismiss one-shot, submission outcome→message mapping (Submitted/Duplicate→the same success value, RateLimited, NetworkError), and message clear; (2) Robolectric coverage for the card-level affordance — kebab present with a report action and absent without one — and for the feed-level entry point (kebab → the "Laporkan" menu item). The feed-level screen test SHALL NOT open the dialog body: the shared `ReportDialog` (an `OutlinedTextField` inside an `AlertDialog`) over the feed's `LazyColumn` triggers the documented Robolectric-only never-settling measure pass (the `mobile-content-report` PostDetailScreenTest precedent), so the dialog-open one-shot, the `target_type=post` submission, and the outcome mapping are locked at the controller level instead (item 1), and the dialog body renders under its own non-`LazyColumn` host test. New `*ScreenTest`-shaped tests SHALL be added to the Release-variant test-exclude list, keeping `:mobile:app:testDevReleaseUnitTest` green.

#### Scenario: Controller and affordance tests exist and are discoverable

- **WHEN** running `./gradlew :mobile:app:testDevDebugUnitTest`
- **THEN** the timeline report controller tests and the kebab presence/absence tests are discovered AND each documented outcome mapping corresponds to at least one `@Test`

#### Scenario: Release variant stays green

- **WHEN** running `./gradlew :mobile:app:testDevReleaseUnitTest`
- **THEN** the task passes, with any new screen-shaped tests listed in the Release-variant exclude block of `mobile/app/build.gradle.kts`
