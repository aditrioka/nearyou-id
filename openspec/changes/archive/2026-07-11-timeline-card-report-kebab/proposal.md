# Proposal: timeline-card-report-kebab

## Why

`mobile-content-report` (PR #359) shipped post/reply reporting on `PostDetailScreen` only; the timeline-card entry point was explicitly deferred (spec requirement "Timeline-card report entry point is deferred", issue [#363](https://github.com/aditrioka/nearyou-id/issues/363)) to stay footprint-disjoint from `image-attached-posts` (#354), which owned `PostCard`. #354 has merged, so the deferral's reason is gone. Store-compliance/safety UX (`docs/03` § Report UX) wants reporting reachable where the content is seen — a user should be able to report a post directly from the Nearby/Following/Global feed without opening the detail screen.

## What Changes

- The shared timeline `PostCard` gains an overflow kebab with a single "Laporkan" item, rendered only when the host supplies a report action (nullability-gated, mirroring the post-detail kebab's optional block item). Chat's `EmbeddedPostCard` and other non-feed hosts pass nothing and stay kebab-free.
- `PostFeedList` threads a per-item report action to the card; the three feed hosts (Nearby / Global / Following) supply it only for **non-authored** posts (author UUID vs. the viewer's `SelfUserIdProvider` id — resolved in the VM off the raw DTO outcome, never on the PII-free card model).
- Tapping "Laporkan" opens the existing shared `ui/components/ReportDialog` (post title variant); submission goes through the existing shared `data/report/ReportSubmitter` seam with `target_type=post`. Outcome mapping keeps the anti-enumeration rule (Submitted AND Duplicate → the same success message); the one-shot result renders as a snackbar, RateLimited/NetworkError typed — same posture as post-detail.
- The report-flow state (dialog target + one-shot message) lives in a shared controller instantiated per feed VM, mirroring the `InlineLikeController` precedent, so the logic exists exactly once across the three feeds.
- Closes issue #363; the `mobile-content-report` deferred requirement flips to a fulfilled one (per the capture-deferred-behaviors-as-requirements convention).

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `mobile-content-report`: the "Timeline-card report entry point is deferred" requirement is REPLACED by a requirement that the timeline card DOES expose a report entry point (kebab → shared dialog → shared submitter, `target_type=post`, gated on non-authorship, anti-enumeration outcome mapping).
- `mobile-post-card`: the shared card's contract gains the optional report overflow affordance (rendered iff the host supplies the action; absent by default so existing non-feed hosts are unchanged) and the card stays structurally PII-free (no author UUID added).

## Impact

- **Mobile only** (`:mobile:app` commonMain + androidUnitTest): `ui/components/PostCard.kt`, `ui/components/PostFeedList.kt`, a new shared controller in `ui/timeline/`, the three feed screens + VMs (`screens/timeline/`), DI unchanged (ReportSubmitter is already a Koin singleton; VM constructors gain injected seams).
- **No backend change** (`POST /api/v1/reports` with `target_type=post` already exists and is consumed by post-detail), **no admin change** (reports land in the existing report queue), **no new strings expected** (reuses `report_title_post`, `profile_report_action`, `profile_report_success_toast`, rate-limit/failure copy, kebab content description).
- Cross-layer cohesion (docs/12): this is the mobile-remainder of an already-shipped vertical slice — backend + admin legs shipped with `content-report` capabilities; no deferred-layer declaration needed.
- PR carries `Closes #363`.
