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

#### Scenario: Premium-to-Free mid-session downgrade
- **WHEN** a viewer's request carries a JWT whose claims indicate Premium AND the server's authoritative `users.subscription_status` for that user has been updated to `'free'` by an out-of-band webhook (e.g., RevenueCat `EXPIRATION` past grace)
- **THEN** the handler reads the authoritative `users.subscription_status` value (NOT the JWT claim cache) AND rejects with `403 premium_required`
- **AND** the JWT is rotated by the `users.token_version` bump that the webhook handler MUST emit (per `auth-jwt` capability), so the next sign-in cycle returns a JWT consistent with the new status

### Requirement: Canonical FTS query reads visible_posts joined with visible_users

The repository SHALL execute the canonical query from `docs/05-Implementation.md:1163-1181` verbatim. It MUST read `FROM visible_posts p JOIN visible_users u ON p.author_id = u.id` (per `docs/05-Implementation.md:1881` shadow-ban rule), and MUST include all of: the FTS-or-trigram match clause `p.content_tsv @@ plainto_tsquery('simple', :query) OR p.content % :query OR u.username % :query`, the auto-hide filter `p.is_auto_hidden = FALSE`, the bidirectional block exclusion via two `NOT IN (SELECT ... FROM user_blocks ...)` clauses, the Premium-private-profile gate, and `ORDER BY rank DESC, p.created_at DESC LIMIT 20 OFFSET :offset`. Reading raw `FROM posts` or `FROM users` is a CI lint violation and is forbidden.

#### Scenario: Viewer-blocked-author exclusion
- **WHEN** viewer V has blocked author A AND V issues a search whose query matches one of A's posts
- **THEN** A's posts are excluded from V's results via the `author_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = :viewer_id)` clause

#### Scenario: Author-blocked-viewer exclusion
- **WHEN** author A has blocked viewer W AND W issues a search whose query matches one of A's posts
- **THEN** A's posts are excluded from W's results via the `author_id NOT IN (SELECT blocker_id FROM user_blocks WHERE blocked_id = :viewer_id)` clause

#### Scenario: Mutual block — both NOT-IN clauses fire idempotently
- **WHEN** viewer V and author A have blocked each other (two `user_blocks` rows: V→A and A→V) AND V issues a search whose query matches A's posts
- **THEN** A's posts are excluded from V's results AND the query plan does not error or double-count — the two NOT-IN clauses are independent set-membership checks, satisfying both is consistent

#### Scenario: Auto-hidden posts excluded
- **WHEN** a post P satisfies the FTS predicate AND `P.is_auto_hidden = TRUE`
- **THEN** P does NOT appear in any viewer's search results

#### Scenario: Shadow-banned authors excluded via visible_users
- **WHEN** an author A has `is_shadow_banned = TRUE` AND A's post matches the FTS predicate
- **THEN** the post does NOT appear in another viewer's search results because `JOIN visible_users` filters A out

#### Scenario: Shadow-banned viewer searches their own posts
- **WHEN** a viewer V has `is_shadow_banned = TRUE` AND V issues a search whose query matches one of V's own posts
- **THEN** V does NOT see their own posts in the search results — the canonical FTS query reads `JOIN visible_users` and `visible_posts`, both of which filter out V's own author rows; the own-content bypass documented at `docs/05-Implementation.md:1885` is for the Repository-layer "my posts" path, NOT the search endpoint
- **AND** this is intentional: search is a discovery surface, not a self-archive surface; shadow-banned users still access their own posts via the user-profile own-content path which is NOT in scope of this capability

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

The handler MUST consult the `search_enabled` Firebase Remote Config boolean flag (declared in `docs/05-Implementation.md:1413`, default `TRUE`) before executing the FTS query. When the flag is `FALSE`, the response MUST be `503 { error: "search_disabled" }` with no DB query executed AND no rate-limit slot consumed (the kill-switch path short-circuits before the rate-limit gate to prevent probe-polling from burning user quota during incidents). The flag value MAY be cached via the existing 5-minute server-side Redis cache used by other Remote Config consumers; up to 5 minutes of stale-cache lag between flag flip and runtime effect is acceptable per `design.md` Risks.

#### Scenario: Kill switch returns 503 without DB or rate-limit hit
- **WHEN** `search_enabled` is `FALSE` AND a Premium user calls the endpoint
- **THEN** the response is `503` with body `{ error: "search_disabled" }` AND no Postgres query is issued AND no Redis rate-limit increment is performed

