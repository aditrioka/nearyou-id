## Context

Chat moderation today closes the loop at the *user* level (suspend / ban / shadow-ban via the report-queue resolution actions) and the *post/reply* level (Hide). A reported **chat message** carrying a severe violation (PII leak, doxxing) has no message-level remediation — the resolution actions reject content actions for `chat_message` targets ("Keep/Hide apply only to post or reply"). The chat data plane was already built redaction-ready: `chat_messages` carries `redacted_at` / `redacted_by` / `redaction_reason` + the atomicity CHECK (V15), the `redacted_by → admin_users(id) ON DELETE SET NULL` validated FK (V16), and the read path (`ChatDtos`/`ChatRepository`/`ChatRoutes`) already NULL-masks `content` and omits `redaction_reason` when `redacted_at IS NOT NULL` (with matching scenarios in `chat-conversations` + `chat-realtime-broadcast`). The `chat_message_redacted` notification type is already in the V10 catalog as a reserved emit site. **This change is the admin WRITE surface** that fills that gap — nothing in the schema, the chat feature, or the notification catalog needs to change.

This is the backend/admin half. Roadmap home: [`docs/08-Roadmap-Risk.md`](../../../docs/08-Roadmap-Risk.md) § Phase 3.5 #23. Visual target: admin mockup frame 9.

## Goals / Non-Goals

**Goals:**
- A `GET /admin/chat-messages/{id}` confirmation page rendering the target message + ±2 surrounding messages for moderation context, a required reason field, and the Redact action.
- A `POST /admin/chat-messages/{id}/redact` write that is CSRF-gated, owner/admin-tier-only, destructive-rate-limited, idempotent, and atomic — writing the redaction flags, one immutable audit row, and a participant notification in one transaction.
- A deep-link from `chat_message` report rows to the redaction page.
- Activate + shape the `chat_message_redacted` notification.

**Non-Goals (explicit, tracked as follow-ups — not silent omissions):**
- Mobile rendering of the redacted message + the `chat_message_redacted` notification (mobile lane; backend already returns `content: null`).
- CSAM-specific handling (the separate frame-13 CSAM surface; redaction here is for PII/doxxing).
- Un-redaction / reversal (redaction is terminal; an erroneous redaction is corrected out-of-band).
- Reporting chat messages from mobile (already shipped — `reports.target_type` includes `chat_message`).
- Any schema change (none needed).

## Decisions

### D1 — `POST`, not `PATCH` (frame-caption fix)
Frame 9's caption labels the route `PATCH /admin/chat-messages/{id}/redact`. The admin panel's **no-JS `<form>` fallback discipline** (`docs/11` §3.6) only supports GET/POST, and every shipped admin write action is `POST`. **Decision: `POST`.** Per the §3.6 precedence rule ("specs/docs win; flag the divergence"), `tasks.md` includes correcting the frame-9 path label `PATCH` → `POST` in the same PR. Alternative (honor PATCH via `hx-patch`): rejected — breaks the no-JS fallback contract the whole panel is built on.

