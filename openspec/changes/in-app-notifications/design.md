## Context

V10 is the first change in the pipeline that adds a table whose writers are not the primary endpoint of the feature — the `notifications` table is populated by the existing V6 follow, V7 like, V8 reply, and V9 auto-hide paths, and read by four brand-new endpoints. This gives V10 an unusual shape: one DDL migration + four small emitters threaded into four already-shipped services + four read endpoints. Getting the seams right matters more than the individual pieces.

The canonical schema, full 13-value `type` enum, indexes, retention, and event-type catalog (trigger, actor semantics, `body_data` shape) are already decided at `docs/05-Implementation.md:820–867`. The block-suppression rule is decided at `docs/02-Product.md:235`. The Phase 2 roadmap item at `docs/08-Roadmap-Risk.md:152` bundles FCM dispatch with the in-app list, but this change deliberately splits them: the DB row is the source of truth (`docs/05-Implementation.md:849`), FCM is a delivery channel, and splitting them keeps V10 small and keeps the FCM change self-contained (it will add `user_fcm_tokens`, the `POST /api/v1/user/fcm-token` endpoint per Phase 1 item 18, the FCM SDK wiring, and iOS NSE handshake work — none of which belongs in this change).

V10 lands on a pipeline where:
- V6 follow, V7 like, V8 reply, V9 auto-hide each already run their primary writes inside a short DB transaction. Adding one transactional INSERT to each is additive and does not change their existing commit boundary.
- V5 `user_blocks` already has the `UNIQUE (blocker_id, blocked_id)` index the emitters will join against for block-suppression. No schema work in V10 to support this.
- V9 left a `// TODO: notifications-api-v??` stub at its auto-hide flip site. V10 deletes that TODO and replaces it with the real emit call.
- V9 established the "full enum up-front, only a subset written in this change" precedent for `moderation_queue.trigger`. V10 adopts the same precedent for `notifications.type`.

This design covers the nine non-obvious decisions that needed explicit rationale — the rest of V10 is straightforward schema + four emitter one-liners + four read endpoints.

## Goals / Non-Goals

**Goals:**
- Ship V10 DDL verbatim from `docs/05-Implementation.md` §820–844 (one table, full 13-value `type` enum, two indexes, two FKs with asymmetric delete behavior).
- Emit four notification types from the four already-shipped engagement paths (like, reply, follow, auto-hide) with block-suppression and self-action suppression, each as a same-transaction INSERT alongside the primary write.
- Ship four read endpoints (`GET /notifications`, `GET /notifications/unread-count`, `PATCH /:id/read`, `PATCH /read-all`) with JWT auth, per-caller ownership filter, cursor pagination, and index-only unread-count.
- Establish the write-time block-suppression convention for future notification emitters (subscription, admin action, data export, chat message, etc.).
- Ship a `NotificationDispatcher` seam with one in-app implementation today so the FCM change is a drop-in add, not a refactor.
- Zero behavioral change to existing endpoints' response shapes.

**Non-Goals:**
- FCM push dispatch (Phase 2 item 7) — the `user_fcm_tokens` schema, the `POST /api/v1/user/fcm-token` endpoint, the `NotificationDispatcher` FCM implementation, the iOS NSE handshake, and the Android data-only payload builder are all in the FCM change.
- Notifications purge worker (90-day retention) — Phase 3.5 admin-panel worker per `docs/08-Roadmap-Risk.md:242`. V10 documents the 90-day contract via schema comment; enforcement lands with the admin-panel worker change.
- Writers for the 9 reserved `type` enum values (`chat_message`, `subscription_*`, `account_action_applied`, `data_export_ready`, `chat_message_redacted`, `privacy_flip_warning`, `username_release_scheduled`, `apple_relay_email_changed`) — each lands with its respective feature change. V10 writes 4 types; reserves the other 9.
- Read-path block-exclusion — block-suppression is write-time only; see Decision 3.
- Batching / collapsing UX ("Alice and 5 others liked your post") — not in Phase 2 spec, deferred post-MVP.
- Mobile UI (notifications screen, unread badge) — Phase 3.
- Per-endpoint rate limits on the read path — defer to the rate-limit foundation change (Phase 1 item 24).
- `actor_user_id` ON DELETE CASCADE semantics — we use SET NULL; see Decision 7.

