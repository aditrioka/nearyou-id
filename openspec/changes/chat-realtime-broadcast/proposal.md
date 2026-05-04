## Why

Phase 2 #9 chat ([`docs/08-Roadmap-Risk.md`](../../../docs/08-Roadmap-Risk.md)) requires Ktor to act as the authoritative publisher to Supabase Realtime per [`docs/04-Architecture.md:69`](../../../docs/04-Architecture.md). The `chat-foundation` change (PR [#61](https://github.com/aditrioka/nearyou-id/pull/61)) shipped the REST data plane and explicitly deferred broadcast publish to this change, naming it `chat-realtime-broadcast` ([`chat-foundation/proposal.md:37`](../archive/2026-04-30-chat-foundation/proposal.md), [`chat-foundation/design.md` § D9](../archive/2026-04-30-chat-foundation/design.md)). [`auth-realtime/spec.md:37`](../../specs/auth-realtime/spec.md) further pins this change as the canonical owner of publish-side shadow-ban hiding ("Hiding shadow-banned senders from OTHER readers is a publish-side concern owned by the future `chat-realtime-broadcast` change"). Without broadcast, chat is "REST polling" — usable for testing the data plane (the state today after `chat-foundation` + `chat-rate-limit`), not yet a shippable feature. This change closes the loop so the Phase 3 mobile chat UI build is unblocked.

## What Changes

