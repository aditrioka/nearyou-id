## Context

`AppleS2SRoutes.appleS2SRoutes(...)` already performs the full Apple S2S verification pipeline: envelope receive, JWT decode, `kid` lookup against `JwksCache`, RSA256 signature verify with 5s leeway, fail-closed `aud` allow-list check, base64url payload parse into `AppleS2SPayload(type, sub, transaction_id, events)`, and `InMemoryDedup` (LRU, capacity 2000) keyed on `transaction_id ?: "$sub:$type"`. After that pipeline it switches on `payload.type`:

- `email-enabled` / `email-disabled` → `users.setAppleRelayEmail(sha256Hex(sub), enabled)` → `200` (shipped, untouched).
- `consent-revoked` / `account-delete` → **`501 NotImplemented`** with `"deferred to deletion-flows change"` (the gap this change closes).
- else → `200 {"status":"ignored"}`.

The deletion substrate is fully shipped by the `account-deletion` + `account-hard-delete-worker` capabilities:
- `deletion_requests` (V27) carries `source` CHECK including `apple_s2s_consent_revoked` + `apple_s2s_account_delete`, plus `deletion_requests_scheduled_idx` (daily scan) and `deletion_requests_immediate_idx` (immediate backstop).
- `AccountDeletionRepository.requestDeletion(userId)` is the canonical `source='user'` insert (atomic idempotent CTE); `cancelDeletion` already excludes `source <> 'apple_s2s_account_delete'`; `getStatus` returns any pending un-cancelled un-executed row.
- `AccountHardDeleteWorker` runs the tombstone+cascade+deletion-log per row under `FOR UPDATE SKIP LOCKED`, idempotently; `execute()` scans all due rows; `processOne(requestId)` / `tombstoneAndCascade(...)` are **private**.
- `users.findByAppleIdHash(hash)` exists (`AuthRoutes.kt:144`); the canonical session-kick idiom is `token_version = token_version + 1` (`CsamRepository.kt:80`).

## Standards conformance

Builds on the **backend layering** Pattern-Registry pattern ([`docs/11`](../../../docs/11-Engineering-Standards.md) § Pattern Registry): Route (`AppleS2SRoutes`) → Service/Repository (`AccountDeletionService`/`AccountDeletionRepository`) + worker (`AccountHardDeleteWorker`), JDBC on the pool-bounded `DbDispatchers.db` (docs/11 §3.2). **No new pattern is introduced** — the Apple deletion inserts reuse the existing `deletion_requests` insert pattern rather than a second one. `deletion_requests` is not a `visible_*` / block-protected table, so no shadow-ban view or block-join applies; writes are keyed by the Apple-resolved `user_id` (no IDOR surface — the endpoint is internal, Apple-JWT-authenticated).

## Cross-layer scope (docs/12)

**Layers spanned: backend only — and that is the complete vertical slice.** The consuming layers already read `deletion_requests` generically:
- **Mobile**: the restore banner (`GET /api/v1/account/deletion-request` → `SettingsAccountDeletionViewModel`) shows any pending cancellable deletion. A `consent-revoked` row (cancellable, 30-day grace) is exactly such a row and surfaces with no client change. An `account-delete` row is immediate + non-cancellable, so it correctly never lingers as a cancellable banner.
- **Admin**: the Hard Delete Queue (`deletionqueue`) surfaces `deletion_requests` rows for oversight regardless of `source`.

No new mobile or admin surface is required; this is **not** a silent single-layer slice. (Recorded as the explicit cohesion finding for `/opsx:preflight` / Phase D.)

## Goals / Non-Goals

**Goals:**
- Honor Apple `account-delete` (immediate, non-cancellable, synchronous tombstone with daily backstop) and `consent-revoked` (30-day cancellable grace, session-kick) exactly per [`docs/06`](../../../docs/06-Security-Privacy.md):133-142.
- Keep the handler idempotent (dedup), crash-free on unknown/already-deleted `sub`, and always-`200` to Apple on success so Apple does not retry-storm.
- Reuse the shipped deletion substrate; add only the minimal seams (public per-request executor + apple-source inserts).

