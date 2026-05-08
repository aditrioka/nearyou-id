## 1. Setup

- [ ] 1.1 Confirm working tree is on the `rate-limit-infrastructure-success-path-log` branch (created by `/next-change` Phase C). `git rev-parse --abbrev-ref HEAD` returns `rate-limit-infrastructure-success-path-log`.
- [ ] 1.2 Confirm the proposal commit (`docs(openspec): propose rate-limit-infrastructure-success-path-log`) is the only commit ahead of `origin/main` at the start of implementation. Subsequent feat commits land on this same branch under the one-PR-per-change convention.
- [ ] 1.3 Confirm `openspec validate rate-limit-infrastructure-success-path-log --strict` passes (re-run if any artifact was edited during proposal-review).

## 2. Implementation — `RedisRateLimiter.admit()` success-path log

- [ ] 2.1 Edit [`infra/redis/src/main/kotlin/id/nearyou/app/infra/redis/RedisRateLimiter.kt`](../../../infra/redis/src/main/kotlin/id/nearyou/app/infra/redis/RedisRateLimiter.kt) — locate `admit()` lines 112-164 (existing private method).
- [ ] 2.2 In the success branch (where `result[0] == 1L`, currently returning `RateLimiter.Outcome.Allowed(remaining = value.toInt())`): before the `return`, emit two distinct `logger.debug` calls under `if (telemetryUserId != null) { ... } else { ... }`:
  - User-axis: `logger.debug("event=rate_limit_check user_id={} key={} outcome=allowed remaining={}", telemetryUserId, key, value)`
  - Key-axis: `logger.debug("event=rate_limit_check key={} outcome=allowed remaining={}", key, value)`
- [ ] 2.3 In the rate-limited branch (where `result[0] != 1L`, currently returning `RateLimiter.Outcome.RateLimited(retryAfterSeconds = value)`): before the `return`, emit two distinct `logger.debug` calls under the same conditional:
  - User-axis: `logger.debug("event=rate_limit_check user_id={} key={} outcome=rate_limited retry_after_seconds={}", telemetryUserId, key, value)`
  - Key-axis: `logger.debug("event=rate_limit_check key={} outcome=rate_limited retry_after_seconds={}", key, value)`
- [ ] 2.4 Confirm the existing failure-path log block at lines 145-160 remains unchanged (the new success-path log additions are purely additive — do not refactor the existing conditional or extract a shared helper).
- [ ] 2.5 Confirm no other call sites (`releaseMostRecent`, `evalShaWithFallback`, `sync`) are touched by this change. The file diff should be confined to the `admit()` body.

## 3. Tests — `RedisRateLimiterTelemetryTest` extension

- [ ] 3.1 Edit [`infra/redis/src/test/kotlin/id/nearyou/app/infra/redis/RedisRateLimiterTelemetryTest.kt`](../../../infra/redis/src/test/kotlin/id/nearyou/app/infra/redis/RedisRateLimiterTelemetryTest.kt) — extend the existing class.
- [ ] 3.2 Add a new helper `captureDebugAndWarnings(block: () -> Unit): List<String>` inside the test class body, mirroring the existing `captureWarnings` helper but lowering the `RedisRateLimiter` logger level to `Level.DEBUG` inside a `try { ... } finally { restoreLevel() }` block. The teardown MUST be exception-safe (Kotest `try-finally` shape) so a test failure does not leak DEBUG into subsequent tests in the same JVM.
- [ ] 3.3 Add scenario 1 — "Success-path admit emits rate_limit_check log for tryAcquire with user_id":
  - Tag the test with `database` (Kotest `Tag` annotation or `Tags.include` per the existing precedent in `RedisRateLimiterIntegrationTest`).
  - Connect to `redis://localhost:6379` (the CI `redis:7-alpine` service container; same connection mechanism `RedisRateLimiterIntegrationTest` uses).
  - Pre-clear the test key via `FLUSHDB` (or scope to a UUID-suffixed key so cross-test cleanup is trivial).
  - Invoke `tryAcquire(userId = U, key = "{scope:rate_test_day}:{user:U}", capacity = 10, ttl = Duration.ofSeconds(60))` against a fresh bucket.
  - Capture DEBUG events; assert exactly one captured event has formatted message containing all of `event=rate_limit_check`, `user_id=<U>`, `key={scope:rate_test_day}:{user:U}`, `outcome=allowed`, `remaining=9`.
- [ ] 3.4 Add scenario 2 — "Success-path admit emits rate_limit_check log for tryAcquireByKey WITHOUT user_id":
  - Same context (database-tagged, real Redis, fresh bucket).
  - Invoke `tryAcquireByKey(key = "{scope:health}:{ip:abc123def4567890}", capacity = 60, ttl = Duration.ofSeconds(60))`.
  - Capture DEBUG events; assert exactly one captured event has formatted message containing `event=rate_limit_check`, `key={scope:health}:{ip:abc123def4567890}`, `outcome=allowed`, `remaining=59` AND assert the formatted message does NOT contain `user_id=` AND does NOT contain `00000000-0000-0000-0000-000000000000`.
