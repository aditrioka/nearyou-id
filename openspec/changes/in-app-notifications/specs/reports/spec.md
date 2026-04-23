## ADDED Requirements

### Requirement: Auto-hide transaction emits a post_auto_hidden notification for the target author

V9 established the 3-unique-aged-reporter auto-hide transaction: after the 3rd (or Nth ≥ 3) report arrives, the transaction (a) flips `is_auto_hidden = TRUE` on the target (`posts` when `target_type = 'post'`, `post_replies` when `target_type = 'reply'`), (b) inserts a `moderation_queue` row with `trigger = 'auto_hide_3_reports'` ON CONFLICT DO NOTHING. V9 left a `// TODO: notifications-api-v??` stub at the flip site. V10 SHALL replace the stub with an invocation of `NotificationEmitter.emit(type = 'post_auto_hidden', recipient = <target author>, actor = NULL, target_type = <'post' | 'reply'>, target_id = <target uuid>, body_data = {"reason": "auto_hide_3_reports"})` in the SAME DB transaction as the flip.

The emit SHALL fire ONLY when the flip actually transitions the flag (first-time flip). Idempotent re-flips (subsequent reporters crossing the threshold again on an already-hidden target) MUST NOT emit duplicate notifications. This matches the V9 pattern where the UPDATE on an already-hidden target is a no-op and the `moderation_queue` INSERT uses `ON CONFLICT DO NOTHING`.

`actor_user_id = NULL` because the auto-hide is system-originated (not attributed to any single reporter). Per the `NotificationEmitter` rules, system-originated emits skip the block-check (there's no actor to block).

Auto-hide on `target_type = 'user'` or `'chat_message'` MUST NOT emit a notification. Neither target type has an `is_auto_hidden` column (V9 made this explicit); the `moderation_queue` row is the sole admin-triage signal for those targets, and the `post_auto_hidden` notification type is specifically about CONTENT (post / reply) being hidden from the author's own viewers.

If the notification INSERT fails (e.g. target author hard-deleted between auto-hide and emit — rare because V4 / V8 RESTRICT FKs prevent user hard-delete while content exists, but theoretically possible mid-tombstone-worker-run), the encompassing transaction rolls back; the `is_auto_hidden` flip AND the `moderation_queue` INSERT AND the triggering 3rd-reporter row all roll back. The reporter receives an error; their next retry re-runs the whole chain.

The V9 `POST /api/v1/reports` response shape (204 no body) MUST NOT change.

#### Scenario: 3rd aged reporter on a post triggers post_auto_hidden notification for author
- **WHEN** 3 distinct reporters aged >7 days have reported Alice's post (target_type = 'post') AND the 3rd POST /reports lands the auto-hide flip
- **THEN** in the SAME transaction, exactly one `notifications` row is inserted with `user_id = Alice.id, type = 'post_auto_hidden', actor_user_id IS NULL, target_type = 'post', target_id = alicePostId, body_data.reason = 'auto_hide_3_reports'`

#### Scenario: 3rd aged reporter on a reply triggers post_auto_hidden notification for reply author
- **WHEN** 3 distinct reporters aged >7 days have reported Alice's reply (target_type = 'reply') AND the 3rd POST /reports lands the auto-hide flip on `post_replies`
- **THEN** in the SAME transaction, exactly one `notifications` row is inserted with `user_id = Alice.id, type = 'post_auto_hidden', actor_user_id IS NULL, target_type = 'reply', target_id = aliceReplyId, body_data.reason = 'auto_hide_3_reports'`

#### Scenario: 4th reporter on already-hidden post does NOT emit duplicate notification
- **WHEN** Alice's post is already `is_auto_hidden = TRUE` from a prior 3rd reporter (one notification already exists) AND a 4th reporter POSTs `/reports` crossing the threshold again
- **THEN** the `UPDATE` is a no-op (per V9) AND the `moderation_queue` INSERT ON CONFLICT DO NOTHING is a no-op (per V9) AND zero NEW `notifications` rows are inserted

#### Scenario: Auto-hide on target_type = 'user' does NOT emit a notification
- **WHEN** 3 aged reporters have reported Alice as a user (target_type = 'user') AND the auto-hide transaction runs
- **THEN** the `moderation_queue` row is written (per V9) AND zero `notifications` rows are inserted (no is_auto_hidden column on users; no post_auto_hidden notification applies to user targets)

#### Scenario: Auto-hide on target_type = 'chat_message' does NOT emit a notification
- **WHEN** 3 aged reporters have reported a chat_message (target_type = 'chat_message') AND the auto-hide transaction runs
- **THEN** the `moderation_queue` row is written (per V9) AND zero `notifications` rows are inserted

#### Scenario: actor_user_id is NULL (system-originated)
- **WHEN** the auto-hide emit fires
- **THEN** the emitted notification has `actor_user_id IS NULL` AND the emitter does NOT issue a `user_blocks` block-check query (system-originated emits skip block-check)

#### Scenario: Notification INSERT failure rolls back the auto-hide chain
- **WHEN** the 3rd reporter's transaction attempts the full chain AND the notification INSERT fails (e.g. FK violation because Alice just got hard-deleted)
- **THEN** the encompassing transaction rolls back: the `reports` INSERT, the `UPDATE` on the post/reply, the `moderation_queue` INSERT, and the notification INSERT all fail atomically

#### Scenario: V9 POST /reports response shape unchanged
- **WHEN** inspecting the V9 `POST /api/v1/reports` response pre-V10 and post-V10
- **THEN** both remain HTTP 204 no body on success (V10 does not alter the reports endpoint response contract)

#### Scenario: V9 TODO comment replaced
- **WHEN** inspecting the V9-era auto-hide flip site in `ReportService.kt`
- **THEN** the `// TODO: notifications-api-v??` comment is removed AND replaced with the `NotificationEmitter.emit(...)` call
