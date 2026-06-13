## Why

A reported chat message carrying a severe violation (PII leak, doxxing) has **no in-panel remediation today**. The shipped report-queue resolution actions can suspend / ban / shadow-ban the offending *user* and Hide a *post or reply*, but they explicitly **cannot act on a chat message** ("Keep/Hide apply only to post or reply targets"). Message-level redaction is the missing moderation action — Phase 3.5 roadmap item #23 ([`docs/08-Roadmap-Risk.md`](../../../docs/08-Roadmap-Risk.md) § Phase 3.5). The chat data plane was already built redaction-ready (the read path NULL-masks `content` and omits `redaction_reason` when `redacted_at IS NOT NULL`; `chat-conversations` + `chat-realtime-broadcast` already carry the redacted-render scenarios), so this change only adds the admin **write** surface that sets the redaction flags, notifies participants, and writes the audit row.

## What Changes

- **New admin surface — Chat Message Redaction** (admin mockup frame 9, `dev/mockups/nearyou-admin-mockup.html` #f09):
  - `GET /admin/chat-messages/{id}` — a redaction confirmation page rendering the target message plus **±2 surrounding messages** for moderation context (limited-disclosure, aligned with the "admin-readable for moderation appeal" privacy posture), a **required** redaction-reason field, and the Redact action.
  - `POST /admin/chat-messages/{id}/redact` — the state-changing write: CSRF-gated → **owner/admin tier only** → idempotent atomic `redacted_at` + `redacted_by` + `redaction_reason` write → immutable `admin_actions_log` row (`admin_chat_redaction`) → `chat_message_redacted` notification to both active conversation participants — all in one DB transaction, written via the shipped admin raw-INSERT pattern (in-app feed only, no FCM push). Mirrors the shipped `AdminReportResolutionRoute` gate order + dual-mode (HTMX-fragment / no-JS 303) response verbatim.
  - Reachable as a **deep-link from a `chat_message` report row** in the Report Queue (`reports.target_type` already includes `'chat_message'`).
- **Activate the `chat_message_redacted` notification emit site** — the type is already in the V10 catalog (currently one of the "reserved for future emit sites"); this change defines its shape per the canonical V10 catalog (docs/05): `target_type = 'message'`, `target_id = <message id>`, `body_data = {conversation_id}` (one key; carries **no content, reason, or message_id** — data-plane PII discipline + the "don't duplicate target_id" rule) and writes it via the shipped admin raw-INSERT pattern.
- **NO migration** — `chat_messages.redacted_at/redacted_by/redaction_reason` + the atomicity CHECK + the redacted index shipped in V15; the `redacted_by → admin_users(id) ON DELETE SET NULL` validated FK shipped in V16; the `chat_message_redacted` notification type shipped in V10.
- **NO chat-feature change** — the chat read/data-plane path already honors redaction.
- **Frame-9 caption fix**: the board labels the route `PATCH`; the shipped no-JS `<form>` fallback discipline ([`docs/11`](../../../docs/11-Engineering-Standards.md) §3.6) only allows GET/POST and every admin write is POST → spec POST and correct the frame caption label in the same PR.

## Capabilities

### New Capabilities
- `admin-chat-message-redaction`: the admin chat-message redaction surface — the `GET` confirmation page (±2 context), the CSRF + owner/admin-tier + destructive-rate-limited `POST .../redact` write, the idempotent atomic redaction transaction (redaction flags + participant notification + audit row), and the dual-mode HTMX/no-JS response.

### Modified Capabilities
- `in-app-notifications`: activate the `chat_message_redacted` emit site — move it from "reserved for future emit sites" to written, and define its shape (`target_type = 'message'`, `body_data = {conversation_id}`, system-originated, in-app only) per the canonical V10 catalog, alongside the existing § "body_data shape per emitted type".
- `admin-report-queue`: a report row whose `target_type = 'chat_message'` additionally renders a "Redact message" deep-link to `GET /admin/chat-messages/{target_id}` (alongside the existing offending-user deep-link).
- `admin-destructive-action-rate-limit`: add `admin_chat_redaction` to the destructive set so a redaction both is gated by AND counts toward the shared 20/hr per-admin cap (the `DestructiveActionRateLimiter` COUNT_SQL gains the direct `action_type = 'admin_chat_redaction'` arm).

## Impact

- **Code (backend, `:backend:ktor` `admin` package)**: new `admin/chatredaction/` repository + `admin/routes/AdminChatRedactionRoute.kt` (GET page + POST redact); wiring in `AdminModule.kt`; reuse of `AdminCsrfGate`, `AdminRoleGate`, `DestructiveActionRateLimiter`, `AdminAuditLogger`, and the existing notification-emit transactional helper.
- **Templates**: new `templates/admin/chat-redaction.peb` + HTMX fragment; one added deep-link in `templates/admin/reports-table.peb` for `chat_message` rows.
- **DB**: no schema change. Reads raw `chat_messages` + `conversation_participants` (admin module is allowlisted from `RawFromPostsRule` / `BlockExclusionJoinRule` per [`docs/04`](../../../docs/04-Architecture.md) § Admin Panel Data Access). Writes `chat_messages` redaction columns, `notifications`, `admin_actions_log`.
- **Out of scope (explicit non-goals, tracked as follow-ups)**: mobile rendering of the redacted message + the `chat_message_redacted` notification (mobile lane — backend already returns `content: null`); CSAM-specific handling (separate frame-13 surface); un-redaction (redaction is terminal).
- **Mockup**: frame 9 caption path label corrected `PATCH` → `POST`; frame retagged from "Usulan" toward shipped on archive.
