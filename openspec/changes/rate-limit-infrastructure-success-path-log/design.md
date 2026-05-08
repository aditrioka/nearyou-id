## Context

The `rate-limit-infrastructure` capability ships a Redis-backed `RateLimiter` whose admit-or-reject decision runs inside a single Lua sliding-window script. The Kotlin wrapper [`RedisRateLimiter.admit()`](../../../infra/redis/src/main/kotlin/id/nearyou/app/infra/redis/RedisRateLimiter.kt) emits structured WARN logs on three failure paths today: connection refusal at `sync()` (`event=redis_connect_failed`), EVALSHA exception during admit (`event=redis_acquire_failed`), and `releaseMostRecent` Redis exception (`event=redis_release_failed`). The user-axis vs key-axis split is already enforced on the EVALSHA failure log via an `if (telemetryUserId != null)` conditional that emits two distinct format strings — one with `user_id={}`, one without — at lines 145-160. There is no log emitted on the success branch.

The shipped `rate-limit-infrastructure/spec.md` § "tryAcquireByKey omits userId from telemetry" scenario reads as if every admit emits a structured log carrying `key=<key>`. The §6.5 pre-archive smoke verification of the just-merged [`rate-limit-ip-hashing`](../archive/2026-05-08-rate-limit-ip-hashing/tasks.md) change tried to filter Cloud Logging on `key=` over a healthy-state smoke window and found zero entries — confirming the spec/code drift. The smoke task closed with an explicit "structural invariant proven by static analysis, runtime verification path incomplete in healthy state" disposition AND filed the follow-up `rate-limit-infrastructure-success-path-key-log-drift` (the entry being promoted here, validated by the `/triage-follow-ups` PM sweep before `/next-change` invoked this proposal).

