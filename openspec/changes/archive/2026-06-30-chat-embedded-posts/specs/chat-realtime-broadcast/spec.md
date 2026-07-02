## MODIFIED Requirements

### Requirement: Payload schema

The broadcast payload SHALL be a JSON object with the following keys, mirroring the `chat_messages` columns surfaced by `GET /api/v1/chat/{id}/messages`:

```
{
  "id": "<message uuid>",
  "conversation_id": "<conversation uuid>",
  "sender_id": "<sender uuid>",
  "content": "<string or null when redacted/embed-only>",
  "embedded_post_id": "<post uuid or null>",
  "embedded_post_snapshot": "<JSON object or null>",
  "embedded_post_edit_id": "<post_edits uuid, null when post unedited, or null for a plain message>",
  "created_at": "<ISO-8601 UTC>",
  "redacted_at": null
}
```

The payload SHALL NOT contain `redaction_reason` (matches the `chat-conversations` read-path render policy which never serializes `redaction_reason`).

When `redacted_at IS NOT NULL`, the `content` field SHALL be `null`. The `redacted_at` field SHALL be the ISO-8601 UTC string of the redaction timestamp.

The three `embedded_*` fields SHALL be present in every payload (presence with null, never absence). For a plain text or redacted message they SHALL be `null`. For an embed message (one persisted by `chat-embedded-posts`) they SHALL be populated: `embedded_post_id` is the source post UUID (or null after the source post is hard-deleted), `embedded_post_snapshot` is the persisted snapshot JSON object, and `embedded_post_edit_id` is the version-at-share-time anchor (`post_edits` UUID, or null when the post was unedited at share time). The populated payload SHALL stay within Supabase Realtime broadcast's per-message size limit, enforced by the `embedded_post_snapshot` size CHECK (`octet_length(::text) < 4096`, V37 `chat-embedded-posts`).

#### Scenario: Non-redacted plain message payload shape
- **GIVEN** a `ChatMessageBroadcast` with `content = "halo"`, `redactedAt = null`, all `embedded*` = null
- **WHEN** `publish` serializes the payload
- **THEN** the JSON object emitted contains exactly the nine top-level keys (`id`, `conversation_id`, `sender_id`, `content`, `embedded_post_id`, `embedded_post_snapshot`, `embedded_post_edit_id`, `created_at`, `redacted_at`); `content == "halo"`; `redacted_at == null`; the three `embedded_*` keys are present with value `null`; `redaction_reason` is NOT present

#### Scenario: Redacted message payload shape
- **GIVEN** a `ChatMessageBroadcast` with `content = "halo"` (in-memory) but `redactedAt != null`
- **WHEN** `publish` serializes the payload
- **THEN** the JSON object's `content` field is `null` (not the original "halo"); `redacted_at` is the ISO-8601 string of the redaction time; `redaction_reason` is NOT present

#### Scenario: Embed message payload carries populated embedded fields
- **GIVEN** a `ChatMessageBroadcast` for an embed message of post P with a populated snapshot and (P edited) an `embeddedPostEditId = E`
- **WHEN** `publish` serializes the payload
- **THEN** the JSON object's `embedded_post_id == P`, `embedded_post_snapshot` is the snapshot JSON object (carrying author/content/city, no coordinate), and `embedded_post_edit_id == E`; `content` may be null (embed-only) or the accompanying text

#### Scenario: Embed payload stays within the size limit
- **WHEN** an embed payload is serialized
- **THEN** the `embedded_post_snapshot` it carries has `octet_length(::text) < 4096` (guaranteed by the V37 CHECK), keeping the total broadcast payload within Supabase Realtime's per-message limit

### Requirement: Out-of-scope clarifications (broadcast ordering, payload size, retry duplicates, conversation deletion, WSS token TTL)

The following are explicit non-goals of the original `chat-realtime-broadcast` change. Future authors picking up these threads MUST file dedicated changes; that change SHALL NOT introduce code or specs addressing them beyond the surface called out here.

1. **Broadcast ordering NOT guaranteed.** Supabase Realtime broadcast does not promise FIFO delivery for messages published in quick succession. Mobile clients SHALL dedup via `id` AND order by `(created_at, id)` per the chat-foundation cursor shape. The payload schema (see § Payload schema) carries `id` and `created_at` precisely so the client has the fields it needs.
2. **Embedded-payload size cap — SHIPPED by `chat-embedded-posts`.** The original `chat-realtime-broadcast` change emitted `embedded_post_id`, `embedded_post_snapshot`, `embedded_post_edit_id` as null-only. The `chat-embedded-posts` change now populates them and owns the size cap: a schema CHECK `octet_length(embedded_post_snapshot::text) < 4096` (V37) bounds the snapshot, and the populated broadcast payload is verified to fit within Supabase Realtime broadcast's per-message size limit.
3. **Retry-induced duplicate broadcasts.** If a Supabase 5xx response is returned for a publish attempt that DID partially fan-out to subscribers, the retry produces a duplicate broadcast. Mobile dedup via `id` is the recovery contract per `docs/05-Implementation.md:1216`. This change does NOT introduce server-side idempotency tokens or de-duplication state.
4. **Conversation deletion mid-publish.** No conversation-delete endpoint exists yet. If a future cleanup worker / admin tool deletes a conversation between commit and publish, subscribers receive a payload for an orphaned `conversation_id`. Mobile clients refetch via REST and get 404, handling as a deleted message. The future deletion-worker change author SHALL address this race.
5. **WSS subscriber token TTL drift.** The `auth-realtime` token TTL is 1 hour. Long-running mobile chat sessions losing their subscription after 1 hour and refetching via `GET /api/v1/realtime/token` is a mobile-side concern, NOT in scope here.
6. **Receiver-side shadow-ban does NOT skip publish.** Only SENDER-side shadow-ban triggers publish-skip (per § Publish-side shadow-ban skip). The receiver's `is_shadow_banned` state is irrelevant to the publish decision (per `auth-realtime/spec.md:37` invisible-actor model — shadow-banned subscribers ARE allowed to subscribe; broadcast fans out regardless of receiver state).
7. **Banned (not shadow-banned) sender publish-skip.** Senders with `is_banned = TRUE` are 403'd by chat-foundation's auth path BEFORE the chat send handler runs. The publish step is reached only by senders who passed auth. No additional ban-skip logic is needed in this change.

#### Scenario: Broadcast ordering documented as non-goal
- **WHEN** the spec's § Payload schema requirement is read end-to-end
- **THEN** the document explicitly states that broadcast ordering is NOT guaranteed AND that mobile clients order by `(created_at, id)` for the canonical client-side ordering contract

#### Scenario: Embedded-payload size cap owned by chat-embedded-posts
- **WHEN** an embed publish payload is emitted
- **THEN** its `embedded_post_snapshot` satisfies `octet_length(::text) < 4096` (the V37 `chat-embedded-posts` CHECK) and the document names `chat-embedded-posts` as the owner of the size-cap enforcement
