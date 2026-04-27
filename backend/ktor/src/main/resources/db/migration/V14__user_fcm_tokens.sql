-- V14: FCM device-token registration table.
--
-- Backs the Phase-1 prerequisite for push notifications:
-- POST /api/v1/user/fcm-token upserts on (user_id, platform, token)
-- and refreshes last_seen_at on every call. Phase 2 will read this
-- table to address devices; Phase 3.5 weekly cleanup will DELETE
-- rows with last_seen_at < NOW() - INTERVAL '30 days'.
--
-- Schema mirrors docs/05-Implementation.md:1376-1389 verbatim, with
-- two ADDITIVE defense-in-depth CHECKs that the canonical schema
-- omits (see openspec/changes/fcm-token-registration/design.md D9):
--   * token         CHECK (char_length BETWEEN 1 AND 4096) — mirrors
--                   the 4 KB transport-layer body cap.
--   * app_version   CHECK (NULL OR char_length <= 64) — mirrors the
--                   handler-side cap.
-- Same posture as the canonical CHECK on platform.
--
-- UNIQUE is (user_id, platform, token) — NOT (token) alone — to
-- support family-shared devices (see design.md D1).

CREATE TABLE user_fcm_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    platform VARCHAR(8) NOT NULL CHECK (platform IN ('android', 'ios')),
    token TEXT NOT NULL CHECK (char_length(token) BETWEEN 1 AND 4096),
    app_version TEXT CHECK (app_version IS NULL OR char_length(app_version) <= 64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, platform, token)
);

CREATE INDEX user_fcm_tokens_user_idx ON user_fcm_tokens(user_id);
CREATE INDEX user_fcm_tokens_last_seen_idx ON user_fcm_tokens(last_seen_at);