Operationally, the `key=` field is the rate-limit hot-key triage signal — operators investigating "why is this user/IP at capacity?" want to query the runtime surface, not unit-test source. The drift makes the spec scenario half-truthful (it's enforced on failure but vacuous on success), and ships the inherited smoke gap into every future operator's mental model. This change closes the drift.

## Goals / Non-Goals

**Goals:**

- Add a structured success-path log on `RedisRateLimiter.admit()` that operators can filter by `key=`, mirroring the failure-path log shape so the spec's "key= field present, no user_id on key-axis" invariant holds in steady state.
- Make the existing "tryAcquireByKey omits userId from telemetry" spec scenario non-vacuous in steady state. The scenario's WHEN clause "AND a structured log is emitted" is currently satisfied only on failure paths; this change ADDs requirements that guarantee log emission on success paths too, with the same key-axis user_id-absence invariant. The existing scenario itself is NOT literally modified — it now covers more cases by virtue of more emissions, not by edited wording.
- Add success-path test coverage to `RedisRateLimiterTelemetryTest` mirroring the existing failure-path coverage — three new scenarios (success/user-axis, success/key-axis, rate-limited).
- Re-run the `rate-limit-ip-hashing` §6.5 smoke against the post-amendment staging deploy as a Section 6 pre-archive verification step. Confirm the Cloud Logging filter `event=rate_limit_check AND key:"{ip:"` resolves to the expected hashed-IP shape with no `user_id=` and no raw-IP literal.

**Non-Goals:**

- Adding a success-path log to `releaseMostRecent`. It's an idempotent-release hook, not an admit-decision call site; no operator filter shape requires it. A future change MAY add symmetric coverage if a key-axis call site needs it.
- Changing the log level posture for failure paths. Existing `event=redis_acquire_failed` / `event=redis_connect_failed` stay at WARN; the new success-path log is DEBUG (rationale below).
- Adding metrics, traces, or other observability surfaces beyond the structured log. The `:infra:otel` Lettuce auto-instrumentation already captures EVALSHA spans with `db.statement` (verified during PR #74 §6.4 wire-log smoke); this change does not duplicate that surface.
- Modifying the Lua script, `RateLimiter` interface, `Outcome` shape, hash-tag key format, or `computeTTLToNextReset` helper. All four are downstream-stable contracts; the change is purely additive on the implementation side.
- Touching any rate-limit call site (`HealthRoutes`, `LikeService`, `ReplyService`, `ChatService`, `SearchService`, `ReportRateLimiter`). The call-site contract is unchanged.

## Decisions

### Decision 1: Log level → DEBUG (with current `<root level="trace">` in `logback.xml`, this fires by default)

**Current logback posture.** [`backend/ktor/src/main/resources/logback.xml`](../../../backend/ktor/src/main/resources/logback.xml) declares `<root level="trace">`. Specific noisy upstream loggers (`org.eclipse.jetty`, `io.netty`, `com.google.firebase`, `com.google.api.client`, `com.google.cloud`) are pinned to INFO; all other loggers — including `id.nearyou.app.infra.redis.RedisRateLimiter` — inherit root=trace. So DEBUG (and TRACE) emissions from app-side loggers ship to the Logback ConsoleAppender → Cloud Run stdout → Cloud Logging by default.

**Choice rationale: DEBUG, not INFO.** The success-path log fires on every admit attempt — at scale this is one log line per request that touches a rate-limited path (every `/api/v1/posts/*/like`, `/api/v1/posts/*/replies`, `/api/v1/chat/*/messages`, `/api/v1/search`, `/api/v1/reports`, every `/health/*` request, plus future Layer 1 / Layer 4 paths). DEBUG is the right semantic level for "per-call mechanical trace," which keeps the log shape compatible with future Logback refactors (e.g., flipping root to INFO + per-package DEBUG pins, OR adding a `<logger name="id.nearyou.app.infra.redis.RedisRateLimiter" level="INFO"/>` defensive pin) without re-deciding the spec.

**Cloud Logging volume implication.** With the current root=trace, the new log entries fire on every admit and ship to Cloud Logging unconditionally. At pre-launch traffic (zero real users, smoke tests + occasional CI synthetic load), this is essentially free — the cost is bounded by the §6 smoke window and any staging probe traffic. At production traffic, this becomes operationally expensive; the project's existing convention for high-frequency structured logs (e.g., the `:infra:otel` per-span attribute writes) is to rely on auto-instrumentation rather than explicit log emission. **Open Question Q3 (below) tracks the decision on whether to pin `RedisRateLimiter` to INFO as a defensive default before production traffic begins.**

**Rejected alternative**: INFO level + sampling at the logger layer. Sampling complicates incident-time triage (the 1-of-N entries an operator picks may not reflect the hot key) and adds a moving part (sample seed, fairness across keys) without materially improving the steady-state cost story over DEBUG.

**Rejected alternative**: invent a `KTOR_RATE_LIMIT_DEBUG` env var that flips the logger level at runtime. The current `application.conf` / logback.xml config does NOT support env-var-driven per-logger overrides, and adding that mechanism is scope creep beyond the single-capability MODIFY of this change. The realistic operator workflow is: edit `logback.xml` (or override via JMX/Logback runtime control) + redeploy the change branch to staging when triaging a live incident — same as any per-logger level adjustment in this codebase today.

**Trade-off accepted**: this change ships a new high-frequency log entry that fires by default with current logback config. If volume becomes a concern before production cutover, file a follow-up to pin the logger to INFO + document the operator triage workflow (raise to DEBUG via logback edit + redeploy). The follow-up is independent of this change's spec contract — the spec mandates the log shape; operational gating decisions live in logback.xml + runbooks, not in the capability spec.

### Decision 2: Format-string conditional shape → two distinct `logger.debug` calls under `if (telemetryUserId != null)`

Mirrors the failure-path conditional already at [`RedisRateLimiter.kt:145-160`](../../../infra/redis/src/main/kotlin/id/nearyou/app/infra/redis/RedisRateLimiter.kt) byte-for-byte. The user-axis branch emits `event=rate_limit_check user_id={} key={} outcome={} ...`; the key-axis branch emits `event=rate_limit_check key={} outcome={} ...` (no `user_id` placeholder, no field). This:

1. Matches the existing source-level structural test pattern in `RedisRateLimiterTelemetryTest` ("RedisRateLimiter source has admit-time log conditional on telemetryUserId"), which we extend with success-path equivalents — preserving a single regex shape across success and failure paths.
2. Makes the absence-of-`user_id=` invariant structurally visible in the source: the format string for the key-axis call literally has no `user_id` placeholder, so a future maintainer cannot accidentally pass a sentinel UUID through.
3. Avoids the alternative of a single format string with a placeholder eaten when null (`logger.debug("event=... user_id={} ...", telemetryUserId, ...)` with `telemetryUserId = null`) — that pattern silently emits `user_id=null` literal text in the formatted message, which is an info-leak smell (and trivially fails the existing telemetry test's "MUST NOT contain user_id=" assertion shape).

**Rejected alternative**: a private helper function `emitAdmitLog(level, outcome, key, telemetryUserId, value)` that internalizes the conditional. Cleaner at the call site but defeats the structural-test gate (the helper's source would no longer match the regex pattern, and the test would have to switch to byte-level asserts on the helper's body — net-negative for clarity).

### Decision 3: Test mechanism → connected real-Redis + DEBUG logger appender

The existing `RedisRateLimiterTelemetryTest` uses an unreachable URL (`redis://127.0.0.1:1`) to force the connect-failure path without a real Redis service container — the test runs in plain `:test` lane (not `:test --tag database`). For success-path coverage we need a connected Redis (admit must actually succeed), so the new scenarios MUST run under the `database` Kotest tag against the CI `redis:7-alpine` service container (the same one `RedisRateLimiterIntegrationTest` already uses).

To capture the new DEBUG log entries, we add a Logback `ListAppender<ILoggingEvent>` attached to the `RedisRateLimiter` logger with its level temporarily lowered to DEBUG inside each test scenario (mirroring the `captureWarnings` helper already in the file, but as `captureDebugAndWarnings`). The level is restored on teardown via a `try { ... } finally { ... }` block so cross-test pollution is avoided.

The success-path scenarios are added to the existing `RedisRateLimiterTelemetryTest` class (not a new test class) — they share the connect-failure context and structural-source-assertion machinery, and a new class would force code duplication for the appender helper.

**Rejected alternative**: mock `RedisRateLimiter` and assert against a stubbed-success Lua return value. Possible but breaks the "test the production wire-shape" property — the source-level structural test already guards against refactor-induced shape changes, but a runtime test against real Redis is the canonical verification for "does the format string in production code actually emit the expected text?"

**Rejected alternative**: route the new scenarios through `RedisRateLimiterIntegrationTest` (which already has real-Redis context). Practical but mixes concerns — the integration test is currently scoped to atomicity + capacity boundaries + Retry-After math + concurrent-coroutine assertions; adding telemetry-shape assertions there muddies its purpose. The telemetry test class is the right home.

### Decision 4: `releaseMostRecent` symmetric coverage → out of scope

`releaseMostRecent` is the idempotent-release hook for `tryAcquire` user-axis call sites that need to "un-do" a successful admit (e.g., the like-service's INSERT-ON-CONFLICT no-op path that releases the just-consumed slot when re-applying an existing like). It is NOT a rate-limit-decision surface, and operators investigating a rate-limit hot key do not query it.

The current shape emits `event=redis_release_failed` only on Redis exception (not on no-op success — `ZPOPMAX` returning nil for an empty/missing key is the documented no-op contract). Adding a success log would inflate Cloud Logging volume on every like INSERT-ON-CONFLICT no-op without operational benefit. If a future call site needs symmetric coverage (e.g., a `releaseMostRecentByKey` overload added for a key-axis call site that needs idempotent release), file a separate change — the surface and rationale will be different.

### Decision 5: Use ADDED requirements rather than MODIFY the existing interface scenario

The new success-path log shape could have been encoded as a MODIFIED Requirement on the existing `RateLimiter interface in :core:domain` requirement (which currently houses the "tryAcquireByKey omits userId from telemetry" scenario). That path requires copying the entire 64-line interface requirement block under `## MODIFIED Requirements` per OpenSpec convention.

This change instead uses ADDED Requirements at the implementation layer (`Redis-backed admit emits structured log on every outcome` + `Test coverage — Redis-backed admit telemetry success-path`). Rationale:

1. **The new behavior is implementation-side, not interface-side.** The `RateLimiter` interface contract is unchanged — `tryAcquire` and `tryAcquireByKey` still return the same `Outcome` shape with the same atomicity guarantees. What's changing is what the Redis-backed implementation logs as a side effect. Putting the scenarios at the implementation layer is more honest about what's being amended.
2. **The existing scenario's WHEN clause is robust.** "AND a structured log is emitted" doesn't constrain to success or failure. By ADDing requirements that mandate emission on success, the existing scenario becomes non-vacuous in steady state without rewriting it.
3. **MODIFIED's "copy the entire requirement block" overhead is real.** A future maintainer reviewing the archive's spec sync would see a 64-line MODIFIED block where 60 lines are unchanged from the parent — line noise vs signal. ADDED keeps the diff focused on what's actually new.

A separate scenario "Success-path log carries user_id only on user-axis call" is added (within the new ADDED requirement) for the positive user-axis case, complementing the existing negative key-axis scenario. This split keeps each scenario's WHEN/THEN focused on a single dimension (user-axis-positive vs key-axis-negative), more scannable than a compound assertion.

## Risks / Trade-offs

**Cloud Logging volume at DEBUG enabled.** Operationally low risk (DEBUG is opt-in). If an operator forgets to lower the level after triage, the Cloud Logging volume stays elevated until they remember. → Mitigation: the test scenarios document the activation mechanism explicitly; runbook update is deferred but a TODO comment in `application.conf` HOCON could surface the mechanism.

**Future `releaseMostRecent`-symmetric refactor pressure.** A future operator-driven request "we want to filter all Redis admit/release events" could push toward symmetric coverage that this change explicitly defers. → Mitigation: design.md decision 4 documents the deferral rationale; future change can revisit with a concrete operator need.

**Source-level structural test brittleness.** The existing test asserts on byte-level format strings (`source.contains("event=redis_acquire_failed user_id={} key={} reason={} message={} fail_soft=true")`). Adding two new format strings doubles the source-coupling surface. → Mitigation: the structural test is the canonical defense against silent-refactor `user_id=` re-introduction; the brittleness is intentional + documented in the test's KDoc. The added scenarios use the same shape so the test extension is mechanical.

**Test runtime cost.** Three new scenarios run against the CI `redis:7-alpine` service container (database-tagged), adding a few hundred ms to the `:infra:redis:test` lane. → Trade-off accepted: integration-grade telemetry coverage requires real Redis; the alternative (mocked Redis) doesn't catch format-string bugs.

**Sentry / log aggregator volume side-effect.** If Sentry's Logback integration ships DEBUG entries to Sentry breadcrumbs (default), enabling DEBUG on this logger could spike Sentry breadcrumb volume per request. → Mitigation: out of scope to verify mid-change; flagged here for operator awareness when the level is raised in a real incident. If it becomes a problem, narrow Sentry's breadcrumb level to INFO+ for this logger via Sentry config.

## Migration Plan

No migration needed — the change is purely additive at the runtime layer (new DEBUG log entries) and at the spec layer (amended scenarios). The deploy steps:

1. Land proposal + design + spec delta + tasks on the change branch (this PR opens via `/next-change`).
2. Implement the `admit()` log additions + extend `RedisRateLimiterTelemetryTest` (via `/opsx:apply` on the same branch).
3. Run `./gradlew ktlintCheck detekt :backend:ktor:test :infra:redis:test :lint:detekt-rules:test` locally before pushing each implementation commit.
4. Pre-archive: trigger a manual staging deploy on the change branch via `gh workflow run deploy-staging.yml --ref rate-limit-infrastructure-success-path-log` (per the `/opsx:apply` skill's pre-archive smoke convention). Run the §6.5 smoke (5 sequential `/health/live` requests + Cloud Logging filter on `event=rate_limit_check AND key:"{ip:"` over the smoke window). Confirm 5 hits, hashed-IP form, no `user_id=`, no raw IPv4. Tick the relevant Section 6 task.
5. Run `openspec validate --specs rate-limit-infrastructure --strict` (43 scenarios should pass post-archive).
6. `/opsx:archive` pushes the archive commit on the same branch; final squash-merge to `main` produces ONE commit on `main` carrying proposal + feat + archive.

**Rollback**: in the unlikely case the new log entries cause a Cloud Logging cost spike (e.g., a future change accidentally raises the logger level), revert the commit on `main` via a follow-up PR. The change is non-load-bearing for runtime correctness — reverting is safe.

## Open Questions

- **Q1 (Sentry breadcrumb volume)**: With root=trace currently shipping all DEBUG entries to the Logback ConsoleAppender, does Sentry's Logback integration capture `rate_limit_check` entries as breadcrumbs and flood the buffer? → Defer: verification cost is low and can be done at first incident or first deploy after this change merges; if the answer is "yes, breadcrumb buffer overflows," narrow Sentry's level for this logger to INFO+ via Sentry config (separate change). Not a blocker for shipping the log shape itself.
- **Q2 (runbook update)**: Should `docs/07-Operations.md` add a "How to read rate-limit triage logs" subsection alongside the existing Moderation Runbook + Deployment Runbook + Secret Management Runbook? → Defer to operator feedback. The current sections are battle-tested against real incidents (PR #54 lineage); pre-emptively adding an unverified subsection conflicts with the "ship the runtime shape, document at first use" pattern. If the §6 smoke surfaces friction, file a follow-up.
- **Q3 (defensive logger pin before production)**: Should this change ALSO pin `<logger name="id.nearyou.app.infra.redis.RedisRateLimiter" level="INFO"/>` in `logback.xml` so the new DEBUG entries are off by default — matching the future production posture without depending on a future logback refactor? → Defer: the current root=trace state means DEBUG is on across the entire app, not just this logger; pinning RedisRateLimiter alone is a half-measure. The real fix is a project-wide logback refactor (root=info + per-package pins) which is a separate concern from this change's capability spec. If volume becomes operationally painful in pre-launch, file a follow-up that scopes the logback refactor properly. Not a blocker for shipping this change.