**Non-Goals:**
- The `apple_relay_email_changed` notification emission on `email-enabled`/`email-disabled` (the flag write is already shipped; the notification is deferred and tracked separately).
- Any new mobile/admin UI, new endpoint, new migration, or new library.
- Changing the existing email-relay, verification, or dedup behavior.

## Decisions

**D1 — Synchronous account-delete execution via a new public per-request executor on `AccountHardDeleteWorker`.**
`account-delete` must run the tombstone+cascade *before* the `200` ([`docs/06`](../../../docs/06-Security-Privacy.md):138). Options: (a) call `execute()` (scans *all* due rows — wrong blast radius, could process unrelated immediate rows on the request thread); (b) expose a public `executeImmediate(requestId): Boolean` that wraps the existing private `processOne(requestId)` (claims the single row `FOR UPDATE SKIP LOCKED`, tombstones, logs, stamps `executed_at`). **Chosen: (b).** It reuses the exact idempotent, concurrency-safe per-row path; the daily worker remains the backstop for the same row if the synchronous call throws or is skipped (the row stays `executed_at IS NULL` and is due at `NOW()`, so `deletion_requests_immediate_idx` picks it up). No second deletion implementation is created.

**D2 — Insert order for account-delete: persist the row first, then execute.**
The handler INSERTs the `apple_s2s_account_delete` row (`scheduled_hard_delete_at = NOW()`) and commits it, *then* calls `executeImmediate(newRowId)`. This guarantees the daily backstop has a durable row even if the synchronous execution fails — matching [`docs/05`](../../../docs/05-Implementation.md):637. The repository returns the new row id (extend the apple insert to `RETURNING id`), avoiding a re-query.

**D3 — Always `200` to Apple after the row is durably persisted; failures of the synchronous execution do not fail the response.**
Apple retries non-2xx. Once the `deletion_requests` row is committed, the deletion is guaranteed (synchronously or via backstop), so the handler returns `200` even if `executeImmediate` throws — the throw is logged (no PII), and the daily worker completes the job. A failure *before* the row is persisted (DB down) returns `500` so Apple retries. Unknown/already-deleted `sub` → `200` with no row (idempotent; Apple should not retry a no-op). For the retry to actually work, the dedup key is recorded **only on a 2xx outcome** (review fix): the old check-and-record-on-receipt shape would have short-circuited Apple's retry of a failed receipt to `200 duplicate`, silently dropping the deletion.

**D4 — `consent-revoked` reuses the 30-day-grace insert AND bumps `token_version` as a separate, every-receipt write.**
Insert mirrors `requestDeletion` but with `source = 'apple_s2s_consent_revoked'` and the same `NOW() + INTERVAL '30 days'`. Distinct from user-initiated deletion (which is explicitly *not* session-terminating, `AccountRoutes.kt:43`), `consent-revoked` additionally revokes live sessions via `token_version = token_version + 1`, per [`docs/06`](../../../docs/06-Security-Privacy.md):137. **The session-kick is a separate statement, NOT atomic with the insert** — the insert lives in `AccountDeletionRepository` (its own connection) while the canonical session-kick is `JdbcUserRepository.incrementTokenVersion(userId)` (a separate connection); claiming one transaction across two repositories would be false. Each write is individually safe-on-retry (the insert is idempotent; an extra `token_version` bump is harmless). **The bump fires on *every* `consent-revoked` receipt for a live user** — including when the deletion insert no-ops because a pending row already exists — because each receipt is a fresh revocation signal that must kick any sessions issued since the last one. (Resolves the former Open Question: bump-always via a separate `incrementTokenVersion`, not bump-only-on-insert; the latter is unreachable when the pending-row CTE short-circuits.)

**D5 — User resolution + privacy.** `sub` → `sha256Hex(sub)` (existing helper) → `users.findByAppleIdHash(hash)`. The raw `sub` and the resolved `user_id` are never logged; failures log only the event type. A `null` lookup (no live user) is the graceful `200`-no-row path (D3).

