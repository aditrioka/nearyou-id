## Context

The text-moderation pipeline today (per [`openspec/specs/content-moderation-keyword-lists/spec.md`](../../specs/content-moderation-keyword-lists/spec.md)) runs Layers 1+2 SYNCHRONOUSLY, before INSERT, via the `TextModerator.moderate(content): Verdict` orchestrator. Verdicts are `Allow`, `Reject` (Layer 1 profanity → HTTP 4xx pre-INSERT), or `Flag` (Layer 2 UU ITE → soft-flag, INSERT proceeds, queue row written in the same SQL transaction).

[`docs/06-Security-Privacy.md:175-180`](../../../docs/06-Security-Privacy.md) prescribes a **third layer** that runs differently from Layers 1+2:

> Layer 3: async post-INSERT toxicity classifier (3000ms timeout regional baseline for asia-southeast1, fail-open) — score >0.8 → set is_auto_hidden = TRUE + insert moderation_queue row (visible to author, hidden from timeline until reviewed)

Three load-bearing distinctions from Layers 1+2:
- **Asynchronous, post-INSERT.** The user's request returns 201/200 BEFORE the Layer 3 classifier call completes. The classifier runs on a fire-and-forget coroutine.
- **Network-bound + fail-open.** A 3000ms `withTimeoutOrNull` budget (regional baseline for asia-southeast1 deployment) means the call may not return at all. Any timeout, network error, 5xx response, or kill-switch-OFF → no action (the row stays as-is).
- **Outcome is mutation, not gating.** Layer 3 cannot reject the request (it's already INSERTed). High-score (>0.8) flips `is_auto_hidden = TRUE` post-hoc. Mid-score (0.6–0.8) writes a queue row only.

This change adds Layer 3 as a NEW capability rather than extending the existing `TextModerator` interface — the synchronous and asynchronous surfaces have different lifetimes, different transaction boundaries, and different failure semantics. Conflating them in one Verdict-shaped abstraction would force every caller to reason about both lifecycles.

**Vendor note:** the original OpenSpec proposal targeted Google Perspective API. Mid-implementation, Perspective announced end-of-2026 sunset with signups closed in February 2026. The change pivoted to OpenAI Moderation API (`omni-moderation-latest` model) — see [`proposal.md`](./proposal.md) § Vendor Swap Amendment for rationale + what changed. The architectural shape (orchestrator → dispatcher → writer, fail-open, kill-switch, threshold semantics) is preserved verbatim; only the vendor-specific HTTP shape + class names changed. The Kotlin interface (`ModerationClient`) stays vendor-neutral so future swaps don't churn consumers.

## Goals / Non-Goals

**Goals:**
- Ship Layer 3 of the canonical multi-layer text-moderation pipeline per [`docs/06-Security-Privacy.md:153-184`](../../../docs/06-Security-Privacy.md).
- Encapsulate the OpenAI Moderation API client behind a `:infra:openai-moderation` module, preserving the project's vendor-isolation invariant (`:backend:ktor` MUST NOT directly import third-party API SDKs per [`openspec/project.md`](../../project.md) § Module Structure). The public interface (`ModerationClient`) stays vendor-neutral via `Map<String, Double>` per-category scores so a future vendor swap doesn't require consumer changes.
- Honor the `perspective_api_enabled` Firebase Remote Config kill-switch (already seeded since Pre-Phase 1 §18; flag name retained as a historical-artifact carve-out per the vendor-swap amendment) so operators can disable Layer 3 instantly without a backend redeploy.
- Maintain fail-open posture: a vendor outage degrades moderation to Layers 1+2 only — silently from the user's perspective, with structured Sentry events for operator visibility.
- Preserve no-user-content-on-Sentry: matched content + raw scores never leave the request lifetime.
- Wire dispatch at the existing post + reply INSERT call sites; reuse the existing `moderation_queue` schema (no new Flyway migration).

**Non-Goals:**
- **Boot-time prime.** Unlike `ModerationListLoader` (which boot-primes the Redis cache so first-traffic moderation isn't slow), `Layer3Moderator` has no warm vendor-side cache to seed. The OpenAI HTTP client is stateless per request. (The orchestrator's threshold cache IS warmed lazily on first dispatch.)
- **Chat-message Layer 3.** Chat send currently runs Layers 1+2; whether to add Layer 3 to chat is a product decision (see Open Question 1). Recommendation: defer to a follow-up if product wants stricter chat moderation.
- **Mobile UI for the auto-hidden-author surface.** Author seeing their own auto-hidden post differently is a mobile-side concern; mobile is design-only per [`openspec/project.md`](../../project.md) § Mobile + Admin Status. Backend exposes the data correctly; UI follows.
- **Admin Panel review queue UI for Layer-3-flagged rows.** Phase 3.5 admin work (Weeks 11–13 per [`docs/08-Roadmap-Risk.md`](../../../docs/08-Roadmap-Risk.md)) owns admin UI. This change writes correctly-shaped queue rows; admin UI surfaces them via the existing `(target_type, target_id)` group + `trigger` filter.
- **Anomaly metrics emission.** Phase 1 §29 (anomaly detection metrics) is a separate change. This change emits structured logs + Sentry events on a per-dispatch basis; aggregation into anomaly-detection metrics is downstream work.
- **Privacy Policy / RoPA update.** User content sent to a third-party (OpenAI, hosted in the US) is a UU PDP disclosure surface — Pre-Launch task in [`docs/06-Security-Privacy.md`](../../../docs/06-Security-Privacy.md). Out of scope here as a code task.
- **Score threshold A/B tuning.** Defaults follow [`docs/06-Security-Privacy.md:163-164`](../../../docs/06-Security-Privacy.md) (>0.8 / >0.6 / ≤0.6). Tuning happens via Remote Config overrides post-launch (see Open Question 2).
- **OpenAI account-level rate-limit increase request.** The Moderation endpoint is free with no per-call charge; only constraint is the account-level requests-per-minute cap. Operational task — not a code change. Sentry WARN on HTTP 429 surfaces account-cap exhaustion.
- **Dual-language support (Indonesian-tuned classifier).** OpenAI's `omni-moderation-latest` model explicitly supports Indonesian as a top-tier benchmarked language (per OpenAI's launch announcement) — this is a step up from the prior plan's "partial ID support" caveat. Switching to a self-hosted XLM-R model is a Month 6+ scope per [`docs/06-Security-Privacy.md:167`](../../../docs/06-Security-Privacy.md), explicitly out of scope.
- **`moderation_queue.score` column.** The current V9 schema has no per-row score column. Admin disambiguation between AutoHide and FlagOnly outcomes is via the related row's `is_auto_hidden` state (TRUE for the high-score path, FALSE for the mid-score path), NOT via a queue-row column. Adding a `score NUMERIC(3,2)` column would require a Flyway migration and is deferred to a follow-up if admin review tooling needs explicit score capture.

