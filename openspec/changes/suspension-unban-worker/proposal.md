## Why

Phase 1 #4 of `docs/08-Roadmap-Risk.md`. The `age-gate` change shipped `users.suspended_until` + the 18+ DOB CHECK; admins can apply 7-day suspensions today (per `docs/06-Security-Privacy.md § Suspension vs Ban`, line 291), but no automated path flips them back when the window elapses. This change adds the daily worker that does so.

It also pioneers the **`/internal/*` route prefix + Cloud Scheduler OIDC middleware** pattern (Phase 1 #28; canonical reference `docs/06-Security-Privacy.md § Internal Endpoint Security`, lines 413–451), establishing the auth + invocation contract reused by every future internal worker (privacy-flip, hard-delete, referral-activity-check, csam-webhook, fcm-cleanup, image-lifecycle, notifications-purge, moderation-archival).

## What Changes

- Add `POST /internal/unban-worker` — daily Cloud Scheduler invocation that runs the canonical UPDATE flipping `is_banned = FALSE` + `suspended_until = NULL` for users whose `suspended_until` window has elapsed. Permanent bans (`suspended_until IS NULL`) and soft-deleted users (`deleted_at IS NOT NULL`) are untouched. Returns `{ "unbanned_count": N }`.
- Add a Ktor plugin / route subtree gating all `/internal/*` endpoints with Google OIDC token verification: bearer token, signature against Google JWKS, `aud` claim match against a configured audience. Reject with HTTP 401 on missing / invalid / wrong-audience tokens. Composable so future endpoints with vendor-specific auth (`/internal/revenuecat-webhook`, `/internal/csam-webhook`) can opt out.
- Emit a structured INFO log per worker run with `event="suspension_unban_applied"`, `unbanned_count`, and the affected user IDs (capped at the first ~50 to avoid log-line bloat in pathological cases). This provides the operational trail until the Admin Panel's `admin_actions_log` lands in Phase 3.5; at that point a follow-up change wires audit-row writes through the same worker (tracked in `FOLLOW_UPS.md`). Canonical [`docs/05-Implementation.md:363`](docs/05-Implementation.md) prescribes "Audit log inserted per unban" against `admin_actions_log`; the table is deferred to Phase 3.5 and explicitly acknowledged absent in V9 (`reports.reviewed_by` and `moderation_queue.reviewed_by` carry "FK to admin_users(id) ON DELETE SET NULL — deferred to the Phase 3.5 admin-users migration" comments).
- No Flyway migration in this change. The schema (`users.is_banned`, `users.suspended_until`, `users_suspended_idx`) was shipped in V2 by `auth-foundation`; the worker is purely application code.
- Document Cloud Scheduler config (cron `0 21 * * *` UTC = 04:00 WIB, target URL `${SERVICE_URL}/internal/unban-worker`, OIDC service account, retry policy) so staging + prod deploys are reproducible.
- No mobile / client changes. No external API contract changes.
- No notification on unban (recommended; design.md surfaces alternatives — generic-reuse vs new type — for explicit decision before implementation).

## Capabilities

### New Capabilities
- `internal-endpoint-auth`: OIDC-bearer-token authentication contract for all `/internal/*` Cloud-Scheduler-triggered endpoints. Defines the verification rules (signature, audience, expiry), the rejection responses, and the composable opt-out mechanism for vendor-webhook endpoints.
- `suspension-unban-worker`: scheduled worker that flips time-bound user suspensions when `suspended_until` elapses. Defines the eligibility predicate (the canonical SQL), the audit-log row contract, the response shape, and the schedule.

### Modified Capabilities
<!-- None. Existing specs (auth-jwt, auth-session, age-gate, users-schema, health-check) are unaffected — the worker reads/writes user rows but doesn't change their requirement contracts. -->

## Impact

- **Code**: new `:infra:oidc` module with `GoogleOidcTokenVerifier`. `:core:domain` gains an `OidcTokenVerifier` interface. `:backend:ktor` adds the `InternalEndpointAuth` Ktor plugin, the `SuspensionUnbanWorker` service, and the `UnbanWorkerRoute`. ~300–400 LOC + tests.
- **Schema**: none. No Flyway migration in this change. The required columns shipped in V2 (`auth-foundation`).
- **Secrets**: new `internal-oidc-audience` secret name (per the project's `secretKey(env, name)` lint rule) — values populated separately for staging vs prod. The audience equals the deployed service URL per Cloud Scheduler convention.
- **Deployment**: Cloud Scheduler cron job + service-account OIDC binding documented for staging first, prod second.
- **APIs**: one new internal route. No public API surface.
- **CI**: existing `:backend:ktor:test` + lint pipeline. No new lint rules.
- **Docs**: `docs/05-Implementation.md § Suspension Unban Worker` is canonical and unchanged. The "Audit log inserted per unban" line describes the eventual end-state once Phase 3.5 ships `admin_actions_log`; the gap is explicitly documented in `design.md` § Reconciliation notes and tracked in `FOLLOW_UPS.md` so a future maintainer reading the canonical line doesn't conclude the audit row is missing in error.
