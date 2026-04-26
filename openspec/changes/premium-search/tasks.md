## 1. Migration V13 — full FTS infrastructure (V4-deferred + username trigram)

- [ ] 1.1 Create `backend/ktor/src/main/resources/db/migration/V13__premium_search_fts.sql` with the following statements in order:
  - `CREATE EXTENSION IF NOT EXISTS pg_trgm;`
  - `ALTER TABLE posts ADD COLUMN content_tsv TSVECTOR GENERATED ALWAYS AS (to_tsvector('simple', content)) STORED;`
  - `CREATE INDEX posts_content_tsv_idx ON posts USING GIN(content_tsv);`
  - `CREATE INDEX posts_content_trgm_idx ON posts USING GIN(content gin_trgm_ops);`
  - `CREATE INDEX users_username_trgm_idx ON users USING GIN(username gin_trgm_ops);`
- [ ] 1.2 Add migration smoke test `backend/ktor/src/test/kotlin/id/nearyou/app/infra/db/MigrationV13SmokeTest.kt` covering: (a) extension `pg_trgm` is present; (b) `posts.content_tsv` column has type `tsvector`; (c) `pg_indexes` lists all three GIN indexes
- [ ] 1.3 Add EXPLAIN-backed assertion in the smoke test that `WHERE content_tsv @@ plainto_tsquery('simple', 'jakarta')` uses Bitmap Index Scan on `posts_content_tsv_idx`, `WHERE content % 'jakart'` uses Bitmap Index Scan on `posts_content_trgm_idx`, and `WHERE username % 'aditrioka'` uses Bitmap Index Scan on `users_username_trgm_idx`
- [ ] 1.4 Add behavior test: INSERT a `posts` row with `content = 'jakarta selatan'` and assert the GENERATED `content_tsv` equals `to_tsvector('simple', 'jakarta selatan')` (proves auto-population works)
- [ ] 1.5 Add rejection test: `UPDATE posts SET content_tsv = ...` fails with the GENERATED ALWAYS error code (proves direct write is impossible)
- [ ] 1.6 Update `dev/supabase-parity-init.sql` if needed so the `migrate-supabase-parity` CI job can run V13 cleanly against the parity surface (per `openspec/project.md` § CI expectations: extend parity init alongside any new migration that depends on Supabase-provided state — `pg_trgm` is bundled with Postgres so likely no parity changes needed; verify locally)

## 2. Domain layer — search use case + DTOs

- [ ] 2.1 Add `SearchPostsUseCase` interface to `:core:domain` (input: `viewerId`, `query`, `offset`; output: `SearchResult` data class with `results: List<PostWithAuthor>` + `nextOffset: Int?`)
- [ ] 2.2 Add `PostWithAuthor` and `SearchResult` data classes to `:core:domain` (pure Kotlin, zero vendor deps per `openspec/project.md` § Module Structure)
- [ ] 2.3 Add `SearchPostsRequest` / `SearchPostsResponse` DTOs to `:core:data` with `kotlinx.serialization` annotations

## 3. Repository layer — canonical FTS query

- [ ] 3.1 Add `SearchRepository` interface in `:core:data`
- [ ] 3.2 Implement `SearchRepositoryImpl` in `:backend:ktor` under `app/search/`. Query string MUST be the verbatim canonical FTS query from `docs/05-Implementation.md:1163-1181` (params: `:query`, `:viewer_id`, `:offset`)
- [ ] 3.3 Confirm the implementation reads `FROM visible_posts p JOIN visible_users u ON p.author_id = u.id` (no raw `FROM posts` / `FROM users` — would trigger `RawFromPostsRule`)
- [ ] 3.4 Confirm bidirectional `user_blocks` NOT-IN clauses are present (would trigger `BlockExclusionJoinRule` if missing)
- [ ] 3.5 Map ResultSet rows to `PostWithAuthor` instances; compute `nextOffset` as `offset + size` when size == 20, else `null`

## 4. Rate-limit integration — hourly Layer-2 caller

- [ ] 4.1 Add `SearchRateLimiter` in `:backend:ktor` under `app/search/` that wraps the shared `RateLimiter` from `rate-limit-infrastructure` capability with key form `{scope:rate_search}:{user:<uuid>}` (canonical two-segment hash-tag form matching `ReportRateLimiter.keyFor` at `backend/ktor/.../moderation/ReportRateLimiter.kt:92`), limit `60`, window `Duration.ofHours(1)`. Mirror the `ReportRateLimiter` (V9) precedent class structure (Outcome sealed interface, companion `keyFor`, default constructor with `InMemoryRateLimiter` for tests).
- [ ] 4.2 Confirm the Redis key satisfies `RedisHashTagRule` (hash-tag braces present)
- [ ] 4.3 Confirm `RateLimitTtlRule` does NOT flag the call site (helper applies only to daily caps; hourly is exempt)
- [ ] 4.4 Wire the rate limiter into Koin DI alongside other Layer-2 limiters

