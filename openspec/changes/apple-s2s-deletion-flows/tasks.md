## 1. Preflight verification (do before writing code)

- [ ] 1.1 Confirm NO new Flyway migration is needed: `deletion_requests` (V27) already has the `apple_s2s_consent_revoked` + `apple_s2s_account_delete` `source` CHECK values, `deletion_requests_immediate_idx`, and the `scheduled_hard_delete_at` column (current migration head is V36). If — and only if — something is genuinely missing, the new migration takes the next free V-number (V37) and a CI parity-init review.
- [ ] 1.2 Confirm the user-lookup seam: `users.findByAppleIdHash(hash)` exists (`AuthRoutes.kt:144`) and returns the user with `id` + `tokenVersion`; confirm whether it filters out tombstoned/soft-deleted users (drives the "unknown sub → no-op" path). Add a "live user" filter at the call site if the lookup returns tombstoned rows.
- [ ] 1.3 Resolve design Open Questions: (a) whether to re-bump `token_version` on a duplicate `consent-revoked` (default: bump on every receipt); (b) whether `consent-revoked` upgrades an existing pending `source='user'` row in place or relies on the pending-row guard (default: pending-row guard, keep the first row). Record the decisions in the implementing commit body.

## 2. Repository: apple-source deletion inserts + session kick

- [ ] 2.1 In `AccountDeletionRepository` (or a thin `AppleDeletionService` wrapping it), add `scheduleConsentRevoked(userId): Boolean` — atomic idempotent INSERT of `source='apple_s2s_consent_revoked'`, `scheduled_hard_delete_at = NOW() + INTERVAL '30 days'`, guarded by the existing pending-row check; bump `token_version = token_version + 1` for the user (canonical idiom per `CsamRepository.kt:80`) in the same transaction. Reuse the existing `requestDeletion` CTE shape — do NOT introduce a second insert pattern.
- [ ] 2.2 Add `scheduleAppleAccountDelete(userId): UUID?` — INSERT `source='apple_s2s_account_delete'`, `scheduled_hard_delete_at = NOW()`, `RETURNING id` (so the handler can synchronously execute it). Commit before returning.
- [ ] 2.3 Confirm `cancelDeletion` still excludes `source <> 'apple_s2s_account_delete'` (already true at `AccountDeletionRepository.kt:112`) and does NOT exclude `apple_s2s_consent_revoked` (so consent-revoked stays cancellable). Add/keep a test.

## 3. Worker: public per-request executor

- [ ] 3.1 Expose a public `executeImmediate(requestId: UUID): Boolean` on `AccountHardDeleteWorker` that wraps the existing private `processOne(requestId)` (claim `FOR UPDATE SKIP LOCKED` → tombstone+cascade → `deletion_log` → stamp `executed_at`), idempotent and no-op if already executed. Do NOT duplicate the tombstone logic.

## 4. Handler: replace the 501 branch

- [ ] 4.1 In `AppleS2SRoutes`, replace the `"consent-revoked", "account-delete" -> 501` branch. For both: require non-null `sub` (else `400`, mirroring the email branch); resolve `sha256Hex(sub)` → live user; on no live user respond `200` no-row (graceful no-op). Never log raw `sub`, resolved `user_id`, or any coordinate.
- [ ] 4.2 `consent-revoked`: call `scheduleConsentRevoked(userId)` → respond `200`.
- [ ] 4.3 `account-delete`: call `scheduleAppleAccountDelete(userId)` → if a row id was returned, call `worker.executeImmediate(id)` inside try/catch (log-only on throw, no PII) → respond `200`. A pre-persist DB failure propagates as non-2xx (Apple retries); a post-persist execution failure still responds `200` (daily backstop completes it).
- [ ] 4.4 Update `appleS2SRoutes(...)` signature + the `Application.kt:1228` call site to pass the deletion repo/service + `accountHardDeleteWorker` (worker already built at `Application.kt:458`). Keep the `InMemoryDedup` default and all verification unchanged.

## 5. Tests (Kotest, `database`-tagged where they touch Postgres)

- [ ] 5.1 `account-delete` happy path: verified payload → row `source='apple_s2s_account_delete'`, `scheduled_hard_delete_at ≈ NOW()`, user tombstoned + `deletion_log` row + `executed_at` set, response `200`, all before return.
- [ ] 5.2 `account-delete` non-cancellable: a subsequent cancel attempt matches no row.
- [ ] 5.3 `account-delete` backstop: with the synchronous executor stubbed to throw, the row persists `executed_at IS NULL` (due now) and the response is still `200`.
- [ ] 5.4 `account-delete` pre-persist failure (DB down) → non-2xx.
- [ ] 5.5 `consent-revoked` happy path: row `source='apple_s2s_consent_revoked'`, `scheduled ≈ NOW()+30d`, `token_version` incremented, response `200`.
- [ ] 5.6 `consent-revoked` cancellable within grace (existing cancel path restores it).
- [ ] 5.7 `consent-revoked` idempotency: a pre-existing pending deletion → no second pending row.
- [ ] 5.8 Unknown/already-deleted `sub` (both events) → `200`, no row, no exception.
- [ ] 5.9 Dedup regression: duplicate `transaction_id` for a deletion event → `200 {"status":"duplicate"}`, no extra row.
- [ ] 5.10 Verification regression: existing `email-enabled`/`email-disabled` + signature/`aud`-rejection tests remain green (no behavior change).

## 6. Verification, lint, smoke

- [ ] 6.1 Run the pre-push gate: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` (note the CI test lane excludes `!network`, not `!database` — `database`-tagged tests run in CI).
- [ ] 6.2 `openspec validate apple-s2s-deletion-flows --strict` green.
- [ ] 6.3 Pre-archive staging smoke (per docs/13 + the one-PR convention): branch-deploy and exercise an Apple S2S `account-delete` + `consent-revoked` against staging (signed/test payload or the integration harness), assert the row + execution + session-kick. Backend-only runtime surface — no mobile bring-up required (cohesion-verified: existing mobile banner + admin queue read `deletion_requests` generically).
- [ ] 6.4 Confirm no new module / Dockerfile COPY / README sync needed (no new Gradle module added).
