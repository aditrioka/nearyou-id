## MODIFIED Requirements

### Requirement: The action-history view merges admin_actions_log and username_history, newest-first

The page SHALL render a history view combining (a) the target user's admin-action rows and (b) the user's `username_history` rows (`old_username`→`new_username`, `changed_at`). Both sources SHALL be ordered newest-first. The history view SHALL be read-only.

Source (a) SHALL be every `admin_actions_log` row WHERE `(target_type = 'user' AND target_id = {id}) OR (after_state ->> 'user_id') = {id}`. It SHALL surface the same columns the audit-log viewer shows: time, the acting admin resolved to a human-readable identity, action type, reason, and a `before_state`→`after_state` disclosure.

The second arm is the `after_state.user_id` convention: an audit row keyed to another object (a `moderation_queue` row, an `appeal`, a `deletion_request`) names the account it affected in `after_state.user_id`. That arm SHALL surface:
- report-queue author enforcement (`moderation_queue_resolved` with an author resolution);
- appeal decisions (`appeal_approved`, `appeal_rejected`);
- hard-delete expedites (`deletion_request_expedited`).

A row matching both arms SHALL appear once. Rows whose `after_state` names a different user, or names no user, SHALL NOT appear. That includes content-only queue resolutions (`keep` / `hide`), which carry no `user_id`.

The `after_state` arm SHALL be served by an index: the V41 partial expression index `admin_actions_after_user_idx` on `(after_state ->> 'user_id')`. It SHALL NOT require a sequential scan of `admin_actions_log`.

#### Scenario: Prior admin actions against the user appear in the history

- **GIVEN** an authenticated session AND a user who was previously suspended (one `admin_actions_log` row with `action_type = 'user_suspended'`, `target_type = 'user'`, `target_id = <user>`)
- **WHEN** the client sends `GET /admin/users/<that-uuid>`
- **THEN** the history view SHALL include a row for that suspend action showing its action type, the acting admin, and a state disclosure

#### Scenario: Username changes appear in the history

- **GIVEN** an authenticated session AND a user with one `username_history` row (`old_username = 'budi'`, `new_username = 'budi_jakarta'`)
- **WHEN** the profile page is served
- **THEN** the history view SHALL include that username change (`budi` → `budi_jakarta`) with its `changed_at`

#### Scenario: A user with no history renders an empty history view, not an error

- **GIVEN** an authenticated session AND a freshly-created user with no `admin_actions_log` and no `username_history` rows
- **WHEN** the profile page is served
- **THEN** the response status SHALL be 200 AND the history view SHALL render an empty-state indicator (no rows) rather than an error

#### Scenario: A ban applied through the report queue appears in the author's history

- **GIVEN** an authenticated session AND user `A` who authored a post AND a `pending` `moderation_queue` row for that post
- **WHEN** an owner/admin resolves the queue row with `resolution = ban_author` AND the client then sends `GET /admin/users/<A>`
- **THEN** the history view SHALL include a `moderation_queue_resolved` row whose state disclosure shows `resolution` `ban_author` (the row is keyed `target_type = 'moderation_queue'` but its `after_state.user_id` is `A`)

#### Scenario: An appeal approval (the unban) appears in the appellant's history

- **GIVEN** an authenticated session AND a banned user `A` with a `pending` appeal
- **WHEN** an owner/admin approves the appeal AND the client then sends `GET /admin/users/<A>`
- **THEN** the history view SHALL include an `appeal_approved` row

#### Scenario: An appeal rejection appears in the appellant's history

- **GIVEN** an authenticated session AND a banned user `A` with a `pending` appeal
- **WHEN** an owner/admin rejects the appeal AND the client then sends `GET /admin/users/<A>`
- **THEN** the history view SHALL include an `appeal_rejected` row

#### Scenario: Any audit row naming the user in after_state.user_id appears

- **GIVEN** an authenticated session AND user `A` AND one `admin_actions_log` row with `action_type = 'deletion_request_expedited'`, `target_type = 'deletion_request'`, `target_id = <some request id>`, and `after_state.user_id = <A>`
- **WHEN** the client sends `GET /admin/users/<A>`
- **THEN** the history view SHALL include that `deletion_request_expedited` row exactly once

#### Scenario: A content-only queue resolution does not appear in the author's history

- **GIVEN** an authenticated session AND user `A` who authored a post AND a `pending` `moderation_queue` row for that post
- **WHEN** the queue row is resolved with `resolution = hide` AND the client then sends `GET /admin/users/<A>`
- **THEN** the history view SHALL NOT include a `moderation_queue_resolved` row (a content resolution acts on the post, not the account, and carries no `after_state.user_id`)

#### Scenario: Another user's enforcement does not leak into the history

- **GIVEN** an authenticated session AND users `A` and `B` AND a report-queue `ban_author` resolution whose resolved author is `B` AND an approved appeal of `B`
- **WHEN** the client sends `GET /admin/users/<A>`
- **THEN** the history view SHALL include neither the `moderation_queue_resolved` row nor the `appeal_approved` row (both name `B`)

#### Scenario: The widened history read is index-served

- **GIVEN** the V41 index `admin_actions_after_user_idx` on `admin_actions_log ((after_state ->> 'user_id')) WHERE (after_state ->> 'user_id') IS NOT NULL`
- **WHEN** the history query is planned with sequential scans disabled (`SET enable_seqscan = off`)
- **THEN** the plan SHALL NOT contain a sequential scan of `admin_actions_log`
