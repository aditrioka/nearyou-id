# Tasks: retention-cleanup-deferred-sweeps

## 1. Repository

- [ ] 1.1 Add `deleteExpiredWebauthnChallenges()`, `deleteOldResolvedModerationQueueRows()`, `deleteOldResolvedReports()` to `RetentionCleanupRepository` + `JdbcRetentionCleanupRepository` with the three SQL constants (design D3/D4), each via the existing `executeDelete` helper

## 2. Worker + route

- [ ] 2.1 Extend `RetentionCleanupResult` + `RetentionCleanupWorker.execute()` with the three new sweeps (appended after `login_events`) and the three new log-line keys in the single `retention_cleanup` INFO line
- [ ] 2.2 Extend `RetentionCleanupResponse` (+ route mapping) with `webauthn_challenges_deleted`, `moderation_queue_deleted`, `reports_deleted`

## 3. Tests (spec scenarios → test cases)

- [ ] 3.1 Repository tests: expired-unconsumed webauthn deleted / <1-day-expired survives / consumed survives; resolved moderation row >1y deleted / <1y survives / pending survives; actioned + dismissed reports >1y deleted / pending survives; NULL resolution-timestamp fail-safe; no-archive-copy assertion (swept row gone, no `%archive%` table exists in the schema)
- [ ] 3.2 Worker/route tests: 200 body carries all seven counts; immediate re-run returns all-zero; log line carries seven counts + duration_ms (extend existing suites)
- [ ] 3.3 Update any existing test fixtures/assertions that pin the four-count response/log shape

## 4. Verification + docs

- [ ] 4.1 Full gate: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` (fresh DB containers if the full suite runs)
- [ ] 4.2 Update the `docs/06` § Retention Policy rows for moderation queue / reports with the enforcing-worker pointer (mirroring the `login_events` row's "the `/internal/cleanup` worker" note), and `docs/08` items 12–13 shipped-status
- [ ] 4.3 PR body: `Closes #365` + `Closes #366` (separate closing keywords), keep title/body current
