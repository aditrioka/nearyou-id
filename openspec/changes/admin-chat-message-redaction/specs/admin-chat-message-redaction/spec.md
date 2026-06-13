## ADDED Requirements

### Requirement: Owner/admin GET /admin/chat-messages/{id} renders the redaction confirmation page

The system SHALL serve `GET /admin/chat-messages/{id}` as an authenticated route wired INSIDE the `authenticate(ADMIN_AUTH_NAME)` block (`admin-login` session middleware gates it). Beyond the session gate it SHALL require the **owner/admin tier**: a valid admin session whose `AdminPrincipal.role` is `moderator` or `read_only` SHALL receive HTTP 403 and the page SHALL NOT render (the page discloses private 1:1 chat content, so the disclosure is limited to admins who can act). On an owner/admin session with a resolvable message it SHALL return HTTP 200 with an HTML page extending the shared admin base layout that renders: the target `chat_messages` row, up to **2 messages immediately before and 2 immediately after** it in the same conversation ordered by `created_at` (the limited-disclosure moderation-context window), a **required** redaction-reason input, and the Redact action. The admin module reads raw `chat_messages` / `conversation_participants` (exempt from the `visible_*`-view + block-exclusion lint per the Admin Panel Data Access policy). All rendered message content SHALL be HTML-escaped. A message that is already redacted SHALL render with its `content` shown as the redacted placeholder (never its original text) and an "already redacted" indicator.

#### Scenario: Owner session renders the page with context
- **GIVEN** an authenticated session with role `owner` AND a `chat_messages` row `<M>` in a conversation that also has 3 earlier and 3 later messages
- **WHEN** `GET /admin/chat-messages/<M>` is served
- **THEN** the response is HTTP 200 AND renders `<M>` plus exactly the 2 immediately-preceding and 2 immediately-following messages by `created_at` AND a required reason input AND a Redact action

#### Scenario: Moderator session is forbidden
- **GIVEN** an authenticated session with role `moderator`
- **WHEN** `GET /admin/chat-messages/<M>` is served
- **THEN** the response is HTTP 403 AND no chat content is rendered

#### Scenario: Malformed message id
- **WHEN** `GET /admin/chat-messages/not-a-uuid` is served on an owner/admin session
- **THEN** the response is HTTP 400 AND no chat content is rendered

#### Scenario: Non-existent message
- **GIVEN** an owner/admin session AND a syntactically valid UUID that matches no `chat_messages` row
- **WHEN** `GET /admin/chat-messages/<UUID>` is served
- **THEN** the response is HTTP 404

#### Scenario: Already-redacted target renders the redacted placeholder
- **GIVEN** an owner/admin session AND a `chat_messages` row `<M>` whose `redacted_at IS NOT NULL`
- **WHEN** `GET /admin/chat-messages/<M>` is served
- **THEN** the page renders `<M>` as the redacted placeholder (NOT its original `content`) AND indicates the message is already redacted

### Requirement: POST /admin/chat-messages/{id}/redact is CSRF- and owner/admin-gated

