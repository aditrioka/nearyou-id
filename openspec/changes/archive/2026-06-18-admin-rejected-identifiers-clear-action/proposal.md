## Why

The `admin-rejected-identifiers-viewer` (PR #156) ships a read-only triage surface over the `rejected_identifiers` anti-abuse blocklist but **no way to act on a row**. When the age gate or persistent-attestation-fail path falsely rejects a legitimate adult, the only way to let them re-verify today is an out-of-band raw-SQL `DELETE` against production (per the staging/ops runbook). That is slow, unaudited, and error-prone. The viewer's own spec already **defers this exact write action by name** (`admin-rejected-identifiers-clear-action`) and tracking issue [#190](https://github.com/aditrioka/nearyou-id/issues/190) holds it open. This change ships the missing write-half: a role-gated, CSRF-gated, audit-logged, rate-limited single-row clear from the admin panel.

## What Changes

- Add `POST /admin/rejected-identifiers/{id}/clear` — a hard `DELETE` of one `rejected_identifiers` row, performed in the **same JDBC transaction** as a single immutable `admin_actions_log` insert (`action_type = 'rejected_identifier_cleared'`, `before_state` JSONB capturing the cleared row).
- **Write-role gate** the action to `owner` / `admin` only (the read view stays open to every admin role — unchanged). Clearing weakens an anti-abuse safety control, so it is restricted to the higher-trust roles that also hold ban / chat-redaction.
- **CSRF-gate** the action via `X-CSRF-Token` vs `admin_sessions.csrf_token_hash`, checked **before** role **before** parse (mirrors `admin-reserved-usernames-editor`).
- **Reason required** — non-blank, length-bounded; a blank/whitespace or over-length reason is rejected with no write.
- **Rate-limit** the action at a dedicated **10 clears / admin / trailing hour**, sourced from the `admin_actions_log` audit trail (mechanism mirrors the reserved-usernames editor). See `design.md` D1 for why this is a *dedicated* cap rather than the shared `admin-destructive-action-rate-limit` (20/hr punitive) limiter.
- **Idempotent / not-found** — clearing a nonexistent or already-cleared id is a graceful no-op (no mutation, no audit row, no 5xx).
- Render a per-row **clear control** (destructive-action confirm + reason input) in the viewer table — visible only to `owner`/`admin` sessions — with an HTMX row-swap on success and a plain-`POST` full-page fallback. All output HTML-escaped (defense-in-depth, consistent with the viewer).
- **No Flyway migration** — `admin_actions_log.action_type` is `VARCHAR(64)` with no CHECK constraint, and both tables already exist (see `design.md` D3).

## Capabilities

### New Capabilities
<!-- none — this folds the write action into the existing viewer capability, matching the admin-report-queue precedent (resolution actions were added to admin-report-queue, not a new capability). -->

### Modified Capabilities

- `admin-rejected-identifiers-viewer`: flip the deferred clear action to **implemented** (RENAMED + MODIFIED of the "manual support-clear action is deferred to a fast-follow change" requirement); narrow the "adds only read routes; mutation methods are unmapped" requirement to scope its no-mutation guard to the **collection** path only (carving out the new `{id}/clear` sub-route); and ADD the clear-action requirements (endpoint + audit, CSRF+role gating, reason validation, idempotency, rate-limit, HTMX control, escaping).

## Impact

- **Backend (`:backend:ktor` `admin` package)**: new `POST /admin/rejected-identifiers/{id}/clear` route handler; a `clear(...)`/`delete(...)` + audit path on `AdminRejectedIdentifiersRepository`; a trailing-hour clear-count query on the same repo; Koin wiring; one new `action_type` string literal.
- **Templates**: per-row clear control + confirm/reason affordance in the rejected-identifiers Pebble template + table fragment (admin CSS / static-asset SHA256SUMS re-pin if `admin.css` is touched).
- **Schema**: none (no migration).
- **Tests**: Kotest DB-tagged tests for the endpoint, audit-row shape (incl. `before_state`), CSRF/role/reason/idempotency gates, and the dedicated rate-limit cap; plus admin-panel manual bring-up with screenshot evidence (UI-affecting → docs/11 §5 DoD).
- **Docs**: `docs/07-Operations.md` § Core Features (Rejected Identifiers Viewer) updates the "manual clear path remains DESIGN" line to shipped; resolves issue #190.
- **Security posture**: introduces the first mutation surface over `rejected_identifiers` — owner/admin + CSRF + audit + rate-limit are the compensating controls.
