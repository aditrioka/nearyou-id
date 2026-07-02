## 1. Preflight verification (do before writing code)

- [x] 1.1 Confirm NO new Flyway migration is needed: `deletion_requests` (V27) already has the `apple_s2s_consent_revoked` + `apple_s2s_account_delete` `source` CHECK values, `deletion_requests_immediate_idx`, and the `scheduled_hard_delete_at` column (current migration head is V36). If — and only if — something is genuinely missing, the new migration takes the next free V-number (V37) and a CI parity-init review. — Confirmed: V27 carries both sources + the immediate index + the column; head is V36. No migration.
- [x] 1.2 Confirm the user-lookup seam: `users.findByAppleIdHash(hash)` exists (`AuthRoutes.kt:144`) and returns the user with `id` + `tokenVersion`; confirm whether it filters out tombstoned/soft-deleted users (drives the "unknown sub → no-op" path). Add a "live user" filter at the call site if the lookup returns tombstoned rows. — Confirmed: `JdbcUserRepository.findByAppleIdHash` does NOT filter `deleted_at`; `UserRow` carries `deletedAt`, so the handler applies a `deletedAt == null` live-user guard at the call site.
- [x] 1.3 Decisions are RESOLVED in design.md (D4 + D7) after proposal review — implement them, don't re-litigate: (a) `consent-revoked` bumps `token_version` on **every** receipt via a **separate** `incrementTokenVersion(userId)` (NOT folded into the insert SQL; not claimed atomic with it); (b) `consent-revoked` keeps the existing pending row (pending-row guard, no second row) but still kicks sessions; (c) `account-delete` is **never** suppressed by a pending row — always inserts its immediate row + executes (Apple immediate-delete compliance).
- [x] 1.4 Note: the existing `AppleS2SRoutesTest` cases that currently assert `501` for `account-delete`/`consent-revoked` (the `InMemoryUsers` in-memory fake spec) must be **updated** to the new behavior, not merely added alongside.

## 2. Repository: apple-source deletion inserts + session kick

- [x] 2.1 In `AccountDeletionRepository` (or a thin `AppleDeletionService` wrapping it), add `scheduleConsentRevoked(userId): Boolean` — idempotent INSERT of `source='apple_s2s_consent_revoked'`, `scheduled_hard_delete_at = NOW() + INTERVAL '30 days'`, guarded by the existing pending-row check. Reuse the existing `requestDeletion` CTE shape — do NOT introduce a second insert pattern. The `token_version` session-kick is a **separate** call (see 4.2), NOT folded into this insert.
- [x] 2.2 Add `scheduleAppleAccountDelete(userId): UUID?` — INSERT `source='apple_s2s_account_delete'`, `scheduled_hard_delete_at = NOW()`, `RETURNING id` (so the handler can synchronously execute it). Commit before returning. **No pending-row guard** (D7) — always inserts even if the user has another pending deletion row.
- [x] 2.3 Confirm `cancelDeletion` still excludes `source <> 'apple_s2s_account_delete'` (already true at `AccountDeletionRepository.kt:112`) and does NOT exclude `apple_s2s_consent_revoked` (so consent-revoked stays cancellable). Add/keep a test.

## 3. Worker: public per-request executor

- [x] 3.1 Expose a public `executeImmediate(requestId: UUID): Boolean` on `AccountHardDeleteWorker` that wraps the existing private `processOne(requestId)` (claim `FOR UPDATE SKIP LOCKED` → tombstone+cascade → `deletion_log` → stamp `executed_at`), idempotent and no-op if already executed. Do NOT duplicate the tombstone logic.

## 4. Handler: replace the 501 branch

- [x] 4.1 In `AppleS2SRoutes`, replace the `"consent-revoked", "account-delete" -> 501` branch. For both: require non-null `sub` (else `400`, mirroring the email branch); resolve `sha256Hex(sub)` → live user; on no live user respond `200` no-row (graceful no-op). Never log raw `sub`, resolved `user_id`, or any coordinate.
- [x] 4.2 `consent-revoked`: call `scheduleConsentRevoked(userId)` AND `users.incrementTokenVersion(userId)` (the session-kick fires on every receipt for a live user, even when the insert no-ops) → respond `200`.
- [x] 4.3 `account-delete`: call `scheduleAppleAccountDelete(userId)` → if a row id was returned, call `worker.executeImmediate(id)` inside try/catch (log-only on throw, no PII) → respond `200`. A pre-persist DB failure propagates as non-2xx (Apple retries); a post-persist execution failure still responds `200` (daily backstop completes it).
- [x] 4.4 Update `appleS2SRoutes(...)` signature + the `Application.kt:1228` call site to pass the deletion repo/service + `accountHardDeleteWorker` (worker already built at `Application.kt:458`). Keep the `InMemoryDedup` default and all verification unchanged.