#### Scenario: Default TRUE permits queries
- **WHEN** `search_enabled` is `TRUE` (default per Remote Config declaration)
- **THEN** the handler proceeds to authentication + Premium gate + rate limit + DB query

### Requirement: Query length guard 2..100 chars (post-trim)

The handler MUST validate the `q` query parameter before any DB or Redis call. Validation MUST occur in this order:

1. URL-decode the parameter
2. Apply NFKC normalization (matches the post-content normalization rule in `docs/02-Product.md` § Post System) and trim leading/trailing Unicode whitespace
3. Reject post-normalization queries with `q.length < 2` or `q.length > 100` Unicode code-points with `400 { error: "invalid_query_length" }`

The lower bound is `2` (not `1`) because a single-character query against `pg_trgm`'s default `similarity_threshold = 0.3` matches near-arbitrary content and forces a near-full-table trigram bitmap scan — adversarial DoS surface even within the 60/hour cap. The upper bound is `100` to bound `pg_trgm` cost on long inputs. URL-decoded code-point count is authoritative (not byte length).

#### Scenario: Empty query rejected
- **WHEN** the request URL is `GET /api/v1/search?q=`
- **THEN** the response is `400` with body `{ error: "invalid_query_length" }`

#### Scenario: Single-char query rejected
- **WHEN** the request URL has `q=a` (post-normalization length 1)
- **THEN** the response is `400` with body `{ error: "invalid_query_length" }`

#### Scenario: Whitespace-only query rejected post-trim
- **WHEN** the request URL has `q=%20%20%20` (URL-decodes to 3 spaces; post-trim length 0)
- **THEN** the response is `400` with body `{ error: "invalid_query_length" }` AND no rate-limit slot is consumed AND no `plainto_tsquery` call is issued

#### Scenario: Oversized query rejected
- **WHEN** the request URL has a `q` parameter whose post-normalization length exceeds 100 code points
- **THEN** the response is `400` with body `{ error: "invalid_query_length" }`

#### Scenario: 2-char boundary accepted
- **WHEN** the request URL has `q=ab` (post-normalization length 2)
- **THEN** the handler proceeds past the length guard

#### Scenario: 100-char boundary accepted
- **WHEN** the request URL has a `q` parameter whose post-normalization length is exactly 100 code points
- **THEN** the handler proceeds past the length guard

### Requirement: pg_trgm.similarity_threshold pinned to 0.3 per session

The repository SHALL execute `SET LOCAL pg_trgm.similarity_threshold = 0.3` (or equivalent connection-scoped pin) before each FTS query, OR the application SHALL include an integration-test assertion that `SHOW pg_trgm.similarity_threshold` returns `0.3` at query time. This pins the canonical default explicitly so a future Postgres GUC change or extension upgrade does not silently shift the `%` operator's match semantics.

#### Scenario: Similarity threshold is 0.3 at query time
- **WHEN** the FTS query path executes against a freshly-leased connection
- **THEN** `SHOW pg_trgm.similarity_threshold` returns `0.3`

### Requirement: Hourly Layer-2 rate limit at 60 queries per Premium user

The handler MUST enforce 60 queries per rolling 60-minute window per authenticated Premium user via the shared `RateLimiter` interface from the `rate-limit-infrastructure` capability.

#### Scenario: Redis key respects canonical hash-tag form
- **WHEN** the rate limiter constructs its Redis key for user `u123`
- **THEN** the key equals `{scope:rate_search}:{user:u123}` (two separate hash-tag segments matching the `ReportRateLimiter.keyFor` precedent at `backend/ktor/src/main/kotlin/id/nearyou/app/moderation/ReportRateLimiter.kt:92` — `RedisHashTagRule` accepts this form)

#### Scenario: Window is Duration.ofHours(1)
- **WHEN** the rate limiter constructs its `RateLimiter.tryAcquire(...)` call
- **THEN** the `window` argument is `Duration.ofHours(1)` (3600 seconds, sliding)

#### Scenario: Hourly cap is exempt from computeTTLToNextReset
- **WHEN** static analysis (`RateLimitTtlRule`) inspects the rate-limit call site
- **THEN** the rule does NOT fire — the helper `computeTTLToNextReset(user_id)` is reserved for daily WIB-stagger caps, not hourly Layer-2 caps

