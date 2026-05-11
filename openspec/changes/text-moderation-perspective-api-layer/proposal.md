## Vendor Swap Amendment (2026-05-11, mid-implementation)

The original proposal below targeted **Google Perspective API**. Mid-implementation
(after 7 commits of orchestrator + dispatcher + writer + tests landed), Perspective
announced **sunset at end-of-2026 with signups closed February 2026** — making the
"Phase 2 stopgap" vendor untenable for a pre-launch product with launch timeline
beyond Q4 2026.

After a research pass across alternatives (OpenAI Moderation, Moderation API
`moderationapi.com`, Azure AI Content Safety, AWS Comprehend, Detoxify XLM-R
self-host, Hive AI, Spectrum Labs/ActiveFence), the vendor pivoted to **OpenAI
Moderation API** (`omni-moderation-latest` model). Rationale:

- **Cost**: free (Moderation endpoint has no per-call charge; only constraint is
  an OpenAI account with payment method on file + $5 minimum prepaid deposit
  one-time, which sits idle if only Moderation is used).
- **Indonesian quality**: OpenAI publicly benchmarks Indonesian as a top-tier
  supported language in `omni-moderation-latest` (alongside Spanish, German,
  Italian, Polish, Vietnamese, Portuguese, French, Chinese, English). Step up
  from Perspective's "partial ID support" caveat in the original spec.
- **Production validation**: Grammarly + ElevenLabs publicly confirmed; widely
  used in Discord bot ecosystems.
- **Sustainability**: strategic surface for OpenAI (used in their own products);
  no sunset risk in the foreseeable horizon.

**What changed in this PR**:

