## 1. Setup

- [x] 1.1 Confirm working tree is on the `rate-limit-infrastructure-success-path-log` branch (created by `/next-change` Phase C). `git rev-parse --abbrev-ref HEAD` returns `rate-limit-infrastructure-success-path-log`.
- [x] 1.2 Confirm the proposal commit (`docs(openspec): propose rate-limit-infrastructure-success-path-log`) plus any review-loop fix commits are the only commits ahead of `origin/main` at the start of implementation. Subsequent feat commits land on this same branch under the one-PR-per-change convention.
- [x] 1.3 Confirm `openspec validate rate-limit-infrastructure-success-path-log --strict` passes (re-run if any artifact was edited during proposal-review).

## 2. Implementation — `RedisRateLimiter.admit()` success-path log

- [x] 2.1 Edit [`infra/redis/src/main/kotlin/id/nearyou/app/infra/redis/RedisRateLimiter.kt`](../../../infra/redis/src/main/kotlin/id/nearyou/app/infra/redis/RedisRateLimiter.kt) — locate `admit()` lines 112-164 (existing private method).
- [x] 2.2 In the success branch (where `result[0] == 1L`, currently returning `RateLimiter.Outcome.Allowed(remaining = value.toInt())`): before the `return`, emit two distinct `logger.debug` calls under `if (telemetryUserId != null) { ... } else { ... }`:
  - User-axis (inside `if`): `logger.debug("event=rate_limit_check user_id={} key={} outcome=allowed remaining={}", telemetryUserId, key, value)`
  - Key-axis (inside `else`): `logger.debug("event=rate_limit_check key={} outcome=allowed remaining={}", key, value)`
- [x] 2.3 In the rate-limited branch (where `result[0] != 1L`, currently returning `RateLimiter.Outcome.RateLimited(retryAfterSeconds = value)`): before the `return`, emit two distinct `logger.debug` calls under the same `if (telemetryUserId != null) { ... } else { ... }` conditional:
  - User-axis (inside `if`): `logger.debug("event=rate_limit_check user_id={} key={} outcome=rate_limited retry_after_seconds={}", telemetryUserId, key, value)`
  - Key-axis (inside `else`): `logger.debug("event=rate_limit_check key={} outcome=rate_limited retry_after_seconds={}", key, value)`
- [x] 2.4 The `else` branch placement (Decisions 2 + 5 in design.md + spec scenario "Source-level structural conditional enforces else-branch placement") is structurally enforced by the test in §3.10 below. A copy-paste-error refactor that emits BOTH lines unconditionally outside the if/else (e.g., user-axis line followed by key-axis line, no else) would silently leak `user_id=` on key-axis calls — the structural test is the canonical defense.
- [x] 2.5 The fail-soft early-return path (`sync()` returns null at line 118 → returns `Allowed(remaining = capacity)`) MUST NOT emit the new log per spec scenario "Fail-soft early-return path emits no rate_limit_check log". Confirm by reading the diff: the new `logger.debug` calls land inside the `try { ... }` block (after `evalShaWithFallback`), NOT before the `sync()` null-check.
- [x] 2.6 Confirm the existing failure-path log block at lines 145-160 remains unchanged (the new success-path log additions are purely additive — do not refactor the existing conditional or extract a shared helper).
- [x] 2.7 Confirm no other call sites (`releaseMostRecent`, `evalShaWithFallback`, `sync`) are touched by this change. The file diff should be confined to the `admit()` body's `try { ... }` block. (Per design.md Decision 4: `releaseMostRecent` symmetric coverage is out of scope.)

## 3. Tests — `RedisRateLimiterTelemetryTest` extension (7 success-path scenarios)

- [x] 3.1 Edit [`infra/redis/src/test/kotlin/id/nearyou/app/infra/redis/RedisRateLimiterTelemetryTest.kt`](../../../infra/redis/src/test/kotlin/id/nearyou/app/infra/redis/RedisRateLimiterTelemetryTest.kt) — extend the existing `StringSpec` class (do NOT create a new test class; the appender helper extension lives alongside the existing `captureWarnings`).
- [x] 3.2 Add a new helper `captureDebugAndWarnings(block: () -> Unit): List<String>` inside the test class body, mirroring the existing `captureWarnings` helper but ALSO saving + lowering + restoring the `RedisRateLimiter` logger level. Implementation pattern:
  ```kotlin
  fun captureDebugAndWarnings(block: () -> Unit): List<String> {
      val logger = LoggerFactory.getLogger(RedisRateLimiter::class.java) as ch.qos.logback.classic.Logger
      val originalLevel = logger.level  // Capture configured level (may be null = inherited)
      val appender = ListAppender<ILoggingEvent>()
      appender.start()
      logger.addAppender(appender)
      logger.level = ch.qos.logback.classic.Level.DEBUG
      try {
          block()
      } finally {
          // Exception-safe restore: try/catch so a teardown error does not mask the original failure.
          try { logger.level = originalLevel } catch (e: Exception) { /* best-effort */ }
          try { logger.detachAppender(appender) } catch (e: Exception) { /* best-effort */ }
          appender.stop()
      }
      return appender.list
          .filter { it.level == Level.DEBUG || it.level == Level.WARN }
          .map { it.formattedMessage }
  }
  ```
  The teardown MUST be exception-safe (test fixture init failure, assertion failure, test timeout cannot leak DEBUG into subsequent tests in the same JVM).
