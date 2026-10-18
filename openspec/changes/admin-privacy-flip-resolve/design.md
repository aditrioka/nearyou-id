## Context

`admin-privacy-flip-monitor` (PR #269, frame 17) lists every `users` row with `privacy_flip_scheduled_at IS NOT NULL`. Each row is classified against a request-scoped evaluation instant as **IN_WINDOW** (mid 72h grace) or **OVERDUE** (the deadline passed and the worker never cleared it). An OVERDUE row exists for one of three reasons:

1. **The worker is not running or is erroring.** Cloud Scheduler is down, or the CTE fails.
2. **The user is soft-deleted.** The worker's `deleted_at IS NULL` guard deliberately leaves the row "for the admin monitor as a stuck-row anomaly" (privacy-flip-worker spec).
3. **The user is premium again but the schedule was never cleared.** This only happens once the worker guard in D5 lands. Today the worker would wrongly flip such a row.

The fix today is out-of-band SQL. The monitor spec says so explicitly and guards against any write.

The read-path facts that make this safe (`docs/05` "Effective private"; `JdbcUserProfileReader`):

- **Effective private** = `private_profile_opt_in AND (subscription_status IN ('premium_active','premium_billing_retry') OR privacy_flip_scheduled_at > now())`.
- **For an OVERDUE row**, the `> now()` grace short-circuit is already false. So visibility is decided by `subscription_status` alone:
  - a `free` user already reads public;
  - a premium user already reads private.
- **Resolving never changes what other users see right now.** It reconciles the stored state:
  - for a `free` user, it resets the opt-in exactly as the worker would have, so a later re-subscribe does not silently re-privatize;
  - for a premium user, it removes a stale schedule.

## Goals / Non-Goals

**Goals:**
- An owner/admin can resolve one OVERDUE row from the panel, with a reason, audited and rate-limited.
- The resolution is **subscription-aware**: never reset a paying user's privacy opt-in. The check runs inside the transaction, so a concurrent webhook cannot race it.
- The worker gets the same guard, so the hourly job and the admin action agree on what "apply the flip" means.

**Non-Goals:**
- **Resolving IN_WINDOW rows** (cutting a user's 72h grace short). Out of scope by design: the grace window is a user-facing promise.
- **Bulk / multi-row resolve.** One row per action, the sibling-write precedent. Bulk would be a separate decision with its own cap semantics.
- **Self-healing premium rows in the worker.** The worker skips them, and the admin resolves them. Keeping a human in the loop for an anomalous webhook ordering is deliberate (D5).
- **Surfacing `subscription_status` on the monitor table.** It is not needed to act: the outcome message names the branch taken, and the identity-only PII requirement stays unchanged.
- **Any notification to the user.** The worker emits none either. The `privacy_flip_warning` stays the EXPIRATION webhook's.

## Decisions

### D1 — Route + gate order: `POST /admin/privacy-flips/{user_id}/resolve`, CSRF → owner/admin → id → reason

The gate order mirrors `adminSubscriptionGrace` / `adminDeletionQueue` / `adminRejectedIdentifiers` verbatim:

1. `AdminCsrfGate.validateCsrf` first. A miss returns 403 plus an `admin_csrf_violation` row, *before* the role gate, so the violation is audited rather than masked.
2. `AdminRoleGate.requireOwnerOrAdmin`.
3. Parse the path UUID. Malformed returns 400.
4. Require `reason`. Trimmed; blank returns 400. Capped server-side at 200 chars, matching the template `maxlength`. Over-length input is truncated, as the deletion-queue `reason` does; the spec scenario asserts no 5xx.

The path is keyed by `user_id` because the monitor row *is* a `users` row. **Owner/admin, not `WRITE_ROLES`:** the action writes the privacy flag, which sits on the lint allowlist. It matches the owner/admin tier of the other restorative/bookkeeping writes (grace expedite, rejected-identifier clear, deletion expedite).

*Alternative considered:* `/clear` vs `/expedite` vs `/resolve`. "Clear" describes only one of the two branches, and "expedite" implies acting early (that is IN_WINDOW, a non-goal). "Resolve" names the operator intent for both branches.

### D2 — One transaction: lock → eligibility → cap → subscription-aware branch → audit

All in `AdminPrivacyFlipsRepository.resolve(...)`, which owns the transaction like its siblings:

1. **Lock and read.** `SELECT private_profile_opt_in, privacy_flip_scheduled_at, subscription_status, deleted_at FROM users WHERE id = ? FOR UPDATE`.
2. **Eligibility.** The row must exist, with `privacy_flip_scheduled_at IS NOT NULL` and `privacy_flip_scheduled_at <= evalInstant`. That is the monitor's OVERDUE predicate, closed on the OVERDUE side and evaluated against the SAME injected request clock the monitor uses. Anything else returns `Ineligible`: unknown id, already cleared (idempotent, which serializes a two-admin race so the loser is a no-op), or still IN_WINDOW. Ineligible means rollback, no write.
3. **Cap.** If the admin is at or over the cap, return `RateLimited` (rollback, no write). The check comes *after* eligibility, so an ineligible attempt never consumes or reports quota (grace precedent).
4. **Branch on the locked `subscription_status`:**
   - `free` → **APPLIED**: `UPDATE users SET private_profile_opt_in = FALSE, privacy_flip_scheduled_at = NULL WHERE id = ?`. This is the worker's exact SET list, annotated `// @allow-privacy-write: admin_resolve` (D4).
   - `premium_active` / `premium_billing_retry` → **CLEARED**: `UPDATE users SET privacy_flip_scheduled_at = NULL WHERE id = ?`. It touches no privacy flag, so no allowlist annotation is needed.
5. **Audit.** `AdminAuditLogger.logPrivacyFlipResolved(conn, …)` writes one row: `action_type = 'privacy_flip_resolved'`, `target_type = 'user'`, `target_id = user_id`, `reason`, `before_state = {private_profile_opt_in, privacy_flip_scheduled_at, subscription_status}`, `after_state = {private_profile_opt_in: <post>, privacy_flip_scheduled_at: null, subscription_status: <unchanged>, resolution: "applied"|"cleared"}`.
6. **Commit.** Any throw rolls back the UPDATE and the audit row together.

**Soft-deleted rows** follow the same branch. The worker skipped them by design and left them for this monitor. Resolving is the admin's sanctioned way to settle them. The flip is harmless on a deleted account and keeps the stored state honest if the user restores within the 30-day grace.

*Alternative considered:* delegating to the worker's CTE with a `WHERE id = ?`. Rejected. The worker's audit row is attributed to the `system` sentinel with a fixed reason, while this action must carry the acting admin and their reason. The worker also has no premium branch.

### D3 — Dedicated rate limit: 10 / admin / trailing hour on `privacy_flip_resolved`

`ratelimit/PrivacyFlipResolveRateLimiter` is another instance of the audit-ledger soft-cap pattern (`DestructiveActionRateLimiter` → `GraceExpediteActionRateLimiter` / `DeletionQueueExpediteRateLimiter` / `RejectedIdentifierClearRateLimiter`):

- The COUNT reads `admin_actions_log` rows for `admin_id`, `action_type = 'privacy_flip_resolved'`, and `created_at > NOW() - INTERVAL '1 hour'`.
- The read runs on the write's connection inside its transaction.
- Concurrency tolerance is ±1 (no `FOR UPDATE` on the ledger), because this is a soft cap.
- An own-connection overload backs the page's quota chip.

Why **10**: it matches the other corrective single-row writes (deletion expedite, data-export trigger, rejected-identifier clear, referral grant). Grace expedite's 20 is a no-mutation bookkeeping write. Why **distinct** from the 20/hour destructive budget: that set is user-punitive (warn / suspend / ban / shadow-ban / redact). A resolve punishes no one, and the triage comment said so explicitly.

### D4 — Privacy-flag write allowlist gains a third declared writer: `admin_resolve`

The invariant (`openspec/project.md` § Coding Conventions) names only the worker and the Settings flow as writers of `private_profile_opt_in = FALSE`. The APPLIED branch is a new writer, so it is **declared**, not smuggled in under `worker`:

- project.md's line becomes `worker|user_settings|admin_resolve`, with the admin-resolve scope spelled out: OVERDUE row, non-premium re-check, audited.
- The `docs/08` lint description is updated to match.

There is no Detekt rule for this invariant today; it is review-enforced, so no lint change is needed.

*Alternative considered:* putting the single-row UPDATE inside `PrivacyFlipWorker` so it could wear the `worker` marker. Rejected: it would be an admin write wearing the worker's label, which is exactly the drift the allowlist exists to prevent.

### D5 — Worker eligibility excludes premium users

Add `AND subscription_status NOT IN ('premium_active', 'premium_billing_retry')` to the worker's `eligible` CTE. Without it, the D2 re-check is cosmetic. Take any premium row the admin would refuse to flip: the next hourly run flips it anyway, because today's worker never reads the status.

With the guard, such a row stays OVERDUE, the monitor's OVERDUE count surfaces it as an anomaly, and the admin resolves it (CLEARED branch). One predicate; the existing partial index `users_privacy_flip_idx` still drives the scan (the status filter is a residual).

*Alternative considered:* the worker auto-clears premium rows (a second UPDATE branch plus a second system audit action). Rejected for now. It is more code and spec surface for an anomalous state that should be looked at, not silently healed.

### D6 — UI: per-row control on OVERDUE rows, owner/admin only; frame 17 updated

- **Fragment** (`privacy-flips-table.peb`):
  - An extra "Action" column renders when `canResolve`, that is, when the session role is in `AdminRoleGate.OWNER_ADMIN_ROLES` (the rejected-identifiers `canClear` precedent).
  - Each OVERDUE row gets `<form class="expedite">`. It carries the hidden `_csrf` (full-page render), a required `reason` input (`maxlength=200`), and a `btn tonal sm` submit with the `bolt` icon labelled "Resolve". The form uses `hx-post` targeting `#privacy-flips-table` with `outerHTML`, plus an `hx-confirm` that explains both branches.
  - IN_WINDOW rows render no control.
  - The quota chip ("Resolve actions this hour: n/10") and the in-band outcome message reuse the existing `.quota` / `.outcome-message` styles.
- **Why tonal, not danger** (contrast frame 15's `btn dangert`): a resolve has no immediate visibility effect (see Context). It is corrective bookkeeping, like frame 18's tonal "Expedite resolution".
- **`form.expedite`** is the shared in-row action form style (frames 15/18/21). Reusing it means **no `admin.css` change and no SHA256SUMS re-pin**.
- **Page** (`privacy-flips.peb`): drop the `READ-ONLY` chip and update the sub-copy.
- **Board** (`dev/mockups/nearyou-admin-mockup.html` frame 17): add the control to the OVERDUE row, drop `READ-ONLY`, and update the caption ("POST …/{user_id}/resolve"). The board stays the canonical visual reference (docs/11 §3.6).
- **Responses:**
  - Success (HTMX): re-render the fragment with the message, honouring the current query parameters.
  - Success (no-JS): `303` to `/admin/privacy-flips` (PRG).
  - Ineligible / RateLimited: re-render with the message (200).

### Standards conformance (docs/11)

- **Backend layering (§3.1):** route → repository, with the transaction owned by the repository. This is the established admin-panel read+write pattern used by every sibling write (`SubscriptionGraceRepository.expedite`, `DeletionQueueRepository.expedite`, `AdminRejectedIdentifiersRepository.clear`). No new layer or second pattern.
- **JDBC discipline (§3.2):** the admin-panel repositories run on the request coroutine, as their siblings do. This change does not alter that module-wide posture. The worker keeps its injected `dbDispatcher` (PR #586).
- **Rate limiting:** the audit-ledger soft-cap pattern (registered by `admin-destructive-action-rate-limit`), one new instance. The Redis-backed `computeTTLToNextReset` invariant applies to user-facing Redis limiters, not this ledger.
- **Admin UI (§3.6):** Pebble + HTMX fragment swap with no-JS fallback, vendored CSS, and no inline styles. Frame 17 is consulted and amended.

### Cross-layer scope (docs/12)

Backend + admin. This is an operator-only support action over an existing admin surface. There is no mobile layer: no user-facing read path changes shape, and no user-visible effect is immediate (Context). No deferred layer exists, so no docs/12 §3 deferral requirement is needed.

## Risks / Trade-offs

- **[A premium row with an elapsed schedule now stays OVERDUE until an admin acts]** → It is visible as the OVERDUE count (the monitor's anomaly signal). It stays private, which is correct for a paying user, and resolving takes one click. It cannot occur under in-order webhooks: `INITIAL_PURCHASE` / `RENEWAL` / `GRANT` clear the schedule in the same UPDATE that sets premium.
- **[The ledger cap is ±1 under concurrency]** → It is accepted as a soft abuse cap, the same as every sibling.
- **[An admin resolves a row the worker is about to process]** → Both lock the row (`FOR UPDATE`). Whichever commits second sees `privacy_flip_scheduled_at IS NULL`:
  - the admin path returns `Ineligible` (no-op);
  - the worker's CTE simply does not match it.
  There is no double flip and no double audit.
- **[Request-clock vs DB-clock skew]** → Eligibility uses the injected route clock (the monitor's evaluation instant), so "OVERDUE in the table" and "eligible to resolve" agree within a request. Production uses the app-server clock. NTP-level skew against the DB's `NOW()` only shifts the boundary by milliseconds, and no grace promise depends on sub-second precision.

## Migration Plan

No schema migration. Deploy order is irrelevant: the worker predicate and the admin route are independent, and both are additive. Rollback is a code revert. Audit rows written meanwhile are immutable history with a new `action_type` that no reader enumerates.

## Open Questions

None blocking.