- [ ] 3.5 Add scenario 3 — "Rate-limited path admit emits rate_limit_check with retry_after_seconds outcome":
  - Same context (database-tagged, real Redis, fresh bucket).
  - Invoke `tryAcquire(userId = U, key = "{scope:rate_test_day}:{user:U}", capacity = 1, ttl = Duration.ofSeconds(60))` twice sequentially. The first returns `Allowed`, the second returns `RateLimited`.
  - Capture DEBUG events; among the captured events, assert the second admit's event has formatted message containing `event=rate_limit_check`, `user_id=<U>`, `key={scope:rate_test_day}:{user:U}`, `outcome=rate_limited`, `retry_after_seconds=<int >= 1>`.
  - Additionally assert the captured `retry_after_seconds` value matches the second admit's returned `Outcome.RateLimited.retryAfterSeconds` byte-for-byte.
- [ ] 3.6 Extend the existing structural-source test ("RedisRateLimiter source has admit-time log conditional on telemetryUserId"):
  - Add assertions that the source contains the two new success-path format strings: `event=rate_limit_check user_id={} key={} outcome=` AND `event=rate_limit_check key={} outcome=` substrings.
  - Add equivalent assertions for the rate-limited variant: `event=rate_limit_check user_id={} key={} outcome=rate_limited retry_after_seconds={}` and `event=rate_limit_check key={} outcome=rate_limited retry_after_seconds={}`.
  - Preserve the existing failure-path assertions (`event=redis_acquire_failed user_id={} key={} reason={} message={} fail_soft=true` and `event=redis_acquire_failed key={} reason={} message={} fail_soft=true`).
  - Preserve the conditional assertion (`if (telemetryUserId != null)`).
- [ ] 3.7 Verify cross-test pollution is prevented — add a final scenario "Cross-test pollution prevented" that runs after the success-path scenarios and asserts the `RedisRateLimiter` logger's effective level is the original (e.g., INFO) value, not DEBUG. Use `LoggerFactory.getLogger(RedisRateLimiter::class.java).level` to read the level after the test scenarios complete.

## 4. Local verification

