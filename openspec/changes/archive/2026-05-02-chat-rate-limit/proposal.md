## Why

`chat-foundation` (V15, PR [#61](https://github.com/aditrioka/nearyou-id/pull/61)) shipped the data plane for 1:1 chat — `conversations` / `conversation_participants` / `chat_messages` schema, four REST endpoints (create-or-return, list conversations, list messages, send message), bidirectional block check on send, 2000-char content guard. It explicitly deferred the canonical Free-tier 50/day chat send cap from [`docs/02-Product.md:318`](../../../docs/02-Product.md) ("Quota: Free 50/day, Premium unlimited") to a follow-up change, naming `chat-rate-limit` and pointing at the shared `rate-limit-infrastructure` capability in its proposal § Out of scope.

The `like-rate-limit` change ([PR #37](https://github.com/aditrioka/nearyou-id/pull/37)) shipped that infrastructure as the `rate-limit-infrastructure` capability (Lettuce-backed `RedisRateLimiter` with single-Lua sliding window, shared `RateLimiter` interface in `:core:domain`, `computeTTLToNextReset(userId)` WIB-stagger helper, hash-tag key standard `{scope:<value>}:{<axis>:<value>}`, `RateLimitTtlRule` + `RedisHashTagRule` Detekt rules). The subsequent `reply-rate-limit` change ([PR #49](https://github.com/aditrioka/nearyou-id/pull/49)) ported the same infrastructure to `POST /api/v1/posts/{post_id}/replies` with a daily-only shape (no burst clause, no `releaseMostRecent` because every successful reply is a real new row). This change applies the identical shape to `POST /api/v1/chat/{conversation_id}/messages`.

Chat sends are structurally identical to replies for rate-limit purposes:

1. **No burst limit.** Likes have a 500/hour burst clause for both tiers ([`docs/02-Product.md:223`](../../../docs/02-Product.md): "Both tiers cap 500/hour burst") to absorb anti-bot velocity-fingerprint patterns. Replies and chat sends do not — chat lacks the velocity-fingerprint surface that anti-bot burst caps target on likes (likes are mass-issuable click-events; chat is a 2000-char compose surface). The canonical line at [`docs/02-Product.md:318`](../../../docs/02-Product.md) is "Free 50/day, Premium unlimited" with no burst clause. So this change ships a daily-only limiter, no rolling-hour limiter — same daily-only shape as `reply-rate-limit`.
2. **No idempotent re-action path.** The `POST /like` handler treats `INSERT ... ON CONFLICT DO NOTHING` returning zero rows as a no-op and releases the slot via `releaseMostRecent`. `POST /chat/{id}/messages` has no equivalent — every successful POST is a real new row in `chat_messages` (no UNIQUE constraint on sender + content). So `releaseMostRecent` is NOT needed at the chat send call site, mirroring `reply-rate-limit`.
3. **Send-only.** `GET /api/v1/chat/{id}/messages`, `GET /api/v1/conversations`, `POST /api/v1/conversations`, and the future admin redaction endpoint MUST stay un-rate-limited at this layer. Read-side throttling lives at the timeline session/hourly layer per [`docs/05-Implementation.md`](../../../docs/05-Implementation.md); per-endpoint read limiters were deferred for likes and replies and the same precedent applies here. Conversation-create is a rare event (one row pair per user-pair lifetime) and is already serialized by the user-pair advisory lock from `chat-foundation` § Slot-race serialization. Admin redaction is a Phase 3.5 endpoint behind `admin_app` role; it is not a user-facing send-rate vector.

## What Changes

- **MODIFIED: `POST /api/v1/chat/{conversation_id}/messages`** — one daily limiter runs in order before any DB work AND before any user-pair advisory lock acquisition:
  - **Daily**: 50/day Free, unlimited Premium. Key `{scope:rate_chat_send_day}:{user:<uuid>}`. TTL via `computeTTLToNextReset(userId)`. Free vs Premium gating reads `users.subscription_status` from the request-scope `viewer` principal populated by the auth-jwt plugin (NOT a fresh DB SELECT — same constraint as the like and reply handlers).
  - On limit hit: HTTP 429 with `error.code = "rate_limited"` and a `Retry-After` header set to seconds-until-oldest-counted-event-ages-out (the daily TTL window). The 429 envelope matches the like-rate-limit and reply-rate-limit precedents byte-for-byte.
  - Limiter ordering: auth (401) → UUID validation on `conversation_id` (400) → daily rate limiter (429) → conversation-existence + active-participant check from chat-foundation (404 / 403) → bidirectional block check from chat-foundation (403 with `"Tidak dapat mengirim pesan ke user ini"`) → 2000-char content-length guard (400) → INSERT `chat_messages` + UPDATE `conversations.last_message_at` in same transaction → 201 success. **The limiter MUST run BEFORE the conversation/participant/block lookups** (matching the like/reply precedent: a downstream business rejection — non-existent conversation, non-participant, blocked, oversize — counts toward the cap as anti-abuse). The limiter MUST run AFTER auth and UUID validation (those are infrastructure-level and never consume a slot).
  - 401 unauthenticated → does NOT consume a slot (limiter never runs without a viewer).
  - 400 `invalid_uuid` on `conversation_id` → does NOT consume a slot (runs before the limiter).
  - 404 `conversation_not_found`, 403 `not_a_participant`, 403 `blocked`, 400 `content_too_long`, 400 `empty_content` → DO consume a slot (run after the limiter, matching the like/reply precedent for downstream business decisions).
  - The limiter SHALL be invoked OUTSIDE the user-pair advisory lock — `POST /api/v1/conversations` is the only endpoint that takes the user-pair lock, and that endpoint is NOT rate-limited by this change. The send-message endpoint takes no user-pair lock; the daily limiter therefore has no lock-ordering interaction to worry about.
- **NEW: `premium_chat_send_cap_override` Firebase Remote Config flag** — when set to a positive integer N, overrides the 50/day Free cap server-side without a mobile release. Mirrors the `premium_like_cap_override` and `premium_reply_cap_override` flag definitions canonically described at [`docs/05-Implementation.md:1416`](../../../docs/05-Implementation.md) (Decision 28 pattern). Unset / malformed / ≤ 0 / oversized (> 10,000) / SDK error → fall back to default 50. Premium users are unaffected (skip the daily limiter entirely). The flag name follows the established `premium_<scope>_cap_override` convention (`like` → `like`, `reply` → `reply`, `chat send` → `chat_send`).
- **NOT MODIFIED:** `GET /api/v1/chat/{conversation_id}/messages`, `GET /api/v1/conversations`, `POST /api/v1/conversations`, future admin chat redaction endpoint. Same scope-discipline as the like-rate-limit and reply-rate-limit precedents — only the SEND endpoint gets a per-endpoint daily cap.

## Capabilities

### New Capabilities
None. This change consumes the existing `rate-limit-infrastructure` capability (shipped in `like-rate-limit`) without extending it.

### Modified Capabilities
- `chat-conversations`: adds the daily-cap, `premium_chat_send_cap_override` Remote Config gate, 429-with-`Retry-After` contract, and limiter-ordering requirements to the existing **Send-message endpoint** requirement. The delta uses `## ADDED Requirements` exclusively (not `## MODIFIED`) because every new requirement is strictly additive: the chat-foundation requirement "Send-message endpoint" continues to describe the un-rate-limited 201 success path verbatim, and the new "Daily send-rate cap with limiter ordering" requirement layers in front of it without altering the 201-on-success contract. This mirrors the `reply-rate-limit` precedent on `post-replies` and the `like-rate-limit` precedent on `post-likes`.

## Impact

- **Code**:
  - The chat send handler (added in `chat-foundation`) gains one pre-DB rate-limit check (daily). The integration point is symmetric with the existing `LikeService` and `ReplyService` integrations shipped in `like-rate-limit` and `reply-rate-limit`.
  - New flag plumbing for `premium_chat_send_cap_override` in the existing `:infra:remote-config` module (one new key alongside `premium_like_cap_override` and `premium_reply_cap_override`).
- **DB**: none. No new tables, no new columns, no new migrations.
- **Dependencies**: none new. Lettuce is already on the classpath from `like-rate-limit`.
- **API**: `POST /api/v1/chat/{conversation_id}/messages` adds 429 to its response set; the 201 success path is unchanged. No request shape change. Mobile already handles 429 generically (timeline rate-limit screen and the like/reply surfaces from PR [#37](https://github.com/aditrioka/nearyou-id/pull/37) / PR [#49](https://github.com/aditrioka/nearyou-id/pull/49)); the chat-compose-specific copy can be tightened in a follow-up UI change (out of scope here).
- **Infra**: requires the staging/prod Upstash Redis project to be reachable from Cloud Run (already provisioned in Pre-Phase 1; same `staging-redis-url` / prod `redis-url` Secret Manager slots already used by the like cap, the reply cap, and the ported V9 reports limiter).
- **CI**: no new lint rules. `RateLimitTtlRule` + `RedisHashTagRule` (from `like-rate-limit`) already cover the new `{scope:rate_chat_send_day}:{user:<uuid>}` call site by virtue of the daily-key shape (`*_day}` substring) and the hash-tag format requirement. The new chat send handler test class will exercise both rules' positive-pass paths via fixture coverage.
- **Out of scope (explicitly deferred — each is a separate change already enumerated in `chat-foundation` proposal § Out of scope)**:
  - Supabase Realtime broadcast publish from Ktor (`chat-realtime-broadcast` follow-up — Phase 2 #9).
  - Embedded post send/render path (`chat-embedded-posts` follow-up).
  - Admin chat-message redaction endpoint (Phase 3.5 admin-panel territory).
  - Per-conversation FCM push batching (Phase 2 #11 follow-up; depends on realtime).
  - `chat_message` notification emit-site (depends on realtime).
  - Perspective API screening of chat content (`perspective-api-moderation` cross-cutting follow-up).
  - Mobile UI tightening of the 429 response on the chat-compose surface (the generic 429 screen suffices for staging soak).
  - Read-side per-endpoint limits on `GET /messages` / `GET /conversations` / `POST /conversations`.
  - Layer 1 guest pre-issuance limits, Layer 4 per-area anti-spam limit — both untouched here.
