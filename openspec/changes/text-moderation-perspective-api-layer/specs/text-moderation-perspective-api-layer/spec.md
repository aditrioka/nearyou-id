## ADDED Requirements

### Requirement: `:infra:perspective` is the sole owner of the Google Perspective API client

A new Gradle module `:infra:perspective` SHALL be created under `infra/perspective/` (alongside `:infra:fcm`, `:infra:oidc`, `:infra:otel`, `:infra:redis`, `:infra:remote-config`, `:infra:supabase`). All Perspective API HTTP call shapes (request body assembly, response parsing, API-key auth, endpoint URL `https://commentanalyzer.googleapis.com/v1alpha1/comments:analyze`) SHALL live entirely inside `:infra:perspective`.

`:backend:ktor` SHALL depend on `:infra:perspective` and SHALL NOT carry any direct Perspective-specific HTTP call code, request-body assembly, or response-parsing logic in its source files. Business modules (`:core:domain`, `:core:data`, `:shared:*`) SHALL NOT depend on `:infra:perspective`.

The new module SHALL expose ONLY the `PerspectiveClient` public interface + a Koin-bindable implementation. The Perspective API request/response shapes SHALL NOT leak into the public interface — `PerspectiveClient` returns a plain Kotlin `PerspectiveScore` data class.

```
data class PerspectiveScore(
    val toxicity: Double,
    val severeToxicity: Double,
    val identityAttack: Double,
    val threat: Double,
)

interface PerspectiveClient {
    suspend fun analyze(content: String): PerspectiveScore
}
```

The implementation SHALL use the Ktor HTTP client (CIO engine, already on the classpath) for the outbound call and `kotlinx.serialization` for JSON parsing. The implementation SHALL NOT pull in `com.google.api-client:*` or other heavy Google API client libraries (rationale: a single REST endpoint does not justify the transitive-dependency footprint).

The API key SHALL be sourced via `secretResolver.resolve("perspective-api-key")` (bare slot name; the `SecretResolver` implementation owns env-prefix derivation, mirroring the [`Application.kt:388`](../../../../../backend/ktor/src/main/kotlin/id/nearyou/app/Application.kt) precedent `secrets.resolve("firebase-admin-sa")`). A `secretKey(env, "perspective-api-key")` computation SHALL be present for the diagnostic startup log line. No hardcoded `"staging-perspective-api-key"` literal SHALL appear.

#### Scenario: Module exists at the canonical path with the canonical name
- **WHEN** the project structure is inspected
- **THEN** `infra/perspective/build.gradle.kts` exists AND the module is included in `settings.gradle.kts` as `:infra:perspective`

#### Scenario: `:backend:ktor` depends on `:infra:perspective`
- **WHEN** `:backend:ktor`'s `build.gradle.kts` `dependencies { ... }` block is inspected
- **THEN** `implementation(project(":infra:perspective"))` (or equivalent) appears in the declarations

#### Scenario: `:backend:ktor` source files contain no direct Perspective API endpoint reference
- **WHEN** the source files of `:backend:ktor` are scanned for the literal string `"commentanalyzer.googleapis.com"` or `"v1alpha1/comments:analyze"`
- **THEN** zero matches are found (the Perspective endpoint URL is encapsulated inside `:infra:perspective`)

#### Scenario: `PerspectiveClient` interface returns plain Kotlin types only
- **WHEN** the public interface declaration of `PerspectiveClient` in `:infra:perspective` is inspected
- **THEN** every public function's return type is a Kotlin standard-library type or the `PerspectiveScore` data class — no `JsonObject`, `JsonElement`, or any HTTP-response-shape type appears in the public surface

#### Scenario: API key resolved via `SecretResolver` precedent call shape
- **WHEN** the module's HTTP client initialization call site is inspected statically
- **THEN** the API key is sourced via `secretResolver.resolve("perspective-api-key")` (bare slot name) AND a `secretKey(env, "perspective-api-key")` computation is present for the diagnostic startup log line AND no hardcoded `"staging-perspective-api-key"` literal appears

### Requirement: `PerspectiveModerator` aggregates attribute scores via `max(...)` and applies canonical thresholds

