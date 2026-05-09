# content-moderation-perspective-api — Design

## Context

The 3-tier text moderation pipeline canonicalized at [`docs/06-Security-Privacy.md:151-180`](../../../docs/06-Security-Privacy.md) is two-thirds shipped: Layers 1 and 2 (sync REJECT + sync FLAG) landed in [`content-moderation-keyword-lists` (PR #70)](../archive/2026-05-07-content-moderation-keyword-lists/proposal.md). Layer 3 (Google Perspective API toxicity classifier, async post-INSERT, 500 ms timeout, fail-open) is the unbuilt closer and is the only remaining product item in Phase 2 of the roadmap.

Constraints inherited from the project posture:
- **`secretKey(env, name)` helper for Secret Manager** ([`openspec/project.md`](../../project.md) § Critical invariants). Direct `client.accessSecretVersion("foo")` reads are a Detekt violation.
- **No vendor SDK import outside `:infra:*`** — the Perspective API HTTP client must live in an `:infra:*` module; `:backend:ktor` consumes only the pure-Kotlin `PerspectiveApiClient` interface.
- **Redis hash-tag format `{scope:<value>}`** for cluster-safe multi-key ops. The kill-switch cache key must follow this shape.
- **Content length guards** are already enforced upstream of any moderator call (post 280, reply 280); Perspective dispatch operates on already-length-validated content.
- **Sync moderator boundary is sacred**: `TextModerator.moderate(...)` from [`content-moderation-keyword-lists/spec.md`](../../specs/content-moderation-keyword-lists/spec.md) returns `Allow` / `Reject` / `Flag` synchronously; Layer 3 must not be conflated with that boundary. Layer 3 lives in a separate `PerspectiveDispatcher` orchestrator.
- **Async dispatch precedent**: `FcmDispatcherScope` from `fcm-push-dispatch` (PR #60) establishes the canonical structured-concurrency pattern for fire-and-forget post-INSERT work — `SupervisorJob` + `withTimeoutOrNull` + JVM-shutdown drain. Re-use that pattern.
- **Remote Config integration precedent**: `RemoteConfigClient` from `:infra:remote-config` (PR #70) establishes the canonical kill-switch-read shape — `fetchBoolean(parameterName)` with 5-min Redis cache; this change re-uses it for `perspective_api_enabled`.
- **Privacy invariants from `observability-otel-foundation`** (PR #66): no user content lands in span attributes / Sentry breadcrumbs / log lines. The structured WARN log emitted on each fail-open path MUST NOT include the analyzed content; only the outcome category + target type/id.

Stakeholders:
- Product safety: Pre-Launch security review §5 expects all 3 tiers operational; Layer 3 closes the toxicity-classifier gap that the keyword-list tier inherently misses (tone shift, leet substitution, novel slurs).
- Operations: kill-switch must work without a backend redeploy (Remote Config primary).
- Reliability: a Perspective API outage MUST NOT block content publishing — fail-open is mandatory.
- Cost: Free tier (1 QPS) is sufficient for MVP volume; the platform's per-user write rate limits keep us well below the QPS ceiling at MVP scale.
- Privacy: Perspective API receives the user's post content over HTTPS; this is a third-party processor disclosure that the Privacy Policy already covers (noted at `docs/06-Security-Privacy.md:160-167`). No additional privacy work in this change beyond honoring the disclosure.

## Goals / Non-Goals

**Goals**:
- A single canonical `PerspectiveDispatcher.dispatchAsync(targetType, targetId, content)` entrypoint that every post-INSERT path consumes for posts and replies.
- Deterministic 500 ms-budget fail-open contract with explicit observability at each error path.
- Pure-Kotlin `:infra:perspective-api` boundary (no vendor types leak into `:backend:ktor`).
- Three score-threshold outcomes (`> 0.8` auto-hide + queue; `[0.6, 0.8]` queue only; `< 0.6` noop) cleanly mapped to DB side-effects.
- Kill-switch via Remote Config without a redeploy.
- Sentry-grade structured logging on every fail-open path; zero exceptions propagate to the caller.
- JVM-shutdown drain via the established `FcmDispatcherScope` precedent.

**Non-Goals**:
- Custom Indonesian-language model (Month 6+ scope per [`docs/06-Security-Privacy.md:167`](../../../docs/06-Security-Privacy.md); Hive Moderation paid or self-hosted XLM-R alternatives).
- Detekt rule for moderate-then-INSERT-then-dispatch call ordering (separate `content-write-moderation-detekt-rule` follow-up; defer until rule of three with the keyword-list emit sites).
- Detekt rule for dispatch invocation at every post-INSERT call site (defer until rule of three; today only 2 emit sites).
- Score threshold tuning beyond the canonical `0.8` / `0.6` (use `moderation_match_threshold`-shaped Remote Config pattern in a follow-up if needed).
- Chat message coverage (out of scope per Q1 below; `chat_messages` lacks `is_auto_hidden` column at V15).
- Author-facing UI for "your post is hidden pending review" (mobile work; deferred until Phase 3).
- Caching / dedup of Perspective API responses (deferred — no current need; revisit if quota becomes tight).
- Throttler for outbound Perspective API calls (deferred — MVP volume is well below 1 QPS free-tier ceiling; revisit at MAU > 10k).
- Rebuilding the `moderation_queue` admin UI to surface `perspective_api_high_score` differently (Phase 3.5 admin work).

## Decisions

### D1 — Async post-INSERT dispatch over sync pre-INSERT gate

The Perspective API call SHALL run **asynchronously, post-INSERT** (after the post / reply row is committed and the HTTP 201 response is sent to the user). It SHALL NOT block the user-facing response.

**Why**:
- Canonical authority: [`docs/06-Security-Privacy.md:179`](../../../docs/06-Security-Privacy.md) explicitly says "Layer 3: Perspective API (async post-INSERT, 500ms timeout, fail-open) — score >0.8 → set is_auto_hidden = TRUE + insert moderation_queue row (visible to author, hidden from timeline until reviewed)". The async-post-INSERT shape is doctrinal, not a design choice.
- UX: a 500 ms gate on every post submission is unacceptable (perceived latency, p99 spikes, dependency on a third-party SLA). Async-with-fail-open keeps the write path fast.
- Author UX: "visible to author, hidden from timeline" — the author sees their post immediately (no confusion about whether it submitted); the auto-hide affects only the timeline-read surface (already-encoded in `visible_posts` view's `WHERE is_auto_hidden = FALSE`). The author can keep editing; admin review settles the disposition.
- Reliability: a Perspective API outage degrades to "every post passes through Layers 1 + 2 only" — exactly the pre-Layer-3 state. No content-publishing outage.

**Implications**:
- The route handler must commit the INSERT, send the 201, **then** enqueue the dispatch. The dispatch is fire-and-forget from the route handler's perspective.
- The dispatch operates on the already-persisted post and writes its results in a **separate transaction** (the dispatcher reads `posts.id` + `posts.content` is already passed through; the auto-hide UPDATE + queue INSERT happen after the API response).
- Error budget: the user never sees an error from the dispatch. All errors are silent fail-open, logged via Sentry WARN.
- Test surface implication: integration tests need to await the async dispatch completion before asserting DB state. Use `runBlocking` + the dispatcher's exposed `awaitInFlight()` test hook (mirror `FcmDispatcherScope`'s pattern).

### D2 — `:infra:perspective-api` module placement (peer to `:infra:fcm`)

The Perspective API HTTP client SHALL live in a new Gradle module `:infra:perspective-api` at `infra/perspective-api/`, peer to `:infra:fcm`, `:infra:remote-config`, etc.

**Why**:
- The "no vendor SDK import outside `:infra:*`" invariant ([`openspec/project.md`](../../project.md) § Critical invariants) demands that any third-party API client live in an `:infra:*` module. Even though Google offers no official Perspective API SDK, the `ktor-client` HTTP client is a vendor-shaped dependency and the boundary is appropriate.
- Mirrors the established pattern: `:infra:fcm` (Firebase Admin SDK) + `:infra:remote-config` (Firebase Admin Remote Config) + `:infra:supabase` (Supabase client). One vendor surface = one infra module.
- Test isolation: the `:infra:perspective-api` module's own test suite covers HTTP request shape + response parse + timeout behavior in isolation; `:backend:ktor`'s `PerspectiveDispatcher` tests use a fake `PerspectiveApiClient` and don't need MockWebServer.
- Dependency hygiene: pulls `ktor-client-*` into `:infra:perspective-api` only; `:backend:ktor` continues to depend on `ktor-server-*` exclusively.
- Future migration cheap: if the team ever swaps Perspective for Hive Moderation / Microsoft Content Moderator / a self-hosted ID-language model, the swap is bounded to this module.

**Alternatives considered**:
- Put the HTTP client directly in `:backend:ktor`. Rejected: violates the "no vendor SDK import outside `:infra:*`" invariant (where "vendor SDK" reads broadly to include any vendor-API HTTP shape).
- Co-locate with `:infra:remote-config` since both are Google. Rejected: different vendor APIs, different lifecycles, different secrets, different test surfaces. Co-location creates artificial coupling.

### D3 — Score thresholds: hardcode `0.8` / `0.6` in V1

Score thresholds SHALL be hardcoded constants `HIGH_SCORE_THRESHOLD = 0.8f` and `LOW_SCORE_THRESHOLD = 0.6f` in V1.

**Why**:
- Canonical authority: [`docs/06-Security-Privacy.md:163-164`](../../../docs/06-Security-Privacy.md) prescribes exactly these values: "Score >0.8 = auto-hide" + "Score 0.6-0.8 = flag to moderation_queue only".
- YAGNI: making them Remote-Config-tunable adds 1 more Remote Config parameter, 1 more cache key, 1 more fallback path, 1 more test surface. The keyword-lists change shipped `moderation_match_threshold` because that one is genuinely operationally tuned (1 vs 3 vs 5 matches changes admin queue volume materially); these toxicity thresholds are calibrated by Perspective itself and are not platform-tunable.
- If product wants to retune later (e.g., loosen to `0.7` after observing false-positive rates), a follow-up `content-moderation-perspective-api-thresholds-tunable` change can promote them to Remote Config in ~1 file edit.

**Alternatives considered**:
- Read from Remote Config alongside `perspective_api_enabled`. Rejected per YAGNI; Remote Config slot reservation is also operations work.
- Hardcode but expose via JVM system property for ops-side override. Rejected — system properties bypass the standard config-management surface; they'd be invisible to ops dashboards.

### D4 — Max-attribute scoring across the 4 requested attributes

The classifier SHALL request 4 attributes (`TOXICITY`, `SEVERE_TOXICITY`, `IDENTITY_ATTACK`, `THREAT`) and compute the **maximum** of the 4 returned summary scores as the canonical "score" for threshold comparison.

**Why**:
- Canonical authority: [`docs/06-Security-Privacy.md:162`](../../../docs/06-Security-Privacy.md) lists the 4 attributes with no guidance on aggregation. Max() is the most permissive (any one attribute scoring high triggers the gate); sum() / mean() would mask high-severity single-axis hits.
- Specifically, `THREAT` and `SEVERE_TOXICITY` are uncommon but high-impact; using max() ensures a single-attribute hit on either is enough to trigger the gate even if `TOXICITY` and `IDENTITY_ATTACK` happen to score low for the same content.
- Operationally simple to reason about: "if any attribute is `>0.8`, we hide it" matches the natural moderator mental model.
- Trivially extensible: future Perspective API attribute additions (e.g., `INSULT`, `PROFANITY`) extend the request list and the same max() rule applies.

**Alternatives considered**:
- Sum of all 4. Rejected — values are in `[0,1]` so sum can reach 4.0; thresholds become arbitrary.
- Weighted mean. Rejected — adds tunable weights with no canonical guidance; YAGNI.
- Per-attribute thresholds (different bar per attribute). Rejected — adds complexity without canonical guidance; revisit if false-positive analysis shows per-attribute calibration matters.

### D5 — Fail-open contract: never block, always log

Every Perspective API call site SHALL be fail-open. Any of the following outcomes results in a noop on the moderation queue + auto-hide path:
- HTTP timeout (`> 500 ms`)
- HTTP error (4xx, 5xx, network unreachable)
- Response parse error (malformed JSON, missing `attributeScores`, missing summary score)
- Kill switch off (`perspective_api_enabled = FALSE`)

For each fail-open outcome, the dispatcher SHALL emit ONE structured WARN log line with `event = "perspective_api_fail_open"`, `outcome = <category>`, `target_type = <"post"|"reply">`, `target_id = <UUID>`, `latency_ms = <int>` (for timeout / HTTP error categories; absent or `0` for kill-switch-off). The log line SHALL NOT include the content, the matched attributes, or any user identifier.

Sentry events SHALL NOT be raised on the kill-switch-off path (would generate noise on every flip). Sentry events SHALL be raised at WARN level on the timeout / HTTP error / parse error paths, deduplicated via Sentry's built-in fingerprinting (`event=perspective_api_fail_open` + `outcome=<category>` is the fingerprint).

**Why**:
- Canonical authority: [`docs/06-Security-Privacy.md:166`](../../../docs/06-Security-Privacy.md) prescribes "Feature flag `perspective_api_enabled` for kill switch" + line 178 prescribes "500ms timeout, fail-open".
- Reliability: a Perspective outage during a busy hour shouldn't generate a Sentry storm; Sentry's deduplication caps the noise but the kill-switch path doesn't deserve any noise at all (operator-initiated).
- Operability: the structured WARN log is sufficient for the standard "is the flag flipped?" + "is the API healthy?" dashboard queries. Cloud Logging retention (30 days default) is the authoritative replay surface.

**Implications**:
- The `latency_ms` field is the primary signal operators use to detect "Perspective is slow but not down" (close-to-500 ms latencies suggest a quota-based throttle; 500 ms-exceed-rate trending up suggests quota exhaustion).
- A Sentry alert on `outcome=parse_error` is the canary for "Perspective changed their response shape" — should be rare; investigate immediately.

### D6 — Vendor SDK boundary: pure-Kotlin `PerspectiveApiClient` interface

The `:infra:perspective-api` module SHALL expose a single public interface:

```
interface PerspectiveApiClient {
    /**
     * Returns max() of the 4 attribute summary scores in [0.0, 1.0],
     * or null if the call timed out / failed / response was unparseable.
     */
    suspend fun analyze(content: String): Float?
}
```

The implementation `KtorPerspectiveApiClient` lives in the same module. `:backend:ktor` MUST depend on the interface only; the implementation is wired via Koin at startup. A test fake `FakePerspectiveApiClient` lives in `:infra:perspective-api`'s test source set for `:backend:ktor` integration tests to consume.

**Why**:
- Vendor SDK encapsulation: zero `io.ktor.client.*` imports in `:backend:ktor`. Future swap to a different HTTP client (or to gRPC, or to a self-hosted model) is bounded.
- Test isolation: the dispatcher's tests don't run against MockWebServer; they consume `FakePerspectiveApiClient` and exercise dispatcher logic only.
- Suspend-friendly: `suspend fun` integrates with the structured-concurrency dispatcher scope without bridging.

### D7 — Structured-concurrency lifecycle: `PerspectiveDispatcherScope` mirrors `FcmDispatcherScope`

The dispatch lifecycle SHALL mirror `FcmDispatcherScope` from `fcm-push-dispatch` (PR #60):
- A `PerspectiveDispatcherScope` data class wrapping a `CoroutineScope(SupervisorJob() + Dispatchers.IO)`.
- `dispatchAsync(targetType, targetId, content)` launches a child coroutine inside the scope.
- The child runs `withTimeoutOrNull(500.milliseconds) { client.analyze(content) }`. On `null` (timeout) → fail-open path. On non-null score → score-threshold dispatch.
- A `shutdown(drainMillis: Long = 5000)` method is registered as a JVM shutdown hook in `Application.module()`. Drains in-flight dispatches up to 5 s; abandons what doesn't complete.
- A test hook `awaitInFlight()` joins all in-flight dispatch coroutines (test-only, not exposed in production wiring).

**Why**:
- Established pattern: PR #60 shipped this lifecycle, it's tested, and reviewers know the shape. Don't reinvent.
- `SupervisorJob`: a single dispatch failing doesn't cancel siblings.
- `Dispatchers.IO`: the `analyze` call is HTTP-bound; `IO` dispatcher pool is the right context.
- Shutdown drain: Cloud Run revision rollover should not cancel in-flight dispatches mid-API-call (would leak Perspective quota + leave content in a "processed but no auto-hide" state).
- 5 s drain budget is the same as FCM dispatch; matches the Cloud Run shutdown grace window.

### D8 — Score-threshold writes: same SQL transaction for UPDATE + INSERT

When `score > 0.8` (auto-hide path), the dispatcher SHALL execute the UPDATE (`SET is_auto_hidden = TRUE`) AND the `moderation_queue` INSERT in **the same SQL transaction**. When `0.6 ≤ score ≤ 0.8` (queue-only path), only the INSERT runs (no transaction needed beyond JDBC's default auto-commit, but for code-path symmetry the implementation may use a single-statement transaction).

The UPDATE uses `WHERE id = :target_id AND deleted_at IS NULL` (mirrors the V9 reports auto-hide path's idempotency guard) so a tombstoned post / hard-deleted post is a no-op (no auto-hide on a deleted row).

The INSERT uses `ON CONFLICT (target_type, target_id, trigger) DO NOTHING` for idempotency under retry / duplicate-dispatch scenarios.

**Why**:
- Atomicity: the auto-hide flip without the queue row would leave admins blind to why a post is hidden ("who hid this?"); the queue row without the auto-hide flip would let toxic content stay live in the timeline. Both states are operationally bad; the transaction prevents either partial state.
- Idempotency on the queue INSERT means a retried dispatch (e.g., from a transient DB error in the UPDATE) doesn't pile up duplicate queue rows.
- The `deleted_at IS NULL` guard handles the rare case where a post is hard-deleted (admin action) between the INSERT and the dispatch's UPDATE; the dispatch becomes a no-op.

## Open Questions

### Q1 — Chat message coverage (RESOLVED — out of scope)

**Resolved**: V1 of this change covers `posts` + `post_replies` only. Chat messages are out of scope.

**Rationale**: V15 schema's `chat_messages` table has no `is_auto_hidden` column. Adding one is a schema migration that's better justified by a dedicated chat-moderation change (with a clear product trigger like "telemetry shows chat is a toxicity vector"). The existing chat moderation surfaces (`TextModerator` Layers 1 + 2 sync gate, plus admin redaction via `redacted_at` / `redacted_by`) cover MVP needs.

**Trigger to revisit**: chat moderation queue entry rate, admin redaction rate, or user-report rate against chat messages indicates chat is a meaningful Layer 3 target. File a follow-up `content-moderation-perspective-api-chat-coverage` change.

### Q2 — Score `[0.6, 0.8]` queue-only outcome — surface to author?

**Resolved**: The `[0.6, 0.8]` queue-only outcome SHALL be admin-internal — the post stays visible to all viewers AND the author. The `moderation_queue` row is an admin-side flag, no author-facing notification. The author has no signal that their post was flagged at this severity unless an admin acts on it.

**Rationale**: Per [`docs/06-Security-Privacy.md:164`](../../../docs/06-Security-Privacy.md), this band is "flag to moderation_queue only" — implicitly admin-side. Surfacing it to the author would create a behavioral test ("which words trigger the soft flag?") that game-able adversaries would use to calibrate around the threshold.

### Q3 — Per-attribute vs max-attribute scoring

**Resolved**: max() of the 4 requested attribute summary scores. See Decision D4.

### Q4 — Perspective API request shape (`languages` field)

**Resolved**: The request SHALL specify `"languages": ["id", "en"]`.

**Rationale**: Perspective API documentation lists Indonesian and English as supported languages (with ID flagged as in beta / partial coverage). Sending both lets the API auto-detect and use the most appropriate model. Per [`docs/06-Security-Privacy.md:165`](../../../docs/06-Security-Privacy.md), "ID language: partial support (mixed ID/EN), accept imperfection in stopgap" — sending both languages is the canonical pattern for this acceptance.

### Q5 — Sentry severity for fail-open paths

**Resolved**: WARN for timeout / HTTP error / parse error. No event for kill-switch off (operator-initiated). See D5 for rationale.

## Risks / Trade-offs

- **Perspective API quota exhaustion**: free tier is 1 QPS. At MVP scale (≤500 seed users, average post creation rate way below 1/sec system-wide), this is a non-issue. **Mitigation**: per-instance throttler is a documented follow-up trigger when MAU > 10k or per-user write limits loosen.
- **Perspective API outage**: degrades silently to "Layer 3 noop" — content publishes normally, no auto-hide. **Mitigation**: structured WARN logs let operators detect; the existing keyword tier (Layer 1 + 2) continues to work; admin-triggered manual review still possible.
- **False positives**: > 0.8 auto-hide on a non-toxic post is the worst-case operational cost (author's post hidden; admin must un-hide). **Mitigation**: conservative `0.8` threshold (the Perspective API is calibrated such that `>0.8` is a strong signal); admin-review gate sees every auto-hide; threshold tunable via a follow-up change if false-positive rate is high.
- **False negatives**: < 0.8 toxic post stays visible. **Mitigation**: this is the "stopgap" character of the current change ([`docs/06-Security-Privacy.md:160`](../../../docs/06-Security-Privacy.md)); Month 6+ scope is custom ID-language model.
- **API shape drift**: Perspective API may rev their response format. **Mitigation**: parse-error fail-open + Sentry WARN means a canary fires immediately; we never silently break.
- **Privacy disclosure**: Perspective API receives user post content over HTTPS. **Mitigation**: covered by existing third-party processor disclosure in Privacy Policy (per `docs/06-Security-Privacy.md` § "Legal Documentation"). No new disclosure work.
- **Cost at scale**: paid tier of Perspective API is gradually billed; documented in `docs/01-Business.md` cost forecast or operations runbook (operations work; not a code-side risk).

## Migration Plan

No data migration. No Flyway migration. The change adds:
1. A new Gradle module + its dependencies.
2. A new Koin singleton wiring (Application.module()).
3. Two emit-site call additions (post creation + post replies).
4. New tests.
5. A new GCP Secret Manager slot reservation (operations work tracked separately).

Rollout:
- Stage 1: Feature ships behind `perspective_api_enabled = FALSE` (kill-switch OFF by default at deploy time despite the canonical Pre-Phase 1 §18 default of TRUE — this lets us observe the dispatch lifecycle in staging without actually calling the API; the kill-switch flip to TRUE is a separate operator action after API key provisioning + smoke test).
- Stage 2: After staging smoke green, flip `staging-perspective_api_enabled = TRUE` and observe dispatch metrics for 24 h.
- Stage 3: Production enable via `perspective_api_enabled = TRUE` Remote Config flip (audit-logged operator action; no code deploy needed).

Rollback:
- Single Remote Config flip `perspective_api_enabled = FALSE` disables Layer 3 platform-wide. Layers 1 + 2 unaffected.
- If a more severe regression is detected (dispatcher leak / coroutine starvation / DB write storm), the safer rollback is a `git revert` on the deploy commit + standard Cloud Run revision rollover.
