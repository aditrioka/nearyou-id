-- Admin schema bootstrap: the five admin tables + three operational-table FK backfills.
-- Canonical DDL verbatim from docs/05-Implementation.md § Admin Users Schema
-- (lines 639-690) and § Admin Actions Log Schema (lines 1189-1205).
-- Spec: openspec/specs/admin-schema (post-archive) — change admin-schema-bootstrap.
--
-- Scope:
--   1. CREATE the five admin tables: admin_users, admin_webauthn_credentials,
--      admin_sessions, admin_webauthn_challenges, admin_actions_log.
--   2. Backfill the three previously-deferred operational-table admin-FKs via
--      ADD CONSTRAINT ... NOT VALID + VALIDATE CONSTRAINT (matches the V9
--      deferral comment's prescription at V9__reports_moderation.sql:20-21):
--      - reports.reviewed_by              → admin_users(id) ON DELETE SET NULL
--      - moderation_queue.resolved_by     → admin_users(id) ON DELETE SET NULL
--      - chat_messages.redacted_by        → admin_users(id) ON DELETE SET NULL
--   3. Replace the now-obsolete deferred-FK COMMENT ON COLUMN text on those
--      three columns with text describing the now-shipped FK.
--
-- Explicitly out of scope (see admin-schema-bootstrap/design.md):
--   * GRANT / REVOKE on the admin_app role — provisioned in Supabase Console
--     per docs/08-Roadmap-Risk.md Pre-Phase 1 #28 (D4 keeps role permissions
--     out of Flyway so V16 is environment-portable).
--   * RLS policies on admin tables — require an identity claim source from
--     Admin #2 session middleware (D5 defers).
--   * Sentinel `system` admin user seed — deferred to the follow-up change
--     `system-actor-and-worker-audit-rows` (D6 + FOLLOW_UPS entry
--     suspension-unban-worker-audit-log-after-phase-3.5).
--   * No Kotlin code ships with this migration.
--
-- FK ON DELETE semantics:
--   * admin_webauthn_credentials / admin_sessions / admin_webauthn_challenges
--     all CASCADE on admin_users delete — session/credential rows have no
--     meaning without their owning admin.
--   * admin_actions_log.admin_id ships with NO explicit ON DELETE clause
--     (Postgres default NO ACTION rejects orphaning hard-delete). This is
--     verbatim per docs/05-Implementation.md:1191 and matches the audit-trail-
--     preservation design at design.md D3 — application-layer code is
--     expected to use `is_active = FALSE` flag-flip, not row DELETE.
--   * Operational-table backfills (reports/moderation_queue/chat_messages) use
--     ON DELETE SET NULL per the openspec/project.md § Coding Conventions
--     invariant: "Admin-user FKs on operational tables must use ON DELETE
--     SET NULL".

CREATE TABLE admin_users (
    id                     UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email                  TEXT         NOT NULL UNIQUE,
    display_name           VARCHAR(100) NOT NULL,
    password_hash          TEXT         NOT NULL,
    totp_secret_encrypted  BYTEA,
    webauthn_enrolled      BOOLEAN      NOT NULL DEFAULT FALSE,
    role                   VARCHAR(16)  NOT NULL CHECK (role IN ('owner', 'admin', 'moderator', 'read_only')),
    is_active              BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_login_at          TIMESTAMPTZ
);

COMMENT ON COLUMN admin_users.password_hash IS
    'Argon2id hash of the admin password. Verification + parameter choice in the Admin #3 admin-login flow.';

COMMENT ON COLUMN admin_users.totp_secret_encrypted IS
    'AES-256 ciphertext of the TOTP shared secret. Encryption key in GCP Secret Manager (rotation policy lives with the key, not the schema).';

CREATE TABLE admin_webauthn_credentials (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_id        UUID        NOT NULL REFERENCES admin_users(id) ON DELETE CASCADE,
    credential_id   BYTEA       NOT NULL UNIQUE,
    public_key      BYTEA       NOT NULL,
    sign_count      BIGINT      NOT NULL DEFAULT 0,
    device_label    TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_used_at    TIMESTAMPTZ
);

