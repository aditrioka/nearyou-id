## 1. Role gate + route wiring

- [ ] 1.1 Add `AdminRoleGate.requireOwnerOrAdmin(call)` — a sibling to `requireWriteRole` that returns 403 for a `moderator` / `read_only` session (configuration of the existing role-gate concern; no new pattern). Unit-test the allow/deny matrix.
- [ ] 1.2 Create `admin/routes/AdminChatRedactionRoute.kt` exposing `GET /admin/chat-messages/{id}` + `POST /admin/chat-messages/{id}/redact`, wired INSIDE `authenticate(ADMIN_AUTH_NAME)` in `AdminModule.kt` alongside the report-queue/user-moderation routes; the bare `/admin/chat-messages` collection exposes no write route.
- [ ] 1.3 GET handler: `requireOwnerOrAdmin` (403) → parse `{id}` UUID (malformed → 400) → load target + ±2 context window + active participants → 404 when no message → render `chat-redaction.peb` (HTML-escaped; an already-redacted target renders the redacted placeholder + an "already redacted" indicator).
- [ ] 1.4 POST handler mirroring `AdminReportResolutionRoute` gate order: CSRF first (403 + `admin_csrf_violation` on miss) → `requireOwnerOrAdmin` (403) → parse `{id}` UUID (400, no write) → required `redaction_reason` (blank/missing → 400, no write) → `DestructiveActionRateLimiter` → repository; map outcomes to the dual-mode response (HTMX fragment / no-JS 303 / in-band message).

## 2. Redaction repository (one transaction)

