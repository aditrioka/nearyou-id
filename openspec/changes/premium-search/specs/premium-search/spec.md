## ADDED Requirements

### Requirement: Search endpoint exposes Premium full-text + fuzzy search

The system SHALL expose `GET /api/v1/search?q=<query>&offset=<n>` that returns ranked post results joined with author profile, available only to authenticated Premium users. The endpoint MUST be protected by the `rest` JWT authenticator (RS256). The handler MUST resolve the viewer's `subscription_status` and reject non-Premium callers with `403 { error: "premium_required", upsell: true }` per `docs/05-Implementation.md:1185`. Guests without a JWT MUST receive `401`.

#### Scenario: Premium user searches successfully
- **WHEN** an authenticated user with `subscription_status IN ('premium_active', 'premium_billing_retry')` issues `GET /api/v1/search?q=jakarta&offset=0`
- **THEN** the response is `200` with body `{ results: [...], next_offset: <number | null> }` containing posts ranked by `ts_rank(content_tsv, plainto_tsquery('simple', 'jakarta')) DESC, created_at DESC`

#### Scenario: Free user receives Premium gate
- **WHEN** an authenticated user with `subscription_status = 'free'` calls the endpoint with any valid `q`
- **THEN** the response is `403` with body `{ error: "premium_required", upsell: true }` and no DB query is executed

#### Scenario: Guest receives 401
- **WHEN** the endpoint is called without an `Authorization` header (or with an invalid JWT)
- **THEN** the response is `401` with the standard auth-error envelope from the `rest` authenticator

### Requirement: Canonical FTS query reads visible_posts joined with visible_users

The repository SHALL execute the canonical query from `docs/05-Implementation.md:1163-1181` verbatim. It MUST read `FROM visible_posts p JOIN visible_users u ON p.author_id = u.id` (per `docs/05-Implementation.md:1881` shadow-ban rule), and MUST include all of: the FTS-or-trigram match clause `p.content_tsv @@ plainto_tsquery('simple', :query) OR p.content % :query OR u.username % :query`, the auto-hide filter `p.is_auto_hidden = FALSE`, the bidirectional block exclusion via two `NOT IN (SELECT ... FROM user_blocks ...)` clauses, the Premium-private-profile gate, and `ORDER BY rank DESC, p.created_at DESC LIMIT 20 OFFSET :offset`. Reading raw `FROM posts` or `FROM users` is a CI lint violation and is forbidden.

#### Scenario: Bidirectional block exclusion is symmetric
- **WHEN** viewer V has blocked author A AND a separate viewer W has been blocked by author A AND both V and W issue a search whose query matches A's posts
- **THEN** A's posts are excluded from V's results AND from W's results (the two `NOT IN` clauses cover both directions of the relation)

#### Scenario: Auto-hidden posts excluded
- **WHEN** a post P satisfies the FTS predicate AND `P.is_auto_hidden = TRUE`
- **THEN** P does NOT appear in any viewer's search results

#### Scenario: Shadow-banned authors excluded via visible_users
- **WHEN** an author A has `is_shadow_banned = TRUE` AND A's post matches the FTS predicate
- **THEN** the post does NOT appear in another viewer's search results because `JOIN visible_users` filters A out

#### Scenario: Premium private-profile gate hides posts from non-followers
- **WHEN** an author A has `subscription_status IN ('premium_active', 'premium_billing_retry')` AND `private_profile_opt_in = TRUE` AND a viewer V does NOT follow A
- **THEN** A's posts do NOT appear in V's search results

#### Scenario: Premium private-profile gate exempts followers
- **WHEN** an author A has `subscription_status IN ('premium_active', 'premium_billing_retry')` AND `private_profile_opt_in = TRUE` AND a viewer V follows A
- **THEN** A's posts DO appear in V's search results when they match the FTS predicate

#### Scenario: Free private-profile (legacy) does not hide posts
- **WHEN** an author A has `subscription_status = 'free'` AND `private_profile_opt_in = TRUE` (e.g., post-downgrade pre-flip-worker state)
- **THEN** A's posts DO appear in any viewer's search results because the gate's `subscription_status NOT IN (...)` arm short-circuits the OR

#### Scenario: Username fuzzy match via pg_trgm
- **WHEN** the query string is `aditrioka` AND a user has `username = 'aditrioka_id'` AND that user has at least one matching post
- **THEN** the post is included in the result set via the `u.username % :query` trigram predicate

### Requirement: search_enabled Remote Config flag acts as kill switch

The handler MUST consult the `search_enabled` Firebase Remote Config boolean flag (declared in `docs/05-Implementation.md:1413`, default `TRUE`) before executing the FTS query. When the flag is `FALSE`, the response MUST be `503 { error: "search_disabled" }` with no DB query executed. The flag value MAY be cached via the existing 5-minute server-side Redis cache used by other Remote Config consumers.