A `PerspectiveModerator` SHALL live in `:backend:ktor` (under `id.nearyou.app.moderation`) and expose:

```
suspend fun moderate(targetType: TargetType, targetId: UUID, content: String): Outcome

enum class TargetType { POST, REPLY }

sealed interface Outcome {
    data object NoAction : Outcome
    data class FlagOnly(val score: Double) : Outcome
    data class AutoHide(val score: Double) : Outcome
}
```

The orchestrator SHALL compute the aggregate score as:

```
score = max(toxicity, severeToxicity, identityAttack, threat)
```

The orchestrator SHALL apply the canonical thresholds from [`docs/06-Security-Privacy.md:163-164`](../../../../../docs/06-Security-Privacy.md):
- `score > 0.8` → `Outcome.AutoHide(score)`
- `score > 0.6` AND `score ≤ 0.8` → `Outcome.FlagOnly(score)`
- `score ≤ 0.6` → `Outcome.NoAction`

Threshold values SHALL be tunable via Firebase Remote Config keys `perspective_api_high_score_threshold` (default 0.8) and `perspective_api_flag_threshold` (default 0.6). Both flags SHALL be clamped to `[0.0, 1.0]`; out-of-range values fall back to the default (mirrors the `moderation_match_threshold` clamp pattern from [`content-moderation-keyword-lists/spec.md`](../../../specs/content-moderation-keyword-lists/spec.md) § "ModerationMatchThresholdLoader resolves the threshold via the same Remote Config + fallback path"). The clamp SHALL apply on EVERY read, including Tier 1 (Redis cache) reads — a stale cached value from a prior bad Remote Config push (e.g., `1.5` or `-0.1`) MUST NOT propagate to the orchestrator.

#### Scenario: Score above 0.8 returns AutoHide
- **GIVEN** `PerspectiveClient.analyze(content)` returns `PerspectiveScore(toxicity = 0.85, severeToxicity = 0.40, identityAttack = 0.10, threat = 0.20)`
- **WHEN** `moderator.moderate(POST, U, content)` is invoked
- **THEN** the result equals `Outcome.AutoHide(score = 0.85)` (the max attribute is `toxicity = 0.85`, above the 0.8 threshold)

#### Scenario: Score in 0.6–0.8 band returns FlagOnly
- **GIVEN** `PerspectiveClient.analyze(content)` returns `PerspectiveScore(toxicity = 0.70, severeToxicity = 0.20, identityAttack = 0.10, threat = 0.05)`
- **WHEN** `moderator.moderate(POST, U, content)` is invoked
- **THEN** the result equals `Outcome.FlagOnly(score = 0.70)` (the max attribute is `toxicity = 0.70`, between 0.6 and 0.8)

#### Scenario: Score at or below 0.6 returns NoAction
- **GIVEN** `PerspectiveClient.analyze(content)` returns `PerspectiveScore(toxicity = 0.60, severeToxicity = 0.40, identityAttack = 0.30, threat = 0.20)`
- **WHEN** `moderator.moderate(POST, U, content)` is invoked
- **THEN** the result equals `Outcome.NoAction` (the max attribute is `toxicity = 0.60`, NOT strictly above 0.6 — the comparison is strict `>`, not `>=`)

#### Scenario: Threshold boundary at 0.8 is exclusive
- **GIVEN** `PerspectiveClient.analyze(content)` returns `PerspectiveScore(toxicity = 0.80, severeToxicity = 0.50, identityAttack = 0.10, threat = 0.20)`
- **WHEN** `moderator.moderate(POST, U, content)` is invoked
- **THEN** the result equals `Outcome.FlagOnly(score = 0.80)` (the boundary `0.80` is NOT auto-hide — `> 0.8` is strict; `0.80` falls into the FlagOnly band because `0.80 > 0.6` AND NOT `0.80 > 0.8`)

