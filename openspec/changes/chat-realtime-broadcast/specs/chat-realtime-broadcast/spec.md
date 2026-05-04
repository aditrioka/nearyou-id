## ADDED Requirements

### Requirement: ChatRealtimeClient domain interface

The system SHALL define a `ChatRealtimeClient` interface in `:core:domain` exposing a `publish` operation. The interface SHALL NOT depend on any vendor SDK; per `CLAUDE.md` § Critical invariants, vendor SDK imports are forbidden outside `:infra:*`.

The interface SHALL declare:

```kotlin
interface ChatRealtimeClient {
    suspend fun publish(
        conversationId: UUID,
        message: ChatMessageBroadcast,
    ): PublishResult
}

data class ChatMessageBroadcast(
    val id: UUID,
    val conversationId: UUID,
    val senderId: UUID,
    val content: String?,
    val embeddedPostId: UUID?,
    val embeddedPostSnapshot: JsonElement?,
    val embeddedPostEditId: UUID?,
    val createdAt: Instant,
    val redactedAt: Instant?,
)

sealed class PublishResult {
    object Success : PublishResult()
    data class Failure(val reason: String) : PublishResult()
}
```

The future `KtorWebSocketChatClient` (post-swap, Month 15+) SHALL implement the same interface so the swap is mechanical.

#### Scenario: Interface lives in :core:domain
- **WHEN** the project is built
- **THEN** `ChatRealtimeClient`, `ChatMessageBroadcast`, and `PublishResult` are declared in the `:core:domain` Gradle module AND no Supabase SDK class is imported by that module

#### Scenario: Vendor SDK isolation enforced
- **WHEN** a static scan runs over `:core:domain` source for any `io.supabase.*` or `io.github.jan-tennert.supabase.*` import
- **THEN** zero matches are found

### Requirement: SupabaseBroadcastChatClient implementation in :infra:supabase

The system SHALL provide a `SupabaseBroadcastChatClient` class in a new Gradle module `:infra:supabase`. The class SHALL implement `ChatRealtimeClient`. The class SHALL be the only implementation registered in DI for the `dev`, `staging`, and `production` environments during the pre-swap period (Months 1–14).

The new module SHALL be added to [`settings.gradle.kts`](../../../../../settings.gradle.kts) AND a one-line description SHALL be added to [`dev/module-descriptions.txt`](../../../../../dev/module-descriptions.txt) AND `dev/scripts/sync-readme.sh --write` SHALL run successfully (per `CLAUDE.md` § Critical invariants — root README module list is auto-generated).

#### Scenario: Module exists and registers the implementation
- **WHEN** the project's DI container resolves `ChatRealtimeClient`
- **THEN** the resolved binding is `SupabaseBroadcastChatClient` (defined in `:infra:supabase`)

#### Scenario: README module list reflects the new module
- **WHEN** `dev/scripts/sync-readme.sh --check` runs
- **THEN** the script exits 0 (no drift) AND the root README's auto-generated module list contains a row for `:infra:supabase` with the description sourced from `dev/module-descriptions.txt`

### Requirement: Channel name format

`SupabaseBroadcastChatClient.publish(conversationId, message)` SHALL pass to the Supabase Realtime client a CHANNEL identifier of exactly `realtime:conversation:<conversation_id>` where `<conversation_id>` is the lowercase UUID with hyphens (canonical RFC 4122 textual form). The `realtime:` prefix is a Supabase client-API requirement for authorization-gated channels. The underlying realtime TOPIC that the V15 `participants_can_subscribe` RLS policy regex evaluates against is `conversation:<conversation_id>` — Supabase strips the `realtime:` prefix before topic evaluation. The topic regex is the canonical anchored form `^conversation:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$` from [V2__auth_foundation.sql:75](../../../../../backend/ktor/src/main/resources/db/migration/V2__auth_foundation.sql) (carried unchanged into the V15 install per `chat-conversations` § Realtime RLS test set). The looser form `^conversation:[0-9a-f-]{36}$` is NOT canonical — it would silently match malformed strings and MUST NOT be used in tests or implementation.

#### Scenario: Channel name uses canonical UUID form
- **GIVEN** `conversationId = UUID.fromString("11111111-2222-3333-4444-555555555555")`
- **WHEN** `publish` is invoked
- **THEN** the channel name passed to the Supabase Realtime client is exactly `"realtime:conversation:11111111-2222-3333-4444-555555555555"`

