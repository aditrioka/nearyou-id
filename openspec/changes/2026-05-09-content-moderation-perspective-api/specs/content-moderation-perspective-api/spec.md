# content-moderation-perspective-api Specification

## Purpose

The `content-moderation-perspective-api` capability is **Layer 3 of the canonical 3-tier text moderation pipeline** at [`docs/06-Security-Privacy.md:151-180`](../../../docs/06-Security-Privacy.md): an async, post-INSERT, fail-open Google Perspective API toxicity classifier that catches the patterns the keyword-based Layers 1 and 2 inherently miss (tone shift, leet substitution, novel slurs). It owns three tightly-coupled components: a `:infra:perspective-api` HTTP client that encapsulates all vendor-shaped surface (no Ktor client imports leak into `:backend:ktor`); a `PerspectiveDispatcher` orchestrator implementing the structured-concurrency dispatch lifecycle (fire-and-forget from the route handler's perspective, 500 ms `withTimeoutOrNull` budget, JVM-shutdown drain); and the canonical score-threshold decision rules (`> 0.8` → auto-hide + queue; `[0.6, 0.8]` → queue only; `< 0.6` → noop). Every error path is fail-open: the user's content publishes regardless of classifier outcome, and a structured WARN log records the reason so operators can detect outages.

This capability closes the Pre-Launch security-review §5 toxicity-classifier gap and is the only remaining unshipped Phase 2 product item per [`docs/08-Roadmap-Risk.md`](../../../docs/08-Roadmap-Risk.md) §16. The kill switch (`perspective_api_enabled` Firebase Remote Config flag, default TRUE per Pre-Phase 1 §18) lets operators disable Layer 3 platform-wide without a code deploy if the classifier exhibits unacceptable false-positive rates or the API quota is exhausted. The capability deliberately scopes to posts and replies only; chat messages stay on Layers 1 + 2 + admin redaction because `chat_messages` lacks an `is_auto_hidden` column at V15.

## Requirements

### Requirement: PerspectiveApiClient interface in `:infra:perspective-api`

A new Gradle module `:infra:perspective-api` SHALL expose a public Kotlin interface:

```
interface PerspectiveApiClient {
    suspend fun analyze(content: String): Float?
}
```

The `analyze` function SHALL return:
- A `Float` in `[0.0f, 1.0f]` representing the **maximum** of the 4 requested attribute summary scores (`TOXICITY`, `SEVERE_TOXICITY`, `IDENTITY_ATTACK`, `THREAT`), on a successful classification.
- `null` on **any** failure path: HTTP timeout (>500 ms), HTTP error response (4xx, 5xx), network unreachable, response parse error (malformed JSON, missing `attributeScores`, missing summary score for ANY of the 4 requested attributes that the API returned without an explicit "this attribute couldn't be scored" signal — partial-attribute responses where the API is explicit about partial coverage are valid; missing summary scores due to malformed response are invalid).

The interface SHALL NOT leak any vendor-specific types (no `io.ktor.client.*`, no `com.google.*`, no Perspective API request / response data classes). `:backend:ktor` consumes only this interface; the implementation is wired via Koin at startup.

#### Scenario: Interface returns Float on success
- **GIVEN** the production `KtorPerspectiveApiClient` is configured with a valid API key
- **WHEN** `client.analyze("benign content")` is invoked AND the Perspective API returns 200 with `{"attributeScores": {"TOXICITY": {"summaryScore": {"value": 0.1}}, "SEVERE_TOXICITY": {"summaryScore": {"value": 0.05}}, "IDENTITY_ATTACK": {"summaryScore": {"value": 0.02}}, "THREAT": {"summaryScore": {"value": 0.03}}}}`
- **THEN** the result is `0.1f` (max of the 4 scores)

#### Scenario: Interface returns null on timeout
- **GIVEN** the API is configured with `requestTimeoutMillis = 500`
- **WHEN** `client.analyze(content)` is invoked AND the Perspective API does not respond within 500 ms
- **THEN** the result is `null` AND no exception propagates from the interface

#### Scenario: Interface returns null on HTTP 429
- **WHEN** `client.analyze(content)` is invoked AND the Perspective API returns HTTP 429 (rate limit exceeded)
- **THEN** the result is `null` AND no exception propagates

#### Scenario: Interface returns null on HTTP 5xx
- **WHEN** `client.analyze(content)` is invoked AND the Perspective API returns HTTP 503
- **THEN** the result is `null` AND no exception propagates

#### Scenario: Interface returns null on parse error
- **WHEN** `client.analyze(content)` is invoked AND the Perspective API returns 200 with body `{"unexpected": "shape"}`
- **THEN** the result is `null` AND no exception propagates

#### Scenario: Interface vendor surface is encapsulated
- **WHEN** any `:backend:ktor` source file is grep'd for `import io.ktor.client.` OR `import io.ktor.serialization.` (the client-side namespace, distinct from the server-side `io.ktor.server.*`)
- **THEN** zero matches are returned (the Ktor client is encapsulated in `:infra:perspective-api`)

#### Scenario: Interface returns max of 4 attributes
- **GIVEN** the API returns `TOXICITY=0.3`, `SEVERE_TOXICITY=0.85`, `IDENTITY_ATTACK=0.4`, `THREAT=0.2`
- **WHEN** `client.analyze(content)` is invoked
- **THEN** the result is `0.85f` (the max), not the mean (0.4375) nor the sum (1.75) nor the TOXICITY-only (0.3) value

### Requirement: HTTP request shape matches the Perspective API contract

The implementation `KtorPerspectiveApiClient` SHALL POST to `https://commentanalyzer.googleapis.com/v1alpha1/comments:analyze?key=<api_key>` with `Content-Type: application/json` and the body:

```json
{
  "comment": {"text": "<content>"},
  "requestedAttributes": {
    "TOXICITY": {},
    "SEVERE_TOXICITY": {},
    "IDENTITY_ATTACK": {},
    "THREAT": {}
  },
  "languages": ["id", "en"]
}
```

Exactly these 4 attributes SHALL be requested. Both `id` (Indonesian) and `en` (English) SHALL be in the `languages` list.

The API key SHALL be passed via the `key` query parameter (per Perspective API authentication docs). The API key SHALL NOT appear in the request body, nor in any log line, nor in any Sentry breadcrumb.

#### Scenario: Request body shape matches contract
- **WHEN** the production `KtorPerspectiveApiClient` is invoked with `content = "test"`
- **THEN** the outbound HTTP POST body deserializes to JSON with exactly the keys `comment.text`, `requestedAttributes.TOXICITY`, `requestedAttributes.SEVERE_TOXICITY`, `requestedAttributes.IDENTITY_ATTACK`, `requestedAttributes.THREAT`, `languages`, AND `comment.text == "test"`, AND `languages == ["id", "en"]`

#### Scenario: API key is in query param, not body
- **WHEN** the production client is invoked
- **THEN** the outbound URL contains `?key=<api_key>` AND the request body does NOT contain the `<api_key>` substring

#### Scenario: API key never appears in logs
- **GIVEN** the API key is `test-secret-AKIAIOSFODNN7EXAMPLE`
- **WHEN** a full dispatch cycle runs (success path + timeout path + HTTP error path)
- **THEN** the captured Sentry breadcrumbs + structured log lines contain ZERO occurrences of the literal `test-secret-AKIAIOSFODNN7EXAMPLE`

### Requirement: API key is resolved via the canonical SecretResolver

The `PerspectiveApiClient` Koin singleton SHALL be constructed with the API key resolved via:

```
val apiKey = secrets.resolve("perspective-api-key")
```

The `SecretResolver` implementation handles env-prefix derivation internally (production reads slot `perspective-api-key`; staging reads slot `staging-perspective-api-key`). This matches the precedent at [`backend/ktor/src/main/kotlin/id/nearyou/app/Application.kt`](../../../backend/ktor/src/main/kotlin/id/nearyou/app/Application.kt) where `secrets.resolve("firebase-admin-sa")` is the canonical call shape and `secretKey(env, "firebase-admin-sa")` is computed only for diagnostic log messages.

For diagnostic log lines on resolution failure (slot missing in Secret Manager), the dispatcher SHALL include the env-aware slot name derived via `secretKey(ktorEnv, "perspective-api-key")` so operators can immediately see whether `staging-perspective-api-key` or `perspective-api-key` was the missing slot — this matches the `Application.kt` precedent (`event=fcm_init_failed reason=missing_secret slot={} env={}`).

Direct hardcoded slot-name reads (bypassing the `SecretResolver`, OR bypassing `secretKey()` for the diagnostic log) are a lint violation per [`openspec/project.md`](../../project.md) § "Coding Conventions & CI Lint Rules" Secrets convention.

#### Scenario: Secret resolution uses canonical resolver
- **WHEN** the source for the `PerspectiveApiClient` Koin factory in `Application.module()` (or its delegated module) is inspected
- **THEN** the API key resolution call is `secrets.resolve("perspective-api-key")` (bare slot name) AND if a diagnostic log line on resolution-null exists, that log line uses `secretKey(ktorEnv, "perspective-api-key")` to surface the env-aware slot name AND no literal `"staging-perspective-api-key"` string appears anywhere

### Requirement: PerspectiveDispatcher orchestrates async post-INSERT dispatch

A `PerspectiveDispatcher` interface in `:backend:ktor` (under `id.nearyou.app.moderation`) SHALL expose:

```
enum class PerspectiveTargetType { POST, REPLY }
interface PerspectiveDispatcher {
    fun dispatchAsync(targetType: PerspectiveTargetType, targetId: UUID, content: String)
    suspend fun shutdown(drainMillis: Long = 5_000)
}
```

The `dispatchAsync` method SHALL launch a child coroutine on the dispatcher's `CoroutineScope(SupervisorJob() + Dispatchers.IO)` and return immediately (synchronous from the caller's perspective; the dispatch is fire-and-forget).

