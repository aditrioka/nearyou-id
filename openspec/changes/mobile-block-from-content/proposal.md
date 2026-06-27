## Why

A user who encounters an abusive post or reply on the mobile post-detail screen can **report** it but cannot **block** its author without leaving the screen — and the post-detail header identity is not even a tap target, so there is no in-context path to the author's profile (where block already lives). This is a gap against the canonical safety contract: `docs/02-Product.md` §"Block User" and `docs/03-UX-Design.md` §"Block User UX" both specify **"Kebab menu (post, reply, profile page): 'Blokir @{username}'"**. The profile-page block shipped with `mobile-profile`; the post/reply context-menu block is the unbuilt third entry point. The backend block endpoint (`POST /api/v1/blocks/{userId}`) and its symmetric enforcement already exist — this change wires the missing front-of-house affordance.

## What Changes

- Add a **"Blokir @{username}"** item to the post-detail **post-header overflow kebab** (today report-only, gated on `!isAuthor`) and the **reply-row overflow kebab** (today report-only, self-block guarded).
- Present the canonical **confirmation dialog** (`docs/03` §Block User UX, verbatim copy) before the block call, and a success **toast** ("Pengguna telah diblokir"); on success the post-block pops back to the timeline and the reply-block removes the row (the blocked content 404s/hides bidirectionally).
- Extract a **shared block-create seam** (`data/block/BlockSubmitter`) mirroring the shipped `data/report/ReportSubmitter` pattern, wrapping `POST /api/v1/blocks/{userId}` → `BlockOutcome` (`Blocked` / `RateLimited` / `NetworkError`); the existing profile block path is refactored onto this one implementation (no behavior change).
- **Reply block** is mobile-only: `ReplyDto.authorId` is already on the wire (carried, never rendered). Surface it to the block call only.
- **Post block** requires the author UUID, which the post-detail wire deliberately omits. Additively expose **`authorUserId`** on the single-post-read response (the server already derives `isAuthor` from `author_id`; the field is never rendered, block-action-only — the same wire-carries-but-never-renders precedent `ReplyDto.authorId` already sets) and thread it into the `PostDetailRoute` payload. **No Flyway migration, no backend block-semantics or schema change.**
- **Deferred (explicit requirements + tracking issues):** the timeline-card (`PostCard`) block kebab (PostCard is owned by in-flight `image-attached-posts` #354 — mirrors the report capability's #363 deferral; `mobile-post-card` spec untouched); and the post-detail header tap-to-profile.

## Capabilities

### New Capabilities
- `mobile-block-from-content`: the mobile post-detail block-from-context-menu surface — the post-header + reply-row "Blokir @{username}" affordances, the shared `BlockSubmitter` seam, the confirmation dialog + toast, the post/reply block outcomes (nav + row removal + rate-limit/network mapping), and the explicit timeline-card-deferral guard.

### Modified Capabilities
- `single-post-read`: additively expose `authorUserId` on the single-post wire DTO — never rendered, block-action-only (relaxes the issue-#202 "no author UUID on the single-post wire" stance, consistent with the established `ReplyDto.authorId` pattern). `isAuthor` and all other fields unchanged.
- `mobile-post-detail`: the `PostDetailRoute` payload carries `authorUserId` (never rendered — PII discipline preserved); the post-header overflow now hosts a block affordance alongside report.

## Impact

- **Backend** (`:backend:ktor`, `post/SinglePostRoutes.kt`): one additive `authorUserId` field on `SinglePostResponse` + its select projection; KDoc amended to record the relaxed #202 stance. No migration, no new endpoint.
- **Mobile** (`:mobile:app`): new `data/block/BlockSubmitter` + `ui/components/BlockConfirmDialog`; `PostDetailScreen` post-header & reply-row kebabs gain the block item; `PostDetailViewModel`/route-payload thread `authorUserId`; profile block path refactored onto the shared seam; new `Res.string.*` entries (block menu item, confirm dialog title/body/buttons, success/rate-limit toasts).
- **Docs**: `docs/05` §User Blocking / issue #202 reconciliation note for the new `authorUserId` field (B.3 reconciliation item).
- **Specs**: new `mobile-block-from-content`; modified `single-post-read`, `mobile-post-detail`. `mobile-content-report`, `user-blocking`, `mobile-post-card` referenced but unchanged.
- **Follow-up issues**: timeline-card block kebab; post-detail header tap-to-profile.
