## ADDED Requirements

### Requirement: posts.content_tsv GENERATED column + GIN FTS indexes (V13 migration)

A Flyway migration `V13__premium_search_fts.sql` SHALL extend the `posts` table with the FTS infrastructure that V4 explicitly deferred (see V4 file comment: "FTS-specific columns (content_tsv + GIN indexes) are deferred to the Search change.") The migration MUST:

1. `CREATE EXTENSION IF NOT EXISTS pg_trgm;` — creates the trigram extension required for both the `%` similarity operator and `gin_trgm_ops` opclass
2. `ALTER TABLE posts ADD COLUMN content_tsv TSVECTOR GENERATED ALWAYS AS (to_tsvector('simple', content)) STORED;` — matches the canonical column definition in `docs/05-Implementation.md:562`. The `'simple'` config is the explicit MVP choice per `docs/05-Implementation.md:1159` (no Indonesian stopwords / stemming yet)
3. `CREATE INDEX posts_content_tsv_idx ON posts USING GIN(content_tsv);` — supports `@@ plainto_tsquery('simple', :query)` lookups
4. `CREATE INDEX posts_content_trgm_idx ON posts USING GIN(content gin_trgm_ops);` — supports `content % :query` fuzzy lookups

The migration MUST be idempotent against pre-existing `pg_trgm` (the `IF NOT EXISTS` guard handles re-run scenarios). The GENERATED column is automatic and STORED, so no application-side write changes are needed — existing INSERT paths from `POST /api/v1/posts` continue unchanged and the column is populated by Postgres.

#### Scenario: V13 migration creates pg_trgm + content_tsv + GIN indexes
- **WHEN** Flyway runs `V13__premium_search_fts.sql` against a Postgres instance where V12 has completed
- **THEN** the migration succeeds AND `pg_extension` lists `pg_trgm` AND `information_schema.columns` shows `posts.content_tsv` with type `tsvector` AND `pg_indexes` lists both `posts_content_tsv_idx` and `posts_content_trgm_idx` as GIN indexes

#### Scenario: content_tsv auto-populates on existing rows
- **WHEN** V13 runs against a database with pre-existing `posts` rows
- **THEN** every existing row's `content_tsv` is populated by Postgres' GENERATED-column rewrite (no application-side backfill needed)

#### Scenario: content_tsv auto-populates on new INSERTs
- **WHEN** a new row is INSERTed via the existing `POST /api/v1/posts` flow without supplying `content_tsv`
- **THEN** the inserted row's `content_tsv` equals `to_tsvector('simple', content)` (Postgres computes it as part of the GENERATED ALWAYS contract)

#### Scenario: content_tsv auto-regenerates on UPDATE of content
- **WHEN** an existing `posts` row's `content` is UPDATEd (e.g., by a future post-edit feature)
- **THEN** the row's `content_tsv` automatically regenerates to `to_tsvector('simple', new_content)` per the GENERATED ALWAYS STORED contract — no application-side recomputation is needed AND no separate trigger is required

#### Scenario: Direct INSERT to content_tsv rejected
- **WHEN** any INSERT or UPDATE attempts to write `content_tsv` directly
- **THEN** Postgres rejects the write with the `cannot insert into column "content_tsv"` error per the GENERATED ALWAYS semantics

#### Scenario: FTS query is index-backed
- **WHEN** EXPLAIN is run on `SELECT id FROM posts WHERE content_tsv @@ plainto_tsquery('simple', 'jakarta')`
- **THEN** the plan shows a Bitmap Index Scan on `posts_content_tsv_idx` (not a sequential scan)

#### Scenario: Trigram fuzzy match is index-backed
- **WHEN** EXPLAIN is run on `SELECT id FROM posts WHERE content % 'jakart'`
- **THEN** the plan shows a Bitmap Index Scan on `posts_content_trgm_idx`

#### Scenario: V13 idempotent against pre-existing pg_trgm
- **WHEN** V13 runs AND `pg_trgm` was created by a prior migration (e.g., a future V14+ migration that pre-creates it for another feature)
- **THEN** the `CREATE EXTENSION IF NOT EXISTS` clause is a no-op AND the migration completes without error
