## MODIFIED Requirements

### Requirement: A per-admin destructive-action cap of 20 per trailing hour

The system SHALL enforce a cap of **20 destructive admin actions per acting admin per trailing one-hour window**. The **destructive set** is the user-punitive / content-removal account-state actions: a **warning** (`admin_actions_log.action_type = 'user_warned'`), a **suspend** (`'user_suspended'`), a **chat-message redaction** (`'admin_chat_redaction'`), a **permanent ban** via report-queue resolution, and a **shadow ban** via report-queue resolution. Restorative and non-punitive actions are NOT in the destructive set and SHALL NOT be counted or capped: **unban**, content **keep**/**hide**, report **decision** bookkeeping (`actioned`/`dismissed`), and any read/login action. The count SHALL be sourced from `admin_actions_log` (the immutable audit trail is the rate-limit ledger): rows for the acting `admin_id` with `created_at > NOW() - INTERVAL '1 hour'` whose action identity is in the destructive set, where the report-queue destructive resolutions (which all log `action_type = 'moderation_queue_resolved'`) are isolated by `after_state ->> 'resolution' IN ('suspend_author_7d', 'ban_author', 'shadow_ban_author')`. A chat-message redaction logs the direct `action_type = 'admin_chat_redaction'` (disjoint from `moderation_queue_resolved`), so it is counted by the direct-`action_type` arm and is never double-counted.

#### Scenario: The count includes the in-window destructive actions for the acting admin

- **GIVEN** an admin who has 3 `user_suspended`, 2 `user_warned`, and 1 `moderation_queue_resolved` (`resolution = ban_author`) rows in `admin_actions_log` within the last hour
- **WHEN** that admin's destructive-action count is computed
- **THEN** the count SHALL be 6

#### Scenario: A chat-message redaction counts toward the cap

- **GIVEN** an admin who has 19 destructive-action rows in the trailing hour AND applies a chat-message redaction (logging one `admin_chat_redaction` row)
- **WHEN** that admin's destructive-action count is computed afterward
- **THEN** the count SHALL be 20 (the redaction advanced the shared destructive budget) AND a subsequent redaction attempt SHALL be rejected at the cap

#### Scenario: Non-destructive and out-of-window actions are excluded

- **GIVEN** an admin with, in the last hour, 1 `user_unbanned` row, 1 `moderation_queue_resolved` (`resolution = hide`) row, 1 `report_resolved` row, AND 5 `user_suspended` rows older than one hour
- **WHEN** that admin's destructive-action count is computed
- **THEN** the count SHALL be 0 (unban, content-hide, and report bookkeeping are not destructive; the 5 suspends fall outside the trailing-hour window)