## Decisions

### 1. Notifications emit in the same DB transaction as the primary write

**Decision:** `LikeService.like()` / `ReplyService.reply()` / `FollowService.follow()` / `ReportService.onAutoHide()` each extend their existing transaction to include the `INSERT INTO notifications` call. Single transaction, one commit, one row-level lock window. No queue, no post-commit hook, no eventual-consistency worker.

**Rationale:** Same reasoning the V9 auto-hide used at `openspec/changes/archive/2026-04-22-reports-v9/design.md` Decision 1. Transactional coupling guarantees that when the primary action commits, the recipient's feed reflects it atomically (no window where "Bob liked my post" is true in `post_likes` but the notification hasn't landed yet). Since the primary-action transactions are short (<10ms P50 at current scale), adding one INSERT is bounded. If the notification INSERT fails (DB error, CHECK violation, rare), the primary action rolls back — which is the correct safety posture for engagement writes that are meant to notify the recipient.

**Alternatives considered:**
- **Out-of-band queue** (Redis Streams → worker → INSERT): rejected. Opens a visible-to-recipient lag window, adds infra, and creates a "liked but notification lost" failure mode that's worse than the "like rejected because notification would have failed" we avoid by coupling. Worth reconsidering at 1000+ rps.
- **Post-commit hook** (Ktor after-call hook, or PG `LISTEN/NOTIFY` pump): rejected. Same lag window problem, plus the fire-and-forget hook can silently drop on crash.
- **Soft-decouple via application-level try/catch** (log-and-continue on notification failure): rejected. "Sometimes we notify, sometimes we don't" is worse than a hard contract. If this ever becomes operationally necessary, the answer is to harden the INSERT (13-value CHECK enum is stable; FK to users which is stable; JSONB body_data is stable), not to make failure silent.

### 2. Block-suppression is enforced at write time, not read time

**Decision:** Before the `INSERT INTO notifications`, the emitter runs one `user_blocks` existence check: `SELECT 1 FROM user_blocks WHERE (blocker_id = :recipient AND blocked_id = :actor) OR (blocker_id = :actor AND blocked_id = :recipient) LIMIT 1`. If any row matches, the emit is skipped (logged at DEBUG with `suppressed_reason = "blocked"`). Read endpoints do NOT filter by `user_blocks` — they trust that write-time suppression means the feed is clean.

**Rationale:** `docs/02-Product.md:235` specifies "Notifications from a blocked user: suppressed." Four ways to implement this; write-time wins on every axis:
- **Storage**: write-time suppression avoids writing rows that would never be read. Over a 90-day retention window with high-volume blocks (e.g., harassment waves), this is non-trivial savings. Read-time filtering would store+paginate+filter.
- **Partial-index efficiency**: `notifications_user_unread_idx` is a partial index on `WHERE read_at IS NULL`. Adding a `NOT EXISTS` join against `user_blocks` at read time bloats every unread-count query. Keeping the read path single-table keeps the partial index's index-only scan viable.
- **Symmetry**: the check is bidirectional (both "I blocked them" and "they blocked me" matter — the spec at `docs/02-Product.md:235` is not directional). One check at write time covers both; read-time would need two JOINs.
- **Forward-looking semantics**: the block feature is explicitly forward-looking (blocking doesn't erase history; it cuts future interaction). Write-time suppression matches: unblocking later does NOT resurrect notifications, which matches the spec at `docs/02-Product.md:235` (no language about resurrection).

**Alternatives considered:**
- **Read-time filter** (`WHERE actor_user_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = :caller)` + reverse): rejected per efficiency and semantics above. Also risks the `BlockExclusionJoinRule` Detekt rule at `openspec/specs/block-exclusion-lint/spec.md` firing on the notifications endpoint, which would be a false positive — the rule is about timeline / chat / search content exposure, not notifications. Avoiding the read-time JOIN sidesteps that question entirely.
- **Both write-time AND read-time** (defense-in-depth): rejected as over-engineering. Write-time is the authoritative check; doubling up doubles cost without a realistic threat model (the only way a blocked-actor notification lands in the table is a race between the emit and the block-INSERT; the race window is <10ms and the outcome of a race-lost notification is "user sees one more notification from someone they just blocked" which is benign and self-corrects on the next block cache refresh).

### 3. `actor_user_id` has ON DELETE SET NULL, not CASCADE

**Decision:** The FK on `notifications.actor_user_id → users(id)` uses `ON DELETE SET NULL`. The `user_id` (recipient) FK uses `ON DELETE CASCADE` — same pattern as V4 `posts.author_id` (CASCADE on post-owner delete matching tombstone worker) versus V9 `reports.reviewed_by` (SET NULL for historical attribution).

**Rationale:** Two distinct semantics:
- **Recipient hard-delete** = the feed's owner is gone. Their 90-day rolling feed is PII about them (who followed them, who liked their content). CASCADE deletes it with them, matching the tombstone worker's treatment of per-user ephemeral data.
- **Actor hard-delete** = someone who interacted with the recipient's content is gone. The recipient's feed entry is NOT PII about the deleted actor (no text, no personal data beyond `actor_user_id`); it's PII about the recipient (they received this interaction). The recipient should continue to see "someone liked your post from 3 weeks ago" with the actor rendered as "a deleted user" (client-side handling). SET NULL preserves the recipient's historical feed integrity.

This matches V9's precedent at `openspec/changes/archive/2026-04-22-reports-v9/` where `reports.reviewed_by` uses SET NULL "admin churn doesn't erase moderation history" — same principle, applied to actor churn on the recipient's feed.

**Alternatives considered:**
- **Both CASCADE**: rejected. Deleting an active user would blow a hole in other users' recent feeds, which is user-visible degradation (unread-count drops, already-seen items vanish).
- **Both SET NULL**: rejected. The recipient's own deletion should wipe their PII per the tombstone worker contract; leaving their `notifications` rows around with `user_id = NULL` would orphan the rows (they reference no real user and can't be retrieved) and bloat the table indefinitely until the 90-day purge catches them.

