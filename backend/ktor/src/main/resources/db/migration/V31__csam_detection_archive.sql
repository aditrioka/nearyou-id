-- CSAM detection archive per openspec/changes/csam-detection-webhook-and-archive.
-- Backs the `csam-detection` capability: one legal-preservation row per CSAM match
-- detected by the Cloudflare CSAM Scanning Tool (HTTP 451 + daily email) and acted
-- on by the `/internal/csam-webhook` takedown handler. The matched image is keyed
-- back to its uploader via the `image_uploads` ledger (V26) at takedown time.
--
-- Preservation model: only the columns needed to file the Kominfo (Ditjen Aptika)
-- report are plaintext (`image_hash`, `ncmec_reference`, `cf_match_id`); all other
-- (sensitive) detail — including the offending uploader's identity — lives in the
-- AES-256-GCM `encrypted_metadata` blob (`csam-archive-aes-key`, admin-only decrypt).
-- NO image bytes are ever retained.
--
-- Legal preservation deliberately has NO foreign key to `users`: the `image_uploads`
-- ledger row is ON DELETE CASCADE to users and vanishes when the banned uploader is
-- hard-deleted, so the uploader id is snapshotted into `encrypted_metadata` during the
-- takedown tx and this row survives the account deletion independently (docs/06
-- retention matrix: "CSAM detection archive: 90+ days, until Kominfo + investigation
-- fulfilled").
--
-- `source` is 2-value (admin-manual MVP + CF-Worker Phase 2 per resolved Open
-- Decision #33); the design-era "email poll" third path is rejected.
--
-- Partial-index-clean: the purge-support index predicate uses only the immutable
-- `kominfo_reported_at IS NOT NULL` (no NOW() in any index WHERE).

CREATE TABLE csam_detection_archive (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- The NCMEC-matched fuzzy image hash. UNIQUE = the dedup key: one archive row
    -- per matched image (ON CONFLICT (image_hash) DO UPDATE enriches, never duplicates).
    image_hash          TEXT NOT NULL UNIQUE,
    -- The Cloudflare match identifier. Nullable (the admin-manual paste path may not
    -- have it); partial UNIQUE below dedups it when present.
    cf_match_id         TEXT,
    -- The matched NCMEC / partner-list reference, plaintext for Kominfo filing.
    ncmec_reference     TEXT,
    -- How the takedown was triggered. CF-Worker forwarding is Phase 2+.
    source              TEXT NOT NULL CHECK (source IN ('admin_manual', 'cf_worker')),
    -- AES-256-GCM ciphertext of the sensitive metadata (uploader id, post id, etc.).
    -- Nullable: fail-soft when `csam-archive-aes-key` is unprovisioned (pre-launch);
    -- the takedown still runs and the plaintext essentials above still persist.
    encrypted_metadata  BYTEA,
    -- Kominfo filing bookkeeping (set later by the deferred admin review surface).
    -- kominfo_reported_at IS NULL = report still pending.
    kominfo_report_id   TEXT,
    kominfo_reported_at  TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 90-day preservation deadline. Defaulted (not GENERATED — `timestamptz + interval`
    -- is STABLE, not IMMUTABLE, so a generated column is rejected); both defaults
    -- evaluate the same transaction `now()`, so expires_at = created_at + 90 days
    -- exactly on insert. Preservation past this is extended only while the report is
    -- unfiled (the purge worker holds on kominfo_reported_at IS NULL), not by mutating
    -- this column; ON CONFLICT enrich leaves created_at/expires_at untouched.
    expires_at          TIMESTAMPTZ NOT NULL DEFAULT (now() + INTERVAL '90 days')
);

-- Dedup the Cloudflare match id when present; multiple NULLs coexist (admin-manual rows).
CREATE UNIQUE INDEX csam_detection_archive_cf_match_idx
    ON csam_detection_archive(cf_match_id)
    WHERE cf_match_id IS NOT NULL;

-- Serves the daily purge scan (DELETE WHERE expires_at < NOW() AND kominfo_reported_at
-- IS NOT NULL). Partial on the immutable IS-NOT-NULL predicate so NOW() never enters a
-- WHERE clause; the time comparison stays in the DELETE statement, not the index.
CREATE INDEX csam_detection_archive_purge_idx
    ON csam_detection_archive(expires_at)
    WHERE kominfo_reported_at IS NOT NULL;
