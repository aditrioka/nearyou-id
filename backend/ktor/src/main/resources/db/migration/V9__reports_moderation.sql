-- Reports + moderation queue: user-submitted content reports and the admin-triage
-- signal store. Canonical DDL verbatim from docs/05-Implementation.md §745–816 — no
-- renames.
--
-- Dependency shape (fifth distinct shape in the migration pipeline):
--   * Schema-level single-side CASCADE FK on V3 `users`: `reports.reporter_id
--     REFERENCES users(id) ON DELETE CASCADE`. This matches the V5 `user_blocks` /
--     V6 `follows` / V7 `post_likes` CASCADE precedent (not the V4 `posts` / V8
--     `post_replies` RESTRICT precedent): a user's submitted reports are included
--     in their Data Export, so hard-delete cascade is safe and matches the
--     privacy policy.
--   * Polymorphic `target_id UUID NOT NULL` on both tables WITHOUT an FK to any
--     single target table. The four target_types (`post`, `reply`, `user`,
--     `chat_message`) span four different tables — a polymorphic FK is not
--     expressible as a single constraint in Postgres. Existence is enforced at
--     the application layer (ReportService runs SELECT 1 per target_type before
--     INSERT; missing/soft-deleted target → 404).
--   * Deferred-FK convention (FIRST in the pipeline): `reports.reviewed_by` and
--     `moderation_queue.resolved_by` are plain UUID columns here — the FK to
--     `admin_users(id) ON DELETE SET NULL` is deferred to the Phase 3.5
--     admin-users migration, which will ADD CONSTRAINT ... NOT VALID + VALIDATE.
--     Each column carries a COMMENT ON COLUMN recording the deferred target so
--     grep/schema-diff tooling stays aware.
--   * No runtime coupling to prior migrations — V9's auto-hide writer flips
--     `posts.is_auto_hidden` (reserved by V4) and `post_replies.is_auto_hidden`
--     (reserved by V8); V4's `visible_posts` view and V8's reply-list WHERE
--     clause already filter the flag, so the flip is a write-only coupling.
--
-- V9-era consumers:
--   * ReportService POST /api/v1/reports (rate-limited 10/hour/user; target-
--     existence check per target_type; self-report rejection; 409 on UNIQUE
--     violation).
--   * Transactional auto-hide path — same DB transaction as reports INSERT:
--     COUNT(DISTINCT r.reporter_id) JOIN users u WHERE u.created_at < NOW() -
--     INTERVAL '7 days' AND u.deleted_at IS NULL; if ≥3, UPDATE posts /
--     post_replies SET is_auto_hidden = TRUE WHERE id = ? AND deleted_at IS
--     NULL (users / chat_messages have no such column, so only the queue row
--     is written).
--   * `auto_hide_3_reports` queue writer — INSERT INTO moderation_queue
--     ... ON CONFLICT (target_type, target_id, trigger) DO NOTHING.
--
-- Reference: docs/05-Implementation.md §745–816 is the canonical source for every
-- column, CHECK constraint enum, and index declared below.

CREATE TABLE reports (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reporter_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    target_type     VARCHAR(16) NOT NULL CHECK (target_type IN ('post', 'reply', 'user', 'chat_message')),
    target_id       UUID NOT NULL,
    reason_category VARCHAR(32) NOT NULL CHECK (reason_category IN (
        'spam',
        'hate_speech_sara',
        'harassment',
        'adult_content',
        'misinformation',
        'self_harm',
        'csam_suspected',
        'other'
    )),
    reason_note     VARCHAR(200),
    status          VARCHAR(16) NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'actioned', 'dismissed')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    reviewed_at     TIMESTAMPTZ,
    reviewed_by     UUID,
    UNIQUE (reporter_id, target_type, target_id)
);

CREATE INDEX reports_status_idx   ON reports (status, created_at DESC);
CREATE INDEX reports_target_idx   ON reports (target_type, target_id);
CREATE INDEX reports_reporter_idx ON reports (reporter_id, created_at DESC);

COMMENT ON COLUMN reports.reviewed_by IS
    'FK to admin_users(id) ON DELETE SET NULL — deferred to the Phase 3.5 admin-users migration.';

CREATE TABLE moderation_queue (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    target_type VARCHAR(16) NOT NULL CHECK (target_type IN ('post', 'reply', 'user', 'chat_message')),
    target_id   UUID NOT NULL,
    trigger     VARCHAR(32) NOT NULL CHECK (trigger IN (
        'auto_hide_3_reports',
        'perspective_api_high_score',
        'uu_ite_keyword_match',
        'admin_flag',
        'csam_detected',
        'anomaly_detection',
        'username_flagged'
    )),
    status      VARCHAR(16) NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'resolved')),
    resolution  VARCHAR(32) CHECK (resolution IS NULL OR resolution IN (
        'keep',
        'hide',
        'delete',
        'shadow_ban_author',
        'suspend_author_7d',
        'ban_author',
        'accept_flagged_username',
        'reject_flagged_username'
    )),
    priority    SMALLINT NOT NULL DEFAULT 5,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_at TIMESTAMPTZ,
    resolved_by UUID,
    notes       TEXT,
    UNIQUE (target_type, target_id, trigger)
);

CREATE INDEX moderation_queue_status_idx ON moderation_queue (status, priority, created_at);
CREATE INDEX moderation_queue_target_idx ON moderation_queue (target_type, target_id);

COMMENT ON COLUMN moderation_queue.resolved_by IS
    'FK to admin_users(id) ON DELETE SET NULL — deferred to the Phase 3.5 admin-users migration.';