### 4. Emit is a synchronous call to `NotificationEmitter`, not a listener on domain events

**Decision:** Each of the four services (`LikeService`, `ReplyService`, `FollowService`, `ReportService`) takes a constructor-injected `NotificationEmitter` dependency and calls it explicitly at the emit site. No domain-event bus, no publish/subscribe indirection.

**Rationale:** Four call sites. Each is one line. The indirection cost of a domain-event framework (Koin module wiring, event type registry, async event handler scheduling, error-propagation policy across boundary) is not worth paying for four deterministic call sites all living in the same module (`backend/ktor`). If the count grows to 10+ emit sites with meaningfully different semantics (e.g., some that can tolerate lag, some that must be transactional), revisit.

**Alternatives considered:**
- **Outbox pattern** (write an `outbox_events` row in the primary TX, async worker picks up + processes into `notifications`): rejected as overkill for four synchronous same-TX writes. Reconsider at the notifications-via-FCM change if FCM dispatch needs its own retry semantics separate from the DB write.
- **Koin domain-event bus**: rejected per indirection cost.

### 5. `body_data` JSONB is written (not computed at read time), and size-capped at emit

**Decision:** At emit time, `body_data` is serialized to a small JSONB (<200 bytes typical, <500 bytes hard cap via app-layer guard). The `post_excerpt` / `reply_excerpt` fields take the first 80 code points of the source content (matches the existing 80-char display truncation used in timeline card previews — confirm in implementation by referencing the existing timeline DTO). Reads return `body_data` as-is; no re-query of `posts` / `post_replies` / `users` at render time.

**Rationale:** The recipient needs enough context to render "Bob replied to your post 'Mau ngopi…' with 'Ayo ke Tebet'" without the client issuing two follow-up GETs. The source content is small (280 chars max), the excerpt is an 80-char prefix, and storing the denormalized snippet at emit time costs ~100 bytes per row (cheap vs. the read-amplification of joining back to `posts` / `post_replies` for every list fetch).

There's a staleness trade-off: if the source post is edited after the notification lands, the excerpt is stale. Accepted — the `post_edits` V4 feature is Premium-only and low-volume, and the excerpt drift is semantically correct ("here's what the post said when Alice liked it" is a reasonable reading). The notification's `target_id` points to the live post, so the client can always GET the current version if the user taps through.