#### Scenario: Max aggregation across attributes — IDENTITY_ATTACK dominates
- **GIVEN** `PerspectiveClient.analyze(content)` returns `PerspectiveScore(toxicity = 0.30, severeToxicity = 0.20, identityAttack = 0.92, threat = 0.10)`
- **WHEN** `moderator.moderate(POST, U, content)` is invoked
- **THEN** the result equals `Outcome.AutoHide(score = 0.92)` (the max attribute is `identityAttack = 0.92`, above the 0.8 threshold; max-aggregation surfaces the worst single attribute)

#### Scenario: Threshold tunable via Remote Config
- **GIVEN** Firebase Remote Config has `perspective_api_high_score_threshold = 0.95` AND `perspective_api_flag_threshold = 0.5`
- **WHEN** `moderator.moderate(POST, U, content)` is invoked AND `PerspectiveClient.analyze(content)` returns `PerspectiveScore(toxicity = 0.85, severeToxicity = 0.30, identityAttack = 0.10, threat = 0.05)`
- **THEN** the result equals `Outcome.FlagOnly(score = 0.85)` (the max `0.85` is above the tuned flag threshold `0.5` but NOT above the tuned high-score threshold `0.95`)

#### Scenario: Threshold clamped on out-of-range Remote Config value
- **GIVEN** Firebase Remote Config returns `perspective_api_high_score_threshold = 1.5` (out of `[0.0, 1.0]`)
- **WHEN** `moderator.moderate(POST, U, content)` is invoked
- **THEN** the high-score threshold used internally is `0.8` (the default; out-of-range value is treated as failure) AND a Sentry WARN with `event = "perspective_threshold_fallback"`, `key = "perspective_api_high_score_threshold"`, `reason = "out_of_range"` is emitted