- [ ] 4.1 Run `./gradlew ktlintCheck` — must pass.
- [ ] 4.2 Run `./gradlew detekt` — must pass.
- [ ] 4.3 Run `./gradlew :infra:redis:test` — all telemetry test scenarios + existing fail-soft test + integration test must pass.
- [ ] 4.4 Run `./gradlew :backend:ktor:test` — confirm no downstream test breaks (no rate-limit call site asserts on the absence of the new log events; the `:backend:ktor` test lane must remain green).
- [ ] 4.5 Run `./gradlew :lint:detekt-rules:test` — confirm no Detekt rule regression (the new `event=rate_limit_check` log additions don't introduce new key-shape violations or hash-tag-rule false positives).
- [ ] 4.6 Run the combined pre-push gate: `./gradlew ktlintCheck detekt :backend:ktor:test :infra:redis:test :lint:detekt-rules:test` — all green before pushing the implementation commits.

## 5. Spec validate

- [ ] 5.1 Run `openspec validate rate-limit-infrastructure-success-path-log --strict` — must pass with zero failures.
- [ ] 5.2 Run `openspec status --change rate-limit-infrastructure-success-path-log` — confirm `applyRequires` artifacts (`tasks`) status is `done`.

## 6. Pre-archive staging smoke (re-run §6.5 from `rate-limit-ip-hashing`)

- [ ] 6.1 Trigger a manual staging deploy on the change branch: `gh workflow run deploy-staging.yml --ref rate-limit-infrastructure-success-path-log` (per the `/opsx:apply` skill's pre-archive smoke convention codified post-`reply-rate-limit`).
- [ ] 6.2 Monitor the deploy run via `gh run list --workflow=deploy-staging.yml --limit=1` until it reports green. Capture the deployed Cloud Run revision name from the run's logs (or via `gcloud run services describe nearyou-api-staging --region=asia-southeast2 --format='value(status.latestReadyRevisionName)'`).
- [ ] 6.3 No logger-level override needed. [`backend/ktor/src/main/resources/logback.xml`](../../../backend/ktor/src/main/resources/logback.xml) currently runs `<root level="trace">` with no `RedisRateLimiter`-specific pin, so DEBUG entries from `RedisRateLimiter` are emitted to the ConsoleAppender → Cloud Run stdout → Cloud Logging by default. The smoke window will see the new `event=rate_limit_check` entries without any config flip. (See design.md Decision 1 + Open Question Q3 for the broader logback posture discussion — pinning to INFO as a defensive default is intentionally deferred to a separate logback-refactor change.)
- [ ] 6.4 Issue 5 sequential `GET https://api-staging.nearyou.id/health/live` requests from a single test client (the same shape as `rate-limit-ip-hashing` §6.2). Capture the request timestamps in UTC.
- [ ] 6.5 In Cloud Logging, run the filter `resource.type="cloud_run_revision" AND resource.labels.service_name="nearyou-api-staging" AND textPayload:"event=rate_limit_check"` over the smoke window (use `textPayload` not `jsonPayload.message` — the Logback ConsoleAppender writes plain-text patterns to stdout, not structured JSON, per the existing `<pattern>%d{YYYY-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>` shape in logback.xml). Capture all matching entries.
- [ ] 6.6 **Assert** (the §6.5 verification gap closure):
  - At least 5 entries match (one per smoke request; Cloud Run probe traffic carrying `User-Agent: GoogleHC|kube-probe` bypasses rate-limit per the `health-check` capability, so smoke-only entries are isolated).
  - Each captured `key=` value matches the regex `\{scope:health\}:\{ip:[0-9a-f]{16}\}` — hashed-IP form, no raw IPv4 dotted-quad.
  - Each captured entry's text does NOT contain `user_id=` (key-axis call, no user_id field).
  - Each captured entry's `outcome=` is `allowed` (the 60-req/min cap is not exceeded by 5 requests).
  - The `remaining=` field decrements monotonically across the smoke (e.g., 59 → 58 → 57 → 56 → 55 if all five hit the same hashed-IP bucket — confirming hash determinism).
- [ ] 6.7 Paste smoke evidence into a PR comment: timestamp window, 5+ captured `key=` values (hashed form), `remaining=` decrement series, and explicit "no `user_id=` in any entry" attestation. Link the PR comment from the Section 7 archive task body.
- [ ] 6.8 If 6.6 fails (raw IP appears, OR `user_id=` leaks on key-axis, OR fewer than 5 entries match): roll back the implementation commits on the branch via `git revert <sha>`, debug, and retry. Do NOT proceed to archive until 6.6 is fully green.

## 7. Archive

- [ ] 7.1 Run `openspec archive rate-limit-infrastructure-success-path-log` locally — verify the move from `openspec/changes/` → `openspec/changes/archive/` and the spec sync into `openspec/specs/rate-limit-infrastructure/spec.md`. Expected output shape: `+ 0, ~ 1, - 0, → 0` (zero ADDED at the capability level — the change ADDED requirements get folded into the capability spec; zero MODIFIED at top-level since this change uses ADDED; one renumbered/inserted requirement section).
- [ ] 7.2 Run `openspec validate --specs rate-limit-infrastructure --strict` — confirm the post-archive capability spec is clean (the existing 43-scenario count grows by ~6 from this change's ADDED scenarios, target post-archive total around 49).
- [ ] 7.3 Stage the archive changes (move + spec sync) and commit on the SAME branch with `chore(openspec): archive rate-limit-infrastructure-success-path-log`.
- [ ] 7.4 Push the archive commit + update the PR body via `gh pr edit <pr> --body "$(cat <<'EOF' ... EOF)"` to the merge-ready shape per `openspec/project.md` § Change Delivery Workflow. Drop the in-progress framing; list final test counts + capability deltas + post-merge tasks (Section 8).
- [ ] 7.5 Final squash-merge to `main` after CI green — produces ONE commit on `main` carrying proposal + feat + archive.

## 8. Post-merge verification (production gate)

- [ ] 8.1 After staging auto-deploy from `main` post-squash, repeat the §6 smoke verification against `main`-deployed staging — confirm Cloud Logging surface remains clean (5+ `event=rate_limit_check` entries with hashed-IP form, no `user_id=` on key-axis, monotonic `remaining=` decrement). The post-merge smoke uses the same env-var override mechanism + cleanup.
- [ ] 8.2 Production deployment is OUT OF SCOPE for this change (no production deploy workflow exists yet, mirroring `rate-limit-ip-hashing` §8.2 disposition). When production tag-deploy lands, this change MUST be already-merged AND the smoke from 8.1 MUST be green. Mark this task N/A in the archive commit body if production deploy still doesn't exist at archive time.
- [ ] 8.3 Delete the `rate-limit-infrastructure-success-path-key-log-drift` follow-up entry from `FOLLOW_UPS.md` after final squash-merge — its action items are now satisfied. Land the deletion as part of the next FOLLOW_UPS.md triage sweep (do NOT add a separate cleanup PR; the entry is the explicit "delete after this change merges" target).
