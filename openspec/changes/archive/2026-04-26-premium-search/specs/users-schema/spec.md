## ADDED Requirements

### Requirement: users_username_trgm_idx GIN trigram index

Flyway migration `V13__premium_search_fts.sql` (the same migration that creates the posts FTS infrastructure per the `post-creation` capability delta) SHALL also create a GIN trigram index on `users.username` to support the `u.username % :query` fuzzy match clause used by the `premium-search` capability's canonical FTS query (`docs/05-Implementation.md:1163-1181`). The index definition MUST be `CREATE INDEX users_username_trgm_idx ON users USING GIN(username gin_trgm_ops);`.

The `pg_trgm` extension is created earlier in the same V13 migration (per the `post-creation` capability delta), so the username trigram index can rely on it being present without a separate guard. Co-locating the username index with the posts FTS work in a single migration matches V4's deferral note that scoped all FTS work to "the Search change."

#### Scenario: V13 migration creates the username trigram index
- **WHEN** Flyway runs `V13__premium_search_fts.sql` against a Postgres instance where V12 has completed
- **THEN** the migration succeeds, `flyway_schema_history` records V13, AND `pg_indexes` lists `users_username_trgm_idx` with `indexdef` matching `CREATE INDEX users_username_trgm_idx ON public.users USING gin (username gin_trgm_ops)`

#### Scenario: Fuzzy username match is index-backed
- **WHEN** EXPLAIN is run on `SELECT id FROM users WHERE username % 'aditrioka'`
- **THEN** the plan shows a Bitmap Index Scan on `users_username_trgm_idx` (not a sequential scan)
