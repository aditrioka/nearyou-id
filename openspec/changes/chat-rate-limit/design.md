## Context

`chat-foundation` (V15, [PR #61](https://github.com/aditrioka/nearyou-id/pull/61)) shipped POST/GET on `/api/v1/chat/{conversation_id}/messages` and POST/GET on `/api/v1/conversations`, but with no rate limiting. The 50/day Free chat send cap is documented as canonical at [`docs/02-Product.md:318`](../../../docs/02-Product.md) ("Quota: Free 50/day, Premium unlimited"); the chat-foundation proposal explicitly named `chat-rate-limit` as a follow-up consuming the shared `rate-limit-infrastructure` capability.

`like-rate-limit` ([PR #37](https://github.com/aditrioka/nearyou-id/pull/37)) shipped the rate-limit infrastructure: a Redis-backed `RateLimiter` interface in `:core:domain`, a Lettuce-based `RedisRateLimiter` in `:infra:redis`, the `computeTTLToNextReset(userId)` WIB-stagger helper, the hash-tag key format, and the two Detekt rules (`RateLimitTtlRule` + `RedisHashTagRule`) that enforce conventions at every call site. `reply-rate-limit` ([PR #49](https://github.com/aditrioka/nearyou-id/pull/49)) was the second consumer (third if the V9 reports port is counted) and proved the daily-only port pattern: same call sequence, same 429 envelope, same Remote Config flag pattern, no burst, no `releaseMostRecent`. This change is the next consumer along that line — a near-mechanical port of the reply-handler integration to the chat send call site.

The chat send path also already integrates with the chat-foundation slot-race-serialized create flow (the user-pair advisory lock from `chat-foundation/specs/chat-conversations/spec.md` § "Slot-race serialization via two advisory locks") and the bidirectional block check (canonical query at [`docs/05-Implementation.md:1304-1308`](../../../docs/05-Implementation.md)). Neither integration is affected by this change — the limiter runs strictly before any conversation/participant/block lookup, so a 429 short-circuits both. The send-message endpoint takes no user-pair advisory lock (only `POST /api/v1/conversations` does), so the limiter has no lock-ordering interaction to worry about.

## Goals / Non-Goals

**Goals:**

- Apply the canonical Free 50/day daily cap to `POST /api/v1/chat/{conversation_id}/messages`, with 429 + `Retry-After` on limit hit, Premium-tier skip, and the `premium_chat_send_cap_override` Remote Config flag honored server-side.
- Honor the existing `rate-limit-infrastructure` capability contract verbatim — same `tryAcquire` shape, same key hash-tag format, same WIB-stagger TTL via `computeTTLToNextReset`. Verify both Detekt rules pass on the new call site without modification.
- Add `LikeRateLimitTest`/`ReplyRateLimitTest`-shaped integration coverage at `ChatSendRateLimitTest` against the existing CI Redis container (already provisioned by `like-rate-limit`).
- Preserve `chat-foundation` send-endpoint contracts byte-for-byte: 201 success path, atomic `last_message_at` UPDATE, bidirectional block 403, 2000-char content guard, shadow-banned-sender persists, embedded-field silent-ignore, `last_message_at` rollback on INSERT failure all unchanged.

**Non-Goals:**

- Burst (rolling-hour) limit on chat sends — chat has no burst clause per [`docs/02-Product.md:318`](../../../docs/02-Product.md). Future anti-bot work on chat, if needed, would be a separate change with its own design.
- Rate-limiting `GET /api/v1/chat/{conversation_id}/messages` or `GET /api/v1/conversations` — read-side limits live at the timeline session/hourly layer per [`docs/05-Implementation.md`](../../../docs/05-Implementation.md). Mirrors the like-rate-limit and reply-rate-limit precedents.
- Rate-limiting `POST /api/v1/conversations` — conversation creation is rare (one row pair per user-pair lifetime), already serialized by the user-pair advisory lock, and not a per-user-velocity abuse vector. A future per-user "create N conversations/day" cap, if needed, would be a separate change.
- Rate-limiting the future admin chat-message redaction endpoint — Phase 3.5 admin-panel territory; not user-facing.
- Mobile-side UX copy for the 429 response — out of scope; mobile already handles 429 generically and the like/reply button surfaces from PR #37 / PR #49 are the precedent. A chat-compose-specific copy update can ship later.
- New Detekt rules — `RateLimitTtlRule` + `RedisHashTagRule` from `like-rate-limit` already cover the new daily-key call site. No rule-side changes.
- Re-litigating the rate-limit-infrastructure contract — that capability's spec is canonical (Decisions 1–7 in `like-rate-limit/design.md`); this change consumes it as-is.
- DB migrations — none required. State lives entirely in Redis.

## Decisions

### Decision 1 — One limiter, daily only (no burst)

**Choice.** The chat send handler runs exactly **one** rate limiter: a daily window keyed `{scope:rate_chat_send_day}:{user:<uuid>}`, capacity 50 (or `premium_chat_send_cap_override` if set), TTL via `computeTTLToNextReset(userId)`. No second limiter — no rolling-hour burst.

**Why.** [`docs/02-Product.md:318`](../../../docs/02-Product.md) defines chat caps as "Free 50/day, Premium unlimited" — no burst clause. [`docs/02-Product.md:223`](../../../docs/02-Product.md) defines like caps as "Free 10/day, Premium unlimited (both tiers cap 500/hour burst)" — burst is explicitly called out for likes only. [`docs/02-Product.md:224`](../../../docs/02-Product.md) defines reply caps daily-only, and `reply-rate-limit` shipped that shape. The asymmetry is canonical, not an oversight: chat is a 2000-char compose surface (as is reply at 280 chars), where each row carries real per-row authoring effort, vs likes which are zero-effort click-events that invite bot-velocity-fingerprint mitigation. Following the canonical line, the chat send handler ships with daily-only — same shape as reply.

**Alternatives considered.**

- **Add a 100/hour or 60/hour burst on chat sends anyway**: would be a divergence from canonical docs without a concrete anti-abuse signal. Rejected — divergence belongs in a separate proposal with its own rationale.
- **Reuse the like burst limiter (500/hour) on chat sends**: numerically too high to be meaningful for chat (a user who can send 500 messages in an hour is hitting daily cap 10× over first), and conceptually wrong (burst is anti-bot for high-velocity actions; chat isn't that). Rejected.
- **A weekly cap instead of daily**: divergence from the canonical line. Rejected.

### Decision 2 — `premium_chat_send_cap_override` mirrors `premium_like_cap_override` / `premium_reply_cap_override` byte-for-byte

Add a new Remote Config integer flag `premium_chat_send_cap_override`, plumbed through the existing `:infra:remote-config` module. Behavior matches the like and reply override flags exactly: read on every Free-tier request, default 50 when unset / malformed / ≤ 0 / SDK error, oversized values (> 10,000) fall back to the default 50 (anti-typo guard, matches the precedents — 10,000 is the threshold that triggers the fallback, not a clamp value), applied at request time (mid-day flips bind immediately), does NOT affect Premium (which skips the daily limiter entirely). The canonical contract for this kind of flag lives at [`docs/05-Implementation.md:1416`](../../../docs/05-Implementation.md) (the Remote Config flag definitions section), with secondary cross-reference in `08-Roadmap-Risk.md` Decision 28. Per the like-rate-limit and reply-rate-limit precedents, separate dials per write-action let ops tighten/loosen one cap without affecting the other.

The flag name follows the established `premium_<scope>_cap_override` convention (`like` → `like`, `reply` → `reply`, `chat send` → `chat_send`). The `_send` suffix disambiguates from a future hypothetical `premium_chat_create_cap_override` (conversation-create cap) without forcing a rename.

### Decision 3 — Tier (`subscription_status`) read from auth-time `viewer` principal, NOT a fresh DB SELECT

**Choice.** The chat send handler reads `subscription_status` from the request-scope `viewer` principal populated by the `auth-jwt` plugin (which already loads `subscription_status` alongside the user identity for every authenticated request). The handler MUST NOT issue a fresh `SELECT subscription_status FROM users WHERE id = :caller` before the limiter — doing so would violate the "limiter runs before any DB read" guarantee.

**Why.** Identical constraint to the like and reply handlers (see `reply-rate-limit/design.md` Decision 3). Adding a DB read would defeat the limiter-before-DB ordering, which is a correctness invariant: a Free user at the cap should get 429 without paying for a `users` round-trip.

**Defect-mode behavior.** If `viewer.subscriptionStatus` is unexpectedly `null` (auth path defect), the chat send handler MUST treat the caller as Free and apply the cap — fail-closed against accidental Premium-tier escalation. The principal-loading defect itself MUST be fixed in the auth-jwt path, not worked around here. See the corresponding spec scenario in `specs/chat-conversations/spec.md` § Daily send-rate cap.

**Note on `UserPrincipal.subscriptionStatus` spec debt.** The field was added by `like-rate-limit` task 6.1.1 as a code change but no `auth-jwt` / `auth-session` spec was amended to document it. `reply-rate-limit` recorded the same debt. This change ALSO consumes the field but does NOT widen its scope to include an `auth-jwt` spec amendment — the debt remains tracked under the same `FOLLOW_UPS.md` line item created during `reply-rate-limit`.

**Alternatives considered.**

- **Read it once at handler entry**: same correctness violation in different clothes — still a DB read before the limiter. Rejected.
- **Cache it in Redis**: premature; the auth principal already carries it. Rejected.

### Decision 4 — No `releaseMostRecent` at the chat send call site

**Choice.** The chat send handler calls `RateLimiter.tryAcquire` exactly once per request and never calls `releaseMostRecent`. Every successful slot acquisition is permanent — there is no "no-op idempotent" path to release back into the bucket.

**Why.** The like handler's release escape hatch exists because `INSERT ... ON CONFLICT (post_id, user_id) DO NOTHING` returning zero rows means the request didn't change DB state and shouldn't burn a slot. `chat_messages` has no equivalent UNIQUE constraint — every successful POST is a real new row, even if the user sends the same content twice in a row. There's no "already-sent" no-op to release on. The 404 `conversation_not_found`, 403 `not_a_participant`, and 403 `blocked` paths also do not release (matches the like and reply precedents: post-limiter business decisions count toward the cap as anti-abuse).

**Alternatives considered.**

- **Release on 403 `blocked`**: would let a Free attacker probe blocked-conversation UUIDs without consuming slots — anti-abuse-negative. Rejected.
- **Release on `last_message_at` rollback** (the rare case where the chat-foundation transaction rolls back post-INSERT due to constraint surfacing): the slot would technically have been "consumed without producing a row." But the rollback path is genuinely exceptional, and burning one slot in that edge case is acceptable noise. The like and reply handlers also do not release on a rolled-back transaction. Skip the release call.

### Decision 5 — Limiter ordering: BEFORE participant/block/content checks

**Choice.** The order of operations on `POST /api/v1/chat/{conversation_id}/messages` is:

1. Auth (`auth-jwt` plugin) — 401 short-circuits.
2. Path UUID validation on `conversation_id` — 400 `invalid_uuid` short-circuits.
3. **Daily rate limiter** (Free only; Premium skips entirely) — 429 short-circuits.
4. Conversation existence + active-participant check (chat-foundation) — 404 `conversation_not_found` / 403 `not_a_participant` short-circuits.
5. Bidirectional block check (chat-foundation) — 403 `blocked` short-circuits with `"Tidak dapat mengirim pesan ke user ini"`.
6. JSON body parsing + chat-foundation 2000-char content-length guard + empty-content guard — 400 `invalid_request` if content empty / >2000 / missing.
7. INSERT `chat_messages` + UPDATE `conversations.last_message_at` (same transaction).
8. 201 response.

Steps 1–3 MUST run before any DB query. The 401, 400-uuid, and rate-limit branches MUST NOT execute a DB statement.

**Why.** Two constraints anchor this ordering:

1. **Limiter before DB.** Rate limiting is the cheapest filter; running a `conversation_participants` SELECT before the limiter wastes a DB round-trip on rate-limited callers. Same constraint as the like and reply handlers.
2. **Rate limiter before content-length guard, not after.** Putting the content guard before the limiter would let a Free attacker burn arbitrarily many "invalid_request" responses without consuming slots, then arrive at the cap with the bucket still empty. By rate-limiting FIRST, we make the cap binding on attempt count, not on success count. The reply handler shipped this exact ordering (`reply-rate-limit/design.md` Decision 5) and chat inherits it.

A rate-limited request never reaches participant lookup, never queries the block table, never parses content, and never inserts a row. Every post-limiter business rejection (404, 403, 400-content) DOES consume a slot — symmetric with like (404 consumes) and reply (404 + content-guard consumes).

**Lock-ordering note.** The send-message endpoint takes no advisory lock (only `POST /api/v1/conversations` does, for create-or-return). The limiter therefore has no lock-ordering interaction; it is a pure pre-DB Redis call.

**Alternatives considered.**

- **Participant/block check before limiter**: wastes DB round-trips on rate-limited callers. Rejected — same reason as the like and reply handlers.
- **Content guard before limiter** (i.e., reject empty/oversize content at JSON-parse time, then run the limiter): leaves an open window for an attacker to send 1000 oversized requests, none of which consume a slot, before sending one valid request that consumes slot 51. Rejected — same as reply.
- **Limiter strictly between block check and INSERT**: wastes the participant SELECT + block SELECT round-trips on rate-limited callers. Rejected.

### Decision 6 — Reuse the existing `:infra:remote-config` flag pattern, no new module work

**Choice.** Add a single new key constant `premium_chat_send_cap_override` to whatever shape `:infra:remote-config` exposes for `premium_like_cap_override` and `premium_reply_cap_override` (likely a typed accessor or a string-keyed reader). The chat send handler reads it via the existing client.

**Why.** `like-rate-limit` and `reply-rate-limit` already proved out the Remote-Config-override path: SDK error, malformed value, oversized value, ≤ 0, and unset all fall back to the canonical default. Reusing the exact pattern means zero design risk and one new key constant to register. The default-fallback and oversized-clamp contract is part of the existing `rate-limit-infrastructure` capability's behavior surface; this change does not extend that surface.

**Alternatives considered.**

- **Build a higher-level `RateLimitOverrideConfig` abstraction** with `like` / `reply` / `chat_send` keys: still premature abstraction; only three keys exist and their behavior is identical. The right time to abstract is when a fourth key needs a divergent fallback policy. Rejected.

### Decision 7 — `ChatSendRateLimitTest` as a new test class alongside `ChatSendRouteTest`

**Choice.** Add a new integration test class `ChatSendRateLimitTest` in `:backend:ktor`, tagged `database`, depending on the existing CI Redis service container that `like-rate-limit` introduced. The class covers the limiter behavior (cap enforcement, Premium skip, override flag, ordering, key shape, WIB rollover). The chat-foundation `ChatFoundationRouteTest` (or whatever the existing chat-foundation test class is named) is unmodified — its scenarios continue to assert the chat-foundation send-endpoint contracts byte-for-byte.

**Why.** Mirrors the `LikeRateLimitTest`-vs-`LikeEndpointsTest` and `ReplyRateLimitTest`-vs-`ReplyServiceTest` splits that `like-rate-limit` and `reply-rate-limit` shipped. Keeping the test classes separate keeps the chat-foundation baseline test class simple and lets the rate-limit test class focus on the new behavior. The CI Redis container is already on the test job from PR #37; no infra setup needed.

**Alternatives considered.**

- **Add the rate-limit scenarios to the chat-foundation test class**: bloats the foundation class with concerns it shouldn't carry; harder to grep "what does chat-foundation test cover" later. Rejected.
- **Skip the integration test class, only test the handler unit-style with an in-memory `RateLimiter` fake**: would miss Lua-script-level correctness (e.g., ZADD-then-EXPIRE-then-ZREMRANGEBYSCORE atomicity, hash-tag clustering on Upstash). The like-rate-limit and reply-rate-limit precedents are integration-against-real-Redis, and we're following them. Rejected.

## Risks / Trade-offs

- **[Risk] Chat-foundation `last_message_at` UPDATE is in the same DB transaction as the `chat_messages` INSERT.** A constraint surfacing failure rolls back both. → The rate limiter runs BEFORE the transaction, so a rolled-back transaction still consumes a slot. This is intentional: a request that successfully passed the limiter and then hit a transient downstream failure should not be free-retried; the next attempt counts toward the cap. Same shape as the reply-rate-limit V10-rollback risk. Acceptable noise.
- **[Risk] Premium → Free downgrade window scoped by JWT TTL, not single request.** → Identical to the like-rate-limit and reply-rate-limit risk shapes. The `viewer.subscriptionStatus` is loaded at JWT issuance (per `like-rate-limit` task 6.1.1) — so subsequent requests with the same un-expired JWT continue to observe the stale Premium status until the JWT expires (15-minute TTL by default). The actual abuse window is `JWT TTL × send rate` (worst-case ~750 chat sends bypassed in one TTL window for a sustained-spam attacker, vs. the cap-bypass-by-one assumption). Mitigation depends on whether the RevenueCat downgrade webhook handler increments `token_version` on `EXPIRATION` (forcing JWT re-auth and a fresh principal load). If it does NOT, the window is bounded only by JWT TTL, not by next-request semantics. Acknowledged as a known gap; tracked under the same Open Question as `reply-rate-limit`.
- **[Risk] `premium_chat_send_cap_override` set to a value below the in-flight daily counter for a Free user mid-day.** → Same shape as the like and reply flags' analogous risk. The override is read at request time, not at midnight; users whose counter already exceeds the new override start getting 429 immediately. Acceptable — the flag is for tightening (anti-abuse) or loosening (UX retention) and the abrupt cutoff matches Decision 28's intent.
- **[Risk] Lettuce sync-API call blocks the Ktor coroutine dispatcher.** → Mitigation: the existing `RedisRateLimiter` impl already wraps the call in `withContext(Dispatchers.IO)` (per `like-rate-limit/design.md` Decision 1). The chat send handler's call site inherits this; no extra wrapping needed.
- **[Trade-off] One Redis round-trip per `POST /chat/{id}/messages` (vs two for `POST /like`).** → Lower per-request cost; same Lua-script execution shape; Upstash sub-millisecond SLA. No concern.
- **[Trade-off] No DB-side counter for per-day chat send usage.** → Intentional: Redis is authoritative for rate limits. The deployed `RedisRateLimiter` is **fail-soft** (per [PR #44](https://github.com/aditrioka/nearyou-id/pull/44) and the staging-soak lessons captured in `like-rate-limit/tasks.md` 9.7): if Lettuce can't reach Redis, the limiter logs `event=redis_connect_failed reason=... fail_soft=true` and returns `Allowed(remaining=capacity)` rather than crashing the request. The 30-second retry-suppression backoff in the limiter prevents log-flooding. The trade-off is that during a Redis outage the cap is bypassed (a Free user can send unlimited chat messages until Redis returns) — accepted because (a) Redis outages are visible via the `event=redis_connect_failed` log + Sentry, (b) the alternative (fail-closed = 503) would block all chat sends for all users including Premium, which is worse than a temporary cap bypass during a known outage, and (c) the limiter is part of a defense-in-depth stack — the daily cap is not the only abuse backstop (block, report, future Perspective API screening all layer in).
- **[Trade-off] Lua atomicity under MAXMEMORY pressure.** → Inherited from `rate-limit-infrastructure` capability — Redis Lua scripts are atomic by construction; OOM under MAXMEMORY pressure surfaces as a script-call-boundary error and the limiter propagates as a 503 (fail-closed for OOM specifically, distinct from the connect-failure fail-soft path above). No partial-state failure mode.
- **[Documented expectation] Slot consumption on 404 `conversation_not_found`, 403 `not_a_participant`, 403 `blocked`, 400 `content_too_long`, and 400 `empty_content` (post-limiter validation failures).** → All consume a slot. This matches the like-handler 404 behavior, the reply-handler 404 + content-guard behavior, and is intentional anti-abuse. A Free attacker probing non-existent conversation UUIDs OR sending oversized content OR sending to blocked recipients gets exactly 50 attempts per day, same as a legitimate user. Documented here so future reviewers don't mistake it for a bug.
- **[Out of scope, but worth recording] No rate limit on `POST /api/v1/conversations`.** → Conversation creation is rare (one row pair per user-pair lifetime), already serialized by the user-pair advisory lock from chat-foundation, and not a per-user-velocity abuse vector. A hypothetical future "create N conversations/day" cap, if needed, would be a separate change.
- **[Out of scope, but worth recording] No rate limit on the future admin redaction endpoint.** → Phase 3.5 admin-panel endpoint, not user-facing.

## Migration Plan

This is **infra-additive** — no DB migration, no breaking API change.

**Steps:**

1. Land the `premium_chat_send_cap_override` flag plumbing in `:infra:remote-config` (one new key constant alongside the like and reply override keys).
2. Land the chat send handler rate-limit integration: limiter call before the chat-foundation participant lookup / block check / content guard / INSERT, 429 envelope on `RateLimited`, Premium-tier skip via `viewer.subscription_status`.
3. Land `ChatSendRateLimitTest` (database-tagged) covering the cap, the Premium skip, the override flag, the ordering, the key shape, and WIB rollover — mirrors `LikeRateLimitTest` and `ReplyRateLimitTest` scenario-by-scenario.

All three live in one feat commit on the same change branch (per the one-PR-per-change convention in [`openspec/project.md`](../../../openspec/project.md)).

**Rollout:**

- Cloud Run deploy uses the existing `KTOR_REDIS_URL` resolution (already provisioned and proven by `like-rate-limit` and `reply-rate-limit`).
- Staging soak: 24 hours. Smoke test: a Free synthetic account hits 50 chat sends, 51st returns 429 with `Retry-After`, then waits the staggered window and the 52nd passes. The smoke script can be derived from `dev/like-rate-limit-smoke.sh` (the script that landed in PR [#41](https://github.com/aditrioka/nearyou-id/pull/41) for `like-rate-limit`), parameterized for the chat send endpoint.
- Prod ship via tag `v*`. The `premium_chat_send_cap_override` flag stays unset at first (50/day Free baseline) — adjust if D7 metrics signal retention impact.

**Rollback:**

- The chat send cap is gated on the existence of the limiter call in the chat send handler. Revert is a clean PR revert: removing the limiter call from the chat send handler immediately restores chat-foundation behavior. The `premium_chat_send_cap_override` flag plumbing in `:infra:remote-config` is harmless if left in (an unread key); cleanup can ship in a follow-up.

## Resolved Sub-Decisions

- **`premium_chat_send_cap_override` does NOT apply to Premium users.** Premium skips the daily limiter entirely (matches the like and reply flag behavior). The override is for Free-tier dial adjustment only. If Premium ever needs a cap (e.g., abuse signal from a single Premium account), that's a separate ban/suspension decision, not a Remote Config dial.
- **The daily cap counts INSERTs, not net chat messages — redacted-by-admin messages still consume the original sender's slot.** A user who sends a message and an admin then redacts it DOES NOT get the slot back. This matches the like cap's "INSERT-counted, unlike doesn't release" rule and the reply cap's "INSERT-counted, soft-delete doesn't release" rule. The cap is predictable: every successful POST consumes one slot, regardless of downstream moderation. Documented as a scenario in the chat-conversations spec delta.
- **Pre-existing divergence to flag:** none found at scoping time. The chat-cap line is consistent across `02-Product.md:318` (Free 50/day, Premium unlimited) and `05-Implementation.md` Layer 2 table. The `chat-foundation` proposal explicitly named `chat-rate-limit` as the follow-up consumer of `rate-limit-infrastructure`, with the 50/day cap canonically sourced. If reconciliation surfaces during the review loop or `/opsx:apply`, file under `FOLLOW_UPS.md`.

## Open Questions

- **JWT TTL window vs RevenueCat `token_version` bump.** See the Premium-downgrade risk above; the bound depends on whether the RevenueCat handler bumps `token_version` on downgrade events. Same Open Question as `reply-rate-limit`. Resolution deferred to `/opsx:apply` task 1.5 (auth-path verification).
- **Mobile 429 handling on the chat-compose surface.** The proposal notes mobile already handles 429 generically (timeline rate-limit screen) and the like/reply button surfaces from PR #37 / PR #49 are wired. Whether the chat-compose surface in the current mobile build correctly handles a 429 from `POST /chat/{id}/messages` (or 5xx-translates / crashes) is unverified at proposal time. Resolution deferred to `/opsx:apply` task 1.6.