- [x] 3.3 **Test fixture: real-Redis connection mechanism.** Use the same shape as [`RedisRateLimiterIntegrationTest`](../../../infra/redis/src/test/kotlin/id/nearyou/app/infra/redis/RedisRateLimiterIntegrationTest.kt) — connect to `redis://localhost:6379` (CI `redis:7-alpine` service container) inside a database-tagged scenario; pre-clear via UUID-suffixed test keys per `RedisRateLimiterIntegrationTest`'s `freshKey(scope)` precedent. **MUST NOT use `FLUSHDB`** — it would nuke sibling integration tests' fixtures since Kotest may run database-tagged specs in the same JVM. Test-key shape: `{scope:rate_test_day}:{user:<UUID-randomUUID>}` for user-axis, `{scope:test_health}:{ip:<UUID-suffixed-hex16>}` for key-axis (NOT `{scope:rate_like_day}` — using the production scope name in tests would mislead future maintainers about what's being tested).
- [x] 3.4 Add scenario "Success-path admit emits rate_limit_check log for tryAcquire with user_id":
  - Generate `userId = UUID.randomUUID()`, fresh test key with UUID-suffix.
  - Invoke `tryAcquire(userId, key, capacity = 10, ttl = Duration.ofSeconds(60))` against a fresh bucket inside `captureDebugAndWarnings { ... }`.
  - Assert exactly one captured event has formatted message containing all of `event=rate_limit_check`, `user_id=<userId.toString()>`, `key=<the-passed-key>`, `outcome=allowed`, `remaining=9`. (Format-arg-ordering-resistant — asserting both `user_id=<U>` AND `key=<actual-key>` catches a future maintainer who swaps the two args in the call site.)
- [x] 3.5 Add scenario "Success-path admit emits rate_limit_check log for tryAcquireByKey WITHOUT user_id":
  - Same context, key-axis test key.
  - Invoke `tryAcquireByKey(key, capacity = 60, ttl = Duration.ofSeconds(60))`.
  - Assert exactly one captured event has formatted message containing `event=rate_limit_check`, `key=<the-passed-key>`, `outcome=allowed`, `remaining=59`.
  - **Strong user_id-absence check**: assert via `forEach { line -> line.contains("user_id=") shouldBe false }` shape (mirroring [`RedisRateLimiterTelemetryTest:97-100`](../../../infra/redis/src/test/kotlin/id/nearyou/app/infra/redis/RedisRateLimiterTelemetryTest.kt) precedent) that EVERY captured DEBUG event omits `user_id=`. Stronger than "exactly one event without user_id" — catches future refactors that emit a second log line unconditionally.
  - Assert no captured event contains `00000000-0000-0000-0000-000000000000`.
- [x] 3.6 Add scenario "User-axis rate-limited admit emits rate_limit_check with retry_after_seconds":
  - Same context, user-axis test key.
  - Invoke `tryAcquire(userId, key, capacity = 1, ttl = Duration.ofSeconds(60))` twice sequentially. The first returns `Allowed`, the second returns `RateLimited`.
  - Assert the second admit's captured event contains `event=rate_limit_check`, `user_id=<userId>`, `key=<key>`, `outcome=rate_limited`, `retry_after_seconds=<int >= 1>`.
  - Additionally assert the captured `retry_after_seconds` integer value matches the second admit's returned `Outcome.RateLimited.retryAfterSeconds` byte-for-byte (no ±tolerance — they travel through the same admit() return statement).
- [x] 3.7 Add scenario "Key-axis rate-limited admit emits rate_limit_check WITHOUT user_id with retry_after_seconds":
  - Same context, key-axis test key.
  - Invoke `tryAcquireByKey(key, capacity = 1, ttl = Duration.ofSeconds(60))` twice sequentially.
  - Assert the second admit's captured event contains `event=rate_limit_check`, `key=<key>`, `outcome=rate_limited`, `retry_after_seconds=<int >= 1>`.
  - Apply the same `forEach` user_id-absence check from §3.5 across all captured events from this admit.
  - This covers the canonical `/health` 60-req/min anti-scrape call site behavior at saturation — the operator's hot-IP triage path.
- [x] 3.8 Add scenario "Fail-soft early-return path emits no rate_limit_check log":
  - Use the existing `unreachableUrl = "redis://127.0.0.1:1"` precedent (no real Redis needed; this scenario is NOT database-tagged).
  - Invoke `tryAcquireByKey(key = "{scope:test_fail_soft}:{ip:abc123def4567890}", capacity = 60, ttl = Duration.ofSeconds(60))` inside `captureDebugAndWarnings { ... }`.
  - Assert the admit returns `Outcome.Allowed(remaining = 60)` (the synthetic fail-soft outcome equal to capacity).
  - Assert ZERO captured events contain `event=rate_limit_check` (the new log MUST NOT fire on the early-return path; the existing `event=redis_connect_failed` WARN is the operator signal).
  - Assert AT LEAST ONE captured event contains `event=redis_connect_failed` (preserves the existing fail-soft contract).
- [x] 3.9 Add scenario "DEBUG-filtered emission contract — log is opt-in, never load-bearing":
  - Database-tagged (real Redis), key-axis test key.
  - Use a variant helper that attaches the appender at `Level.INFO` (NOT DEBUG) — the new `event=rate_limit_check` DEBUG entries are filtered out by the appender's level.
  - Invoke `tryAcquireByKey(key, capacity = 10, ttl = Duration.ofSeconds(60))`.
  - Assert the admit returns `Allowed(remaining = 9)` (the runtime contract is unchanged regardless of log level).
  - Assert ZERO `event=rate_limit_check` entries captured (proves the log is opt-in via DEBUG enablement; never load-bearing).
- [x] 3.10 Extend the existing structural-source test ("RedisRateLimiter source has admit-time log conditional on telemetryUserId") to use a regex match enforcing the if/else branch placement:
  - Read the source via the existing working-directory fallback: `java.io.File(sourcePath).takeIf { it.exists() }?.readText() ?: java.io.File("../../$sourcePath").readText()`.
  - Add `Regex.containsMatchIn` assertion for the success-path allowed branch:
    ```kotlin
    val successAllowedRegex = Regex(
        """if \(telemetryUserId !=\s*null\) \{[\s\S]*?event=rate_limit_check user_id=\{\} key=\{\} outcome=allowed[\s\S]*?\} else \{[\s\S]*?event=rate_limit_check key=\{\} outcome=allowed[\s\S]*?\}"""
    )
    successAllowedRegex.containsMatchIn(source) shouldBe true
    ```
  - Add an analogous regex for the rate-limited variant matching `outcome=rate_limited retry_after_seconds=\{\}`.
  - PRESERVE the existing failure-path substring assertions (`event=redis_acquire_failed user_id={} key={} reason={} message={} fail_soft=true` and the key-only variant) AND the existing conditional substring (`if (telemetryUserId != null)`).
  - This catches BOTH (a) consolidated single-format-string refactor (regex's distinct user-axis and key-axis format strings won't both match a single statement) AND (b) copy-paste-error refactor that stacks both lines outside the conditional (regex's `if/else` enforcement won't match unconditional emission).
- [x] 3.11 Add scenario "Cross-test pollution prevented via effectiveLevel comparison" — list LAST in the `StringSpec` block (after all DEBUG-bumping scenarios). Use `effectiveLevel` not `level`:
  - Read `(LoggerFactory.getLogger(RedisRateLimiter::class.java) as ch.qos.logback.classic.Logger).effectiveLevel`.
  - Assert it matches the value observed BEFORE any DEBUG-bumping scenario ran (typically `Level.TRACE` inherited from `<root level="trace">` in `logback.xml`, OR whatever `logback-test.xml` overrides for the test classpath).
  - Note: `level` returns the configured level which may be `null` for inherited-from-root loggers; `effectiveLevel` always resolves via the Logback inheritance chain — `level == null` is NOT a passing condition for "level was restored," so the assertion MUST use `effectiveLevel`.
- [x] 3.12 Verify the final test class structure: 3 existing scenarios (`tryAcquire fail-soft log includes the connect-failure event`, `tryAcquireByKey fail-soft log does NOT include user_id field`, `RedisRateLimiter source has admit-time log conditional on telemetryUserId`) + 8 new scenarios from §3.4-§3.11 = 11 total scenarios. Existing 3 are preserved; new 8 added per the spec's enumerated test-coverage requirement.

## 4. Local verification

- [x] 4.1 Run `./gradlew ktlintCheck` — must pass.
- [x] 4.2 Run `./gradlew detekt` — must pass.
- [x] 4.3 Pre-condition: ensure local Redis is running for database-tagged tests (`docker compose up -d redis` from `dev/docker-compose.yml`, OR connect to a CI-equivalent local Redis on `localhost:6379`). Without Redis up, the new database-tagged scenarios silently skip per the existing precedent in `RedisRateLimiterIntegrationTest:42-58` — verifying locally requires Redis running.
- [x] 4.4 Run `./gradlew :infra:redis:test` — all telemetry test scenarios + existing fail-soft test + integration test must pass.
- [x] 4.5 Run `./gradlew :backend:ktor:test` — confirm no downstream test breaks (no rate-limit call site asserts on the absence of the new log events; the `:backend:ktor` test lane must remain green).
- [x] 4.6 Run `./gradlew :lint:detekt-rules:test` — confirm no Detekt rule regression (the new `event=rate_limit_check` log additions don't introduce new key-shape violations or hash-tag-rule false positives).
- [x] 4.7 Run the combined pre-push gate: `./gradlew ktlintCheck detekt :backend:ktor:test :infra:redis:test :lint:detekt-rules:test` — all green before pushing the implementation commits.

## 5. Spec validate

- [x] 5.1 Run `openspec validate rate-limit-infrastructure-success-path-log --strict` — must pass with zero failures.
- [x] 5.2 Run `openspec status --change rate-limit-infrastructure-success-path-log` — confirm `applyRequires` artifacts (`tasks`) status is `done`.

## 6. Pre-archive staging smoke (re-run §6.5 from `rate-limit-ip-hashing`)

- [ ] 6.1 Trigger a manual staging deploy on the change branch: `gh workflow run deploy-staging.yml --ref rate-limit-infrastructure-success-path-log` (per the `/opsx:apply` skill's pre-archive smoke convention codified post-`reply-rate-limit`).
- [ ] 6.2 Monitor the deploy run via `gh run list --workflow=deploy-staging.yml --limit=1` until it reports green. Capture the deployed Cloud Run revision name from the run's logs (or via `gcloud run services describe nearyou-api-staging --region=asia-southeast2 --format='value(status.latestReadyRevisionName)'`).
- [ ] 6.3 No logger-level override needed. [`backend/ktor/src/main/resources/logback.xml`](../../../backend/ktor/src/main/resources/logback.xml) currently runs `<root level="trace">` with no `RedisRateLimiter`-specific pin, so DEBUG entries from `RedisRateLimiter` are emitted to the ConsoleAppender → Cloud Run stdout → Cloud Logging by default. The smoke window will see the new `event=rate_limit_check` entries without any config flip. (See design.md Decision 1 + Open Question Q3 for the broader logback posture discussion — pinning to INFO as a defensive default is intentionally deferred to a separate logback-refactor change.)
- [ ] 6.4 Issue 5 sequential `GET https://api-staging.nearyou.id/health/live` requests from a single test client (the same shape as `rate-limit-ip-hashing` §6.2). Capture the request timestamps in UTC.
- [ ] 6.5 **Wait 90 seconds** after §6.4 completes before running the Cloud Logging filter — Cloud Run stdout → Cloud Logging ingestion lag on staging can run 30-90s. Then run the filter `resource.type="cloud_run_revision" AND resource.labels.service_name="nearyou-api-staging" AND textPayload:"event=rate_limit_check"` over the smoke window (use `textPayload` not `jsonPayload.message` — the Logback ConsoleAppender writes plain-text patterns to stdout, not structured JSON, per the existing `<pattern>%d{YYYY-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>` shape in logback.xml). Capture all matching entries. **Retry-on-empty pattern**: if zero entries match on first query, wait another 30s and retry up to 3 attempts before declaring failure (mirrors the smoke retry pattern from PR #74's §6.5).
- [ ] 6.6 **Assert** (the §6.5 verification gap closure):
  - At least 5 entries match (one per smoke request; Cloud Run probe traffic carrying `User-Agent: GoogleHC|kube-probe` bypasses rate-limit per the `health-check` capability, so smoke-only entries are isolated).
  - Each captured `key=` value matches the regex `\{scope:health\}:\{ip:[0-9a-f]{16}\}` — hashed-IP form, no raw IPv4 dotted-quad.
  - Each captured entry's text does NOT contain `user_id=` (key-axis call, no user_id field).
  - Each captured entry's `outcome=` is `allowed` (the 60-req/min cap is not exceeded by 5 requests).
  - The `remaining=` field decrements monotonically across the smoke (e.g., 59 → 58 → 57 → 56 → 55 if all five hit the same hashed-IP bucket — confirming hash determinism).
  - **Note**: this smoke proves the KEY-AXIS contract end-to-end (the `/health/*` call site is the only key-axis live caller today). The user-axis emission contract is verified by §3.4 + §3.6 integration tests — they are NOT covered by this staging smoke and that's by design (no user-axis live call site today exercises the path with the kind of stable test conditions a smoke needs).
- [ ] 6.7 Paste smoke evidence into a PR comment: timestamp window, 5+ captured `key=` values (hashed form), `remaining=` decrement series, and explicit "no `user_id=` in any entry" attestation. Link the PR comment from the Section 7 archive task body.
- [ ] 6.8 If 6.6 fails (raw IP appears, OR `user_id=` leaks on key-axis, OR fewer than 5 entries match after the retry-on-empty pattern): roll back the implementation commits on the branch via `git revert <sha>`, debug, and retry. Do NOT proceed to archive until 6.6 is fully green.
- [ ] 6.9 **Smoke noise-floor check** (sanity): the §6.5 filter `textPayload:"event=rate_limit_check"` is specific enough to isolate the new log entries from the broader `<root level="trace">` Cloud Logging stream. Verify by running the filter WITHOUT issuing the §6.4 smoke requests — should return 0 entries from the smoke window (confirms the filter only matches new entries, not background app DEBUG/TRACE noise from Lettuce / Netty / JJWT).

## 7. Archive

- [ ] 7.1 Run `openspec archive rate-limit-infrastructure-success-path-log` locally — verify the move from `openspec/changes/` → `openspec/changes/archive/` and the spec sync into `openspec/specs/rate-limit-infrastructure/spec.md`. **Expected output shape: `+ 2, ~ 0, - 0, → 0`** — two requirements ADDED at the capability level (the change uses ADDED only; zero MODIFIED, zero REMOVED, zero RENAMED). If the actual output differs, that's a flag — the most likely cause is an inadvertent spec-delta operator change during proposal-review. Empirically verify the archive output matches before pushing the archive commit.
- [ ] 7.2 Run `openspec validate --specs rate-limit-infrastructure --strict` — confirm the post-archive capability spec is clean. **Expected scenario count: 56** (parent capability currently has 46 scenarios across 8 requirements; this change ADDs 10 scenarios across 2 requirements; post-archive: 56 scenarios across 10 requirements). If `--strict` reports a different count, recount the parent + delta and update this expectation OR debug the discrepancy.
- [ ] 7.3 Stage the archive changes (move + spec sync) and commit on the SAME branch with `chore(openspec): archive rate-limit-infrastructure-success-path-log`.
- [ ] 7.4 Push the archive commit + update the PR body via `gh pr edit <pr> --body "$(cat <<'EOF' ... EOF)"` to the merge-ready shape per `openspec/project.md` § Change Delivery Workflow. Drop the in-progress framing; list final test counts + capability deltas + post-merge tasks (Section 8).
- [ ] 7.5 Final squash-merge to `main` after CI green — produces ONE commit on `main` carrying proposal + feat + archive.

## 8. Post-merge verification (production gate)

- [ ] 8.1 After staging auto-deploy from `main` post-squash, repeat the §6 smoke verification against `main`-deployed staging — confirm Cloud Logging surface remains clean (5+ `event=rate_limit_check` entries with hashed-IP form, no `user_id=` on key-axis, monotonic `remaining=` decrement). The post-merge smoke uses the same mechanism as §6 (no logger-level override needed — `<root level="trace">` ships DEBUG by default).
- [ ] 8.2 Production deployment is OUT OF SCOPE for this change (no production deploy workflow exists yet, mirroring `rate-limit-ip-hashing` §8.2 disposition). When production tag-deploy lands, this change MUST be already-merged AND the smoke from 8.1 MUST be green. Mark this task N/A in the archive commit body if production deploy still doesn't exist at archive time.
- [ ] 8.3 Delete the `rate-limit-infrastructure-success-path-key-log-drift` follow-up entry from `FOLLOW_UPS.md` after final squash-merge — its action items are now satisfied. Land the deletion as part of the next FOLLOW_UPS.md triage sweep (do NOT add a separate cleanup PR; the entry is the explicit "delete after this change merges" target).
