## ADDED Requirements

### Requirement: Daily send-rate cap — 50/day Free, unlimited Premium, with WIB stagger

`POST /api/v1/chat/{conversation_id}/messages` SHALL enforce a per-user **daily** rate limit of 50 successful chat-message INSERTs for Free-tier callers and unlimited for Premium-tier callers. The check runs via `RateLimiter.tryAcquire(userId, key, capacity, ttl)` against a Redis-backed counter keyed `{scope:rate_chat_send_day}:{user:<user_id>}`.

The TTL MUST be supplied via `computeTTLToNextReset(userId)` so the per-user offset distributes resets across `00:00–01:00 WIB` (per [`docs/05-Implementation.md`](../../../docs/05-Implementation.md) §1751-1755). Hardcoding `Duration.ofDays(1)` or any other fixed duration at this call site is a `RateLimitTtlRule` Detekt violation.

Tier gating reads `users.subscription_status` (V3 schema column, three-state enum). Both `premium_active` and `premium_billing_retry` (the 7-day grace state) MUST skip the daily limiter entirely. Only `free` (and any future state mapping to free) is subject to the cap.

**Read-site constraint.** Tier MUST be read from the request-scope `viewer` principal populated by the auth-jwt plugin (which loads `subscription_status` alongside the user identity for every authenticated request — added by `like-rate-limit` task 6.1.1; tracked as `auth-jwt` spec debt in `FOLLOW_UPS.md` since the field is not yet documented in any spec). The chat send handler MUST NOT issue a fresh `SELECT subscription_status FROM users WHERE id = :caller` before the limiter; doing so would violate the "limiter runs before any DB read" guarantee. If the auth principal does not carry `subscription_status`, that's a defect in the auth path and MUST be fixed there, not worked around by adding a DB read in this handler.

**Defect-mode behavior.** If `viewer.subscriptionStatus` is unexpectedly null, the handler MUST treat the caller as Free (fail-closed against accidental Premium-tier escalation) and apply the cap. This is a defensive guardrail; the underlying defect MUST still be fixed in the auth-jwt path.

The daily limiter MUST run BEFORE any DB read (specifically, before the chat-foundation conversation-existence + active-participant lookup, before the bidirectional block check, before the `chat_messages` INSERT, and before the `conversations.last_message_at` UPDATE) AND BEFORE the chat-foundation 2000-char content-length guard (so a Free attacker spamming oversized payloads still consumes slots and hits 429 at the cap, rather than burning unlimited "invalid_request" responses). On `RateLimited`, the response is HTTP 429 with body `{ "error": { "code": "rate_limited" } }` and a `Retry-After` header set to the seconds returned by the limiter (≥ 1).

The daily limiter MUST count successful slot acquisitions (i.e., requests that pass auth + UUID validation), not net chat messages. A message that an admin later redacts via the future `PATCH /admin/chat-messages/:id/redact` endpoint (Phase 3.5) MUST NOT release the original sender's slot — admin redaction is independent of rate-limit state and never decrements the daily counter.

#### Scenario: 50 chat sends within a day succeed
- **WHEN** Free-tier caller A successfully POSTs 50 distinct chat-message INSERTs within a single WIB day (each on an active conversation with a non-blocked recipient, with valid 1–2000 char content)
- **THEN** all 50 responses are HTTP 201

#### Scenario: 51st chat send in same day rate-limited
- **WHEN** Free-tier caller A has 50 successful chat sends in the current WIB day AND attempts a 51st
- **THEN** the response is HTTP 429 with `error.code = "rate_limited"` AND a `Retry-After` header carrying a positive integer AND no `chat_messages` row is inserted AND `conversations.last_message_at` is unchanged

#### Scenario: Retry-After approximates seconds to next reset
- **WHEN** the 51st request is rejected at WIB time `T`
- **THEN** the `Retry-After` value is approximately the number of seconds from `T` to (next 00:00 WIB) + (`hash(A.id) % 3600` seconds), within ±5 seconds (CI runner clock + Redis `TIME` skew tolerance, matches the like-rate-limit and reply-rate-limit precedents)

