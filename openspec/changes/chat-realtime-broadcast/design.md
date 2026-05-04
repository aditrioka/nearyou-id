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

### D1. Tx boundary: publish runs AFTER the chat-foundation transaction commits

**Choice:** the publish call is dispatched after the `INSERT chat_messages` + `UPDATE conversations.last_message_at` transaction commits. The handler captures the inserted row's projection, completes the tx, then invokes `chatRealtimeClient.publish(...)` outside the tx scope.

**Rationale:**
- Persistence is the source of truth (`docs/04-Architecture.md:69` — Ktor as authoritative publisher; persistence-then-broadcast). If publish were inside the tx, a slow Supabase call would hold a row lock on `chat_messages` and `conversations` for the full broadcast duration, multiplying contention with `chat-foundation`'s `last_message_at` UPDATE.
- A failed publish must not roll back the persisted INSERT (the message is visible via REST `GET /messages` regardless). In-tx failure semantics would force rollback-or-swallow, both wrong.
- The chat-foundation `chat-conversations/spec.md` § Send-message endpoint scenario "Send updates last_message_at atomically" requires the INSERT and UPDATE be in a single tx — but the post-commit publish does not undermine that requirement.

**Alternative rejected (in-tx publish):** would couple Supabase availability to chat send latency, expand the lock window, and create a path where a transient Supabase 503 fails the whole HTTP request despite the message being legitimately persistable.

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

### D8. Module placement: new `:infra:supabase` Gradle module

**Choice:** create a new `:infra:supabase` module containing `SupabaseBroadcastChatClient` and the Supabase SDK dependency. The `ChatRealtimeClient` interface and `ChatMessageBroadcast` / `PublishResult` types live in `:core:domain`. The chat-module handler in `:backend:ktor` injects the interface; the binding is in `:backend:ktor`'s DI configuration.

**Rationale:**
- `CLAUDE.md` § Critical invariants: "No vendor SDK import outside `:infra:*` — domain/data code depends only on interfaces."
- [`docs/04-Architecture.md:149`](../../../docs/04-Architecture.md) names `:infra:supabase` as the canonical module for `SupabaseBroadcastChatClient`.
- A new module triggers the `dev/module-descriptions.txt` + `dev/scripts/sync-readme.sh --write` requirement per `CLAUDE.md` § Critical invariants. Phase 2 task captures this.

**Alternative rejected (put `SupabaseBroadcastChatClient` in `:backend:ktor`):** violates the no-vendor-SDK-outside-`:infra:*` rule. Even though the only caller is `:backend:ktor`, the rule exists so the future `:infra:ktor-ws` swap is mechanical (delete one module, add another, change DI binding).

### D9. Observability: OTel span around publish + structured WARN on failure

**Choice:** `SupabaseBroadcastChatClient.publish(...)` emits an OTel span named `chat.realtime.publish` with attributes:

- `supabase.realtime.channel = realtime:conversation:<conv_id>` (mandatory per `docs/04-Architecture.md:398`)
- `user_id` (hashed via the existing helper, mandatory per `docs/04-Architecture.md:398`)
- `chat.message_id = <uuid>` (NOT hashed — UUIDs are opaque random identifiers with no PII content; convention asymmetry vs `user_id` is acknowledged: `user_id` is hashed because the raw user UUID is a stable cross-event correlator that could let a log reader build a per-user activity profile, while `chat.message_id` is single-use and surfaces no aggregation surface)
- Span status `OK` on `Success`, `ERROR` with `error.type` = exception class name on `Failure`.

On `Failure` after retry exhaustion, the chat handler ALSO logs a structured WARN: `event = "chat_realtime_publish_failed"`, `conversation_id`, `message_id`, `error_class`. Log level is WARN (not ERROR) because the user-visible outcome is fine — REST resync covers it.

**Secret-leakage guarantees:** the resolved Supabase service-role-key VALUE (loaded via `secretKey(env, "supabase-service-role-key")`) MUST NEVER appear as a span attribute, log field, HTTP request/response that gets logged, or any other observable. The spec includes both a literal-source-grep scenario (the slot name only appears at the `secretKey(...)` call site) AND a defense-in-depth scenario (no captured span/log emitted by `chat.realtime.publish` contains the resolved key value as an attribute or message). The `RawSecretReadRule` Detekt rule referenced in earlier drafts of this design does NOT exist on disk; enforcement is via static-grep tests aligned with the [`auth-realtime/spec.md:31-33`](../../specs/auth-realtime/spec.md) precedent for `supabase-jwt-secret`.

