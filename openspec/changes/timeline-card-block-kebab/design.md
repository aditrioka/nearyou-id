# Design: timeline-card-block-kebab

## Context

`mobile-block-from-content` shipped the shared block seam (`data/block/BlockSubmitter` + `BlockOutcome` + `ui/components/BlockConfirmDialog`) consumed by profile and post-detail (post-header + reply-row). `timeline-card-report-kebab` shipped the timeline card's overflow kebab, the `PostFeedList` per-item action threading, the VM-side authorship gate, and the feed overlay (dialog + snackbar). This change is the intersection: the block item on the timeline kebab. Everything below reuses those two seams — no new block path, no backend/admin work, no new strings.

## D1 — Card affordance: second nullability-gated menu item on the existing kebab

`PostCard` gains `onBlock: (() -> Unit)? = null`. The kebab now renders when **either** action is supplied (`onReport != null || onBlock != null`); its `DropdownMenu` carries "Laporkan" iff `onReport != null` and "Blokir @{username}" (resource `profile_block_action`, interpolated with `model.authorUsername`) iff `onBlock != null`, in that order (report first — matches the post-detail kebab's item order).

- **Why the username renders on the item:** `docs/03` §Block User UX specifies the kebab item copy as "Blokir @{username}". `authorUsername` is already on the display-only `PostCardModel` (public handle, not PII) — no model change, no UUID introduced.
- **Why nullability-gating per item:** mirrors the shipped `onReport` gate and the post-detail kebab's `onBlockPost: (() -> Unit)?` — one parameter carries eligibility AND the callback; own posts get neither item, so their card renders no kebab (no dead control), unchanged from today.
- New test tag `POST_CARD_BLOCK_ITEM_TAG` beside `POST_CARD_REPORT_ITEM_TAG`; `POST_CARD_KEBAB_TAG` unchanged.

## D2 — Feed threading: `blockActionOf` beside `reportActionOf`

`PostFeedList` gains `blockActionOf: (T) -> (() -> Unit)? = { null }`; each card gets `onBlock = blockActionOf(item.post)`. Identical shape to the shipped `reportActionOf` (design D2 of `timeline-card-report-kebab`); the default keeps every existing caller source-compatible and kebab-free.

## D3 — Block-flow state: one shared controller, per-feed instances

New `ui/timeline/TimelineBlockController` mirroring `TimelineReportController` (the registered pattern for cross-feed card-action state — no second pattern):

- `blockTarget: StateFlow<TimelineBlockTarget?>` — non-null while `BlockConfirmDialog` should be shown; carries `authorUserId` (the POST path param, never rendered) + `authorUsername` (the dialog's display identity). Cleared by dismiss or confirm.
- `blockMessage: StateFlow<TimelineBlockMessage?>` — the one-shot result (`SUCCESS` / `RATE_LIMITED` / `FAILED` ← `Blocked` / `RateLimited` / `NetworkError`), cleared via `onMessageShown()`. Deliberately parallel to `TimelineReportMessage` (same three-member shape; the report enum is report-scoped).
- `onBlockClicked(authorUserId, authorUsername)` / `onDialogDismissed()` / `onConfirmed()` — confirm closes the dialog immediately, then `blockSubmitter.submit(userId)` on the host scope; a `Blocked` outcome ALSO invokes the ctor-injected `removeAuthorPosts: (authorUserId) -> Unit` before surfacing `SUCCESS`. Compose-free and unit-testable.

Each of the three feed VMs holds an instance (ctor gains the already-Koin-singleton `BlockSubmitter`), exactly like `reportController`.

## D4 — Blocked outcome on a feed: success toast + local removal of the author's loaded posts

`Blocked` → the canonical success toast (`profile_block_success_toast`) AND the host VM filters every post with the blocked `authorUserId` out of its retained `Loaded` outcome (posts list only; cursor/anchor untouched — the next load-more page is server-side block-excluded anyway). Rationale: the block contract is mutual invisibility — leaving the author's cards on screen (post-detail's "pop back" has no timeline equivalent, and report's "leave the list alone" would contradict the just-confirmed dialog copy). A refresh would drop them regardless (`visible_posts` + the block-exclusion join); the local filter just makes the promise immediate. `RateLimited`/`NetworkError` → typed one-shot message, NO removal (mirrors the block spec's failed-block-leaves-surface-unchanged rule).

## D5 — Eligibility: the shipped report gate, shared

Same VM-side gate as `reportActionFor`: `blockActionFor(postId, selfUserId)` returns null when the self id is unresolved (fail-closed), the post left the loaded set, or the viewer authored it; otherwise a closure over the raw DTO's `authorUserId` + `authorUsername`. The comparison runs on the raw DTO — `PostCardModel` stays UUID-free. No self-block path exists structurally (own posts get no item), matching the post-detail posture.

## D6 — Result surface: the existing overlay grows the block half

`TimelineReportOverlay` is renamed to **`TimelineActionsOverlay`** (same file rename, `ui/timeline/`) and gains the block state: renders `BlockConfirmDialog` while `blockTarget` is non-null (per-feed test tag), and feeds block messages into the SAME single `SnackbarHost` the report messages use (one host per feed screen — a second host would double-render). Three call sites (the feed screens) update mechanically. Why not a second overlay: two overlapping `SnackbarHost`s in one `Box` is a real z-order/duplication hazard, and the overlay's job is "the kebab's dialogs + one-shot messages" — one component, now two actions.

## Out of scope

- Share-to-chat (send) item on the timeline kebab (untracked; the kebab requirement stays the MODIFY hook).
- Blocking from chat's `EmbeddedPostCard` (stays kebab-free via the null defaults).
- Backend/admin: `POST /api/v1/blocks/{userId}`, rate limits, the block registry, and feed block-exclusion already handle timeline-originated blocks identically.
- Unblock / undo from the toast (the settings block-list owns unblock).