**Alternatives considered:**
- **Store target IDs only, JOIN at read time**: rejected per read-amplification. Every notifications-list fetch would need 4-way JOINs against potentially-4 different target tables.
- **Pre-render HTML/Markdown snippet**: rejected. Storing structured JSON is cheaper and keeps rendering concerns on the client.

### 6. Full 13-value `type` enum ships at V10, even though V10 writes only 4 values

**Decision:** The `CHECK (type IN (...))` constraint includes all 13 values from `docs/05-Implementation.md:826–832` at V10. Only `post_liked`, `post_replied`, `followed`, `post_auto_hidden` have writers in this change. The other 9 values are reserved for future feature changes (chat, subscription, account action, data export, chat redaction, privacy flip, username release, Apple S2S).

**Rationale:** Identical precedent to V9's decision to ship the full 7-value `moderation_queue.trigger` enum when only one value had a writer (see `openspec/changes/archive/2026-04-22-reports-v9/design.md` Decision 7). Avoids an enum-widening migration every time a downstream feature adds a notification type. The vocabulary has been stable in `docs/05-Implementation.md` through multiple revisions.

**Alternatives considered:**
- **Ship only the 4 values V10 writes**: rejected — creates 9 micro-migrations over subsequent changes.
- **Use a lookup table instead of CHECK enum**: rejected — inconsistent with the V5 `user_blocks`, V6 `follows`, V7 `post_likes`, V8 `post_replies`, V9 `reports` + `moderation_queue` precedent which all use CHECK enums for closed vocabularies.

### 7. Unread count uses the partial index; list endpoint reuses either the partial or all index based on filter

**Decision:** `GET /notifications/unread-count` runs `SELECT COUNT(*) FROM notifications WHERE user_id = :caller AND read_at IS NULL`, which the planner will serve via `notifications_user_unread_idx` (the partial index). `GET /notifications?unread_only=true` runs against the same partial index; `GET /notifications` (default, all) runs against `notifications_user_all_idx`. Both indexes are on `(user_id, created_at DESC)` — the partial also has the `WHERE read_at IS NULL` filter. The `user_id` column gives range isolation per caller; `created_at DESC` gives the natural pagination order.

