# Proposal: retention-cleanup-deferred-sweeps

## Why

`scheduled-retention-cleanup` (PR #360) shipped the `POST /internal/cleanup` worker with two sweeps explicitly bounded out as scope-boundary requirements + negative-guard scenarios, each tracked by a `follow-up` issue: the WebAuthn-challenge cleanup (#365, `docs/05` §750; `docs/08` Phase 3.5 item 13) and the 1-year retention of resolved `moderation_queue` + `reports` rows (#366, `docs/06` § Retention Policy; `docs/08` Phase 3.5 item 12). This change executes both deferred sweeps, closing the last two gaps between the written retention policy and the worker that enforces it.

## What Changes

- Add a **WebAuthn-challenge sweep** to the worker: `DELETE FROM admin_webauthn_challenges WHERE expires_at < NOW() - INTERVAL '1 day' AND consumed_at IS NULL` — verbatim from `docs/05` §750. Served by the existing `admin_webauthn_challenges_cleanup_idx` partial index. No migration.
- Add a **moderation-queue retention sweep**: `DELETE FROM moderation_queue WHERE status = 'resolved' AND resolved_at < NOW() - INTERVAL '1 year'` — the `docs/06` "Moderation queue (resolved rows) | 1 year" window.
- Add a **reports retention sweep**: `DELETE FROM reports WHERE status IN ('actioned', 'dismissed') AND reviewed_at < NOW() - INTERVAL '1 year'` — the `docs/06` "Reports (resolved) | 1 year" window.
- **Archival = deletion, no archive table**: the audit trail for moderation decisions lives in `admin_actions_log` (its own 1-year window). A copy-to-archive-table step would retain reporter/target PII past the written retention window, against the UU-PDP data-minimization posture. `docs/08` item 12's "archival worker" is satisfied by retention-enforcing deletes (design.md D1).
- **Cadence**: the sweeps join the existing per-invocation run (Cloud Scheduler, daily). `docs/05`/`docs/08` say "weekly" for these sweeps; an idempotent 1-day/1-year threshold delete run daily is a strict superset of weekly — no second endpoint or schedule (design.md D2).
- Worker result/response/log line grow three counts: `webauthn_challenges_deleted`, `moderation_queue_deleted`, `reports_deleted`.
- The two scope-boundary requirements in the spec flip from negative guards to positive sweep requirements.
- Closes #365 + #366.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `scheduled-retention-cleanup`: the two "out of scope (deferred)" requirements (WebAuthn challenges; moderation/reports archival) are RENAMED+MODIFIED into positive sweep requirements; the counts-response, idempotency, and log-line requirements are MODIFIED to carry the three new counts.

## Impact

- **Backend only** (`backend/ktor`): `RetentionCleanupRepository` (+3 sweep methods + SQL), `RetentionCleanupWorker` (+3 counts in result + log line), `RetentionCleanupRoutes` (+3 response fields), and their tests. No mobile/admin surface — the worker is Cloud-Scheduler-invoked (docs/12: no counterpart layer exists for an internal worker).
- **No migration**: all three sweeps are served by existing indexes (`admin_webauthn_challenges_cleanup_idx` V16 partial; `moderation_queue_status_idx` / `reports_status_idx` status-prefix).
- **No new dependency, no new endpoint, no auth change** — the OIDC gate and error classification are untouched.