The launched child coroutine SHALL:
1. Read the kill-switch flag `perspective_api_enabled` via `RemoteConfigClient.fetchBoolean(...)` (with 5-min Redis cache via the canonical hash-tag key `{scope:perspective}:{key:enabled}`). If `null` → default TRUE per [`docs/05-Implementation.md:1042`](../../../docs/05-Implementation.md) (Pre-Phase 1 §18 default).
2. If kill-switch OFF → emit single structured WARN log with `event="perspective_api_fail_open"`, `outcome="kill_switch_off"`, `target_type=<post|reply>`, `target_id=<UUID>`, `latency_ms=0`, AND return without calling the API.
3. Else: invoke `client.analyze(content)`. The 500 ms timeout budget is owned by the `PerspectiveApiClient` implementation (`KtorPerspectiveApiClient` enforces it via `withTimeout` internally and returns `null` on expiry). The dispatcher MAY measure elapsed milliseconds via `System.currentTimeMillis()` brackets purely for the `latency_ms` log field; the dispatcher SHALL NOT add a redundant outer timeout wrap (single-source-of-truth for the timeout budget keeps the test surface deterministic).
4. On `null` return: emit single structured WARN log with `event="perspective_api_fail_open"`, `outcome=<timeout|http_error|parse_error>` (the exact outcome category is the underlying client's responsibility to surface; the dispatcher SHALL log `outcome="client_returned_null"` if it cannot distinguish), `target_type`, `target_id`, `latency_ms=<measured>`, AND return.
5. On non-null score → execute the score-threshold decision per `### Requirement: Score thresholds map to canonical DB writes`.

The dispatcher SHALL NEVER throw to the caller of `dispatchAsync`. Internal failures (DB write errors, Redis errors during the cache read, unexpected exceptions in the API client) are caught at the dispatcher boundary and emitted as structured ERROR logs; they do NOT propagate to the route handler.

#### Scenario: dispatchAsync returns immediately
- **WHEN** `dispatcher.dispatchAsync(POST, UUID.randomUUID(), "test")` is invoked AND `client.analyze` is configured to take 200 ms
- **THEN** the call returns within 10 ms (the dispatch is fire-and-forget; the 200 ms `analyze` runs on the dispatcher's coroutine scope, not the caller's)

#### Scenario: Kill switch off skips API call
- **GIVEN** Remote Config returns `perspective_api_enabled = false`
- **WHEN** `dispatcher.dispatchAsync(POST, postId, "test content")` is invoked AND `awaitInFlight()` completes
- **THEN** the underlying `PerspectiveApiClient.analyze` is invoked ZERO times AND a single WARN log line was emitted with `outcome="kill_switch_off"` AND no rows were modified in `posts` or `moderation_queue`

#### Scenario: Kill switch null defaults to TRUE
- **GIVEN** Remote Config returns null for `perspective_api_enabled` (cascade-default)
- **WHEN** `dispatcher.dispatchAsync(POST, postId, "test")` is invoked AND `awaitInFlight()` completes
- **THEN** the underlying `PerspectiveApiClient.analyze` IS invoked (the default is TRUE per Pre-Phase 1 §18)

#### Scenario: Client null return triggers fail-open log
- **GIVEN** Remote Config kill-switch is TRUE AND `FakePerspectiveApiClient.nextScore = null`
- **WHEN** `dispatcher.dispatchAsync(POST, postId, "test")` is invoked AND `awaitInFlight()` completes
- **THEN** a single WARN log was emitted with `event="perspective_api_fail_open"`, `target_type="post"`, `target_id=<the postId>`, `outcome` ∈ `{timeout, http_error, parse_error, client_returned_null}` AND no rows were modified in `posts` or `moderation_queue`

#### Scenario: dispatchAsync never throws to caller
- **GIVEN** the dispatcher's internal Redis client is configured to throw `RedisException` on every read (simulated outage)
- **WHEN** `dispatcher.dispatchAsync(POST, postId, "test")` is invoked from a route handler
- **THEN** the call returns normally (no exception propagates) AND a structured ERROR log is emitted internally for the unexpected dispatcher failure

#### Scenario: Outbound log lines do NOT contain content
- **GIVEN** the content sentinel is `"DO-NOT-LEAK-CONTENT-X9Z2"` AND the kill-switch is OFF (so the dispatch path runs but skips API call)
- **WHEN** `dispatcher.dispatchAsync(POST, postId, "DO-NOT-LEAK-CONTENT-X9Z2")` is invoked AND `awaitInFlight()` completes
- **THEN** the captured WARN log line content does NOT contain the literal `DO-NOT-LEAK-CONTENT-X9Z2`

### Requirement: Score thresholds map to canonical DB writes

The dispatcher's score-threshold decision rules SHALL be:

| Score range | Action |
|-------------|--------|
| `score > 0.8f` | **Auto-hide path**: in a single SQL transaction, UPDATE `posts.is_auto_hidden = TRUE` (or `post_replies.is_auto_hidden = TRUE`) WHERE `id = :target_id` AND the row is not soft-deleted, AND INSERT one `moderation_queue` row with `target_type=<post|reply>`, `target_id=<the_id>`, `trigger='perspective_api_high_score'`, `priority=5`, `status='pending'`, `notes=NULL`. Use `ON CONFLICT (target_type, target_id, trigger) DO NOTHING` for idempotency. |
| `0.6f <= score <= 0.8f` | **Queue-only path**: INSERT one `moderation_queue` row with the same shape (no UPDATE; `is_auto_hidden` stays FALSE). Idempotency via `ON CONFLICT DO NOTHING`. |
| `score < 0.6f` | **Noop**: no DB writes. |

The threshold constants (`HIGH_SCORE_THRESHOLD = 0.8f`, `LOW_SCORE_THRESHOLD = 0.6f`) are hardcoded in V1 of this capability per design D3.

The auto-hide UPDATE SHALL include the soft-delete guard `AND deleted_at IS NULL` for both target types — V4 and V8 schemas use the same `deleted_at TIMESTAMPTZ` column shape on `posts` and `post_replies` respectively. The guard mirrors the V9 reports auto-hide path's idempotency pattern.

#### Scenario: High score commits UPDATE + INSERT atomically (post)
- **GIVEN** kill-switch ON AND `FakePerspectiveApiClient.nextScore = 0.85f` AND a post `P` exists with `is_auto_hidden = FALSE`
- **WHEN** `dispatcher.dispatchAsync(POST, P, "test content")` is invoked AND `awaitInFlight()` completes
- **THEN** `posts.is_auto_hidden` for `P` equals `TRUE` AND exactly one `moderation_queue` row exists with `target_type='post'`, `target_id=P`, `trigger='perspective_api_high_score'`, `status='pending'`, `priority=5`, `notes IS NULL`

#### Scenario: High score commits UPDATE + INSERT atomically (reply)
- **GIVEN** kill-switch ON AND `nextScore = 0.9f` AND a reply `R` exists with `is_auto_hidden = FALSE` AND not soft-deleted
- **WHEN** `dispatcher.dispatchAsync(REPLY, R, "test content")` is invoked AND `awaitInFlight()` completes
- **THEN** `post_replies.is_auto_hidden` for `R` equals `TRUE` AND exactly one `moderation_queue` row exists with `target_type='reply'`, `target_id=R`, `trigger='perspective_api_high_score'`

#### Scenario: Mid score writes queue row only
- **GIVEN** kill-switch ON AND `nextScore = 0.7f` AND post `P` exists with `is_auto_hidden = FALSE`
- **WHEN** `dispatcher.dispatchAsync(POST, P, "test")` is invoked AND `awaitInFlight()` completes
- **THEN** `posts.is_auto_hidden` for `P` remains `FALSE` AND exactly one `moderation_queue` row exists with `target_type='post'`, `target_id=P`, `trigger='perspective_api_high_score'`

#### Scenario: Low score writes nothing
- **GIVEN** kill-switch ON AND `nextScore = 0.3f` AND post `P` exists with `is_auto_hidden = FALSE`
- **WHEN** `dispatcher.dispatchAsync(POST, P, "test")` is invoked AND `awaitInFlight()` completes
- **THEN** `posts.is_auto_hidden` for `P` remains `FALSE` AND no `moderation_queue` row exists for `target_id = P AND trigger = 'perspective_api_high_score'`

#### Scenario: Threshold boundary at exactly 0.8 is mid-band
- **GIVEN** kill-switch ON AND `nextScore = 0.8f` (exactly the boundary)
- **WHEN** `dispatcher.dispatchAsync(POST, P, "test")` is invoked AND `awaitInFlight()` completes
- **THEN** `posts.is_auto_hidden` for `P` remains `FALSE` AND exactly one `moderation_queue` row exists (queue-only band per `0.6 <= score <= 0.8`; the `> 0.8` band is strictly greater)

#### Scenario: Threshold boundary at exactly 0.6 is mid-band
- **GIVEN** kill-switch ON AND `nextScore = 0.6f` (exactly the lower boundary)
- **WHEN** `dispatcher.dispatchAsync(POST, P, "test")` is invoked AND `awaitInFlight()` completes
- **THEN** exactly one `moderation_queue` row exists (queue-only band; the `< 0.6` band is strictly less)

#### Scenario: High score on tombstoned post is no-op
- **GIVEN** kill-switch ON AND `nextScore = 0.95f` AND a post `P` exists with `deleted_at IS NOT NULL` (hard-deleted between INSERT and dispatch)
- **WHEN** `dispatcher.dispatchAsync(POST, P, "test")` is invoked AND `awaitInFlight()` completes
- **THEN** the UPDATE matches zero rows (idempotency guard) AND the `moderation_queue` INSERT either succeeds (target_id may be a "ghost" but the queue is admin-internal; admin handles) OR is skipped per implementation choice; the spec REQUIRES that the UPDATE not error AND that no exception propagates

#### Scenario: Idempotent retry produces single queue row
- **GIVEN** a high-score dispatch produced one `moderation_queue` row for `(target_type='post', target_id=P, trigger='perspective_api_high_score')`
- **WHEN** the same dispatch is retried (e.g., simulated transient DB error caused a re-dispatch) AND `awaitInFlight()` completes
- **THEN** the `moderation_queue` table contains exactly ONE row for that key (the `ON CONFLICT DO NOTHING` clause suppresses the duplicate)

#### Scenario: Auto-hide UPDATE + queue INSERT are atomic
- **GIVEN** the queue INSERT is configured to fail (e.g., simulated FK violation)
- **WHEN** the high-score dispatch runs AND `awaitInFlight()` completes
- **THEN** `posts.is_auto_hidden` for `P` is NOT flipped to TRUE (the transaction rolls back) AND the dispatcher logs the failure as a structured ERROR

### Requirement: Structured WARN log shape per fail-open category

For every fail-open outcome (kill-switch off, timeout, HTTP error, parse error, dispatcher-internal error), the dispatcher SHALL emit exactly one log line with the following structured fields:

- `event = "perspective_api_fail_open"` (or `"perspective_api_dispatcher_error"` for the internal-error category, which is ERROR-level not WARN)
- `outcome = "kill_switch_off" | "timeout" | "http_error" | "parse_error" | "client_returned_null" | "dispatcher_internal_error"`
- `target_type = "post" | "reply"`
- `target_id = <UUID string>`
- `latency_ms = <integer>` (0 for kill-switch path; full elapsed for timeout / HTTP / parse paths)

The log line SHALL NOT include the `content` parameter, the matched-attribute scores, or any user identifier (no `user_id`, no `author_id`, no JWT claim).

Log severity:
- `outcome ∈ {kill_switch_off, timeout, http_error, parse_error, client_returned_null}` → WARN
- `outcome = dispatcher_internal_error` → ERROR

Sentry breadcrumb emission:
- ALL WARN-level outcomes EXCEPT `kill_switch_off` SHALL emit a Sentry breadcrumb. `kill_switch_off` is operator-initiated and SHALL NOT raise a Sentry event (would create flag-flip noise).
- The ERROR-level dispatcher-internal-error SHALL raise a Sentry event at ERROR severity.
- Sentry deduplication is the standard pattern (`event` + `outcome` is the fingerprint).

#### Scenario: Kill switch log has zero latency
- **GIVEN** kill-switch OFF
- **WHEN** the dispatch runs AND completes
- **THEN** the WARN log has `latency_ms = 0`

#### Scenario: Timeout log has full elapsed latency
- **GIVEN** the API client takes 600 ms (exceeds 500 ms timeout)
- **WHEN** the dispatch runs AND completes
- **THEN** a WARN log is emitted with `latency_ms` ≥ 500 (approximately matches the timeout window)

#### Scenario: Kill switch path raises no Sentry event
- **GIVEN** Sentry capture is mocked AND kill-switch is OFF
- **WHEN** 100 dispatches run AND complete
- **THEN** zero Sentry events were captured (only structured WARN logs)

#### Scenario: Timeout path raises one Sentry breadcrumb
- **GIVEN** Sentry capture is mocked AND `nextScore = null` (simulating timeout)
- **WHEN** 1 dispatch runs AND completes
- **THEN** exactly one Sentry breadcrumb was captured with severity WARN AND `event = "perspective_api_fail_open"`

#### Scenario: Log line does NOT contain content sentinel
- **GIVEN** content sentinel is `"DO-NOT-LEAK-X9Z2"`
- **WHEN** any fail-open dispatch runs against `"DO-NOT-LEAK-X9Z2"`
- **THEN** the captured log line does NOT contain the literal `DO-NOT-LEAK-X9Z2`

#### Scenario: Log line does NOT contain user identifier
- **GIVEN** the dispatch is invoked for `target_id = <some UUID>` AND the content is `"hello"` AND the post's author is `user_X`
- **WHEN** any fail-open dispatch runs
- **THEN** the captured log line does NOT contain the literal `user_X` AND does NOT contain a `user_id` field AND does NOT contain a `author_id` field

### Requirement: Dispatcher scope drains on JVM shutdown

The dispatcher SHALL register a JVM shutdown hook that calls `dispatcher.shutdown(drainMillis = 5_000)`. The `shutdown` method SHALL:
1. Cancel new dispatch enqueues immediately (the scope's `SupervisorJob` is cancelled).
2. Await in-flight dispatches up to `drainMillis` via `withTimeoutOrNull(drainMillis.milliseconds) { coroutineContext.job.cancelAndJoin() }`.
3. Return without throwing regardless of timeout.

The 5-second drain budget mirrors the `FcmDispatcherScope` precedent and matches Cloud Run's revision-rollover grace window.

#### Scenario: Shutdown drains in-flight dispatches up to 5 seconds
- **GIVEN** 1 dispatch is in flight with a 1-second `analyze` time AND `shutdown(5_000)` is called
- **THEN** `shutdown` returns within 1.1 seconds AND the dispatch's DB writes (if score thresholds matched) are committed

#### Scenario: Dispatches exceeding the 5-second drain are abandoned
- **GIVEN** 1 dispatch is in flight with a 10-second `analyze` time AND `shutdown(5_000)` is called
- **THEN** `shutdown` returns within ~5.1 seconds (the 5-second drain budget) AND the dispatch's coroutine is cancelled AND no DB writes occurred for that dispatch

#### Scenario: Shutdown 5-second boundary is deterministic
- **GIVEN** 1 dispatch with exactly 5.0-second `analyze` time AND `shutdown(5_000)` is called
- **THEN** the dispatch is cancelled at the 5-second boundary AND `shutdown` completes deterministically (test uses `kotlinx-coroutines-test` `TestScheduler` for deterministic timing per the existing precedent)

#### Scenario: Dispatches enqueued after shutdown are rejected silently
- **GIVEN** `shutdown` has completed
- **WHEN** `dispatcher.dispatchAsync(POST, postId, "test")` is invoked
- **THEN** the call returns without throwing AND a single WARN log is emitted with `event="perspective_api_dispatch_after_shutdown"` AND no API call AND no DB write occurs

### Requirement: Kill-switch cache key uses canonical Redis hash-tag format

The Redis cache for the `perspective_api_enabled` flag SHALL use the key shape `{scope:perspective}:{key:enabled}` exactly. The key includes the hash-tag braces `{...}` for cluster-safe multi-key ops per `RedisHashTagRule`.

The cache TTL SHALL be 5 minutes (300 seconds), matching the moderation-list cache TTL precedent from `content-moderation-keyword-lists`.

#### Scenario: Cache key matches canonical hash-tag format
- **WHEN** the dispatcher reads or writes the kill-switch cache key
- **THEN** the key string equals exactly `{scope:perspective}:{key:enabled}` (no variants, no environment-prefix injection)

#### Scenario: Cache TTL is 5 minutes
- **WHEN** the dispatcher writes the kill-switch flag to Redis
- **THEN** the TTL on the written key is 300 seconds (±2 seconds for test latency tolerance)
