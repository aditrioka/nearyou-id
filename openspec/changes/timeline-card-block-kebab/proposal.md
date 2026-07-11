# Proposal: timeline-card-block-kebab

## Why

`mobile-block-from-content` (PR #439) shipped the post-detail post-header + reply-row "Blokir @{username}" entry points plus the shared `data/block/BlockSubmitter` seam and `ui/components/BlockConfirmDialog`; the timeline-card entry point was explicitly deferred (spec requirement "Timeline-card block entry point is deferred", issue [#456](https://github.com/aditrioka/nearyou-id/issues/456)) to stay footprint-disjoint from `image-attached-posts` (#354), which owned `PostCard`. #354 has merged and the timeline card has since gained its overflow kebab (`timeline-card-report-kebab`, PR #462), so both the deferral's reason and the "no kebab to put the item on" constraint are gone. The safety contract (`docs/02` §"Block User", `docs/03` §"Block User UX": *kebab menu on post, reply, profile*) wants blocking reachable where the content is seen — a user should be able to block an author directly from the Nearby/Following/Global feed without opening the detail screen.

## What Changes

- The shared timeline `PostCard` kebab gains a second item, "Blokir @{username}" (resource `profile_block_action`), rendered only when the host supplies a block action (nullability-gated `onBlock`, mirroring `onReport`). The kebab itself now renders when EITHER action is supplied; chat's `EmbeddedPostCard` and other non-feed hosts pass nothing and stay kebab-free.
- `PostFeedList` threads a per-item block action to the card (`blockActionOf`, mirroring `reportActionOf`); the three feed hosts (Nearby / Global / Following) supply it only for **non-authored** posts (raw-DTO `authorUserId` vs. the viewer's `SelfUserIdProvider` id — the shipped report-kebab gate, fail-closed while unresolved).
- Selecting "Blokir @{username}" opens the existing shared `BlockConfirmDialog`; a confirmed block goes through the existing shared `BlockSubmitter` seam (`POST /api/v1/blocks/{userId}`). Outcome mapping mirrors the block contract: `Blocked` → the success toast + the feed locally removes every loaded post by the blocked author (mutual invisibility — a refresh would drop them anyway); `RateLimited`/`NetworkError` → typed one-shot messages, list unchanged.
- The block-flow state (dialog target + one-shot message) lives in a shared `ui/timeline/TimelineBlockController` instantiated per feed VM — the `TimelineReportController`/`InlineLikeController` precedent, so the logic exists exactly once across the three feeds.
- Closes issue #456; the `mobile-block-from-content` deferred requirement flips to a fulfilled one (per the capture-deferred-behaviors-as-requirements convention), and the `PostDetailSourceGuardTest` PostCard-stays-block-free negative guard flips with it.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `mobile-block-from-content`: the "Timeline-card block entry point is deferred" requirement is RENAMED+MODIFIED into a requirement that the timeline card DOES expose a block entry point (kebab item → shared `BlockConfirmDialog` → shared `BlockSubmitter`, gated on non-authorship, `Blocked` → toast + local removal of the author's loaded posts).
- `mobile-post-card`: the optional-kebab requirement is MODIFIED — the kebab renders when at least one of the two hoisted actions (`onReport` / `onBlock`) is supplied, and its menu carries the corresponding item(s); the card stays structurally PII-free (no author UUID added; the block target username rides the existing display model).

## Impact

- **Mobile only** (`:mobile:app` commonMain + androidUnitTest): `ui/components/PostCard.kt`, `ui/components/PostFeedList.kt`, a new `ui/timeline/TimelineBlockController.kt`, the timeline overlay (`ui/timeline/TimelineReportOverlay.kt` grows the block dialog + shared snackbar feed), the three feed screens + VMs (`screens/timeline/`); VM constructors gain the already-singleton `BlockSubmitter` (DI otherwise unchanged).
- **No backend change** (`POST /api/v1/blocks/{userId}` already exists and is consumed by profile + post-detail; timeline DTOs already carry `authorUserId`), **no admin change** (blocks land in the existing block registry), **no new strings expected** (reuses `profile_block_action`, `profile_block_confirm_*`, `cta_block`, `cta_cancel`, `profile_block_success_toast`, `profile_block_rate_limited`, network-failure copy, kebab content description).
- Cross-layer cohesion (docs/12): this is the mobile-remainder of an already-shipped vertical slice — backend + admin legs shipped with the blocking capabilities; no deferred-layer declaration needed.
- PR carries `Closes #456`.
