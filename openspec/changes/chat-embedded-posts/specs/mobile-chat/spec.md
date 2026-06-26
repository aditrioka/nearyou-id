## MODIFIED Requirements

### Requirement: Send message with client-side guard, optimistic append, and reconcile

The send path SHALL `POST /api/v1/chat/{conversation_id}/messages`. For a **plain text send from the message input bar** the body SHALL be `{ content }` only (no `embedded_*` fields). The **same send client method MAY additionally carry an `embedded_post_id`** when invoked by the share-to-chat flow (`mobile-chat-embedded-posts`); in that case `content` is optional and the client guard SHALL require at least one of a non-empty `content` or an `embedded_post_id`. For the text-bar path the client SHALL reject empty/whitespace-only content and content longer than 2000 characters BEFORE issuing the request. On a successful `201`, the optimistically-appended message SHALL be reconciled to the server-assigned `id` from the response. The send-state projection SHALL map `SendOutcome` to the input-bar states (idle / sending / blocked / too-long / network-retry) with no generic fallthrough.

#### Scenario: Plain text-bar send carries no embedded fields
- **WHEN** the user sends a message from the text input bar
- **THEN** the request body contains a `content` field and NONE of `embedded_post_id` / `embedded_post_snapshot` / `embedded_post_edit_id`

#### Scenario: Share-to-chat send carries embedded_post_id
- **WHEN** the share-to-chat flow sends a post embed
- **THEN** the request body carries `embedded_post_id` (and optionally `content`), and the client guard permits an absent/empty `content` because the embed is present

#### Scenario: Over-length content blocked client-side
- **WHEN** the user attempts to send content of 2001 characters
- **THEN** no request is issued AND the input bar shows the too-long state

#### Scenario: Empty or whitespace-only content is not sent
- **WHEN** the user attempts to send `""` or a whitespace-only string (e.g. `"   "`) from the text input bar with no embed
- **THEN** no request is issued AND the send action stays idle/disabled (no optimistic append)

#### Scenario: Optimistic append reconciles to the server id
- **GIVEN** an optimistic message appended on send
- **WHEN** the `201` returns the inserted row with its server `id`
- **THEN** the optimistic row is reconciled to that `id` (one row, not two) so a subsequent realtime echo or resync of the same `id` does not duplicate it

### Requirement: ChatRealtimeSubscriber is a vendor-SDK-free seam

The KMP module `:infra:supabase-realtime` commonMain SHALL declare a vendor-SDK-free `ChatRealtimeSubscriber` interface exposing `fun subscribe(conversationId: Uuid): Flow<ChatMessageInbound>`, a `ChatMessageInbound` data class (`id`, `conversationId`, `senderId`, `content: String?`, `embeddedPostId: Uuid?`, `embeddedPostSnapshot: EmbeddedPostSnapshot?`, `embeddedPostEditId: Uuid?`, `createdAt: Instant`, `redactedAt: Instant?`), and a `RealtimeTokenProvider` fun-interface (`suspend fun fetchToken(): String`). The `EmbeddedPostSnapshot` model and the three `embedded_*` fields SHALL be plain, vendor-SDK-free data (a typed Kotlin data class decoded from the snapshot JSON, or a `kotlinx.serialization.json.JsonElement` — never a Supabase/vendor type) so the chat thread can render the embedded-post context card. The interface source file SHALL NOT import any Supabase / vendor SDK symbol — the supabase-kt import is confined to the `SupabaseChatRealtimeSubscriber` implementation in the same module (CLAUDE.md critical invariant — vendor SDK imports only in `:infra:*`). The `redaction_reason` payload key SHALL still NOT appear on `ChatMessageInbound` (parsed-and-dropped at the infra boundary).

#### Scenario: Interface source file has no vendor import
- **WHEN** a static scan runs over the `ChatRealtimeSubscriber` interface source file for `io.github.jan.supabase.*` (or any vendor Supabase import)
- **THEN** zero matches are found (the import lives only in the `SupabaseChatRealtimeSubscriber` impl)

#### Scenario: Inbound model surfaces embedded fields and omits redaction-reason
- **WHEN** inspecting `ChatMessageInbound`
- **THEN** it exposes `embeddedPostId`, `embeddedPostSnapshot`, and `embeddedPostEditId` as plain vendor-SDK-free properties AND it has no `redactionReason` property

#### Scenario: Embedded snapshot decodes to a vendor-free model
- **WHEN** a realtime payload carrying a populated `embedded_post_snapshot` is parsed at the infra boundary
- **THEN** the `embeddedPostSnapshot` on `ChatMessageInbound` is a plain Kotlin model (no Supabase/vendor symbol) carrying the snapshot's author/content/city fields and no coordinate
