## MODIFIED Requirements

### Requirement: notifications table created via Flyway V10

A migration `V10__notifications.sql` SHALL create the `notifications` table verbatim-aligned with `docs/05-Implementation.md` §820–844 with columns:
- `id UUID PRIMARY KEY DEFAULT gen_random_uuid()`
- `user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE`
- `type VARCHAR(48) NOT NULL CHECK (type IN ('post_liked', 'post_replied', 'followed', 'chat_message', 'subscription_billing_issue', 'subscription_expired', 'post_auto_hidden', 'account_action_applied', 'data_export_ready', 'chat_message_redacted', 'privacy_flip_warning', 'username_release_scheduled', 'apple_relay_email_changed'))` — all 13 values per the canonical catalog. After `chat-message-notification` ships, the application code writes 5 of the 13 (`post_liked`, `post_replied`, `followed`, `post_auto_hidden`, `chat_message`); the remaining 8 are reserved for future emit sites (subscription, admin, deletion, privacy-flip, username-release, Apple-relay).
- `actor_user_id UUID REFERENCES users(id) ON DELETE SET NULL` (nullable)
- `target_type VARCHAR(16)` (nullable)
- `target_id UUID` (nullable)
- `body_data JSONB` (nullable)
- `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`
- `read_at TIMESTAMPTZ` (nullable)

Plus two indexes:
- `notifications_user_unread_idx ON notifications (user_id, created_at DESC) WHERE read_at IS NULL` — partial; `read_at IS NULL` predicate is immutable (unlike `> NOW()` predicates which PostgreSQL rejects; see `docs/08-Roadmap-Risk.md:486`)
- `notifications_user_all_idx ON notifications (user_id, created_at DESC)` — full; backs the default "all notifications" list endpoint

