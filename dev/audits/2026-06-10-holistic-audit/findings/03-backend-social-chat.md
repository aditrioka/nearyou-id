# 03 — Backend Social Graph + Chat + Search + Notifications + User

Area: `backend/ktor/.../app/{follow,block,user,chat,search,notifications}/**` + matching `infra/supabase` + `infra/fcm` repos. Rubric: docs/11 §3 vs docs/02 (chat/search/notifications) + docs/05 canonical queries. Reviewed 2026-06-10.

## CRITICAL

(none)

## HIGH

1. `backend/ktor/src/main/kotlin/id/nearyou/app/chat/ChatService.kt:237` + `moderation/ModerationListLoader.kt:74` — Moderation tier-cascade network I/O runs inside the open chat-send DB transaction.
   `preInsertHookInTx` calls `textModerator.moderate(content)` between `BEGIN` and the `chat_messages` INSERT (`ChatRepository.sendMessage:363`). `ModerationListLoader.load()` has NO in-JVM cache: every Free/Premium send does 2–3 synchronous Redis reads (profanity list + UU ITE list + threshold) while holding a Hikari connection with `autoCommit=false`; on Redis degradation the ladder falls through to Firebase Remote Config → repo file → Secret Manager — serial remote calls + timeouts inside the transaction. With docs/11 §3.2 pools sized 2–10 per instance, a Redis brown-out converts into DB-pool exhaustion that stalls unrelated requests. Fix: compute the verdict BEFORE opening the transaction (verdict depends only on `content`; keep enforcement/precedence in the hooks), and/or add a short in-process cache in the loader. Same shape exists in `CreatePostService`/`ReplyService` (other area). Confidence: high.

2. Area-wide (e.g. `chat/ChatRepository.kt:108`, `infra/supabase/.../JdbcUserFollowsRepository.kt:30`, `infra/supabase/.../JdbcNotificationRepository.kt:109`, `user/JdbcUserProfileReader.kt:49`) — Blocking JDBC on the request coroutine context; no pool-bounded dispatcher exists anywhere.
   docs/11 §3.2 ("the #1 backend perf rule") mandates a single DI-provided `Dispatchers.IO.limitedParallelism(maxPoolSize)` for every JDBC call. Grep shows zero `limitedParallelism` in backend/infra: most repo calls run directly on the Ktor call dispatcher (Follow/Block/Chat/Notifications/Profile are non-suspend straight through the route), and the spots that do offload use raw `Dispatchers.IO` (Search repo, FcmTokenRepository, ConsentRepository). Request floods queue on the Hikari pool and starve unrelated IO exactly as §3.2 warns. Fix: one shared bounded dispatcher via DI + `withContext` at the service/repo seam. (Baseline is 2 days old — this is the expected gap docs/11 was written to surface; systemic, also applies to other areas.) Confidence: high.

3. `follow/FollowRoutes.kt:40` + `block/BlockRoutes.kt:29` — No rate limit on follow/unfollow/block/unblock; follow↔unfollow loop is an unbounded FCM-push spam vector.
   docs/05 § Follow Schema prescribes "Follow/unfollow rate limit: 50/hour"; docs/02 §4 + docs/05 § Block prescribe 30 block/unblock per hour. Neither the `follow-system`/`user-blocking` specs nor any `follow-up` issue carries these (like/reply/chat all got their `*-rate-limit` follow-ups; follow/block never did). Concretely abusable: each re-follow after unfollow is a fresh INSERT (`inserted=true`) → new `followed` notification row + real FCM dispatch (`FollowService.follow:44`, emitter has no dedupe) — a 2-call loop spams a victim's device push-channel and grows `notifications` unboundedly. Fix: `FollowRateLimiter`/`BlockRateLimiter` on the `ReportRateLimiter` hourly pattern (`{scope:rate_follow}:{user:U}`). Confidence: high (gap vs canonical docs; no deferral found).

## MEDIUM