## 5. Tests (Kotest, `database`-tagged where they touch Postgres)

- [x] 5.1 `account-delete` happy path: verified payload → row `source='apple_s2s_account_delete'`, `scheduled_hard_delete_at ≈ NOW()`, user tombstoned + `deletion_log` row + `executed_at` set, response `200`, all before return.
- [x] 5.2 `account-delete` non-cancellable: a subsequent cancel attempt matches no row.
- [x] 5.3 `account-delete` backstop (end-to-end): with the synchronous executor stubbed to throw, the row persists `executed_at IS NULL` (due now) and the response is still `200`; THEN run `worker.execute()` and assert the row is now tombstoned/`executed_at` set (proves `deletion_requests_immediate_idx` backstop pickup, per docs/08:322).
- [x] 5.4 `account-delete` pre-persist failure (DB down) → non-2xx.
- [x] 5.5 `consent-revoked` happy path: row `source='apple_s2s_consent_revoked'`, `scheduled ≈ NOW()+30d`, `token_version` incremented, response `200`.
- [x] 5.6 `consent-revoked` cancellable within grace (existing cancel path restores it).
- [x] 5.7 `consent-revoked` idempotency + every-receipt session-kick: a pre-existing pending deletion → no second pending row, AND `token_version` is still incremented on the no-op-insert path.
- [x] 5.8 Unknown/already-deleted `sub` (both events, incl. a `deleted_at IS NOT NULL` row) → `200`, no row, no `token_version` bump, no exception.
- [x] 5.9 Dedup regression: duplicate `transaction_id` for a deletion event → `200 {"status":"duplicate"}`, no extra row.
- [x] 5.10 Verification regression: existing `email-enabled`/`email-disabled` flag-write + signature/`aud`-rejection tests remain green (no behavior change); update the prior `501` assertions for the two events to the new behavior.
- [x] 5.11 Missing-`sub` on a deletion event (both types) → `400`, no row (mirrors the email branch).
- [x] 5.12 `account-delete` escalation: a user with a pending `'user'`/`'apple_s2s_consent_revoked'` grace row → `account-delete` still inserts the immediate row and tombstones now (not left in grace).
- [x] 5.13 Double-execute safety: `executeImmediate(id)` called twice (or `executeImmediate` then `execute()`) on the same row → exactly one `deletion_log` row, no double-cascade (per-row `FOR UPDATE SKIP LOCKED` idempotency).
- [x] 5.14 PII-in-logs: with a capturing logger, a synchronous-execution failure log line contains no raw `sub` / `user_id` / `apple_id_hash` / coordinate (only event type + error class).
- [x] 5.15 Placement: the DB-touching deletion scenarios (5.1–5.8, 5.11–5.14) go in a NEW `@Tags("database")` spec (the existing `AppleS2SRoutesTest` is an untagged in-memory fake); any new DB pool MUST `autoClose` (docs/11 §3.2 / CI connection budget). 5.9–5.10 can stay in the in-memory file. CI runs `database` tests (excludes only `!network`).

## 6. Verification, lint, smoke

- [x] 6.1 Run the pre-push gate: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` (note the CI test lane excludes `!network`, not `!database` — `database`-tagged tests run in CI).
- [x] 6.2 `openspec validate apple-s2s-deletion-flows --strict` green.
- [x] 6.3 Pre-archive staging smoke (per docs/13 + the one-PR convention): branch-deploy and exercise an Apple S2S `account-delete` + `consent-revoked` against staging (signed/test payload or the integration harness), assert the row + execution + session-kick. Backend-only runtime surface — no mobile bring-up required (cohesion-verified: existing mobile banner + admin queue read `deletion_requests` generically).
- [x] 6.4 Confirm no new module / Dockerfile COPY / README sync needed (no new Gradle module added).
