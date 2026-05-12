# text-moderation-perspective-api-layer Specification

## Purpose

The `text-moderation-perspective-api-layer` capability owns the asynchronous post-INSERT classifier layer (Layer 3) of the multi-layer text-moderation pipeline. Layer 1+2 (keyword-based blocklist + UU ITE wordlist, synchronous, see [`content-moderation-keyword-lists`](../content-moderation-keyword-lists/spec.md)) handle deterministic vocabulary-matched cases; Layer 3 catches what tokenization cannot — novel slurs, identity attacks, threats wrapped in benign vocabulary — by calling the OpenAI Moderation API (`omni-moderation-latest`) through a vendor-neutral `ModerationClient` interface, in a fire-and-forget coroutine AFTER each successful `posts` / `post_replies` INSERT. Score-threshold-driven outcomes map to `Outcome.AutoHide` (max-category score > 0.8 — auto-hides the row + writes a `moderation_queue` row with `trigger = 'perspective_api_high_score'` in one SQL transaction), `Outcome.FlagOnly` (0.6–0.8 — queue row only), or `Outcome.NoAction` (≤ 0.6 plus any timeout / 5xx / network failure / kill-switch-OFF, per the fail-open posture).

This capability closes the canonical multi-layer moderation contract at [`docs/06-Security-Privacy.md:153`](../../../docs/06-Security-Privacy.md) and the Pre-Launch security-review §5 "Perspective API kill switch tested" gate. Vendor pivot context: the change originally targeted Google Perspective API (per its historical name); Perspective announced end-of-2026 sunset mid-implementation, the project pivoted to OpenAI Moderation, and the historical name + V9 `moderation_queue.trigger = 'perspective_api_high_score'` enum + Firebase Remote Config parameter names (`perspective_api_*`) are preserved as documented debt — operator-facing or schema-fixed, renaming would require parallel external migrations (see the archived change's § "What is preserved as historical artifact"). The Firebase Remote Config `perspective_api_enabled` kill switch + structured `event=layer3_*` Sentry breadcrumbs (which NEVER carry user content) let operators disable Layer 3 without redeploy when the vendor degrades or cost budgets shift.
## Requirements
### Requirement: `:infra:openai-moderation` is the sole owner of the OpenAI Moderation API client

A new Gradle module `:infra:openai-moderation` SHALL be created under `infra/openai-moderation/` (alongside `:infra:fcm`, `:infra:oidc`, `:infra:otel`, `:infra:redis`, `:infra:remote-config`, `:infra:supabase`). All OpenAI Moderation API HTTP call shapes (request body assembly, response parsing, `Authorization: Bearer` header handling, endpoint URL `https://api.openai.com/v1/moderations`, model name `omni-moderation-latest`) SHALL live entirely inside `:infra:openai-moderation`.

`:backend:ktor` SHALL depend on `:infra:openai-moderation` and SHALL NOT carry any direct OpenAI-specific HTTP call code, request-body assembly, or response-parsing logic in its source files. Business modules (`:core:domain`, `:core:data`, `:shared:*`) SHALL NOT depend on `:infra:openai-moderation`.

The new module SHALL expose ONLY the `ModerationClient` public interface + a Koin-bindable implementation. The OpenAI API request/response shapes SHALL NOT leak into the public interface — `ModerationClient` returns a vendor-neutral `ModerationScore` data class holding a `Map<String, Double>` of per-category 0..1 confidence scores (the keys are the vendor's category names verbatim; consumers do not depend on a fixed attribute set).

```
data class ModerationScore(
    val categoryScores: Map<String, Double>,
) {
    fun maxScore(): Double = categoryScores.values.maxOrNull() ?: 0.0
}

interface ModerationClient {
    suspend fun analyze(content: String): ModerationScore
}
```

**Why vendor-neutral interface:** the underlying vendor pivoted mid-implementation (Google Perspective → OpenAI Moderation) when Perspective announced end-of-2026 sunset. The interface stays neutral so future vendor swaps (e.g., to Azure AI Content Safety or a self-hosted classifier) don't require changes in `:backend:ktor` consumer code. OpenAI's 13 categories (e.g., `harassment`, `harassment/threatening`, `hate`, `hate/threatening`, `sexual`, `sexual/minors`, `violence`, `violence/graphic`, etc.) flow through as `Map<String, Double>` keys; the orchestrator aggregates via `maxScore()`.

The implementation SHALL use the Ktor HTTP client (CIO engine, already on the classpath) for the outbound call and `kotlinx.serialization` for JSON parsing. The implementation SHALL NOT pull in OpenAI-vendor SDK libraries.

The Ktor HTTP client SHALL configure CIO `requestTimeoutMillis = 3000`, `connectTimeoutMillis = 1000`, AND `socketTimeoutMillis = 3000` so the underlying socket is closed when the orchestrator's `withTimeoutOrNull(3000.ms)` cancels the coroutine. Engine-level timeouts defend against socket-pin under load (per design.md Decision 2).

The OpenAI API key SHALL be passed into the `OpenAiModerationClient` constructor as a `String` parameter at boot. `:backend:ktor`'s `Application.module()` resolves the API key via `secrets.resolve("openai-api-key")` (bare slot name; mirroring [`Application.kt`](../../../../../backend/ktor/src/main/kotlin/id/nearyou/app/Application.kt) `secrets.resolve("firebase-admin-sa")` precedent), with `secretKey(env, "openai-api-key")` computed for the diagnostic init log line. `:infra:openai-moderation` SHALL NOT depend on `:backend:ktor`'s `SecretResolver` interface — secret resolution happens at the `:backend:ktor` boundary, mirroring the established `:infra:fcm` / `:infra:remote-config` precedent.

#### Scenario: Module exists at the canonical path with the canonical name
- **WHEN** the project structure is inspected
- **THEN** `infra/openai-moderation/build.gradle.kts` exists AND the module is included in `settings.gradle.kts` as `:infra:openai-moderation`

#### Scenario: `:backend:ktor` depends on `:infra:openai-moderation`
- **WHEN** `:backend:ktor`'s `build.gradle.kts` `dependencies { ... }` block is inspected
- **THEN** `implementation(project(":infra:openai-moderation"))` (or equivalent) appears in the declarations

#### Scenario: `:backend:ktor` source files contain no direct OpenAI Moderation endpoint reference
- **WHEN** the source files of `:backend:ktor` are scanned for the literal string `"api.openai.com"` or `"api.openai.com/v1/moderations"`
- **THEN** zero matches are found (the OpenAI endpoint URL is encapsulated inside `:infra:openai-moderation`)

#### Scenario: `ModerationClient` interface returns plain Kotlin types only
- **WHEN** the public interface declaration of `ModerationClient` in `:infra:openai-moderation` is inspected
- **THEN** every public function's return type is a Kotlin standard-library type or the `ModerationScore` data class — no `JsonObject`, `JsonElement`, or any HTTP-response-shape type appears in the public surface

#### Scenario: API key resolved in `:backend:ktor` and passed into `:infra:openai-moderation` at construction
- **WHEN** the `OpenAiModerationClient` constructor signature in `:infra:openai-moderation` is inspected
- **THEN** the constructor takes a `String` (or equivalent value type) parameter for the API key — NOT a `SecretResolver` reference
- **AND** in `:backend:ktor`'s `Application.module()`, the call site that constructs `OpenAiModerationClient` is `OpenAiModerationClient(apiKey = secrets.resolve("openai-api-key") ?: ...)` (bare slot name, mirroring the [`Application.kt`](../../../../../backend/ktor/src/main/kotlin/id/nearyou/app/Application.kt) precedent)
- **AND** a `secretKey(env, "openai-api-key")` computation is present at the same call site for the diagnostic init log line
- **AND** no hardcoded `"staging-openai-api-key"` literal appears

#### Scenario: Ktor HTTP client is configured with engine-level timeouts
- **WHEN** the `OpenAiModerationClient` HTTP client construction is inspected
- **THEN** the CIO engine config sets `requestTimeoutMillis = 3000`, `connectTimeoutMillis = 1000`, AND `socketTimeoutMillis = 3000` (defense-in-depth against socket-pin under load per design.md Decision 2)

### Requirement: `Layer3Moderator` aggregates per-category scores via `maxScore()` and applies canonical thresholds

A `Layer3Moderator` SHALL live in `:backend:ktor` (under `id.nearyou.app.moderation`) and expose:

```
suspend fun moderate(targetType: TargetType, targetId: UUID, content: String): Outcome

enum class TargetType { POST, REPLY }

sealed interface Outcome {
    data object NoAction : Outcome
    data class FlagOnly(val score: Double) : Outcome
    data class AutoHide(val score: Double) : Outcome
}
```

The orchestrator SHALL compute the aggregate score as the per-call max across all vendor-returned categories:

```
score = moderationScore.maxScore()  // == categoryScores.values.max()
```

The orchestrator SHALL apply the canonical thresholds from [`docs/06-Security-Privacy.md:163-164`](../../../../../docs/06-Security-Privacy.md):
- `score > 0.8` → `Outcome.AutoHide(score)`
- `score > 0.6` AND `score ≤ 0.8` → `Outcome.FlagOnly(score)`
- `score ≤ 0.6` → `Outcome.NoAction`

Threshold values SHALL be tunable via Firebase Remote Config keys `perspective_api_high_score_threshold` (default 0.8) and `perspective_api_flag_threshold` (default 0.6), resolved through the `Layer3ConfigLoader` (Requirement: `Layer3ConfigLoader` caches kill-switch + thresholds in `:backend:ktor`). Both values SHALL be clamped to `[0.0, 1.0]` on EVERY read (cache + Tier 2); out-of-range values fall back to the default. The clamp prevents a single bad Remote Config push from poisoning Layer 3 enforcement for the 5-min cache window.

**Note on flag-name preservation:** the Firebase Remote Config parameter names retain the historical `perspective_api_*` prefix per the vendor-swap historical-artifact carve-out (the flags were seeded in Firebase Console at Pre-Phase 1 §18; renaming requires a parallel seed migration). The underlying classifier is now OpenAI Moderation; the operator-facing flag names are decoupled from the vendor identity.

If the loader resolves `flag_threshold > high_score_threshold` (cross-flag misconfiguration: an operator typo could invert the band logic), the orchestrator SHALL fall back to BOTH defaults (`high_score = 0.8`, `flag = 0.6`) for the affected dispatch AND emit a Sentry **ERROR** event with `event = "layer3_threshold_misconfigured"` (per design.md Decision 11; ERROR rather than WARN because cross-flag inversion is operator-actionable, not background noise).

#### Scenario: Score above 0.8 returns AutoHide
- **GIVEN** `ModerationClient.analyze(content)` returns `ModerationScore(categoryScores = mapOf("harassment" to 0.85, "hate" to 0.40, "violence" to 0.10, "sexual" to 0.20))`
- **WHEN** `moderator.moderate(POST, U, content)` is invoked
- **THEN** the result equals `Outcome.AutoHide(score = 0.85)` (the max category is `harassment = 0.85`, above the 0.8 threshold)

#### Scenario: Score in 0.6–0.8 band returns FlagOnly
- **GIVEN** `ModerationClient.analyze(content)` returns `ModerationScore(categoryScores = mapOf("harassment" to 0.70, "hate" to 0.20, "violence" to 0.10, "sexual" to 0.05))`
- **WHEN** `moderator.moderate(POST, U, content)` is invoked
- **THEN** the result equals `Outcome.FlagOnly(score = 0.70)` (the max category is `harassment = 0.70`, between 0.6 and 0.8)

#### Scenario: Score at 0.6 returns NoAction (boundary exclusive on the low side)
- **GIVEN** `ModerationClient.analyze(content)` returns `ModerationScore(categoryScores = mapOf("harassment" to 0.60, "hate" to 0.40, "violence" to 0.30, "sexual" to 0.20))`
- **WHEN** `moderator.moderate(POST, U, content)` is invoked
- **THEN** the result equals `Outcome.NoAction` (the max category is `harassment = 0.60`, NOT strictly above 0.6 — the comparison is strict `>`, not `>=`)

#### Scenario: Score just above 0.6 returns FlagOnly (FlagOnly entry boundary)
- **GIVEN** `ModerationClient.analyze(content)` returns `ModerationScore(categoryScores = mapOf("harassment" to 0.6001, "hate" to 0.40, "violence" to 0.10, "sexual" to 0.05))`
- **WHEN** `moderator.moderate(POST, U, content)` is invoked
- **THEN** the result equals `Outcome.FlagOnly(score = 0.6001)` (any value strictly greater than 0.6 enters the FlagOnly band)

#### Scenario: Threshold boundary at 0.8 is exclusive
- **GIVEN** `ModerationClient.analyze(content)` returns `ModerationScore(categoryScores = mapOf("harassment" to 0.80, "hate" to 0.50, "violence" to 0.10, "sexual" to 0.20))`
- **WHEN** `moderator.moderate(POST, U, content)` is invoked
- **THEN** the result equals `Outcome.FlagOnly(score = 0.80)` (the boundary `0.80` is NOT auto-hide — `> 0.8` is strict; `0.80` falls into the FlagOnly band because `0.80 > 0.6` AND NOT `0.80 > 0.8`)

#### Scenario: Score at 0.0 returns NoAction (low extreme)
- **GIVEN** `ModerationClient.analyze(content)` returns `ModerationScore(categoryScores = mapOf("harassment" to 0.0, "hate" to 0.0, "violence" to 0.0, "sexual" to 0.0))`
- **WHEN** `moderator.moderate(POST, U, content)` is invoked
- **THEN** the result equals `Outcome.NoAction` (max is 0.0, well below all thresholds)

#### Scenario: Score at 1.0 returns AutoHide (high extreme)
- **GIVEN** `ModerationClient.analyze(content)` returns `ModerationScore(categoryScores = mapOf("harassment" to 1.0, "hate" to 1.0, "violence" to 1.0, "sexual" to 1.0))`
- **WHEN** `moderator.moderate(POST, U, content)` is invoked
- **THEN** the result equals `Outcome.AutoHide(score = 1.0)` (max is 1.0, well above the 0.8 high-score threshold)

#### Scenario: Max aggregation across categories — a single high category dominates
- **GIVEN** `ModerationClient.analyze(content)` returns `ModerationScore(categoryScores = mapOf("harassment" to 0.30, "hate" to 0.20, "hate/threatening" to 0.92, "violence" to 0.10))`
- **WHEN** `moderator.moderate(POST, U, content)` is invoked
- **THEN** the result equals `Outcome.AutoHide(score = 0.92)` (the max category is `hate/threatening = 0.92`, above the 0.8 threshold; max-aggregation surfaces the worst single category — useful for Indonesian SARA context where `hate/threatening` is the high-risk surface)

#### Scenario: Max aggregation across tied categories
- **GIVEN** `ModerationClient.analyze(content)` returns `ModerationScore(categoryScores = mapOf("harassment" to 0.85, "hate" to 0.85, "violence" to 0.10, "sexual" to 0.05))` (two categories tied at 0.85)
- **WHEN** `moderator.moderate(POST, U, content)` is invoked
- **THEN** the result equals `Outcome.AutoHide(score = 0.85)` (max returns the tied value; the implementation does NOT accidentally use `+` or `avg`)

#### Scenario: Empty category map returns NoAction (defensive)
- **GIVEN** `ModerationClient.analyze(content)` returns `ModerationScore(categoryScores = emptyMap())` (e.g., a future vendor variant returns no categories)
- **WHEN** `moderator.moderate(POST, U, content)` is invoked
- **THEN** `maxScore()` returns `0.0` (defensive `?: 0.0` per the helper contract) AND the result equals `Outcome.NoAction` (treats vendor returning no categories as NoAction-equivalent rather than throwing)

#### Scenario: Threshold tunable via Remote Config
- **GIVEN** Firebase Remote Config has `perspective_api_high_score_threshold = 0.95` AND `perspective_api_flag_threshold = 0.5`
- **WHEN** `moderator.moderate(POST, U, content)` is invoked AND `ModerationClient.analyze(content)` returns `ModerationScore(categoryScores = mapOf("harassment" to 0.85, "hate" to 0.30, "violence" to 0.10, "sexual" to 0.05))`
- **THEN** the result equals `Outcome.FlagOnly(score = 0.85)` (the max `0.85` is above the tuned flag threshold `0.5` but NOT above the tuned high-score threshold `0.95`)

#### Scenario: Threshold clamped on out-of-range positive Remote Config value
- **GIVEN** Firebase Remote Config returns `perspective_api_high_score_threshold = 1.5` (out of `[0.0, 1.0]`)
- **WHEN** `moderator.moderate(POST, U, content)` is invoked
- **THEN** the high-score threshold used internally is `0.8` (the default; out-of-range value is treated as failure) AND a Sentry WARN with `event = "layer3_threshold_fallback"`, `key = "perspective_api_high_score_threshold"`, `reason = "out_of_range"` is emitted

#### Scenario: Threshold clamped on out-of-range negative Remote Config value (Tier 2 path)
- **GIVEN** Firebase Remote Config returns `perspective_api_flag_threshold = -0.1` (out of `[0.0, 1.0]`)
- **WHEN** `moderator.moderate(POST, U, content)` is invoked
- **THEN** the flag threshold used internally is `0.6` (the default; the negative value is rejected by the same `[0.0, 1.0]` clamp that catches positive out-of-range values; the clamp is symmetric) AND a Sentry WARN with `event = "layer3_threshold_fallback"`, `key = "perspective_api_flag_threshold"`, `reason = "out_of_range"` is emitted

#### Scenario: Threshold clamped on cached out-of-range value (cached-bad-value poisoning prevention)
- **GIVEN** Redis key `{scope:layer3_config}:{flag:perspective_api_high_score_threshold}` holds the value `"-0.5"` (a stale cached value from a prior bad Remote Config push that has since been corrected, but the cache TTL has not yet elapsed)
- **WHEN** `moderator.moderate(POST, U, content)` is invoked
- **THEN** the high-score threshold used internally is `0.8` (the default; the Tier 1 cache hit's negative value is rejected by the same `[0.0, 1.0]` clamp that applies to Tier 2 reads — a single bad Remote Config push CANNOT poison Layer 3 enforcement for the 5-min cache window)

#### Scenario: Cross-flag misconfiguration falls back to defaults + Sentry ERROR
- **GIVEN** the loader resolves `perspective_api_flag_threshold = 0.9` AND `perspective_api_high_score_threshold = 0.7` (cross-flag inversion: flag threshold higher than high-score threshold)
- **WHEN** `moderator.moderate(POST, U, content)` is invoked AND `ModerationClient.analyze(content)` returns `ModerationScore(categoryScores = mapOf("harassment" to 0.75, "hate" to 0.10, "violence" to 0.05))` (a score that, under the inverted thresholds, would silently AutoHide because every score >0.7 → AutoHide and the FlagOnly band is empty)
- **THEN** BOTH thresholds fall back to defaults (`high_score = 0.8`, `flag = 0.6`) — under the defaults, the score `0.75` enters the FlagOnly band → result equals `Outcome.FlagOnly(score = 0.75)` AND a Sentry **ERROR** event is emitted with `event = "layer3_threshold_misconfigured"`, `flag_threshold = 0.9`, `high_score_threshold = 0.7` (the misconfiguration is operator-actionable; ERROR not WARN per design.md Decision 11)

### Requirement: `Layer3ConfigLoader` caches kill-switch + thresholds in `:backend:ktor`

A `Layer3ConfigLoader` SHALL live in `:backend:ktor` (under `id.nearyou.app.moderation`, sibling of `Layer3Moderator`) and own the cache layer for the kill-switch and threshold flags. The loader SHALL NOT live inside `:infra:remote-config` — that infra module is stateless (no Redis dependency) per its module contract.

The loader exposes (suspending where applicable):

```
suspend fun isEnabled(): Boolean        // perspective_api_enabled
suspend fun highScoreThreshold(): Double // perspective_api_high_score_threshold
suspend fun flagThreshold(): Double     // perspective_api_flag_threshold
```

For each flag, the loader applies a 3-tier resolution ladder (mirroring `ModerationListLoader`):

1. **Tier 1 — Redis 5-min cache.** Read JSON-serialized value from key `{scope:layer3_config}:{flag:<flag-name>}` (where `<flag-name>` is one of `perspective_api_enabled`, `perspective_api_high_score_threshold`, `perspective_api_flag_threshold` — operator-facing flag names retain the historical `perspective_api_*` prefix per the vendor-swap carve-out). Hit: return the parsed value (subject to the clamp + cross-flag-misconfig checks per the orchestrator's contract). Miss: proceed to Tier 2.
2. **Tier 2 — Firebase Remote Config.** Fetch via `RemoteConfigClient.fetchBoolean(...)` (kill-switch) or `RemoteConfigClient.fetchDouble(...)` (thresholds; the new method per design.md Decision 6b). On success: cache to Tier 1 with 5-min TTL via Lettuce `SETEX` and return. On network error / parse error: emit Sentry WARN with `event = "layer3_threshold_fallback"`, `tier = "remote_config"`, `to = "default"`, `key = "<flag-name>"` and proceed to Tier 3.
3. **Tier 3 — Static default.** Return the hardcoded default (`perspective_api_enabled = true`, `high_score_threshold = 0.8`, `flag_threshold = 0.6`). Cache to Tier 1 with 5-min TTL.

For the kill-switch SPECIFICALLY: if Tiers 1 + 2 both fail (Tier 2 throws, NOT a clean false-return), the loader emits a Sentry **ERROR** with `event = "layer3_kill_switch_unavailable"` and falls OPEN to `enabled = true` (per design.md Decision 12 — ERROR rather than WARN because the operator's disable is being silently bypassed; operator wants to be paged).

The Redis cache key SHALL match the pattern `{scope:layer3_config}:{flag:<flag-name>}` exactly (cluster-safe via the hash-tag braces, mirroring `RedisHashTagRule`). The scope token `layer3_config` is vendor-neutral; the `<flag-name>` retains the operator-facing `perspective_api_*` prefix.

Cross-flag misconfiguration (`flag_threshold > high_score_threshold`) is detected at threshold-load time (per design.md Decision 11): the loader returns the resolved values to the orchestrator unchanged; the orchestrator checks the cross-flag invariant and falls back to BOTH defaults if violated, emitting the Sentry ERROR.

#### Scenario: Tier 1 cache hit short-circuits
- **GIVEN** Redis key `{scope:layer3_config}:{flag:perspective_api_high_score_threshold}` holds the value `"0.85"`
- **WHEN** `loader.highScoreThreshold()` is invoked
- **THEN** the result equals `0.85` AND no Remote Config fetch is made AND no Sentry event is emitted

#### Scenario: Tier 1 cache miss, Tier 2 Remote Config success
- **GIVEN** Redis key `{scope:layer3_config}:{flag:perspective_api_enabled}` does NOT exist AND Firebase Remote Config returns `perspective_api_enabled = false`
- **WHEN** `loader.isEnabled()` is invoked
- **THEN** the result equals `false` AND the Redis key is populated with TTL 300s AND no Sentry event is emitted

#### Scenario: Cache key uses canonical hash-tag format with vendor-neutral scope
- **WHEN** the loader writes to Redis after a successful Tier 2 resolution for the kill-switch
- **THEN** the Redis key written equals exactly `"{scope:layer3_config}:{flag:perspective_api_enabled}"` (vendor-neutral `layer3_config` scope; flag name retains operator-facing `perspective_api_enabled`; matches the `RedisHashTagRule` Detekt expectation)

### Requirement: `Layer3Moderator` invocation has a 3000ms timeout (regional baseline) and fails open

The orchestrator SHALL wrap the `ModerationClient.analyze(content)` call in `withTimeoutOrNull(3000.milliseconds)`. On timeout (return value null), the orchestrator SHALL return `Outcome.NoAction` AND emit a Sentry WARN with `event = "layer3_dispatch_failed"`, `failure_kind = "timeout"`.

**Regional baseline note:** the 3000ms budget is calibrated for `asia-southeast1` Cloud Run deployment → US-hosted OpenAI. Empirical TTFB from Singapore is **bimodal** (measured 2026-05-11 via raw curl from a one-shot Cloud Run Job in the production region): ~40% fast-path at 550-700ms, ~40% slow-path at 1500-1550ms, ~20% gateway-timeout outliers at 15s+. The 3000ms budget covers the observed slow tail with ~1500ms margin and still bails fast on the 504 outliers (which would otherwise hold coroutine resources for 15s+). The budget evolved through the change lifecycle: 500ms (pre-pivot canonical doc assuming US deployment) → 1500ms (initial post-pivot bump after fast-path measurement) → 3000ms (final, after observing the bimodal slow-path tail at 1500ms). A deployment in a US Cloud Run region could safely tighten this to ~500-800ms; deployments in other Asia/EU regions should re-measure before changing. The budget is a constructor parameter (`analyzeTimeoutMillis` on `DefaultLayer3Moderator`); tests assert the canonical default `3000L` on the `ANALYZE_TIMEOUT_MILLIS` companion constant.

The orchestrator SHALL ALSO return `Outcome.NoAction` (fail-open) on any of:
- OpenAI Moderation API HTTP 4xx response (auth failure, malformed request, rate limit) — Sentry WARN with `failure_kind = "http_4xx"`, including the integer status code
- OpenAI Moderation API HTTP 5xx response (vendor outage) — Sentry WARN with `failure_kind = "http_5xx"`, including the integer status code
- Network exception thrown (DNS fail, TLS handshake fail, connection refused) — Sentry WARN with `failure_kind = "network"`
- Response-body parse error (vendor returned an unexpected JSON shape) — Sentry WARN with `failure_kind = "parse"`
- Kill-switch read returns FALSE: `loader.isEnabled()` returns `false` — silent skip, no Sentry event, NO `ModerationClient.analyze(...)` invocation
- Kill-switch read failure: `loader.isEnabled()` cascades through Tiers 1+2 and throws — fail-OPEN to `enabled = true`, emit Sentry **ERROR** (per design.md Decision 12) with `event = "layer3_kill_switch_unavailable"`, attempt the moderation call

The orchestrator SHALL NOT propagate any non-cancellation exception out of `moderate(...)` — it absorbs every failure mode and returns `Outcome.NoAction`. `CancellationException` SHALL propagate per coroutine convention (so structured cancellation works correctly through the dispatcher scope).

#### Scenario: 3000ms timeout returns NoAction with Sentry WARN
- **GIVEN** `ModerationClient.analyze(content)` suspends for 3100ms (e.g., simulated by a `delay(3100)` in a test fixture; tests typically use a tighter constructor-tunable `analyzeTimeoutMillis` to keep runtime fast — the boundary semantic is what's asserted, not a hardcoded 3000ms)
- **WHEN** `moderator.moderate(POST, U, content)` is invoked
- **THEN** the result equals `Outcome.NoAction` AND exactly one Sentry WARN is emitted with `event = "layer3_dispatch_failed"`, `failure_kind = "timeout"`, `target_type = "post"`, `target_id = "<U>"` AND no vendor response is consumed AND no `moderation_queue` row is written AND no `is_auto_hidden` flip occurs

#### Scenario: HTTP 5xx returns NoAction with Sentry WARN
- **GIVEN** `ModerationClient.analyze(content)` throws an exception representing an HTTP 503 response from the OpenAI Moderation endpoint
- **WHEN** `moderator.moderate(POST, U, content)` is invoked
- **THEN** the result equals `Outcome.NoAction` AND exactly one Sentry WARN is emitted with `event = "layer3_dispatch_failed"`, `failure_kind = "http_5xx"`, `status_code = 503`

#### Scenario: HTTP 429 (rate limit) returns NoAction with Sentry WARN
- **GIVEN** `ModerationClient.analyze(content)` throws an exception representing an HTTP 429 response (OpenAI rate-limit exhaustion)
- **WHEN** `moderator.moderate(POST, U, content)` is invoked
- **THEN** the result equals `Outcome.NoAction` AND exactly one Sentry WARN is emitted with `event = "layer3_dispatch_failed"`, `failure_kind = "http_4xx"`, `status_code = 429` (operator signal that the account-level rate limit is being hit)

#### Scenario: Network exception returns NoAction with Sentry WARN
- **GIVEN** `ModerationClient.analyze(content)` throws a `java.net.ConnectException` (e.g., simulated DNS failure)
- **WHEN** `moderator.moderate(POST, U, content)` is invoked
- **THEN** the result equals `Outcome.NoAction` AND exactly one Sentry WARN is emitted with `event = "layer3_dispatch_failed"`, `failure_kind = "network"`

#### Scenario: Response parse error returns NoAction with Sentry WARN
- **GIVEN** `ModerationClient.analyze(content)` throws a `kotlinx.serialization.SerializationException` (e.g., vendor returned an unexpected JSON shape)
- **WHEN** `moderator.moderate(POST, U, content)` is invoked
- **THEN** the result equals `Outcome.NoAction` AND exactly one Sentry WARN is emitted with `event = "layer3_dispatch_failed"`, `failure_kind = "parse"`

#### Scenario: Kill-switch OFF skips dispatch silently
- **GIVEN** `loader.isEnabled()` returns `false`
- **WHEN** `moderator.moderate(POST, U, content)` is invoked
- **THEN** the result equals `Outcome.NoAction` AND `ModerationClient.analyze(...)` is NOT invoked (verifiable via mock-spy call count) AND no Sentry event is emitted (operator-controlled disable is a normal state, not an alert)

#### Scenario: Kill-switch read failure fails open with Sentry ERROR
- **GIVEN** `loader.isEnabled()` cascades through Tiers 1 + 2 and throws (Remote Config network error AND no fallback resolves cleanly)
- **WHEN** `moderator.moderate(POST, U, content)` is invoked
- **THEN** `ModerationClient.analyze(...)` IS invoked (fail-OPEN to enabled=true preserves Layer 3 protection during Remote Config outages) AND a Sentry **ERROR** (NOT WARN) with `event = "layer3_kill_switch_unavailable"` is emitted (per design.md Decision 12; ERROR because the operator's disable is being silently bypassed and operators want to be paged)

#### Scenario: Orchestrator never throws non-cancellation exceptions to caller
- **GIVEN** any non-cancellation failure mode in the dispatch chain (timeout, HTTP error, network exception, parse exception, Remote Config exception)
- **WHEN** `moderator.moderate(POST, U, content)` is invoked
- **THEN** the function returns a value (`Outcome.NoAction` in failure modes) AND does NOT throw any non-cancellation exception to the caller

#### Scenario: Orchestrator propagates CancellationException
- **GIVEN** the dispatcher scope is cancelled while `moderator.moderate(POST, U, content)` is in flight (e.g., JVM shutdown signal mid-dispatch past the drain budget)
- **WHEN** the cancellation reaches the orchestrator
- **THEN** the orchestrator allows `CancellationException` to propagate (does NOT swallow it as a generic failure) — structured coroutine cancellation works correctly

### Requirement: AutoHide outcome flips `is_auto_hidden` and writes queue row in one SQL transaction (with soft-delete guard)

When `moderator.moderate(...)` returns `Outcome.AutoHide(score)`, the orchestrator SHALL execute — inside ONE SQL transaction with a single COMMIT:
1. `UPDATE posts SET is_auto_hidden = TRUE WHERE id = ? AND deleted_at IS NULL` (or `UPDATE post_replies SET is_auto_hidden = TRUE WHERE id = ? AND deleted_at IS NULL` when `targetType = REPLY`)
2. `INSERT INTO moderation_queue (target_type, target_id, trigger, ...) VALUES (..., 'perspective_api_high_score', ...) ON CONFLICT (target_type, target_id, trigger) DO NOTHING`

Both statements run in the same transaction; if either fails, the transaction is rolled back and neither applies. Idempotent retries are safe via the ON CONFLICT clause (the second invocation's UPDATE is a no-op since the flag is already TRUE; its INSERT is conflict-suppressed).

**Trigger string preservation:** The `moderation_queue` row's `trigger` SHALL be the canonical string `'perspective_api_high_score'` per the V9 Flyway migration (`backend/ktor/src/main/resources/db/migration/V9__reports_moderation.sql:81`). The string retains the historical `perspective_api_*` prefix per the vendor-swap historical-artifact carve-out — the CHECK constraint enum was fixed at V9 before the vendor swap; renaming requires a Flyway migration. The underlying classifier is now OpenAI Moderation. No new Flyway migration is needed for this change.

The `AND deleted_at IS NULL` predicate on the UPDATE matches the canonical V9 writer pattern documented at [`backend/ktor/src/main/resources/db/migration/V9__reports_moderation.sql:36`](../../../../../backend/ktor/src/main/resources/db/migration/V9__reports_moderation.sql) — without it, the async Layer 3 dispatch could flip a soft-deleted row's `is_auto_hidden` flag, surfacing to admin review as a phantom queue entry against deleted content.

If the UPDATE matches zero rows (race-to-no-op when the row was soft-deleted between INSERT and dispatch), the orchestrator SHALL skip the INSERT and emit a structured INFO log noting the soft-deleted-target race. No queue row is written, no Sentry event is emitted (the soft-delete race is benign — the user voluntarily deleted their content; Layer 3 has nothing to moderate).

The `moderation_queue` row's `priority` field SHALL be set to a value matching the existing `auto_hide_3_reports` priority (whatever value the V9 trigger sets — verify at implementation time). The row's `status` SHALL be the default unreviewed state (typically `'pending'`).

#### Scenario: AutoHide writes both the flip and the queue row in one transaction
- **WHEN** `moderator.moderate(POST, U, content)` produces `Outcome.AutoHide(score = 0.92)`
- **THEN** the SQL transaction emits both `UPDATE posts SET is_auto_hidden = TRUE WHERE id = U AND deleted_at IS NULL` AND `INSERT INTO moderation_queue (target_type, target_id, trigger, ...) VALUES ('post', U, 'perspective_api_high_score', ...) ON CONFLICT DO NOTHING` AND a single COMMIT (atomicity verifiable via JDBC statement spy)

#### Scenario: AutoHide for reply target updates `post_replies` not `posts`
- **WHEN** `moderator.moderate(REPLY, R, content)` produces `Outcome.AutoHide(score = 0.85)`
- **THEN** the SQL transaction emits `UPDATE post_replies SET is_auto_hidden = TRUE WHERE id = R AND deleted_at IS NULL` (NOT `UPDATE posts ...`) AND `INSERT INTO moderation_queue (target_type, target_id, trigger, ...) VALUES ('reply', R, 'perspective_api_high_score', ...) ON CONFLICT DO NOTHING`

#### Scenario: Soft-deleted target races to no-op
- **GIVEN** the post (id `U`) was soft-deleted (`UPDATE posts SET deleted_at = NOW()`) between the user's INSERT and the async Layer 3 dispatch
- **WHEN** `moderator.moderate(POST, U, content)` produces `Outcome.AutoHide(score = 0.92)`
- **THEN** `UPDATE posts SET is_auto_hidden = TRUE WHERE id = U AND deleted_at IS NULL` returns `0` (no rows affected) AND the `INSERT INTO moderation_queue ...` is NOT executed (orchestrator detects the zero-affected-rows return and short-circuits) AND a structured INFO log line is emitted noting the soft-deleted-target race AND no Sentry event is emitted (benign race, user voluntarily deleted content)

#### Scenario: AutoHide idempotent retry does not double-write queue
- **GIVEN** `moderator.moderate(POST, U, content)` previously wrote a `moderation_queue` row with `(target_type, target_id, trigger) = ('post', U, 'perspective_api_high_score')` AND a retry for the same target produces another `Outcome.AutoHide`
- **WHEN** the second invocation attempts the queue INSERT
- **THEN** the `ON CONFLICT (target_type, target_id, trigger) DO NOTHING` clause suppresses the duplicate AND the queue contains exactly one row for that target+trigger AND `is_auto_hidden` remains TRUE (the second `UPDATE posts SET is_auto_hidden = TRUE` is a no-op, idempotent)

#### Scenario: Concurrent AutoHide invocations collapse to one queue row
- **GIVEN** two concurrent transactions T1 and T2 each produce `Outcome.AutoHide` for the same `(target_type='post', target_id=P)` (e.g., a retry race where two dispatches for the same post fire nearly simultaneously)
- **WHEN** both transactions execute their UPDATE + INSERT pair AND both COMMIT
- **THEN** exactly one `moderation_queue` row exists for `(target_type='post', target_id=P, trigger='perspective_api_high_score')` AND neither transaction surfaces a unique-violation error to the orchestrator

#### Scenario: Atomicity rollback when queue INSERT fails mid-transaction
- **GIVEN** a test fixture configured to throw a SQL exception during the queue `INSERT` step (e.g., a triggered `RuntimeException` from a JDBC interceptor)
- **WHEN** `moderator.moderate(POST, U, content)` produces `Outcome.AutoHide` and attempts the transactional UPDATE+INSERT
- **THEN** the SQL transaction rolls back AND a re-SELECT of the post row shows `is_auto_hidden = FALSE` (the UPDATE was not committed) AND the `moderation_queue` table contains zero rows for `(target_type='post', target_id=U, trigger='perspective_api_high_score')`

### Requirement: FlagOnly outcome writes queue row only

When `moderator.moderate(...)` returns `Outcome.FlagOnly(score)`, the orchestrator SHALL execute — inside one SQL transaction:

1. `INSERT INTO moderation_queue (target_type, target_id, trigger, ...) VALUES (..., 'perspective_api_high_score', ...) ON CONFLICT (target_type, target_id, trigger) DO NOTHING`

WITHOUT updating `is_auto_hidden`. The row remains visible on timelines until an admin reviews it. The single-statement transaction is trivially atomic; explicit transaction boundaries are noted here so the implementation matches the AutoHide path's transactional shape.

The `moderation_queue` row's `trigger` SHALL be the canonical string `'perspective_api_high_score'` (per the V9 enum + historical-artifact carve-out — see the AutoHide Requirement). The FlagOnly outcome and the AutoHide outcome share the same trigger name. Admin disambiguation between the FlagOnly and AutoHide outcomes is via the **related row's `is_auto_hidden` state** (per design.md Decision 10): a queue row whose target's `is_auto_hidden = TRUE` was the AutoHide path; a queue row whose target's `is_auto_hidden = FALSE` was the FlagOnly path. The admin UI's existing `(target_type, target_id)` grouping JOINs against the source row, so this disambiguation surfaces naturally without a separate trigger string or queue-row column.

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

### Requirement: `Layer3DispatcherScope` is a long-lived supervised coroutine scope with bounded shutdown drain

A `Layer3DispatcherScope` SHALL be constructed at application startup as a single class owning BOTH the lifecycle (long-lived `SupervisorJob`-rooted scope, bounded shutdown drain) AND the dispatch-after-shutdown WARN behavior. This is a **deliberate divergence** from the `:infra:fcm` pattern (which splits these responsibilities between `FcmDispatcherScope` + `FcmDispatcher`); per design.md Decision 4, Layer 3 dispatch is simpler than FCM (no payload builders, no parallelism limit, no multi-token fan-out) so collapsing both responsibilities into one class keeps the surface lean.

Internal shape:

```
class Layer3DispatcherScope(...) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("layer3-dispatch"))
    @Volatile private var shutdown = false

    fun dispatch(parentContext: CoroutineContext, block: suspend () -> Unit): Job? { ... }
    suspend fun shutdown(drainMillis: Long = 5_000) { ... }
}
```

The `dispatch(parentContext, block)` API takes the originating request's coroutine context as `parentContext` so OTel trace context inherits per design.md Decision 13 (the dispatch span is parented under the originating request span via the propagated `traceparent`). The supervisor job ensures one failed dispatch coroutine does NOT cancel sibling dispatches (a vendor error for post P does not affect the in-flight dispatch for post Q).

On JVM shutdown (Cloud Run revision rollover, SIGTERM), the scope SHALL drain in-flight dispatches up to a 5-second budget via:

```
withTimeoutOrNull(drainMillis) { scope.coroutineContext[Job]?.cancelAndJoin() }
```

Dispatches still in flight after the 5-second budget SHALL be cancelled (cooperative cancellation via the standard coroutine semantics; the Ktor HTTP client respects cancellation, with engine-level timeouts per Decision 2 ensuring socket close). A WARN log entry SHALL be emitted listing the count of cancelled-mid-dispatch coroutines.

After shutdown, any new `dispatch(...)` invocation (e.g., from a late call site that didn't observe the shutdown signal) SHALL emit a structured WARN log line `event=layer3_dispatch_after_shutdown target_type=<...> target_id=<...>` and silently no-op (return `null` from `dispatch(...)`; do not throw to the caller).

#### Scenario: Sibling dispatch failure does not cancel peers
- **GIVEN** `Layer3DispatcherScope.scope` has two in-flight dispatches D1 (for post P1) and D2 (for post P2) AND D1's `ModerationClient.analyze(...)` throws an unhandled exception
- **WHEN** D1 fails
- **THEN** D2 continues running to completion (the SupervisorJob isolation prevents D1's failure from propagating to its siblings)

#### Scenario: Shutdown drains in-flight dispatches up to 5 seconds
- **GIVEN** `Layer3DispatcherScope.scope` has an in-flight dispatch D1 that completes in 1.0s AND another D2 that completes in 2.5s
- **WHEN** JVM shutdown is initiated AND the scope's drain budget elapses
- **THEN** both D1 and D2 complete (well within the 5s budget) AND no cancellation occurs

#### Scenario: Dispatches exceeding the 5-second drain are cancelled
- **GIVEN** `Layer3DispatcherScope.scope` has an in-flight dispatch D1 that would complete in 10s
- **WHEN** JVM shutdown is initiated AND the 5s drain budget elapses
- **THEN** D1 is cancelled at the 5s mark AND a WARN log entry `event=layer3_dispatch_drain_exceeded cancelled_count=1` is emitted

#### Scenario: Dispatch after shutdown silently no-ops (with full-shutdown precondition)
- **GIVEN** `Layer3DispatcherScope.shutdown(drainMillis = 5_000)` has been awaited to full completion (drain elapsed AND scope cancelled — verified by `scope.coroutineContext[Job]?.isActive == false`)
- **WHEN** a late call site invokes `scope.dispatch(parentContext) { layer3Moderator.moderate(POST, U, content) }`
- **THEN** the call returns `null` (no Job; silent no-op) AND no exception is thrown to the caller AND a structured WARN log line `event=layer3_dispatch_after_shutdown target_type=post target_id=<U>` is emitted

### Requirement: Sentry events emitted by `Layer3Moderator` and `Layer3ConfigLoader` do NOT carry user content

When the orchestrator or loader emits a Sentry event (success or failure path), the event payload SHALL include:
- `event` (string — one of `"layer3_dispatch_failed"`, `"layer3_high_score_applied"`, `"layer3_flag_applied"`, `"layer3_kill_switch_unavailable"`, `"layer3_threshold_fallback"`, `"layer3_threshold_misconfigured"`)
- `target_type` (`"post"` | `"reply"`) on dispatch events
- `target_id` (UUID string — the post/reply identifier; non-PII per the `chat-realtime-broadcast` precedent where `conversation_id` in span attributes is sanctioned — see [`observability-otel-foundation/spec.md`](../../../specs/observability-otel-foundation/spec.md))
- `score_bucket` on success-path events: `"low"` if `score ≤ 0.6`, `"mid"` if `0.6 < score ≤ 0.8`, `"high"` if `score > 0.8` (bucket boundaries match the canonical thresholds; explicit mapping per design.md Decision 7)
- `failure_kind` (`"timeout"` | `"http_4xx"` | `"http_5xx"` | `"network"` | `"parse"`) on failure events
- `status_code` (integer) when `failure_kind` is `"http_4xx"` or `"http_5xx"`

The event SHALL NOT include:
- The original `content` string (raw user input)
- The raw `ModerationScore` object or any of its per-category float values (raw scores would enable bypass-pattern reverse engineering)
- Any user identifier (the orchestrator does not consume the user identity; the upstream handler may add a hashed `user.id` per [`observability-otel-foundation/spec.md`](../../../specs/observability-otel-foundation/spec.md), but `Layer3Moderator` itself stays user-agnostic)

The orchestrator + loader SHALL emit at most ONE Sentry event per `moderate(...)` invocation (across both surfaces). Sentry's built-in deduplication MAY further suppress repeated events of the same `(event, failure_kind, target_type)` key across calls.

#### Scenario: Failure event omits raw content and raw scores
- **GIVEN** content `"sentinel-content-DO-NOT-LEAK"` produces an OpenAI Moderation HTTP 503 failure AND `Outcome.NoAction`
- **WHEN** the Sentry event for the failure is captured AND its on-the-wire JSON is serialized via Sentry's `JsonSerializer.serialize(event, writer)`
- **THEN** the serialized JSON blob does NOT contain the substring `"sentinel-content-DO-NOT-LEAK"` AND has `failure_kind = "http_5xx"`, `status_code = 503`, `target_type = "post"`, `target_id = "<U>"`

#### Scenario: AutoHide success event omits raw scores
- **GIVEN** `Outcome.AutoHide(score = 0.92)` was applied AND the vendor returned `ModerationScore(categoryScores = mapOf("harassment" to 0.92, "hate" to 0.40, "violence" to 0.10, "sexual" to 0.20))`
- **WHEN** the Sentry event for the success is captured AND its on-the-wire JSON is serialized
- **THEN** the serialized JSON blob does NOT contain the literal floats `"0.92"`, `"0.40"`, `"0.10"`, or `"0.20"` AND has `score_bucket = "high"`, `target_type = "post"`, `target_id = "<U>"`

#### Scenario: At most one Sentry event per dispatch
- **WHEN** any `moderator.moderate(...)` invocation completes (success or failure)
- **THEN** the count of Sentry events captured for that single invocation is at most 1

#### Scenario: score_bucket mapping is canonical
- **GIVEN** a sequence of dispatches producing `Outcome.NoAction(score=0.50)`, `Outcome.FlagOnly(score=0.65)`, `Outcome.FlagOnly(score=0.80)`, `Outcome.AutoHide(score=0.85)`
- **WHEN** the Sentry events for each are captured
- **THEN** the `score_bucket` values are exactly `"low"`, `"mid"`, `"mid"`, `"high"` respectively (boundaries: `low ≤ 0.6`, `0.6 < mid ≤ 0.8`, `high > 0.8`)

### Requirement: Async dispatch wired at `posts` and `post_replies` INSERT call sites with OTel context propagation

The post creation handler (`POST /api/v1/posts` per [`post-creation/spec.md`](../../../specs/post-creation/spec.md)) AND the reply creation handler (`POST /api/v1/posts/{post_id}/replies` per [`post-replies/spec.md`](../../../specs/post-replies/spec.md)) SHALL invoke `layer3DispatcherScope.dispatch(coroutineContext) { layer3Moderator.moderate(targetType, targetId, content) }` AFTER the synchronous content INSERT commits, BEFORE the response is sent.

The `coroutineContext` argument is the request handler's current coroutine context — passing it propagates the OTel trace context (and `traceparent` header) into the dispatch coroutine per design.md Decision 13. The Layer 3 dispatch span will be parented under the originating request span; operators investigating a slow/error dispatch can navigate the trace tree from the user's request all the way down to the OpenAI Moderation HTTP outbound call.

The dispatch SHALL fire-and-forget — the request handler SHALL NOT `await`, `join`, or otherwise observe the dispatch coroutine's completion. Response time MUST NOT regress from the pre-Layer-3 baseline.

If the synchronous Layer 1 (`Verdict.Reject`) prevents the INSERT, the Layer 3 dispatch SHALL NOT be invoked (no row exists to moderate). This is structural — the dispatch call site sits AFTER the INSERT call site.

If the synchronous Layer 2 (`Verdict.Flag`) writes a queue row with `trigger = 'uu_ite_keyword_match'` AND Layer 3 produces `Outcome.FlagOnly` or `Outcome.AutoHide`, the resulting state has TWO `moderation_queue` rows for the same `(target_type, target_id)` — one per trigger. This is the canonical multi-trigger behavior per [`docs/05-Implementation.md:545`](../../../../../docs/05-Implementation.md); the admin UI surfaces them grouped.

#### Scenario: Post creation handler invokes Layer 3 dispatch after INSERT
- **WHEN** `POST /api/v1/posts` succeeds (Layer 1 + Layer 2 both pass; INSERT commits successfully)
- **THEN** within the request handler scope, `layer3DispatcherScope.dispatch(coroutineContext) { layer3Moderator.moderate(POST, <new post id>, content) }` is invoked exactly once AFTER the INSERT commit AND BEFORE the response is sent (verifiable via static call-order analysis)

#### Scenario: Reply creation handler invokes Layer 3 dispatch after INSERT
- **WHEN** `POST /api/v1/posts/{post_id}/replies` succeeds
- **THEN** within the request handler scope, `layer3DispatcherScope.dispatch(coroutineContext) { layer3Moderator.moderate(REPLY, <new reply id>, content) }` is invoked exactly once AFTER the INSERT commit AND BEFORE the response is sent

#### Scenario: Layer 1 reject prevents Layer 3 dispatch
- **GIVEN** the synchronous `TextModerator.moderate(content)` returns `Verdict.Reject(matchedKeywords = ["badword"])`
- **WHEN** the request handler returns HTTP 400 to the user
- **THEN** `layer3DispatcherScope.dispatch(...)` is NOT invoked (no row was INSERTed; no target exists to moderate; the handler short-circuits before reaching the dispatch call site — verifiable via mock-spy call count on the dispatcher scope)

#### Scenario: Layer 2 flag does not block Layer 3 dispatch
- **GIVEN** the synchronous `TextModerator.moderate(content)` returns `Verdict.Flag(matchedKeywords = ["sara1", "sara2", "sara3"])` (Layer 2 wrote a queue row with `trigger = 'uu_ite_keyword_match'`) AND the INSERT committed
- **WHEN** the request handler returns HTTP 201 to the user
- **THEN** `layer3DispatcherScope.dispatch(...)` IS invoked AFTER the INSERT commit (Layer 3 runs independently of Layer 2's outcome — both can fire on the same row, producing two queue rows with distinct triggers)

#### Scenario: Request response time does not regress with Layer 3 enabled
- **GIVEN** Layer 3 is enabled (kill-switch ON) AND a `ModerationClient` mock that simulates a 400ms latency
- **WHEN** `POST /api/v1/posts` is invoked
- **THEN** the response is sent within the pre-Layer-3 baseline latency budget (≤ p95 baseline + 10ms, accounting for the cost of the `dispatch` call only — NOT the cost of the Layer 3 dispatch itself)

#### Scenario: Layer 3 dispatch span is parented under the originating request span (OTel context propagation)
- **GIVEN** the `POST /api/v1/posts` request creates a span `SPAN_REQ` AND the handler invokes `layer3DispatcherScope.dispatch(coroutineContext) { layer3Moderator.moderate(POST, U, content) }` AFTER the INSERT commit
- **WHEN** the dispatch coroutine performs the OpenAI Moderation HTTP call (creating a span `SPAN_DISPATCH` and a child `SPAN_OPENAI_HTTP`)
- **THEN** `SPAN_DISPATCH.parentSpanId == SPAN_REQ.spanId` AND `SPAN_OPENAI_HTTP.parentSpanId == SPAN_DISPATCH.spanId` AND all three spans share the same `traceId` (operators can navigate from the user's request span down to the OpenAI outbound call via the parent chain)