4. `infra/supabase/.../JdbcUserFollowsRepository.kt:137-169` vs `JdbcUserBlockRepository.kt:23` — Check-then-act race lets a `follows` row and a `user_blocks` row commit for the same pair.
   `followInTx` SELECTs `user_blocks` then INSERTs `follows`; a concurrent block (INSERT `user_blocks` + DELETE `follows`) can fully interleave under READ COMMITTED in either order (block's DELETE cannot see follow's uncommitted INSERT). Result: block + follow coexist — read paths mask it (lists/timelines NOT-IN-filter), but the stale edge silently RESURRECTS when the blocker later unblocks, violating "Follow relationships … automatically removed when a block is applied" (docs/02 §4). Fix: take the canonical user-pair advisory lock (`hashtext(LEAST||':'||GREATEST)` — already implemented in `ChatRepository.acquireUserPairLock`) in both `followInTx` and `JdbcUserBlockRepository.create`. Confidence: high on the race, medium on severity (tiny window, durable effect).

5. `follow/FollowRoutes.kt:109-163` (`/followers`, `/following`) vs `user/UserProfileRoutes.kt:35` — Cross-endpoint differential defeats the profile read's constant-404 design (shadow-ban/block oracle). QUESTION-grade.
   `user-profile-read` collapses unknown/soft-deleted/shadow-banned/blocked to one constant 404 "so a viewer cannot tell which cause applies". But `ensureProfileExists` (`JdbcUserFollowsRepository.kt:176`) reads raw `users`, so for a shadow-banned or blocking target: `GET /users/{id}` → 404 while `GET /users/{id}/followers` → 200 (and `POST /follows/{id}` → 204/409) — a prober distinguishes "hidden-from-me" from "gone". Each endpoint matches its own spec (follow-system predates user-profile-read); the leak is emergent composition. If the constant-404 intent is real, `/followers`/`/following` should resolve the profile through the same `visible_users` + bidirectional-block gate. Confidence: high on the differential; deliberate-vs-oversight is for the operator.

