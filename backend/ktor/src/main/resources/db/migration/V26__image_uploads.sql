-- Image-upload ownership ledger per openspec/changes/premium-image-upload-pipeline
-- (design D3). Backs the `premium-image-upload` capability: one row per image that
-- passed upfront Safe Search and was stored in Cloudflare Images. The ledger binds
-- the Cloudflare `image_id` to its uploader so the post-attach path
-- (`POST /api/v1/posts` with `image_id`) can authorize ownership — the existing
-- `posts.image_id TEXT` column (V4) is otherwise unvalidated free text. It is also
-- the future linkage point for the deferred CSAM subsystem
-- (`csam-detection-webhook-and-archive`) and the orphan-cleanup follow-up.
--
-- The daily 50/upload quota + 1/60s throttle live in Redis (rate-limit infra,
-- `computeTTLToNextReset`), NOT a DB count — this table is the ownership/lifecycle
-- ledger, not the rate-limit source of truth.
--
-- Partial-index-clean: the lone index has no `WHERE` clause (partial-index
-- invariant — no `NOW()` in WHERE).

CREATE TABLE image_uploads (
    -- The Cloudflare Images image id (also persisted into posts.image_id on attach).
    cf_image_id          TEXT PRIMARY KEY,
    uploader_user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Google Cloud Vision Safe Search verdict at upload, stored as the categorical
    -- Likelihood enum name (e.g. 'VERY_UNLIKELY' .. 'VERY_LIKELY'). Nullable for
    -- forward-compat (a verdict category Vision did not return).
    safe_search_adult    TEXT,
    safe_search_violence TEXT,
    safe_search_racy     TEXT,
    -- Lifecycle: 'uploaded' (stored, not yet on a post) -> 'attached' (bound to a post).
    -- The attach flip is a conditional UPDATE ... WHERE status = 'uploaded' so concurrent
    -- attaches of the same image resolve to exactly one winner.
    status               TEXT NOT NULL DEFAULT 'uploaded'
        CHECK (status IN ('uploaded', 'attached'))
);

-- Per-uploader, newest-first: serves the orphan-cleanup scan (deferred follow-up)
-- and admin/audit lookups. (Daily-quota counting is Redis-backed, not this index.)
CREATE INDEX image_uploads_uploader_idx ON image_uploads(uploader_user_id, created_at DESC);
