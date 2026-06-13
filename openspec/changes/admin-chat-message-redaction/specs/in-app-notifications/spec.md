## ADDED Requirements

### Requirement: chat_message_redacted emit site and body_data shape

The `chat_message_redacted` notification type (already present in the V10 `type` CHECK catalog as a reserved emit site) SHALL be written by the `admin-chat-message-redaction` capability when an admin applies a chat-message redaction. Per the canonical V10 event-type catalog ([`docs/05-Implementation.md`](../../../../docs/05-Implementation.md) § Notifications Schema), each row SHALL have:

- `type = 'chat_message_redacted'`
- `actor_user_id = NULL` (system-originated; no user actor)
- `target_type = 'message'`, `target_id = <redacted message id>` (the canonical `(target_type, target_id)` addressing pair — matching the shipped `chat_message` notification's `target_type = 'message'` convention)
- `body_data = {"conversation_id": <UUID string>}` — **exactly one key**

`body_data` SHALL NOT carry the message `content`, the `redaction_reason`, any actor/admin identity, or `message_id` (the redacted message is the row's `target_id`; per the catalog rule "Do NOT duplicate `target_id` inside `body_data`", `conversation_id` is the only thing the outer pair cannot supply — it lets the client route without a second fetch). One row SHALL be written per **active** conversation participant (`conversation_participants WHERE conversation_id = … AND left_at IS NULL`).

The write SHALL use the **shipped admin notification pattern** — a raw in-transaction `INSERT INTO notifications (…)` mirroring `ReportResolutionRepository` / `UserModerationRepository` (which write `account_action_applied` the same way) — NOT the `NotificationEmitter` service path. Because it is a direct system INSERT (not a `NotificationEmitter` call site), no block-suppression or shadow-ban actor-masking applies, and the cross-capability shadow-ban rule (§ "NotificationEmitter write-path …") does not bind — both active participants are notified regardless of any block between them. Delivery is **in-app feed only** (no FCM push), consistent with the shipped admin `account_action_applied` notification (FCM push for admin-originated notifications is out of scope here).

This adds the `chat_message_redacted` shape to the catalog (which until now defined shapes only for the five written types). With this change the catalog's descriptive "writes 5 of the 13 / 8 reserved for future emit sites" counts become 6 written / 7 reserved (the existing § "notifications table" + § "body_data shape per emitted type" wording is reworded at archive time — tracked in tasks).

#### Scenario: chat_message_redacted body_data shape
- **WHEN** a `chat_message_redacted` notification is written for a redaction of message `<M>` in conversation `<C>`
- **THEN** the row has `type = 'chat_message_redacted'`, `actor_user_id = NULL`, `target_type = 'message'`, `target_id = <M>` AND `body_data` is exactly `{"conversation_id": "<C>"}` (one key) — carrying no `content`, no `redaction_reason`, no `message_id`, no actor identity

#### Scenario: System row reaches both participants despite a block
- **GIVEN** two active participants with a `user_blocks` row between them
- **WHEN** a redaction of a message in their conversation is applied
- **THEN** a `chat_message_redacted` row is written for EACH active participant (the direct system INSERT applies no block-suppression)

#### Scenario: One row per active participant (left participant excluded)
- **GIVEN** a conversation with two active participants AND one whose `conversation_participants.left_at IS NOT NULL`
- **WHEN** a redaction of a message in that conversation is applied
- **THEN** `chat_message_redacted` rows are written only for the two active participants (none for the left participant)

#### Scenario: Admin-originated redaction notification is not FCM-pushed
- **WHEN** a `chat_message_redacted` notification is written
- **THEN** it appears in the recipients' in-app `GET /api/v1/notifications` feed AND no FCM push is dispatched for it (matching the shipped admin `account_action_applied` behavior)
