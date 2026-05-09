## Context

The text-moderation pipeline today (per [`openspec/specs/content-moderation-keyword-lists/spec.md`](../../specs/content-moderation-keyword-lists/spec.md)) runs Layers 1+2 SYNCHRONOUSLY, before INSERT, via the `TextModerator.moderate(content): Verdict` orchestrator. Verdicts are `Allow`, `Reject` (Layer 1 profanity → HTTP 4xx pre-INSERT), or `Flag` (Layer 2 UU ITE → soft-flag, INSERT proceeds, queue row written in the same SQL transaction).

[`docs/06-Security-Privacy.md:175-180`](../../../docs/06-Security-Privacy.md) prescribes a **third layer** that runs differently from Layers 1+2:

> Layer 3: Perspective API (async post-INSERT, 500ms timeout, fail-open) — score >0.8 → set is_auto_hidden = TRUE + insert moderation_queue row (visible to author, hidden from timeline until reviewed)

Three load-bearing distinctions from Layers 1+2:
- **Asynchronous, post-INSERT.** The user's request returns 201/200 BEFORE the Perspective call completes. The classifier runs on a fire-and-forget coroutine.
- **Network-bound + fail-open.** A 500ms `withTimeoutOrNull` budget means the call may not return at all. Any timeout, network error, 5xx response, or kill-switch-OFF → no action (the row stays as-is).
- **Outcome is mutation, not gating.** Layer 3 cannot reject the request (it's already INSERTed). High-score (>0.8) flips `is_auto_hidden = TRUE` post-hoc. Mid-score (0.6–0.8) writes a queue row only.

This change adds Layer 3 as a NEW capability rather than extending the existing `TextModerator` interface — the synchronous and asynchronous surfaces have different lifetimes, different transaction boundaries, and different failure semantics. Conflating them in one Verdict-shaped abstraction would force every caller to reason about both lifecycles.

## Goals / Non-Goals

**Goals:**
- Ship Layer 3 of the canonical multi-layer text-moderation pipeline per [`docs/06-Security-Privacy.md:153-184`](../../../docs/06-Security-Privacy.md).
- Encapsulate the Google Perspective API client behind a `:infra:perspective` module, preserving the project's vendor-isolation invariant (`:backend:ktor` MUST NOT directly import third-party API SDKs per [`openspec/project.md`](../../project.md) § Module Structure).
- Honor the `perspective_api_enabled` Firebase Remote Config kill-switch (already seeded since Pre-Phase 1 §18) so operators can disable Layer 3 instantly without a backend redeploy.
- Maintain fail-open posture: a Perspective API outage degrades moderation to Layers 1+2 only — silently from the user's perspective, with structured Sentry WARN events for operator visibility.
- Preserve no-user-content-on-Sentry: matched content + raw scores never leave the request lifetime.
- Wire dispatch at the existing post + reply INSERT call sites; reuse the existing `moderation_queue` schema (no new Flyway migration).

**Non-Goals:**
- **Chat-message Layer 3.** Chat send currently runs Layers 1+2; whether to add Layer 3 to chat is a product decision (see Open Question 1). Recommendation: defer to a follow-up if product wants stricter chat moderation.
- **Mobile UI for the auto-hidden-author surface.** Author seeing their own auto-hidden post differently is a mobile-side concern; mobile is design-only per [`openspec/project.md`](../../project.md) § Mobile + Admin Status. Backend exposes the data correctly; UI follows.
- **Admin Panel review queue UI for Perspective-flagged rows.** Phase 3.5 admin work (Weeks 11–13 per [`docs/08-Roadmap-Risk.md`](../../../docs/08-Roadmap-Risk.md)) owns admin UI. This change writes correctly-shaped queue rows; admin UI surfaces them via the existing `(target_type, target_id)` group + `trigger` filter.
- **Anomaly metrics emission.** Phase 1 §29 (anomaly detection metrics) is a separate change. This change emits structured logs + Sentry events on a per-dispatch basis; aggregation into anomaly-detection metrics is downstream work.
- **Privacy Policy / RoPA update.** User content sent to a third-party (Google Perspective) is a UU PDP disclosure surface — Pre-Launch task in [`docs/06-Security-Privacy.md`](../../../docs/06-Security-Privacy.md). Out of scope here as a code task.
- **Score threshold A/B tuning.** Defaults follow [`docs/06-Security-Privacy.md:163-164`](../../../docs/06-Security-Privacy.md) (>0.8 / 0.6–0.8 / ≤0.6). Tuning happens via Remote Config overrides post-launch (see Open Question 4).
- **Perspective quota increase request.** Operational task — not a code change.
- **Dual-language support (Indonesian-tuned classifier).** Perspective's Indonesian model is currently used as-is; switching to a self-hosted XLM-R model is a Month 6+ scope per [`docs/06-Security-Privacy.md:167`](../../../docs/06-Security-Privacy.md), explicitly out of scope.

## Decisions

### Decision 1: Vendor encapsulation — new `:infra:perspective` module

Create `infra/perspective/` Gradle module owning all `com.google.api-client.*` / Perspective REST API call shapes. `:backend:ktor` depends on `:infra:perspective` and imports only the `PerspectiveClient` interface + `PerspectiveScore` data class (plain Kotlin types).

**Why:** Mirrors the project's vendor-isolation pattern — `:infra:fcm`, `:infra:oidc`, `:infra:redis`, `:infra:remote-config`, `:infra:supabase`, `:infra:otel` all follow the same `interface in :backend:ktor → SDK in :infra:*` shape. Direct Perspective SDK imports in `:backend:ktor` would violate the convention and complicate future swaps (e.g., to a self-hosted XLM-R classifier in Month 6).

**Alternatives considered:**
- (a) Inline HTTP call in `:backend:ktor` using the Ktor client. **Rejected**: violates the no-vendor-SDK-outside-:infra:* invariant; couples Perspective's request/response shape to backend code.
- (b) Add to existing `:infra:remote-config` module. **Rejected**: Perspective is not Firebase-related; conflating concerns harms module boundaries.

### Decision 2: HTTP client — Ktor client (CIO engine), not the Google API client library

Use the Ktor HTTP client (CIO engine, already on the classpath via `:backend:ktor`) inside `:infra:perspective` to POST to `https://commentanalyzer.googleapis.com/v1alpha1/comments:analyze`. The request body is `{"comment": {"text": "<content>"}, "requestedAttributes": {"TOXICITY": {}, "SEVERE_TOXICITY": {}, "IDENTITY_ATTACK": {}, "THREAT": {}}}`; the API key is appended as a `?key=<api-key>` query parameter (per Google's documented auth pattern for Perspective).

**Why:** The Perspective API is a single REST endpoint; the full Google API client library brings ~40 transitive dependencies for one call. Ktor client is already on the classpath, supports `kotlinx.serialization` JSON parsing, and integrates cleanly with coroutines + `withTimeoutOrNull`.

**Alternatives considered:**
- (a) `com.google.api-client:google-api-client` + a generated Perspective stub. **Rejected**: heavy dependency footprint for a single endpoint.
- (b) `java.net.http.HttpClient` (JDK 11+ stdlib). **Rejected**: works, but manual JSON wiring is friction we don't need given Ktor client + serialization are already in.

### Decision 3: Threshold aggregation — `max(toxicity, severeToxicity, identityAttack, threat)`

Compute a single aggregate score: `score = max(toxicity, severeToxicity, identityAttack, threat)`. Apply the canonical thresholds:
- `score > 0.8` → `Outcome.AutoHide` (write queue row + flip `is_auto_hidden = TRUE`)
- `score > 0.6` → `Outcome.FlagOnly` (write queue row, no flip)
- `score ≤ 0.6` → `Outcome.NoAction`

**Why:** [`docs/06-Security-Privacy.md:163-164`](../../../docs/06-Security-Privacy.md) reads "Score >0.8 = auto-hide ... Score 0.6-0.8 = flag to moderation_queue only" — singular "score". The doc lists 4 attributes (TOXICITY, SEVERE_TOXICITY, IDENTITY_ATTACK, THREAT) without specifying aggregation. `max(...)` is the conservative interpretation: any single high-toxicity attribute triggers; this matches the Perspective API community convention for attribute aggregation. Per-attribute weights would be product tuning; `max` is the deterministic baseline.

**Alternatives considered:**
- (a) Per-attribute thresholds with separate cutoffs (e.g., `THREAT > 0.5` triggers but `TOXICITY > 0.8`). **Rejected for v1**: more correct in theory but harder to reason about and tune; defer to a follow-up if Month 3+ data shows attribute-blindness.
- (b) Weighted average (e.g., `0.4 * TOXICITY + 0.3 * SEVERE_TOXICITY + 0.2 * IDENTITY_ATTACK + 0.1 * THREAT`). **Rejected**: weights are arbitrary without data; max-aggregation has no tuning surface to get wrong.

### Decision 4: Async dispatch — `PerspectiveDispatcherScope` mirroring `:infra:fcm` `FcmDispatcherScope`

A long-lived `CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("perspective-dispatch"))` constructed at application startup. Call sites at the post / reply INSERT path invoke `scope.launch { perspectiveModerator.moderate(targetType, targetId, content) }` — fire-and-forget, no `await` at the request handler. The supervisor job ensures one failed dispatch doesn't cancel sibling dispatches. JVM shutdown drains in-flight dispatches up to a 5-second budget via `cancelAndJoin` inside `withTimeoutOrNull(5_000)`.

**Why:** Matches the canonical `:infra:fcm` pattern (`FcmDispatcherScope` from `fcm-push-dispatch` capability). Same operational shape: long-lived scope, supervised, structured logging on failures, bounded shutdown drain. Reuses operator mental model and the same shutdown-test fixture pattern (per [`fcm-shutdown-drain-deterministic-tests`](../../../FOLLOW_UPS.md)).

**Alternatives considered:**
- (a) `kotlinx.coroutines.GlobalScope`. **Rejected**: no cancel-on-shutdown story; orphaned coroutines on JVM exit.
- (b) Per-request scope tied to the Ktor `call`. **Rejected**: the request returns 201 BEFORE the Perspective call completes; tying the dispatch to the request scope would cancel the dispatch when the response sends.
- (c) `Application.module()`-bound scope. **Rejected**: the Application scope cancels at shutdown without a drain budget; the supervised `:infra:fcm` shape is the project convention.

### Decision 5: Fail-open mechanics — every failure mode → `Outcome.NoAction`

The orchestrator returns `Outcome.NoAction` on:
- `withTimeoutOrNull` returns null (500ms exceeded)
- Perspective API returns HTTP 4xx (auth failure, malformed request, rate limit) — log Sentry WARN
- Perspective API returns HTTP 5xx (Google outage) — log Sentry WARN
- Network exception thrown (DNS fail, TLS handshake fail, connection refused) — log Sentry WARN
- `RemoteConfigClient.fetchBoolean("perspective_api_enabled")` returns FALSE — silent skip, no Sentry event
- `RemoteConfigClient` throws (Remote Config unavailable) — fail-OPEN to enabled=true; log Sentry WARN, attempt the Perspective call

**Why:** [`docs/06-Security-Privacy.md:179`](../../../docs/06-Security-Privacy.md) reads "500ms timeout, fail-open". The user's content is already INSERTed; failing the dispatch silently preserves availability. The Layer 1+2 sync gates already ran successfully; Layer 3 is defense-in-depth. A flapping Perspective endpoint MUST NOT degrade post-creation latency.

**Sentry deduplication:** Each dispatch failure emits a Sentry breadcrumb-level event (NOT an exception capture) keyed on `(failure_kind, perspective_endpoint)` so Sentry's built-in dedup suppresses event floods during sustained outages. Mirrors the `ModerationListLoader`'s in-call rate-limit pattern from [`content-moderation-keyword-lists/spec.md`](../../specs/content-moderation-keyword-lists/spec.md) § "Tier-fallback Sentry events emit at most once per `load(list)` call".

**Alternatives considered:**
- (a) Fail-CLOSED on Perspective unavailability (block the post until Perspective recovers). **Rejected**: defeats the whole point of an async post-INSERT layer; degrades user experience for a defense-in-depth surface.
- (b) Retry with exponential backoff on 5xx. **Rejected for v1**: complicates the dispatch surface; Perspective's SLA is high; if 5xx persists it's a sustained outage and retries don't help.

### Decision 6: Kill-switch source-of-truth — `RemoteConfigClient.fetchBoolean("perspective_api_enabled")` on every dispatch

Read `perspective_api_enabled` (default TRUE per Pre-Phase 1 §18) on every dispatch. The 5-min Redis cache layer in `:infra:remote-config` (`{scope:remote_config}:{flag:perspective_api_enabled}` cache key) absorbs the read cost.

**Why:** Operators MUST be able to disable Layer 3 within minutes (5-min Remote Config cache TTL is the bound, matching the existing `ModerationListLoader` 5-min TTL convention per `content-moderation-keyword-lists` Tier-1 cache). Caching the kill-switch in the orchestrator long-term would defeat the operational kill-switch contract.

`RemoteConfigClient` already exposes `fetchBoolean(parameterName: String): Boolean?` per [`infra/remote-config/.../RemoteConfigClient.kt:42`](../../../infra/remote-config/src/main/kotlin/id/nearyou/app/infra/remoteconfig/RemoteConfigClient.kt) — no interface change needed for the kill switch.

**Alternatives considered:**
- (a) Read at boot only, refresh on a fixed schedule (e.g., every 1 minute). **Rejected**: complicates the orchestrator; Remote Config's own caching already handles this.
- (b) JVM-internal cache with manual invalidation endpoint. **Rejected**: the `:infra:remote-config` module already owns this pattern; bypassing it is duplication.

### Decision 6b: Threshold reads — extend `RemoteConfigClient` with a `fetchDouble` method

The existing `RemoteConfigClient` interface ([`infra/remote-config/.../RemoteConfigClient.kt`](../../../infra/remote-config/src/main/kotlin/id/nearyou/app/infra/remoteconfig/RemoteConfigClient.kt)) exposes `fetchBoolean`, `fetchInt`, `fetchStringList`, `fetchRawString` — but NOT `fetchDouble`. The threshold tunables (`perspective_api_high_score_threshold`, `perspective_api_flag_threshold`) need a Double pathway.

**Decision:** Add a new `fetchDouble(parameterName: String): Double?` method to `RemoteConfigClient` in `:infra:remote-config`. Mirrors the existing `fetchInt` parse-and-clamp pattern. This is a forward-compatible interface extension (no caller change needed for existing methods); the new method's implementation reuses the same Tier 1/2/3/4 fallback ladder via `fetchRawString` then `String.toDoubleOrNull()` parse.

**Alternatives considered:**
- (a) Use `fetchRawString` + parse Double in `PerspectiveModerator` directly (no interface change). **Rejected**: scatters the parse + clamp logic into the consumer; the `:infra:remote-config` module owns parse-from-Remote-Config patterns by precedent (`fetchInt` parses `Int`).
- (b) Add a generic `fetchTyped<T>` method. **Rejected**: over-abstraction for one new caller; `fetchDouble` matches the existing per-type method shape.

### Decision 7: No-user-content on Sentry breadcrumbs

Sentry events emitted by `PerspectiveModerator` SHALL include:
- `event = "perspective_dispatch_failed"` (failure path) or `"perspective_high_score_applied"` / `"perspective_flag_applied"` (success path)
- `target_type` (`post` | `reply`) and `target_id` (UUID — the post/reply identifier)
- `score_bucket` (`low` | `mid` | `high`) — coarse bucket, not the raw score
- `failure_kind` (`timeout` | `http_4xx` | `http_5xx` | `network` | `parse`) on failure events

The events SHALL NOT include:
- Raw `content` string (user-supplied text)
- Raw `score` floats per attribute (would leak Perspective's classification of specific content)
- Matched-keyword analogues (Perspective doesn't return them, but to be explicit)
- User identifier (`user.id`) — the orchestrator does not consume the user identity; the upstream handler may add a hashed `user.id` per [`observability-otel-foundation/spec.md`](../../../specs/observability-otel-foundation/spec.md) but `PerspectiveModerator` itself stays user-agnostic

**Why:** Mirrors the `content-moderation-keyword-lists` Sentry contract (`### Requirement: TextModerator Sentry events do NOT carry user content`). Raw scores per attribute could enable bypass-pattern reverse engineering ("what wording produces what scores"); coarse buckets are operationally sufficient for "is Layer 3 firing as expected" dashboarding.

### Decision 8: Atomicity — auto-hide flip + queue insert in one SQL transaction

The high-score path (`Outcome.AutoHide`) executes `UPDATE posts SET is_auto_hidden = TRUE WHERE id = ?` AND `INSERT INTO moderation_queue (...) ON CONFLICT (target_type, target_id, trigger) DO NOTHING` inside one transaction. Either both apply or neither does.

**Why:** A partial failure (auto-hide flips but queue write fails) leaves the row hidden with no admin-review trail — a worse state than no action at all. A partial failure the other way (queue row written but auto-hide didn't apply) leaves the row visible with an admin alert pending — lesser harm but inconsistent. Atomicity prevents both. Mirrors the [`content-moderation-keyword-lists`](../../specs/content-moderation-keyword-lists/spec.md) `### Requirement: TextModerator integration ... before INSERT` § Scenario "Flag verdict writes moderation_queue row in the same transaction as INSERT".

### Decision 9: Boot-time prime — NOT needed

Unlike `ModerationListLoader` (which boot-primes the Redis cache so first-traffic moderation isn't slow), `PerspectiveModerator` has no warm cache to seed. The Perspective HTTP client is stateless per request. Skip the boot-prime; first dispatch incurs first-call latency, which is a non-issue because the dispatch is async (user request has already returned).

### Decision 10: Two queue rows when Layer 2 + Layer 3 both fire

A row that Layer 2 flagged (`uu_ite_keyword_match` trigger) AND Layer 3 high-scored (`perspective_api_high_score` trigger) gets TWO `moderation_queue` rows — one per trigger. The admin UI surfaces them grouped by `(target_type, target_id)` per [`docs/05-Implementation.md:545`](../../../docs/05-Implementation.md). This is the canonical multi-trigger behavior and is preserved here.

**Why:** The existing UNIQUE constraint is `(target_type, target_id, trigger)` — different triggers do not collide. Two rows per target with different triggers is the correct shape; consolidating them in the admin UI is a presentation concern, not a write-time one.

## Risks / Trade-offs

- **Perspective API quota — default 1 QPS** → Mitigation: file the quota-increase request before staging smoke (operational task, not code). Free-tier signup is sufficient for staging at MVP traffic; production needs a quota increase. Sentry WARN on Perspective HTTP 429 surfaces quota exhaustion.
- **Perspective API outage degrades moderation silently** → Mitigation: fail-open + Sentry WARN per failure (deduplicated). Layer 1+2 still protect the highest-priority cases (profanity + UU ITE).
- **Score threshold sensitivity for Indonesian content** → Mitigation: defaults from canonical doc (>0.8 / >0.6); Remote Config-tunable overrides (`perspective_api_high_score_threshold` / `perspective_api_flag_threshold`) ship in this change so ops can adjust without redeploy. Open Question 4 surfaces the tuning playbook.
- **User content sent to a third-party (Google) — UU PDP disclosure** → Mitigation: Privacy Policy / RoPA update is a Pre-Launch task; flag in `docs/06-Security-Privacy.md` Privacy section. NOT a code change here, but the proposal calls it out as a downstream prerequisite.
- **Cost** → Perspective API is free for moderate volumes; pricing surfaces only above quota tier. MVP traffic is well within free.
- **Latency budget regression risk** → Mitigation: dispatch is fire-and-forget; the 500ms `withTimeoutOrNull` lives entirely off the request path. The user request returns immediately after the synchronous Layer 1+2 + INSERT.

## Migration Plan

No data migration. No schema migration. New module + new code only.

**Deploy sequence:**
1. Land code + tests via the standard one-PR-per-change lifecycle.
2. Provision the `staging-perspective-api-key` secret slot in GCP Secret Manager (manual operator step) BEFORE staging smoke.
3. Verify `perspective_api_enabled = TRUE` in staging Firebase Remote Config (already seeded per Pre-Phase 1 §18; idempotent verification only).
4. Smoke against staging: post a test message that should trigger high-score → assert `is_auto_hidden = TRUE` + queue row written.
5. Squash-merge → auto-deploys to staging via the unified branch.
6. Production: provision `perspective-api-key` slot + verify `perspective_api_enabled = TRUE` BEFORE the prod tag-deploy.

**Rollback:** Flip `perspective_api_enabled = FALSE` in Firebase Remote Config; Layer 3 dispatch silent-skips within 5 minutes (Remote Config cache TTL). Code rollback is a separate revert PR if a behavior bug surfaces.

## Open Questions

### Open Question 1: Should chat-message Layer 3 ship in this change too?

Chat send (`POST /api/v1/chat/{conversation_id}/messages`) currently runs Layer 1+2 synchronously per `chat-foundation` capability. Whether to extend Layer 3 to chat is a product decision:

- **Pro**: chat is a 2000-char compose surface — the Perspective scoring window is wider; toxic chat is a harassment vector.
- **Con**: chat is private (1:1) — moderation ROI per dispatch is lower; chat redaction is an admin tool (Phase 3.5) not a user-visible surface.
- **Recommendation**: **defer chat Layer 3 to a follow-up.** Ship posts + replies in this change. Open a follow-up entry `text-moderation-perspective-api-layer-chat-extension` triggered if Phase 3 chat-monitoring data shows toxicity exceeding the keyword-list catch rate.

### Open Question 2: Should the threshold-tunable Remote Config flags ship in this change or be deferred?

`perspective_api_high_score_threshold` (default 0.8) and `perspective_api_flag_threshold` (default 0.6) — Remote Config-tunable so ops can adjust without redeploy.

- **Pro**: ship-now means Month 1 data tuning happens without a code change.
- **Con**: more surface area — two new Remote Config keys + their loader logic + clamp-to-[0.0, 1.0] guards + cached-bad-value handling (matching `moderation_match_threshold` pattern from [`content-moderation-keyword-lists`](../../specs/content-moderation-keyword-lists/spec.md)).
- **Recommendation**: **ship the flags in this change.** Marginal cost, high optionality. Mirrors the existing `moderation_match_threshold` tunable pattern. Defaults align with the canonical doc.

### Open Question 3: Sync vs async — should Layer 3 ever block the response?

Currently spec'd as async post-INSERT. Could a "block-the-response if Perspective returns within 100ms" mode be useful?

- **Pro**: in the rare 100ms-respond case, the user could see "your post was flagged" inline rather than discover it minutes later.
- **Con**: complicates the dispatch surface significantly; 100ms is Perspective's p50, not its tail; waiting introduces variance into the request path that the canonical doc explicitly rejects.
- **Recommendation**: **stick with async-only.** Canonical doc says async; add complexity only if user feedback justifies.

### Open Question 4: Threshold-tuning playbook — when do we tune?

Out-of-scope for this change but worth noting: Pre-Launch security review (`docs/08-Roadmap-Risk.md` Pre-Launch §5) doesn't list a threshold-tuning gate. Recommend adding one in a future docs PR: "Re-tune Perspective thresholds at Month 3 + Month 6 based on `(false_positive_rate, false_negative_rate)` from admin queue review data."

### Open Question 5: API client placement — `:infra:perspective` vs adding to `:infra:remote-config`?

Settled in Decision 1 — `:infra:perspective`. Open question for confirmation: any objection to introducing a single-call infra module? Pattern matches `:infra:oidc` (single-purpose, one verifier) so precedent exists.
