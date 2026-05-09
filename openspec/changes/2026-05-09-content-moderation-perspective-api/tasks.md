# content-moderation-perspective-api — Tasks

## 1. External-data sanity checks (gate before any coding)

- [ ] 1.1 Verify Perspective API endpoint URL + request shape against the live API docs (https://developers.perspectiveapi.com/s/about-the-api-methods?language=en_US) — `https://commentanalyzer.googleapis.com/v1alpha1/comments:analyze` is the documented endpoint as of design time; confirm at task-start (vendor URLs drift). Verify the canonical attribute names spelled exactly: `TOXICITY`, `SEVERE_TOXICITY`, `IDENTITY_ATTACK`, `THREAT`. Document the verification date in the implementation commit body.
- [ ] 1.2 Verify Perspective API supports `languages: ["id", "en"]` per the live language-support page (currently both listed; ID is in partial-coverage beta). If Indonesian support has been deprecated / rotated to a different identifier, surface to the user before proceeding.
- [ ] 1.3 Verify the GCP Secret Manager slot reservations: `perspective-api-key` (production) AND `staging-perspective-api-key` (staging). If either is absent at task-start, document the gap in `FOLLOW_UPS.md` (slot creation is operations work, not in-code work; spec scenarios still verify the canonical resolve-call shape independent of slot presence via fixture). Tracked separately as a Phase 7 staging-readiness gate.

## 2. `:infra:perspective-api` module scaffold

- [ ] 2.1 Create directory `infra/perspective-api/` with `build.gradle.kts` mirroring [`infra/remote-config/build.gradle.kts`](../../../infra/remote-config/build.gradle.kts) — declare `kotlin("jvm")` plugin, the standard test dependencies (kotest, kotlinx-coroutines-test, mockk for fake-server). Add new `libs.versions.toml` entries: `ktor-client` version pinned to the existing Ktor server version (`3.4.x` line) and three implementation deps: `ktor-client-core`, `ktor-client-cio`, `ktor-client-content-negotiation`, `ktor-client-serialization-kotlinx-json`. NO Koin / Ktor server dependencies (the module exposes pure-data interfaces consumed by `:backend:ktor`).
- [ ] 2.2 Add `include(":infra:perspective-api")` to [`settings.gradle.kts`](../../../settings.gradle.kts) alongside the other `:infra:*` includes.
- [ ] 2.3 Add a one-line entry to [`dev/module-descriptions.txt`](../../../dev/module-descriptions.txt) describing `:infra:perspective-api` (e.g., `:infra:perspective-api — Google Perspective API toxicity classifier client (Layer 3 of text moderation)`). Run `dev/scripts/sync-readme.sh --write` to regenerate the README module list per [`CLAUDE.md`](../../../CLAUDE.md) "Root README module list is auto-generated" maintenance trigger.
- [ ] 2.4 Implement `infra/perspective-api/src/main/kotlin/id/nearyou/app/infra/perspectiveapi/PerspectiveApiClient.kt` — the public `interface PerspectiveApiClient { suspend fun analyze(content: String): Float? }` per spec `### Requirement: PerspectiveApiClient interface in :infra:perspective-api`. The `Float?` return is the max() of 4 attribute summary scores in `[0.0, 1.0]`, or `null` on any failure (timeout / HTTP error / parse error).
- [ ] 2.5 Implement `infra/perspective-api/src/main/kotlin/id/nearyou/app/infra/perspectiveapi/KtorPerspectiveApiClient.kt` — the production implementation using `HttpClient(CIO) { install(ContentNegotiation) { json() } }`. Constructor takes the API key string + an optional `requestTimeoutMillis: Long = 500` (the canonical 500 ms timeout from the spec). The `analyze(content)` implementation:
  1. Builds the request body via a `@Serializable` data class hierarchy mirroring the Perspective API request shape (`{"comment":{"text":...},"requestedAttributes":{...},"languages":["id","en"]}`).
  2. POSTs to `https://commentanalyzer.googleapis.com/v1alpha1/comments:analyze?key=<api_key>` with `Content-Type: application/json`.
  3. Parses the response into a `@Serializable` response data class.
  4. Returns `max()` of the 4 attribute summary scores, or `null` on any failure path.
  All failure paths are caught — timeout via `withTimeout(requestTimeoutMillis)` wrapping the call; HTTP errors via try/catch on `HttpClient`'s `ResponseException`; parse errors via try/catch on `SerializationException`. Log a structured WARN line on each failure with `event=perspective_api_client_fail` `outcome=<category>`.
- [ ] 2.6 Implement `infra/perspective-api/src/test/kotlin/id/nearyou/app/infra/perspectiveapi/FakePerspectiveApiClient.kt` — a deterministic test fake with `var nextScore: Float? = null` returned on every call. Used by `:backend:ktor` integration tests via Koin override.
- [ ] 2.7 Add unit tests at `infra/perspective-api/src/test/kotlin/id/nearyou/app/infra/perspectiveapi/KtorPerspectiveApiClientTest.kt` using `MockEngine` from `ktor-client-mock` to stub HTTP responses. Cover all spec scenarios: 200 with all 4 attributes scored → returns max; 200 with partial attributes → returns max of present; 200 with empty `attributeScores` → returns `null` (parse error); 429 → returns `null` (HTTP error); 500 → returns `null`; network unreachable → returns `null`; response delayed beyond 500 ms → returns `null` (timeout); malformed JSON → returns `null`. Verify request body shape via `MockEngine` request inspection (check the JSON contains exactly the 4 requested attributes + both languages).
- [ ] 2.8 Add a Detekt-rules guard ensuring `:backend:ktor` source files contain NO `import io.ktor.client.*` (the Ktor client is encapsulated in `:infra:perspective-api`; the server still uses `ktor-server-*`). Reuse the structural test pattern from `:backend:ktor/FcmDispatchStructuralTest`. If trivial to implement, ship; if awkward, defer to a `FOLLOW_UPS.md` entry.

## 3. `PerspectiveDispatcher` orchestrator in `:backend:ktor`

- [ ] 3.1 Add new file `backend/ktor/src/main/kotlin/id/nearyou/app/moderation/PerspectiveDispatcher.kt` defining:
  ```
  enum class PerspectiveTargetType { POST, REPLY }
  interface PerspectiveDispatcher {
      fun dispatchAsync(targetType: PerspectiveTargetType, targetId: UUID, content: String)
      suspend fun shutdown(drainMillis: Long = 5_000)
      // test-only:
      suspend fun awaitInFlight()
  }
  ```
  + an `AsyncPerspectiveDispatcher` implementation owning the `CoroutineScope(SupervisorJob() + Dispatchers.IO)` per design D7. `dispatchAsync` launches a child coroutine; the child:
  1. Reads `perspective_api_enabled` via `RemoteConfigClient.fetchBoolean("perspective_api_enabled")` (cached 5 min via Redis hash-tag key `{scope:perspective}:{key:enabled}`). If `null` (cascade-default) → treat as TRUE per Pre-Phase 1 §18 default.
  2. If kill-switch OFF → emit single WARN log with `event=perspective_api_fail_open` `outcome=kill_switch_off` and return.
  3. Else: call `client.analyze(content)`. On `null` → emit WARN with `outcome=<timeout|http_error|parse_error>` (the client distinguishes via its own logging; the dispatcher just sees `null`) and return.
  4. On non-null score: dispatch by threshold per design D8 — `> 0.8` → UPDATE + INSERT in same transaction; `[0.6, 0.8]` → INSERT only; `< 0.6` → return.
- [ ] 3.2 Implement the kill-switch read with caching: a new method `RemoteConfigClient.fetchBoolean(name)` already exists per the `:infra:remote-config` interface (PR #70). Caching is via the existing Redis cache pattern; key shape `{scope:perspective}:{key:enabled}` per the Redis hash-tag invariant. Reuse the existing `RedisStringCache` interface from `:infra:redis`; serialize `Boolean` as `"true"` / `"false"` strings. TTL 5 minutes (mirrors the moderation-list cache TTL). On Redis cache miss + Remote Config null return → default TRUE (Pre-Phase 1 §18 canonical default).
- [ ] 3.3 Implement the structured WARN log emission. Use the project's existing structured-logging helper (likely `slf4j` MDC wrapper; check the canonical pattern in `FcmDispatcher`). Log fields: `event`, `outcome`, `target_type`, `target_id`, `latency_ms` (the millis spent in the `client.analyze` call; 0 for kill-switch path; full elapsed for timeout / HTTP error). The log MUST NOT include `content` or any user identifier. Per design D5, also raise a Sentry breadcrumb at WARN level for `outcome ∈ {timeout, http_error, parse_error}`; NOT for `outcome=kill_switch_off`.
- [ ] 3.4 Implement the score-threshold writes per design D8:
  - `score > 0.8`: open a JDBC transaction, UPDATE `posts SET is_auto_hidden = TRUE WHERE id = :target_id AND deleted_at IS NULL` (for `targetType = POST`) or `UPDATE post_replies SET is_auto_hidden = TRUE WHERE id = :target_id AND deleted_at IS NULL` (for `targetType = REPLY` — V8 uses the same `deleted_at TIMESTAMPTZ` shape as V4), then INSERT into `moderation_queue (target_type, target_id, trigger, priority, status) VALUES (?, ?, 'perspective_api_high_score', 5, 'pending') ON CONFLICT (target_type, target_id, trigger) DO NOTHING`. Commit. The `notes` column stays NULL (the canonical contract — no content leaks into the queue).
  - `0.6 ≤ score ≤ 0.8`: single-statement INSERT into `moderation_queue` with the same shape; no UPDATE. Use auto-commit or wrap in a single-statement transaction for code-path symmetry (test surface easier to reason about).
  - `score < 0.6`: noop.
- [ ] 3.5 Implement the `shutdown(drainMillis)` method: calls `scope.coroutineContext.job.cancelAndJoin()` inside `withTimeoutOrNull(drainMillis.milliseconds)`. Mirror `FcmDispatcherScope.shutdown` exactly. Register the shutdown hook in `Application.module()` (`Runtime.getRuntime().addShutdownHook(Thread { runBlocking { dispatcher.shutdown() } })`).
- [ ] 3.6 Implement the test-only `awaitInFlight()` helper: tracks active dispatch jobs in an internal `MutableList<Job>` (with synchronized access), each `dispatchAsync` adds to the list and removes-on-completion. `awaitInFlight()` calls `joinAll(activeJobs.toList())`. Marked `@TestOnly` via the project's annotation convention.
- [ ] 3.7 Add Koin singleton wiring in `Application.module()` for the `PerspectiveApiClient` (uses `secretKey(env, "perspective-api-key")` via the existing `SecretResolver`) and the `PerspectiveDispatcher`. Mirror the `FcmDispatcher` wiring shape — single block at the end of the moderation Koin module.

## 4. Unit + integration tests for `PerspectiveDispatcher`

- [ ] 4.1 Add `backend/ktor/src/test/kotlin/id/nearyou/app/moderation/PerspectiveDispatcherTest.kt` covering kill-switch + threshold + fail-open scenarios using `FakePerspectiveApiClient`. Cover all dispatcher-level spec scenarios: kill-switch OFF → no API call + no DB write + `outcome=kill_switch_off` log; client returns `null` → no DB write + `outcome=*` log per the underlying error category; score `0.85` → UPDATE + INSERT both committed; score `0.7` → INSERT only; score `0.3` → no DB write; idempotent retry under same `target_id` produces exactly one queue row (`ON CONFLICT DO NOTHING`); content sentinel `"DO-NOT-LEAK-CONTENT"` is absent from all log lines + Sentry breadcrumbs after dispatch.
- [ ] 4.2 Add `PerspectiveDispatcherShutdownTest` covering the structured-concurrency drain: 1 in-flight dispatch with a 100 ms `analyze` time + `shutdown(5_000)` → completes; 1 in-flight dispatch with a 10 s `analyze` time + `shutdown(5_000)` → cancels at the 5 s boundary; verify no exception propagates from `shutdown` itself. Use `kotlinx-coroutines-test` `TestScheduler` for deterministic timing per the precedent `fcm-shutdown-drain-deterministic-tests` follow-up established (so we don't repeat the deferred-test issue).
- [ ] 4.3 Add Sentry-payload-content tests: for every dispatcher outcome (kill-switch off, timeout, http_error, parse_error, score>0.8, score 0.6-0.8, score<0.6), capture the formatted log line + Sentry breadcrumb, assert the original content sentinel is absent. Reuse `LogCapture` helper from `TextModeratorTest`.

## 5. Integration: `POST /api/v1/posts`

- [ ] 5.1 Locate the canonical post-creation handler (likely `backend/ktor/.../post/PostRoutes.kt` + `CreatePostService`). Inject `PerspectiveDispatcher` via Koin into the service.
- [ ] 5.2 Wire `dispatcher.dispatchAsync(POST, post.id, post.content)` AFTER the canonical INSERT transaction commits AND BEFORE the route handler returns the 201 response. The dispatch is fire-and-forget; the handler does NOT await it.
- [ ] 5.3 Verify the call order: `contentGuard.enforce` → `textModerator.moderate` → `posts.create(...)` (commit) → `perspectiveDispatcher.dispatchAsync(...)` → `respond(201, post)`. Static-call-order assertion test extends `PostCreationCallOrderTest` with the new ordering invariant.
- [ ] 5.4 Add integration tests at `backend/ktor/src/test/kotlin/id/nearyou/app/post/PostCreationPerspectiveIntegrationTest.kt` covering:
  - High-score post (`FakePerspectiveApiClient.nextScore = 0.85`) → 201 returned + author sees post + `awaitInFlight()` → posts.is_auto_hidden = TRUE + 1 moderation_queue row with `trigger='perspective_api_high_score'`, `target_type='post'`, `target_id=<new_post_id>`, `status='pending'`.
  - Mid-score post (`nextScore = 0.7`) → 201 + post visible + queue row only + posts.is_auto_hidden remains FALSE.
  - Low-score post (`nextScore = 0.3`) → 201 + no queue row + posts.is_auto_hidden remains FALSE.
  - Fail-open post (`nextScore = null`) → 201 + no queue row + posts.is_auto_hidden remains FALSE + WARN log emitted.
  - Kill-switch OFF (`perspective_api_enabled = FALSE` via Remote Config fake) → 201 + no API call invoked (assert `FakePerspectiveApiClient.callCount == 0`) + no queue row.
  - Profanity-rejected content does NOT trigger Layer 3 (the dispatcher is invoked AFTER the commit, but if commit doesn't happen the dispatch isn't enqueued): assert `dispatcher.dispatchAsync` is not invoked when `TextModerator.moderate` returns `Reject`.
- [ ] 5.5 Add a regression assertion that the existing post-creation tests pass unchanged (the async dispatch must not affect the synchronous post-creation path's response or shape).

## 6. Integration: `POST /api/v1/posts/{post_id}/replies`

- [ ] 6.1 Locate the canonical post-replies handler. Inject `PerspectiveDispatcher`.
- [ ] 6.2 Wire `dispatcher.dispatchAsync(REPLY, reply.id, reply.content)` AFTER the canonical INSERT transaction commits AND BEFORE the response. Same fire-and-forget shape.
- [ ] 6.3 Extend `PostRepliesCallOrderTest` with the new ordering invariant: `resolveVisiblePost` → `textModerator.moderate` → `replies.insertInTx(...)` (commit) → `perspectiveDispatcher.dispatchAsync(...)` → `notifications.emit(...)` → `respond(201, reply)`. Note: notification emission stays AFTER the dispatch enqueue — the dispatch is non-blocking so this is harmless ordering.
- [ ] 6.4 Add integration tests at `backend/ktor/src/test/kotlin/id/nearyou/app/post/PostRepliesPerspectiveIntegrationTest.kt` covering the same 6 scenarios as 5.4 but adapted for `target_type='reply'` and `post_replies.is_auto_hidden`.
- [ ] 6.5 Add the rate-limit-precedence test: 21st reply attempt returns 429, dispatcher is NOT invoked (mock-spy assertion `dispatcher.callCount == 20` after 21 attempts where the first 20 succeeded). Documents the rate-limit gate is upstream of Layer 3.

## 7. Operations + secret-slot wiring

- [ ] 7.1 Add Koin wiring of the `PerspectiveApiClient` factory using `secrets.resolve("perspective-api-key")` (bare slot name; the `SecretResolver` handles env-prefix derivation internally per the canonical pattern at `Application.kt`). Use `secretKey(ktorEnv, "perspective-api-key")` only for the diagnostic log line on resolution failure (mirrors the FCM `event=fcm_init_failed reason=missing_secret slot={} env={}` precedent).
- [ ] 7.2 Document the secret-slot creation in `docs/10-Setup-Checklist.md` § Secrets — entries for `perspective-api-key` (production, target reservation: API key obtained from Google Cloud Console, Perspective API service) and `staging-perspective-api-key` (staging).
- [ ] 7.3 Document the `perspective_api_enabled` Remote Config kill-switch flip in `docs/07-Operations.md` § Moderation runbook (new sub-section): default ON in production after smoke-test green; flip OFF emergency procedure; how to read dispatcher metrics from Cloud Logging.
- [ ] 7.4 Update `gradle/libs.versions.toml` with the new ktor-client coordinates + version pin (matches Ktor server version line). Add justification rows to [`docs/09-Versions.md`](../../../docs/09-Versions.md) Version Pinning Decisions Log per the table convention.

## 8. Staging smoke + docs

- [ ] 8.1 Pre-archive staging smoke (per `openspec/project.md` § "Staging deploy timing" pre-archive smoke convention): manually trigger `gh workflow run deploy-staging.yml --ref content-moderation-perspective-api`; wait for green; run `dev/scripts/smoke-perspective-api.sh` (new script) that:
  1. POSTs a benign post → asserts 201 + `is_auto_hidden = FALSE` (verify via Cloud Run psql job per the `extract-staging-psql-helper-script` pattern).
  2. POSTs a known-toxic post (curated test string, NOT a real slur — use Perspective's documented test inputs per their playground) → asserts 201 returned synchronously + after a 2 s wait, `is_auto_hidden = TRUE` + queue row exists.
  3. Flips `perspective_api_enabled = FALSE` via Firebase Console → POSTs a known-toxic post → asserts no API call (Cloud Logging shows `outcome=kill_switch_off`) + `is_auto_hidden = FALSE`.
  4. Flips back to TRUE.
  Document the smoke test results in the PR body before archive.
- [ ] 8.2 Update `docs/06-Security-Privacy.md:178-180` if any divergence is found between the spec'd contract and what shipped (target: zero divergence; if found, classify per the `/next-change` Phase B reconciliation rules — fix the proposal OR file a `FOLLOW_UPS.md` entry to amend the doc).
- [ ] 8.3 Update `docs/05-Implementation.md` Content Moderation section's `Status: DESIGN` line for the Perspective API stub — flip to `Status: SHIPPED (V<N>)` referencing this change name. Note: no Flyway migration; reference is to the change name not a V-number.
- [ ] 8.4 Update `docs/08-Roadmap-Risk.md` Phase 2 §16 line to reflect "shipped" status, citing this change.

## 9. Archive prep

- [ ] 9.1 Run `openspec validate content-moderation-perspective-api --strict`. Fix any artifact failures.
- [ ] 9.2 Delete the `perspective-api-stopgap` entry from `FOLLOW_UPS.md` (this change closes its action items — the entry's "Build text-moderation-perspective-api-layer change when Phase 2 §16 work begins" is satisfied).
- [ ] 9.3 Surface any out-of-scope findings discovered during implementation as new `FOLLOW_UPS.md` entries (per the canonical convention; do NOT silently sweep into this change).
- [ ] 9.4 PR body refresh per the same-PR iteration rule: at proposal-review settle, retitle to `feat(moderation): content-moderation-perspective-api`; at archive, refresh to merge-ready shape.
