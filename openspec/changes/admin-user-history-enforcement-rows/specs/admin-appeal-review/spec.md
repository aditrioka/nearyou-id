## MODIFIED Requirements

### Requirement: Appeal decisions write immutable audit rows

Each approve or reject SHALL write exactly one `admin_actions_log` row within the same transaction as the decision. The row SHALL have:
- a new action type: `appeal_approved` or `appeal_rejected`;
- the acting admin (`admin_id`);
- the appeal id (`target_type = 'appeal'`, `target_id` = the appeal id);
- the target user: the appellant's id as `after_state.user_id`, a canonical lowercase UUID string.

`after_state.user_id` SHALL be recorded for BOTH approve and reject. It is the `after_state.user_id` convention through which the decision appears in the appellant's `admin-user-management` profile history. The `admin_actions_log` immutability invariant (UPDATE/DELETE revoked at the `admin_app` role) applies unchanged.

#### Scenario: Approve writes one appeal_approved audit row
- **WHEN** an admin approves an appeal
- **THEN** exactly one `admin_actions_log` row with action type `appeal_approved` is written, referencing the acting admin, the appeal (`target_type = 'appeal'`, `target_id` = the appeal id), and the appellant (`after_state.user_id` = the appellant's id)

#### Scenario: Reject writes one appeal_rejected audit row
- **WHEN** an admin rejects an appeal
- **THEN** exactly one `admin_actions_log` row with action type `appeal_rejected` is written, referencing the acting admin, the appeal (`target_type = 'appeal'`, `target_id` = the appeal id), and the appellant (`after_state.user_id` = the appellant's id)

#### Scenario: Idempotent re-approve writes no additional audit row
- **GIVEN** an appeal already has `status = 'approved'` (its `appeal_approved` audit row already written)
- **WHEN** an admin issues the approve action again (the guarded update affects no rows)
- **THEN** no additional `admin_actions_log` row is written (the audit write is inside the same guarded transaction, so a no-op decision logs nothing)