The `user_id` FK MUST use `ON DELETE CASCADE` (recipient's per-user feed is PII about them; wiped on hard-delete with the tombstone worker's treatment of other per-user rows). The `actor_user_id` FK MUST use `ON DELETE SET NULL` (actor churn preserves the recipient's historical feed; render layer shows "a deleted user" for null actors).

#### Scenario: Migration runs cleanly from V9
- **WHEN** Flyway runs `V10__notifications.sql` against a DB at V9
- **THEN** the migration succeeds AND `flyway_schema_history` records V10

#### Scenario: All canonical columns present
- **WHEN** querying `information_schema.columns WHERE table_name = 'notifications'`
- **THEN** every column above is present with its documented type, nullability, and default

#### Scenario: type CHECK enum accepts all 13 values
- **WHEN** an INSERT supplies any of the 13 documented `type` values (`post_liked`, `post_replied`, `followed`, `chat_message`, `subscription_billing_issue`, `subscription_expired`, `post_auto_hidden`, `account_action_applied`, `data_export_ready`, `chat_message_redacted`, `privacy_flip_warning`, `username_release_scheduled`, `apple_relay_email_changed`)
- **THEN** the INSERT succeeds for each value

#### Scenario: type CHECK enum rejects out-of-enum value
- **WHEN** an INSERT supplies `type = 'post_shared'`
- **THEN** the INSERT fails with SQLSTATE `23514` (check-constraint violation)

#### Scenario: Both indexes exist
- **WHEN** querying `pg_indexes WHERE tablename = 'notifications'`
- **THEN** the result contains `notifications_user_unread_idx` AND `notifications_user_all_idx`

#### Scenario: Partial index has the immutable WHERE predicate
- **WHEN** querying `pg_indexes` for `notifications_user_unread_idx`
- **THEN** the index definition contains `WHERE read_at IS NULL` (or equivalent `WHERE (read_at IS NULL)`)

#### Scenario: user_id CASCADE removes notifications on recipient hard-delete
- **WHEN** a `users` row is hard-deleted AND it is referenced as `notifications.user_id` by N rows
- **THEN** all N `notifications` rows are cascade-deleted in the same transaction

#### Scenario: actor_user_id SET NULL on actor hard-delete preserves recipient feed
- **WHEN** a `users` row is hard-deleted AND it is referenced as `notifications.actor_user_id` by N rows on OTHER users' feeds
- **THEN** all N rows persist with `actor_user_id = NULL` (they are NOT deleted; the recipient's feed remains intact)

### Requirement: NotificationEmitter write-path with block-suppression and self-action suppression

A `NotificationEmitter` component SHALL encapsulate the write path for all notification types. Each emit call MUST:
1. Short-circuit with `suppressed_reason = "self_action"` when `actor_user_id = recipient_user_id` (self-actions never produce a notification).
2. Query `user_blocks` for bidirectional block: `SELECT 1 FROM user_blocks WHERE (blocker_id = :recipient AND blocked_id = :actor) OR (blocker_id = :actor AND blocked_id = :recipient) LIMIT 1`. If a row matches, short-circuit with `suppressed_reason = "blocked"`. System-originated notifications (actor_user_id IS NULL) skip this check.
3. Otherwise, `INSERT INTO notifications (user_id, type, actor_user_id, target_type, target_id, body_data)` in the caller's existing DB transaction.

The emitter MUST be constructor-injectable into the **five wired writer services** (`LikeService`, `ReplyService`, `FollowService`, `ReportService`, `ChatService` — the chat send-handler service introduced by `chat-message-notification`) via Koin. The block-check and INSERT MUST execute in the same DB transaction as the caller's primary write.

For chat sends (`ChatService`), the caller is responsible for the sender-shadow-ban skip BEFORE invoking `NotificationEmitter.emit(...)` — this is a chat-handler-level decision documented in [`chat-conversations/spec.md`](../../../../specs/chat-conversations/spec.md) § "Send-message endpoint" and is NOT enforced inside the emitter (the emitter is generic across notification types). For all other emit sites (Like / Reply / Follow / Report), shadow-ban skip is not relevant — those flows do not have a publishable real-time surface to suppress.

**Cross-capability shadow-ban handling rule.** Capabilities that compose `NotificationEmitter.emit(...)` SHALL choose between two patterns based on the surface's privacy properties:

1. **Real-time + private-1:1 surface (e.g., chat) → SUPPRESS the emit at the call site.** The shadow-banned actor's notification is not written, no FCM push fires, no in-app row appears. Justification: the recipient cannot reach the actor by any other means (no mutual public surface), so a "Seseorang sent you a message" push from an unreachable counterparty is creepy and useless. Both real-time channels (broadcast + FCM push) get suppressed together. This is the chat pattern, established by `chat-realtime-broadcast` (publish-skip) and extended by `chat-message-notification` (emit-skip).

2. **Public-engagement surface (e.g., post likes / replies / follows) → ALLOW the emit; MASK the username at the FCM body.** The notification row writes normally; the FCM dispatcher reads `actor_user_id` via `ActorUsernameLookup.lookup(...)` from `visible_users` (per [`fcm-push-dispatch/spec.md`](../../specs/fcm-push-dispatch/spec.md) § "ActorUsernameLookup reads from visible_users"); the username masking falls back to `"Seseorang menyukai post-mu"` etc. Justification: the recipient is already exposed to the actor's content via the public surface (post visible via reporter / direct-link / search paths), so a generic-actor notification preserves the recipient's awareness without amplifying the actor.

Future capability authors with a similar real-time + private-1:1 surface (e.g., a future direct-mention feature, a future DM-style group chat, a future voice-message capability) SHOULD follow the chat pattern. Authors with a public-engagement surface (a future bookmark / repost / quote-engagement notification) SHOULD follow the like/reply/follow pattern. Reviewers SHALL flag any new emit call site that doesn't explicitly reference this rule when choosing between the two patterns.

#### Scenario: Self-action suppression (like own post)
- **WHEN** user Alice likes her own post
- **THEN** zero rows are inserted into `notifications` AND the emitter logs `suppressed_reason = "self_action"` at DEBUG

#### Scenario: Block-suppression (Alice blocked Bob, Bob likes Alice's post)
- **WHEN** Alice has a `user_blocks` row `(blocker_id = Alice, blocked_id = Bob)` AND Bob likes Alice's post
- **THEN** zero `notifications` rows are inserted for Alice AND the emitter logs `suppressed_reason = "blocked"` at DEBUG

#### Scenario: Block-suppression (Bob blocked Alice, Bob likes Alice's post)
- **WHEN** Bob has a `user_blocks` row `(blocker_id = Bob, blocked_id = Alice)` AND Bob likes Alice's post (Alice's post is still visible via the reporter / direct-link path)
- **THEN** zero `notifications` rows are inserted for Alice (bidirectional suppression)

#### Scenario: Normal emit (no block, not self)
- **WHEN** Bob likes Alice's post AND no `user_blocks` row exists between Alice and Bob
- **THEN** exactly one `notifications` row is inserted for Alice with `type = 'post_liked'`, `actor_user_id = Bob`

#### Scenario: System-originated emit skips block-check
- **WHEN** an emit occurs with `actor_user_id = NULL` (e.g. `post_auto_hidden`, `privacy_flip_warning`)
- **THEN** the `user_blocks` query is NOT issued AND the INSERT proceeds unconditionally (no user to block)

#### Scenario: Emit failure rolls back primary write
- **WHEN** a primary write (like INSERT) succeeds AND the notification INSERT fails (e.g. recipient user hard-deleted between validation and emit)
- **THEN** the encompassing transaction rolls back AND no `post_likes` row persists

#### Scenario: ChatService is the fifth wired writer service
- **WHEN** inspecting Koin DI bindings in `:backend:ktor`
- **THEN** `NotificationEmitter` is constructor-injected into `ChatService` (the chat send-handler service) AND the four pre-existing wired services (`LikeService`, `ReplyService`, `FollowService`, `ReportService`) — five services total

#### Scenario: Chat-side block-suppression (Alice blocked Bob, Bob sends Alice a chat message)
- **GIVEN** Alice has a `user_blocks` row `(blocker_id = Alice, blocked_id = Bob)`
- **WHEN** Bob attempts `POST /api/v1/chat/X/messages { content: "halo" }` to a conversation containing Alice
- **THEN** the chat-foundation handler returns `403 Forbidden` BEFORE reaching the emit step (chat-foundation's bidirectional block check fires first); zero `notifications` rows are inserted (the emitter is never invoked, so its block-check short-circuit also never fires — the outcome at the data layer is identical to "blocked" suppression but it's enforced earlier in the request flow)

### Requirement: body_data shape per emitted type

For the **five types V10 writes after `chat-message-notification` lands**, `body_data` SHALL be a JSONB object with the following shapes:
- `post_liked`: `{"post_excerpt": <string, ≤ 80 code points>}`
- `post_replied`: `{"reply_id": <UUID string>, "reply_excerpt": <string, ≤ 80 code points>}`
- `followed`: `{}`
- `post_auto_hidden`: `{"reason": "auto_hide_3_reports"}`
- `chat_message`: `{"conversation_id": <UUID string>, "preview": <string, ≤ 80 code points OR JSON null>}`

Excerpts and previews SHALL be the first 80 code points of the source content (post content for `post_liked`, reply content for `post_replied`, chat message content for `chat_message`), taken at emit time. The excerpt / preview MUST NOT be regenerated on read; subsequent edits to the source content do NOT update the already-written `body_data`.

For `chat_message` specifically, `preview` SHALL be JSON `null` when the source `chat_messages.content` is `NULL` (an embedded-only message — schema-permitted per [`docs/05-Implementation.md:1273`](../../../../../docs/05-Implementation.md)). `null` is the canonical signal for "no text to preview"; mobile UI is responsible for rendering a localized fallback (e.g., "Sent a post") via Moko Resources.

The `chat_message` `body_data` SHALL NOT carry `embedded_post_id`, `embedded_post_snapshot`, or `embedded_post_edit_id` keys (those are owned by the future `chat-embedded-posts` change and are out of scope for `chat-message-notification`). Forward-compat: future changes MAY add keys to `chat_message` body_data, but this change ships exactly the two-key shape `{conversation_id, preview}`.

#### Scenario: post_liked body_data shape
- **WHEN** Bob likes Alice's 180-char post
- **THEN** the `notifications.body_data` JSONB has exactly one key `post_excerpt` whose value is the first 80 code points of the post content

#### Scenario: post_replied body_data shape
- **WHEN** Bob replies "ayo ketemu" to Alice's post
- **THEN** the `body_data` JSONB has exactly two keys `reply_id` (UUID) AND `reply_excerpt` (string, the reply content since it's ≤ 80)

#### Scenario: followed body_data shape
- **WHEN** Bob follows Alice
- **THEN** the `body_data` JSONB is the empty object `{}`

#### Scenario: post_auto_hidden body_data shape
- **WHEN** the V9 auto-hide path flips a post via the 3-reporter threshold
- **THEN** the `body_data` JSONB has exactly one key `reason` with value `"auto_hide_3_reports"`

#### Scenario: chat_message body_data shape (text content)
- **WHEN** Bob successfully sends "halo Alice" to Alice in conversation X (Bob not shadow-banned, no block) AND the emit fires
- **THEN** the `notifications.body_data` JSONB has exactly two keys `conversation_id` (UUID string matching X) AND `preview` (string `"halo Alice"`)

#### Scenario: chat_message body_data shape (text > 80 code points)
- **WHEN** Bob successfully sends a 200-character message to Alice
- **THEN** the `body_data.preview` is the first 80 code points of the message content (the next 120 are NOT present)

#### Scenario: chat_message body_data shape (embedded-only, content NULL)
- **WHEN** an embedded-only `chat_messages` row is inserted (content NULL, embedded_post_snapshot non-null) AND the emit fires
- **THEN** the `body_data.preview` is JSON `null` (NOT `""`, NOT a placeholder string); the `body_data.conversation_id` is the conversation UUID

#### Scenario: Excerpt frozen at emit (source edit does not mutate notification)
- **WHEN** Bob likes Alice's post (excerpt captured) AND Alice later edits the post via the Premium edit feature
- **THEN** the `notifications.body_data.post_excerpt` on the already-written row is unchanged

#### Scenario: chat_message preview frozen at emit (later redaction does not mutate notification)
- **GIVEN** Bob successfully sent "halo Alice" to Alice in conversation X; the resulting notification row has `body_data.preview = "halo Alice"`; an admin later redacts the message via the future Phase 3.5 redaction endpoint (simulated in test by a direct UPDATE on `chat_messages` setting `redacted_at` and clearing `content`)
- **WHEN** Alice subsequently queries `GET /api/v1/notifications`
- **THEN** the returned `body_data.preview` for the original `chat_message` notification row is still `"halo Alice"` (frozen at emit time, not regenerated on read)
