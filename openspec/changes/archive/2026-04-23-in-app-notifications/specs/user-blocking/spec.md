## ADDED Requirements

### Requirement: Blocks suppress notifications at write time, not read time

V10 introduces the first cross-feature consumer of `user_blocks` for notification suppression. The `NotificationEmitter` SHALL check `user_blocks` bidirectionally BEFORE inserting a `notifications` row, and SHALL skip the insert if any block row exists in either direction between the actor and the recipient. Suppression is write-time: once a notification is not written, it cannot be retroactively materialised if the block is later lifted.

Bidirectional suppression means: if EITHER `(blocker_id = recipient, blocked_id = actor)` OR `(blocker_id = actor, blocked_id = recipient)` exists in `user_blocks`, no notification row is written. This matches the V5 `user_blocks` bidirectional-visibility contract documented in `docs/02-Product.md` § Blocking.

System-originated emits (actor_user_id = NULL, e.g. `post_auto_hidden`) SKIP the block-check entirely — there is no actor to block. The auto-hide signal is admin/system-sourced and always reaches the target author.

Unblock (`DELETE /api/v1/blocks/{user_id}`) MUST NOT resurrect notifications that were suppressed while the block was active. Notifications suppressed during a block window are lost forever; this is by design, matching the V5 block semantics where historical activity during the block is not replayed.

V10 does NOT change any existing `user_blocks` schema, endpoint contract, or read-path filter. The write-time block-check on notifications is a NEW consumer of the existing table; all prior consumers (timeline read-path filters, follow read-path, reply read-path) remain unchanged.

#### Scenario: Notification suppressed when recipient has blocked actor
- **WHEN** Alice has a `user_blocks` row `(blocker_id = Alice, blocked_id = Bob)` AND Bob performs an action (like/reply/follow) against Alice
- **THEN** the `NotificationEmitter` queries `user_blocks` AND finds the row AND inserts zero `notifications` rows for Alice

#### Scenario: Notification suppressed when actor has blocked recipient (bidirectional)
- **WHEN** Bob has a `user_blocks` row `(blocker_id = Bob, blocked_id = Alice)` AND Bob performs an action against Alice's content (possible if Bob hits a direct-link / cached view of content produced before the block)
- **THEN** the `NotificationEmitter` finds the row in the reverse direction AND inserts zero `notifications` rows for Alice

#### Scenario: Unblock does NOT resurrect suppressed notifications
- **WHEN** Alice blocked Bob AND Bob liked Alice's post during the block window (producing zero notifications) AND Alice later DELETEs the block
- **THEN** zero `notifications` rows for that historical like are materialised (suppression is write-time, not replay-able)

#### Scenario: System-originated emits skip block-check
- **WHEN** the V10 auto-hide flow emits a `post_auto_hidden` notification with `actor_user_id = NULL`
- **THEN** the emitter does NOT issue a `user_blocks` query (no actor to block) AND the notification row is written regardless of any blocks Alice has in her table

#### Scenario: user_blocks schema and endpoints unchanged
- **WHEN** comparing `user_blocks` DDL, `POST /api/v1/blocks`, `DELETE /api/v1/blocks/{user_id}`, and `GET /api/v1/blocks` pre-V10 and post-V10
- **THEN** all schemas, response shapes, and behavior match the V5 contract byte-for-byte (V10 adds a new consumer but does not alter blocking itself)

#### Scenario: Block-check happens inside the emit transaction
- **WHEN** `NotificationEmitter.emit(...)` runs inside a transaction that also inserts the triggering event (like/reply/follow row)
- **THEN** the `user_blocks` SELECT sees the same transactional snapshot — a block inserted in the same transaction (hypothetical edge case) would be visible to the emitter, and the notification would be suppressed accordingly