6. `follow/FollowRoutes.kt:90` (`FollowListItem`), `block/BlockRoutes.kt:21` (`BlockListItem`) — Social lists return bare user IDs; rendering forces a client N+1 against the single-user profile endpoint.
   Followers/following/blocked-list rows carry only `{userId, createdAt}`; there is no batch profile read, and `GET /api/v1/users/{id}` is one-at-a-time with two COUNT subqueries each. A 30-row followers page = 30 sequential profile GETs (≈60 follows-COUNTs) from mobile. The same gap for notifications is already tracked (#194 actor-username enrichment); follow/block lists have no tracking issue, and the mobile profile+follow screens are the CURRENT critical-path work (#196). Fix: enrich list rows via `LEFT JOIN visible_users` (chat-conversations partner pattern) or add a batch profile endpoint before the mobile profile screen lands. Confidence: high.

## LOW

7. `chat/ChatRepository.kt:173-175` — `other` participant join lacks `left_at IS NULL`; latent duplicate conversation rows.
   Today no write path sets `left_at`, so it's unreachable; but if leave-conversation ever ships, a left+rejoined slot yields 2+ `other` rows → duplicate list items for one conversation. Also `sendMessage:355` maps "partner left" to `NotParticipantException` (403) for a sender who IS a participant — misleading code path. Add the filter now (one line) to keep schema semantics honest.

8. `infra/supabase/.../JdbcNotificationRepository.kt:121` — Unknown-type row skip happens AFTER LIMIT; can silently terminate pagination early.
   `toRowOrNull()` drops unknown `type` rows post-fetch; `NotificationService.list` then sees `rows.size <= limit` → returns `nextCursor = null`, truncating all older notifications. Only triggers on version-skew/manual inserts (13-value CHECK guards writes), but the failure mode is silent data loss in the list. Fix: derive the cursor from the last FETCHED row (pre-filter), or filter in SQL (`type IN (...)`).

9. `chat/ChatRepository.kt:167` vs `user/JdbcUserProfileReader.kt:152` — Premium-badge formula inconsistent across surfaces.
   Chat partner `is_premium` = `IN ('premium_active','premium_billing_retry')`; profile read's `isPremium` = `= 'premium_active'` only (design D2, the newer, explicitly-reasoned choice). A billing-retry user shows a premium badge in the chat list but not on their profile. Align chat to the D2 formula (or record the divergence in docs/11 Pattern Registry).

10. `docs/05-Implementation.md:975` — `user_blocks` schema drift: doc says `blocked_at`, actual V5 migration + all code use `created_at`.
    Doc-only fix; canonical doc is wrong vs shipped schema (V5 checksum-immutable).

11. `user/JdbcActorUsernameLookup.kt:36` — `@AllowMissingBlockJoin` is inert where placed.
    Annotation sits on the `lookup()` function but the SQL literal lives in the companion const (rule walks up from the literal — PR #207 lesson), AND `BlockExclusionJoinRule` doesn't match `visible_users` anyway. Purely documentary today; fail-safe direction (lint would still fire if the query switched to raw `users`), but move it to `SQL_SELECT_USERNAME` for consistency with `JdbcUserProfileReader.SQL_SELF`.

12. `follow/FollowService.kt:43-54` — Duplicate bidirectional `user_blocks` query per follow.
    `followInTx` checks blocks, then `DbNotificationEmitter.emit` re-checks the identical pair in the same transaction (`isBlockedBetween`). One redundant round-trip per follow; harmless but contradicts the "no extra SELECT" discipline the chat emit path documents. Either pass a `blockCheckDone` hint or accept as emitter-centralization cost (document).

13. `infra/supabase/.../JdbcSearchRepository.kt:123` — OFFSET pagination vs docs/11 §3.3 "no OFFSET" rule (registry tension, not a bug).
    The query is verbatim-canonical from docs/05 (LIMIT 20 OFFSET ?, route-capped at 10k), but docs/11 §3.3 now says cursor-only for timeline-style endpoints. Deep pages re-rank the full match set per request. Reconcile the two docs (either exempt search or plan keyset-by-rank later); no code change needed at MVP scale.

14. `openspec/specs/chat-conversations/spec.md:13` — `conversation_participants.last_read_at` is dead schema: never written or read by any backend path; chat unread counts/read receipts have no tracking issue. Fine to defer, but worth a `follow-up` issue so the mobile chat screen (critical-path menu) doesn't discover it cold.

15. `chat/ChatRepository.kt:181-200` — Conversation-list keyset paginates over the mutable `last_message_at`; a conversation bumped mid-pagination jumps ahead of the cursor and is skipped until refresh. Inherent to chat-list ordering (industry-standard tradeoff), cursor logic itself is correct incl. the NULLS-LAST tail. Note only; no action.

## Verified clean (hunting-list items with no finding)

- Duplicate-conversation race: user-pair advisory lock + `conv_slot_unique` partial index — race-proof per docs/05; existence/self-DM/block ordering matches design.
- Send path: limiter → parse → 2000-char guard (`ContentLengthGuard`) → tx {block check → moderate → INSERT → flag-queue → emit → bump `last_message_at`} → COMMIT → broadcast. Broadcast-fail-after-persist IS documented (design D3): WARN + span event, 201 kept, 4 attempts w/ 100/300/900ms backoff + 500ms per-attempt timeout, REST resync.
- Conversation list has no last-message/unread N+1 because the spec'd response deliberately omits both (chat-conversations spec line 139).
- Chat/messages cursors: base64url JSON, strict decode → 400; `(created_at,id)` DESC keyset matches ORDER BY; µs precision round-trips.
- Notifications: unread COUNT rides the partial index `notifications_user_unread_idx`; `markRead`/`markAllRead` scoped `user_id = ?` (own rows only); FCM fan-out post-commit on a bounded `FcmDispatcherScope` with DB-clock race-guarded token pruning.
- FcmTokenRepository: single atomic upsert (`ON CONFLICT ... DO UPDATE`, `xmax=0` created-detection), `last_seen_at` freshness per docs/05; per-user token-count tripwire is design D3 (cap deliberately absent).
- ConsentRepository: own-row write keyed by JWT sub; `analytics_consent` correctly outside username/privacy-flag write allowlists (documented in KDoc).
- Search: verbatim canonical FTS query (plainto_tsquery 'simple' + pg_trgm `%`, `SET LOCAL similarity_threshold`), fully parameterized, hard LIMIT 20, offset capped 10k pre-service, premium gate before limiter (one Redis round-trip), kill switch doesn't burn quota.
- Suspension semantics on profile read: deliberately absent (session-terminating; #208) — not a gap.
- `@AllowMissingBlockJoin` on `JdbcUserProfileReader.SQL_SELF`: correctly placed on the SQL-holding property.
