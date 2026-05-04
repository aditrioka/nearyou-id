## Context

Chat in nearyou-id is built in three layered changes:

1. **`chat-foundation`** (PR [#61](https://github.com/aditrioka/nearyou-id/pull/61), shipped 2026-04-30) — REST data plane: schema (`conversations`, `conversation_participants`, `chat_messages`), `POST /api/v1/conversations`, `GET /api/v1/conversations`, `GET /api/v1/chat/{id}/messages`, `POST /api/v1/chat/{id}/messages`. Also installed the V15 `participants_can_subscribe` RLS policy on `realtime.messages` (V2's gated `DO` block was a no-op at V2-time because `conversation_participants` did not exist).
2. **`chat-rate-limit`** (PR [#62](https://github.com/aditrioka/nearyou-id/pull/62), shipped 2026-05-02) — 50/day Free cap on `POST /api/v1/chat/{id}/messages` with WIB-staggered TTL.
3. **`chat-realtime-broadcast`** (this change) — server-side publish of new chat messages to Supabase Realtime so subscribed mobile clients receive a real-time push without polling.

After this change ships, the chat MVP is feature-complete on the server side. Phase 3 mobile chat UI (subscribe + render) consumes this contract.

**Constraints inherited from the project:**

- `CLAUDE.md` § Critical invariants — no vendor SDK import outside `:infra:*`; secrets via `secretKey(env, name)` only; OTel mandatory attributes per [`docs/04-Architecture.md:398`](../../../docs/04-Architecture.md).
- [`docs/04-Architecture.md:69`](../../../docs/04-Architecture.md) — "Ktor as authoritative publisher" — the server (not the client) emits broadcast.
- [`docs/04-Architecture.md:139-149`](../../../docs/04-Architecture.md) — `ChatRealtimeClient` interface in `:core:domain`; `:infra:supabase` houses `SupabaseBroadcastChatClient` (default during pre-swap period Months 1-14); a future `:infra:ktor-ws` houses the post-swap `KtorWebSocketChatClient`. The interface, in this version, is sketched with `subscribe(...)` and `unsubscribe(...)` methods — that's the consumer side. The server-side publish is what THIS change adds; the architecture sketch's interface shape is one direction of the contract, and a second method `publish(conversationId, message)` is the natural addition for the publisher side.
- [`docs/05-Implementation.md:1198-1216`](../../../docs/05-Implementation.md) — pre-swap chat flow including the canonical 3x retry + WARN log on broadcast failure.
- [`auth-realtime/spec.md:37`](../../specs/auth-realtime/spec.md) — explicit handoff: this change owns publish-side shadow-ban hiding.

## Goals / Non-Goals

**Goals:**
- Wire Ktor → Supabase Realtime broadcast publish into the existing `POST /api/v1/chat/{conversation_id}/messages` happy path WITHOUT changing the REST contract (response shape, status codes, error semantics).
- Establish the `ChatRealtimeClient` domain interface so the post-swap `KtorWebSocketChatClient` (Month 15+) can swap in cleanly.
- Implement the publish-side shadow-ban skip per `auth-realtime/spec.md:37` to complete the invisible-actor model on chat.
- Implement the canonical 3x retry + WARN log + REST-resync recovery path per `docs/05-Implementation.md:1213-1216`.
- Keep persistence atomicity intact: a publish failure does not roll back a persisted INSERT.
- Emit the OTel attributes mandated by `docs/04-Architecture.md:398` so chat publish shows up in dashboards.

**Non-Goals:**
- Mobile-side subscription / dedup / merge-into-local-DB — Phase 3 mobile concern; the interface contract here informs but does not implement it.
- DIY Ktor WebSocket + Redis Streams — Month 15+ post-swap; out of scope.
- Persistent delivery receipts / read receipts / typing indicators — orthogonal product features.
- Realtime token issuance — already shipped by `auth-realtime` (`GET /api/v1/realtime/token`).
- V15 RLS policy — already shipped by `chat-foundation`.
- Read-side per-endpoint rate limiting — chat reads happen via WSS subscription gated by RLS; no Ktor REST endpoint to limit.

## Decisions

### D1. Tx boundary: publish runs AFTER the chat-foundation transaction commits, awaited inline before HTTP 201

**Choice:** the publish call is dispatched after the `INSERT chat_messages` + `UPDATE conversations.last_message_at` transaction commits. The handler captures the inserted row's projection, completes the tx, then invokes `chatRealtimeClient.publish(...)` outside the tx scope. The publish call IS `await`ed inline by the request handler — the HTTP 201 response is written ONLY after publish returns (Success or Failure). The handler runs publish on the request's coroutine context (NOT a fire-and-forget `application.launch`).

**Why awaited inline (not fire-and-forget):**
- **Predictable observability**: with await-inline, the WARN log on `event = "chat_realtime_publish_failed"` fires synchronously within the request lifecycle and is captured by request-scoped tracing/MDC; with fire-and-forget, the log line emerges after the response has already been sent and may not be correlated with the request.
- **Bounded latency**: worst-case added HTTP latency = 4 attempts × per-attempt timeout + ~1.3s backoff. With a 500ms per-attempt timeout (D7 task), worst case is ~3.3s — large but acceptable for chat send (the 99th-percentile chat latency target in `docs/04-Architecture.md` Phase 2 benchmark is p95 <200ms for timeline; chat is comparatively lax).
- **Coroutine cancellation safety**: if the client disconnects mid-handler, Ktor cancels the coroutine. With await-inline on the request's context, publish is canceled too — no orphaned in-flight Supabase call. The persisted row remains; the client will resync via REST on reconnect. Documented in spec scenario "Coroutine cancellation behavior."

**Why not in-tx, why not fire-and-forget — alternatives rejected:**
- Persistence is the source of truth (`docs/04-Architecture.md:69` — Ktor as authoritative publisher; persistence-then-broadcast). If publish were inside the tx, a slow Supabase call would hold a row lock on `chat_messages` and `conversations` for the full broadcast duration, multiplying contention with `chat-foundation`'s `last_message_at` UPDATE.
- A failed publish must not roll back the persisted INSERT (the message is visible via REST `GET /messages` regardless). In-tx failure semantics would force rollback-or-swallow, both wrong.
- The chat-foundation `chat-conversations/spec.md` § Send-message endpoint scenario "Send updates last_message_at atomically" requires the INSERT and UPDATE be in a single tx — but the post-commit publish does not undermine that requirement.
- **Fire-and-forget alternative rejected**: would let the request handler return 201 before publish completes, but the WARN log on failure would emerge outside the request scope (worse correlation for ops debugging) and the orphaned in-flight call could complete after the JVM is killed (no log line, silent drop). Await-inline is the cleaner contract.

**Crash-window contract (adversarial finding E):** if the JVM is killed (OOM / SIGKILL / container eviction / network drop) between the tx commit and the publish call (or mid-publish), the persisted row exists but no broadcast fires AND no WARN log is captured (the handler died before reaching the WARN). Recovery is mobile REST resync via `GET /api/v1/chat/{id}/messages?cursor=...`. This is an explicit non-goal: an outbox / replay log is NOT introduced by this change. A future change MAY add an outbox if the operational incident rate justifies it; the current contract is "best-effort post-commit publish; durable persistence; mobile resync covers misses." Spec scenario "Crash-window contract" documents this.

### D2. Shadow-ban filter location: publish-side skip on the server, sourced from the `viewer` principal

**Choice:** when the message sender has `is_shadow_banned = TRUE`, the chat send handler SKIPS the publish call entirely. No Supabase round-trip occurs. The row still persists per `chat-conversations` invisible-actor model. The handler reads the sender's shadow-ban state from `viewer.isShadowBanned` on the request principal — a new field added to `UserPrincipal` by this change (loaded by `AuthPlugin.configureUserJwt` from `users.is_shadow_banned` in the same auth-time SELECT that already pulls `subscription_status` per the like-rate-limit precedent). The handler MUST NOT issue a fresh `SELECT is_shadow_banned FROM users` for the publish decision.

**Rationale:**
- [`auth-realtime/spec.md:37`](../../specs/auth-realtime/spec.md) is explicit: "Hiding shadow-banned senders from OTHER readers is a publish-side concern owned by the future `chat-realtime-broadcast` change." This change is the named owner.
- Symmetric with the read-path filter in `chat-conversations/spec.md` § List-messages endpoint: `sender_id = :viewer OR NOT EXISTS shadow-ban`. Read filters out shadow-banned senders for non-self viewers; publish skip is the realtime equivalent.
- Server-side skip closes the leak entirely (no payload ever leaves the publisher). Consumer-side filtering on mobile is documented at [`docs/05-Implementation.md:1880`](../../../docs/05-Implementation.md) ("Chat delivery: filter when consuming from Supabase Realtime broadcast (application-level)") — these two are NOT in conflict; they're defense-in-depth at different layers. Server skip is the primary; mobile filter is the backup for race conditions where a sender becomes shadow-banned between publish and receive (e.g., mid-flight admin action).
- **Why principal-sourced state, not a fresh SELECT.** The chat-rate-limit cycle already established that data needed pre-DB-hop lives on the principal: `subscriptionStatus` was added to `UserPrincipal` via `AuthPlugin.configureUserJwt` in `like-rate-limit` task 6.1.1 (see `FOLLOW_UPS.md` entry `auth-jwt-spec-debt-userprincipal-subscription-status`). `is_shadow_banned` is the same shape of state — used in multiple places (read-path filter, publish-side skip, future moderation features), already retrievable by the existing auth-time user-row SELECT for free, and architecturally part of user identity. Adding `isShadowBanned: Boolean` to `UserPrincipal` (and to the AuthPlugin's loading SELECT) is the principled answer, costs zero extra DB round-trips at request time, and removes the spec contradiction that would otherwise arise from the "MUST NOT issue an additional SELECT" clause. The future docs-only OpenSpec change tracked by the existing `auth-jwt-spec-debt-userprincipal-subscription-status` FOLLOW_UPS entry will document BOTH `subscriptionStatus` and `isShadowBanned` together (the FOLLOW_UPS entry is amended in Phase 9 of `tasks.md`).
- The slight in-flight staleness when a moderator flips `is_shadow_banned` mid-request is documented in the Risks section ("shadow-ban skip race") and accepted: an in-flight message slips through; all subsequent messages are filtered; mobile consumer-side filter catches the race as defense-in-depth.

**Reconciliation note:** the apparent doc divergence between `auth-realtime/spec.md:37` (publish-side) and `docs/05-Implementation.md:1880` (consumer-side) resolves as defense-in-depth. No FOLLOW_UPS needed — both readings are simultaneously canonical for their respective layers.

**Alternative rejected (allow a fresh SELECT for the publish decision):** contradicts the chat-rate-limit pattern of "limiter MUST run before any DB read" by adding a per-send DB hop. Doesn't scale: every future per-user check on the send path would need its own SELECT.

**Alternative rejected (consumer-side only):** would let a shadow-banned sender's payload reach Supabase and any subscriber whose mobile-filter logic is buggy or stale. Defeats the invisible-actor model's privacy goal.

### D3. Broadcast failure does NOT roll back persisted INSERT

**Choice:** if `chatRealtimeClient.publish(...)` returns `Failure` (after exhausting retries) or throws after the tx commits, the chat handler logs a structured WARN and returns HTTP 201 with the chat-foundation response shape. The `chat_messages` row stays. The `conversations.last_message_at` UPDATE stays.

**Rationale:**
- Tx already committed (per D1). Rollback isn't possible at that point even if desired.
- REST resync via `GET /api/v1/chat/{id}/messages?cursor=...` (chat-foundation) is the canonical recovery path per [`docs/05-Implementation.md:1215`](../../../docs/05-Implementation.md): "Client offline during broadcast: miss. On next app open, fetch delta via REST."
- Non-201 on publish failure would punish the sender for a Supabase outage they have no control over and would create a confusing UX where the message exists in DB but the sender saw an error.

### D4. 4 total attempts (1 initial + 3 retries) with exponential backoff inside `SupabaseBroadcastChatClient`

**Choice:** `SupabaseBroadcastChatClient` performs up to 4 total attempts on transient failures (network IOException, timeout, HTTP 5xx) — 1 initial attempt followed by up to 3 retries. Backoff sleeps occur BEFORE retry attempts 2, 3, and 4: ~100ms, ~300ms, ~900ms. After the 4th attempt fails, return `PublishResult.Failure(reason)` to the caller. The caller (chat handler) emits the WARN log.

**Rationale:**
- Canonical contract from [`docs/05-Implementation.md:1213-1214`](../../../docs/05-Implementation.md): "INSERT success + broadcast fail: Ktor retries broadcast 3x with exponential backoff. If still failing, log a warning." The natural reading of "retries 3x" is 3 retries (= 4 total attempts), with 3 inter-attempt sleeps.
- Retry happens INSIDE the infra adapter so the domain handler stays simple (single call, single result).
- Backoff schedule: 100ms / 300ms / 900ms = ~1.3s total backoff budget. Plus per-attempt HTTP latency × 4 — with a 500ms per-attempt timeout, worst-case added latency to the publish call is ~3.3s. Small enough to not noticeably delay a successful eventual response, large enough to ride out a typical transient Supabase blip.

**Alternative rejected (no retry, fire-and-forget once):** misses the documented 3x retry contract. Alternative also rejected (caller-side retry loop): forces every consumer of `ChatRealtimeClient` to know retry policy; the post-swap `KtorWebSocketChatClient` may have different retry semantics, so encoding policy in the adapter is cleaner. Alternative also rejected (3 total attempts + 2 sleeps): the original proposal had 3 attempts + 3 backoff intervals which is mathematically inconsistent (3 attempts have only 2 inter-attempt sleeps); the fix is to align attempt count with the canonical "retries 3x" reading (4 total).

### D5. Channel name: `realtime:conversation:<conversation_id>` (publisher); topic: `conversation:<conversation_id>` (RLS evaluation)

**Choice:** the Supabase Realtime CHANNEL identifier passed by the publisher SHALL be `realtime:conversation:<conversation_id>` (lowercase UUID, canonical RFC 4122 textual form including hyphens). The `realtime:` prefix is a Supabase client-API requirement for authorization-gated channels. The underlying realtime TOPIC that the V15 RLS policy regex evaluates against is `conversation:<conversation_id>` — Supabase strips the `realtime:` prefix before topic evaluation. Concretely, `realtime.topic()` returns `conversation:<uuid>` (without the prefix), and the V15 policy's `split_part(realtime.topic(), ':', 2)` extracts the UUID for the `conversation_participants` JOIN.

**Rationale:**
- The V15 `participants_can_subscribe` RLS policy (per `chat-conversations/spec.md` § Realtime RLS test set) authorizes subscriptions on `realtime.messages` rows whose `realtime.topic()` matches `^conversation:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$` (the canonical anchored UUID regex from [V2__auth_foundation.sql:75](../../../backend/ktor/src/main/resources/db/migration/V2__auth_foundation.sql), unchanged in V15). The broadcast publish must produce a topic the policy will match.
- The earlier sketch in `docs/05-Implementation.md:1198-1206` describes the broadcast as `conversation:<id>` (without the `realtime:` prefix). That description is correct at the **topic** level (which is what subscribers and the RLS policy see) but understates the publisher-side API which requires the `realtime:` prefix. The two readings are consistent at different layers — publisher passes prefixed channel; Supabase strips on the way to RLS topic evaluation; subscriber sees stripped topic in the policy regex match. A FOLLOW_UPS entry tracks a docs amendment to spell this out at `docs/05-Implementation.md:1204` so future readers don't introduce a bug by removing the `realtime:` prefix from publisher code (Phase 9 task in `tasks.md`).
- Server publish identifier and the topic the policy regex matches must agree at the topic layer. Any deviation (e.g., `chat.<conv_id>`, mis-cased UUID) breaks the subscription.

**Regex citation correction:** the original draft of this proposal cited `^conversation:[0-9a-f-]{36}$` as the V15 regex. That is loose and would match malformed strings like `conversation:------------------------------------`. The canonical regex in V2 line 75 is the dash-positioned form `^conversation:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`. Every spec/test reference uses this canonical form. Verified during Phase 1 task 1.1.

### D6. Payload schema: mirror columns surfaced by `GET /messages`

**Choice:** the broadcast payload SHALL be a JSON object with these fields (keys match `chat_messages` columns):

```
{
  "id": "<message uuid>",
  "conversation_id": "<conversation uuid>",
  "sender_id": "<user uuid>",
  "content": "<string or null when redacted>",
  "embedded_post_id": null,
  "embedded_post_snapshot": null,
  "embedded_post_edit_id": null,
  "created_at": "<ISO-8601 UTC>",
  "redacted_at": null
}
```

`redaction_reason` is NEVER serialized (matches the `chat-conversations` read-path render policy). When `redacted_at IS NOT NULL`, `content` is set to `null`. The three `embedded_*` fields are present-with-null until the future `chat-embedded-posts` change populates them; serializing them now keeps the mobile parser stable across the embedded-posts ship.

**Rationale:**
- Mobile dedup via `id` (per [`docs/05-Implementation.md:1216`](../../../docs/05-Implementation.md): "Duplicate broadcast (retry + original both succeed): client dedupes via `message_id` UUID").
- Symmetry with `GET /messages` lets mobile use one render path for both REST and realtime input.
- Forward-compatibility: serializing `embedded_*` fields with null avoids a breaking schema change when `chat-embedded-posts` lands.

### D7. Credential and library: Supabase service role key via `secretKey(env, "supabase-service-role-key")`; library pinned in Phase 3.1

**Choice:** server-side broadcast publish uses the Supabase service role key, resolved via `secretKey(env, "supabase-service-role-key")`. New GCP Secret Manager slots: `staging-supabase-service-role-key` (staging) and `supabase-service-role-key` (prod, unprefixed per the convention from `docs/08-Roadmap-Risk.md` § Pre-Phase 1 #34). The actual library/SDK choice (e.g., the official Supabase Kotlin SDK vs. a hand-rolled Ktor HttpClient against the broadcast HTTP endpoint) is deferred to `tasks.md` Phase 3.1 — both options are acceptable; the choice is a weight-vs-stability tradeoff to be made when the implementation lands.

**Rationale:**
- The existing `supabase-jwt-secret` is the HS256-signing secret for client realtime tokens (per `auth-realtime/spec.md`). Server-originated publish needs a privileged credential, not a token-signing secret.
- The Supabase service role key bypasses RLS by design (which is correct here — the broadcast publish is a privileged server action, and the RLS gate is on the SUBSCRIBER side, not the publisher). **This RLS-bypass property is intentional and load-bearing:** the spec § "Credential resolution via secretKey helper" includes an explicit non-goal note so a future maintainer doesn't mistakenly assume the publish path is RLS-gated.
- `secretKey(env, name)` enforces the staging-vs-prod prefix automatically per `CLAUDE.md` § Critical invariants. (The convention is enforced via static grep, not via a Detekt rule — see D9 reconciliation note.)

**Reconciliation note (additive secret slot):** Pre-Phase 1 secret-slot list at [`docs/08-Roadmap-Risk.md` § Pre-Phase 1 #34](../../../docs/08-Roadmap-Risk.md) does NOT yet list `supabase-service-role-key`; the list ends at `staging-resend-api-key`. This is an additive secret, not a rename, so an entry to FOLLOW_UPS.md is filed (Phase 9 task in `tasks.md`) to amend the Pre-Phase 1 list when a docs PR next touches that section. The change ships fine without the docs amendment because the slot is created in GCP Secret Manager via Phase 7 staging-deploy infra.

**`secretKey` helper enum verification:** `tasks.md` Phase 2 includes a verification step to confirm `secretKey(env, name)` accepts a brand-new slot name without requiring registration in a known-keys enum (some `secretKey` shapes do; Phase 2 task 2.7 confirms this for `supabase-service-role-key` before Phase 3 implementation begins).

**Alternative rejected (anon key + RLS bypass via service role on broadcast endpoint only):** Supabase Realtime broadcast publish over HTTP requires a credential authorized to `realtime:broadcast`; the anon key is not. The service role key is the canonical answer. Alternative also rejected (signed payload via the existing HS256 secret): conflates two different secrets and would require a custom auth path on the Supabase side that does not exist.

### D8. Module placement: extend the existing `:infra:supabase` Gradle module

**Choice:** add the new `realtime/` package + `SupabaseBroadcastChatClient` + the Supabase Realtime SDK dependency to the **existing** `:infra:supabase` module ([`settings.gradle.kts:51`](../../../settings.gradle.kts) — already includes JdbcUserRepository et al.). The `ChatRealtimeClient` interface and `ChatMessageBroadcast` / `PublishResult` types live in `:core:domain`. The chat-module handler in `:backend:ktor` injects the interface; the DI binding is in `:backend:ktor`'s Koin module (per the project's existing DI convention at `Application.kt`).

**Rationale:**
- `CLAUDE.md` § Critical invariants: "No vendor SDK import outside `:infra:*` — domain/data code depends only on interfaces." The Supabase Realtime SDK import stays inside `:infra:supabase`.
- [`docs/04-Architecture.md:149`](../../../docs/04-Architecture.md) names `:infra:supabase` as the canonical module for `SupabaseBroadcastChatClient` — and that module exists already, currently housing the JDBC repos. Adding the realtime client keeps the module's responsibility coherent ("Supabase-vendor-specific code lives here").
- The existing module description at [`dev/module-descriptions.txt:20`](../../../dev/module-descriptions.txt) currently misclaims JWKS/token-verifier scope (stale). Phase 2.3 replaces it with an accurate one-liner covering both the JDBC repos and the new realtime client. `dev/scripts/sync-readme.sh --write` runs to refresh the auto-generated root README per `CLAUDE.md` § Critical invariants.

**Reconciliation with round-3 finding:** an earlier draft of this design treated `:infra:supabase` as a NEW module to be created. Round-3 implementation-feasibility review surfaced that the module is already on disk with 25+ Kotlin files. This design decision is updated accordingly: the change EXTENDS the existing module (adds a new `realtime/` package), it does NOT create a new module. Phase 2 tasks were rewritten in lock-step.

**Alternative rejected (put `SupabaseBroadcastChatClient` in `:backend:ktor`):** violates the no-vendor-SDK-outside-`:infra:*` rule. Even though the only caller is `:backend:ktor`, the rule exists so the future `:infra:ktor-ws` swap is mechanical (delete one module, add another, change DI binding).

### D9. Observability: structured WARN log on failure (OTel deferred to a future foundation change)

**Choice:** observability for THIS change is structured WARN log only. On `PublishResult.Failure` after retry exhaustion (or thrown exception caught after the tx commits), the chat handler logs a structured line with: `event = "chat_realtime_publish_failed"`, `conversation_id`, `message_id`, `error_class`. Log level is WARN (not ERROR) — the user-visible outcome is fine; REST resync covers the missed broadcast; ops can alert on the log-line rate.

**OpenTelemetry deferred to a future `observability-otel-foundation` change.** An earlier draft of this design mandated an OTel span `chat.realtime.publish` with mandatory attributes per [`docs/04-Architecture.md:398`](../../../docs/04-Architecture.md). Round-3 implementation-feasibility review surfaced that:

1. **OTel SDK is NOT in the project** — zero references in `gradle/libs.versions.toml` or `build-logic/`.
2. **No "existing user-id hashing helper" exists** — the design's reference to "user_id (hashed via existing helper)" was wrong; no such helper is on disk.
3. **No shipped capability emits OTel spans** — chat-foundation, chat-rate-limit, fcm-push-dispatch, post-likes, post-replies, premium-search all use structured logs only; instrumenting only chat-realtime-broadcast would create a single-instrumented-capability anomaly.

Adopting OTel here would couple a chat-publish feature change to a project-wide observability foundation (SDK adoption + global tracer wiring + hash helper introduction + DI plumbing) — at minimum doubling the scope and creating a long-running PR. Per the precedent set by other infrastructure adoptions (Detekt rules in `coordinate-jitter-lint-rule` etc.), project-wide infrastructure lands via its own dedicated change. THIS change ships ONLY the structured WARN log; a future `observability-otel-foundation` change adopts OTel SDK + global tracer + hashed-user-id helper + retroactively instruments all existing call sites with one consistent policy.

The `docs/04-Architecture.md:398` "mandatory OTel attributes" list documents the FUTURE state once that foundation lands. A FOLLOW_UPS entry (filed in `tasks.md` Phase 9) amends the canonical doc to clarify this. Until the foundation lands, structured logs are the de-facto observability surface (matching every other shipped capability).

**Secret-leakage guarantees (still in scope):** the resolved Supabase service-role-key VALUE (loaded via `secretKey(env, "supabase-service-role-key")`) MUST NEVER appear in any log field, HTTP request header that gets logged, or anywhere observable. The spec includes both a literal-source-grep scenario (the slot name only appears at the `secretKey(...)` call site) AND a defense-in-depth scenario (no captured log line on the publish call path contains the resolved key value as a field or message substring). The `RawSecretReadRule` Detekt rule referenced in earlier drafts of this design does NOT exist on disk; enforcement is via static-grep tests aligned with the [`auth-realtime/spec.md:31-33`](../../specs/auth-realtime/spec.md) precedent for `supabase-jwt-secret`.

**Rationale:**
- WARN (not ERROR) prevents Sentry-noise when transient Supabase outages cause harmless REST-resync recovery — but ops can still set up an alert on `event = "chat_realtime_publish_failed"` rate.
- Structured logs (already used everywhere else in the project) provide enough context (`conversation_id`, `message_id`, `error_class`) for ops to investigate; OTel adds richer distributed-tracing context but is not a launch blocker.

### D10. Test layering: `ChatRealtimeBroadcastTest` mocks `ChatRealtimeClient`

**Choice:** integration tests in `:backend:ktor` against the chat handler use a `FakeChatRealtimeClient` test double for behavioral assertions (publish invoked / not invoked / failure handled). Real Supabase round-trip is tested separately at the `:infra:supabase` module level by `SupabaseBroadcastChatClientTest` (network-tagged), gated to the staging-smoke phase per the `like-rate-limit` precedent.

**Rationale:**
- Decouples behavioral correctness (handler did the right thing given a publish result) from infra correctness (the SDK actually talks to Supabase). Same pattern as `like-rate-limit`'s `InMemoryRateLimiter` vs `RedisRateLimiterIntegrationTest` split.
- Behavioral tests run on every CI; infra tests gated to staging-smoke avoid network flake on every PR.

### D11. Privileged publish path: caller-allowlist non-goal

**Choice:** the `ChatRealtimeClient` binding (with the service-role-key-equipped Supabase client) is registered in DI for `:backend:ktor`. The ONLY caller permitted to invoke `publish` is the chat send handler (`POST /api/v1/chat/{conversation_id}/messages`). Any future route that wants to publish chat broadcasts MUST replicate the chat-foundation auth + active-participant + bidirectional-block check pre-publish, OR use a separately-bound chat-only client. This is not a code-enforced restriction in THIS change — it's a non-goal contract that future authors must honor.

**Rationale:**
- The Supabase service role key bypasses RLS by design (D7). A future ill-considered route (e.g., an admin "broadcast announcement to all conversations" feature) could trivially misuse the same DI binding to spam channels with arbitrary payloads.
- Encoding the restriction as a non-goal in the spec creates a paper trail; reviewers of future PRs that inject `ChatRealtimeClient` outside the chat send route can flag the missing checks.
- A code-enforced approach (e.g., a token-based caller assertion) is overkill for the current state (one caller); revisit if a second legitimate caller arises.

**Surfaced by adversarial round-3 review.** The original draft did not address this defense-in-depth concern. Spec § Privileged publish path codifies the non-goal.

### D12. `PublishResult.Failure` shape and `error_class` log field

**Choice:** `PublishResult.Failure(reason: String)` carries a `reason` string that is the FULLY-QUALIFIED CLASS NAME of the last exception encountered during the retry loop (e.g., `"java.io.IOException"`, `"java.net.SocketTimeoutException"`, `"id.nearyou.app.infra.supabase.SupabaseHttp5xxException"`). The chat handler's WARN log on `PublishResult.Failure` sets `error_class = result.reason`. On `PublishResult.Success`, no WARN is logged. On a thrown exception (rare — should be caught by the retry loop and converted to `Failure`, but defensive against unexpected `Throwable`), the handler catches and logs `error_class = throwable::class.qualifiedName`.

**Rationale:**
- A unified `error_class` field across both Failure-path and exception-path keeps the log-line schema flat for ops alerting (single Splunk/Elasticsearch query for `event = "chat_realtime_publish_failed"`).
- Fully-qualified names disambiguate (e.g., `java.net.SocketTimeoutException` vs a hypothetical custom one); short names risk collision in big logs.

**Earlier draft ambiguity (round-3 implementation-feasibility finding):** spec scenario "Publish failure with persisted row + 201 + WARN log" said `error_class = "Failure"`. That was wrong — the scenario implied a literal string `"Failure"` regardless of the actual error. Spec scenario corrected to assert `error_class = result.reason` (the captured class name).

### D13. Payload JSON naming: snake_case via per-field `@SerialName` annotations

**Choice:** `ChatMessageBroadcast` data class uses Kotlin camelCase fields. Each field has a `@SerialName("snake_case_form")` annotation matching the wire format. NO global `JsonNamingStrategy` is configured — explicit per-field annotation keeps the contract visible at the data class definition.

**Rationale:**
- Existing chat-foundation REST DTOs (`ChatDtos.kt`) use a hand-rolled `chatMessageJson` for serialization rather than reflection-driven kotlinx.serialization. The publish payload could either: (a) hand-roll a serializer matching `ChatDtos.kt`, OR (b) use kotlinx.serialization with `@SerialName` annotations. Option (b) is cleaner because the chat-foundation hand-rolled serializer is internal to `ChatDtos.kt`; the broadcast payload is a separate concern emitting a smaller field set.
- Per-field annotations are explicit at the source; a global `JsonNamingStrategy.SnakeCase` is implicit and would change behavior across unrelated kotlinx.serialization usages in the project.

**Surfaced by adversarial round-3 review.** Implementer ambiguity flagged.

### D14. `:infra:supabase` build conventions match the existing module

**Choice:** the new `realtime/` package added to `:infra:supabase` uses the SAME Gradle plugin / dependency conventions as the existing module's `build.gradle.kts` (currently uses `id("nearyou.kotlin.jvm")` per the project's `build-logic` convention plugins). NEW dependencies (Supabase Realtime SDK, kotlinx.serialization-json) are added to the existing module's dependency block.

**Rationale:**
- Avoids creating a divergent build configuration within the same module.
- The convention plugin handles the project's standard ktlint/detekt/Java-toolchain setup; sticking with it ensures the new realtime code participates in the same CI lint surface as the existing JDBC repos.

**Surfaced by adversarial round-3 review.** Phase 2.2 task is "match the existing `:infra:supabase` build file conventions" — this design entry pins what that means.

## Risks / Trade-offs

- **Risk: Supabase broadcast outage with high message volume burns the retry budget on every send.** → Mitigation: 4-attempts cap (1 initial + 3 retries) with bounded backoff (100/300/900 ms = ~1.3s budget) plus per-attempt timeout (~500ms; pinned in Phase 3.1) limits per-request added latency to ~3.3s worst case. Failure path is WARN-and-move-on, not 5xx; chat persists regardless. A circuit-breaker layer is NOT added in this change (rule-of-three: one call site is too few; revisit if a second publish-style infra adapter accumulates).
- **Risk: handler crash / coroutine cancellation between commit and publish (adversarial finding E + F).** → Mitigation accepted: the persisted row is durable; mobile REST resync via `GET /api/v1/chat/{id}/messages?cursor=...` recovers the missed broadcast on the next app open (per `docs/05-Implementation.md:1215`). No outbox / replay log introduced. If the operational incident rate from "subscribers complain about missing messages on a healthy DB" exceeds tolerance, a future change adds a Postgres-backed outbox + worker; this change documents the gap as a non-goal so future authors know where to start.
- **Risk: payload schema drift between server publish and mobile consumer.** → Mitigation: spec pins the payload field set explicitly; integration test asserts the exact payload shape; future `chat-embedded-posts` change MUST extend the schema with NEW fields (additive only, never rename or repurpose existing fields).
- **Risk: shadow-ban skip race — sender becomes shadow-banned between INSERT and post-commit publish.** → Mitigation: the publish-side check reads `is_shadow_banned` from the same `users` row consulted by the chat handler's auth/principal context (already loaded). If a moderator flips `is_shadow_banned = TRUE` between auth and publish, the publish still emits (using stale state). This is acceptable per the invisible-actor model — an in-flight message slips through but all subsequent messages are filtered. Mobile consumer-side filter (`docs/05-Implementation.md:1880`) catches this race as defense-in-depth.
- **Risk: service role key exposure in logs.** → Mitigation: the credential is read once at startup via `secretKey(env, name)` and stored only in the Supabase SDK client's internal state; never logged. Enforcement is via the static-grep test scenario shape (matches the precedent set by [`auth-realtime/spec.md:31-33`](../../specs/auth-realtime/spec.md) for `supabase-jwt-secret`): the spec includes a scenario asserting the literal string `"supabase-service-role-key"` appears only at the `secretKey(env, ...)` call site, AND a defense-in-depth scenario asserting the resolved key VALUE never appears in any captured log field across the publish call path. NOTE: an earlier draft of this design referenced a `RawSecretReadRule` Detekt rule; that rule does not exist on disk (the lint rules in `lint/detekt-rules/` are RateLimitTtlRule, RedisHashTagRule, RawFromPostsRule, RawXForwardedForRule, BlockExclusionJoinRule, CoordinateJitterRule). The `secretKey(env, name)` convention is enforced via static grep tests, not Detekt — aligned with auth-realtime precedent.
- **Risk: privileged-publish caller-allowlist drift.** → Mitigation: D11 documents the non-goal (only the chat send route may invoke `ChatRealtimeClient.publish`). Reviewers of future PRs that inject `ChatRealtimeClient` outside the chat send route flag the missing chat-foundation auth + participant + block checks. This is a paper-trail mitigation, not a code-enforced one — accepted because there's currently one caller and over-engineering the protection is premature.
- **Risk: broadcast ordering not guaranteed (adversarial round-3 finding 4).** → Mitigation accepted: Supabase Realtime broadcast does not promise ordered delivery for messages published in quick succession. Mobile clients dedup via `id` AND order by `(created_at, id)` per `chat-foundation` § List-messages cursor shape. The spec's payload schema includes `id` and `created_at` so the client has the fields it needs. Spec § Payload schema documents the non-goal "broadcast ordering NOT guaranteed; clients order by (created_at, id) per chat-foundation cursor shape."
- **Risk: oversized `embedded_post_snapshot` JSONB payload bloating broadcast (adversarial round-3 finding 5).** → Mitigation: not in scope here (this change emits `embedded_*` as null-only). The future `chat-embedded-posts` change MUST cap payload size at the schema layer (`CHECK (octet_length(embedded_post_snapshot::text) < ...)`) AND verify Supabase Realtime broadcast accepts the resulting payload size. Spec § Payload schema documents this as a non-goal pin: "payload size cap is the responsibility of `chat-embedded-posts`."
- **Risk: WSS subscriber token TTL drift (adversarial round-3 finding 6).** → Mitigation: not in scope here. The 1h `auth-realtime` token TTL is a mobile-side concern; the mobile client refetches via `GET /api/v1/realtime/token` on token expiry. Spec § Out-of-scope lists this as deferred to mobile.
- **Risk: receiver-shadow-ban does NOT skip publish (adversarial round-3 finding 3).** → Mitigation accepted: only SENDER-side shadow-ban skips publish (per `auth-realtime/spec.md:37` invisible-actor model — shadow-banned subscribers ARE allowed to subscribe; the receiver's shadow-ban state is irrelevant to the publish decision). Spec § Publish-side shadow-ban skip documents this explicitly so future readers don't infer "publish hides from shadow-banned receivers too."
- **Risk: banned (not shadow-banned) sender publish-skip not specified (adversarial round-3 finding 3 secondary).** → Mitigation: chat-foundation's auth path 403s banned senders BEFORE the chat send handler runs. The publish step is reached only by senders who passed auth (i.e., NOT banned). Spec § Publish-side shadow-ban skip notes this dependency on chat-foundation's auth check — no additional ban-skip logic needed in THIS change.
- **Risk: retry-induced duplicate broadcasts (adversarial round-3 finding 10).** → Mitigation accepted: if a Supabase 5xx is returned but the broadcast was actually fan-out to subscribers (partial-failure), the retry produces a duplicate broadcast. Mobile dedup via `id` (per `docs/05-Implementation.md:1216`) is the contract. Spec § Out-of-scope documents: "duplicate broadcasts on partial-failure retry IS expected; mobile dedup via id is the recovery contract."
- **Risk: conversation deleted mid-publish (adversarial round-3 finding 9).** → Mitigation: not in scope here. No conversation-delete endpoint exists yet. If a future cleanup worker / admin tool deletes a conversation between commit and publish, subscribers receive a payload for a now-orphaned row; mobile clients refetch via REST and get 404, handling as a deleted message. Spec § Out-of-scope notes this for the future deletion-worker change author.
- **Trade-off: post-commit publish vs in-tx publish** — chosen post-commit (D1). The cost is that a consumer who subscribes-then-checks-via-REST in the wrong order may see a brief window where the message is in DB but not yet broadcast. Mobile UX guidelines (deferred, Phase 3) handle this by always treating REST as authoritative on first-load.
- **Trade-off: ChatRealtimeClient interface extension (`publish` added to a sketch that originally had only `subscribe`/`unsubscribe`)** — the [`docs/04-Architecture.md:139-149`](../../../docs/04-Architecture.md) sketch shows the SUBSCRIBER side. Adding `publish` extends the interface for the publisher side. Acceptable because (a) the architecture doc sketch is an abstraction, not a complete contract; (b) the post-swap `KtorWebSocketChatClient` will need both sides too (publish via Redis Streams, subscribe via WebSocket); (c) keeping both in one interface lets the DI binding pick a single implementation per environment.

## Migration Plan

This is a server-side capability addition. No data migration. No mobile change required (the mobile subscriber path is built later in Phase 3 against the same channel name + payload contract).

**Deploy order:**

1. Land secret slots `staging-supabase-service-role-key` and `supabase-service-role-key` in GCP Secret Manager (manual step, captured in Phase 1 tasks).
2. Merge proposal PR + implementation PR (one-PR-per-change per `openspec/project.md` § Change Delivery Workflow + parent `/next-change` skill).
3. CI auto-deploys to staging. Phase 7 staging-smoke validates publish round-trip end-to-end (per `tasks.md` § 7. Staging deploy + smoke).
4. Manual approval gates prod deploy (per `docs/04-Architecture.md` deployment table).

**Rollback:** if publish causes elevated chat-send latency or persistent failure, revert via standard rollback (revert the merge commit, redeploy). The chat handler's pre-this-change behavior (persist + return 201, no publish) is the safe fallback. No DB schema changes, so rollback is a pure code revert.

## Open Questions

After round-3 review, all major scope decisions are pinned:

- **OTel observability**: descoped to a future `observability-otel-foundation` change (D9 explanation; FOLLOW_UPS entry filed in `tasks.md` Phase 9.6).
- **Module placement**: extends existing `:infra:supabase` (D8 update; Phase 2 tasks rewritten in lock-step).
- **`UserPrincipal.isShadowBanned` field**: added in this change's Phase 2.6; future docs-only OpenSpec change documents the field alongside `subscriptionStatus` (extends existing `auth-jwt-spec-debt-userprincipal-subscription-status` FOLLOW_UPS entry per `tasks.md` Phase 9.4).
- **Crash/cancellation contract**: best-effort post-commit publish + REST resync recovery; no outbox introduced (Risks section).
- **Caller-allowlist**: paper-trail non-goal (D11); future authors flag at PR review.
- **Retry timing tolerance**: ±150ms (relaxed from ±50ms per `:infra:redis` precedent — see `tasks.md` Phase 5.18).
- **Test 17 (D2 race) determinism**: pinned via test-injectable hook (see `spec.md` § Publish-side shadow-ban skip scenario "Shadow-ban skip race tolerance" — test mechanism noted in `tasks.md` Phase 5.19).

The canonical docs anchor every load-bearing decision (D1 tx boundary via 04-Architecture.md:69; D2 shadow-ban skip via auth-realtime/spec.md:37; D4 retry policy via 05-Implementation.md:1213; D5 channel name via the V15 RLS regex; D6 payload via the chat_messages columns; D7 credential via the service-role-key convention; D8 module placement via 04-Architecture.md:149). The reconciliation pass (Phase 1 tasks) re-verifies each anchor before code lands.
