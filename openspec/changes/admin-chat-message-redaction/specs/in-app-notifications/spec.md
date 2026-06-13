## ADDED Requirements

### Requirement: chat_message_redacted emit site and body_data shape

The `chat_message_redacted` notification type (already present in the V10 `type` CHECK catalog as a reserved emit site) SHALL be written by the `admin-chat-message-redaction` capability when an admin applies a chat-message redaction. Each emit SHALL be **system-originated** (`actor_user_id = NULL`) — so the `NotificationEmitter` bidirectional block-check is skipped (per the existing emitter's null-actor path) and the row is written unconditionally; the cross-capability shadow-ban actor-masking rule is **not applicable** (there is no user actor). One notification SHALL be written per **active** conversation participant (`conversation_participants WHERE conversation_id = … AND left_at IS NULL`). Each row SHALL have `type = 'chat_message_redacted'`, `actor_user_id = NULL`, `target_type = 'chat_message'`, `target_id = <redacted message id>`, and `body_data` of exactly the shape:

- `chat_message_redacted`: `{"conversation_id": <UUID string>, "message_id": <UUID string>}`

`body_data` SHALL NOT carry the redacted message `content`, the `redaction_reason`, or any actor/admin identity (data-plane PII discipline — the purpose of redaction is to remove the content from user-visible surfaces). This requirement adds the `chat_message_redacted` shape alongside the five shapes already defined in § "body_data shape per emitted type"; it does not change any existing shape.

#### Scenario: chat_message_redacted body_data shape
- **WHEN** a `chat_message_redacted` notification is emitted for a redaction of message `<M>` in conversation `<C>`
- **THEN** the `body_data` JSONB has exactly two keys `conversation_id` (= `<C>`) AND `message_id` (= `<M>`) AND carries neither `content` nor `redaction_reason` nor any actor identity

#### Scenario: System-originated emit skips block-suppression for redaction
- **GIVEN** two active participants with a `user_blocks` row between them
- **WHEN** a `chat_message_redacted` notification is emitted (`actor_user_id = NULL`)
- **THEN** the emitter does NOT issue the `user_blocks` block-check AND a notification row is written for each active participant

#### Scenario: One row per active participant
- **GIVEN** a conversation with two active participants AND one whose `conversation_participants.left_at IS NOT NULL`
- **WHEN** a redaction of a message in that conversation is applied
- **THEN** `chat_message_redacted` rows are written only for the two active participants (none for the left participant)