#### Scenario: Premium user not gated by daily cap
- **WHEN** caller A has `users.subscription_status = 'premium_active'` AND attempts a 100th chat send in a single WIB day
- **THEN** the response is HTTP 201 AND no daily-limiter check increments a counter for A (the daily limiter MUST be skipped, not consulted-and-overridden)

#### Scenario: Premium billing retry still treated as Premium
- **WHEN** caller A has `users.subscription_status = 'premium_billing_retry'` AND attempts a 75th chat send in a single WIB day
- **THEN** the response is HTTP 201 AND the daily limiter is skipped (75 chosen distinct from the 60 used in override scenarios and the 100 used in `premium_active`, so test fixtures don't accidentally exercise multiple code paths together)

#### Scenario: Daily key uses hash-tag format
- **WHEN** the daily limiter check runs against Redis for caller `A` (uuid `U`)
- **THEN** the key used is exactly `"{scope:rate_chat_send_day}:{user:U}"`

#### Scenario: Admin redaction does not release a daily slot
- **WHEN** within the same WIB day Free-tier caller A executes this sequence: (1) POSTs 5 successful chat sends (bucket grows 0 → 5), (2) an admin redacts one of those messages via the future redaction endpoint (the message row's `redacted_at`/`redacted_by` are set; bucket stays at 5 — admin redaction does NOT release), (3) POSTs 45 more successful chat sends (bucket grows 5 → 50), (4) POSTs a 51st chat send attempt
- **THEN** the 51st POST is rejected with HTTP 429 `error.code = "rate_limited"` AND the daily bucket size remains at 50 (the 51st acquisition was rejected, so no slot was added) AND the redacted message's slot was never released — proving admin moderation does not refund the cap

#### Scenario: Null subscriptionStatus on viewer principal treated as Free (defensive)
- **WHEN** the auth-jwt plugin populates `viewer` with `subscriptionStatus = null` (auth-path defect) AND Free-equivalent caller A attempts a 51st chat send in a day
- **THEN** the response is HTTP 429 (the handler MUST fall through to the Free-tier path, NOT skip the limiter). The implementation MUST also slf4j-WARN-log the defect (so the auth-path bug surfaces in monitoring), but the integration-test assertion is response status only — logging behavior is implementation-tested via service-level unit tests (mirrors the malformed-flag scenario hedge from `reply-rate-limit`).

#### Scenario: Daily limiter runs before participant lookup
- **WHEN** Free-tier caller A is at slot 51 AND attempts `POST /chat/{conversation_id}/messages` on a conversation A is NOT a participant in
- **THEN** the response is HTTP 429 with `error.code = "rate_limited"` (NOT 403 `not_a_participant`) AND no `conversation_participants` SELECT was executed

#### Scenario: Daily limiter runs before block check
- **WHEN** Free-tier caller A is at slot 51 AND attempts `POST /chat/{conversation_id}/messages` on a conversation where the recipient B has blocked A
- **THEN** the response is HTTP 429 with `error.code = "rate_limited"` (NOT 403 `blocked`) AND no `user_blocks` SELECT was executed

#### Scenario: Daily limiter runs before content-length guard
- **WHEN** Free-tier caller A is at slot 51 AND POSTs a chat message with 2001-character content
- **THEN** the response is HTTP 429 with `error.code = "rate_limited"` (NOT 400 `invalid_request`) AND no JSON body parsing or content-length validation occurred

#### Scenario: Daily limiter runs before unknown-conversation 404
- **WHEN** Free-tier caller A is at slot 51 AND POSTs `/chat/{nonexistent_uuid}/messages`
- **THEN** the response is HTTP 429 with `error.code = "rate_limited"` (NOT 404 `conversation_not_found`)

#### Scenario: WIB day rollover restores the cap
- **WHEN** Free-tier caller A is at slot 50 at WIB `23:59:00` AND a chat send is attempted at WIB `00:00:01 + (hash(A.id) % 3600)` seconds the next day
- **THEN** the response is HTTP 201 (the user-specific reset window has passed AND old entries are pruned)

### Requirement: `premium_chat_send_cap_override` Firebase Remote Config flag

The chat send service SHALL read the Firebase Remote Config flag `premium_chat_send_cap_override` on every `POST /api/v1/chat/{conversation_id}/messages` request from a Free-tier caller (Premium calls skip the daily limiter entirely, so the flag is irrelevant for them).

When the flag is unset, malformed, ≤ 0, or unavailable due to Remote Config error: the daily cap is `50` (the canonical default). When the flag is set to a positive integer N: the daily cap is `N`. When the flag is set to a value above 10,000 (clearly absurd / typo): the cap falls back to the default `50` (anti-typo guard, mirrors the like and reply override fallbacks — 10,000 is the threshold that triggers the fallback, not a clamp value applied to the override). The flag MUST mirror the `premium_like_cap_override` and `premium_reply_cap_override` Decision 28 contract from `like-rate-limit` and `reply-rate-limit` byte-for-byte (default-fallback shape, request-time read, mid-day flip-binds-immediately behavior).

#### Scenario: Flag unset uses 50 default
- **WHEN** `premium_chat_send_cap_override` is unset in Remote Config AND Free caller A attempts a 51st chat send in a day
- **THEN** the response is HTTP 429 (daily cap = 50)

#### Scenario: Flag = 60 raises the cap
- **WHEN** `premium_chat_send_cap_override = 60` AND Free caller A has 60 successful chat sends today AND attempts a 61st chat send
- **THEN** the 60th chat send (within the override) returned HTTP 201 AND the 61st returns HTTP 429 with `error.code = "rate_limited"`

#### Scenario: Flag = 10 lowers the cap mid-day
- **WHEN** Free caller A has 15 successful chat sends today (under the original 50 cap) AND `premium_chat_send_cap_override = 10` is set AND A attempts a 16th chat send
- **THEN** the response is HTTP 429 (the override applies at request time; users above the new cap are immediately rate-limited)

#### Scenario: Flag = 0 falls back to default
- **WHEN** `premium_chat_send_cap_override = 0` (invalid) AND Free caller A attempts a 51st chat send in a day
- **THEN** the response is HTTP 429 (the cap remains 50, not 0)

#### Scenario: Flag malformed (non-integer string) falls back to default
- **WHEN** `premium_chat_send_cap_override = "fifty"` (or any non-integer-parseable value returned by the Remote Config client) AND Free caller A attempts a 51st chat send in a day
- **THEN** the response is HTTP 429 (the cap remains 50). The implementation MUST also slf4j-WARN-log the malformed value so ops can detect the misconfiguration, but the integration-test assertion is the response status only (logging behavior is implementation-tested via service-level unit tests, not via a `ListAppender` in the HTTP-level test class)

#### Scenario: Flag oversized integer (above any sane cap) falls back to default
- **WHEN** `premium_chat_send_cap_override = Long.MAX_VALUE` or any positive value above 10,000 (clearly absurd) AND Free caller A attempts a 51st chat send
- **THEN** the response is HTTP 429 (the cap falls back to default 50). The upper-bound fallback prevents accidental cap removal via a typo (e.g., `5000000000` instead of `50`). Implementations MAY pick any specific threshold ≥ 10,000 (no abuse signal supports a Free user sending >10,000 chat messages/day). The implementation MUST also slf4j-WARN-log the oversized value, but the integration-test assertion is response status only (mirrors the malformed-flag scenario hedge).

#### Scenario: Remote Config network failure falls back to default
- **WHEN** the Remote Config SDK throws an `IOException` (or any error) when the chat send handler attempts to read `premium_chat_send_cap_override` AND Free caller A attempts a 51st chat send in a day
- **THEN** the response is HTTP 429 (the cap defaults to 50) AND the request does NOT 5xx — Remote Config errors MUST NOT propagate into a user-facing 5xx since the safe default is conservative (the canonical 50/day cap)

#### Scenario: Flag does NOT affect Premium
- **WHEN** `premium_chat_send_cap_override = 10` AND Premium caller A attempts a 16th chat send
- **THEN** the response is HTTP 201 (Premium skips the daily limiter entirely)

### Requirement: Limiter ordering and pre-DB execution on chat send

The chat send service SHALL run, in this exact order, on every `POST /api/v1/chat/{conversation_id}/messages`:

1. Auth (existing `auth-jwt` plugin).
2. Path UUID validation on `conversation_id` (existing — 400 `invalid_uuid` on malformed path).
3. **Daily rate limiter** (`{scope:rate_chat_send_day}:{user:<uuid>}`) — Free only; Premium skips. On `RateLimited`: 429 + `Retry-After` + STOP.
4. Conversation existence + active-participant check (existing chat-foundation — 404 `conversation_not_found` on missing row in `conversations`; 403 `not_a_participant` on non-participant or `left_at != NULL`).
5. Bidirectional block check (existing chat-foundation — 403 `blocked` with `"Tidak dapat mengirim pesan ke user ini"` on `user_blocks` row in either direction).
6. JSON body parsing + chat-foundation 2000-char content-length guard + empty-content guard (existing — 400 `invalid_request` on empty / whitespace-only / >2000 / missing / null `content`).
7. Chat-foundation `INSERT INTO chat_messages` and `UPDATE conversations SET last_message_at = NOW()` in the same DB transaction.
8. Return HTTP 201 with the chat-foundation response shape.

Steps 1–3 MUST run before any DB query. Steps 1–3 MUST NOT execute a DB statement. The chat send handler MUST NOT call `RateLimiter.releaseMostRecent` on any path — every successful slot acquisition is permanent (there is no idempotent re-action path on `chat_messages` analogous to the `INSERT ... ON CONFLICT DO NOTHING` no-op on `post_likes`).

The send-message endpoint takes NO advisory lock (only `POST /api/v1/conversations` takes the user-pair advisory lock from chat-foundation § "Slot-race serialization via two advisory locks"). The daily limiter therefore has no lock-ordering interaction; it is a pure pre-DB Redis call.

_Note: the "limiter runs before participant lookup", "limiter runs before block check", and "limiter runs before content-length guard" assertions live in the Daily send-rate cap requirement above — its dedicated short-circuit scenarios cover all three orderings. This requirement focuses on auth/UUID short-circuits BEFORE the limiter and slot-consumption rules AFTER the limiter._

#### Scenario: Auth failure short-circuits before limiter
- **WHEN** the request lacks a valid JWT
- **THEN** the response is HTTP 401 AND no limiter check runs (no Redis round-trip for auth-rejected requests)

#### Scenario: Invalid UUID short-circuits before limiter
- **WHEN** the request path is `POST /api/v1/chat/not-a-uuid/messages`
- **THEN** the response is HTTP 400 with `error.code = "invalid_uuid"` AND no limiter check runs

#### Scenario: 404 conversation_not_found consumes a slot
- **WHEN** Free caller A has 5 successful chat sends today AND POSTs `/chat/{nonexistent_uuid}/messages` with valid content
- **THEN** the response is HTTP 404 with `error.code = "conversation_not_found"` AND the daily bucket size is 6 (the slot was consumed before the 404 was decided) AND the limiter MUST NOT call `releaseMostRecent`

#### Scenario: 403 not_a_participant post-limiter consumes a slot
- **WHEN** Free caller A has 5 successful chat sends today AND POSTs `/chat/{conversation_id}/messages` on a conversation A is NOT an active participant in
- **THEN** the response is HTTP 403 AND the daily bucket size is 6 (the slot was consumed because the limiter ran before the participant check) AND the limiter MUST NOT call `releaseMostRecent`

#### Scenario: 403 blocked post-limiter consumes a slot
- **WHEN** Free caller A has 5 successful chat sends today AND POSTs `/chat/{conversation_id}/messages` on a conversation where the recipient B has blocked A
- **THEN** the response is HTTP 403 with body `{ "error": "Tidak dapat mengirim pesan ke user ini" }` AND the daily bucket size is 6 (the slot was consumed because the limiter ran before the block check) AND the limiter MUST NOT call `releaseMostRecent`

#### Scenario: 400 invalid_request post-limiter consumes a slot
- **WHEN** Free caller A has 5 successful chat sends today AND POSTs `/chat/{conversation_id}/messages` with empty content `{ "content": "" }` AND the conversation_id is a valid UUID belonging to a conversation A is an active participant in
- **THEN** the response is HTTP 400 with `error.code = "invalid_request"` AND the daily bucket size is 6 (the slot was consumed because the limiter ran before the content guard) AND the limiter MUST NOT call `releaseMostRecent`

#### Scenario: Successful chat send consumes a slot
- **WHEN** Free caller A has 5 successful chat sends today AND POSTs a fresh chat send on an active conversation with valid content
- **THEN** the response is HTTP 201 AND the daily bucket size is 6 AND `conversations.last_message_at` is updated

#### Scenario: Transaction rollback (chat_messages INSERT failure) does NOT release the slot
- **WHEN** Free caller A is at slot 5 AND POSTs a valid chat message AND the encompassing DB transaction rolls back (e.g., a constraint surfacing causes both the `chat_messages` INSERT and the `conversations.last_message_at` UPDATE to roll back together)
- **THEN** zero `chat_messages` rows persist AND `conversations.last_message_at` reverts to its pre-request value (chat-foundation atomicity contract preserved) AND the daily bucket size is 6 (the slot remains consumed; `releaseMostRecent` MUST NOT be called on the rollback path) AND the limiter response was `Allowed`, so a regression that adds a release on rollback would be caught by this scenario

#### Scenario: Shadow-banned sender's send consumes a slot identically to non-banned sender
- **WHEN** Free caller A has `is_shadow_banned = TRUE` AND has 5 successful chat sends today AND POSTs a fresh chat send on an active conversation with valid content
- **THEN** the response is HTTP 201 (chat-foundation invisible-actor model preserved — shadow-banned senders are allowed to send) AND the daily bucket size is 6 (shadow-ban state does NOT affect rate-limit accounting; the cap applies symmetrically to banned and non-banned senders)

### Requirement: GET /api/v1/chat/{conversation_id}/messages is NOT rate-limited at the per-endpoint layer

The chat-foundation list-messages endpoint (see `chat-conversations` § "List-messages endpoint") MUST NOT call `RateLimiter.tryAcquire`. Per-endpoint read-side rate limiting on GET `/messages` is explicitly deferred (matches the `like-rate-limit` and `reply-rate-limit` precedents which also did not rate-limit the corresponding GET endpoints); read-side throttling lives at the timeline session/hourly layer per [`docs/05-Implementation.md`](../../../docs/05-Implementation.md) (50/session soft + 150/hour hard).

#### Scenario: GET messages unaffected by daily chat send cap
- **WHEN** Free caller A is at slot 51 AND GETs `/api/v1/chat/{conversation_id}/messages` on an active conversation
- **THEN** the response is HTTP 200 with the chat-foundation cursor-paginated response shape AND no rate-limiter check occurred

### Requirement: GET /api/v1/conversations is NOT rate-limited at the per-endpoint layer

The chat-foundation list-conversations endpoint (see `chat-conversations` § "List-conversations endpoint") MUST NOT call `RateLimiter.tryAcquire`. Per-endpoint read-side rate limiting is explicitly deferred (matches the `like-rate-limit` and `reply-rate-limit` precedents); read-side throttling lives at the timeline session/hourly layer.

#### Scenario: GET conversations unaffected by daily chat send cap
- **WHEN** Free caller A is at slot 51 AND GETs `/api/v1/conversations`
- **THEN** the response is HTTP 200 with the chat-foundation cursor-paginated response shape AND no rate-limiter check occurred

### Requirement: POST /api/v1/conversations is NOT rate-limited at the per-endpoint layer

The chat-foundation create-or-return endpoint (see `chat-conversations` § "Create-or-return endpoint") MUST NOT call `RateLimiter.tryAcquire`. Conversation creation is rare (one row pair per user-pair lifetime), already serialized by the user-pair advisory lock, and is not a per-user-velocity abuse vector. A hypothetical future per-user "create N conversations/day" cap, if needed, would be a separate change.

#### Scenario: POST conversations unaffected by daily chat send cap
- **WHEN** Free caller A is at slot 51 AND POSTs `/api/v1/conversations { recipient_user_id: B }` where no prior conversation exists
- **THEN** the response is HTTP 201 with the chat-foundation create-or-return response shape AND no rate-limiter check occurred

### Requirement: Integration test coverage — chat send rate limit

`ChatSendRateLimitTest` (tagged `database`, backed by the `InMemoryRateLimiter` extracted in `like-rate-limit` task 7.1 — Lua-level correctness is exercised separately by `:infra:redis`'s `RedisRateLimiterIntegrationTest`) SHALL exist alongside the existing chat-foundation route test class and cover, at minimum:

1. 50 chat sends in a day succeed for Free user.
2. 51st chat send in the same day rate-limited; 429 + `Retry-After` + no `chat_messages` row inserted AND `conversations.last_message_at` unchanged (verified via row-count snapshot + column-value snapshot before/after the request).
3. `Retry-After` value within ±5s of expected (`now` frozen via `AtomicReference<Instant>` clock injected into `InMemoryRateLimiter`).
4. Premium user (status `premium_active`) skips the daily limiter — 100 chat sends in a day all succeed (chosen distinct from the 60 used in scenario 6 to avoid test-data ambiguity).
5. Premium billing retry status (`premium_billing_retry`) also skips the daily limiter (75 chat sends all succeed).
6. `premium_chat_send_cap_override` raises the cap to 60 for a Free user (61st rejected after a successful 60th).
7. `premium_chat_send_cap_override` lowers the cap mid-day; user previously at 15 is rejected at 16.
8. `premium_chat_send_cap_override = 0` falls back to default 50.
9. `premium_chat_send_cap_override = "fifty"` (malformed non-integer) falls back to default 50. The slf4j WARN log is implementation-detail; the integration test asserts response status only.
10. Remote Config network failure (SDK throws) falls back to default 50; no 5xx propagated.
11. 404 `conversation_not_found` consumes a slot (does NOT release): a request to a non-existent conversation UUID at slot 5 leaves the daily bucket at size 6.
12. 403 `not_a_participant` consumes a slot when caller passed auth + UUID validation: leaves the daily bucket at size 6.
13. 403 `blocked` consumes a slot when caller passed auth + UUID validation: leaves the daily bucket at size 6.
14. 400 `invalid_request` on **empty content** (`{ "content": "" }`) consumes a slot when caller passed auth + UUID validation: leaves the daily bucket at size 6. (Split from a single empty/whitespace/oversized scenario to ensure each rejection branch is independently exercised — an implementation that only handles one branch correctly should not pass.)
15. 400 `invalid_request` on **whitespace-only content** (`{ "content": "   " }`) consumes a slot when caller passed auth + UUID validation: leaves the daily bucket at size 6.
16. 400 `invalid_request` on **2001-character content** consumes a slot when caller passed auth + UUID validation: leaves the daily bucket at size 6.
17. Daily limit hit short-circuits before the chat-foundation participant check: at slot 51, POSTing to a conversation the caller is NOT a participant in returns HTTP 429 (NOT HTTP 403) — behavioral proof that the limiter ran before participant lookup.
18. Daily limit hit short-circuits before the chat-foundation block check: at slot 51, POSTing to a conversation where the recipient blocks the caller returns HTTP 429 (NOT HTTP 403) — behavioral proof that the limiter ran before block lookup.
19. Daily limit hit short-circuits before the chat-foundation content-length guard: at slot 51, POSTing 2001-char content returns HTTP 429 (NOT HTTP 400) — behavioral proof that the limiter ran before content validation.
20. Daily limit hit short-circuits before unknown-conversation 404: at slot 51, POSTing to a non-existent conversation UUID returns HTTP 429 (NOT HTTP 404).
21. Hash-tag key shape verified: daily key = `{scope:rate_chat_send_day}:{user:<uuid>}` (via a `SpyRateLimiter` test double that captures `tryAcquire` keys).
22. WIB rollover (single user): at the per-user reset moment the cap restores (frozen `AtomicReference<Instant>` clock advanced past `computeTTLToNextReset(userId)` + 1s).
23. WIB rollover (per-user offset is genuinely per-user): two distinct synthetic users `U1` and `U2` whose UUIDs hash to different `% 3600` offsets exhaust their caps at the same wall-clock moment; the `Retry-After` values returned to each (captured via `SpyRateLimiter`) differ by ≥ 1 second — proving the offset is applied per-user, not a global constant. (Test fixture MUST pick UUID values whose `hashCode() % 3600` is known to differ; assert the difference is at least 1 second to absorb any same-millisecond hash collisions if they ever occur — collision probability is `1/3600 ≈ 0.0003`, so the fixture deterministically picks non-colliding UUIDs.)
24. Admin redaction does NOT release a daily slot: POST 5 successful chat sends → simulate admin redaction on 1 (bucket stays at 5) → POST 45 more (bucket reaches 50) → 51st POST attempt rejected with HTTP 429 → bucket stays at 50.
25. GET `/messages` unaffected by the daily cap (caller at 51/50 still gets HTTP 200 from the chat-foundation list-messages endpoint).
26. GET `/conversations` unaffected by the daily cap (caller at 51/50 still gets HTTP 200 from the chat-foundation list-conversations endpoint).
27. POST `/conversations` unaffected by the daily cap (caller at 51/50 still gets HTTP 201 from the chat-foundation create-or-return endpoint).
28. Tier (`subscription_status`) is read from the auth-time `viewer` principal: a `SpyRateLimiter` confirms `tryAcquire` was invoked AND the response is HTTP 201 — combined with scenario 17 (limiter-before-participant-lookup), this proves no DB read sits between auth and limiter.
29. The chat send handler MUST NOT call `RateLimiter.releaseMostRecent` on any code path — assert via `SpyRateLimiter` that no `releaseMostRecent` invocation occurs across the full scenario set above.
30. Null `subscriptionStatus` on the viewer principal is treated as Free (defensive guardrail) — limiter applied, 51st request rejected with HTTP 429.
31. Transaction rollback does NOT release the slot: simulate a constraint surfacing failure that rolls back both the `chat_messages` INSERT and the `conversations.last_message_at` UPDATE; verify zero `chat_messages` rows persisted, `last_message_at` reverted to pre-request value, AND the daily bucket size still grew by 1 (no `releaseMostRecent` was called).
32. `premium_chat_send_cap_override = Long.MAX_VALUE` (or any value > 10,000) falls back to default 50.
33. Shadow-banned sender's send consumes a slot identically: a Free shadow-banned sender at slot 5 successfully sends one message, the daily bucket size becomes 6, and the response is HTTP 201 (the chat-foundation invisible-actor contract is preserved while the cap applies symmetrically).
34. Embedded-field silent-ignore + slot consumption: at slot 5 a Free caller A sends `POST /api/v1/chat/{conversation_id}/messages { content: "halo", embedded_post_id: <uuid> }`; the response is 201, the daily bucket size becomes 6 (exactly one slot consumed — not zero, not two), the inserted row has `embedded_post_id IS NULL`, AND a structured WARN log line records the ignored field with `event = "chat_send_embedded_field_ignored"` (matching the chat-foundation contract per `openspec/specs/chat-conversations/spec.md` § Send-message endpoint). The success-path assertion (slot 5 → slot 6 + WARN) proves the body IS parsed after the limiter passes. **The rejection-path assertion proves limiter-runs-before-body-parse**: at slot 51 the same request MUST return HTTP **429 specifically (NOT 422, NOT 400, NOT 201)** — a 422 / 400 differential would indicate the implementation parsed the body, validated/inspected the embedded fields, and rejected before reaching the limiter; the test MUST also assert via `SpyRateLimiter` that `tryAcquireInvocations == 1` AND zero `chat_send_embedded_field_ignored` WARN log lines were emitted on the 429 path (proving the body was never inspected for embedded fields on the rejected path).

#### Scenario: Test class discoverable
- **WHEN** running `./gradlew :backend:ktor:test --tests '*ChatSendRateLimitTest*'`
- **THEN** the class is discovered AND every numbered scenario above corresponds to at least one `@Test` method