## Decisions

### Decision 1: Vendor encapsulation — new `:infra:openai-moderation` module + API key passed in at construction

Create `infra/openai-moderation/` Gradle module owning all OpenAI Moderation REST API call shapes. `:backend:ktor` depends on `:infra:openai-moderation` and imports only the `ModerationClient` interface + `ModerationScore` data class (plain Kotlin types; `ModerationScore` is vendor-neutral, holding `Map<String, Double>` per-category scores).

The OpenAI API key SHALL be resolved in `:backend:ktor`'s `Application.module()` via the existing `secrets.resolve("openai-api-key")` call shape (mirroring [`Application.kt`](../../../backend/ktor/src/main/kotlin/id/nearyou/app/Application.kt) `secrets.resolve("firebase-admin-sa")` for the Firebase admin SA), and passed into the `OpenAiModerationClient` constructor as a `String` parameter at boot. The infra module SHALL NOT depend on `:backend:ktor`'s `SecretResolver` interface (which lives in `id.nearyou.app.config`).

The diagnostic `secretKey(env, "openai-api-key")` call lives in `:backend:ktor`'s init log line, mirroring the Remote Config init pattern.

**Why:** Mirrors the project's vendor-isolation pattern — `:infra:fcm`, `:infra:oidc`, `:infra:redis`, `:infra:remote-config`, `:infra:supabase`, `:infra:otel` all follow the same `interface in :backend:ktor → SDK in :infra:*` shape, AND all of them resolve their secret in `:backend:ktor` (NOT inside the infra module). Direct OpenAI SDK imports in `:backend:ktor` would violate the convention; conversely, an infra module that owns the `SecretResolver` dependency would invert the dependency direction (infra → backend).

The interface name `ModerationClient` (not `OpenAiClient`) is deliberately vendor-neutral so the `:backend:ktor` consumer doesn't need refactoring on a future vendor swap. The implementation class (`OpenAiModerationClient`) is vendor-specific; the public interface is not.

**Alternatives considered:**
- (a) Inline HTTP call in `:backend:ktor` using the Ktor client. **Rejected**: violates the no-vendor-SDK-outside-:infra:* invariant; couples OpenAI's request/response shape to backend code.
- (b) Add to existing `:infra:remote-config` module. **Rejected**: OpenAI moderation is not Firebase-related; conflating concerns harms module boundaries.
- (c) `:infra:openai-moderation` resolves its own secret via a `SecretResolver` parameter. **Rejected**: inverts the established convention (`:infra:fcm` and `:infra:remote-config` both have their secret resolved in `:backend:ktor` and passed in); would also force `SecretResolver` to be moved out of `:backend:ktor` to be reachable from infra.
- (d) Vendor-specific interface name (`OpenAiModerationClient` as the public interface). **Rejected**: bakes the vendor identity into every consumer; a future vendor swap would churn `:backend:ktor` imports + Koin bindings + test fixtures. The vendor-neutral `ModerationClient` shape protects consumers from this churn (precedent: this change itself pivoted from Perspective to OpenAI mid-implementation; the Kotlin interface shape was the only mid-flight constant).

### Decision 2: HTTP client — Ktor client (CIO engine) with explicit timeout config

