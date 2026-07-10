# Design: timeline-card-report-kebab

## Context

`mobile-content-report` shipped the shared report seam (`data/report/ReportSubmitter` + `ui/components/ReportDialog`) consumed by profile, post-detail, and chat-thread. The timeline card was the one deferred entry point (#363). Everything below reuses that seam — no new report path, no backend/admin work, no new strings.

## D1 — Card affordance: nullability-gated kebab, no model change

`PostCard` gains `onReport: (() -> Unit)? = null`. Kebab (an `IconButton` + `DropdownMenu` with one "Laporkan" item) renders iff non-null, trailing the identity header row per mockup frame 1 (`.post .head .more`: 20dp `more_vert` glyph, `onSurfaceVariant` tint; the M3 IconButton owns the ≥48dp touch target).

- **Why nullability-gating, not a `PostCardModel` flag:** mirrors the post-detail kebab's `onBlockPost: (() -> Unit)?` precedent; keeps `PostCardModel` display-only; default `null` keeps every existing caller (`EmbeddedPostCard`, tests) source-compatible and byte-identical.
- **Structural change:** the current identity `Row` is `fillMaxWidth` + clickable (profile target). It becomes `weight(1f)` inside an outer header `Row` with the kebab trailing, so the kebab is outside the profile tap target (spec: fires neither `onOpen` nor `onOpenProfile`).
- **Own posts render no kebab** (a one-item menu with the item ineligible = dead control). When future items land (block/share from the timeline), the kebab can become always-present the way the post-detail kebab did — that change MODIFIES the kebab requirement.

## D2 — Feed threading: per-item nullable action through PostFeedList

`PostFeedList` gains `reportActionOf: (T) -> (() -> Unit)? = { null }`; each card gets `onReport = reportActionOf(item.post)`. One parameter carries both eligibility and the callback (null = ineligible), so no parallel `eligibleOf` predicate.

## D3 — Report-flow state: one shared controller, per-feed instances

New `ui/timeline/TimelineReportController` mirroring `InlineLikeController` (the registered pattern for cross-feed card-action logic — no second pattern introduced):

- `reportingPostId: StateFlow<String?>` — non-null while the dialog is up (one-shot, docs/11 § 2.2).
- `reportMessage: StateFlow<TimelineReportMessage?>` — the one-shot result (`SUCCESS` / `RATE_LIMITED` / `FAILED`), mapped from `ReportOutcome` with the anti-enumeration rule (Submitted + Duplicate → `SUCCESS`), cleared via `onMessageShown()`. Deliberately parallel to `postDetailReportMessage` (that enum is post-detail-scoped; hoisting it into a shared home is churn across shipped files for two 4-line mappings).
- `onReportClicked(postId)` / `onDialogDismissed()` / `onSubmitted(category, note)` — submit closes the dialog immediately, then `reportSubmitter.submit(ReportTargetType.POST, postId, category, note)` on the host scope; compose-free and unit-testable, like the like controller.

Each of the three feed VMs (`NearbyTimelineViewModel`, `GlobalTimelineViewModel`, `FollowingTimelineViewModel`) holds an instance (ctor gains `ReportSubmitter`, resolved via `koinInject` at the screen like `LikeFlow`).

## D4 — Eligibility: VM-side authorship check off the raw DTO

The VMs already retain the raw timeline outcome (that's how `authorUserIdForPost` works for the profile tap). Eligibility = `authorUserIdForPost(post.id) != selfUserId`, with `selfUserId` resolved once from the suspend `SelfUserIdProvider.selfUserId()` into VM state on init (Nearby already injects the provider for the radius gate; Global/Following ctors gain it). Unresolvable self id (null) → treat as eligible-unknown → **no kebab** (fail-closed: never offer reporting we can't gate). The `PostCardModel` stays UUID-free — the comparison happens on the raw DTO in the VM.

## D5 — Result surface: shared overlay composable

Timeline screens are inset-free (no Scaffold — the shell owns it), so there is no existing snackbar host. A small shared `ui/timeline/TimelineReportOverlay` composable renders, for a given controller state: the `ReportDialog` (title `report_title_post`, per-feed test tag) while `reportingPostId` is non-null, and a bottom-aligned `SnackbarHost` fed by the one-shot message (resolved via `stringResource`, cleared via `onMessageShown` — the post-detail snackbar pattern). Each feed screen mounts it once inside its root `Box`. One implementation, three mounts — same shape as `DailyCapUpsellDialog` usage.

## Out of scope

- Block / share-to-chat items on the timeline kebab (untracked; the kebab requirement is their MODIFY hook).
- Reporting from chat's `EmbeddedPostCard` (stays kebab-free via the null default).
- Backend/admin: `POST /api/v1/reports` with `target_type=post` and the admin report queue already handle timeline-originated reports identically.