- [ ] 2.1 Create `admin/chatredaction/ChatRedactionRepository.kt`: read the target `chat_messages` row, the ±2 surrounding messages (by `created_at` within the conversation), and the active `conversation_participants` (raw reads — admin module allowlist; bounded dispatcher).
- [ ] 2.2 Idempotent atomic `UPDATE chat_messages SET redacted_at = NOW(), redacted_by = :adminId, redaction_reason = :reason WHERE id = :id AND redacted_at IS NULL`; return an outcome enum (`Applied` / `AlreadyRedacted` / `NotFound`) from rows-affected + existence.
- [ ] 2.3 In the SAME transaction (only on `Applied`): write a `chat_message_redacted` notification row per active participant via a **raw `INSERT INTO notifications (…)`** (the shipped admin pattern, mirroring `ReportResolutionRepository:407` — NOT `NotificationEmitter`): `actor_user_id=NULL`, `target_type='message'`, `target_id=<message id>`, `body_data = {"conversation_id": <uuid>}` (exactly one key; no content, no reason, no `message_id`).
- [ ] 2.4 In the SAME transaction (only on `Applied`): write exactly one `admin_actions_log` row (`action_type='admin_chat_redaction'`, `target_type='chat_message'` (the admin-action target vocabulary — distinct from the notification's `'message'`), `target_id`, `reason`, `before_state`=original row state (content, or the embed snapshot when content is NULL), `after_state`=redacted marker, `ip=call.clientIp`, `user_agent`).
- [ ] 2.5 In-app feed only — **no FCM push** for `chat_message_redacted` (matches the shipped admin `account_action_applied`; no dispatcher wired into the admin path). FCM-push-on-redaction is an explicit out-of-scope follow-up (file a `follow-up` issue).
- [ ] 2.6 One transaction per redaction op (open/commit/rollback in the helper, not scattered autocommit).

## 3. Templates (admin mockup frame 9)

- [ ] 3.1 Render frame 9 (`dev/mockups/nearyou-admin-mockup.html` #f09) + generate its measurement annex (`dev/scripts/mockup-measure.sh nearyou-admin-mockup.html 9`) BEFORE building the template.
- [ ] 3.2 `templates/admin/chat-redaction.peb` (page) + HTMX fragment: ±2 context window (reported message highlighted), required reason input, Redact danger button, owner/admin warning banner; vendored vanilla CSS tokens lifted from the board `.frame` block; no-JS `<form method=post>` fallback; no client framework / CDN / inline styles; fluid per the frame-4b responsive contract.
- [ ] 3.3 `templates/admin/reports-table.peb`: render the "Redact message" deep-link (`/admin/chat-messages/{target_id}`) on `chat_message` report rows (additive to the existing sender link; no link on post/reply/user rows).

## 4. Notification write wiring

- [ ] 4.1 Write the `chat_message_redacted` rows via a raw in-transaction `INSERT INTO notifications` helper in the redaction repository (mirroring `ReportResolutionRepository:407`); `body_data` JSON built in Kotlin + passed as `?::jsonb`. Do NOT introduce `NotificationEmitter` into the admin module. Document in code why the emitter's cross-capability shadow-ban rule does not bind here (not a `NotificationEmitter` call site; system row, no block-suppression).

## 5. Tests

- [ ] 5.1 GET route tests: owner 200 + exactly ±2 context messages; `moderator` AND `read_only` → 403 (no content); malformed id → 400; non-existent → 404; already-redacted → redacted placeholder rendered; embedded-post-only target (content NULL) renders via snapshot (no crash); context window clamps at conversation boundaries (target as first/last message → fewer than 2 neighbors, no error).
- [ ] 5.2 POST route tests: CSRF miss → 403 + `admin_csrf_violation` (no write); `moderator` → 403; `read_only` → 403 (no write, no audit); **CSRF-before-role precedence** — `moderator`/`read_only` + missing CSRF → 403 via the CSRF path WITH `admin_csrf_violation` written (not masked by a silent role-403); blank reason → 400 (no write).
- [ ] 5.3 Repo `@Tags("database")`: first redaction sets `redacted_at`/`redacted_by`/`redaction_reason`; re-redaction with a DIFFERENT reason is a no-op that preserves the original trio (incl. `redaction_reason`) + writes no new audit/notification; not-found outcome; embedded-post-only (content NULL) message is redactable + `before_state` captures the snapshot; concurrent double-redaction → exactly one `Applied` (one audit row, one notification set) + one `AlreadyRedacted`.
- [ ] 5.4 Repo `@Tags("database")`: both active participants get a `chat_message_redacted` row (`actor_user_id` NULL, `target_type='message'`, `target_id=<msg>`, `body_data` exactly `{"conversation_id": <uuid>}` — no content/reason/message_id); a `user_blocks` row between participants does NOT suppress; a `left_at IS NOT NULL` participant gets none; a no-op emits none.
- [ ] 5.5 Repo `@Tags("database")`: exactly one immutable `admin_actions_log` (`admin_chat_redaction`) row on `Applied`; none on reject/no-op.
- [ ] 5.6 Rate-limit test: the 21st destructive action in the hour is refused in-band with no write.
- [ ] 5.7 Dual-mode response test: HTMX success → fragment with redacted render; no-JS success → 303 `Location: /admin/chat-messages/{id}`.
- [ ] 5.8 New DB-tagged test HikariPool uses `autoClose(hikari())` + size 2 (CI connection budget).
- [ ] 5.9 Data-plane regression guard: a redacted message still serializes `content: null` + omits `redaction_reason` on the chat REST/realtime path (the existing `chat-conversations` contract is preserved by this change).

## 6. Verification + smoke (pre-archive — docs/11 §5 DoD)

- [ ] 6.1 `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` green locally.
- [ ] 6.2 `verify-loop` admin bring-up: load `GET /admin/chat-messages/{id}` and run a redaction round-trip (owner login + CSRF); attach screenshot evidence to the PR body.
- [ ] 6.3 (SHOULD) staging branch deploy (`gh workflow run deploy-staging.yml --ref admin-chat-message-redaction`) + admin smoke of the redaction route before archive.

## 7. Mockup + docs reconciliation

- [ ] 7.1 Frame 9 caption: correct the path label `PATCH /admin/chat-messages/{id}/redact` → `POST …` (no-JS `<form>` fallback discipline); retag the frame toward shipped at archive.
- [ ] 7.2 File a `follow-up` issue reconciling `docs/05-Implementation.md` § Direct Messaging redaction-UX wording ("recipient receives") with `docs/07` "affected conversation participants" (docs-stale, reconciliation bucket b — do NOT rewrite docs as part of this change).
- [ ] 7.3 At archive (spec-sync), reword the now-stale descriptive counts in `openspec/specs/in-app-notifications/spec.md` (§ "notifications table" + § "body_data shape per emitted type": "writes 5 of the 13 / 8 reserved" → "6 / 7", and add the `chat_message_redacted` shape to the catalog requirement) so the canonical spec doesn't self-contradict after this emit site activates.

## 8. OpenSpec lifecycle

- [ ] 8.1 `openspec validate admin-chat-message-redaction --strict` green.
- [ ] 8.2 `/opsx:apply` lands feat commits on this branch; retitle PR `feat(admin): chat-message redaction` + refresh body.
- [ ] 8.3 `/opsx:archive` after 6.2 (+ 6.3) pass; sync specs; refresh PR body to merge-ready.