#### Scenario: Channel name uses lowercase
- **GIVEN** `conversationId` is a UUID whose default `toString()` representation is lowercase (Java's `UUID.toString()` always emits lowercase)
- **WHEN** `publish` is invoked AND the channel name is captured
- **THEN** the channel name does NOT contain any uppercase letter

#### Scenario: Channel topic matches V15 RLS policy regex
- **GIVEN** the channel name produced by `publish` for any well-formed `conversationId`
- **WHEN** the channel name has its leading `realtime:` prefix stripped to obtain the realtime TOPIC (matching what `realtime.topic()` returns inside the policy body), AND the resulting topic is tested against the V15 regex `^conversation:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`
- **THEN** the regex matches

#### Scenario: Loose regex form rejected for tests
- **WHEN** any spec scenario or test asserts a regex match against the channel topic
- **THEN** the regex literal used is the canonical anchored form `^conversation:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$` — NEVER `^conversation:[0-9a-f-]{36}$` (which would match malformed strings such as `conversation:------------------------------------`)

### Requirement: Payload schema

The broadcast payload SHALL be a JSON object with the following keys, mirroring the `chat_messages` columns surfaced by `GET /api/v1/chat/{id}/messages`:

```
{
  "id": "<message uuid>",
  "conversation_id": "<conversation uuid>",
  "sender_id": "<sender uuid>",
  "content": "<string or null when redacted>",
  "embedded_post_id": null,
  "embedded_post_snapshot": null,
  "embedded_post_edit_id": null,
  "created_at": "<ISO-8601 UTC>",
  "redacted_at": null
}
```

The payload SHALL NOT contain `redaction_reason` (matches the `chat-conversations` read-path render policy which never serializes `redaction_reason`).

When `redacted_at IS NOT NULL`, the `content` field SHALL be `null`. The `redacted_at` field SHALL be the ISO-8601 UTC string of the redaction timestamp.

The three `embedded_*` fields SHALL be present with value `null` for every payload emitted by this change (the future `chat-embedded-posts` change populates them; this change ships forward-compatible serialization).

#### Scenario: Non-redacted message payload shape
- **GIVEN** a `ChatMessageBroadcast` with `content = "halo"`, `redactedAt = null`, all `embedded*` = null
- **WHEN** `publish` serializes the payload
- **THEN** the JSON object emitted contains exactly the nine top-level keys (`id`, `conversation_id`, `sender_id`, `content`, `embedded_post_id`, `embedded_post_snapshot`, `embedded_post_edit_id`, `created_at`, `redacted_at`); `content == "halo"`; `redacted_at == null`; the three `embedded_*` keys are present with value `null`; `redaction_reason` is NOT present

#### Scenario: Redacted message payload shape
- **GIVEN** a `ChatMessageBroadcast` with `content = "halo"` (in-memory) but `redactedAt != null`
- **WHEN** `publish` serializes the payload
- **THEN** the JSON object's `content` field is `null` (not the original "halo"); `redacted_at` is the ISO-8601 string of the redaction time; `redaction_reason` is NOT present

#### Scenario: Forward-compat embedded fields are present-with-null
- **WHEN** any payload is serialized by this change
- **THEN** the JSON object contains all three keys `embedded_post_id`, `embedded_post_snapshot`, `embedded_post_edit_id` with value `null` (presence with null, not absence)

### Requirement: Publish-side shadow-ban skip

When the message sender has `users.is_shadow_banned = TRUE`, the chat send handler SHALL NOT invoke `ChatRealtimeClient.publish(...)`. The `chat_messages` row still persists (per `chat-conversations` invisible-actor model). No Supabase Realtime broadcast is emitted for messages from shadow-banned senders.

The handler SHALL determine sender shadow-ban state from `viewer.isShadowBanned` on the request-scope `UserPrincipal` populated by the auth-jwt plugin. `isShadowBanned: Boolean` is a new field added to `UserPrincipal` by this change (`AuthPlugin.configureUserJwt` loads it from `users.is_shadow_banned` in the same auth-time SELECT that already pulls `subscription_status` per the like-rate-limit precedent). The handler SHALL NOT issue an additional `SELECT is_shadow_banned FROM users` for the publish decision — the principal's auth-time value is authoritative for the publish-skip decision (mid-flight admin flips between auth and publish are accepted per the design § D2 risk; mobile consumer-side filter at `docs/05-Implementation.md:1880` provides defense-in-depth against the race).

#### Scenario: Shadow-banned sender's message persists but does not broadcast
- **GIVEN** Free caller A has `users.is_shadow_banned = TRUE` (and `viewer.isShadowBanned = TRUE` on the request principal) AND is an active participant of conversation X with B; no `user_blocks` row in either direction
- **WHEN** A calls `POST /api/v1/chat/X/messages { content: "halo" }`
- **THEN** the response is HTTP 201 (chat-foundation invisible-actor contract preserved); the row exists in `chat_messages` with `sender_id = A`; `ChatRealtimeClient.publish(...)` is NOT invoked (verified via test-double `FakeChatRealtimeClient.publishInvocations.isEmpty()` for this request)

#### Scenario: Non-shadow-banned sender's message both persists and broadcasts
- **GIVEN** caller A has `users.is_shadow_banned = FALSE` (and `viewer.isShadowBanned = FALSE`) AND is an active participant of conversation X with B; no `user_blocks` row
- **WHEN** A successfully sends a message
- **THEN** the response is HTTP 201; `ChatRealtimeClient.publish(...)` is invoked exactly once with the message's `id`, `conversation_id`, `sender_id`, `content`, `created_at` matching the persisted row

#### Scenario: Publish-skip reads from principal, not a fresh DB SELECT
- **GIVEN** caller A's request principal has `viewer.isShadowBanned = TRUE`
- **WHEN** A successfully sends a message
- **THEN** the chat send handler does NOT execute any `SELECT is_shadow_banned FROM users` for the publish decision (verifiable via JDBC statement spy or test that the only user-row read on the request path is the auth-time one already performed by `AuthPlugin.configureUserJwt`)

#### Scenario: Shadow-ban skip race tolerance (D2)
- **GIVEN** caller A's request principal has `viewer.isShadowBanned = FALSE` (state at auth time) AND a moderator flips `users.is_shadow_banned = TRUE` for A in a separate connection AFTER auth completed but BEFORE the handler reaches the publish step
- **WHEN** A's send completes (the handler reads `viewer.isShadowBanned = FALSE` from the principal — stale)
- **THEN** the response is HTTP 201 AND `ChatRealtimeClient.publish(...)` IS invoked (the in-flight message slips through; D2 explicitly accepts this — mobile consumer-side filter provides defense-in-depth for the race window). All subsequent sends from A in new requests will see `viewer.isShadowBanned = TRUE` (loaded fresh per-request) and correctly skip publish

### Requirement: Post-commit publish dispatch

The chat send handler SHALL invoke `ChatRealtimeClient.publish(...)` AFTER the chat-foundation `INSERT chat_messages` + `UPDATE conversations.last_message_at` transaction commits. Publish SHALL NOT occur inside the transaction. Publish SHALL receive the inserted row's projection (id, conversation_id, sender_id, content, embedded_post_*, created_at, redacted_at = null because this code path is for new sends only).

If the chat-foundation transaction fails (constraint surfacing, rollback, etc.), publish SHALL NOT be invoked.

#### Scenario: Publish runs after commit
- **GIVEN** caller A successfully sends a message in conversation X
- **WHEN** the request handler completes
- **THEN** the `chat_messages` INSERT has committed AND the `conversations.last_message_at` UPDATE has committed AND `ChatRealtimeClient.publish` was invoked exactly once AFTER both commits (verifiable via test-double that captures wall-clock invocation timestamps + the row's final visibility in a separate connection's SELECT)

#### Scenario: Publish skipped on tx rollback
- **GIVEN** caller A attempts to send a message AND the chat-foundation transaction rolls back (a constraint surfacing causes both INSERT and UPDATE to roll back)
- **WHEN** the request completes
- **THEN** `ChatRealtimeClient.publish` was NOT invoked AND zero `chat_messages` rows persist

### Requirement: Broadcast failure does NOT roll back persisted INSERT

If `ChatRealtimeClient.publish(...)` returns `PublishResult.Failure(reason)` OR throws after the chat-foundation transaction has committed, the chat send handler SHALL:

1. NOT roll back the persisted `chat_messages` row.
2. NOT roll back the `conversations.last_message_at` UPDATE.
3. Return HTTP 201 with the unchanged chat-foundation response shape.
4. Log a structured WARN line with `event = "chat_realtime_publish_failed"`, `conversation_id = <uuid>`, `message_id = <uuid>`, `error_class = <exception class name or "Failure" if Failure was returned>`.

#### Scenario: Publish failure with persisted row + 201 + WARN log
- **GIVEN** the test-double `FakeChatRealtimeClient.publish` is configured to return `Failure("simulated outage")`
- **WHEN** caller A successfully INSERTs a chat_messages row (tx commits) AND publish is then invoked AND returns `Failure`
- **THEN** the HTTP response is 201 (NOT 5xx); the `chat_messages` row remains persisted; `conversations.last_message_at` reflects the new send; a WARN log line is emitted with `event = "chat_realtime_publish_failed"`, the matching `message_id` and `conversation_id`, and `error_class = "Failure"`

#### Scenario: Publish exception with persisted row + 201 + WARN log
- **GIVEN** `FakeChatRealtimeClient.publish` is configured to throw `IOException("Connection refused")`
- **WHEN** caller A successfully sends a message AND publish throws
- **THEN** the HTTP response is 201; the row persists; a WARN log line is emitted with `error_class = "IOException"`

#### Scenario: Publish failure does not corrupt last_message_at
- **GIVEN** conversation X has `last_message_at = T1` AND publish is configured to fail
- **WHEN** caller A successfully sends a message at clock time T2 > T1
- **THEN** after the request completes, `conversations.last_message_at` for X is T2 (the chat-foundation atomicity contract is preserved; publish failure does not touch it)

### Requirement: 4 total attempts (1 initial + 3 retries) with exponential backoff inside SupabaseBroadcastChatClient

`SupabaseBroadcastChatClient.publish(...)` SHALL perform up to 4 total attempts on transient failure (network IOException, timeout, HTTP 5xx response from Supabase) — 1 initial attempt followed by up to 3 retries. Backoff sleeps occur BEFORE retry attempts 2, 3, and 4: ~100ms, ~300ms, ~900ms (3 inter-attempt sleeps for 4 total attempts; mathematically consistent). After the 4th attempt fails, the method SHALL return `PublishResult.Failure(reason)` carrying the last error class name. This matches the canonical contract from [`docs/05-Implementation.md:1213-1214`](../../../../../docs/05-Implementation.md) ("Ktor retries broadcast 3x with exponential backoff") under the natural reading "3 retries = 4 total attempts."

The retry policy SHALL be encapsulated inside the infra adapter; the chat handler SHALL invoke `publish` exactly once per send and treat the returned `PublishResult` as final.

#### Scenario: Single transient failure recovers
- **GIVEN** `SupabaseBroadcastChatClient` is wired with a stub Supabase client that fails the first attempt with a 503 then succeeds on the second
- **WHEN** `publish` is invoked
- **THEN** the method returns `PublishResult.Success` AND the stub client recorded exactly 2 attempts AND the chat handler observed exactly one return value

#### Scenario: All 4 attempts fail returns Failure
- **GIVEN** the stub Supabase client fails every call with a 503
- **WHEN** `publish` is invoked
- **THEN** the method returns `PublishResult.Failure` AND the stub client recorded exactly 4 attempts

#### Scenario: Backoff timing approximately matches schedule
- **GIVEN** the stub Supabase client fails every call with a 503 AND the test captures the wall-clock timestamp of each attempt
- **WHEN** `publish` is invoked AND all 4 attempts complete
- **THEN** the gaps between attempts approximate (within ±50ms tolerance for clock + scheduler jitter) the schedule: attempt2 - attempt1 ≈ 100ms; attempt3 - attempt2 ≈ 300ms; attempt4 - attempt3 ≈ 900ms

#### Scenario: Retry policy not visible at handler level
- **WHEN** the chat send handler invokes `chatRealtimeClient.publish(...)` via the test-double `FakeChatRealtimeClient`
- **THEN** the handler code makes exactly one call (no handler-level loop); retry semantics are owned by the adapter

### Requirement: OTel span around publish

`SupabaseBroadcastChatClient.publish(...)` SHALL emit an OpenTelemetry span named `chat.realtime.publish` with attributes:

- `supabase.realtime.channel = realtime:conversation:<conversation_id>` (mandatory per [`docs/04-Architecture.md:398`](../../../../../docs/04-Architecture.md))
- `user_id` set to the hashed sender id via the existing hashing helper (mandatory per [`docs/04-Architecture.md:398`](../../../../../docs/04-Architecture.md))
- `chat.message_id = <message uuid>` (NOT hashed — message UUIDs are opaque single-use identifiers with no aggregation surface, unlike `user_id` which is a stable cross-event correlator)

Span status SHALL be `OK` on `Success` and `ERROR` (with `error.type` attribute = exception class name) on `Failure` or thrown exception.

The Supabase service role key SHALL NEVER appear as a span attribute, log field, HTTP request header that gets logged, or anywhere observable. Two enforcement scenarios apply: (1) literal-source-grep — any source-code occurrence of the literal `supabase-service-role-key` SHALL be only at the `secretKey(env, "supabase-service-role-key")` call site; (2) defense-in-depth — the resolved key VALUE itself SHALL NEVER appear in any attribute or log field captured during `chat.realtime.publish` execution.

#### Scenario: Span emitted with mandatory attributes on success
- **WHEN** `publish` is invoked successfully
- **THEN** an OTel span named `chat.realtime.publish` is recorded with attributes `supabase.realtime.channel`, `user_id`, `chat.message_id` populated; status is `OK`

#### Scenario: Span error on Failure
- **WHEN** `publish` returns `PublishResult.Failure` after retry exhaustion
- **THEN** the OTel span has status `ERROR` AND attribute `error.type` set to the last exception class name

#### Scenario: Service role key slot name appears only at secretKey call site
- **WHEN** searching the backend source for the literal string `"supabase-service-role-key"`
- **THEN** the only occurrences are calls to `secretKey(env, "supabase-service-role-key")`

#### Scenario: Service role key VALUE never appears in spans or logs
- **GIVEN** the resolved service role key value `K` (loaded at startup via `secretKey(env, ...)`) AND a test that captures all OTel span attributes + all log lines emitted during a `publish` invocation (across both success and failure paths)
- **WHEN** the captured attributes and log fields are scanned for `K` (substring match)
- **THEN** `K` does NOT appear in any captured attribute value or log message

### Requirement: Credential resolution via secretKey helper

`SupabaseBroadcastChatClient` SHALL obtain its Supabase service role key via `secretKey(env, "supabase-service-role-key")` at startup. The helper resolves to GCP Secret Manager slot `staging-supabase-service-role-key` in staging and `supabase-service-role-key` in production per the env-aware naming convention from `CLAUDE.md` § Critical invariants.

The credential SHALL be loaded once at startup and held in the Supabase SDK client instance. The credential SHALL NOT be refreshed mid-process; rotation requires a process restart (matches the existing precedent for other long-lived credentials such as `supabase-jwt-secret`).

**Non-goal: the publish path bypasses RLS by design.** The Supabase service role key is RLS-bypassing — that's its intended property at this call site. The V15 `participants_can_subscribe` RLS policy gates SUBSCRIBERS, not the server-originated publisher. A future maintainer reading this spec MUST NOT attempt to "tighten" the publish path by switching to the anon key or the HS256 client token; both would fail to authorize a server-side broadcast. RLS gating at the subscriber side is sufficient for the privacy model; publish-side shadow-ban skip + bidirectional block check (in the chat handler from chat-foundation) provide the publisher-side equivalents.

#### Scenario: secretKey helper used at single call site
- **WHEN** searching the backend source for `secretKey(env, "supabase-service-role-key")`
- **THEN** there is exactly one call site (the `SupabaseBroadcastChatClient` initializer or its DI provider)

#### Scenario: Hardcoded service role key forbidden
- **WHEN** searching the backend source for any non-`secretKey(...)` reference to a Supabase service role key value
- **THEN** zero occurrences are found

### Requirement: Integration test coverage

`ChatRealtimeBroadcastTest` (tagged `database`) SHALL exist alongside `ChatSendRouteTest` and `ChatSendRateLimitTest` in `:backend:ktor` and SHALL cover, at minimum:

1. Successful send invokes `publish` exactly once with the expected `ChatMessageBroadcast` payload (id, conversation_id, sender_id, content, created_at all match the persisted row).
2. Shadow-banned sender's send persists the row but does NOT invoke `publish`.
3. Publish failure returned by the test double does NOT roll back the persisted INSERT or `last_message_at` UPDATE; HTTP response is still 201; WARN log line emitted with the canonical fields.
4. Publish exception thrown by the test double has the same outcome as #3 (no rollback, 201, WARN log with `error_class = "<exception class name>"`).
5. Block-rejected send (`user_blocks` row in either direction) does NOT invoke `publish` (chat-foundation 403 path).
6. Rate-limited send (chat-rate-limit 51st attempt) does NOT invoke `publish` (chat-rate-limit 429 path).
7. 400 invalid_request (empty content) does NOT invoke `publish`.
8. 404 conversation_not_found does NOT invoke `publish`.
9. 403 not_a_participant does NOT invoke `publish`.
10. Channel name format: when `publish` is invoked, the captured `conversationId` argument's textual form satisfies the V15 RLS regex (canonical anchored form, NOT the loose `[0-9a-f-]{36}` form) when prefixed with `conversation:`.
11. Payload format: capture the `ChatMessageBroadcast` argument; assert all 9 fields are present (the three `embedded_*` are null for this change). Also assert at compile-time that `redaction_reason` is NOT a Kotlin field on `ChatMessageBroadcast` (e.g., reflection-based check or simply that the data class doesn't compile with that field — captured by the test asserting the field set is exactly the 9 documented fields).
12. Tx rollback on INSERT failure does NOT invoke `publish` (the rollback path tested by chat-foundation extends here).
13. **Post-commit ordering via separate JDBC connection**: a successful send is captured in two snapshots — (a) before the test-double's `publish` callback fires, a separate JDBC connection SELECTs the new row from `chat_messages` and finds it; (b) after the callback fires, the same SELECT still finds it. Proves publish runs strictly AFTER the chat-foundation tx commits and the row is externally visible.
14. **OTel span emission on success**: an in-memory OTel exporter (e.g., `InMemorySpanExporter`) captures the span emitted during a successful publish; assert span name = `chat.realtime.publish`, status = OK, and attributes `supabase.realtime.channel`, `user_id`, `chat.message_id` are populated with the expected values.
15. **OTel span emission on failure**: same setup, but the test-double's adapter is configured to fail; assert span status = ERROR, attribute `error.type` = the captured exception class name.
16. **Retry timing assertion**: when a stub adapter delays each attempt and the test captures wall-clock timestamps, the inter-attempt gaps approximate 100ms / 300ms / 900ms (±50ms tolerance) for 4 total attempts.
17. **D2 shadow-ban race**: a Free caller's principal has `viewer.isShadowBanned = FALSE` AND a moderator flips `users.is_shadow_banned = TRUE` (in a separate connection) BEFORE the handler reaches the publish step; assert the response is HTTP 201 AND `publish` IS invoked (stale-state-acceptable per D2). Then in a fresh request from the same user, the new principal has `viewer.isShadowBanned = TRUE` and `publish` is correctly NOT invoked.
18. **2000-char content boundary on broadcast path**: send a message with `content` of length exactly 2000 chars (the chat-foundation upper bound); assert HTTP 201 AND the captured broadcast payload's `content` field is exactly the 2000-char string (no truncation, no escaping artifacts).
19. **secretKey wiring**: a unit-level test (or DI-wiring test) asserts `SupabaseBroadcastChatClient` is constructed with the value returned from `secretKey(env, "supabase-service-role-key")` (verifiable via a test-injected mock `SecretResolver` that records the key it was queried for).
20. **Service-role-key not in span/log**: configure the OTel in-memory exporter + a `ListAppender`; trigger a success and a failure publish; scan all captured span attributes and log messages for the exact resolved key value; assert zero matches.

A separate test class `SupabaseBroadcastChatClientTest` (tagged `network`, gated to staging-smoke) SHALL exercise the real Supabase round-trip:

21. Real publish to a staging Supabase project succeeds.
22. Real publish failure (e.g., bad credential) returns `PublishResult.Failure` after retry exhaustion (4 attempts total).
23. **Payload deserialization round-trip**: a test subscriber on the same staging project subscribes to `realtime:conversation:<test-uuid>`, the publish is invoked, and the subscriber receives a payload that JSON-deserializes back to a 9-field shape with values matching the publisher input.

#### Scenario: ChatRealtimeBroadcastTest discoverable
- **WHEN** running `./gradlew :backend:ktor:test --tests '*ChatRealtimeBroadcastTest*'`
- **THEN** the class is discovered AND every numbered scenario 1–20 above corresponds to at least one `@Test` method

#### Scenario: SupabaseBroadcastChatClientTest discoverable when network credentials present
- **WHEN** running `./gradlew :infra:supabase:test --tests '*SupabaseBroadcastChatClientTest*'` on a runner with staging Supabase credentials configured
- **THEN** the class is discovered AND every numbered scenario 21–23 above corresponds to at least one `@Test` method

#### Scenario: SupabaseBroadcastChatClientTest gated to staging-smoke
- **WHEN** running `./gradlew :infra:supabase:test` on a CI runner without staging credentials
- **THEN** `SupabaseBroadcastChatClientTest` is skipped (network tag absent or credentials missing) AND the absence does not fail the build
