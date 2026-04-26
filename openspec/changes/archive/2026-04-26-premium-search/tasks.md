## 1. Migration V13 — full FTS infrastructure (V4-deferred + username trigram)

- [x] 1.1 Create `backend/ktor/src/main/resources/db/migration/V13__premium_search_fts.sql` with the following statements in order:
  - `CREATE EXTENSION IF NOT EXISTS pg_trgm;`
  - `ALTER TABLE posts ADD COLUMN content_tsv TSVECTOR GENERATED ALWAYS AS (to_tsvector('simple', content)) STORED;`
  - `CREATE INDEX posts_content_tsv_idx ON posts USING GIN(content_tsv);`
  - `CREATE INDEX posts_content_trgm_idx ON posts USING GIN(content gin_trgm_ops);`
  - `CREATE INDEX users_username_trgm_idx ON users USING GIN(username gin_trgm_ops);`
- [x] 1.2 Add migration smoke test `backend/ktor/src/test/kotlin/id/nearyou/app/infra/db/MigrationV13SmokeTest.kt` covering: (a) extension `pg_trgm` is present; (b) `posts.content_tsv` column has type `tsvector`; (c) `pg_indexes` lists all three GIN indexes
- [x] 1.3 Add EXPLAIN-backed assertion in the smoke test that `WHERE content_tsv @@ plainto_tsquery('simple', 'jakarta')` uses Bitmap Index Scan on `posts_content_tsv_idx`, `WHERE content % 'jakart'` uses Bitmap Index Scan on `posts_content_trgm_idx`, and `WHERE username % 'aditrioka'` uses Bitmap Index Scan on `users_username_trgm_idx`
- [x] 1.4 Add behavior test: INSERT a `posts` row with `content = 'jakarta selatan'` and assert the GENERATED `content_tsv` equals `to_tsvector('simple', 'jakarta selatan')` (proves auto-population on INSERT)
- [x] 1.5 Add backfill test: pre-seed the test Postgres with several `posts` rows BEFORE running V13, then run V13, then assert every pre-existing row's `content_tsv` equals `to_tsvector('simple', content)` (proves existing-row backfill is correct, not just new-row INSERT)
- [x] 1.6 Add UPDATE-regen test: INSERT a row with `content = 'foo'`, then `UPDATE posts SET content = 'bar' WHERE id = ?`, then assert `content_tsv` regenerated to `to_tsvector('simple', 'bar')` (proves GENERATED ALWAYS STORED auto-regenerates on UPDATE per Postgres semantics — no separate trigger needed)
- [x] 1.7 Add rejection test: `UPDATE posts SET content_tsv = ...` fails with the GENERATED ALWAYS error code (proves direct write is impossible)
- [x] 1.8 Verify V13 reconciles with `dev/supabase-parity-init.sql`: `pg_trgm` is bundled with Postgres so no parity-init change should be required, but explicitly run the `migrate-supabase-parity` CI job equivalent locally (`./gradlew :backend:ktor:test --tests '*ParityInit*'` if that exists, or invoke Flyway against a fresh Postgres after the parity-init script applies). DECIDE before pushing: parity-init change required (Y/N) and document the answer in the commit message.

## 2. Domain layer — search use case + DTOs

- [x] 2.1 Add `SearchPostsUseCase` interface to `:core:domain` (input: `viewerId`, `query`, `offset`; output: `SearchResult` data class with `results: List<PostWithAuthor>` + `nextOffset: Int?`)
- [x] 2.2 Add `PostWithAuthor` and `SearchResult` data classes to `:core:domain` (pure Kotlin, zero vendor deps per `openspec/project.md` § Module Structure)
- [x] 2.3 Add `SearchPostsRequest` / `SearchPostsResponse` DTOs to `:core:data` with `kotlinx.serialization` annotations

## 3. Repository layer — canonical FTS query

- [x] 3.1 Add `SearchRepository` interface in `:core:data`
- [x] 3.2 Implement `SearchRepositoryImpl` in `:backend:ktor` under `app/search/`. Query string MUST be the verbatim canonical FTS query from `docs/05-Implementation.md:1163-1181` (params: `:query`, `:viewer_id`, `:offset`)
- [x] 3.3 Confirm the implementation reads `FROM visible_posts p JOIN visible_users u ON p.author_id = u.id` (no raw `FROM posts` / `FROM users` — would trigger `RawFromPostsRule`)
- [x] 3.4 Confirm bidirectional `user_blocks` NOT-IN clauses are present (would trigger `BlockExclusionJoinRule` if missing)
- [x] 3.5 Map ResultSet rows to `PostWithAuthor` instances; compute `nextOffset` as `offset + size` when size == 20, else `null`

## 4. Rate-limit integration — hourly Layer-2 caller

