## RENAMED Requirements

- FROM: `### Requirement: Decision outcome surfaced via status read, proactive notification deferred`
- TO: `### Requirement: Decision outcome surfaced via status read and an in-app notification`

## MODIFIED Requirements

### Requirement: Decision outcome surfaced via status read and an in-app notification

The appeal decision SHALL be surfaced to the user through the own-appeal-status read AND proactively through one in-app `notifications` row of type `appeal_decided`, written by the admin approve / reject action inside the decision transaction (see `admin-appeal-review` § "Appeal decisions insert an appeal_decided notification atomically" for the write and `in-app-notifications` § "appeal_decided emit site and body_data shape" for the row shape). Delivery is **in-app feed only**: deciding an appeal MUST NOT enqueue or dispatch an FCM push (operator decision 2026-10-04 — no `PushCopy` case and no `NotificationDispatcher` call for `appeal_decided`). The status read remains the surface for the free-text `decision_reason` and for an appellant who is still banned (and therefore 403'd from the notifications feed) when the decision lands.

#### Scenario: Decision visible on the next status read
- **GIVEN** an admin transitions caller A's appeal to `approved`
- **WHEN** A next reads their appeal status
- **THEN** the response reports `status = 'approved'`

#### Scenario: Approving an appeal surfaces an in-app notification in the appellant's feed
- **WHEN** an admin approves caller A's pending appeal (lifting the ban)
- **THEN** exactly one `notifications` row with `user_id = A` and `type = 'appeal_decided'` is inserted AND it is returned by A's next `GET /api/v1/notifications`

#### Scenario: Rejecting an appeal still records the in-app notification
- **WHEN** an admin rejects caller A's pending appeal (A stays banned, so A's feed read is 403 until access returns)
- **THEN** exactly one `notifications` row with `user_id = A` and `type = 'appeal_decided'` is inserted (it becomes visible in A's feed once A can authenticate again)

#### Scenario: Deciding an appeal dispatches no FCM push
- **WHEN** an admin approves or rejects an appeal
- **THEN** no FCM push is dispatched for the `appeal_decided` row (the decision path never calls the `NotificationDispatcher`; the outcome is in-app only)
