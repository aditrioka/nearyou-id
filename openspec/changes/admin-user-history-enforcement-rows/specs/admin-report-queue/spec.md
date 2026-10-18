## MODIFIED Requirements

### Requirement: Authenticated moderation-queue resolution endpoint performs the named enforcement

The system SHALL serve `POST /admin/moderation-queue/{id}/resolve` as an authenticated route wired INSIDE the `authenticate(ADMIN_AUTH_NAME)` block. On a valid session with a valid CSRF token and a sufficiently-roled admin (per the role-gating requirement), it SHALL accept a `resolution` form field constrained to the enforcement subset `{keep, hide, suspend_author_7d, ban_author, shadow_ban_author}`.

In ONE database transaction, it SHALL:
- set `moderation_queue.status = 'resolved'` + `resolution` + `resolved_by` + `resolved_at`;
- **perform the enforcement that resolution names** (per the content-resolution and author-resolution requirements);
- write exactly one `admin_actions_log` row with `action_type = 'moderation_queue_resolved'` (`target_type = 'moderation_queue'`, `target_id` = the queue id).

That row's `after_state` SHALL record both the `resolution` value and the enforcement effect. For an **author resolution** (`suspend_author_7d`, `ban_author`, `shadow_ban_author`), the `after_state` SHALL also record `user_id`: the resolved author's id as a canonical lowercase UUID string. This is the `after_state.user_id` convention, through which the enforcement appears in that user's `admin-user-management` profile history. A **content resolution** (`keep`, `hide`) acts on a post or reply, not an account, so its `after_state` SHALL NOT carry a `user_id` key.

The transition SHALL apply only to a queue row currently in `status = 'pending'`.

The values `delete`, `accept_flagged_username`, and `reject_flagged_username` are OUT OF SCOPE and SHALL be rejected (see the malformed/out-of-scope requirement):
- `delete` is not in the in-row action set (`docs/07-Operations.md` § Core Features lists Hide/Dismiss/Suspend/Ban/Shadow-ban).
- The username resolutions are owned by the future Premium Username Change Oversight feature.

#### Scenario: A pending queue item is resolved and the resolution is recorded
- **GIVEN** an authenticated write-role admin session with a valid CSRF token AND a `moderation_queue` row with `status = 'pending'` for a `post` target
- **WHEN** the client sends `POST /admin/moderation-queue/{id}/resolve` with `resolution=hide`
- **THEN** that queue row's `status` SHALL become `resolved` AND `resolution` SHALL be `hide` AND `resolved_by` + `resolved_at` SHALL be set
- **AND** exactly one `admin_actions_log` row with `action_type = 'moderation_queue_resolved'` SHALL be written whose `after_state` records `resolution = hide` AND carries no `user_id` key

#### Scenario: An author resolution records the resolved author in after_state.user_id
- **GIVEN** a `pending` `moderation_queue` row whose resolved author is user `A`
- **WHEN** the queue row is resolved with an author resolution (`suspend_author_7d`, `ban_author`, or `shadow_ban_author`)
- **THEN** the single `moderation_queue_resolved` audit row's `after_state` SHALL record that `resolution` AND `user_id = <A>` (lowercase UUID string) alongside the enforcement effect

#### Scenario: A user-target author resolution records that user itself
- **GIVEN** a `pending` `moderation_queue` row with `target_type = 'user'` and `target_id = <U>`
- **WHEN** the queue row is resolved with `resolution=shadow_ban_author`
- **THEN** the `moderation_queue_resolved` audit row's `after_state.user_id` SHALL be `<U>`