**D6 — Wiring.** `appleS2SRoutes(...)` gains `AccountDeletionRepository` (or a thin `AppleDeletionService`) + `AccountHardDeleteWorker` parameters, passed from `Application.kt` (both already constructed there: worker at `Application.kt:458`; the route call site is `Application.kt:1228` — verify both line refs at apply time, they drift). Keep the route signature change minimal and the existing `InMemoryDedup` default intact.

**D7 — `account-delete` is NOT idempotency-suppressed by an existing pending row (Apple immediate-delete compliance).**
Unlike `consent-revoked` (which honors the pending-row guard and keeps one grace row), `account-delete` MUST always insert its own `apple_s2s_account_delete` row at `NOW()` and execute it synchronously, *even if* the user already has a pending `'user'` or `'apple_s2s_consent_revoked'` grace row. Rationale: Apple requires immediate deletion when the Apple ID itself is deleted; suppressing the immediate insert because a 30-day grace row exists would leave the user in grace and violate that requirement. After the synchronous tombstone, any other pending row for that user is mooted by the worker's **per-user tombstone guard** (`SQL_TOMBSTONE ... WHERE deleted_at IS NULL`, added at review): when the leftover row becomes due, zero rows update → the worker stamps `executed_at` without a second `deletion_log` entry or re-cascade. So `scheduleAppleAccountDelete` does NOT carry the `WHERE NOT EXISTS (pending)` guard that `requestDeletion`/`scheduleConsentRevoked` use.

## Risks / Trade-offs

- **Synchronous tombstone on the request thread blocks the Apple response** → Mitigation: it runs the single-row `processOne` on the JDBC dispatcher; tombstone+cascade for one user is bounded; the `200`-after-persist contract (D3) means even a slow/failed execution doesn't break Apple's expectation.
- **Session-kick vs. restore-banner interaction for consent-revoke** → bumping `token_version` logs the user out, so the in-app restore banner is unreachable until they re-authenticate with Apple (which they just revoked). This is faithful to [`docs/06`](../../../docs/06-Security-Privacy.md):137 (both "cancellable" and "sessions kicked" are stated). Cancellation during grace therefore happens via re-auth or support, not a live in-app session. Documented here as a deliberate, docs/06:137-faithful consequence — not silently resolved.
- **Double-execution race (synchronous call + daily worker on the same row)** → Mitigation: `processOne` claims `FOR UPDATE SKIP LOCKED` and is idempotent/no-op once `executed_at` is set, so concurrent execution is safe.
- **Replayed Apple notification** → Mitigation: existing `InMemoryDedup` short-circuits before any DB write; additionally the deletion inserts are idempotent (pending-row guard).

## Migration Plan

No schema migration. Deploy is a code-only change to the backend image. Rollback = revert the commit (the handler returns to `501` for the two events; no data shape changes, so already-written `apple_s2s_*` rows remain valid and the daily worker still processes them). Pre-archive staging smoke: post a signed/test Apple S2S `account-delete` + `consent-revoked` payload (or the unit/integration harness) and assert the row + execution; covered by `tasks.md` verification.

## Open Questions

_(Both former Open Questions are now resolved as Decisions — see D4 and below — after the proposal-review pass.)_

- **RESOLVED (was: re-bump `token_version` on duplicate `consent-revoked`?)** → D4: yes, bump on every receipt via a separate `incrementTokenVersion`; the insert remains idempotent. A same-statement bump was rejected because the pending-row CTE short-circuits the no-op path, making "bump on every receipt" unreachable if folded into the insert.
- **RESOLVED (was: upgrade-in-place vs distinct row when a `source='user'` pending row exists?)** → For `consent-revoked`: keep the existing pending row (pending-row guard holds — no second row; the surviving row's source is whichever was first), and still kick sessions. For `account-delete`: D7 — never suppressed; always insert+execute the immediate row regardless of any existing pending row.
