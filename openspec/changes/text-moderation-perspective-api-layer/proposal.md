## Why

The text-moderation pipeline today (Layers 1+2, shipped via [`content-moderation-keyword-lists`](../../specs/content-moderation-keyword-lists/spec.md)) catches keyword-listed profanity (Layer 1, sync REJECT) and UU ITE category matches (Layer 2, sync soft-flag). It does NOT catch toxicity that doesn't tokenize against the operator-curated wordlists — slurs phrased novelly, identity attacks expressed without listed keywords, threats wrapped in benign vocabulary. The canonical multi-layer moderation contract at [`docs/06-Security-Privacy.md:153-184`](../../../docs/06-Security-Privacy.md) prescribes a third layer: an asynchronous post-INSERT call to the **Google Perspective API** (toxicity classifier) with score-threshold-driven outcomes. This is **Phase 2 §16** in [`docs/08-Roadmap-Risk.md:178`](../../../docs/08-Roadmap-Risk.md) and the [`perspective-api-stopgap`](../../../FOLLOW_UPS.md) follow-up has been waiting for this change.

Now is the right time because: (a) the Layer 1+2 foundation (`TextModerator`, `ModerationListLoader`, `:infra:remote-config`) shipped two weeks ago — Layer 3 plugs in cleanly; (b) the `moderation_queue.trigger` enum already includes `'perspective_api_high_score'` (reserved at V9) so no Flyway migration is needed; (c) the `perspective_api_enabled` Firebase Remote Config kill-switch flag has been seeded since Pre-Phase 1 §18; (d) Pre-Launch security review §5 lists "Perspective API kill switch tested" as a gate.

## What Changes

- **NEW** `:infra:perspective` Gradle module under `infra/perspective/` owning the Google Perspective API HTTP client (per the project's `:infra:*` vendor-encapsulation pattern). Exposes a `PerspectiveClient` interface returning a plain Kotlin `PerspectiveScore(toxicity, severeToxicity, identityAttack, threat)` data class. API key sourced from a new GCP Secret Manager slot `perspective-api-key` (env-namespaced).
- **NEW** `PerspectiveConfigLoader` + `PerspectiveModerator` orchestrator in `:backend:ktor` (`id.nearyou.app.moderation`) — `PerspectiveConfigLoader` is the cache layer for the kill-switch + thresholds (4-tier fallback: Redis 5-min cache → Remote Config → static defaults → fail-open; mirrors the `ModerationListLoader` pattern; cache key scope `{scope:perspective_config}:{flag:<flag-name>}`). `PerspectiveModerator` consumes the loader, invokes `PerspectiveClient` with a 500ms `withTimeoutOrNull` budget, applies the score-threshold mapping (>0.8 → auto-hide + queue; 0.6–0.8 → queue only; ≤0.6 → no action), writes to `moderation_queue` with `trigger = 'perspective_api_high_score'`, and (high-score only) flips `posts.is_auto_hidden = TRUE` (or `post_replies.is_auto_hidden`) — with `AND deleted_at IS NULL` soft-delete guard — in the same SQL transaction as the queue insert.
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
