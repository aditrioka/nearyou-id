-- Supabase-parity starting state for the CI `migrate-supabase-parity` job.
-- This SQL is applied to a fresh `postgis/postgis:16-3.4` DB BEFORE Flyway
-- runs, to mirror the conditions on a freshly-provisioned Supabase project:
--
--   1. PostGIS (and friends) are INSTALLED but NOT ENABLED. The postgis image
--      auto-enables them via its entrypoint, which hides migrations that
--      forgot to `CREATE EXTENSION`. Dropping them here forces each migration
--      to enable what it needs. See V4 incident (PR #8).
--
--   2. A `realtime` schema exists with enough surface (`realtime.messages`,
--      `realtime.topic()`) for Supabase realtime RLS policies to COMPILE.
--      Plain Postgres has no realtime schema, so CI test jobs silently
--      skipped V2's RLS DO-block — hiding a broken reference to a
--      not-yet-created table. See V2 incident (PR #7).
--
--   3. An `auth.jwt()` stub for the same reason — policy bodies on Supabase
--      typically reference `auth.jwt()`.
--
-- This is a simulation, not a snapshot. Anything Supabase enables by default
-- that our migrations might depend on should be added here so CI catches
-- the gap before a broken deploy does.

DROP EXTENSION IF EXISTS postgis_topology CASCADE;
DROP EXTENSION IF EXISTS postgis CASCADE;
DROP EXTENSION IF EXISTS "uuid-ossp" CASCADE;
DROP EXTENSION IF EXISTS btree_gist CASCADE;

CREATE SCHEMA IF NOT EXISTS realtime;

CREATE TABLE IF NOT EXISTS realtime.messages (
    id BIGSERIAL PRIMARY KEY,
    topic TEXT NOT NULL DEFAULT ''
);
ALTER TABLE realtime.messages ENABLE ROW LEVEL SECURITY;

CREATE OR REPLACE FUNCTION realtime.topic()
    RETURNS TEXT
    LANGUAGE SQL
    STABLE
    AS $$ SELECT ''::TEXT $$;

CREATE SCHEMA IF NOT EXISTS auth;

CREATE OR REPLACE FUNCTION auth.jwt()
    RETURNS JSONB
    LANGUAGE SQL
    STABLE
    AS $$ SELECT '{}'::JSONB $$;
