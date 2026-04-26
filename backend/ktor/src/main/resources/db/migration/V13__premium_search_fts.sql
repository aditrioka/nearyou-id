-- V13: Premium search full-text + fuzzy infrastructure.
--
-- Ships the FTS schema that V4 explicitly deferred. See
-- backend/ktor/src/main/resources/db/migration/V4__post_creation.sql:8
-- ("FTS-specific columns (content_tsv + GIN indexes) are deferred to
-- the Search change."). This migration IS that change.
--
-- Five additive statements, in order:
--   1. CREATE EXTENSION pg_trgm — for the `%` similarity operator and
--      gin_trgm_ops opclass. Bundled with Postgres 16; no Supabase-parity
--      change required (verified via task 1.8).
--   2. ALTER TABLE posts ADD COLUMN content_tsv ... GENERATED ALWAYS
--      AS (to_tsvector('simple', content)) STORED — auto-populates on
--      INSERT and auto-regenerates on UPDATE. The 'simple' config is
--      the canonical MVP choice (no Indonesian stemming/stopwords)
--      per docs/05-Implementation.md:1159. Direct writes to content_tsv
--      are rejected by Postgres' GENERATED ALWAYS contract.
--   3. CREATE INDEX posts_content_tsv_idx — GIN on the tsvector column,
--      backs `@@ plainto_tsquery('simple', :query)` lookups.
--   4. CREATE INDEX posts_content_trgm_idx — GIN trigram on raw content,
--      backs `content % :query` fuzzy lookups.
--   5. CREATE INDEX users_username_trgm_idx — GIN trigram on username,
--      backs `username % :query` fuzzy lookups in the canonical search
--      query (docs/05-Implementation.md:1163-1181).
--
-- Reversibility: each step is undoable with a corresponding DROP. No
-- triggers, no seed data, no column drops. The deliberate absence of
-- a re-index trigger (which docs/02-Product.md:282 declares) is tracked
-- in FOLLOW_UPS.md § premium-search-reindex-trigger-doc-divergence —
-- the view + GIN combination handles correctness without one until a
-- Redis search-result cache lands at Month 6+ scale.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

ALTER TABLE posts
    ADD COLUMN content_tsv TSVECTOR
    GENERATED ALWAYS AS (to_tsvector('simple', content)) STORED;

CREATE INDEX posts_content_tsv_idx ON posts USING GIN (content_tsv);

CREATE INDEX posts_content_trgm_idx ON posts USING GIN (content gin_trgm_ops);

CREATE INDEX users_username_trgm_idx ON users USING GIN (username gin_trgm_ops);

-- The `visible_posts` view was created in V4 with `SELECT * FROM posts`. Postgres
-- expands `*` to the literal column list at view-creation time, so the existing
-- view does NOT surface the new `content_tsv` column. Re-running CREATE OR REPLACE
-- VIEW re-expands `*` against the current `posts` schema, picking up content_tsv.
-- The view's WHERE predicate is unchanged.
CREATE OR REPLACE VIEW visible_posts AS
    SELECT * FROM posts WHERE is_auto_hidden = FALSE;
