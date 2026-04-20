-- Auth foundation: users + refresh_tokens + (when running on Supabase) realtime RLS policy.

CREATE TABLE users (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username                    VARCHAR(60) NOT NULL UNIQUE,
    display_name                VARCHAR(50) NOT NULL,
    bio                         VARCHAR(160),
    email                       TEXT,
    google_id_hash              TEXT UNIQUE,
    apple_id_hash               TEXT UNIQUE,
    apple_relay_email           BOOLEAN DEFAULT FALSE,
    date_of_birth               DATE NOT NULL,
    private_profile_opt_in      BOOLEAN NOT NULL DEFAULT FALSE,
    privacy_flip_scheduled_at   TIMESTAMPTZ,
    is_shadow_banned            BOOLEAN NOT NULL DEFAULT FALSE,
    is_banned                   BOOLEAN NOT NULL DEFAULT FALSE,
    suspended_until             TIMESTAMPTZ,
    device_fingerprint_hash     TEXT,
    token_version               INT NOT NULL DEFAULT 0,
    username_last_changed_at    TIMESTAMPTZ,
    invite_code_prefix          VARCHAR(8) NOT NULL UNIQUE,
    analytics_consent           JSONB NOT NULL DEFAULT '{"analytics": false, "crash": true, "ads_personalization": false}',
    subscription_status         VARCHAR(32) NOT NULL DEFAULT 'free'
        CHECK (subscription_status IN ('free', 'premium_active', 'premium_billing_retry')),
    inviter_reward_claimed_at   TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ DEFAULT NOW(),
    deleted_at                  TIMESTAMPTZ,
    CHECK (date_of_birth <= (CURRENT_DATE - INTERVAL '18 years'))
);

CREATE INDEX users_username_lower_idx     ON users (LOWER(username));
CREATE INDEX users_email_idx              ON users (email)              WHERE deleted_at IS NULL;
CREATE INDEX users_suspended_idx          ON users (suspended_until)    WHERE suspended_until IS NOT NULL;
CREATE INDEX users_subscription_idx       ON users (subscription_status) WHERE deleted_at IS NULL;
CREATE INDEX users_privacy_flip_idx       ON users (privacy_flip_scheduled_at) WHERE privacy_flip_scheduled_at IS NOT NULL;

CREATE TABLE refresh_tokens (
    id                       UUID PRIMARY KEY,
    family_id                UUID NOT NULL,
    user_id                  UUID NOT NULL REFERENCES users (id),
    device_fingerprint_hash  TEXT,
    token_hash               TEXT NOT NULL,
    created_at               TIMESTAMPTZ DEFAULT NOW(),
    used_at                  TIMESTAMPTZ,
    last_used_at             TIMESTAMPTZ,
    revoked_at               TIMESTAMPTZ,
    expires_at               TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX refresh_tokens_token_hash_idx ON refresh_tokens (token_hash);
CREATE        INDEX refresh_tokens_family_idx     ON refresh_tokens (family_id);
CREATE        INDEX refresh_tokens_user_idx       ON refresh_tokens (user_id);
CREATE        INDEX refresh_tokens_family_active_idx
    ON refresh_tokens (family_id) WHERE revoked_at IS NULL;
CREATE        INDEX refresh_tokens_expires_idx
    ON refresh_tokens (expires_at) WHERE revoked_at IS NULL;

-- Supabase realtime RLS. Plain Postgres has no `realtime` schema so the whole block
-- is skipped. Postgres has no CREATE POLICY IF NOT EXISTS, so DROP+CREATE is the
-- idempotent pattern.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.schemata WHERE schema_name = 'realtime') THEN
        EXECUTE $policy$
            DROP POLICY IF EXISTS participants_can_subscribe ON realtime.messages;
            CREATE POLICY participants_can_subscribe ON realtime.messages
            FOR SELECT USING (
                realtime.topic() ~ '^conversation:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                AND EXISTS (
                    SELECT 1 FROM public.conversation_participants cp
                    WHERE cp.conversation_id = (split_part(realtime.topic(), ':', 2))::uuid
                        AND cp.user_id = ((auth.jwt()->>'sub')::uuid)
                        AND cp.left_at IS NULL
                        AND NOT EXISTS (
                            SELECT 1 FROM public.users u
                            WHERE u.id = cp.user_id AND u.is_shadow_banned = TRUE
                        )
                )
            )
        $policy$;
    END IF;
END $$;