**Rationale:**
- `docs/04-Architecture.md:398` explicitly lists `supabase.realtime.channel` as a mandatory OTel attribute.
- WARN (not ERROR) prevents Sentry-noise when transient Supabase outages cause harmless REST-resync recovery — but ops can still set up an alert on `event = "chat_realtime_publish_failed"` rate.

### D10. Test layering: `ChatRealtimeBroadcastTest` mocks `ChatRealtimeClient`

**Choice:** integration tests in `:backend:ktor` against the chat handler use a `FakeChatRealtimeClient` test double for behavioral assertions (publish invoked / not invoked / failure handled). Real Supabase round-trip is tested separately at the `:infra:supabase` module level by `SupabaseBroadcastChatClientTest` (network-tagged), gated to the staging-smoke phase per the `like-rate-limit` precedent.

**Rationale:**
- Decouples behavioral correctness (handler did the right thing given a publish result) from infra correctness (the SDK actually talks to Supabase). Same pattern as `like-rate-limit`'s `InMemoryRateLimiter` vs `RedisRateLimiterIntegrationTest` split.
- Behavioral tests run on every CI; infra tests gated to staging-smoke avoid network flake on every PR.

## Risks / Trade-offs

- **Risk: Supabase broadcast outage with high message volume burns the retry budget on every send.** → Mitigation: 3-retry cap with bounded backoff (100/300/900 ms) limits per-request added latency to ~1.3s worst case. Failure path is WARN-and-move-on, not 5xx; chat persists regardless. A circuit-breaker layer is NOT added in this change (rule-of-three: one call site is too few; revisit if a second publish-style infra adapter accumulates).
- **Risk: payload schema drift between server publish and mobile consumer.** → Mitigation: spec pins the payload field set explicitly; integration test asserts the exact payload shape; future `chat-embedded-posts` change MUST extend the schema with NEW fields (additive only, never rename or repurpose existing fields).
- **Risk: shadow-ban skip race — sender becomes shadow-banned between INSERT and post-commit publish.** → Mitigation: the publish-side check reads `is_shadow_banned` from the same `users` row consulted by the chat handler's auth/principal context (already loaded). If a moderator flips `is_shadow_banned = TRUE` between auth and publish, the publish still emits (using stale state). This is acceptable per the invisible-actor model — an in-flight message slips through but all subsequent messages are filtered. Mobile consumer-side filter (`docs/05-Implementation.md:1880`) catches this race as defense-in-depth.
- **Risk: service role key exposure in OTel span attributes / logs.** → Mitigation: the credential is read once at startup via `secretKey(env, name)` and stored only in the Supabase SDK client's internal state; never logged, never set as a span attribute. Enforcement is via the static-grep test scenario shape (matches the precedent set by [`auth-realtime/spec.md:31-33`](../../specs/auth-realtime/spec.md) for `supabase-jwt-secret`): the spec includes a scenario asserting the literal string `"supabase-service-role-key"` appears only at the `secretKey(env, ...)` call site, AND a defense-in-depth scenario asserting the resolved key VALUE never appears in any captured OTel span attribute or log field. NOTE: an earlier draft of this design referenced a `RawSecretReadRule` Detekt rule; that rule does not exist on disk (the lint rules in `lint/detekt-rules/` are RateLimitTtlRule, RedisHashTagRule, RawFromPostsRule, RawXForwardedForRule, BlockExclusionJoinRule, CoordinateJitterRule). The `secretKey(env, name)` convention is enforced via static grep tests, not Detekt — aligned with auth-realtime precedent.
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

None at proposal time. The canonical docs anchor every load-bearing decision (D1 tx boundary via 04-Architecture.md:69; D2 shadow-ban skip via auth-realtime/spec.md:37; D4 retry policy via 05-Implementation.md:1213; D5 channel name via the V15 RLS regex; D6 payload via the chat_messages columns; D7 credential via the service-role-key convention; D8 module placement via 04-Architecture.md:149). The reconciliation pass (Phase 1 tasks) re-verifies each anchor before code lands.
