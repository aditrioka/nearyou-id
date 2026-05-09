## 1. Proposal review (this PR's proposal-review phase)

- [ ] 1.1 Run `openspec validate text-moderation-perspective-api-layer --strict` and confirm green
- [ ] 1.2 Canonical-docs reconciliation pass — diff every claim against `docs/06-Security-Privacy.md:153-184`, `docs/05-Implementation.md:520+` (`moderation_queue.trigger` enum), `docs/05-Implementation.md:1042` (`perspective_api_enabled` flag)
- [ ] 1.3 Open the change PR (`docs(openspec): propose text-moderation-perspective-api-layer`); branch name = change name; PR body summarizes Why + What Changes + Capabilities deltas
- [ ] 1.4 Multi-lens sub-agent review (general / security-and-invariant / OpenSpec format-and-correctness / test-coverage), then optional round-2 regression scan
- [ ] 1.5 qodo review at PR push (auto, GitHub-side)
- [ ] 1.6 Apply blocking findings as new commits on the same branch, re-run validate, push
- [ ] 1.7 Refresh PR title + body to summarize blocking-vs-non-blocking findings + "ready to start implementation"

## 2. Module scaffolding (`:infra:perspective`)

- [ ] 2.1 Create `infra/perspective/build.gradle.kts` matching the `:infra:fcm` / `:infra:remote-config` shape (Kotlin/JVM, ktor-client-core + ktor-client-cio + ktor-client-content-negotiation + kotlinx-serialization-json deps). Module SHALL NOT depend on `:backend:ktor`'s `SecretResolver` — secret resolution is the caller's responsibility per design.md Decision 1
- [ ] 2.2 Add `:infra:perspective` to `settings.gradle.kts`
- [ ] 2.3 Add a one-line entry to `dev/module-descriptions.txt` describing `:infra:perspective`
- [ ] 2.4 Run `dev/scripts/sync-readme.sh --write` to regenerate the README module list; commit the resulting README diff
- [ ] 2.5 Verify CI's sync-readme `--check` passes locally before pushing
- [ ] 2.6 Define `PerspectiveScore` data class + `PerspectiveClient` interface in `:infra:perspective` package `id.nearyou.app.infra.perspective`
- [ ] 2.7 Implement `KtorPerspectiveClient(apiKey: String)` constructor — receives the resolved API key string (resolved by caller in `:backend:ktor` Application.module() via `secrets.resolve("perspective-api-key")` per design.md Decision 1, mirroring [`Application.kt:441`](../../../backend/ktor/src/main/kotlin/id/nearyou/app/Application.kt) `secrets.resolve("firebase-admin-sa")`). HTTP client SHALL use Ktor CIO engine with explicit timeouts: `requestTimeoutMillis = 500`, `connectTimeoutMillis = 200`, `socketTimeoutMillis = 500` per design.md Decision 2 (defense against socket-pin past coroutine cancellation). POST to `https://commentanalyzer.googleapis.com/v1alpha1/comments:analyze?key=<api-key>` with the canonical `{"comment": {"text": "..."}, "requestedAttributes": {"TOXICITY": {}, "SEVERE_TOXICITY": {}, "IDENTITY_ATTACK": {}, "THREAT": {}}}` body
- [ ] 2.7a In `:backend:ktor` `Application.module()`, add the secret-resolution call site: `val perspectiveApiKey = secrets.resolve("perspective-api-key")` with a `secretKey(env, "perspective-api-key")` computation for the diagnostic init log line (mirroring [`Application.kt:439-441`](../../../backend/ktor/src/main/kotlin/id/nearyou/app/Application.kt) `event=...init_failed reason=missing_secret slot={} env={}` pattern). Construct `KtorPerspectiveClient(apiKey = perspectiveApiKey ?: ...)`, bind via Koin
- [ ] 2.8 Add fixture-mock `RecordingPerspectiveClient` in `:infra:perspective` test sources for downstream test reuse (records each `analyze(content)` call's content + lets tests stage a `PerspectiveScore` or a thrown exception per call)
- [ ] 2.9 Unit tests for `KtorPerspectiveClient`: happy-path JSON parse against a `MockEngine`, malformed-JSON parse error, HTTP 4xx, HTTP 5xx, network-exception path, API-key URL-encoding correctness, AND verify the CIO engine config sets the three timeout values per design.md Decision 2

## 3. Pin Perspective API Ktor client deps in version catalog (if not already)

- [ ] 3.1 Verify `gradle/libs.versions.toml` has the Ktor client coordinates already (likely yes — used by `:infra:fcm` and elsewhere)
- [ ] 3.2 If any new artifact pin is added, add a row to `docs/09-Versions.md` per the version-pinning convention

## 4. GCP Secret Manager slot reservation (operational, document only)

- [ ] 4.1 Document new secret slot `perspective-api-key` in `docs/05-Implementation.md` § Secrets list (line 22 in current revision)
- [ ] 4.2 Document the staging slot mirror `staging-perspective-api-key` per the same convention (env-namespaced)
- [ ] 4.3 Add an item to `docs/10-Setup-Checklist.md` for the operator to provision both slots before staging smoke (Section 6 task)

## 5. Extend `:infra:remote-config` with `fetchDouble`

- [ ] 5.0a Add `fun fetchDouble(parameterName: String): Double?` to the `RemoteConfigClient` interface at [`infra/remote-config/.../RemoteConfigClient.kt:34`](../../../infra/remote-config/src/main/kotlin/id/nearyou/app/infra/remoteconfig/RemoteConfigClient.kt) (mirror existing `fetchInt` shape per Decision 6b)
- [ ] 5.0b Implement `fetchDouble` in the production binding by delegating to `fetchRawString(...)` + `String.toDoubleOrNull()`; null-on-parse-error to match the `fetchInt` pattern
- [ ] 5.0c Unit tests for `fetchDouble`: happy-path parse, malformed-numeric-string returns null, integer-shaped string parses (`"3"` → `3.0`), trailing-whitespace tolerant if `fetchInt` is

## 6. `PerspectiveConfigLoader` (cache layer in `:backend:ktor`) + `PerspectiveModerator` orchestrator

- [ ] 6.0 Define `PerspectiveConfigLoader` in `:backend:ktor` package `id.nearyou.app.moderation` (sibling of `PerspectiveModerator`) per design.md Decision 6 — the cache layer for kill-switch + thresholds lives in `:backend:ktor`, NOT `:infra:remote-config` (that infra module has no Redis dependency)
- [ ] 6.0a Implement `PerspectiveConfigLoader.isEnabled()` — Tier 1 (Redis cache key `{scope:perspective_config}:{flag:perspective_api_enabled}`, 5-min TTL) → Tier 2 (`RemoteConfigClient.fetchBoolean("perspective_api_enabled")`) → Tier 3 (static default `true`). On Tier 2 throw + no fallback, fail-OPEN to true AND emit Sentry **ERROR** `event = "perspective_kill_switch_unavailable"` per design.md Decision 12
- [ ] 6.0b Implement `PerspectiveConfigLoader.highScoreThreshold()` / `flagThreshold()` — Tier 1 (Redis cache key `{scope:perspective_config}:{flag:<flag-name>}`) → Tier 2 (`RemoteConfigClient.fetchDouble(...)`) → Tier 3 (static defaults 0.8 / 0.6). Apply `[0.0, 1.0]` clamp on EVERY read (cache + Tier 2); emit Sentry WARN `event = "perspective_threshold_fallback"` on out-of-range
- [ ] 6.0c Unit tests for `PerspectiveConfigLoader`: Tier 1 hit short-circuits, Tier 1 miss→Tier 2 success caches, Tier 2 throw + Tier 3 fallback (kill-switch fails open + ERROR), threshold clamp on positive out-of-range, threshold clamp on negative out-of-range, threshold clamp on cached bad value
- [ ] 6.1 Define `Outcome` sealed interface (`NoAction` / `FlagOnly(score)` / `AutoHide(score)`) and `TargetType` enum (`POST` / `REPLY`) in `:backend:ktor` package `id.nearyou.app.moderation`
- [ ] 6.2 Define `PerspectiveModerator` interface + Koin-bindable `DefaultPerspectiveModerator` implementation
- [ ] 6.3 Implement orchestrator's threshold consumption — read via `PerspectiveConfigLoader.highScoreThreshold()` / `flagThreshold()`. Detect cross-flag misconfiguration (`flag_threshold > high_score_threshold`) per design.md Decision 11; if violated, fall back to BOTH defaults (0.8 / 0.6) AND emit Sentry **ERROR** `event = "perspective_threshold_misconfigured"` (ERROR not WARN — operator-actionable)
- [ ] 6.4 Implement orchestrator's kill-switch check — read via `PerspectiveConfigLoader.isEnabled()`; on `false` return `Outcome.NoAction` silently (no Sentry event); on Tier-3-fallback throw, the loader handles the ERROR emission
- [ ] 6.5 Implement `moderate(targetType, targetId, content): Outcome` — kill-switch check → `withTimeoutOrNull(500.ms) { client.analyze(content) }` → score aggregation `max(toxicity, severeToxicity, identityAttack, threat)` → threshold mapping (with cross-flag-misconfig check) → DB write (transactional) for AutoHide / FlagOnly → return Outcome
- [ ] 6.6 Wrap every PerspectiveClient invocation in try/catch capturing all failure kinds → emit Sentry WARN with `event = "perspective_dispatch_failed"`, populate `failure_kind` ∈ `{timeout, http_4xx, http_5xx, network, parse}`, populate `status_code` for HTTP failure kinds → return `Outcome.NoAction`. **Do NOT swallow `CancellationException`** — let it propagate per coroutine convention so structured cancellation works through the dispatcher scope
- [ ] 6.7 Verify the orchestrator NEVER throws non-cancellation exceptions to its caller. Add a contract-level test exercising the explicit failure-fixture matrix: `{timeout (delay 600ms), http_4xx (status codes 400, 401, 403, 404, 429), http_5xx (500, 502, 503, 504), network (ConnectException, SocketTimeoutException, UnknownHostException), parse (SerializationException, IllegalArgumentException), Remote Config exception during kill-switch read, Remote Config exception during threshold read}` — for each, assert the orchestrator returns `Outcome.NoAction` and does NOT throw. Separately assert that injected `CancellationException` propagates correctly (does NOT get swallowed)

## 7. DB write helpers — transactional auto-hide + queue insert (with soft-delete guard)

- [ ] 7.1 Add JDBC helper `applyAutoHideAndQueue(targetType, targetId, score)` that opens a transaction, runs `UPDATE posts SET is_auto_hidden = TRUE WHERE id = ? AND deleted_at IS NULL` (or `UPDATE post_replies SET is_auto_hidden = TRUE WHERE id = ? AND deleted_at IS NULL`) — the `AND deleted_at IS NULL` predicate matches the canonical V9 writer pattern at [`V9__reports_moderation.sql:36`](../../../backend/ktor/src/main/resources/db/migration/V9__reports_moderation.sql) and prevents orphan flips on tombstoned rows. Capture the UPDATE's affected-row count via `Statement.executeUpdate()` return value
- [ ] 7.1a If UPDATE affected zero rows (soft-deleted-target race per design.md Decision 8), short-circuit: do NOT execute the queue INSERT. Emit a structured INFO log noting the race. ROLLBACK the (otherwise empty) transaction
- [ ] 7.1b If UPDATE affected one row, proceed: run `INSERT INTO moderation_queue (target_type, target_id, trigger, status, priority, ...) VALUES (..., 'perspective_api_high_score', 'pending', ?, ...) ON CONFLICT (target_type, target_id, trigger) DO NOTHING`, COMMIT
- [ ] 7.2 Add JDBC helper `applyFlagQueue(targetType, targetId, score)` that runs the queue INSERT only (no flip), same ON CONFLICT clause, inside an explicit transaction (single-statement; explicit txn boundary mirrors the AutoHide path's shape per spec.md FlagOnly Requirement)
- [ ] 7.3 Determine the correct `priority` value to set on Perspective queue rows by inspecting existing `moderation_queue` insert sites — likely matches `auto_hide_3_reports` priority; document the choice in the change PR body
- [ ] 7.4 Integration tests against a real Postgres (Testcontainers / `KotestProjectConfig` Docker setup): (a) **idempotency** — call helper twice sequentially for the same target → exactly one queue row exists. (b) **true concurrency** — use a `java.util.concurrent.CountDownLatch(2)` (or coroutine-based equivalent) ensuring two separate JDBC connections both reach the INSERT statement BEFORE either COMMITs; both call the helper simultaneously → exactly one queue row exists; neither connection observes a unique-violation error (race-safe via UNIQUE constraint exercised by genuine concurrency, not sequential calls)
- [ ] 7.5 Atomicity test: simulate the queue INSERT failing mid-transaction via a JDBC interceptor that throws on the queue INSERT step (e.g., wrap the `Connection` in a proxy that throws `RuntimeException("simulated INSERT failure")` when the SQL matches `INSERT INTO moderation_queue`). After the helper invocation throws, **re-SELECT the post row from a fresh connection** AND assert `is_auto_hidden = FALSE` (the UPDATE was rolled back) AND assert `SELECT count(*) FROM moderation_queue WHERE target_id = U AND trigger = 'perspective_api_high_score'` returns `0` (no queue row written)
- [ ] 7.6 Soft-delete race test: insert a post + soft-delete it (`UPDATE posts SET deleted_at = NOW()`) → invoke `applyAutoHideAndQueue(POST, U, 0.92)` → assert UPDATE returns 0 affected rows AND no queue row is written AND no exception is thrown

## 8. `PerspectiveDispatcherScope` lifecycle (single class — divergence from `:infra:fcm` split per design.md Decision 4)

- [ ] 8.1 Define `PerspectiveDispatcherScope` class wrapping a `CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("perspective-dispatch"))` PLUS a `@Volatile private var shutdown: Boolean = false` flag (single class owns BOTH the lifecycle AND the dispatch-after-shutdown WARN behavior — divergence from `:infra:fcm`'s `FcmDispatcherScope` + `FcmDispatcher` split, justified by Perspective dispatch's smaller surface)
- [ ] 8.2 Implement `dispatch(parentContext: CoroutineContext, block: suspend () -> Unit): Job?` API. Pre-shutdown: launch the block in `scope` with `parentContext` propagated (preserves OTel trace context per design.md Decision 13). Post-shutdown: emit `event=perspective_dispatch_after_shutdown` structured WARN log + return `null` (silent no-op, do NOT throw to caller)
- [ ] 8.3 Implement `shutdown(drainMillis: Long = 5_000)` — set `shutdown = true` first (prevents new dispatches from launching), then `withTimeoutOrNull(drainMillis) { scope.coroutineContext[Job]?.cancelAndJoin() }`. Track cancelled-mid-dispatch coroutine count via `scope.coroutineContext[Job]?.children?.count { it.isCancelled }` before/after; emit a structured WARN log `event=perspective_dispatch_drain_exceeded cancelled_count=<N>` if any were cancelled
- [ ] 8.4 Wire JVM shutdown hook in `Application.module()` to call `perspectiveDispatcherScope.shutdown()` (alongside the existing `fcmDispatcherScope.shutdown()` site)
- [ ] 8.5 Tests using `kotlinx-coroutines-test` `runTest` + `TestScheduler.advanceTimeBy` for deterministic timing:
  - Sibling-isolation: D1 throws unhandled exception → D2 still completes (SupervisorJob isolation)
  - Drain-completes-within-budget: two dispatches at 1.0s + 2.5s + shutdown(5000) → both complete, no cancellation
  - Drain-exceeds-budget: one dispatch at 10.0s + shutdown(5000) → dispatch is cancelled at the 5s mark + `event=perspective_dispatch_drain_exceeded cancelled_count=1` log captured
  - Dispatch-after-shutdown: call `shutdown(5000).await()` (or `runTest` + advance past 5_000) — verify `scope.coroutineContext[Job]?.isActive == false` BEFORE the late `dispatch(...)` call; THEN call `dispatch(parentContext) { perspectiveModerator.moderate(POST, U, content) }` → assert return is `null`, no exception thrown, `event=perspective_dispatch_after_shutdown` log captured

## 9. Wire async dispatch at `posts` and `post_replies` INSERT call sites (with OTel context propagation)

- [ ] 9.1 Add `perspectiveDispatcherScope.dispatch(coroutineContext) { perspectiveModerator.moderate(POST, newPostId, content) }` to [`backend/ktor/.../post/CreatePostService.kt`](../../../backend/ktor/src/main/kotlin/id/nearyou/app/post/CreatePostService.kt) immediately AFTER the INSERT commit, BEFORE the service returns. Passing `coroutineContext` propagates the OTel trace context per design.md Decision 13
- [ ] 9.2 Add the analogous dispatch invocation with `REPLY` target type to [`backend/ktor/.../engagement/ReplyService.kt`](../../../backend/ktor/src/main/kotlin/id/nearyou/app/engagement/ReplyService.kt) immediately AFTER the INSERT commit
- [ ] 9.3 Add a structural source-scan test `PerspectiveDispatchCallSiteTest` (mirroring `PostCreationCallOrderTest` / `ChatModerationCallOrderTest`) asserting: (a) `perspectiveDispatcherScope.dispatch` invocation appears AFTER the canonical INSERT call in `CreatePostService.kt` and `ReplyService.kt`; (b) chat send service files (`backend/ktor/.../chat/*Service.kt`) do NOT contain `perspectiveDispatcherScope.dispatch` OR `perspectiveModerator.moderate` invocations
- [ ] 9.4 Verify chat handler explicitly does NOT carry the Layer 3 dispatch (deferred per design Open Question 1) — the test from 9.3 covers this structurally

## 10. End-to-end integration tests

- [ ] 10.1 Boot `Application.module()` in `testApplication { ... }` with Koin overrides binding `RecordingPerspectiveClient` (returning a stage-able `PerspectiveScore`) and a real Postgres + Redis
- [ ] 10.2 `POST /api/v1/posts` with content that the recording client scores at 0.92 → assert HTTP 201 returned within baseline latency, then assert (with bounded wait) that `posts.is_auto_hidden = TRUE` and a `moderation_queue` row exists with `trigger = 'perspective_api_high_score'`
- [ ] 10.3 `POST /api/v1/posts` with content that the recording client scores at 0.70 → assert `posts.is_auto_hidden = FALSE` (unchanged) AND a `moderation_queue` row exists with `trigger = 'perspective_api_high_score'`
- [ ] 10.4 `POST /api/v1/posts` with content that the recording client scores at 0.50 → assert no DB writes from Layer 3 (only the post row from the INSERT exists; no queue row)
- [ ] 10.5 `POST /api/v1/posts` with the recording client configured to throw an `HttpStatusException(503)` → assert post row exists, queue row does NOT, and a Sentry WARN `failure_kind = "http_5xx"` was captured
- [ ] 10.6 `POST /api/v1/posts` with the recording client configured to suspend 600ms → assert dispatch times out, no DB writes from Layer 3, Sentry WARN `failure_kind = "timeout"` captured
- [ ] 10.7 `POST /api/v1/posts` with `perspective_api_enabled = FALSE` in the test Remote Config fake → assert `RecordingPerspectiveClient.analyzeCalls.size == 0` (no dispatch invoked); no Sentry event
- [ ] 10.8 Same shape of tests against `POST /api/v1/posts/{post_id}/replies`
- [ ] 10.9 Layer 1 reject path → confirm `RecordingPerspectiveClient.analyzeCalls.size == 0` for the request (Layer 1 short-circuits before the INSERT)
- [ ] 10.10 Layer 2 flag path → confirm `RecordingPerspectiveClient.analyzeCalls.size == 1` (Layer 2 doesn't block Layer 3); resulting `moderation_queue` rows: ONE with `trigger='uu_ite_keyword_match'`, ONE with `trigger='perspective_api_high_score'` (assuming Perspective also high-scored the same content)

## 11. Privacy / no-content-on-Sentry tests

- [ ] 11.1 Sentry-capture fixture that intercepts emitted events. For each `event = "perspective_*"` event captured during test runs, **serialize the SentryEvent to its on-the-wire JSON via Sentry's `JsonSerializer.serialize(event, writer)`** (NOT just inspect specific fields like `event.message` / `event.tags`) AND substring-search the SERIALIZED JSON blob for `"sentinel-content-DO-NOT-LEAK"` when test content is exactly that sentinel. This catches content that leaked into `event.extra`, `event.contexts`, `event.fingerprint`, etc., not just the obvious top-level fields
- [ ] 11.2 Same approach for raw per-attribute scores: serialize the captured event AND assert raw float literals (e.g., `"0.85"`, `"0.40"`) do NOT appear in the JSON blob — only `score_bucket` values (`"low"` / `"mid"` / `"high"`) appear
- [ ] 11.3 Assert at most ONE Sentry event is emitted per `moderate(...)` invocation (no event spam during cascade) — count captured events per test invocation

## 12. Static-source-scan defense

- [ ] 12.1 The call-order test at task 9.3 (`PerspectiveDispatchCallSiteTest`) covers the post + reply handler call-order assertion. No separate test needed
- [ ] 12.2 Add new test class `lint/detekt-rules/.../PerspectiveEndpointLeakageScanTest.kt` (Kotest StringSpec) that walks `backend/ktor/src/` and asserts zero references to the literal strings `"commentanalyzer.googleapis.com"` AND `"v1alpha1/comments:analyze"` (encapsulation invariant for the Perspective endpoint URL). **Why a separate test class, NOT extending `vendor-sdk-leakage-scan`**: the canonical [`openspec/specs/vendor-sdk-leakage-scan/spec.md`](../../../specs/vendor-sdk-leakage-scan/spec.md) scans `import` lines for forbidden Kotlin package prefixes — Perspective doesn't have a Kotlin SDK package (we use the Ktor HTTP client + a URL string), so the canonical scan's import-line discipline doesn't fit. The vendor-sdk-leakage-scan spec § "Substring matches inside string literals are NOT flagged" explicitly excludes string-literal scanning; URL-substring isolation is a different scan shape. Per `openspec/specs/vendor-sdk-leakage-scan/spec.md:52` ("The list MAY be extended..."), extending the canonical list is opt-in not required, and only applies when a new vendor introduces a Kotlin import prefix to forbid — Perspective doesn't. No `vendor-sdk-leakage-scan` MODIFIED delta needed in this change

## 13. Canonical-doc amendments shipped in this change

These docs are amended IN THIS CHANGE because the change introduces the surface they describe (new Remote Config flags, new attribute-aggregation interpretation, new operational behaviors). Per the canonical-docs reconciliation pass, NEW surfaces are documented in canonical docs as part of the change that introduces them — not deferred to a follow-up.

- [ ] 13.1 Update [`docs/05-Implementation.md:1042`](../../../docs/05-Implementation.md) § "Reserved / DESIGN" Firebase Remote Config flags list to add: `perspective_api_high_score_threshold` (number, default 0.8) and `perspective_api_flag_threshold` (number, default 0.6) with one-line descriptions
- [ ] 13.2 Update [`docs/06-Security-Privacy.md:160-166`](../../../docs/06-Security-Privacy.md) Layer 3 contract to canonicalize the attribute aggregation: append a clarifying sentence after line 162 (`Attributes: TOXICITY, ...`): "The score compared against the thresholds is the per-call max across all four attributes: `score = max(toxicity, severeToxicity, identityAttack, threat)`."
- [ ] 13.3 Update [`docs/06-Security-Privacy.md:160-166`](../../../docs/06-Security-Privacy.md) to canonicalize the threshold inclusivity convention: clarify that `score > 0.8` is strictly greater than (boundary value `0.80` falls into the FlagOnly band), `score > 0.6` strictly greater than for FlagOnly entry, `score ≤ 0.6` returns NoAction
- [ ] 13.4 Update [`docs/06-Security-Privacy.md`](../../../docs/06-Security-Privacy.md) Privacy section to note that user content is sent to a third-party (Google Perspective) for classification — flag as a Pre-Launch Privacy Policy update task (not a Privacy Policy text change in THIS change, but the docs surface needs to call out the dependency for the Pre-Launch checklist)
- [ ] 13.5 Update [`docs/05-Implementation.md:22`](../../../docs/05-Implementation.md) § Secrets list to add `perspective-api-key` (live list, since the slot will be provisioned in production once tag-deployed; mirror the `firebase-admin-sa` shape — env-namespaced via `staging-perspective-api-key` for staging)

## 14. Pre-archive staging smoke (operator-driven; runtime path)

- [ ] 14.1 Run `gh workflow run deploy-staging.yml --ref text-moderation-perspective-api-layer` to deploy the branch to staging
- [ ] 14.2 Tail the deploy run; confirm green
- [ ] 14.3 Verify `staging-perspective-api-key` secret slot is provisioned in GCP Secret Manager (operator-only step; cannot be done by the agent)
- [ ] 14.4 Verify `perspective_api_enabled = TRUE` in staging Firebase Remote Config (already seeded per Pre-Phase 1 §18; idempotent verification)
- [ ] 14.5 Smoke: write a staging script `dev/scripts/smoke-text-moderation-perspective-api-layer.sh` that creates a test user, posts a high-toxicity message, polls the staging DB for `is_auto_hidden = TRUE` AND a queue row with `trigger = 'perspective_api_high_score'`
- [ ] 14.6 Confirm baseline latency on `POST /api/v1/posts` did not regress (compare p95 against the pre-Layer-3 baseline from the staging dashboard); document delta in the PR body
- [ ] 14.7 Confirm Sentry surfaces no unexpected ERROR events post-smoke (only the expected WARN events from the timeout/failure test cases, if any were exercised)
- [ ] 14.8 Tick Section 14 in this `tasks.md` (mark each item complete) and proceed to archive

## 15. Archive

- [ ] 15.1 Run `openspec archive text-moderation-perspective-api-layer` (locally)
- [ ] 15.2 Verify the resulting `openspec/specs/text-moderation-perspective-api-layer/spec.md` (NEW) is well-formed and `openspec/specs/content-moderation-keyword-lists/spec.md` (MODIFIED) absorbs the Layer 3 sibling clause
- [ ] 15.3 Run `openspec validate --specs text-moderation-perspective-api-layer --strict` AND `openspec validate --specs content-moderation-keyword-lists --strict` — both green
- [ ] 15.4 Delete the `perspective-api-stopgap` entry from `FOLLOW_UPS.md` (this change closes that follow-up)
- [ ] 15.5 Update `docs/08-Roadmap-Risk.md` Phase 2 §16 to indicate "shipped in change `text-moderation-perspective-api-layer`" (mirror the format used by other shipped Phase 2 items)
- [ ] 15.6 Commit the archive on the same branch (`docs(openspec): archive text-moderation-perspective-api-layer and sync specs`); PR title may need a final retitle to `feat(moderation): text-moderation-perspective-api-layer` if it isn't already
- [ ] 15.7 Final squash-merge to `main` after CI is green