## 5. Service layer — Premium gate + Remote Config gate

- [ ] 5.1 Add `SearchService` in `:backend:ktor` under `app/search/`. Sequence: validate query length (1..100) → check `search_enabled` Remote Config flag → check Premium status → check rate limit → call `SearchRepository`
- [ ] 5.2 Premium check reads `users.subscription_status IN ('premium_active', 'premium_billing_retry')`
- [ ] 5.3 `search_enabled` reads via the existing Remote Config client (5-min Redis cache reuse — no new cache needed)
- [ ] 5.4 Define error sealed class with cases: `InvalidQueryLength`, `KillSwitchActive`, `PremiumRequired`, `RateLimited(retryAfterSeconds: Long)`, mapped to `400` / `503` / `403` / `429` respectively in the route layer

## 6. Endpoint — GET /api/v1/search

- [ ] 6.1 Add `SearchRoute` in `:backend:ktor` under `app/search/` mounted at `GET /api/v1/search` inside `authenticate("rest") { ... }` (so guests get `401`)
- [ ] 6.2 Parse `q` (required) and `offset` (optional, default `0`); reject negative or non-integer `offset` with `400 { error: "invalid_offset" }` BEFORE any service call
- [ ] 6.3 Map `SearchService` errors to HTTP responses per task 5.4; `429` MUST set `Retry-After` header
- [ ] 6.4 Wire route registration into `Application.module()`'s route tree

## 7. Tests — integration + unit

- [ ] 7.1 Integration test: Premium user happy path — seed 25 matching posts, query, assert 20 returned + `next_offset = 20`, second page with `offset=20` returns 5 + `next_offset = null`
- [ ] 7.2 Integration test: Free user rejected with `403 premium_required` (no DB query issued — verify via mocked repository spy or absence of `pg_stat_statements` entry)
- [ ] 7.3 Integration test: guest (no JWT) rejected with `401`
- [ ] 7.4 Integration test: `search_enabled = FALSE` returns `503`
- [ ] 7.5 Integration test: `q=` (empty) returns `400 invalid_query_length`
- [ ] 7.6 Integration test: `q` of length 101 returns `400`; `q` of length 100 proceeds
- [ ] 7.7 Integration test: 61st query within rolling hour returns `429` with `Retry-After`
- [ ] 7.8 Integration test: bidirectional block exclusion — viewer V blocked author A → A's posts hidden from V; viewer W blocked by author A → A's posts hidden from W
- [ ] 7.9 Integration test: `is_auto_hidden = TRUE` post excluded
- [ ] 7.10 Integration test: shadow-banned author's posts excluded (via `visible_users` join)
- [ ] 7.11 Integration test: Premium private-profile + non-follower → hidden; Premium private-profile + follower → visible; Free private-profile (legacy) → visible
- [ ] 7.12 Integration test: username fuzzy match — query `aditrioka` returns post by user `aditrioka_id`
- [ ] 7.13 Integration test: `'simple'` config behavior — query `makanan` does NOT FTS-match a post containing `makan` (lexeme literal); document the trigram fallback path
- [ ] 7.14 Unit test: `SearchRateLimiter.keyFor(uuid)` returns exactly `{scope:rate_search}:{user:<uuid>}`
- [ ] 7.15 Unit test: `SearchService` error mapping (each sealed-class case → expected HTTP code)
- [ ] 7.16 Run `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` locally; all four green before pushing

## 8. Pre-archive smoke

- [ ] 8.1 Add `dev/scripts/smoke-premium-search.sh` covering: deploy ID assertion, Premium happy path (returns 200), Free 403, kill-switch flip + recovery, length-guard 400, rate-limit 429 (issue 61 calls in a tight loop)
- [ ] 8.2 `gh workflow run deploy-staging.yml --ref premium-search` and poll until deploy run is green
- [ ] 8.3 Run `dev/scripts/smoke-premium-search.sh` against the branch staging deploy; all assertions pass
- [ ] 8.4 Tick Section 8 in this tasks.md file as part of the smoke commit

## 9. Documentation sync

- [ ] 9.1 Update `docs/09-Versions.md` Version Decisions table only if a new library version is pinned (none expected — this change reuses existing infra)
- [ ] 9.2 No `docs/05-Implementation.md` edits expected (proposal aligned to canonical sources verbatim); flag any mid-implementation divergence to `FOLLOW_UPS.md` per `/next-change` Phase B step 7 procedure
