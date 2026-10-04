## ADDED Requirements

### Requirement: Appeal decisions insert an appeal_decided notification atomically

Each applied approve or reject SHALL insert exactly one `notifications` row for the appellant (`user_id` = the appeal's `user_id`) with `type = 'appeal_decided'`, `actor_user_id = NULL`, `target_type = 'appeal'`, `target_id` = the decided appeal's id, and `body_data = {"decision": "approved"}` (approve) or `{"decision": "rejected"}` (reject). The insert SHALL run on the decision transaction's own connection — the shipped admin-notification pattern (a raw in-transaction `INSERT INTO notifications (…)`, mirroring `UserModerationRepository`'s `account_action_applied`), NOT the social `NotificationEmitter` path — so the appeal transition, the unban (approve), the `admin_actions_log` row, and the notification commit or roll back together. A decision that is a no-op (already decided) or targets a non-existent appeal SHALL insert no notification. The `body_data` SHALL NOT carry the admin's free-text `decision_reason`, the `action_type`, any admin identity, or the appeal id (which is the row's `target_id`).

#### Scenario: Approve inserts one approved appeal_decided notification
- **GIVEN** appellant A is suspended with a `pending` appeal P
- **WHEN** an admin approves P
- **THEN** exactly one `notifications` row exists for A with `type = 'appeal_decided'`, `actor_user_id IS NULL`, `target_type = 'appeal'`, `target_id = P`, and `body_data = {"decision": "approved"}`

#### Scenario: Reject inserts one rejected appeal_decided notification without the reason
- **GIVEN** appellant A is suspended with a `pending` appeal P
- **WHEN** an admin rejects P with `decision_reason = "Melanggar pedoman komunitas"`
- **THEN** exactly one `notifications` row exists for A with `type = 'appeal_decided'`, `target_type = 'appeal'`, `target_id = P`, and `body_data` exactly `{"decision": "rejected"}` (the reason text is absent)

#### Scenario: Idempotent re-decision inserts no additional notification
- **GIVEN** appeal P has already been approved (its `appeal_decided` row already written)
- **WHEN** an admin issues approve (or reject) on P again
- **THEN** no additional `notifications` row is inserted for the appellant

#### Scenario: A failed notification insert rolls back the whole decision
- **GIVEN** appellant A is suspended with a `pending` appeal P AND the `notifications` insert for A is forced to fail
- **WHEN** an admin approves P
- **THEN** the decision fails AND P is still `pending` AND A is still banned AND no `appeal_approved` audit row was written