**Rationale:** The partial index on `WHERE read_at IS NULL` is a small subset of the full index (typically 1-10% of rows for an engaged user; less for inactive). Unread-count is the hottest query (it's the "badge count" the mobile app polls on foreground), and serving it from the partial index is an index-only scan with no heap fetches. `read_at IS NULL` is immutable (no `NOW()`-style non-immutability issue — Postgres accepts it cleanly), sidestepping the partial-index pitfall that `docs/08-Roadmap-Risk.md:486` documents for `username_history` and `csam_archive`.

**Alternatives considered:**
- **Single index only** (`(user_id, created_at DESC)` without the partial): rejected. Unread-count becomes a filter-on-index-scan, which with thousands of read notifications buries the tens of unread ones.
- **Separate `unread_count` materialized column on `users`**: rejected. Consistency maintenance (increment on emit, decrement on read, reset on read-all) is error-prone and doesn't pay off until unread-count is called at >100 rps per user.

### 8. Cursor pagination on `created_at DESC` (seek-paginate, not offset-paginate)

**Decision:** The `GET /notifications` endpoint accepts an optional `?cursor=<ISO8601>` query param. With no cursor, return the newest `limit` rows. With cursor, return rows `WHERE created_at < :cursor` ordered `DESC` with the same limit. `next_cursor` in the response is the oldest `created_at` in the current page, or `null` if the page is not full.

**Rationale:** Offset pagination on a monotonically-growing table with per-user partial filtering is O(offset) per page fetch — the classic "page 50" problem. Seek pagination on `created_at DESC` uses the index directly (`(user_id, created_at DESC)` → WHERE + ORDER align), is O(1) per page, and doesn't drift if new rows arrive between fetches. The cursor is a timestamp (opaque to client; stable across retries; naturally monotonic).

**Trade-off**: two notifications with the same `created_at` microsecond can theoretically straddle a page boundary. Mitigation: `created_at TIMESTAMPTZ` has microsecond precision; collision probability per single user is negligible. If ever observed (unlikely), tie-break on `id` (UUID) as secondary cursor. Deferred — not worth complicating the V10 cursor shape for a case the data won't produce.

**Alternatives considered:**
- **Offset pagination**: rejected per the O(offset) cost.
- **Compound cursor `(created_at, id)`**: rejected as premature. ISO8601 is trivial to document and debug.

### 9. `NotificationDispatcher` interface ships as a one-implementation seam (in-app only)

**Decision:** `NotificationService.emit()` writes the DB row, then calls `NotificationDispatcher.dispatch(NotificationDto)`. `NotificationDispatcher` is a `:core:data` interface. The only V10 implementation (`InAppOnlyDispatcher`) is a no-op (logs the dispatch at INFO; does not push anywhere). The FCM dispatch change swaps in a real implementation (or adds a composite that fans out to in-app + FCM).

**Rationale:** Two payoffs with one small interface:
- **Clean seam for the FCM change**: the FCM change adds one class (`FcmDispatcher`), re-binds in Koin, and ships. It does NOT touch `NotificationService` or any emitter. This minimizes the diff of the FCM change and keeps V10 cleanly reverse-compatible if FCM is delayed.
- **Testability today**: `NotificationService` unit tests inject a recording dispatcher; V10 doesn't need to mock FCM or any push infrastructure to prove correctness.

The interface is in `:core:data` (not `:infra:*`) because its contract is framework-agnostic — it's a pure delivery-channel abstraction. The FCM implementation itself will live in `:infra:firebase` (new module in the FCM change) per the `docs/04-Architecture.md` `:infra:*` rule that forbids vendor SDK imports outside `:infra:*`.

**Alternatives considered:**
- **No seam; build FCM directly into `NotificationService` in the later change**: rejected. The later change would then have to refactor `NotificationService` (which every emitter depends on) — a wider blast radius than necessary.
- **Put the interface in `:core:domain`**: rejected. `:core:domain` is "pure Kotlin, zero vendor deps" per `openspec/project.md:48`. `NotificationDispatcher` is a data-flow interface, not a domain concept — it belongs in `:core:data`.

## Risks / Trade-offs

- **Risk: a notification INSERT failure rolls back the primary action (like / reply / follow / auto-hide).** Mitigation: the INSERT is structurally simple — two FKs to `users`, one 13-value CHECK enum, one nullable JSONB, three nullable fields. The realistic failure modes are (a) recipient user hard-deleted between primary-write validation and notification emit (FK violation → transaction rolls back — correct, the primary action shouldn't land anyway if the recipient is gone; the emitter checks `user_id` existence via the FK itself), (b) CHECK enum mismatch (not possible in V10 since the 4 writers emit hardcoded types). If this becomes operationally painful (e.g., an audit shows 0.01% of likes get rolled back this way), the fix is a `SELECT 1 FROM users WHERE id = :recipient` pre-check in the emitter; defer until evidence.
- **Risk: write-time block-suppression race (actor emits before recipient's block commits).** Mitigation: the race window is <10ms; the worst outcome is one stray notification from the newly-blocked actor. Mobile client can filter on its end using the local block list if aggressive UX is needed; server doesn't bother. Matches the `docs/02-Product.md` non-strict semantics of block ("forward-looking").
- **Risk: `body_data` excerpt drift after `post_edits` lands.** Accepted — see Decision 5. The `target_id` points to the live post; client can resolve current state on tap-through.
- **Risk: hot recipient (e.g., a creator with 10k followers liking their posts rapid-fire) serializes on the primary-write TX.** Mitigation: the added INSERT is one row against `notifications` with a `user_id` that's the post author. Postgres row-level locking is not involved (the INSERT doesn't UPDATE any existing row); only the `users` FK validation and the CHECK constraint are on the hot path. At <100 rps per recipient, no lock contention expected. If observed at scale, the fix is a per-recipient INSERT-batching queue — deferred.
- **Risk: 90-day retention accumulates rows until the purge worker ships.** Mitigation: the partial `_unread_idx` keeps the hot-path queries lean regardless of total row count. The full `_all_idx` serves paginated lists; its size grows linearly with retention × emit rate, which is small (4 types × typical per-user engagement) at MVP scale. Even pessimistic sizing (every user receives 1000 notifications / 90 days = ~1MB/user) is fine for Supabase Pro. Phase 3.5 purge worker will catch up without intervention.
- **Risk: cursor pagination ties on `created_at` microsecond.** Mitigation: negligible probability at per-user scale; documented in Decision 8. If observed, compound cursor on `(created_at, id)` is a 3-line change.
- **Trade-off: four services gain a `NotificationEmitter` dependency.** Mitigation: the dependency is a single-method interface, constructor-injected via Koin. The services' existing test suites can no-op the emitter by injecting a recording stub. No existing test will break; tests adding notification assertions land alongside the emitter wiring.
- **Trade-off: `post_auto_hidden` notification is recipient-visible even for the rare "I reported and got auto-hidden on my own content" edge case.** Self-reports are already rejected by V9 at the endpoint, so this can't trigger from self-reports. Third-party auto-hide is exactly the case we WANT to notify the author about. No mitigation needed.
- **Trade-off: 9 reserved `type` enum values will sit unused until their feature changes land.** See Decision 6. Pattern matches V9.

## Migration Plan

1. Author `V10__notifications.sql` with the one table, 13-value CHECK enum, 2 indexes, both FKs (CASCADE on `user_id`, SET NULL on `actor_user_id`).
2. Stage Flyway migration run against `dev` local DB via the existing `scripts/flyway-migrate-local.sh`. Verify the table + both indexes exist; verify the CHECK enum rejects out-of-enum values; verify both FKs are recorded correctly in `information_schema.referential_constraints`.
3. Ship `NotificationService` + `NotificationEmitter` + four emitter call sites (one per service) + four read endpoints + `NotificationDispatcher` interface + in-app-only implementation + Koin wiring — all in one PR. Detekt and ktlint pass locally before PR.
4. Staging deploy: `main` merge auto-triggers Cloud Run Job `nearyou-migrate` (Flyway) → applies V10 → backend deploys. Zero-downtime because the table is brand new (no contract break on existing routes) and the emitters' transaction signatures are unchanged to their callers (just adds one INSERT inside the existing TX).
5. Post-deploy smoke on staging: POST a like on a seed user's post from another seed user → GET `/notifications` as the post author → verify the `post_liked` row. Repeat for reply, follow, auto-hide (via the 3-reporter chain from V9). Verify block-suppression by blocking before the like and confirming no notification. Verify self-action suppression by liking own post.
6. Prod deploy: git tag `v*` after staging smoke tests pass.

**Rollback strategy:**
- If V10 migration fails: Flyway records the failure; next run re-attempts. DB has no partial state because the migration is one CREATE TABLE + indexes — no data mutation.
- If V10 migration succeeds but the application code has a bug in the emitters: hot-fix deploy; `notifications` table sits idle if the deploy is full rollback. Forward-compatible — an empty `notifications` table harms nothing.
- Extreme rollback (remove the table): `V11__drop_notifications.sql` DROPs the table. There's no data to preserve in a rollback scenario.

## Open Questions

- **Should `post_auto_hidden` notifications be suppressed if the target is an auto-hidden reply (not a post) whose parent post is also auto-hidden?** The notification would correctly fire for the reply author on their reply being hidden, regardless of the parent state. No current reason to suppress; deferred — if mobile UX complains about "both got auto-hidden; two notifications feels redundant," revisit with a dedup heuristic.
- **Should the read endpoints strip HTML/Markdown from `body_data` excerpts defensively?** V10 stores excerpts as the raw source-content prefix (which is already 280-char user-submitted text — no HTML allowed by the post / reply content validation). If a future source relaxes that (unlikely), revisit. For V10, no sanitization.
- **Should `GET /notifications` expose a `since=<cursor>` param (newer-than) for polling?** Deferred — the `unread-count` endpoint serves the badge polling use case cheaply; `since` is useful only if the client wants to render newly-arrived items inline without a full refresh. Mobile spec in Phase 3 will clarify.
- **Should the `NotificationEmitter` fail-closed or fail-open on block-check DB error?** V10 fails-closed (block-check failure propagates up and rolls back the primary TX). Alternative: fail-open (emit the notification on block-check DB error, matching "don't block user actions on auxiliary checks"). Leaning fail-closed because the block-check failure mode is itself a DB error, which is the primary action's problem too — we shouldn't succeed the primary action while silently losing a secondary safety check. Deferred — decision revisited if block-check DB error rate becomes non-zero in staging.