- **NEW** `ChatRealtimeClient` interface in `:core:domain` matching the abstraction sketched at [`docs/04-Architecture.md:139-149`](../../../docs/04-Architecture.md). Single publish method (subscribe is mobile-only and out of scope here): `suspend fun publish(conversationId: UUID, message: ChatMessageBroadcast): PublishResult`. `PublishResult` is a sealed type with `Success` and `Failure(reason)` variants. The interface lives in domain so the chat-module handler can depend on it without pulling Supabase SDK transitively.
- **NEW** `:infra:supabase` Gradle module containing `SupabaseBroadcastChatClient`, the `ChatRealtimeClient` implementation. Module placement matches [`docs/04-Architecture.md:149`](../../../docs/04-Architecture.md) ("`:infra:supabase` (`SupabaseBroadcastChatClient`, default during pre-swap period Months 1-14)") and the project's `:infra:*` vendor-isolation convention from `CLAUDE.md` § Critical invariants ("No vendor SDK import outside `:infra:*`").
- **NEW** broadcast credential resolution via `secretKey(env, "supabase-service-role-key")` (and the staging-prefixed variant by environment). The Supabase service role key is the canonical credential for server-side broadcast publish; the existing `supabase-jwt-secret` is HS256-signing for client-issued realtime tokens (per [`auth-realtime/spec.md`](../../specs/auth-realtime/spec.md)) and is not the right credential for server-originated publish. Reconciliation: this is an additive secret slot — verified Pre-Phase 1 inventory at [`docs/08-Roadmap-Risk.md` § Pre-Phase 1 #34](../../../docs/08-Roadmap-Risk.md) does NOT yet list it (lists `staging-supabase-jwt-secret` only). Documented as the canonical addition under `design.md` § Reconciliation, with a follow-up entry to amend the Pre-Phase 1 secret-slot list if needed.
- **MODIFY** the `chat-conversations` send-message endpoint requirement so a successful POST `/api/v1/chat/{conversation_id}/messages` (HTTP 201) calls `ChatRealtimeClient.publish` AFTER the chat-foundation transaction commits. The publish is best-effort: a failed publish does NOT roll back the persisted INSERT and does NOT change the response status (still 201). The HTTP response body shape stays exactly as `chat-foundation` defined it.
- **NEW** publish-side shadow-ban skip: when the message sender has `is_shadow_banned = TRUE`, the publish step is SKIPPED entirely (no Supabase round-trip). The row still persists per `chat-conversations` invisible-actor model. This is the publish-side counterpart of the V15 RLS subscriber decision documented in [`auth-realtime/spec.md:37`](../../specs/auth-realtime/spec.md) and the read-path filter in [`chat-conversations/spec.md`](../../specs/chat-conversations/spec.md) § List-messages endpoint (`sender_id = :viewer OR NOT EXISTS shadow-ban`).
- **NEW** broadcast-failure retry-then-WARN contract per the canonical chat flow at [`docs/05-Implementation.md:1213-1216`](../../../docs/05-Implementation.md): `SupabaseBroadcastChatClient` retries publish 3x with exponential backoff on transient failure. If all 3 retries fail, return `PublishResult.Failure`; the chat handler logs a structured WARN with `event = "chat_realtime_publish_failed"`, `conversation_id`, `message_id`, `error_class`. The HTTP response is still 201 (the message persisted; mobile subscribers recover via REST resync — `GET /api/v1/chat/{id}/messages?cursor=...` already shipped in `chat-foundation`).
- **NEW** channel name and payload contract: the Supabase Realtime channel identifier passed by the publisher SHALL be `realtime:conversation:<conversation_id>` (the `realtime:` prefix is a Supabase client-API requirement); the underlying realtime TOPIC that the V15 RLS regex evaluates against is `conversation:<conversation_id>` (the `realtime:` prefix is stripped by Supabase before topic evaluation). The topic regex is canonical `^conversation:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$` per [V2__auth_foundation.sql:75](../../../backend/ktor/src/main/resources/db/migration/V2__auth_foundation.sql) and the V15 install (the loose form `[0-9a-f-]{36}` is NOT correct — would silently match malformed strings). Payload SHALL be a JSON object containing `id`, `conversation_id`, `sender_id`, `content`, `created_at`, `embedded_post_id`, `embedded_post_snapshot`, `embedded_post_edit_id`, `redacted_at` mirroring the columns surfaced by `GET /messages` (with `redaction_reason` always omitted, `content` set to null when `redacted_at IS NOT NULL` — same render policy as the read path).
- **NEW** OTel span around publish per [`docs/04-Architecture.md:398`](../../../docs/04-Architecture.md) mandatory attributes: `supabase.realtime.channel = realtime:conversation:<conv_id>`, `user_id` (hashed via existing helper), span name `chat.realtime.publish`, span status reflects publish success/failure.
- **NEW** integration test class `ChatRealtimeBroadcastTest` (tagged `database`, mocking `ChatRealtimeClient` for behavioral assertions) covering: publish invoked on success, publish NOT invoked when sender is shadow-banned, publish failure does not roll back persisted INSERT or `last_message_at` UPDATE, payload matches contract, channel name format is exact, OTel span emitted with required attributes.

## Capabilities

### New Capabilities
- `chat-realtime-broadcast`: server-side publish of new chat messages to Supabase Realtime, including the publish-side shadow-ban skip, broadcast-failure retry-then-WARN contract, channel naming, payload schema, OTel observability, and the credential resolution path. Establishes the `ChatRealtimeClient` domain interface and the `SupabaseBroadcastChatClient` infrastructure binding.

### Modified Capabilities
- `chat-conversations`: the existing Send-message endpoint requirement gains a post-commit clause: on HTTP 201, `ChatRealtimeClient.publish` is invoked after the chat-foundation tx commits, with publish failure NOT changing response semantics.

## Impact

**Affected code:**
- New module `:infra:supabase` (Gradle settings + module-descriptions sync per `CLAUDE.md` § Critical invariants).
- New domain interface in `:core:domain` (`ChatRealtimeClient`, `ChatMessageBroadcast`, `PublishResult`).
- Modified chat-module send-message handler (currently in `:backend:ktor` per chat-foundation) — wire post-commit publish call, shadow-ban check, WARN log, OTel span.
- New integration test class `ChatRealtimeBroadcastTest`.
- New secret slots: `supabase-service-role-key` (prod) + `staging-supabase-service-role-key` (staging) in GCP Secret Manager. No code-side hardcoding; all access goes via `secretKey(env, name)` helper per `CLAUDE.md` § Critical invariants.

**Affected APIs:**
- `POST /api/v1/chat/{conversation_id}/messages`: response shape unchanged; behavior gains a post-commit publish side-channel. No breaking change for existing REST clients.

**Dependencies:**
- New: Supabase server-side SDK (Java/Kotlin) for broadcast publish — exact artifact decision documented in `design.md` § D7 with a fallback to direct REST POST against the Supabase Realtime broadcast HTTP endpoint if the SDK choice introduces too much weight. Pin in `gradle/libs.versions.toml` per `docs/09-Versions.md` policy.

**Systems:**
- Supabase Realtime project: must have the V15 `participants_can_subscribe` RLS policy active (already shipped by chat-foundation). No new schema migration required by THIS change — the V15 policy is the prerequisite, not a deliverable.

**Out of scope (deferred or already done):**
- V15 `participants_can_subscribe` RLS policy installation — already shipped by `chat-foundation` (V15 migration).
- Mobile `ChatRealtimeClient` subscription, reconnection, message merging into local DB, dedup via `message_id` — Phase 3 mobile work. The interface contract here informs mobile design but does not implement it.
- Realtime token issuance — already shipped by `auth-realtime` (`GET /api/v1/realtime/token`).
- Ktor → Redis Streams + DIY WebSocket swap — Month 15+ post-swap (per [`docs/04-Architecture.md:17`](../../../docs/04-Architecture.md)).
- Persisting publish status / delivery receipts — out of scope; broadcast is fire-and-forget with REST resync as the recovery mechanism.
- Read-side per-endpoint rate limiting on a hypothetical "read realtime stream" path — chat reads happen via WSS subscription gated by RLS, not via a Ktor REST endpoint, so there's no per-endpoint limiter here.
- Any change to `chat-rate-limit` (50/day Free cap) — that's the daily send cap, fully orthogonal to publish.