### D2 — The whole surface is owner/admin tier-gated (GET + POST)
`docs/07` specifies redaction is `role IN ('owner','admin')` only — **moderator and read_only are not permitted**. Unlike the report queue (where the GET listing is any-admin and `ban` is a *sub-option* tier-checked in-repo via the in-band `ForbiddenBanTier` branch), redaction is a **whole surface that is owner/admin-only end to end**, AND its GET page discloses **private 1:1 chat content** (the ±2 context). **Decision:** gate BOTH `GET /admin/chat-messages/{id}` and `POST .../redact` at the owner/admin tier — a valid admin session whose role is `moderator` or `read_only` receives **403** (the page/form is never shown to a non-owner/admin, so the content disclosure is limited to admins who can actually act). Add a reusable `AdminRoleGate.requireOwnerOrAdmin(call)` sibling to the existing `requireWriteRole` (configuration of the existing role-gate concern — not a new pattern). The POST gate order is therefore: CSRF first (403 + `admin_csrf_violation` on miss) → `requireOwnerOrAdmin` (403 on miss) → parse UUID → validate reason → rate limit → repo txn. Alternative (mirror the report route's any-admin GET + in-band tier message): rejected — it would expose private DM content to `read_only`/`moderator` admins who cannot act, a privacy smell that outweighs the pattern-symmetry gain.

### D3 — Idempotency via `WHERE redacted_at IS NULL`
The redaction UPDATE carries `... WHERE id = :id AND redacted_at IS NULL`. 0 rows updated → if the row exists it is already redacted (in-band "already redacted, no change made"), else NotFound (in-band "no matching message"). Re-redaction is a safe no-op; the atomicity CHECK is satisfied because `redacted_at` + `redacted_by` are set together in the same statement.

### D4 — Notification: both active participants, system-originated, content-free
Recipients = **all active participants** of the conversation (`conversation_participants WHERE conversation_id = … AND left_at IS NULL`), per `docs/07` "affected conversation participants receive". `docs/05` line ~1034 phrases it "recipient receives" (singular) — the spec follows `docs/07`'s plural; the `docs/05` wording is flagged as a minor reconciliation (a `follow-up` doc-fix, not a behavior change). The emit goes through the existing `NotificationEmitter` with **`actor_user_id = NULL`** (system-originated): the emitter's null-actor path skips the block-check and writes unconditionally, so a block between the two participants does NOT suppress the redaction notice. **Shadow-ban actor-masking rule (`in-app-notifications` § emitter, cross-capability rule) is N/A** — there is no user actor to shadow-ban; redaction is a non-stealth moderation action (unlike shadow-ban), so participants are openly informed. `body_data = {"conversation_id": <uuid>, "message_id": <uuid>}` — it carries **no content and no reason** (data-plane PII discipline; the whole point is to remove the content from user-visible surfaces). `target_type = 'chat_message'`, `target_id = <message id>`. Emitted in the redaction transaction; FCM fan-out post-commit (the established notification-emit pattern).

### D5 — ±2 context disclosure window
The GET page shows the target message plus up to 2 messages immediately before and after (by `created_at`) in the same conversation, so the moderator sees enough context to judge a severe violation. This is a deliberate **limited-disclosure** read aligned with the "chat is admin-readable for moderation appeal" privacy posture (`docs/02` § chat). The admin module reads raw `chat_messages` / `conversation_participants` (allowlisted from `RawFromPostsRule` / `BlockExclusionJoinRule` per `docs/04` § Admin Panel Data Access). Surrounding redacted messages render as the redacted placeholder, not their original content.

### D6 — Reason required at the application layer
The DB atomicity CHECK does not require `redaction_reason` (only `redacted_at` + `redacted_by` coupled), but frame 9 and `docs/07` mark the reason **required**. **Decision:** the POST handler rejects a blank/missing reason with **400, no write** (same shape as the report route's invalid-decision 400). The reason is stored in `chat_messages.redaction_reason` (admin-only; never serialized on the chat data plane) and in the audit row.

### D7 — Audit row
One immutable `admin_actions_log` row per applied redaction: `action_type = 'admin_chat_redaction'`, `target_type = 'chat_message'`, `target_id = <message id>`, `reason = <redaction reason>`, `before_state` = the original content (the audit trail legitimately retains the original for the moderation record — `admin_actions_log` is admin-only, immutable at the `admin_app` role level, 1-year retention), `after_state` = a redacted marker, `ip = call.clientIp` (request-context value, never raw `X-Forwarded-For`), `user_agent`. No audit row is written on a no-op / rejection.

### D8 — Reuse the report-resolution pattern verbatim (no new pattern)
The route reuses the shipped `AdminReportResolutionRoute` shape exactly: gate order (CSRF → owner/admin role gate per D2 → parse UUID → validate reason → rate limit → repo txn), the single-parse `formParametersAfterValidation` body read, the outcome enum → in-band-message mapping (no-op / not-found / rate-limited), and the dual-mode response (HTMX fragment vs. no-JS 303). The repository mirrors `ReportResolutionRepository` (one transaction, `RETURNING`-guarded idempotent UPDATE, audit + notification in-txn). The destructive-action gate reuses `DestructiveActionRateLimiter` (20/hour per admin, shared budget).

### Standards conformance (`docs/11`)
- **Backend layering (§3.1):** `AdminChatRedactionRoute` (thin: parse/validate/authenticate/respond) → `ChatRedactionRepository` (transaction boundary + SQL). DTOs/view-maps live with the route; SQL rows do not leak into response shapes. Cross-feature notification emit goes through the `notifications` feature's `NotificationEmitter` interface (no direct table reach).
- **JDBC discipline (§3.2):** repository runs on the shared bounded dispatcher; one transaction per redaction op; test pools `autoClose(hikari())` + size 2 (CI connection budget).
- **Admin UI (§3.6):** Pebble template + HTMX fragment swap + vendored vanilla CSS (tokens lifted from the board `.frame` block), no-JS `<form method=post>` fallback, no client framework, no inline styles. Built against frame 9 (render + measurement annex at apply time).
- **Pattern Registry:** introduces **no new pattern** for any registered concern (route/service/repository, CSRF, role gate, rate limit, audit, notification emit) — every pattern is the shipped admin-write one. **No `docs/11` amendment required.**

## Risks / Trade-offs

- **Original content retained in `admin_actions_log.before_state`** (could be the doxxed address) → Mitigation: the audit log is admin-only, immutable, role-revoked for UPDATE/DELETE, 1-year retention; it is the intended moderation record and is never exposed on any user-facing data plane. Consistent with how the report-resolution audit records enforcement state.
- **Both participants notified (including the message sender)** → this is intended transparency (redaction is non-stealth); the sender learns their message was removed by a moderator and can use the existing appeal path. Differs deliberately from shadow-ban's stealth invariant.
- **No un-redact** → terminal by design; an erroneous redaction is corrected out-of-band (DB) for MVP. A reversible-redaction admin action can be a later follow-up if needed.
- **Reason persisted in `chat_messages.redaction_reason`** → never serialized on the chat data plane (`ChatDtos` omits it unconditionally); only the admin audit + admin reads see it.

## Migration Plan

No DB migration (schema, FK, notification type, and read-path render all already shipped — V10/V15/V16 + the chat feature). Deploy = code + templates. Rollback = revert the PR (no data migration to unwind; any rows already redacted stay validly redacted). Pre-archive: admin-panel `verify-loop` bring-up of `GET /admin/chat-messages/{id}` + a redaction round-trip with screenshot evidence in the PR body (`docs/11` §5 DoD). Runtime-impacting backend change → SHOULD run a staging branch deploy + smoke before archive (project.md § Staging deploy timing).

## Open Questions

- **Notify the redacted message's sender, or only the other participant?** The spec follows `docs/07` ("affected conversation participants" = both active participants). If the operator prefers recipient-only (the `docs/05` "recipient" singular reading), this narrows to the non-sender participant — flag at review. Default: both.
- None blocking implementation.
