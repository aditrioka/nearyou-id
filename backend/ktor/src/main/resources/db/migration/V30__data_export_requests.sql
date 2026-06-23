-- V30: data export request lifecycle (account-data-export).
--
-- Renumbered V29 → V30 (2026-06-20): V29 was a 3-way collision across in-flight
-- branches (csam-detection-webhook-and-archive #358, referral-grant-worker #353,
-- this change #356). By PR-number order among the V29 cohort this change is the
-- middle claimant → V30. Re-verify at merge; bump again if V30 also collides.
--
-- `data_export_requests` — verbatim from docs/05-Implementation.md § Data Export
-- Requests Schema. Backs the UU-PDP data-portability producer: the trigger endpoint
-- (`POST /api/v1/account/export`) enqueues a `pending` row; the OIDC worker
-- (`/internal/data-export-worker`) gathers the docs/06 §350 scope matrix, uploads a
-- ZIP to R2, and transitions the row pending → processing → ready → (expired|failed).
--
-- No `notifications` change ships here — the `data_export_ready` type already exists in
-- the V10 catalog (the worker emits it with body_data {signed_url, expires_at}).
--
-- Partial-index-clean: both partial indexes filter on the `status` column only, never
-- `NOW()` (the partial-index invariant — a non-immutable WHERE is rejected by PG and by
-- the lint rule). The one-active partial UNIQUE index makes idempotency structural: a
-- second enqueue while a request is pending/processing raises a unique-violation the
-- route maps to "return the existing request" (a ready/expired/failed row never blocks a
-- fresh request — the active set is pending|processing only).

CREATE TABLE data_export_requests (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status              VARCHAR(16) NOT NULL DEFAULT 'pending' CHECK (status IN (
                            'pending', 'processing', 'ready', 'expired', 'failed'
                        )),
    requested_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    started_at          TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    r2_object_key       TEXT,
    download_expires_at TIMESTAMPTZ,
    attempt_count       INT NOT NULL DEFAULT 0,
    error               TEXT
);

-- Worker scan path: the oldest un-started requests.
CREATE INDEX data_export_requests_pending_idx
    ON data_export_requests(requested_at)
    WHERE status = 'pending';

-- One active request per user (structural idempotency; pending|processing only).
CREATE UNIQUE INDEX data_export_requests_one_active_idx
    ON data_export_requests(user_id)
    WHERE status IN ('pending', 'processing');

-- Latest-status lookup for `GET /api/v1/account/export`.
CREATE INDEX data_export_requests_user_recent_idx
    ON data_export_requests(user_id, requested_at DESC);
