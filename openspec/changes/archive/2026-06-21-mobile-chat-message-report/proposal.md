## Why

Chat messages are the **last unreportable user-generated-content surface** in the mobile app. After `mobile-content-report` (PR [#359](https://github.com/aditrioka/nearyou-id/pull/359)), users can report posts, replies, and other users — but an abusive 1:1 direct message has no in-app report path. For an 18+ UGC product this is both a trust-&-safety hole and an **app-store-review requirement** (Apple/Google both mandate in-app reporting of user-generated content). The backend already supports it (`reports` validates `target_type='chat_message'`) and `docs/06` § Report System (line 229) names chat-message reporting as canonical, so the gap is purely the missing mobile surface. Tracked by follow-up issue [#364](https://github.com/aditrioka/nearyou-id/issues/364).

## What Changes

- Add a **"Laporkan" (report) affordance to the 1:1 chat thread** (`ChatThreadScreen`): long-press a received message bubble → a small menu exposing "Laporkan" → the existing shared `ReportDialog`. Long-press is the chat idiom (chat bubbles carry no kebab).
- Submit `POST /api/v1/reports` with `target_type = "chat_message"`, `target_id = <chat message id>`, the selected `reason_category`, and an optional ≤200-code-point note.
- **Reuse the shared report seam** shipped by `mobile-content-report` (`data/report/` — `ReportReasonCategory`, `ReportApiClient`, `ReportSubmitter`, `ReportOutcome` — plus `ui/components/ReportDialog`). The chat thread becomes the **4th consumer** alongside post-detail (post/reply) and profile (user). The ONLY edit to the shared seam is adding the `CHAT_MESSAGE("chat_message")` member to `ReportTargetType` (whose own doc-comment already reserves chat_message as "a deferred chat-surface change"). No second report path is introduced (anti-patchwork, docs/11 Pattern Registry).
- **Gate** the affordance on the row projection `isReportable = !isOwn && !isRedacted` (only the *other* party's, non-redacted messages are reportable — mirrors the post `!isAuthor` gate). The chat row carries `isOwn`/`isRedacted` and drops the raw `senderId` (PII discipline), so the gate never re-threads `senderId` onto the UI path.
- **Outcome rendering mirrors the post-detail posture** (not the profile posture): `Submitted` (204) and `Duplicate` (409 `duplicate_report`) both render the same success message (anti-enumeration / anti-retaliation, docs/03 line 234); `RateLimited` (429) does not claim success; `NetworkError` is retryable. One-shot UI state consumed + cleared via an `onXxxShown()` callback. The reporter is never shown a moderation review outcome.
- **No backend, schema, or Flyway migration change** — the `reports` endpoint, its 10/hour rate limit, duplicate handling, and the (post/reply-only) auto-hide coupling are all unchanged. Admin enforcement for a reported chat message is the already-shipped chat-redaction path (`admin-chat-message-redaction`).
- All new UI strings come from `:shared:resources` Compose Multiplatform Resources — no hardcoded UI string literals.

## Capabilities

### New Capabilities
- `mobile-chat-message-report`: the `:mobile:app` chat-thread surface for reporting an individual chat message — the long-press → "Laporkan" → shared `ReportDialog` entry point, the `isReportable = !isOwn && !isRedacted` affordance gate, submission via the shared seam with `target_type = "chat_message"`, the post-detail-style outcome→UI mapping, PII discipline, and the commonTest / Robolectric / iOS test trio.

### Modified Capabilities
- `mobile-content-report`: (1) the shared-seam requirement that fixes `ReportTargetType` at `USER`/`POST`/`REPLY` is amended to include `CHAT_MESSAGE("chat_message")`; (2) the "Chat-message report is deferred" requirement is flipped from a deferred-guard to a delivered-elsewhere pointer (the affordance now ships in `mobile-chat-message-report`), so the spec is not left with a contradictory "SHALL NOT add a chat-message report" guard.

## Impact

- **Modules:** `:mobile:app` only (`screens/chat/**`, the shared `data/report/ReportTargetType.kt`, `:shared:resources` strings). No `:backend:ktor` change.
- **Endpoints/schema:** none new or changed — reuses the shipped `POST /api/v1/reports` (`target_type='chat_message'` already valid). **No Flyway migration** → footprint-disjoint from all in-flight backend PRs and from `image-attached-posts` (#354, which owns `PostCard`); the chat thread is a separate surface.
- **Tests:** new `commonTest` (target-type wire mapping, outcome→state mapping, affordance-visibility projection), a new Robolectric `ChatThreadScreenTest` path (Release-variant-excluded), and a new iOS flow test.
- **Docs:** `docs/03` § Report UX (line 230) lists the report kebab for "post, reply, profile page" only; the chat surface uses long-press, not a kebab. This is a docs/idiom nuance to reconcile (docs/06 § Report System line 229 is the governing canonical source and already includes chat message). Handled in the reconciliation pass.
- **Verification:** UI-affecting → manual `verify-loop` bring-up with screenshot evidence required before archive (docs/11 §5 DoD).