- [x] 4.1 Add `SearchRateLimiter` in `:backend:ktor` under `app/search/` that wraps the shared `RateLimiter` from `rate-limit-infrastructure` capability with key form `{scope:rate_search}:{user:<uuid>}` (canonical two-segment hash-tag form matching `ReportRateLimiter.keyFor` at `backend/ktor/.../moderation/ReportRateLimiter.kt:92`), limit `60`, window `Duration.ofHours(1)`. Mirror the `ReportRateLimiter` (V9) precedent class structure (Outcome sealed interface, companion `keyFor`, default constructor with `InMemoryRateLimiter` for tests).
- [x] 4.2 Confirm the Redis key satisfies `RedisHashTagRule` (hash-tag braces present)
- [x] 4.3 Confirm `RateLimitTtlRule` does NOT flag the call site (helper applies only to daily caps; hourly is exempt)
- [x] 4.4 Wire the rate limiter into Koin DI alongside other Layer-2 limiters

## 5. Service layer — Premium gate + Remote Config gate

- [x] 5.1 Add `SearchService` in `:backend:ktor` under `app/search/`. Sequence (order is load-bearing): URL-decode + NFKC-normalize + trim `q` → validate post-trim length 2..100 → check `search_enabled` Remote Config flag (short-circuits BEFORE rate-limit gate so probe-polling does not burn quota) → check Premium status → check rate limit → call `SearchRepository`
- [x] 5.2 Premium check reads `users.subscription_status IN ('premium_active', 'premium_billing_retry')`
- [x] 5.3 `search_enabled` reads via the existing Remote Config client (5-min Redis cache reuse — no new cache needed)
- [x] 5.4 Define error sealed class with cases: `InvalidQueryLength`, `InvalidOffset`, `KillSwitchActive`, `PremiumRequired`, `RateLimited(retryAfterSeconds: Long)`, mapped to `400` (query) / `400` (offset) / `503` / `403` / `429` respectively in the route layer
- [x] 5.5 Pin `pg_trgm.similarity_threshold = 0.3` per session — either via `SET LOCAL` in the repository's connection-prep block, or via integration-test assertion `SHOW pg_trgm.similarity_threshold` returns `0.3`. Document the choice in the repository class header comment.

## 6. Endpoint — GET /api/v1/search

- [x] 6.1 Add `SearchRoute` in `:backend:ktor` under `app/search/` mounted at `GET /api/v1/search` inside `authenticate("rest") { ... }` (so guests get `401`)
- [x] 6.2 Parse `q` (required) and `offset` (optional, default `0`); reject negative, non-integer, or `> 10000` `offset` with `400 { error: "invalid_offset" }` BEFORE any service call
- [x] 6.3 Map `SearchService` errors to HTTP responses per task 5.4; `429` MUST set `Retry-After` header
- [x] 6.4 Wire route registration into `Application.module()`'s route tree

## 7. Tests — integration + unit

