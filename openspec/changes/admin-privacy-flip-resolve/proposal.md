## Why

The shipped `admin-privacy-flip-monitor` (`GET /admin/privacy-flips`, admin board frame 17) makes a stuck privacy-flip row *discoverable*. An OVERDUE row has a passed deadline that the worker never cleared. But the only fix today is an out-of-band raw-SQL worker correction ("anomalies are escalated to an out-of-band worker fix"). That path is slow and unaudited. Issue [#277](https://github.com/aditrioka/nearyou-id/issues/277) tracks the missing in-panel write action. The monitor spec already carries the negative guard ("no clear / expedite control"), so shipping the write is a `MODIFIED` capability.

A second, entangled gap: the `privacy-flip-worker` eligibility predicate never reads `subscription_status`. Suppose a scheduled row's owner is premium again. One way is an out-of-order RevenueCat delivery: a `BILLING_ISSUE` retried after an `EXPIRATION`. That sets status to billing-retry but never clears the schedule. The worker would then flip `private_profile_opt_in` off for a paying user. The resolve action must refuse to do exactly that (triage, #277 comment). Guarding only the admin path would leave the worker doing it an hour later.

## What Changes

- **ADD `POST /admin/privacy-flips/{user_id}/resolve`.** It resolves one OVERDUE privacy-flip row in ONE transaction:
  - Lock the user row and re-check the target state. The target must have a non-NULL `privacy_flip_scheduled_at` at or before the request's evaluation instant.
  - **Re-check `subscription_status` inside that transaction**, then branch:
    - **Not premium** (`free`): apply the worker's flip (`private_profile_opt_in = FALSE`, `privacy_flip_scheduled_at = NULL`).
    - **Premium again** (`premium_active` / `premium_billing_retry`, so the webhook missed clearing): clear the schedule ONLY. Never make a paying user's profile public.
  - Write exactly one immutable `admin_actions_log` row (`action_type = 'privacy_flip_resolved'`). Its `before_state` / `after_state` record the resolution branch.
- **Gating**, mirroring the sibling admin writes in this order:
  - CSRF first (`AdminCsrfGate.validateCsrf`).
  - Then the `owner`/`admin` role.
  - Then parse the path UUID (400).
  - Then a **required reason** (400).
- **Ineligible targets** are a no-op with no mutation and no audit row, surfaced in-band, never a 5xx:
  - an IN_WINDOW row (resolving early would cut a user's grace short);
  - an already-cleared row;
  - an unknown id.
- **Rate-limit** at a dedicated **10 resolves / admin / trailing hour**. The `privacy_flip_resolved` counter is sourced from the audit trail (another instance of the audit-ledger soft-cap pattern). It is independent of the 20/hour destructive budget, because resolving is corrective bookkeeping, not a punitive action.
- **UI.** Add a per-row resolve control (reason input + `hx-confirm`) on OVERDUE rows, visible to `owner`/`admin` sessions only. It ships with a quota chip, an in-band outcome message, an HTMX fragment swap, and a plain-`POST` 303 PRG fallback. The page drops its `READ-ONLY` chip. Admin board frame 17 is updated to show the control.
- **Privacy-flag write allowlist.** Add a third declared writer, `// @allow-privacy-write: admin_resolve`, to the canonical invariant in `openspec/project.md` § Coding Conventions (and its `docs/08` lint description). Today the list is only `worker` and `user_settings`.
- **Worker guard.** `privacy-flip-worker` eligibility adds `subscription_status NOT IN ('premium_active', 'premium_billing_retry')`. An elapsed schedule on a premium user is left OVERDUE: the monitor surfaces it and the admin resolve clears it. The worker no longer flips a paying user public.
- **No Flyway migration.** `admin_actions_log.action_type` is a plain `VARCHAR(64)` with no CHECK, and every column already exists.

## Capabilities

### New Capabilities
<!-- none — the write folds into the existing monitor capability (the admin-rejected-identifiers-clear-action precedent). -->

### Modified Capabilities

- `admin-privacy-flip-monitor`: RENAMED + MODIFIED "The capability adds only read routes; mutation methods are unmapped" → the collection path stays read-only, and mutation is confined to the `/{user_id}/resolve` sub-route. ADDED requirements for the resolve action:
  - endpoint + subscription-aware branch + audit;
  - CSRF + owner/admin gating order;
  - required reason;
  - OVERDUE-only eligibility / idempotent no-op;
  - dedicated rate-limit;
  - atomicity;
  - the owner/admin-only HTMX control.
- `privacy-flip-worker`: MODIFIED "Hourly worker flips elapsed-grace private profiles to public". Eligibility excludes premium users (`premium_active` / `premium_billing_retry`), so the worker never resets a paying user's privacy opt-in.

## Impact

- **Backend (`:backend:ktor` admin package)**:
  - `AdminPrivacyFlipsRoute.kt`: the POST handler, plus the list render refactored to accept an outcome message.
  - `AdminPrivacyFlipsRepository.kt`: the `resolve(...)` transaction.
  - New `ratelimit/PrivacyFlipResolveRateLimiter.kt`.
  - `AdminAuditLogger.kt`: `logPrivacyFlipResolved`.
  - `AdminModule.kt`: wiring.
  - `PrivacyFlipWorker.kt`: one eligibility predicate.
- **Templates**: `privacy-flips-table.peb` (resolve control, quota chip, outcome message) and `privacy-flips.peb` (header/sub-copy). No `admin.css` change: the resolve form reuses the existing `form.expedite` in-row action style, so the static-asset SHA256SUMS is untouched.
- **Schema**: none.
- **Tests**: Kotest DB-tagged route + repository tests for every ADDED/MODIFIED scenario. The existing 405 test moves to the bare collection path. A worker test covers the premium exclusion.
- **Docs**:
  - `docs/07-Operations.md` § Privacy Flip Monitor: the resolve write is shipped.
  - `docs/05-Implementation.md` "Effective private" paragraph: writer list.
  - `openspec/project.md` + `docs/08-Roadmap-Risk.md`: privacy-write allowlist gains `admin_resolve`.
  - `dev/mockups/nearyou-admin-mockup.html` frame 17: control + caption.
- **Layers** (docs/12): backend + admin. This is an admin-only support tool, so no mobile surface is applicable (no user-facing read or write changes; a resolve causes no immediate visibility change, see design D2).
- **Security posture**: this adds the first mutation over `users.private_profile_opt_in` / `privacy_flip_scheduled_at` from the admin panel. The compensating controls are owner/admin + CSRF + required reason + audit + a dedicated rate-limit + the in-transaction subscription re-check.
- Resolves #277.
