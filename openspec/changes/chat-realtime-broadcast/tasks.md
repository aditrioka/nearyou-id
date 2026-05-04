## 1. Reconciliation pass against canonical docs

- [ ] 1.1 Verify the V15 RLS topic regex (`chat-conversations` § Realtime RLS test set) matches the channel name format `realtime:conversation:<conv_id>` chosen in `design.md` § D5; capture the regex source line ([V2__auth_foundation.sql:64-89](../../../backend/ktor/src/main/resources/db/migration/V2__auth_foundation.sql)) and confirm the V15 install carries the same regex
- [ ] 1.2 Verify [`docs/05-Implementation.md:1213-1214`](../../../docs/05-Implementation.md) prescribes 3x retry + WARN log; confirm `design.md` § D4 + `spec.md` § Requirement: 3x retry... matches verbatim
- [ ] 1.3 Verify [`docs/04-Architecture.md:139-149`](../../../docs/04-Architecture.md) names `:infra:supabase` as the canonical module for `SupabaseBroadcastChatClient`; confirm `design.md` § D8 + `spec.md` § Requirement: SupabaseBroadcastChatClient implementation in :infra:supabase matches
- [ ] 1.4 Verify [`docs/04-Architecture.md:398`](../../../docs/04-Architecture.md) lists `supabase.realtime.channel` and `user_id` (hashed) as mandatory OTel attributes; confirm `design.md` § D9 + `spec.md` § Requirement: OTel span around publish matches
- [ ] 1.5 Verify [`auth-realtime/spec.md:37`](../../specs/auth-realtime/spec.md) explicitly names this change as the owner of publish-side shadow-ban hiding; confirm `design.md` § D2 + `spec.md` § Requirement: Publish-side shadow-ban skip matches
- [ ] 1.6 Cross-check [`docs/05-Implementation.md:1880`](../../../docs/05-Implementation.md) ("Chat delivery: filter when consuming from Supabase Realtime broadcast (application-level)") against `design.md` § D2 reconciliation note; confirm the consumer-side note is mobile-layer defense-in-depth (not in conflict with publish-side skip)
- [ ] 1.7 Verify [`docs/05-Implementation.md:1198-1216`](../../../docs/05-Implementation.md) chat flow pseudocode against `design.md` § D1 (post-commit publish) and § D3 (broadcast failure does not roll back); the flow shows persistence-then-broadcast — confirm alignment
- [ ] 1.8 Verify the canonical Pre-Phase 1 secret-slot list at [`docs/08-Roadmap-Risk.md` § Pre-Phase 1 #34](../../../docs/08-Roadmap-Risk.md) does NOT yet include `staging-supabase-service-role-key` / `supabase-service-role-key`; if confirmed, file a `FOLLOW_UPS.md` entry for a docs amendment (per Phase 6 below)
- [ ] 1.9 Re-check [`docs/05-Implementation.md:1200`](../../../docs/05-Implementation.md) — its pseudocode "validates quota, sender is participant, not shadow-banned, not blocked" includes a sender-shadow-ban pre-check that contradicts the chat-foundation invisible-actor model (where shadow-banned senders DO insert). This is a pre-existing chat-foundation residue, NOT introduced by this change. File a `FOLLOW_UPS.md` entry under Phase 6 noting the doc divergence; do NOT amend in this change

## 2. Module + interface scaffold

- [ ] 2.1 Add `:infra:supabase` to [`settings.gradle.kts`](../../../settings.gradle.kts) as a new module
- [ ] 2.2 Create the module Gradle build file (`infra/supabase/build.gradle.kts`) with the conventional plugins (Kotlin, Detekt, ktlint) matching sibling `:infra:*` modules; do NOT yet add the Supabase SDK dependency until Phase 3 picks the library
- [ ] 2.3 Add a one-line description for `:infra:supabase` to [`dev/module-descriptions.txt`](../../../dev/module-descriptions.txt) (e.g., `:infra:supabase — Supabase Realtime broadcast publish (chat-realtime-broadcast)`)
- [ ] 2.4 Run `dev/scripts/sync-readme.sh --write` to regenerate the root README module list; verify the new module appears
- [ ] 2.5 Define `ChatRealtimeClient`, `ChatMessageBroadcast`, `PublishResult` in `:core:domain`'s chat package; suspend `publish` method returns `PublishResult`
- [ ] 2.6 Run `./gradlew ktlintCheck detekt` — confirm CI lint passes for the new files

## 3. SupabaseBroadcastChatClient implementation

- [ ] 3.1 Pick the Supabase server-side library: research Maven Central for an SDK that supports server-originated broadcast publish over the Realtime HTTP API; pin in [`gradle/libs.versions.toml`](../../../gradle/libs.versions.toml) and add a row to [`docs/09-Versions.md`](../../../docs/09-Versions.md). Fallback if no good SDK exists: hand-rolled REST client using Ktor HttpClient against `https://<project>.supabase.co/realtime/v1/api/broadcast` with the service role key
- [ ] 3.2 Add the dependency to `:infra:supabase`'s build.gradle.kts
- [ ] 3.3 Implement `SupabaseBroadcastChatClient`:
    - [ ] 3.3.1 Constructor: takes `serviceRoleKey: String` (resolved at startup via `secretKey(env, "supabase-service-role-key")`) and the project URL
    - [ ] 3.3.2 `publish(...)` builds the channel name exactly `realtime:conversation:<conversationId>`
    - [ ] 3.3.3 Serialize `ChatMessageBroadcast` to JSON matching the spec payload schema (9 fields, `redaction_reason` always omitted, `embedded_*` always present-with-null in this change)
    - [ ] 3.3.4 Wrap the HTTP/SDK call in OTel span `chat.realtime.publish` with attributes `supabase.realtime.channel`, `user_id` (hashed via existing helper), `chat.message_id`
    - [ ] 3.3.5 Retry policy: 3 total attempts (1 initial + 2 retries), exponential backoff ~100ms, ~300ms, ~900ms; retry on IOException / SocketTimeoutException / HTTP 5xx; do NOT retry on 4xx
    - [ ] 3.3.6 Return `PublishResult.Success` on first successful response; return `PublishResult.Failure(<lastErrorClassName>)` on retry exhaustion
- [ ] 3.4 Wire DI in `:backend:ktor`: bind `ChatRealtimeClient` → `SupabaseBroadcastChatClient` for `dev`, `staging`, `production` environments
- [ ] 3.5 Add a no-op `NoopChatRealtimeClient` for unit-test suites that don't want the real adapter (returns `Success` immediately, captures invocation count)
- [ ] 3.6 Run `./gradlew :infra:supabase:test :infra:supabase:detekt` — confirm green

## 4. Chat handler wiring

- [ ] 4.1 Inject `ChatRealtimeClient` into the chat send route handler (currently in `:backend:ktor`'s chat module)
- [ ] 4.2 After the chat-foundation `INSERT chat_messages` + `UPDATE conversations.last_message_at` transaction commits, capture the inserted row's projection (id, conversation_id, sender_id, content, embedded_*, created_at, redacted_at = null) into a `ChatMessageBroadcast` value
- [ ] 4.3 Branch on sender shadow-ban state (read from `viewer.isShadowBanned` on the request principal — same field used by the chat-conversations read-path filter): if `TRUE`, SKIP `publish` entirely; if `FALSE`, invoke `chatRealtimeClient.publish(conversationId, broadcast)`
- [ ] 4.4 On `PublishResult.Failure(reason)` OR thrown exception: emit a structured WARN log with fields `event = "chat_realtime_publish_failed"`, `conversation_id`, `message_id`, `error_class`; do NOT alter the HTTP response (still 201)
- [ ] 4.5 On `PublishResult.Success`: no extra logging needed (OTel span already captures success)
- [ ] 4.6 Verify the publish call is NOT inside the chat-foundation `transaction { ... }` block (use code reading + a behavioral test in Phase 5)
- [ ] 4.7 Run `./gradlew :backend:ktor:detekt :backend:ktor:ktlintCheck` — confirm green

## 5. Integration tests (`:backend:ktor`)

- [ ] 5.1 Create `ChatRealtimeBroadcastTest.kt` alongside `ChatSendRouteTest.kt` and `ChatSendRateLimitTest.kt`, tagged `database`
- [ ] 5.2 Implement `FakeChatRealtimeClient` test double: captures all `publish` invocations (timestamps, conv id, broadcast payload), can be configured per-test to return `Success`, `Failure(reason)`, or throw
- [ ] 5.3 Test 1: successful send invokes publish exactly once with payload matching the persisted row (assert all 9 payload fields)
- [ ] 5.4 Test 2: shadow-banned sender's send persists the row but does NOT invoke publish
- [ ] 5.5 Test 3: publish returns Failure → HTTP 201, row persists, last_message_at unchanged from publish failure, WARN log line emitted with `event = "chat_realtime_publish_failed"` and `error_class = "Failure"`
- [ ] 5.6 Test 4: publish throws IOException → same outcome as Test 3 with `error_class = "IOException"`
- [ ] 5.7 Test 5: block-rejected send (403) → publish NOT invoked
- [ ] 5.8 Test 6: rate-limited send (429 from chat-rate-limit) → publish NOT invoked
- [ ] 5.9 Test 7: 400 invalid_request (empty content) → publish NOT invoked
- [ ] 5.10 Test 8: 404 conversation_not_found → publish NOT invoked
- [ ] 5.11 Test 9: 403 not_a_participant → publish NOT invoked
- [ ] 5.12 Test 10: channel name format — capture the `conversationId` arg, assert `"conversation:" + conversationId.toString()` matches the V15 RLS regex `^conversation:[0-9a-f-]{36}$`
- [ ] 5.13 Test 11: payload format — capture `ChatMessageBroadcast`, assert all 9 fields present (embedded_* are null for this change), assert `redaction_reason` is NOT a field on the broadcast type
- [ ] 5.14 Test 12: tx rollback on INSERT failure → publish NOT invoked AND zero `chat_messages` rows persist
- [ ] 5.15 Test: post-commit ordering — assert via captured wall-clock timestamps that publish is invoked AFTER the row is visible in a separate connection's SELECT (read-after-write)
- [ ] 5.16 Run `./gradlew :backend:ktor:test --tests '*ChatRealtimeBroadcastTest*'` — confirm all green

## 6. Adapter-level integration tests (`:infra:supabase`)

- [ ] 6.1 Create `SupabaseBroadcastChatClientTest.kt` in `:infra:supabase`, tagged `network` (gated to staging-smoke runs)
- [ ] 6.2 Test: publish to a real staging Supabase Realtime project succeeds (channel = `realtime:conversation:<test-uuid>`, payload matches contract)
- [ ] 6.3 Test: bad credential → `PublishResult.Failure` after 3 attempts (verify retry count via test-injected counter or by HTTP request count)
- [ ] 6.4 Test: simulated 503 then success → `PublishResult.Success` after retry (verify exactly 2 attempts via test injection)
- [ ] 6.5 Verify CI runner skips this class when staging credentials are absent (matches the precedent set by `like-rate-limit`'s `RedisRateLimiterIntegrationTest`)

## 7. Staging deploy + smoke

- [ ] 7.1 Land secret slots `staging-supabase-service-role-key` (staging GCP Secret Manager) and document `supabase-service-role-key` (prod, deferred to actual prod deploy)
- [ ] 7.2 Trigger `gh workflow run deploy-staging.yml --ref chat-realtime-broadcast` to deploy the branch to staging
- [ ] 7.3 Smoke check: as a real test user, send a chat message via `POST /api/v1/chat/{conversation_id}/messages`; assert HTTP 201 and a row in `chat_messages` (REST data plane unaffected)
- [ ] 7.4 Smoke check: subscribe a second test user to `realtime:conversation:<conv_id>` via the Supabase Realtime client (using a token from `GET /api/v1/realtime/token`); send from user A; assert user B receives the broadcast within ~1 second
- [ ] 7.5 Smoke check: shadow-ban user A in the staging DB; send a message as A; assert user B does NOT receive a realtime broadcast (publish-side skip works) AND the row IS persisted (REST `GET /messages` from A's perspective shows it)
- [ ] 7.6 Smoke check: induce a Supabase outage (e.g., temporarily revoke the service role key via Supabase Dashboard); send a message; assert HTTP 201 still returned, row persisted, WARN log line `chat_realtime_publish_failed` appears in Cloud Logging within 30 seconds
- [ ] 7.7 Restore the service role key; confirm subsequent sends broadcast normally
- [ ] 7.8 Capture smoke output as a comment on the PR (matches `chat-rate-limit` precedent)

## 8. CI lint + final verification

- [ ] 8.1 Run `./gradlew ktlintCheck detekt :backend:ktor:test :infra:supabase:test :lint:detekt-rules:test` locally per `CLAUDE.md` § Pre-push verification
- [ ] 8.2 Verify `RawSecretReadRule` (or the `secretKey(env, name)` enforcement) is satisfied: `grep -rn 'supabase-service-role-key' backend/` shows occurrences only at the `secretKey(env, ...)` call site
- [ ] 8.3 Verify no Supabase SDK imports in `:core:domain` or `:backend:ktor` source: `grep -rn 'io.supabase\|io.github.jan-tennert.supabase' core/domain/src/ backend/ktor/src/` returns zero matches
- [ ] 8.4 Run `openspec validate chat-realtime-broadcast --strict` — fix any failures before pushing the implementation
- [ ] 8.5 Run `./gradlew assemble` — confirm the staging Docker image builds with `:infra:supabase` correctly COPY'd (analogous to PR #42's `:infra:redis` Docker fix; verify `Dockerfile` includes the new module)

## 9. Documentation maintenance

- [ ] 9.1 If Phase 1 task 1.8 confirmed the Pre-Phase 1 secret-slot list at `docs/08-Roadmap-Risk.md` lacks `supabase-service-role-key`: add a `FOLLOW_UPS.md` entry `pre-phase-1-secret-slot-list-supabase-service-role-key` requesting a docs-only amendment to the Pre-Phase 1 #34 section listing both `staging-supabase-service-role-key` and `supabase-service-role-key`. Action item: file a docs PR after this change archives, OR batch with the next OpenSpec change that touches the Pre-Phase 1 section
- [ ] 9.2 If Phase 1 task 1.9 confirmed `docs/05-Implementation.md:1200` carries a stale "not shadow-banned" sender pre-check (chat-foundation residue): add a `FOLLOW_UPS.md` entry `chat-flow-pseudocode-shadow-ban-precheck-stale` requesting a docs-only amendment to remove the "not shadow-banned" clause from the Pre-Swap chat flow pseudocode; this is chat-foundation's residue, not introduced here, but worth tracking
- [ ] 9.3 If neither 9.1 nor 9.2 surfaces a confirmed divergence (i.e., docs are up-to-date), DO NOT add empty entries; skip these tasks

## 10. PR maintenance

- [ ] 10.1 At the boundary from proposal-review to implementation: retitle the PR via `gh pr edit <pr> --title 'feat(chat): chat-realtime-broadcast (publish leg + ChatRealtimeClient + SupabaseBroadcastChatClient)'` per `CLAUDE.md` § "PR title and body MUST stay current at every phase boundary"
- [ ] 10.2 Update the PR body via `gh pr edit <pr> --body "$(cat <<'EOF' ... EOF)"` to reflect the implementation diff (modules added, secrets to provision, smoke-check evidence link)
- [ ] 10.3 At archive boundary: retitle if needed AND update the body to point to archive paths
