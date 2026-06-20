## Context

`mobile-content-report` (PR [#359](https://github.com/aditrioka/nearyou-id/pull/359)) shipped a single shared report-submission seam — `data/report/` (`ReportReasonCategory`, `ReportTargetType`, `ReportApiClient`, `ReportSubmitter`, `ReportOutcome`) + `ui/components/ReportDialog` — consumed by the post-detail (post/reply) and profile (user) surfaces. It deliberately left `ReportTargetType` at `USER`/`POST`/`REPLY` and recorded an explicit "Chat-message report is deferred" guard, with the chat-surface work tracked as [#364](https://github.com/aditrioka/nearyou-id/issues/364). The backend already accepts the fourth target type (`reports` CHECK: `target_type IN ('post','reply','user','chat_message')`; `ReportRoutes.kt` maps it), so this change is the mobile half only — it adds the chat entry point and graduates `chat_message` from "deferred" to "delivered". The chat thread (`screens/chat/ChatThreadScreen.kt`) renders messages as `MessageBubble`s in a `LazyColumn` keyed by message id; `ChatThreadViewModel` already holds `viewerId` (via `ViewerIdProvider`) and each `ChatMessageRow` carries `id` + sender distinction. There is no existing long-press / kebab affordance on a bubble today.

**Standards conformance (docs/11 Pattern Registry).** This change builds entirely on existing patterns and introduces no new one (so **no docs/11 amendment is required**):
- **§2.6 Data layer** — reuses the shipped `ReportApiClient → ReportSubmitter → sealed ReportOutcome` seam unchanged (the chat thread is its 4th consumer). The only seam edit is one new enum member on `ReportTargetType`. No second networking/report path.
- **§2.2 State holder** — the report submission is wired through the existing `ChatThreadViewModel` (resolved via `viewModel { }` at the `ChatThreadRoute` NavEntry scope through the existing Koin module), injecting the concrete `ReportSubmitter` exactly as `PostDetailViewModel` does (`FakeReportSubmitter` in `commonTest`). One-shot UI result on the screen's single `StateFlow<…UiState>`, consumed + cleared via an `onXxxShown()` callback — not a `Channel`/`SharedFlow` event bus.
- **Navigation** — no new `NavKey` / route; the affordance is in-screen (long-press → menu → the existing `ReportDialog`).
- **Design system** — reuses the already-mockup-conformed `ReportDialog`; the only new chrome is the long-press menu (consult chat mockup frames 2 + 5 at apply time). All strings via `:shared:resources`.

## Goals / Non-Goals

**Goals:**
- Users can report an individual *received* chat message from the thread, via the shared report dialog, with the same outcome semantics as post-detail.
- Zero backend / schema / migration change; zero new networking or state pattern.
- Close the last unreportable-UGC surface (store-compliance + T&S).

**Non-Goals:**
- No "Blokir" affordance in the same long-press menu — blocking remains a profile/post-context action (docs/06 line 237). (A combined chat-bubble action menu can be a later follow-up.)
- No conversation-level / user-level report from chat (user reporting already exists on the profile surface).
- No backend change to auto-hide, rate-limit, or admin surfacing — a reported `chat_message` flows to the existing admin report queue + chat-redaction enforcement path unchanged.
- No edit to the `reports` capability spec (backend behavior is untouched).

## Decisions

**D1 — Reuse the shared seam; add exactly one enum member.** Add `CHAT_MESSAGE("chat_message")` to `ReportTargetType` and route chat reports through the existing `ReportSubmitter.submit(target = CHAT_MESSAGE, targetId = message.id, category, note)`. *Alternative rejected:* a chat-specific report client/dialog — that is the patchwork failure mode docs/11 exists to prevent, and would duplicate the outcome-mapping logic. Because `ReportTargetType` physically lives in the `mobile-content-report` capability's seam, adding the member is modeled as a MODIFIED requirement on that capability (not a silent edit).

**D2 — Long-press entry affordance, not a kebab.** Chat bubbles have no kebab and no spare chrome; the platform idiom for a per-message action is **long-press → contextual menu**. `docs/03` § Report UX line 230 enumerates the report *kebab* for "post, reply, profile page" and omits chat; `docs/06` § Report System line 229 ("one-tap report from a post, reply, profile, **and chat message**") is the governing canonical source and DOES include chat message. The kebab-list omission in docs/03 is a surface-idiom gap, not a behavior conflict — reconciled by a docs/03 follow-up (see Risks). *Alternatives rejected:* a persistent overflow icon on every bubble (clutters the thread, fights the minimal chat aesthetic); a swipe action (collides with future reply-swipe affordances).

**D3 — Gate on `senderId != viewerId` and exclude redacted messages.** Only the *other* party's messages expose "Laporkan" (mirrors the post `!isAuthor` gate); already-redacted messages (`redactedAt != null`) are excluded (already moderated). This is a **client-side UX gate, not a security boundary** — the backend `self_report_rejected` guard fires only for `target_type = "user"`, so a self-report of one's own chat message would be accepted as a harmless no-op (bounded by the 10/hour limit and by `chat_message` not participating in auto-hide). We gate it out for UX clarity, not correctness. *Alternative rejected:* expose report on all messages — needless clutter, and a "report my own DM" action is meaningless.

**D4 — Post-detail outcome posture (fold Duplicate into success), not profile posture.** `Submitted` (204) and `Duplicate` (409 `duplicate_report`) render the identical success message; the reporter cannot distinguish a first report from a repeat (anti-enumeration / anti-retaliation — especially important in a 1:1 context where a leak of "already reported" is attributable). `RateLimited` (429) shows a rate-limit message without claiming success; `NetworkError`/transport/unenumerated status is retryable. *Alternative rejected:* the profile surface's distinct "already reported" copy — that leaks prior-report state to the only other participant in the conversation.

**D5 — Reuse `ChatThreadViewModel`; one-shot result state.** No new state holder. The VM gains a `reportMessage(row)` entry and a nullable one-shot `reportResult` field on its existing `StateFlow`, cleared via `onReportResultShown()`. The dialog's selected category + optional note (≤200 code points, enforced by the shared dialog) are passed straight to `ReportSubmitter`. *Alternative rejected:* a separate report VM scoped to the dialog — fragments chat state and duplicates the post-detail wiring.

**D6 — No backend, no migration, no flag.** `target_type='chat_message'` is already valid; there is nothing to gate or roll out. Ships as an ordinary mobile-only squash-merge.

## Risks / Trade-offs

- **Long-press may collide with an existing bubble gesture (text copy/selection).** → `ChatThreadScreen` has no `combinedClickable`/`onLongClick` on `MessageBubble` today (verified); the long-press is net-new. If a copy-on-long-press is added later, both actions should share one menu — noted for that future change.
- **Optimistic / realtime-injected messages may briefly lack a persisted server id.** → Only messages carrying the real wire `id` are reportable; optimistic *sent* rows are own-messages and are already gated out by `senderId == viewerId`, so no un-persisted id ever reaches `target_id`.
- **Client self-report gate is not a backend boundary.** → Accepted (D3): a bypassed self-report is a harmless, rate-limited no-op; no security or privacy regression.
- **docs/03 § Report UX omits the chat entry point.** → Reconciliation pass (Phase B.3): file a `follow-up` to add the chat long-press entry to docs/03 § Report UX, OR amend it in this PR; docs/06 remains canonical and already covers it. Not a behavior conflict.
- **PII leakage via logs.** → The seam already forbids logging tokens/bodies/targetId; this change adds no log of `senderId`, message body, or the JWT `sub`. Enforced by a source-scan test scenario.

## Migration Plan

None. Mobile-only; no Flyway migration, no feature flag, no backend deploy. Standard one-PR lifecycle (propose → apply → archive → single squash-merge). Rollback = revert the squash commit (no data or schema state to unwind).

## Open Questions

- **Long-press surface: dropdown menu vs. modal bottom sheet?** Resolve at apply time against chat mockup frames 2 + 5 (the measurement annex). Default lean: a small `DropdownMenu` anchored to the bubble (lighter than a bottom sheet for a single action). Non-blocking — the `ReportDialog` it opens is unchanged either way.