#### Scenario: Threshold clamped on cached out-of-range value (cached-bad-value poisoning prevention)
- **GIVEN** Redis key `{scope:remote_config}:{flag:perspective_api_high_score_threshold}` holds the value `"-0.5"` (a stale cached value from a prior bad Remote Config push)
- **WHEN** `moderator.moderate(POST, U, content)` is invoked
- **THEN** the high-score threshold used internally is `0.8` (the default; the Tier 1 cache hit's negative value is rejected by the same `[0.0, 1.0]` clamp that applies to Tier 2 reads — a single bad Remote Config push CANNOT poison Layer 3 enforcement for the 5-min cache window)

### Requirement: `PerspectiveModerator` invocation has a 500ms timeout and fails open

The orchestrator SHALL wrap the `PerspectiveClient.analyze(content)` call in `withTimeoutOrNull(500.milliseconds)`. On timeout (return value null), the orchestrator SHALL return `Outcome.NoAction` AND emit a Sentry WARN with `event = "perspective_dispatch_failed"`, `failure_kind = "timeout"`.

The orchestrator SHALL ALSO return `Outcome.NoAction` (fail-open) on any of:
- Perspective API HTTP 4xx response (auth failure, malformed request, rate limit) — Sentry WARN with `failure_kind = "http_4xx"`, including the integer status code
- Perspective API HTTP 5xx response (Google outage) — Sentry WARN with `failure_kind = "http_5xx"`, including the integer status code
- Network exception thrown (DNS fail, TLS handshake fail, connection refused) — Sentry WARN with `failure_kind = "network"`
- Response-body parse error (Perspective returned an unexpected JSON shape) — Sentry WARN with `failure_kind = "parse"`
- Kill-switch OFF: `RemoteConfigClient.fetchBoolean("perspective_api_enabled")` returns FALSE — silent skip, no Sentry event, NO `PerspectiveClient.analyze(...)` invocation
- Kill-switch read failure: `RemoteConfigClient.fetchBoolean(...)` throws (Remote Config unavailable) — fail-OPEN to enabled=true, log Sentry WARN with `event = "perspective_kill_switch_unavailable"`, attempt the Perspective call

The orchestrator SHALL NOT propagate any exception out of `moderate(...)` — it absorbs every failure mode and returns `Outcome.NoAction`. The dispatch is async fire-and-forget; an unhandled exception would be lost to the supervisor scope's `CoroutineExceptionHandler` and would not surface to the request handler.

#### Scenario: 500ms timeout returns NoAction with Sentry WARN
- **GIVEN** `PerspectiveClient.analyze(content)` suspends for 600ms (e.g., simulated by a `delay(600)` in a test fixture)
- **WHEN** `moderator.moderate(POST, U, content)` is invoked
- **THEN** the result equals `Outcome.NoAction` AND exactly one Sentry WARN is emitted with `event = "perspective_dispatch_failed"`, `failure_kind = "timeout"`, `target_type = "post"`, `target_id = "<U>"` AND no Perspective response is consumed AND no `moderation_queue` row is written AND no `is_auto_hidden` flip occurs

#### Scenario: HTTP 5xx returns NoAction with Sentry WARN
- **GIVEN** `PerspectiveClient.analyze(content)` throws an exception representing an HTTP 503 response from the Perspective endpoint
- **WHEN** `moderator.moderate(POST, U, content)` is invoked
- **THEN** the result equals `Outcome.NoAction` AND exactly one Sentry WARN is emitted with `event = "perspective_dispatch_failed"`, `failure_kind = "http_5xx"`, `status_code = 503`

#### Scenario: HTTP 429 (rate limit) returns NoAction with Sentry WARN
- **GIVEN** `PerspectiveClient.analyze(content)` throws an exception representing an HTTP 429 response (Perspective quota exhaustion)
- **WHEN** `moderator.moderate(POST, U, content)` is invoked
- **THEN** the result equals `Outcome.NoAction` AND exactly one Sentry WARN is emitted with `event = "perspective_dispatch_failed"`, `failure_kind = "http_4xx"`, `status_code = 429` (operator signal that quota increase is needed)

#### Scenario: Network exception returns NoAction with Sentry WARN
- **GIVEN** `PerspectiveClient.analyze(content)` throws a `java.net.ConnectException` (e.g., simulated DNS failure)
- **WHEN** `moderator.moderate(POST, U, content)` is invoked
- **THEN** the result equals `Outcome.NoAction` AND exactly one Sentry WARN is emitted with `event = "perspective_dispatch_failed"`, `failure_kind = "network"`

#### Scenario: Response parse error returns NoAction with Sentry WARN
- **GIVEN** `PerspectiveClient.analyze(content)` throws a `kotlinx.serialization.SerializationException` (e.g., Perspective returned an unexpected JSON shape)
- **WHEN** `moderator.moderate(POST, U, content)` is invoked
- **THEN** the result equals `Outcome.NoAction` AND exactly one Sentry WARN is emitted with `event = "perspective_dispatch_failed"`, `failure_kind = "parse"`

#### Scenario: Kill-switch OFF skips dispatch silently
- **GIVEN** `RemoteConfigClient.fetchBoolean("perspective_api_enabled")` returns `false`
- **WHEN** `moderator.moderate(POST, U, content)` is invoked
- **THEN** the result equals `Outcome.NoAction` AND `PerspectiveClient.analyze(...)` is NOT invoked (verifiable via mock-spy call count) AND no Sentry event is emitted (operator-controlled disable is a normal state, not an alert)

#### Scenario: Kill-switch read failure fails open to enabled=true
- **GIVEN** `RemoteConfigClient.fetchBoolean("perspective_api_enabled")` throws (Remote Config network error AND repo-fallback unavailable)
- **WHEN** `moderator.moderate(POST, U, content)` is invoked
- **THEN** `PerspectiveClient.analyze(...)` IS invoked (fail-OPEN to enabled=true preserves Layer 3 protection during Remote Config outages) AND a Sentry WARN with `event = "perspective_kill_switch_unavailable"` is emitted

#### Scenario: Orchestrator never throws to caller
- **GIVEN** any failure mode in the dispatch chain (timeout, HTTP error, network exception, parse exception, Remote Config exception)
- **WHEN** `moderator.moderate(POST, U, content)` is invoked
- **THEN** the function returns a value (`Outcome.NoAction` in failure modes) AND does NOT throw any exception to the caller (the call site fire-and-forget pattern relies on this no-throw contract; an unhandled throw would crash the supervisor scope's exception handler instead of being absorbed)

### Requirement: AutoHide outcome flips `is_auto_hidden` and writes queue row in one SQL transaction

When `moderator.moderate(...)` returns `Outcome.AutoHide(score)`, the orchestrator SHALL execute both:
1. `UPDATE posts SET is_auto_hidden = TRUE WHERE id = ?` (or `UPDATE post_replies SET is_auto_hidden = TRUE WHERE id = ?` when `targetType = REPLY`)
2. `INSERT INTO moderation_queue (target_type, target_id, trigger, ...) VALUES (..., 'perspective_api_high_score', ...) ON CONFLICT (target_type, target_id, trigger) DO NOTHING`

inside ONE SQL transaction with a single COMMIT. Either both apply or neither does.

The `moderation_queue` row's `trigger` SHALL be the canonical string `'perspective_api_high_score'` per the V9 Flyway migration (`backend/ktor/src/main/resources/db/migration/V9__reports_moderation.sql:523`). No new Flyway migration is needed.

The `moderation_queue` row's `priority` field SHALL be set to a value matching the existing `auto_hide_3_reports` priority (whatever value the V9 trigger sets — verify at implementation time). The row's `status` SHALL be the default unreviewed state (typically `'pending'`).

#### Scenario: AutoHide writes both the flip and the queue row in one transaction
- **WHEN** `moderator.moderate(POST, U, content)` produces `Outcome.AutoHide(score = 0.92)`
- **THEN** the SQL transaction emits both `UPDATE posts SET is_auto_hidden = TRUE WHERE id = U` AND `INSERT INTO moderation_queue (target_type, target_id, trigger, ...) VALUES ('post', U, 'perspective_api_high_score', ...) ON CONFLICT DO NOTHING` AND a single COMMIT (atomicity verifiable via JDBC statement spy)

#### Scenario: AutoHide for reply target updates `post_replies` not `posts`
- **WHEN** `moderator.moderate(REPLY, R, content)` produces `Outcome.AutoHide(score = 0.85)`
- **THEN** the SQL transaction emits `UPDATE post_replies SET is_auto_hidden = TRUE WHERE id = R` (NOT `UPDATE posts ...`) AND `INSERT INTO moderation_queue (target_type, target_id, trigger, ...) VALUES ('reply', R, 'perspective_api_high_score', ...) ON CONFLICT DO NOTHING`

#### Scenario: AutoHide idempotent retry does not double-write queue
- **GIVEN** `moderator.moderate(POST, U, content)` previously wrote a `moderation_queue` row with `(target_type, target_id, trigger) = ('post', U, 'perspective_api_high_score')` AND a retry for the same target produces another `Outcome.AutoHide`
- **WHEN** the second invocation attempts the queue INSERT
- **THEN** the `ON CONFLICT (target_type, target_id, trigger) DO NOTHING` clause suppresses the duplicate AND the queue contains exactly one row for that target+trigger AND `is_auto_hidden` remains TRUE (the second `UPDATE posts SET is_auto_hidden = TRUE` is a no-op, idempotent)

#### Scenario: Concurrent AutoHide invocations collapse to one queue row
- **GIVEN** two concurrent transactions T1 and T2 each produce `Outcome.AutoHide` for the same `(target_type='post', target_id=P)` (e.g., a retry race where two dispatches for the same post fire nearly simultaneously)
- **WHEN** both transactions execute their UPDATE + INSERT pair AND both COMMIT
- **THEN** exactly one `moderation_queue` row exists for `(target_type='post', target_id=P, trigger='perspective_api_high_score')` AND neither transaction surfaces a unique-violation error to the orchestrator

### Requirement: FlagOnly outcome writes queue row only

When `moderator.moderate(...)` returns `Outcome.FlagOnly(score)`, the orchestrator SHALL execute:

1. `INSERT INTO moderation_queue (target_type, target_id, trigger, ...) VALUES (..., 'perspective_api_high_score', ...) ON CONFLICT (target_type, target_id, trigger) DO NOTHING`

WITHOUT updating `is_auto_hidden`. The row remains visible on timelines until an admin reviews it.

The `moderation_queue` row's `trigger` SHALL be the canonical string `'perspective_api_high_score'` (the V9 enum has only this single Perspective-derived value; the FlagOnly outcome and the AutoHide outcome share the same trigger name — the score column captures the actual score for admin disambiguation).

#### Scenario: FlagOnly writes queue row but does not flip is_auto_hidden
- **WHEN** `moderator.moderate(POST, U, content)` produces `Outcome.FlagOnly(score = 0.70)`
- **THEN** the SQL transaction emits `INSERT INTO moderation_queue (target_type, target_id, trigger, ...) VALUES ('post', U, 'perspective_api_high_score', ...) ON CONFLICT DO NOTHING` AND does NOT emit any `UPDATE posts SET is_auto_hidden` statement (verifiable via JDBC statement spy)

#### Scenario: FlagOnly idempotent retry does not double-write queue
- **GIVEN** `moderator.moderate(POST, U, content)` previously wrote a queue row for `(target_type='post', target_id=U, trigger='perspective_api_high_score')` via a `FlagOnly` outcome AND a retry produces another `FlagOnly`
- **WHEN** the second invocation attempts the queue INSERT
- **THEN** the `ON CONFLICT DO NOTHING` clause suppresses the duplicate AND the queue contains exactly one row

### Requirement: NoAction outcome performs no DB writes

When `moderator.moderate(...)` returns `Outcome.NoAction` (sub-threshold score, fail-open path, or kill-switch OFF), the orchestrator SHALL NOT execute any SQL write statements.

#### Scenario: NoAction performs zero DB writes
- **WHEN** `moderator.moderate(POST, U, content)` produces `Outcome.NoAction`
- **THEN** zero INSERT, UPDATE, or DELETE statements are emitted by the orchestrator (verifiable via JDBC statement spy capturing all SQL executed during the dispatch)

### Requirement: `PerspectiveDispatcherScope` is a long-lived supervised coroutine scope with bounded shutdown drain

A `PerspectiveDispatcherScope` SHALL be constructed at application startup as:

```
val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("perspective-dispatch"))
```

Call sites at the post / reply INSERT path SHALL invoke the moderator via `scope.launch { perspectiveModerator.moderate(targetType, targetId, content) }` — fire-and-forget.

The `SupervisorJob()` ensures one failed dispatch coroutine does NOT cancel sibling dispatches (a Perspective error for post P does not affect the in-flight dispatch for post Q).

On JVM shutdown (Cloud Run revision rollover, SIGTERM), the scope SHALL drain in-flight dispatches up to a 5-second budget via:

```
withTimeoutOrNull(5_000) { scope.coroutineContext[Job]?.cancelAndJoin() }
```

Dispatches still in flight after the 5-second budget SHALL be cancelled (cooperative cancellation via the standard coroutine semantics; the Ktor HTTP client respects cancellation). A WARN log entry SHALL be emitted listing the count of cancelled-mid-dispatch coroutines.

After shutdown, any new `scope.launch { ... }` invocation (e.g., from a late call site that didn't observe the shutdown signal) SHALL emit a structured WARN log line `event=perspective_dispatch_after_shutdown target_type=<...> target_id=<...>` and silently no-op (do not throw to the caller).

This shape mirrors the canonical `:infra:fcm` `FcmDispatcherScope` lifecycle from the [`fcm-push-dispatch`](../../../specs/fcm-push-dispatch/spec.md) capability.

#### Scenario: Sibling dispatch failure does not cancel peers
- **GIVEN** `PerspectiveDispatcherScope.scope` has two in-flight dispatches D1 (for post P1) and D2 (for post P2) AND D1's `PerspectiveClient.analyze(...)` throws an unhandled exception
- **WHEN** D1 fails
- **THEN** D2 continues running to completion (the SupervisorJob isolation prevents D1's failure from propagating to its siblings)

#### Scenario: Shutdown drains in-flight dispatches up to 5 seconds
- **GIVEN** `PerspectiveDispatcherScope.scope` has an in-flight dispatch D1 that completes in 1.0s AND another D2 that completes in 2.5s
- **WHEN** JVM shutdown is initiated AND the scope's drain budget elapses
- **THEN** both D1 and D2 complete (well within the 5s budget) AND no cancellation occurs

#### Scenario: Dispatches exceeding the 5-second drain are cancelled
- **GIVEN** `PerspectiveDispatcherScope.scope` has an in-flight dispatch D1 that would complete in 10s
- **WHEN** JVM shutdown is initiated AND the 5s drain budget elapses
- **THEN** D1 is cancelled at the 5s mark AND a WARN log entry `event=perspective_dispatch_drain_exceeded cancelled_count=1` is emitted

#### Scenario: Dispatch after shutdown silently no-ops
- **GIVEN** `PerspectiveDispatcherScope.scope` has been shut down (drain completed, scope cancelled)
- **WHEN** a late call site invokes `scope.launch { perspectiveModerator.moderate(POST, U, content) }`
- **THEN** the launch silently no-ops (no exception thrown to the caller) AND a structured WARN log line `event=perspective_dispatch_after_shutdown target_type=post target_id=<U>` is emitted

### Requirement: Sentry events emitted by `PerspectiveModerator` do NOT carry user content

When `PerspectiveModerator.moderate(...)` emits a Sentry breadcrumb-level event (success or failure path), the event payload SHALL include:
- `event` (string — one of `"perspective_dispatch_failed"`, `"perspective_high_score_applied"`, `"perspective_flag_applied"`, `"perspective_kill_switch_unavailable"`, `"perspective_threshold_fallback"`)
- `target_type` (`"post"` | `"reply"`)
- `target_id` (UUID string — the post/reply identifier)
- `score_bucket` (`"low"` | `"mid"` | `"high"` — coarse bucket, not the raw score) on success-path events
- `failure_kind` (`"timeout"` | `"http_4xx"` | `"http_5xx"` | `"network"` | `"parse"`) on failure-path events
- `status_code` (integer) when `failure_kind` is `"http_4xx"` or `"http_5xx"`

The event SHALL NOT include:
- The original `content` string (raw user input)
- The raw `PerspectiveScore` object or any of its per-attribute float values (raw scores would enable bypass-pattern reverse engineering)
- Any user identifier (the orchestrator does not consume the user identity; the upstream handler may add a hashed `user.id` per [`observability-otel-foundation/spec.md`](../../../specs/observability-otel-foundation/spec.md), but `PerspectiveModerator` itself stays user-agnostic)

The orchestrator SHALL emit at most ONE Sentry event per `moderate(...)` invocation. Sentry's built-in deduplication MAY further suppress repeated events of the same `(event, failure_kind, target_type)` key across calls.

#### Scenario: Failure event omits raw content and raw scores
- **GIVEN** content `"sentinel-content-DO-NOT-LEAK"` produces a Perspective HTTP 503 failure AND `Outcome.NoAction`
- **WHEN** the Sentry event for the failure is captured
- **THEN** the event payload does NOT contain the substring `"sentinel-content-DO-NOT-LEAK"` AND has `failure_kind = "http_5xx"`, `status_code = 503`, `target_type = "post"`, `target_id = "<U>"`

#### Scenario: AutoHide success event omits raw scores
- **GIVEN** `Outcome.AutoHide(score = 0.92)` was applied AND Perspective returned `PerspectiveScore(toxicity = 0.92, severeToxicity = 0.40, identityAttack = 0.10, threat = 0.20)`
- **WHEN** the Sentry event for the success is captured
- **THEN** the event payload does NOT contain the literal floats `"0.92"`, `"0.40"`, `"0.10"`, or `"0.20"` AND has `score_bucket = "high"`, `target_type = "post"`, `target_id = "<U>"`

#### Scenario: At most one Sentry event per dispatch
- **WHEN** any `moderator.moderate(...)` invocation completes (success or failure)
- **THEN** the count of Sentry events captured for that single invocation is at most 1

### Requirement: Async dispatch wired at `posts` and `post_replies` INSERT call sites

The post creation handler (`POST /api/v1/posts` per [`post-creation/spec.md`](../../../specs/post-creation/spec.md)) AND the reply creation handler (`POST /api/v1/posts/{post_id}/replies` per [`post-replies/spec.md`](../../../specs/post-replies/spec.md)) SHALL invoke `perspectiveDispatcherScope.launch { perspectiveModerator.moderate(targetType, targetId, content) }` AFTER the synchronous content INSERT commits, BEFORE the response is sent.

The dispatch SHALL fire-and-forget — the request handler SHALL NOT `await`, `join`, or otherwise observe the dispatch coroutine's completion. Response time MUST NOT regress from the pre-Layer-3 baseline.

If the synchronous Layer 1 (`Verdict.Reject`) prevents the INSERT, the Layer 3 dispatch SHALL NOT be invoked (no row exists to moderate). This is structural — the dispatch call site sits AFTER the INSERT call site.

If the synchronous Layer 2 (`Verdict.Flag`) writes a queue row with `trigger = 'uu_ite_keyword_match'` AND Layer 3 produces `Outcome.FlagOnly` or `Outcome.AutoHide`, the resulting state has TWO `moderation_queue` rows for the same `(target_type, target_id)` — one per trigger. This is the canonical multi-trigger behavior per [`docs/05-Implementation.md:545`](../../../../../docs/05-Implementation.md); the admin UI surfaces them grouped.

#### Scenario: Post creation handler invokes Layer 3 dispatch after INSERT
- **WHEN** `POST /api/v1/posts` succeeds (Layer 1 + Layer 2 both pass; INSERT commits successfully)
- **THEN** within the request handler scope, `perspectiveDispatcherScope.launch { perspectiveModerator.moderate(POST, <new post id>, content) }` is invoked exactly once AFTER the INSERT commit AND BEFORE the response is sent (verifiable via static call-order analysis)

#### Scenario: Reply creation handler invokes Layer 3 dispatch after INSERT
- **WHEN** `POST /api/v1/posts/{post_id}/replies` succeeds
- **THEN** within the request handler scope, `perspectiveDispatcherScope.launch { perspectiveModerator.moderate(REPLY, <new reply id>, content) }` is invoked exactly once AFTER the INSERT commit AND BEFORE the response is sent

#### Scenario: Layer 1 reject prevents Layer 3 dispatch
- **GIVEN** the synchronous `TextModerator.moderate(content)` returns `Verdict.Reject(matchedKeywords = ["badword"])`
- **WHEN** the request handler returns HTTP 400 to the user
- **THEN** `perspectiveDispatcherScope.launch { ... }` is NOT invoked (no row was INSERTed; no target exists to moderate; the handler short-circuits before reaching the dispatch call site — verifiable via mock-spy call count on the dispatcher scope)

#### Scenario: Layer 2 flag does not block Layer 3 dispatch
- **GIVEN** the synchronous `TextModerator.moderate(content)` returns `Verdict.Flag(matchedKeywords = ["sara1", "sara2", "sara3"])` (Layer 2 wrote a queue row with `trigger = 'uu_ite_keyword_match'`) AND the INSERT committed
- **WHEN** the request handler returns HTTP 201 to the user
- **THEN** `perspectiveDispatcherScope.launch { ... }` IS invoked AFTER the INSERT commit (Layer 3 runs independently of Layer 2's outcome — both can fire on the same row, producing two queue rows with distinct triggers)

#### Scenario: Request response time does not regress with Layer 3 enabled
- **GIVEN** Layer 3 is enabled (kill-switch ON) AND a `PerspectiveClient` mock that simulates a 400ms latency
- **WHEN** `POST /api/v1/posts` is invoked
- **THEN** the response is sent within the pre-Layer-3 baseline latency budget (≤ p95 baseline + 10ms, accounting for the cost of the `scope.launch` call only — NOT the cost of the Perspective dispatch itself)

### Requirement: Boot-time prime is NOT required

Unlike `ModerationListLoader` (which boot-primes the Redis cache to avoid first-traffic Tier 2/3/4 cascade cost), `PerspectiveModerator` SHALL NOT have a boot-time prime step. The Perspective HTTP client is stateless per request; there is no warm cache to seed. Boot SHALL complete without any Perspective API call.

#### Scenario: No Perspective API call during application boot
- **WHEN** `Application.module()` boots
- **THEN** zero `PerspectiveClient.analyze(...)` calls are made before the boot sequence completes (the mock-spy on `PerspectiveClient` records zero invocations)