### Auth + Premium gate
- [x] 7.1 Integration test: Premium user happy path — seed 21 matching posts (one over the page-size threshold to align with the spec's "matches at least 21 posts" full-page wording, distinct from the exactly-20 boundary case in 7.15); query, assert 20 returned + `next_offset = 20`; second page with `offset=20` returns 1 + `next_offset = null`
- [x] 7.2 Integration test: Free user rejected with `403 premium_required` (no DB query issued — verify via mocked repository spy or absence of `pg_stat_statements` entry); also assert no rate-limit slot consumed
- [x] 7.3 Integration test: guest (no JWT) rejected with `401`
- [x] 7.4 Integration test: Premium-to-Free mid-session — server-side `users.subscription_status` flipped to `free` while JWT still claims Premium → handler reads authoritative DB value AND rejects with `403 premium_required`

### Kill switch
- [x] 7.5 Integration test: `search_enabled = FALSE` returns `503` AND no DB query issued AND no rate-limit slot consumed (confirm short-circuit ordering)

### Length guard
- [x] 7.6 Integration test: `q=` (empty after URL-decode) returns `400 invalid_query_length`
- [x] 7.7 Integration test: `q=a` (length 1 post-trim) returns `400 invalid_query_length` (single-char DoS guard)
- [x] 7.8 Integration test: `q=%20%20%20` (URL-decodes to 3 spaces, post-trim length 0) returns `400 invalid_query_length` AND no rate-limit slot consumed AND no `plainto_tsquery` call issued
- [x] 7.9 Integration test: `q=ab` (length 2 post-trim) proceeds past length guard
- [x] 7.10 Integration test: `q` of length 100 proceeds; length 101 returns `400`
- [x] 7.11 Integration test: NFKC normalization — `q` containing a fullwidth character normalizes to a half-width form before length-counting (assert by sending a known fullwidth string)

### Rate limit
- [x] 7.12 Integration test: 60 queries within rolling hour all succeed
- [x] 7.13 Integration test: 61st query within rolling hour returns `429` AND the `Retry-After` header value EXACTLY equals `RateLimiter.Outcome.RateLimited.retryAfterSeconds` (assert numeric equality, not just header presence)
- [x] 7.14 Unit test: `SearchRateLimiter.keyFor(uuid)` returns exactly `{scope:rate_search}:{user:<uuid>}`

### Pagination
- [x] 7.15 Integration test: exactly-20-match boundary — seed exactly 20 matching posts; page 1 returns 20 results + `next_offset = 20`; page 2 with `offset=20` returns `{ results: [], next_offset: null }`
- [x] 7.16 Integration test: `offset=-1` returns `400 invalid_offset`
- [x] 7.17 Integration test: `offset=abc` (non-integer) returns `400 invalid_offset`
- [x] 7.18 Integration test: `offset=10001` returns `400 invalid_offset` AND no DB query issued (deep-OFFSET DoS guard)

### Block exclusion
- [x] 7.19 Integration test: viewer V blocked author A → A's posts hidden from V (one-direction block, V→A)
- [x] 7.20 Integration test: viewer W blocked by author A → A's posts hidden from W (other-direction block, A→W)
- [x] 7.21 Integration test: mutual block — V and A blocked each other (two `user_blocks` rows: V→A and A→V) → A's posts excluded from V's results AND query plan does not error or double-filter

### Shadow-ban + visibility
- [x] 7.22 Integration test: `is_auto_hidden = TRUE` post excluded from results
- [x] 7.23 Integration test: shadow-banned author's posts excluded from another viewer's results (via `visible_users` join)
- [x] 7.24 Integration test: shadow-banned viewer searches their own posts → ZERO results returned (intentional per spec; documented in scenario)

### Privacy gate
- [x] 7.25 Integration test: Premium private-profile author + non-follower viewer → posts hidden
- [x] 7.26 Integration test: Premium private-profile author + follower viewer → posts visible
- [x] 7.27 Integration test: Free private-profile author (legacy state, e.g., post-downgrade pre-flip-worker) + any viewer → posts visible

### FTS + trigram behavior
- [x] 7.28 Integration test: username fuzzy match — query `aditrioka` returns post by user `aditrioka_id` via the `u.username % :query` clause
- [x] 7.29 Integration test: lexeme literal — query `makanan` does NOT match a post containing `makan` (the `'simple'` config does not stem; trigram threshold 0.3 also does not bridge)
- [x] 7.30 Integration test: case-insensitive — query `JAKARTA` matches a post containing `jakarta` (the `'simple'` config lowercases at lexeme generation)

### similarity_threshold pin
- [x] 7.31 Integration test: `SHOW pg_trgm.similarity_threshold` returns `0.3` at FTS query time (assertion on a leased connection from the same pool the repository uses)

### Service / unit
- [x] 7.32 Unit test: `SearchService` error mapping (each sealed-class case → expected HTTP code)

### Local CI
- [x] 7.33 Run `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` locally; all four green before pushing

## 8. Pre-archive smoke

- [x] 8.1 Add `dev/scripts/smoke-premium-search.sh` covering: Premium happy path (returns 200), length-guard 400, offset-bound 400, rate-limit 429 (issue 61 calls in a tight loop) with Retry-After value sanity-bound. Free 403 + guest 401 + kill switch 503 are NOT smoked (no deploy-config surface; covered by integration tests).
- [x] 8.2 `gh workflow run deploy-staging.yml --ref premium-search` triggered + run `24950917542` green at 2026-04-26T07:18:50Z (5m 39s wall-clock). No-auth probe `GET /api/v1/search?q=test` returns `401 token_revoked` from the deployed instance — endpoint reachable + auth plugin wired correctly.
- [x] 8.3 Smoke ran green at 2026-04-26T07:30Z against staging deploy `24950917542`. All 5 assertions passed: 400 `invalid_query_length` (empty q), 400 `invalid_offset` (offset=10001), 200 OK (Premium happy path), 60 OKs (rate-limit window), 61st = 429 with `Retry-After=3572s` (within [1, 3600] hourly window — limiter genuinely firing, not fail-softing). Premium test user `10d600e9-df39-48ec-9493-7e3d445493f1` was promoted via a one-shot Cloud Run Job (`postgres:16-alpine` image executing `psql UPDATE users SET subscription_status = 'premium_active'`) — Cloud Run is the only IPv6-capable GCP runtime that reaches Supabase from outside the deployed app's network namespace; Cloud Build, Cloud Shell, and direct workstation psql all fail with "Address not available" / "Cannot assign requested address" on Supabase's IPv6 host. Promoter + verifier jobs deleted post-smoke.
- [x] 8.4 Section 8 ticked complete in this commit.

## 9. Documentation sync

- [x] 9.1 No library version pin required — V13 adds the `pg_trgm` extension (bundled with Postgres, no Gradle dependency), and search reuses existing infra (`rate-limit-infrastructure`, `visible_posts`/`visible_users` views, Remote Config, Lettuce-via-`:infra:redis`). No `docs/09-Versions.md` amendment.
- [x] 9.2 No `docs/05-Implementation.md` edits required — the canonical FTS query at `:1163-1181` was copied verbatim into `JdbcSearchRepository.CANONICAL_FTS_QUERY` and into the `premium-search` spec. The `docs/02-Product.md:282` re-index-trigger divergence found during proposal review is logged in `FOLLOW_UPS.md` § `premium-search-reindex-trigger-doc-divergence` (deferred to Month 6+ alongside the Redis search-result cache; not a blocker for V13 because the GIN-on-view combination is correct without a re-index trigger).