- `:infra:perspective` Gradle module → `:infra:openai-moderation`
- `PerspectiveClient` interface → `ModerationClient` (vendor-agnostic; the
  interface stays neutral so future vendor swaps don't churn consumers)
- `PerspectiveScore(toxicity, severeToxicity, identityAttack, threat)` →
  `ModerationScore(categoryScores: Map<String, Double>)` (vendor-neutral
  score shape; OpenAI's 13 categories flow through as Map keys)
- `KtorPerspectiveClient` → `OpenAiModerationClient` (Bearer-auth + JSON body;
  no Google-API URL `?key=` pattern)
- `PerspectiveModerator` → `Layer3Moderator` (architectural name, vendor-agnostic)
- All Sentry event names: `perspective_*` → `layer3_*`
- Secret slot: `perspective-api-key` → `openai-api-key`

**What is preserved as historical artifact** (per CLAUDE.md "documented debt"
principle — these are operator-facing or schema-fixed, renaming would require
parallel external migrations):

- OpenSpec change name `text-moderation-perspective-api-layer` (Git branch + PR
  + spec directory) — historical artifact from the proposal phase
- Firebase Remote Config parameter names (`perspective_api_enabled`,
  `perspective_api_high_score_threshold`, `perspective_api_flag_threshold`) —
  already seeded in Firebase Console; renaming requires parallel seed migration
- V9 SQL `moderation_queue.trigger = 'perspective_api_high_score'` — fixed in
  the V9 CHECK constraint enum; renaming requires a Flyway migration

**What is unchanged**:

- Architectural shape: orchestrator → dispatcher → writer, all the same
- Aggregation policy: per-call max across categories (was 4 attributes; now 13
  categories — same `max(...)` semantics)
- Threshold semantics: `>0.8 → AutoHide`, `>0.6 ≤ 0.8 → FlagOnly`, `≤0.6 →
  NoAction`. Strict-greater-than boundary.
- Fail-open posture, kill-switch, cross-flag-misconfig fallback, soft-delete
  race handling, no-content-on-Sentry contract, OTel trace propagation.

**Second mid-flight amendment — timeout budget bumped 500 → 1500ms (2026-05-11)**:

After the OpenAI swap landed and staging deploy went green, the pre-archive
smoke surfaced a regional-latency finding: `asia-southeast1` Cloud Run →
US-hosted OpenAI Moderation API has empirical TTFB p50 ~600-900ms (measured
via a one-shot `curl` Cloud Run Job in the production region). The original
500ms budget — written assuming a US deployment per the pre-pivot canonical
doc — was incompatible with Singapore reality; every dispatch would
fail-open on timeout, effectively disabling Layer 3 in production. Budget
bumped to **1500ms** (covers Singapore p95 with ~500ms tail buffer for parse
+ outliers; constructor-tunable via `analyzeTimeoutMillis` for deployments
in other regions). Affected files:

- `Layer3Moderator.kt`: `ANALYZE_TIMEOUT_MILLIS: Long = 1500L` (was 500L)
- `OpenAiModerationClient.kt`: `REQUEST_TIMEOUT_MILLIS` + `SOCKET_TIMEOUT_MILLIS = 1500L`
- `docs/06-Security-Privacy.md:184`: canonical "Layer 3 budget" line
- `design.md` Decision 2 + Risks section
- `specs/text-moderation-perspective-api-layer/spec.md`: timeout requirement + scenarios
- Tests: `Layer3ModeratorTest` + `Layer3RouteTest` timeout-scenarios use
  100ms test-only budget via the `analyzeTimeoutMillis` constructor param
  (preserves fast test runtime; asserts the boundary semantic, not a
  hardcoded number)

The dispatch is fire-and-forget so user request time is unaffected; shutdown
drain budget (5s) still > 1500ms; coroutine resource usage at 100
dispatches/sec is ~3× the prior baseline but well within capacity.
- All test coverage (47 unit + 7 writer-integration + 8 service-level integration
  + 11 HTTP-boundary route + 3 structural + 1 endpoint-leakage scan, plus 14
  new OpenAI client tests).

**Operator-side checklist** (per `docs/10-Setup-Checklist.md` § 4.2):

1. ✅ `staging-openai-api-key` provisioned in GCP Secret Manager (2026-05-11)
2. ✅ Cloud Run runtime SA granted `roles/secretmanager.secretAccessor`
3. ✅ Wired into `deploy-staging.yml --set-secrets` as
   `OPENAI_API_KEY=staging-openai-api-key:latest`
4. ⚠️ Privacy Policy / UU PDP cross-border-transfer disclosure (Pasal 56) —
   Pre-Launch task; legal-counsel review needed (see proposal § Why for
   background; not a hard-block for staging smoke but mandatory pre-Launch)

---

## Why

The text-moderation pipeline today (Layers 1+2, shipped via [`content-moderation-keyword-lists`](../../specs/content-moderation-keyword-lists/spec.md)) catches keyword-listed profanity (Layer 1, sync REJECT) and UU ITE category matches (Layer 2, sync soft-flag). It does NOT catch toxicity that doesn't tokenize against the operator-curated wordlists — slurs phrased novelly, identity attacks expressed without listed keywords, threats wrapped in benign vocabulary. The canonical multi-layer moderation contract at [`docs/06-Security-Privacy.md:153-184`](../../../docs/06-Security-Privacy.md) prescribes a third layer: an asynchronous post-INSERT call to the **Google Perspective API** (toxicity classifier) with score-threshold-driven outcomes. This is **Phase 2 §16** in [`docs/08-Roadmap-Risk.md:178`](../../../docs/08-Roadmap-Risk.md) and the [`perspective-api-stopgap`](../../../FOLLOW_UPS.md) follow-up has been waiting for this change.

Now is the right time because: (a) the Layer 1+2 foundation (`TextModerator`, `ModerationListLoader`, `:infra:remote-config`) shipped two weeks ago — Layer 3 plugs in cleanly; (b) the `moderation_queue.trigger` enum already includes `'perspective_api_high_score'` (reserved at V9) so no Flyway migration is needed; (c) the `perspective_api_enabled` Firebase Remote Config kill-switch flag has been seeded since Pre-Phase 1 §18; (d) Pre-Launch security review §5 lists "Perspective API kill switch tested" as a gate.

## What Changes

- **NEW** `:infra:perspective` Gradle module under `infra/perspective/` owning the Google Perspective API HTTP client (per the project's `:infra:*` vendor-encapsulation pattern). Exposes a `PerspectiveClient` interface returning a plain Kotlin `PerspectiveScore(toxicity, severeToxicity, identityAttack, threat)` data class. API key sourced from a new GCP Secret Manager slot `perspective-api-key` (env-namespaced).
- **NEW** `PerspectiveConfigLoader` + `PerspectiveModerator` orchestrator in `:backend:ktor` (`id.nearyou.app.moderation`) — `PerspectiveConfigLoader` is the cache layer for the kill-switch + thresholds (4-tier fallback: Redis 5-min cache → Remote Config → static defaults → fail-open; mirrors the `ModerationListLoader` pattern; cache key scope `{scope:perspective_config}:{flag:<flag-name>}`). `PerspectiveModerator` consumes the loader, invokes `PerspectiveClient` with a 500ms `withTimeoutOrNull` budget (bumped to **1500ms** mid-implementation when empirical TTFB from asia-southeast1 → US OpenAI was measured at p50 600-900ms — see design.md Decision 2 + proposal.md § Vendor Swap Amendment), applies the score-threshold mapping (>0.8 → auto-hide + queue; 0.6–0.8 → queue only; ≤0.6 → no action), writes to `moderation_queue` with `trigger = 'perspective_api_high_score'`, and (high-score only) flips `posts.is_auto_hidden = TRUE` (or `post_replies.is_auto_hidden`) — with `AND deleted_at IS NULL` soft-delete guard — in the same SQL transaction as the queue insert.
- **NEW** async dispatch wiring after each successful content INSERT (posts + replies). Mirrors the `:infra:fcm` `FcmDispatcherScope` pattern — long-lived `SupervisorJob`-rooted scope, structured logging on failures, JVM-shutdown drain budget. Fire-and-forget at the call site; the orchestrator owns its own coroutine scope.
- **NEW** capability spec `text-moderation-perspective-api-layer` covering the orchestrator contract, threshold semantics (max-attribute aggregation), fail-open posture (timeout / 5xx / network error / kill-switch-OFF → no action), idempotent queue write (existing UNIQUE on `(target_type, target_id, trigger)`), atomicity (auto-hide + queue insert in one transaction), async dispatch lifecycle (drain on shutdown), no-user-content-on-Sentry contract.
- **MODIFIED** `content-moderation-keyword-lists` capability — single requirement amendment clarifying that Layer 3 runs **post-INSERT, async**, NOT through the existing synchronous `TextModerator.moderate(content): Verdict` path. The existing `### Requirement: TextModerator integration is invoked AFTER existing length and rate-limit gates, BEFORE INSERT` gets a sibling clause: "Layer 3 (Perspective API) runs AFTER the INSERT in a separate async dispatcher; see `text-moderation-perspective-api-layer` capability."
- Closes the `perspective-api-stopgap` follow-up (delete the entry in the archive commit).

## Capabilities

### New Capabilities

- `text-moderation-perspective-api-layer`: Asynchronous post-INSERT Layer 3 toxicity classifier on top of the keyword-list moderator. Owns the Perspective API client interface, score-threshold semantics, fail-open mechanics, kill-switch, queue-row atomicity, and async dispatch lifecycle.

### Modified Capabilities

- `content-moderation-keyword-lists`: ONE requirement amended (`### Requirement: TextModerator integration is invoked AFTER existing length and rate-limit gates, BEFORE INSERT`) to add a sibling clause distinguishing the synchronous Layer 1+2 path from the asynchronous Layer 3 path. No requirements removed; no scenarios reworked.

## Impact

- **New module**: `:infra:perspective` (Gradle `infra/perspective/build.gradle.kts`, included in `settings.gradle.kts`, described in `dev/module-descriptions.txt`, README auto-regenerated via `dev/scripts/sync-readme.sh --write`).
- **New backend code**: `id.nearyou.app.moderation.PerspectiveModerator` orchestrator, `PerspectiveDispatcherScope` lifecycle, async-dispatch wiring at the post-INSERT call sites in `PostCreationService` and `PostRepliesService` (or equivalent existing service names). Koin module bindings.
- **No schema migration**: `moderation_queue.trigger = 'perspective_api_high_score'` is already in V9; `posts.is_auto_hidden` is in V4; `post_replies.is_auto_hidden` is in V8. Net-zero new Flyway migrations.
- **New secret**: `perspective-api-key` in GCP Secret Manager (staging + prod slots). Documented in [`docs/05-Implementation.md:22`](../../../docs/05-Implementation.md) secrets list.
- **No mobile changes**: backend-only contract. Mobile UI for the auto-hidden-author surface is design-only and explicitly out of scope.
- **No admin UI changes**: queue rows surface in the existing admin UI (Phase 3.5 work owns the UI; this change writes the rows correctly).
- **Privacy Policy surface**: user-supplied post content is sent to a third-party (Google Perspective) for classification. UU PDP disclosure surface — flag as a Pre-Launch task in `docs/06-Security-Privacy.md`. NOT a code task in this change.
- **Out-of-scope (deferred)**: chat-message Layer 3 (chat ships Layer 1+2 only today; whether Layer 3 should also apply is a product decision — open question in `design.md`); admin Perspective-flagged-row review UI (Phase 3.5); anomaly metrics emission (Phase 1 §29 separate change); Aho-Corasick → Perspective tie-breaking is no-op (Layer 1 reject prevents INSERT, so Layer 3 never runs on Layer-1-rejected content).