Use the Ktor HTTP client (CIO engine, already on the classpath via `:backend:ktor`) inside `:infra:openai-moderation` to POST to `https://api.openai.com/v1/moderations`. The request body is `{"model": "omni-moderation-latest", "input": "<content>"}`; the API key is sent via the `Authorization: Bearer <api-key>` header (per OpenAI's documented auth pattern). The response body is a JSON object with `results[0].category_scores`, a flat object keyed by OpenAI's 13 category names (e.g., `harassment`, `harassment/threatening`, `hate`, `hate/threatening`, `illicit`, `illicit/violent`, `self-harm`, `self-harm/intent`, `self-harm/instructions`, `sexual`, `sexual/minors`, `violence`, `violence/graphic`). The slash-separated subcategory keys are preserved verbatim in `ModerationScore.categoryScores`.

The HTTP client SHALL configure CIO `requestTimeoutMillis = 3000` AND `connectTimeoutMillis = 1000` AND `socketTimeoutMillis = 3000` to defend against socket-read pinning beyond the orchestrator-level `withTimeoutOrNull(3000.ms)` budget. The 3000ms baseline (bumped twice from the original 500ms in design v1: first to 1500ms after measuring fast-path TTFB ~600-900ms, then to 3000ms after observing a bimodal slow-path tail at 1500-1550ms) is calibrated to asia-southeast1 → US OpenAI TTFB (empirical bimodal distribution measured 2026-05-11 from raw `curl` in a one-shot Cloud Run Job in the production region: ~40% fast-path 550-700ms, ~40% slow-path 1500-1550ms, ~20% gateway-timeout outliers 15s+). 3000ms covers the slow tail with ~1500ms margin and still bails fast on 504 outliers. Without engine-level timeouts, a slow socket read can keep the underlying connection in the pool past the coroutine timeout — under sustained load this exhausts the pool. The engine timeouts ensure the socket is closed when the coroutine cancels.

**Why:** The OpenAI Moderation API is a single REST endpoint; pulling in a vendor-specific SDK is unnecessary weight for one call. Ktor client is already on the classpath, supports `kotlinx.serialization` JSON parsing, and integrates cleanly with coroutines. The explicit engine timeouts are defense-in-depth — coroutine cancellation propagates to Ktor's suspending calls, but the underlying socket may hold past the cancellation point unless engine-level timeouts force a close.

**Alternatives considered:**
- (a) `com.openai:openai-java` (vendor's official Java SDK). **Rejected**: heavier dependency footprint for a single endpoint; the Ktor client + serialization stack already supports the request/response shape directly.
- (b) `java.net.http.HttpClient` (JDK 11+ stdlib). **Rejected**: works, but manual JSON wiring is friction we don't need given Ktor client + serialization are already in.
- (c) Skip engine-level timeouts and rely on `withTimeoutOrNull` alone. **Rejected**: socket-pin risk under load.

### Decision 3: Threshold aggregation — `max(...)` across all vendor-returned per-category scores

Compute a single aggregate score as the per-call max across all categories the vendor returns:

```
score = moderationScore.maxScore()  // == moderationScore.categoryScores.values.max()
```

Apply the canonical thresholds:
- `score > 0.8` → `Outcome.AutoHide` (write queue row + flip `is_auto_hidden = TRUE`)
- `score > 0.6` AND `score ≤ 0.8` → `Outcome.FlagOnly` (write queue row, no flip)
- `score ≤ 0.6` → `Outcome.NoAction`

**Why:** [`docs/06-Security-Privacy.md:163-164`](../../../docs/06-Security-Privacy.md) reads "Score >0.8 = auto-hide ... Score 0.6-0.8 = flag to moderation_queue only" — singular "score". The doc doesn't specify aggregation across the vendor's category set. `max(...)` is the conservative interpretation: any single high-toxicity category triggers; this matches the natural reading of "the score" against a per-category vector. Per-category weights would be product tuning; `max` is the deterministic baseline.

The `max(...)` approach is vendor-set-agnostic — it works whether the vendor returns 4 categories or 13. OpenAI's `omni-moderation-latest` returns 13; the orchestrator does not enumerate them. A future vendor swap that returns a different set of categories continues to work without changes in the orchestrator.

**Alternatives considered:**
- (a) Per-category thresholds with separate cutoffs (e.g., `hate/threatening > 0.5` triggers but generic categories require `> 0.8`). **Rejected for v1**: more correct in theory but harder to reason about and tune. Indonesian SARA context might justify weighting `hate` or `hate/threatening` more aggressively, but ship max-aggregation first; tune via per-category thresholds in a follow-up if Month 3+ data shows systematic miss patterns. See Open Question 3.
- (b) Weighted average across categories. **Rejected**: weights are arbitrary without data; max-aggregation has no tuning surface to get wrong.
- (c) Hard-coded category allowlist (only consider `harassment`, `hate`, `violence`, `sexual`; ignore others). **Rejected**: ignoring `self-harm` or `illicit/violent` categories would create policy gaps; the vendor returns all 13 for a reason.

### Decision 4: Async dispatch — `Layer3DispatcherScope` (single class owning lifecycle + dispatch-after-shutdown WARN)

A long-lived `CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("layer3-dispatch"))` constructed at application startup. Call sites at the post / reply INSERT path invoke `scope.dispatch(coroutineContext) { layer3Moderator.moderate(targetType, targetId, content) }` — fire-and-forget, no `await` at the request handler. The supervisor job ensures one failed dispatch doesn't cancel sibling dispatches. JVM shutdown drains in-flight dispatches up to a 5-second budget via `cancelAndJoin` inside `withTimeoutOrNull(5_000)`. Late `dispatch` calls after shutdown emit a structured `event=layer3_dispatch_after_shutdown` WARN log + silently no-op.

**Why:** Same operational shape as `:infra:fcm`'s dispatch infrastructure (long-lived scope, supervised, structured logging on failures, bounded shutdown drain), but consolidated into one class. The `:infra:fcm` shape splits `FcmDispatcherScope` (lifecycle/shutdown — [`infra/fcm/.../FcmDispatcherScope.kt:25`](../../../infra/fcm/src/main/kotlin/id/nearyou/app/infra/fcm/FcmDispatcherScope.kt)) from `FcmDispatcher` (per-dispatch logic + dispatch-after-shutdown WARN — [`infra/fcm/.../FcmDispatcher.kt:42`](../../../infra/fcm/src/main/kotlin/id/nearyou/app/infra/fcm/FcmDispatcher.kt)) because FCM has substantial per-dispatch logic (payload builders, parallelism limit `Dispatchers.IO.limitedParallelism(8)`, multi-token fan-out). Layer 3 dispatch is simpler — a single timed HTTP call per invocation — so collapsing both responsibilities into `Layer3DispatcherScope` keeps the surface lean. **Deliberate divergence from the `:infra:fcm` pattern, called out here for reviewer awareness.**

The class name `Layer3DispatcherScope` is vendor-neutral (architectural-layer name); the previous proposal used `PerspectiveDispatcherScope` (vendor-named) — the rename to `Layer3*` happened mid-implementation when the vendor swap landed. Future vendor swaps don't churn this name.

**Alternatives considered:**
- (a) `kotlinx.coroutines.GlobalScope`. **Rejected**: no cancel-on-shutdown story; orphaned coroutines on JVM exit.
- (b) Per-request scope tied to the Ktor `call`. **Rejected**: the request returns 201 BEFORE the Layer 3 call completes; tying the dispatch to the request scope would cancel the dispatch when the response sends.
- (c) `Application.module()`-bound scope. **Rejected**: the Application scope cancels at shutdown without a drain budget; the supervised pattern is the project convention.
- (d) Split into `Layer3DispatcherScope` + `Layer3Dispatcher` mirroring the FCM split exactly. **Rejected**: artificial split for symmetry; the dispatch logic is too thin to justify a second class. Future-proof: if Layer 3 dispatch grows complexity (batching, retries, parallelism limits), split at that point.

### Decision 5: Fail-open mechanics — every failure mode → `Outcome.NoAction`

The orchestrator returns `Outcome.NoAction` on:
- `withTimeoutOrNull` returns null (3000ms regional-baseline budget exceeded)
- OpenAI Moderation API returns HTTP 4xx (auth failure, malformed request, rate limit) — log Sentry WARN
- OpenAI Moderation API returns HTTP 5xx (vendor outage) — log Sentry WARN
- Network exception thrown (DNS fail, TLS handshake fail, connection refused) — log Sentry WARN
- Response-body parse error — log Sentry WARN
- Kill-switch read returns FALSE — silent skip, no Sentry event
- Kill-switch read throws (Remote Config + all fallback tiers unavailable) — fail-OPEN to enabled=true; log Sentry **ERROR** (not WARN) per Decision 12 — operators want to be paged on this surface

The orchestrator SHALL NOT propagate any non-cancellation exception out of `moderate(...)`. `CancellationException` SHALL propagate per coroutine convention so structured cancellation works correctly through the dispatcher scope.

**Why:** [`docs/06-Security-Privacy.md:179`](../../../docs/06-Security-Privacy.md) reads "3000ms timeout regional baseline for asia-southeast1, fail-open" (originally "500ms" in the pre-pivot doc; updated 2026-05-11 to match the empirical TTFB measurement from the production region). The user's content is already INSERTed; failing the dispatch silently preserves availability. The Layer 1+2 sync gates already ran successfully; Layer 3 is defense-in-depth. A flapping vendor endpoint MUST NOT degrade post-creation latency.

**Sentry deduplication:** Each dispatch failure emits a Sentry breadcrumb-level event keyed on `(failure_kind, layer3_endpoint_host)` so Sentry's built-in dedup suppresses event floods during sustained outages. Mirrors the `ModerationListLoader`'s in-call rate-limit pattern from [`content-moderation-keyword-lists/spec.md`](../../specs/content-moderation-keyword-lists/spec.md) § "Tier-fallback Sentry events emit at most once per `load(list)` call".

**Alternatives considered:**
- (a) Fail-CLOSED on vendor unavailability (block the post until vendor recovers). **Rejected**: defeats the whole point of an async post-INSERT layer; degrades user experience for a defense-in-depth surface.
- (b) Retry with exponential backoff on 5xx. **Rejected for v1**: complicates the dispatch surface; if 5xx persists it's a sustained outage and retries don't help.

### Decision 6: Kill-switch + threshold reads cached in `:backend:ktor` orchestrator (NOT in `:infra:remote-config`)

The kill-switch (`perspective_api_enabled`) and threshold tunables (`perspective_api_high_score_threshold`, `perspective_api_flag_threshold`) are cached in the `Layer3ConfigLoader` (a sibling of `Layer3Moderator` in `:backend:ktor` package `id.nearyou.app.moderation`), NOT in `:infra:remote-config`. The infra module is stateless — it owns the Firebase SDK + the `RemoteConfigClient` interface + parse-from-Server-template logic, but no caching layer.

The cache layer mirrors the [`content-moderation-keyword-lists`](../../specs/content-moderation-keyword-lists/spec.md) `ModerationListLoader` pattern: Redis 5-min TTL via Lettuce `SETEX`, with key scope `{scope:layer3_config}:{flag:<flag-name>}` (cluster-safe via the hash-tag braces, mirroring `RedisHashTagRule`). The scope token `layer3_config` is vendor-neutral; the `<flag-name>` retains the operator-facing `perspective_api_*` prefix per the vendor-swap historical-artifact carve-out (the flags were seeded in Firebase Console at Pre-Phase 1 §18; renaming requires a parallel seed migration). The 3-tier fallback ladder (Redis cache → Remote Config → static default; kill-switch fail-OPENs on Tier-2 failure) is simpler than `ModerationListLoader`'s 4-tier (no repo-file or Secret Manager fallback for these flags — defaults are hardcoded in the loader: `perspective_api_enabled = TRUE`, `high_score_threshold = 0.8`, `flag_threshold = 0.6`).

**Why:** `:infra:remote-config` today (per [`infra/remote-config/.../RemoteConfigClient.kt`](../../../infra/remote-config/src/main/kotlin/id/nearyou/app/infra/remoteconfig/RemoteConfigClient.kt)) has NO Redis dependency; its only caching is whatever the Firebase Admin SDK itself provides internally. The cache layer the canonical doc references for "operators can disable Layer 3 within 5 min" lives in the consumer (`ModerationListLoader` for Layer 1+2; `Layer3ConfigLoader` for Layer 3). Putting the cache inside `:infra:remote-config` would force a Redis dependency into the infra module — un-warranted scope creep.

The 5-min TTL bound matches the operational kill-switch contract: operators can disable Layer 3 via Firebase Console; effect propagates within 5 min.

**Alternatives considered:**
- (a) Add a Redis cache layer to `:infra:remote-config`. **Rejected**: forces Redis dependency into the infra module; deviates from the existing `ModerationListLoader` pattern where the consumer owns the cache.
- (b) JVM-internal cache (no Redis) with 1-min TTL. **Rejected**: doesn't survive Cloud Run revision rollover; a fresh instance pays the cold Remote Config fetch cost on first dispatch. The Redis cache is shared across instances.
- (c) Read on every dispatch with no cache. **Rejected**: Remote Config fetch is ~30ms per call; at 100 dispatches/sec this is significant background load against the Remote Config quota. Cache TTL aligns with the kill-switch propagation contract.

### Decision 6b: Threshold reads — extend `RemoteConfigClient` with a `fetchDouble` method

The existing `RemoteConfigClient` interface ([`infra/remote-config/.../RemoteConfigClient.kt:34`](../../../infra/remote-config/src/main/kotlin/id/nearyou/app/infra/remoteconfig/RemoteConfigClient.kt)) exposes `fetchStringList`, `fetchInt`, `fetchBoolean`, `fetchRawString` — but NOT `fetchDouble`. The threshold tunables (`perspective_api_high_score_threshold`, `perspective_api_flag_threshold`) need a Double pathway.

**Decision:** Add a new `fetchDouble(parameterName: String): Double?` method to `RemoteConfigClient` in `:infra:remote-config`. Mirrors the existing `fetchInt` parse-and-clamp pattern. This is a forward-compatible interface extension (no caller change needed for existing methods); the implementation reuses the existing `fetchRawString` plumbing and parses via `String.toDoubleOrNull()`.

**Alternatives considered:**
- (a) Use `fetchRawString` + parse Double inline in `Layer3ConfigLoader` (no interface change). **Rejected**: scatters the parse + null-on-malformed logic into the consumer; the `:infra:remote-config` module owns parse-from-Remote-Config patterns by precedent (`fetchInt` parses `Int`).
- (b) Add a generic `fetchTyped<T>` method. **Rejected**: over-abstraction for one new caller; `fetchDouble` matches the existing per-type method shape.

### Decision 7: No-user-content on Sentry breadcrumbs

Sentry events emitted by `Layer3Moderator` and `Layer3ConfigLoader` SHALL include:
- `event` (string, one of: `"layer3_dispatch_failed"`, `"layer3_high_score_applied"`, `"layer3_flag_applied"`, `"layer3_kill_switch_unavailable"`, `"layer3_threshold_fallback"`, `"layer3_threshold_misconfigured"`)
- `target_type` (`"post"` | `"reply"`) — opaque category, no user content
- `target_id` (UUID string — the post/reply identifier; non-PII per the `chat-realtime-broadcast` precedent where `conversation_id` is sanctioned in span attributes — see [`observability-otel-foundation/spec.md`](../../../specs/observability-otel-foundation/spec.md))
- `score_bucket` (`"low"` | `"mid"` | `"high"`) on success-path events; bucket boundaries match Decision 3 thresholds — `low` if `score ≤ 0.6`, `mid` if `0.6 < score ≤ 0.8`, `high` if `score > 0.8`
- `failure_kind` (`"timeout"` | `"http_4xx"` | `"http_5xx"` | `"network"` | `"parse"`) on failure events
- `status_code` (integer) when `failure_kind` is `"http_4xx"` or `"http_5xx"`

The events SHALL NOT include:
- Raw `content` string (user-supplied text)
- Raw `score` floats per category (would leak the vendor's classification of specific content for bypass-pattern reverse engineering)
- Matched-keyword analogues (OpenAI Moderation doesn't return them, but to be explicit)
- Owner-correlatable user identifier (`user.id`) — the orchestrator does not consume the user identity

**Why:** Mirrors the `content-moderation-keyword-lists` Sentry contract (`### Requirement: TextModerator Sentry events do NOT carry user content`). Coarse `score_bucket` is operationally sufficient for "is Layer 3 firing as expected" dashboarding. `target_id` (UUID without owner correlation) is low-leak surface — operator can't identify the user from the post UUID alone without an authorized DB query (which is itself audited at the DB role layer).

**Event name preservation:** the event prefix is `layer3_*` (vendor-neutral) rather than `perspective_*` (the original proposal's vendor-named events). The rename happened mid-implementation alongside the vendor swap; future vendor swaps don't churn dashboard / Sentry alert rules.

### Decision 8: AutoHide flip + queue insert in one transaction (with soft-delete guard)

The high-score path (`Outcome.AutoHide`) executes — inside ONE SQL transaction with a single COMMIT:

1. `UPDATE posts SET is_auto_hidden = TRUE WHERE id = ? AND deleted_at IS NULL` (or `UPDATE post_replies SET is_auto_hidden = TRUE WHERE id = ? AND deleted_at IS NULL`)
2. `INSERT INTO moderation_queue (target_type, target_id, trigger, ...) VALUES (..., 'perspective_api_high_score', ...) ON CONFLICT (target_type, target_id, trigger) DO NOTHING`

Both statements run in the same transaction; if either fails, the transaction is rolled back and neither applies. Idempotent retries are safe via the ON CONFLICT clause (the second invocation's UPDATE is a no-op since the flag is already TRUE; its INSERT is conflict-suppressed by the existing UNIQUE constraint). If the row is soft-deleted between the user's INSERT and the async dispatch's UPDATE, the UPDATE matches zero rows AND the INSERT still writes a queue entry — to avoid orphan queue rows for deleted content, the orchestrator SHALL skip the INSERT when the UPDATE returns zero affected rows (race-to-no-op on soft-deleted target).

**Trigger string preservation:** the trigger value `'perspective_api_high_score'` retains the historical `perspective_api_*` prefix per the vendor-swap carve-out — the V9 CHECK constraint enum was fixed before the vendor swap; renaming requires a Flyway migration. The Kotlin code uses this string as a verbatim constant; the underlying classifier is now OpenAI Moderation.

**Why:** A partial failure (UPDATE applied but INSERT failed) leaves the row hidden with no admin-review trail. A partial failure the other way (queue row written but UPDATE didn't apply) leaves the row visible with an admin alert pending. Single transaction prevents both. The `AND deleted_at IS NULL` predicate matches the canonical V9 writer pattern documented at [`backend/ktor/src/main/resources/db/migration/V9__reports_moderation.sql:36`](../../../backend/ktor/src/main/resources/db/migration/V9__reports_moderation.sql) for the auto-hide-3-reports trigger writer; without it, the async Layer 3 dispatch would flip a tombstoned row's `is_auto_hidden` flag, surfacing to admin review as a phantom queue entry against deleted content.

The race-to-no-op condition (UPDATE returns zero) is detectable by checking the JDBC `Statement.executeUpdate(...)` return value; the orchestrator skips the INSERT and emits a structured INFO log noting the soft-deleted-target race.

### Decision 9 — REMOVED

This decision (formerly "Boot-time prime — NOT needed") was a non-decision. Folded into Goals/Non-Goals (see "Boot-time prime" entry in Non-Goals).

### Decision 10: Two queue rows when Layer 2 + Layer 3 both fire — disambiguation via related row state

A row that Layer 2 flagged (`uu_ite_keyword_match` trigger) AND Layer 3 high-scored (`perspective_api_high_score` trigger) gets TWO `moderation_queue` rows — one per trigger. The admin UI surfaces them grouped by `(target_type, target_id)` per [`docs/05-Implementation.md:545`](../../../docs/05-Implementation.md). This is the canonical multi-trigger behavior and is preserved here.

Within the Layer 3 trigger family, the AutoHide outcome and the FlagOnly outcome both produce queue rows with the same `trigger = 'perspective_api_high_score'` string (the V9 enum has only this single Layer-3-derived value). Admin disambiguation between the two outcomes is via the **related row's `is_auto_hidden` state**: a queue row whose target's `is_auto_hidden = TRUE` was the AutoHide path; a queue row whose target's `is_auto_hidden = FALSE` was the FlagOnly path. The admin UI's existing `(target_type, target_id)` grouping already JOINs against the source row, so this disambiguation surfaces naturally.

**Why:** The existing UNIQUE constraint is `(target_type, target_id, trigger)` — different triggers do not collide. Two rows per target with different triggers is the correct shape; consolidating them in the admin UI is a presentation concern, not a write-time one. Adding a separate trigger value `perspective_api_mid_score` for FlagOnly would require a Flyway migration to extend the V9 CHECK constraint enum — out of scope for a "schema-free" change. The `is_auto_hidden`-based disambiguation is sufficient for MVP admin review.

### Decision 11: Cross-flag misconfiguration fallback

If a Remote Config push results in `flag_threshold > high_score_threshold` (a cross-flag misconfiguration: e.g., operator sets `flag = 0.9, high_score = 0.7` which inverts the band logic), the orchestrator SHALL detect this at threshold-load time AND fall back to BOTH defaults (`high_score = 0.8`, `flag = 0.6`) for the affected dispatch. A Sentry **ERROR** event with `event = "layer3_threshold_misconfigured"` is emitted — operators want to be paged on this surface because it's an operational error, not an outage.

**Why:** Without this guard, an operator typo would silently invert the band logic — every score >0.7 would AutoHide, none would Flag. Defense-in-depth for a tunable surface. Sentry ERROR (not WARN) because this is operator-actionable misconfiguration, not background noise.

### Decision 12: Kill-switch read failure → Sentry ERROR (not WARN)

When the kill-switch read throws (Remote Config + all fallback tiers unavailable), the orchestrator fails OPEN to `enabled=true` AND emits a Sentry **ERROR** (not WARN). Failing OPEN means an operator-disabled state is silently re-enabled during Remote Config outages; this is operator-actionable and warrants paging.

**Why:** The fail-open posture is correct for the dispatch outcome (defense-in-depth — keep Layer 3 protection on if we can't read the kill-switch). But the operator who disabled Layer 3 (perhaps because the vendor had an incident OR the API key leaked) needs to know the disable is being silently bypassed. Sentry ERROR vs WARN is the difference between "ops dashboard widget" and "PagerDuty alert". The canonical doc [`docs/06-Security-Privacy.md:179`](../../../docs/06-Security-Privacy.md) says "fail-open" referring to the timeout/error path's outcome (NoAction); it doesn't address the kill-switch *read* path specifically.

**Alternatives considered:**
- (a) Make the kill-switch fail-mode itself a Remote Config flag (`perspective_api_kill_switch_fail_mode = "open" | "closed"`, default open). **Rejected for v1**: adds a tunable that can itself be misconfigured; ERROR-on-fail-open Sentry alert handles the operator-visibility need without a new flag.
- (b) Sentry WARN (current default). **Rejected**: doesn't page the operator.

### Decision 13: OTel trace context propagation through dispatch coroutine

The dispatch coroutine SHALL inherit the OTel trace context from the originating request scope so that the Layer 3 dispatch span is parented under the originating request span (the user's `POST /api/v1/posts` or `POST /api/v1/posts/{post_id}/replies` span).

Implementation: the call site invokes `layer3DispatcherScope.dispatch(coroutineContext) { ... }` passing the request's `coroutineContext` as a parent. The `MDCContext` + OTel context propagation already wired by `:infra:otel`'s instrumentation handles the rest — `traceparent` flows through to the OpenAI Moderation HTTP outbound call, and the resulting span tree shows: `POST /api/v1/posts` → `INSERT posts` → `layer3.dispatch (async)` → `POST api.openai.com/v1/moderations`.

**Why:** Per [`observability-otel-foundation/spec.md`](../../../specs/observability-otel-foundation/spec.md) the W3C `traceparent` propagation contract covers cross-service correlation. A fire-and-forget dispatch that DROPS the trace context creates an orphan span tree that's hard to correlate to the originating request — operators investigating a slow/error dispatch want to see "what was the user doing when this fired". Inheriting the context preserves the trace tree without affecting the request's response time.

**Alternatives considered:**
- (a) Use `scope.launch { ... }` without context propagation. **Rejected**: orphan span tree.
- (b) Manually capture `traceparent` at the call site and reinject in the dispatch. **Rejected**: reinvents what coroutine context inheritance + OTel auto-instrumentation already do.

## Risks / Trade-offs

- **OpenAI account-level rate limit on the Moderation endpoint** → Mitigation: the Moderation endpoint is free with no per-call charge but has an account-level requests-per-minute cap. Sentry WARN on HTTP 429 surfaces account-cap exhaustion. If we hit it under sustained load, the operational response is to file a rate-limit increase request with OpenAI (account-level, not code-side).
- **Vendor outage degrades moderation silently** → Mitigation: fail-open + Sentry WARN per failure (deduplicated). Layer 1+2 still protect the highest-priority cases (profanity + UU ITE).
- **Score threshold sensitivity for Indonesian content** → Mitigation: defaults from canonical doc (>0.8 / >0.6); Remote Config-tunable overrides (`perspective_api_high_score_threshold` / `perspective_api_flag_threshold`) ship in this change so ops can adjust without redeploy. Open Question 2 surfaces the tuning playbook; Open Question 3 surfaces per-category SARA weighting. OpenAI publicly benchmarks Indonesian as top-tier supported in `omni-moderation-latest` — improvement over the prior plan's "partial ID support" caveat.
- **User content sent to a third-party (OpenAI, hosted in the US) — UU PDP disclosure** → Mitigation: Privacy Policy / RoPA update is a Pre-Launch task; flag in `docs/06-Security-Privacy.md` Privacy section. NOT a code change here, but the proposal calls it out as a downstream prerequisite. UU PDP Pasal 56 (cross-border-transfer disclosure) cross-border-transfer mechanism (SCC / adequacy / consent) requires legal-counsel input before launch.
- **Cost** → OpenAI Moderation endpoint is free; only constraint is the account-level rate limit + one-time $5 prepaid minimum deposit (idle if only Moderation is used). MVP traffic well within free tier.
- **Latency budget regression risk** → Mitigation: dispatch is fire-and-forget; the 3000ms `withTimeoutOrNull` lives entirely off the request path. The user request returns immediately after the synchronous Layer 1+2 + INSERT. The 3000ms budget (regional baseline for asia-southeast1) is constructor-tunable via `analyzeTimeoutMillis` on `DefaultLayer3Moderator`; deployments in other regions should re-measure baseline TTFB and tune accordingly. Production traffic may show a different (less-bimodal) distribution as connection-pool warmth and OpenAI account scheduling tier stabilize — tightening the budget once that data exists is a post-launch tuning task.
- **Cross-flag misconfiguration silently inverts band logic** → Mitigation: Decision 11 falls back to defaults + Sentry ERROR.
- **Kill-switch fail-OPEN undoes operator's disable during Remote Config outage** → Mitigation: Decision 12 emits Sentry ERROR (pages operator).
- **Orphan queue rows on soft-deleted target** → Mitigation: Decision 8 adds the `AND deleted_at IS NULL` UPDATE guard + race-to-no-op handling.
- **Socket pinning past coroutine cancellation** → Mitigation: Decision 2 sets explicit Ktor CIO `requestTimeoutMillis = 3000` + `socketTimeoutMillis = 3000` (matching the orchestrator budget).
- **Future vendor swap churn** → Mitigation: the `ModerationClient` interface + `ModerationScore` data class are vendor-neutral (the implementation class `OpenAiModerationClient` and the Ktor wire shape are the only OpenAI-specific surfaces). A future swap (e.g., to Azure AI Content Safety) only changes the implementation class + `:infra:openai-moderation` content; `:backend:ktor` consumers, the orchestrator, the dispatcher scope, the writer, and all tests stay constant. This was validated empirically by the Perspective → OpenAI mid-flight swap that produced this change: the interface stayed; the implementation rewrote.

## Migration Plan

No data migration. No schema migration. New module + new code only.

**Deploy sequence:**
1. Land code + tests via the standard one-PR-per-change lifecycle.
2. Provision the `staging-openai-api-key` secret slot in GCP Secret Manager (manual operator step) BEFORE staging smoke. Account-level prerequisites: OpenAI account with payment method on file + $5 minimum prepaid deposit (one-time; idle if only Moderation is used).
3. Verify `perspective_api_enabled = TRUE` in staging Firebase Remote Config (already seeded per Pre-Phase 1 §18; idempotent verification only; flag name retained as historical-artifact carve-out).
4. Pre-archive smoke: `gh workflow run deploy-staging.yml --ref text-moderation-perspective-api-layer` deploys the branch to staging (the workflow's `workflow_dispatch` accepts arbitrary refs per [`.github/workflows/deploy-staging.yml:9`](../../../.github/workflows/deploy-staging.yml)). Smoke against the branch deploy: post a test message that should trigger high-score → assert `is_auto_hidden = TRUE` + queue row written.
5. Squash-merge → auto-deploys to `main`-staging.
6. Production: provision `openai-api-key` slot + verify `perspective_api_enabled = TRUE` BEFORE the prod tag-deploy.

**Rollback:** Flip `perspective_api_enabled = FALSE` in Firebase Remote Config; Layer 3 dispatch silent-skips within 5 minutes (Redis cache TTL bound). Code rollback is a separate revert PR if a behavior bug surfaces.

## Open Questions

### Open Question 1: Should chat-message Layer 3 ship in this change too?

Chat send (`POST /api/v1/chat/{conversation_id}/messages`) currently runs Layer 1+2 synchronously per `chat-foundation` capability. Whether to extend Layer 3 to chat is a product decision:

- **Pro**: chat is a 2000-char compose surface — the classifier scoring window is wider; toxic chat is a harassment vector.
- **Con**: chat is private (1:1) — moderation ROI per dispatch is lower; chat redaction is an admin tool (Phase 3.5) not a user-visible surface.
- **Recommendation**: **defer chat Layer 3 to a follow-up.** Ship posts + replies in this change. Open a follow-up entry `text-moderation-layer3-chat-extension` triggered if Phase 3 chat-monitoring data shows toxicity exceeding the keyword-list catch rate.

### Open Question 2: Threshold-tuning playbook — when do we tune?

Out-of-scope for this change but worth noting: Pre-Launch security review (`docs/08-Roadmap-Risk.md` Pre-Launch §5) doesn't list a threshold-tuning gate. Recommend adding one in a future docs PR: "Re-tune Layer 3 thresholds at Month 3 + Month 6 based on `(false_positive_rate, false_negative_rate)` from admin queue review data."

### Open Question 3: Per-category SARA weighting

Decision 3 picks `max(...)` aggregation as the v1 baseline. The Indonesian SARA context (suku/agama/ras/antargolongan harassment) might justify weighting OpenAI's `hate` and `hate/threatening` categories more aggressively (e.g., `hate/threatening > 0.6` triggers high-score path, vs the generic 0.8). **Defer** — observe Month 1–3 admin queue review patterns and decide whether to ship per-category thresholds in a follow-up. If the answer is yes, the follow-up's shape is straightforward: extend the threshold loader to produce per-category thresholds (a `Map<String, Pair<Double, Double>>` of `category → (flag_threshold, high_score_threshold)` with the existing two flags as the wildcard default); the orchestrator swaps `maxScore()` for a per-category comparison loop. The `ModerationClient` interface and the `Map<String, Double>` score shape do NOT need to change.
