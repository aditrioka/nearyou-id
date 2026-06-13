## ADDED Requirements

### Requirement: chat_message report rows deep-link to the redaction surface

For a report row whose `target_type = 'chat_message'`, the system SHALL render — in addition to the existing offending-user (message sender) deep-link to `/admin/users?q=<sender>` — a "Redact message" deep-link to `GET /admin/chat-messages/<target_id>` (the `admin-chat-message-redaction` surface). This is additive: it does not change the offending-user deep-link behavior already specified for `chat_message` rows. The redaction deep-link SHALL render for `chat_message` rows regardless of the viewing admin's role (the link is just navigation; the owner/admin tier gate is enforced by the redaction surface itself on GET). Rows whose `target_type ∈ {post, reply, user}` SHALL NOT render a redaction deep-link.

#### Scenario: A chat_message report row links to the redaction surface
- **GIVEN** an authenticated admin session AND a report with `target_type = chat_message` and `target_id = <M>`
- **WHEN** `GET /admin/reports` is served
- **THEN** the report's row contains a link to `/admin/chat-messages/<M>` ("Redact message") in addition to the existing message-sender link

#### Scenario: Non-chat targets render no redaction link
- **GIVEN** an authenticated admin session AND a report with `target_type = post`
- **WHEN** `GET /admin/reports` is served
- **THEN** the report's row contains NO `/admin/chat-messages/...` link