CREATE TABLE admin_sessions (
    id                   UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_id             UUID         NOT NULL REFERENCES admin_users(id) ON DELETE CASCADE,
    session_token_hash   TEXT         NOT NULL UNIQUE,
    csrf_token_hash      TEXT         NOT NULL,
    ip                   INET         NOT NULL,
    user_agent           TEXT,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_active_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at           TIMESTAMPTZ  NOT NULL,
    revoked_at           TIMESTAMPTZ
);

CREATE INDEX admin_sessions_admin_idx  ON admin_sessions (admin_id, created_at DESC);
CREATE INDEX admin_sessions_active_idx ON admin_sessions (expires_at) WHERE revoked_at IS NULL;

CREATE TABLE admin_webauthn_challenges (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_id     UUID         REFERENCES admin_users(id) ON DELETE CASCADE,
    challenge    BYTEA        NOT NULL,
    ceremony     VARCHAR(16)  NOT NULL CHECK (ceremony IN ('registration', 'authentication')),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at   TIMESTAMPTZ  NOT NULL,
    consumed_at  TIMESTAMPTZ
);

CREATE INDEX admin_webauthn_challenges_admin_idx   ON admin_webauthn_challenges (admin_id, created_at DESC);
CREATE INDEX admin_webauthn_challenges_cleanup_idx ON admin_webauthn_challenges (expires_at) WHERE consumed_at IS NULL;

CREATE TABLE admin_actions_log (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_id      UUID         NOT NULL REFERENCES admin_users(id),
    action_type   VARCHAR(64)  NOT NULL,
    target_type   VARCHAR(32),
    target_id     TEXT,
    reason        TEXT,
    before_state  JSONB,
    after_state   JSONB,
    ip            INET,
    user_agent    TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX admin_actions_admin_idx  ON admin_actions_log (admin_id, created_at DESC);
CREATE INDEX admin_actions_target_idx ON admin_actions_log (target_type, target_id);
CREATE INDEX admin_actions_type_idx   ON admin_actions_log (action_type, created_at DESC);

-- FK backfill #1: reports.reviewed_by → admin_users(id) ON DELETE SET NULL.
-- NOT VALID + VALIDATE pattern per the V9:20-21 deferral comment's prescription.
ALTER TABLE reports
    ADD CONSTRAINT reports_reviewed_by_fkey
    FOREIGN KEY (reviewed_by) REFERENCES admin_users(id) ON DELETE SET NULL
    NOT VALID;

ALTER TABLE reports VALIDATE CONSTRAINT reports_reviewed_by_fkey;

COMMENT ON COLUMN reports.reviewed_by IS
    'FK to admin_users(id) ON DELETE SET NULL — set to NULL when the reviewing admin is hard-deleted; admin churn does not erase moderation history.';

-- FK backfill #2: moderation_queue.resolved_by → admin_users(id) ON DELETE SET NULL.
ALTER TABLE moderation_queue
    ADD CONSTRAINT moderation_queue_resolved_by_fkey
    FOREIGN KEY (resolved_by) REFERENCES admin_users(id) ON DELETE SET NULL
    NOT VALID;

ALTER TABLE moderation_queue VALIDATE CONSTRAINT moderation_queue_resolved_by_fkey;

COMMENT ON COLUMN moderation_queue.resolved_by IS
    'FK to admin_users(id) ON DELETE SET NULL — set to NULL when the resolving admin is hard-deleted; admin churn does not erase queue resolution history.';

-- FK backfill #3: chat_messages.redacted_by → admin_users(id) ON DELETE SET NULL.
-- The redaction-atomicity CHECK at V15 keeps redacted_at + redacted_by coupled;
-- post-V16, redacted_by is a validated admin FK rather than a free UUID. The
-- companion deferred-FK on chat_messages.embedded_post_edit_id is unrelated to
-- this change (target table post_edits ships with a future post-edit-history
-- change) and remains deferred.
ALTER TABLE chat_messages
    ADD CONSTRAINT chat_messages_redacted_by_fkey
    FOREIGN KEY (redacted_by) REFERENCES admin_users(id) ON DELETE SET NULL
    NOT VALID;

ALTER TABLE chat_messages VALIDATE CONSTRAINT chat_messages_redacted_by_fkey;

COMMENT ON COLUMN chat_messages.redacted_by IS
    'FK to admin_users(id) ON DELETE SET NULL — set to NULL when the redacting admin is hard-deleted; redaction history is preserved.';
