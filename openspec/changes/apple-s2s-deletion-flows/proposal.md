## Why

The Apple Sign-In server-to-server (S2S) handler at [`AppleS2SRoutes.kt`](../../../backend/ktor/src/main/kotlin/id/nearyou/app/auth/routes/AppleS2SRoutes.kt) currently returns `501 NotImplemented` for the `account-delete` and `consent-revoked` events ("deferred to deletion-flows change"). Honoring an Apple `account-delete` notification is an **App Store requirement** for any app using Sign in with Apple — without it, a user who deletes their Apple ID leaves an orphaned NearYouID account that violates the UU-PDP right-to-erasure promise in the Privacy Policy. This is Phase 4 item 7 ([`docs/08-Roadmap-Risk.md`](../../../docs/08-Roadmap-Risk.md):245), and the `account-deletion` change already laid the full foundation it builds on (the `deletion_requests` V27 schema, both `apple_s2s_*` `source` CHECK values, the non-cancellable cancel-guard, and the `deletion_requests_immediate_idx` backstop index).

## What Changes

- Replace the `"consent-revoked", "account-delete" -> 501` branch in `AppleS2SRoutes` with real handlers that resolve the Apple `sub` (via the existing `sha256Hex(sub)` → `apple_id_hash` → `users.findByAppleIdHash`) and write a `deletion_requests` row, reusing the established `AccountDeletionService`/`AccountDeletionRepository` insert pattern:
  - **`consent-revoked`** (user revoked Sign in with Apple) → `source = 'apple_s2s_consent_revoked'`, `scheduled_hard_delete_at = NOW() + 30 days`; **cancellable** during the grace window like a normal user-initiated deletion; **sessions kicked immediately** (`token_version = token_version + 1`, the canonical idiom at `CsamRepository.kt:80`). Respond `200`.
  - **`account-delete`** (user deleted their Apple ID entirely) → `source = 'apple_s2s_account_delete'`, `scheduled_hard_delete_at = NOW()`; **synchronously** runs the tombstone+cascade via the existing `AccountHardDeleteWorker` (a new public per-request executor) before responding `200` to Apple; the daily worker backstops a synchronous failure via `deletion_requests_immediate_idx`. **Non-cancellable** (the cancel-guard already excludes this source at `AccountDeletionRepository.kt:112`).
- Resolve a `sub` that maps to no live user (already-deleted / unknown `apple_id_hash`) gracefully to `200` (no crash, no row), so Apple does not retry-storm.
- The existing `email-enabled`/`email-disabled` relay-email handling, Apple-JWT signature verification, `aud` allow-list, and `transaction_id` dedup are **unchanged** and remain green. The `apple_relay_email_changed` notification emission is **explicitly out of scope** (tracked separately).
- **No new Flyway migration** — the schema, both `source` values, and the immediate-pickup index all shipped in V27 (current head is V36).

## Capabilities

### New Capabilities

- `apple-s2s-deletion-flows`: the server-side ingestion behavior for the two Apple S2S account-deletion events at `POST /internal/apple/s2s-notifications` — source semantics, synchronous vs. grace scheduling, session-kick on consent-revoke, non-cancellability of account-delete, no-user graceful handling, and dedup idempotency. Activates the `apple_s2s_consent_revoked` / `apple_s2s_account_delete` sources that `account-deletion` reserved.

### Modified Capabilities

<!-- None. account-deletion / account-hard-delete-worker requirements are unchanged: this capability exercises the schema, sources, cancel-guard, and worker they already shipped. The synchronous per-request executor is an implementation seam (design.md), not a spec-requirement change to account-hard-delete-worker. -->

## Impact

- **Code:** `backend/ktor/.../auth/routes/AppleS2SRoutes.kt` (replace the 501 branch); `account/AccountDeletionRepository.kt` (sibling apple-source inserts + `token_version` bump); `account/AccountHardDeleteWorker.kt` (expose a public per-request executor mirroring the private `processOne`); `Application.kt` (pass the deletion repo + worker into `appleS2SRoutes`). `users.findByAppleIdHash` already exists.
- **APIs:** `POST /internal/apple/s2s-notifications` gains real `account-delete` + `consent-revoked` behavior (previously `501`). No new endpoint, no contract change to existing events.
- **Cross-layer cohesion** (docs/12): backend-only is a **complete** vertical slice — `consent-revoked` rows surface in the existing mobile restore banner (`GET /api/v1/account/deletion-request` via `SettingsAccountDeletionViewModel`) and in the admin Hard Delete Queue (`deletionqueue`), both of which read `deletion_requests` generically; no new mobile/admin surface is required.
- **Schema / dependencies:** none new (no migration, no library pin, no vendor SDK).
- **Compliance:** closes the App Store Sign-in-with-Apple account-deletion gap and the Pre-Launch "Apple S2S immediate delete test" ([`docs/08`](../../../docs/08-Roadmap-Risk.md):322).