#### Scenario: 60 queries within an hour all succeed
- **WHEN** a Premium user issues 60 search queries within a 60-minute window
- **THEN** all 60 receive `200` AND no `429` is returned

#### Scenario: 61st query in the same hour returns 429
- **WHEN** a Premium user issues a 61st search query within the same 60-minute window
- **THEN** the response is `429` with a `Retry-After` header whose value equals `RateLimiter.Outcome.RateLimited.retryAfterSeconds` (seconds until the oldest entry in the window expires)

### Requirement: Pagination via OFFSET with next_offset cursor and bounded offset

The handler MUST accept an optional `offset` query parameter (default `0`, integer ≥ 0) and pass it as `OFFSET :offset` to the canonical query. The handler MUST reject `offset > 10000` with `400 { error: "invalid_offset" }` to defend against adversarial deep-OFFSET requests that force Postgres to materialize-and-discard a huge prefix from the GIN bitmap heap-fetch path (DoS surface even within the 60/hour cap; expensive per-query). The response body MUST include `next_offset`: the integer to use for the next page (typically `offset + results.length`), or `null` when fewer than 20 results were returned (signaling end of results).

#### Scenario: First page returns next_offset = 20 when full
- **WHEN** a query matches at least 21 posts AND the request omits `offset`
- **THEN** the response includes 20 results AND `next_offset = 20`

#### Scenario: Last page returns next_offset = null
- **WHEN** a query matches fewer than 20 posts at the requested offset
- **THEN** the response includes the available results AND `next_offset = null`

#### Scenario: Exactly-20-match boundary returns next_offset = 20 then empty page 2
- **WHEN** a query matches exactly 20 posts AND the request omits `offset`
- **THEN** page 1 returns 20 results AND `next_offset = 20`
- **AND** a follow-up request with `offset=20` returns `{ results: [], next_offset: null }`
- **AND** clients SHOULD treat `results = []` as a terminal signal even when `next_offset != null` was returned by the previous page

#### Scenario: Negative or non-integer offset rejected
- **WHEN** the request includes `offset=-1` or `offset=abc`
- **THEN** the response is `400` with body `{ error: "invalid_offset" }`

#### Scenario: Excessive offset rejected
- **WHEN** the request includes `offset=10001` (or any value greater than 10000)
- **THEN** the response is `400` with body `{ error: "invalid_offset" }` AND no DB query is executed

#### Scenario: Concurrent insert can shift offsets (documented limitation)
- **WHEN** a new matching post is inserted between the client's page-1 and page-2 requests
- **THEN** the page-2 OFFSET semantics MAY produce a duplicate or missing row at the page boundary — this is a known FTS+OFFSET limitation that the spec accepts; clients tolerating this are correct; clients requiring strict consistency MUST use a future cursor-based pagination (out of scope for this capability)

### Requirement: 'simple' tsvector configuration is the MVP choice

The query MUST use `plainto_tsquery('simple', :query)` (matching the `posts.content_tsv` GENERATED column's `to_tsvector('simple', content)` definition from `docs/05-Implementation.md:562`). The `'simple'` config performs no stemming and no Indonesian stopword removal — this is an explicit MVP limitation per `docs/05-Implementation.md:1159`. Any future upgrade to a custom Indonesian dictionary MUST update both the GENERATED column expression (Flyway migration) AND this requirement in lockstep.

#### Scenario: Lexeme-level matching, no stemming
- **WHEN** the query is `makanan` AND a post contains the word `makan` (and no other lexemes match the trigram threshold)
- **THEN** the post does NOT match the FTS predicate (the `'simple'` config does not stem `makanan` to `makan`)
- **AND** the post is NOT returned via the trigram `%` operator either when string similarity is below `pg_trgm.similarity_threshold` (0.3 per pinned default — the trigram clause is the fallback for fuzzy intent but does not bridge the stemming gap on short words)

#### Scenario: Case-insensitive matching via 'simple'
- **WHEN** the query is `JAKARTA` (uppercase) AND a post contains `jakarta` (lowercase)
- **THEN** the post matches via the FTS predicate — the `'simple'` config lowercases at lexeme generation, so `JAKARTA` → lexeme `jakarta` matches the stored `content_tsv` lexeme `jakarta`