The system SHALL serve the state-changing redaction at `POST /admin/chat-messages/{id}/redact` (the verb is `POST`, not `PATCH` — the admin panel's no-JS `<form>` fallback supports only GET/POST). The handler SHALL apply, in order: (1) CSRF validation FIRST — a missing/mismatched token SHALL return HTTP 403 and write an `admin_csrf_violation` audit entry, with no redaction write; (2) the owner/admin tier gate — a `moderator` or `read_only` session SHALL return HTTP 403 with no write; (3) parse the `{id}` path segment as a UUID — a malformed id SHALL return HTTP 400 with no write; (4) read `redaction_reason` from the CSRF-consumed form body — a missing or blank reason SHALL return HTTP 400 with no write. The collection path `/admin/chat-messages` SHALL NOT expose a bare write route.

#### Scenario: Missing CSRF token is rejected and audited
- **GIVEN** an owner/admin session AND a redaction POST with no/invalid CSRF token
- **WHEN** `POST /admin/chat-messages/<M>/redact` is served
- **THEN** the response is HTTP 403 AND an `admin_csrf_violation` audit entry is written AND `<M>` is NOT redacted

#### Scenario: Moderator is forbidden from redacting
- **GIVEN** an authenticated session with role `moderator` AND a valid CSRF token
- **WHEN** `POST /admin/chat-messages/<M>/redact` is served
- **THEN** the response is HTTP 403 AND `<M>` is NOT redacted AND no `admin_actions_log` row is written

#### Scenario: Blank reason is rejected
- **GIVEN** an owner/admin session AND a valid CSRF token AND a body with empty `redaction_reason`
- **WHEN** `POST /admin/chat-messages/<M>/redact` is served
- **THEN** the response is HTTP 400 AND `<M>` is NOT redacted

### Requirement: Redaction performs an idempotent atomic flag write

On a valid owner/admin request, the system SHALL execute, in one DB transaction, `UPDATE chat_messages SET redacted_at = NOW(), redacted_by = :actingAdminId, redaction_reason = :reason WHERE id = :id AND redacted_at IS NULL`. Setting `redacted_at` and `redacted_by` together satisfies the V15 redaction-atomicity CHECK. When exactly one row is updated, the redaction is applied. When zero rows are updated, the system SHALL distinguish: if the row exists and is already redacted, the outcome is a no-op ("already redacted, no change made") that writes NO audit row and emits NO notification; if no row exists, the outcome is not-found. Re-redacting an already-redacted message SHALL be a safe no-op (it SHALL NOT overwrite the original `redacted_at`/`redacted_by`/`redaction_reason`).

#### Scenario: First redaction applies the flags
- **GIVEN** an owner `<A>` AND a non-redacted message `<M>` AND reason "doxxing — home address"
- **WHEN** the redaction POST is applied
- **THEN** `<M>.redacted_at` is set, `<M>.redacted_by = <A>`, `<M>.redaction_reason = "doxxing — home address"`

#### Scenario: Re-redaction is a no-op
- **GIVEN** a message `<M>` already redacted by owner `<A1>` at time `<T1>`
- **WHEN** owner `<A2>` POSTs a redaction for `<M>`
- **THEN** zero rows are updated AND `<M>.redacted_by` remains `<A1>` AND `<M>.redacted_at` remains `<T1>` AND no new `admin_actions_log` row and no new notification are written

#### Scenario: Redaction reason is never exposed on the chat data plane
- **GIVEN** a redacted message `<M>` with `redaction_reason` set
- **WHEN** the chat REST/realtime data plane serializes `<M>`
- **THEN** `content` is `null` AND `redaction_reason` is absent from the payload (the existing `chat-conversations` render contract is unaffected by this change)

### Requirement: Redaction notifies active conversation participants

In the SAME transaction as the flag write, an applied redaction SHALL emit a `chat_message_redacted` notification to **every active participant** of the message's conversation (`conversation_participants WHERE conversation_id = <M.conversation_id> AND left_at IS NULL`), via the `in-app-notifications` `NotificationEmitter` with `actor_user_id = NULL` (system-originated). Because the actor is NULL, the emitter's bidirectional block-check is skipped and the rows are written unconditionally — a block between the two participants SHALL NOT suppress the redaction notice. The shadow-ban actor-masking cross-capability rule is not applicable (there is no user actor). FCM fan-out for the emitted notifications SHALL occur post-commit. The notification `body_data` shape is defined by the `in-app-notifications` capability and SHALL carry no content and no reason.

#### Scenario: Both active participants are notified
- **GIVEN** a conversation with active participants `<U1>` (sender) and `<U2>` AND a redaction applied to `<U1>`'s message `<M>`
- **WHEN** the redaction transaction commits
- **THEN** exactly one `chat_message_redacted` notification row exists for `<U1>` AND one for `<U2>`, each with `actor_user_id = NULL`, `target_type = 'chat_message'`, `target_id = <M>`

#### Scenario: A block between participants does not suppress the notice
- **GIVEN** active participants `<U1>` and `<U2>` with a `user_blocks` row between them AND a redaction applied to `<M>`
- **WHEN** the redaction transaction commits
- **THEN** both `<U1>` and `<U2>` still receive a `chat_message_redacted` notification (system-originated emit skips block-suppression)

#### Scenario: A no-op redaction emits no notification
- **GIVEN** an already-redacted message `<M>`
- **WHEN** a second redaction POST is served
- **THEN** no new `chat_message_redacted` notification rows are written

### Requirement: Redaction writes one immutable audit row

An applied redaction SHALL write exactly one `admin_actions_log` row in the same transaction: `action_type = 'admin_chat_redaction'`, `target_type = 'chat_message'`, `target_id = <message id>`, `reason = <redaction reason>`, `before_state` capturing the original content (the moderation record; `admin_actions_log` is admin-only and immutable at the `admin_app` role level), `after_state` recording the redacted result, `ip` set from the request-context `clientIp` value (never a raw `X-Forwarded-For` read), and `user_agent`. A no-op / not-found / rejected (403/400) outcome SHALL write NO `admin_chat_redaction` row.

#### Scenario: Applied redaction writes one audit row
- **WHEN** owner `<A>` redacts message `<M>` with reason `<R>`
- **THEN** exactly one `admin_actions_log` row is written with `action_type = 'admin_chat_redaction'`, `target_id = <M>`, `reason = <R>`, and a non-null `before_state`

#### Scenario: Rejected redaction writes no redaction audit row
- **WHEN** a redaction POST is rejected for a blank reason (400)
- **THEN** no `admin_chat_redaction` row is written

### Requirement: Redaction is destructive-action rate-limited

The redaction POST SHALL pass through the shared `DestructiveActionRateLimiter` (20 actions/hour per admin, the destructive-action budget shared with the other admin destructive actions). When the limit is exceeded the system SHALL return an in-band "quota exceeded" outcome (the message stays unredacted, nothing is written), consistent with the report-resolution rate-limit handling.

#### Scenario: Over-quota redaction is refused without a write
- **GIVEN** an owner who has already taken 20 destructive actions in the current hour
- **WHEN** a 21st redaction POST is served
- **THEN** the response renders an in-band rate-limit message AND `<M>` is NOT redacted AND no `admin_actions_log` row is written

### Requirement: Dual-mode HTMX / no-JS response

A successful redaction SHALL re-render the redaction page fragment (with the message now shown as the redacted placeholder) for an `HX-Request`, or 303-redirect back to `GET /admin/chat-messages/{id}` for the no-JS path. No-op / not-found / rate-limit outcomes SHALL re-render with an in-band informational message (fragment for HTMX, full page for no-JS). This mirrors the shipped report-resolution dual-mode response.

#### Scenario: HTMX success swaps the redacted fragment
- **GIVEN** an `HX-Request` redaction POST that applies
- **THEN** the response is HTTP 200 with the page fragment showing `<M>` as redacted

#### Scenario: No-JS success redirects
- **GIVEN** a non-HX redaction POST that applies
- **THEN** the response is HTTP 303 with `Location: /admin/chat-messages/<M>`
