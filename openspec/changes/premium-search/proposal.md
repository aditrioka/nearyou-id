## Why

Phase 2 social features are otherwise complete (timelines, likes, replies, reports, in-app notifications, follow, block, rate limiting), but Premium users have no way to find specific posts or accounts beyond scrolling — this is the last un-shipped Phase 2 feature per `docs/08-Roadmap-Risk.md` Phase 2 item 6 and a key Premium differentiator per `docs/02-Product.md` § Search. V4 explicitly deferred the FTS infrastructure to "the Search change" (V4 file comment), so this change builds it end-to-end: schema (V13 migration creates `pg_trgm` extension + `posts.content_tsv` GENERATED column + two posts GIN indexes + a username trigram index), endpoint + service + repository, Premium gate, kill switch, and hourly rate limiter.

## What Changes

- New endpoint `GET /api/v1/search?q=<query>&offset=<n>` returning ranked post results joined with author profile (LIMIT 20, OFFSET pagination per `docs/05-Implementation.md:1180`)
- Premium gate: Free users receive `403 { error: "premium_required", upsell: true }`; guests receive `401`
- `search_enabled` Firebase Remote Config kill switch (default `TRUE`, already declared in `docs/05-Implementation.md:1413`) — when `FALSE`, returns `503 { error: "search_disabled" }`
- Query length guard: `2 ≤ q.length ≤ 100` Unicode code points (post-NFKC-normalize and trim); outside that range returns `400`. Lower bound is `2` (not `1`) to defend against `pg_trgm`'s default `similarity_threshold = 0.3` matching near-arbitrary content on single-char queries — adversarial DoS surface even within the 60/hour cap. Whitespace-only queries (`q=%20%20%20`) also rejected post-trim, NOT counted against the rate limit
- Bounded `offset ≤ 10000` (deep-OFFSET DoS defense — each query forces Postgres to materialize-and-discard a huge prefix from the GIN bitmap heap-fetch path)
- `pg_trgm.similarity_threshold` pinned to canonical `0.3` per session (silent-drift protection)
- Hourly rate limit: 60 queries/hour per Premium user via the shared `RateLimiter` interface from `rate-limit-infrastructure` (sliding-window, `Duration.ofHours(1)`; not the daily WIB-stagger helper since this is hourly per `docs/05-Implementation.md:1746`). Redis key uses the canonical two-segment hash-tag form `{scope:rate_search}:{user:<uuid>}` matching the `ReportRateLimiter.keyFor` precedent
- Single Flyway migration **V13__premium_search_fts.sql** that builds the entire FTS stack V4 deferred:
  - `CREATE EXTENSION IF NOT EXISTS pg_trgm;`
  - `posts.content_tsv TSVECTOR GENERATED ALWAYS AS (to_tsvector('simple', content)) STORED`
  - `posts_content_tsv_idx` GIN on `content_tsv`
  - `posts_content_trgm_idx` GIN on `content gin_trgm_ops`
  - `users_username_trgm_idx` GIN on `username gin_trgm_ops`
- Canonical FTS query reads `visible_posts JOIN visible_users` with bidirectional block exclusion + auto-hide filter + Premium-private-profile gate, copied verbatim from `docs/05-Implementation.md:1163-1181`
- Tests covering: Premium happy path, Free 403, guest 401, kill-switch 503, length-guard 400, rate-limit 429, bidirectional block exclusion, auto-hidden exclusion, shadow-banned exclusion, Premium private-profile gate, username fuzzy match, `'simple'` tsvector lexeme behavior, GENERATED column auto-population, `pg_trgm` idempotency

## Capabilities

### New Capabilities
- `premium-search`: Premium-only full-text + fuzzy search across post content and usernames, kill-switch gated, hourly rate-limited, joining shadow-ban-safe and block-aware views

### Modified Capabilities (additive deltas — new requirements added to existing capabilities, no existing requirements rewritten)
- `post-creation`: ADDS requirements covering the V13 schema additions to `posts` — `posts.content_tsv` GENERATED column + `posts_content_tsv_idx` + `posts_content_trgm_idx` GIN indexes + `pg_trgm` extension creation. V4 deferred this set to "the Search change"; V13 ships it. The post-creation INSERT path is unchanged — the GENERATED column auto-populates server-side
- `users-schema`: ADDS a requirement for the `users_username_trgm_idx` GIN trigram index (V13)

## Impact

- **Affected code**: new module `:backend:ktor` paths under `app/search/` (`SearchRepository`, `SearchService`, `SearchRateLimiter`, `SearchRoute`); new DTOs in `:core:data`; new use case in `:core:domain`
- **Affected schema**: `V13__premium_search_fts.sql` creates `pg_trgm` extension, adds `posts.content_tsv` GENERATED column, creates 3 GIN indexes (2 on `posts`, 1 on `users`). No column drops, no triggers, no seed data
- **Affected APIs**: new `GET /api/v1/search` endpoint under existing `/api/v1` versioned route tree
- **Dependencies**: reuses `RedisRateLimiter` from `rate-limit-infrastructure`, `visible_posts`/`visible_users` from `visible-posts-view`, `subscription_status` field from `users-schema`, Remote Config client (already wired for `search_enabled` flag declaration)
- **Out of scope** (explicit defer, documented in `design.md`): Redis search-result cache, re-index trigger plumbing, Indonesian dictionary upgrade, location-filtered search, hashtag/mention indexing
- **Operations**: new smoke script `dev/scripts/smoke-premium-search.sh` for the pre-archive staging deploy step (per `openspec/project.md` § Staging deploy timing)
