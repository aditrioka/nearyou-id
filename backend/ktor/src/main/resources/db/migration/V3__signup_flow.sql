-- Signup flow: reserved_usernames (+ seed), rejected_identifiers, username_history.
-- See docs/05-Implementation.md §§ Reserved Usernames Schema, Rejected Identifiers Schema,
-- Username Generation & Customization.

-- ============================================================================
-- reserved_usernames
-- ============================================================================
CREATE TABLE reserved_usernames (
    username    TEXT PRIMARY KEY,
    reason      VARCHAR(64) NOT NULL,
    source      VARCHAR(16) NOT NULL DEFAULT 'admin_added'
        CHECK (source IN ('seed_system', 'admin_added')),
    created_at  TIMESTAMPTZ DEFAULT NOW(),
    updated_at  TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX reserved_usernames_source_idx ON reserved_usernames (source);

CREATE OR REPLACE FUNCTION reserved_usernames_set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER reserved_usernames_updated_at_trigger
    BEFORE UPDATE ON reserved_usernames
    FOR EACH ROW EXECUTE FUNCTION reserved_usernames_set_updated_at();

CREATE OR REPLACE FUNCTION reserved_usernames_protect_seed()
RETURNS TRIGGER AS $$
BEGIN
    IF (TG_OP = 'DELETE' AND OLD.source = 'seed_system') THEN
        RAISE EXCEPTION 'Cannot delete seed_system reserved username: %', OLD.username;
    END IF;
    IF (TG_OP = 'UPDATE' AND OLD.source = 'seed_system' AND NEW.source <> 'seed_system') THEN
        RAISE EXCEPTION 'Cannot change source of seed_system reserved username: %', OLD.username;
    END IF;
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER reserved_usernames_protect_seed_trigger
    BEFORE UPDATE OR DELETE ON reserved_usernames
    FOR EACH ROW EXECUTE FUNCTION reserved_usernames_protect_seed();

-- Seed: documented words.
INSERT INTO reserved_usernames (username, reason, source) VALUES
    ('admin',         'system-reserved operational handle', 'seed_system'),
    ('support',       'system-reserved operational handle', 'seed_system'),
    ('moderator',     'system-reserved operational handle', 'seed_system'),
    ('system',        'system-reserved operational handle', 'seed_system'),
    ('nearyou',       'brand-reserved handle',              'seed_system'),
    ('staff',         'system-reserved operational handle', 'seed_system'),
    ('official',      'system-reserved operational handle', 'seed_system'),
    ('akun_dihapus',  'deleted-user tombstone handle',      'seed_system'),
    ('deleted_user',  'deleted-user tombstone handle',      'seed_system')
ON CONFLICT (username) DO NOTHING;

-- Seed: every 1-char handle drawn from [a-z0-9].
INSERT INTO reserved_usernames (username, reason, source)
SELECT c, 'reserved-short-handle', 'seed_system'
FROM unnest(string_to_array('a,b,c,d,e,f,g,h,i,j,k,l,m,n,o,p,q,r,s,t,u,v,w,x,y,z,0,1,2,3,4,5,6,7,8,9', ',')) AS c
ON CONFLICT (username) DO NOTHING;

-- Seed: every 2-char handle drawn from [a-z0-9][a-z0-9].
INSERT INTO reserved_usernames (username, reason, source)
SELECT a.c || b.c, 'reserved-short-handle', 'seed_system'
FROM unnest(string_to_array('a,b,c,d,e,f,g,h,i,j,k,l,m,n,o,p,q,r,s,t,u,v,w,x,y,z,0,1,2,3,4,5,6,7,8,9', ',')) AS a(c)
CROSS JOIN unnest(string_to_array('a,b,c,d,e,f,g,h,i,j,k,l,m,n,o,p,q,r,s,t,u,v,w,x,y,z,0,1,2,3,4,5,6,7,8,9', ',')) AS b(c)
ON CONFLICT (username) DO NOTHING;

-- ============================================================================
-- rejected_identifiers
-- ============================================================================
CREATE TABLE rejected_identifiers (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    identifier_hash  TEXT NOT NULL,
    identifier_type  VARCHAR(8) NOT NULL
        CHECK (identifier_type IN ('google', 'apple')),
    reason           VARCHAR(32) NOT NULL
        CHECK (reason IN ('age_under_18', 'attestation_persistent_fail')),
    rejected_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (identifier_hash, identifier_type)
);

CREATE INDEX rejected_identifiers_hash_idx ON rejected_identifiers (identifier_hash);

-- ============================================================================
-- username_history
-- ============================================================================
CREATE TABLE username_history (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    old_username   VARCHAR(60) NOT NULL,
    new_username   VARCHAR(60) NOT NULL,
    changed_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    released_at    TIMESTAMPTZ NOT NULL
);

CREATE INDEX username_history_old_lower_idx ON username_history (LOWER(old_username));
-- Plain B-tree (NOT partial): PG rejects WHERE released_at > NOW() (NOW() is STABLE, not IMMUTABLE).
CREATE INDEX username_history_released_idx  ON username_history (released_at);
CREATE INDEX username_history_user_idx      ON username_history (user_id, changed_at DESC);