#### Scenario: Kill switch returns 503
- **WHEN** `search_enabled` is `FALSE` AND a Premium user calls the endpoint
- **THEN** the response is `503` with body `{ error: "search_disabled" }` and no Postgres query is issued

#### Scenario: Default TRUE permits queries
- **WHEN** `search_enabled` is `TRUE` (default per Remote Config declaration)
- **THEN** the handler proceeds to authentication + Premium gate + rate limit + DB query

### Requirement: Query length guard 1..100 chars

The handler MUST validate the `q` query parameter length before any DB or Redis call. Queries with `q.length < 1` or `q.length > 100` MUST be rejected with `400 { error: "invalid_query_length" }`. URL-decoded character count is authoritative (not byte length).

#### Scenario: Empty query rejected
- **WHEN** the request URL is `GET /api/v1/search?q=`
- **THEN** the response is `400` with body `{ error: "invalid_query_length" }`

#### Scenario: Oversized query rejected
- **WHEN** the request URL has a `q` parameter whose URL-decoded length exceeds 100 characters
- **THEN** the response is `400` with body `{ error: "invalid_query_length" }`

#### Scenario: 100-char boundary accepted
- **WHEN** the request URL has a `q` parameter whose URL-decoded length is exactly 100 characters
- **THEN** the handler proceeds past the length guard

### Requirement: Hourly Layer-2 rate limit at 60 queries per Premium user

The handler MUST enforce 60 queries per rolling 60-minute window per authenticated Premium user via the shared `RateLimiter` interface from the `rate-limit-infrastructure` capability. The Redis key MUST follow the canonical two-segment hash-tag form `{scope:rate_search}:{user:<uuid>}` matching the `ReportRateLimiter` precedent at `backend/ktor/src/main/kotlin/id/nearyou/app/moderation/ReportRateLimiter.kt:92` (which sets the canonical pattern for Layer-2 callers). The window MUST be `Duration.ofHours(1)`; the hourly cap MUST NOT use `computeTTLToNextReset(user_id)` (that helper is reserved for daily WIB-stagger caps; `RateLimitTtlRule` enforces the helper only on daily caps, not hourly). On cap exceeded the response MUST be `429` with a `Retry-After: <seconds-until-window-roll>` header populated from the `RateLimiter.Outcome.RateLimited.retryAfterSeconds` value.

#### Scenario: 60 queries within an hour all succeed
- **WHEN** a Premium user issues 60 search queries within a 60-minute window
- **THEN** all 60 receive `200` and no `429` is returned

#### Scenario: 61st query in the same hour returns 429
- **WHEN** a Premium user issues a 61st search query within the same 60-minute window
- **THEN** the response is `429` with a `Retry-After` header indicating the seconds until the oldest entry in the window expires

#### Scenario: Redis key respects canonical hash-tag form
- **WHEN** the rate limiter constructs its Redis key for user `u123`
- **THEN** the key equals `{scope:rate_search}:{user:u123}` (two separate hash-tag segments matching the `ReportRateLimiter.keyFor` precedent — `RedisHashTagRule` accepts this form)

### Requirement: Pagination via OFFSET with next_offset cursor

The handler MUST accept an optional `offset` query parameter (default `0`, integer ≥ 0) and pass it as `OFFSET :offset` to the canonical query. The response body MUST include `next_offset`: the integer to use for the next page (typically `offset + results.length`), or `null` when fewer than 20 results were returned (signaling end of results).

#### Scenario: First page returns next_offset = 20 when full
- **WHEN** a query matches at least 20 posts AND the request omits `offset`
- **THEN** the response includes 20 results AND `next_offset = 20`

#### Scenario: Last page returns next_offset = null
- **WHEN** a query matches fewer than 20 posts at the requested offset
- **THEN** the response includes the available results AND `next_offset = null`

#### Scenario: Negative or non-integer offset rejected
- **WHEN** the request includes `offset=-1` or `offset=abc`
- **THEN** the response is `400` with body `{ error: "invalid_offset" }`

### Requirement: 'simple' tsvector configuration is the MVP choice

The query MUST use `plainto_tsquery('simple', :query)` (matching the `posts.content_tsv` GENERATED column's `to_tsvector('simple', content)` definition from `docs/05-Implementation.md:562`). The `'simple'` config performs no stemming and no Indonesian stopword removal — this is an explicit MVP limitation per `docs/05-Implementation.md:1159`. Any future upgrade to a custom Indonesian dictionary MUST update both the GENERATED column expression (Flyway migration) AND this requirement in lockstep.

#### Scenario: Lexeme-level matching, no stemming
- **WHEN** the query is `makanan` AND a post contains the word `makan`
- **THEN** the post does NOT match the FTS predicate (the `'simple'` config does not stem `makanan` to `makan`)
- **AND** the post MAY still be returned via the trigram `%` operator if similarity is high enough (this is acceptable; the trigram clause is the fallback for fuzzy intent)
