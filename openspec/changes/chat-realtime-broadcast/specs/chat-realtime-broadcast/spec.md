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
    val embeddedPostSnapshot: String?,
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

`SupabaseBroadcastChatClient.publish(conversationId, message)` SHALL emit the broadcast on a Supabase Realtime channel whose topic is exactly `realtime:conversation:<conversation_id>` where `<conversation_id>` is the lowercase UUID with hyphens (canonical RFC 4122 textual form). The format SHALL match the V15 `participants_can_subscribe` RLS topic regex `^conversation:[0-9a-f-]{36}$` (per `chat-conversations` § Realtime RLS test set runs against real schema and [V2__auth_foundation.sql:64-89](../../../../../backend/ktor/src/main/resources/db/migration/V2__auth_foundation.sql)) so that the V15-installed RLS policy authorizes matching subscribers.

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
- **WHEN** the channel name (with the leading `realtime:` prefix stripped to obtain the topic the policy regex matches against) is tested against the V15 regex `^conversation:[0-9a-f-]{36}$`
- **THEN** the regex matches

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

The handler SHALL determine sender shadow-ban state from the request-scope `viewer` principal populated by the auth-jwt plugin (the same field that powers the read-path filter in `chat-conversations` § List-messages endpoint). The handler SHALL NOT issue an additional `SELECT is_shadow_banned FROM users` for the publish decision.

#### Scenario: Shadow-banned sender's message persists but does not broadcast
- **GIVEN** Free caller A has `users.is_shadow_banned = TRUE` AND is an active participant of conversation X with B; no `user_blocks` row in either direction
- **WHEN** A calls `POST /api/v1/chat/X/messages { content: "halo" }`
- **THEN** the response is HTTP 201 (chat-foundation invisible-actor contract preserved); the row exists in `chat_messages` with `sender_id = A`; `ChatRealtimeClient.publish(...)` is NOT invoked (verified via test-double `FakeChatRealtimeClient.publishInvocations.isEmpty()` for this request)

#### Scenario: Non-shadow-banned sender's message both persists and broadcasts
- **GIVEN** caller A has `users.is_shadow_banned = FALSE` AND is an active participant of conversation X with B; no `user_blocks` row
- **WHEN** A successfully sends a message
- **THEN** the response is HTTP 201; `ChatRealtimeClient.publish(...)` is invoked exactly once with the message's `id`, `conversation_id`, `sender_id`, `content`, `created_at` matching the persisted row

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

### Requirement: 3x retry with exponential backoff inside SupabaseBroadcastChatClient

`SupabaseBroadcastChatClient.publish(...)` SHALL retry on transient failure (network IOException, timeout, HTTP 5xx response from Supabase) up to 3 times total (one initial attempt + 2 retries) with exponential backoff: ~100ms, ~300ms, ~900ms. After exhausting retries, the method SHALL return `PublishResult.Failure(reason)` carrying the last error class name.

The retry policy SHALL be encapsulated inside the infra adapter; the chat handler SHALL invoke `publish` exactly once per send and treat the returned `PublishResult` as final.

#### Scenario: Single transient failure recovers
- **GIVEN** `SupabaseBroadcastChatClient` is wired with a stub Supabase client that fails the first attempt with a 503 then succeeds on the second
- **WHEN** `publish` is invoked
- **THEN** the method returns `PublishResult.Success` AND the stub client recorded exactly 2 attempts AND the chat handler observed exactly one return value

#### Scenario: All 3 attempts fail returns Failure
- **GIVEN** the stub Supabase client fails every call with a 503
- **WHEN** `publish` is invoked
- **THEN** the method returns `PublishResult.Failure` AND the stub client recorded exactly 3 attempts

#### Scenario: Retry policy not visible at handler level
- **WHEN** the chat send handler invokes `chatRealtimeClient.publish(...)` via the test-double `FakeChatRealtimeClient`
- **THEN** the handler code makes exactly one call (no handler-level loop); retry semantics are owned by the adapter

### Requirement: OTel span around publish

`SupabaseBroadcastChatClient.publish(...)` SHALL emit an OpenTelemetry span named `chat.realtime.publish` with attributes:

- `supabase.realtime.channel = realtime:conversation:<conversation_id>` (mandatory per [`docs/04-Architecture.md:398`](../../../../../docs/04-Architecture.md))
- `user_id` set to the hashed sender id via the existing hashing helper (mandatory per [`docs/04-Architecture.md:398`](../../../../../docs/04-Architecture.md))
- `chat.message_id = <message uuid>`

Span status SHALL be `OK` on `Success` and `ERROR` (with `error.type` attribute = exception class name) on `Failure` or thrown exception.

The Supabase service role key SHALL NEVER appear as a span attribute, log field, or anywhere observable. Static scan: any source-code occurrence of the literal `supabase-service-role-key` SHALL be only at the `secretKey(env, "supabase-service-role-key")` call site.

#### Scenario: Span emitted with mandatory attributes on success
- **WHEN** `publish` is invoked successfully
- **THEN** an OTel span named `chat.realtime.publish` is recorded with attributes `supabase.realtime.channel`, `user_id`, `chat.message_id` populated; status is `OK`

#### Scenario: Span error on Failure
- **WHEN** `publish` returns `PublishResult.Failure` after retry exhaustion
- **THEN** the OTel span has status `ERROR` AND attribute `error.type` set to the last exception class name

#### Scenario: Service role key never logged or attributed
- **WHEN** searching the backend source for the literal string `"supabase-service-role-key"`
- **THEN** the only occurrences are calls to `secretKey(env, "supabase-service-role-key")`

### Requirement: Credential resolution via secretKey helper

`SupabaseBroadcastChatClient` SHALL obtain its Supabase service role key via `secretKey(env, "supabase-service-role-key")` at startup. The helper resolves to GCP Secret Manager slot `staging-supabase-service-role-key` in staging and `supabase-service-role-key` in production per the env-aware naming convention from `CLAUDE.md` § Critical invariants.

The credential SHALL be loaded once at startup and held in the Supabase SDK client instance. The credential SHALL NOT be refreshed mid-process; rotation requires a process restart (matches the existing precedent for other long-lived credentials such as `supabase-jwt-secret`).

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
10. Channel name format: when `publish` is invoked, the captured `conversationId` argument's textual form satisfies the V15 RLS regex when prefixed with `conversation:`.
11. Payload format: capture the `ChatMessageBroadcast` argument; assert all 9 fields are present (the three `embedded_*` are null for this change).
12. Tx rollback on INSERT failure does NOT invoke `publish` (the rollback path tested by chat-foundation extends here).

A separate test class `SupabaseBroadcastChatClientTest` (tagged `network`, gated to staging-smoke) SHALL exercise the real Supabase round-trip:

13. Real publish to a staging Supabase project succeeds.
14. Real publish failure (e.g., bad credential) returns `PublishResult.Failure` after retry exhaustion (3 attempts).

#### Scenario: ChatRealtimeBroadcastTest discoverable
- **WHEN** running `./gradlew :backend:ktor:test --tests '*ChatRealtimeBroadcastTest*'`
- **THEN** the class is discovered AND every numbered scenario 1–12 above corresponds to at least one `@Test` method

#### Scenario: SupabaseBroadcastChatClientTest gated to staging-smoke
- **WHEN** running `./gradlew :infra:supabase:test` on a CI runner without staging credentials
- **THEN** `SupabaseBroadcastChatClientTest` is skipped (network tag absent or credentials missing) AND the absence does not fail the build
