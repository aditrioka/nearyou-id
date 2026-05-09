# global-timeline Specification

## Purpose

The global-timeline capability defines `GET /api/v1/timeline/global`, the third and final timeline tab — a chronological cross-Indonesia feed of posts. The query reads `FROM visible_posts` with the same bidirectional `user_blocks` NOT-IN exclusion + V7 likes LEFT JOIN + V8 reply-count LEFT JOIN LATERAL pattern as Nearby and Following, keyset-paginated on `(created_at DESC, id DESC)`, and adds a required `city_name` field to every item (denormalized at write time by the `posts_set_city_tg` trigger). MVP scope is authenticated-only, strictly chronological with no province / island filter or ranking; guest read access and Redis-backed session/hour scroll caps are deferred to follow-up changes.
## Requirements
### Requirement: GET /api/v1/timeline/global endpoint exists

A Ktor route SHALL be registered at `GET /api/v1/timeline/global`. The route MUST require Bearer JWT authentication via the existing `auth-jwt` plugin; an unauthenticated request MUST receive HTTP 401 with error code `unauthenticated`. The route handler MUST live under `backend/ktor/src/main/kotlin/id/nearyou/app/timeline/` alongside `NearbyTimelineService` and `FollowingTimelineService`. Guest access is explicitly NOT wired in this capability and lands with a separate rate-limit / guest-auth change.

#### Scenario: Unauthenticated rejected
- **WHEN** `GET /api/v1/timeline/global` is called with no `Authorization` header
- **THEN** the response is HTTP 401 with `error.code = "unauthenticated"`

#### Scenario: Authenticated routed to handler
- **WHEN** the same request is made with a valid Bearer JWT
- **THEN** the request reaches the handler (HTTP status is not 401)

### Requirement: Query parameters

The endpoint SHALL accept only `cursor` as an optional query parameter. No `lat`/`lng`/`radius_m` parameters are accepted; the endpoint is purely chronological-over-all-visible-authors. Unknown query parameters MUST be ignored (not rejected).

#### Scenario: No params returns first page
- **WHEN** the request supplies no query params
- **THEN** the response is HTTP 200 with the first page of Global posts

#### Scenario: Malformed cursor rejected
- **WHEN** `cursor=not-a-base64-json`
- **THEN** the response is HTTP 400 with `error.code = "invalid_cursor"`

### Requirement: Canonical query runs FROM visible_posts with bidirectional block exclusion

The endpoint's data query SHALL be `FROM visible_posts` (NOT `FROM posts`), with NO `follows` filter (Global is chronological over every visible author), NO `ST_DWithin` / `ST_Distance`, and two NOT-IN subqueries excluding `user_blocks` rows in BOTH directions:
- `author_user_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = :viewer)`
- `author_user_id NOT IN (SELECT blocker_id FROM user_blocks WHERE blocked_id = :viewer)`

Both block-exclusion subqueries MUST be present simultaneously so `BlockExclusionJoinRule` passes on the new query literal.

The query SHALL carry the V7 `LEFT JOIN post_likes pl ON pl.post_id = p.id AND pl.user_id = :viewer` projecting `(pl.user_id IS NOT NULL) AS liked_by_viewer`, and the V8 `LEFT JOIN LATERAL (SELECT COUNT(*) AS n FROM post_replies pr JOIN visible_users vu ON vu.id = pr.author_id WHERE pr.post_id = p.id AND pr.deleted_at IS NULL) c ON TRUE` projecting `c.n AS reply_count`. Both joins MUST uphold the cardinality invariants already enforced on Nearby and Following (at most one `post_likes` row per outer row via PK; exactly one LATERAL scalar row per outer row; neither join appears in `ORDER BY` or the keyset predicate).

The reply counter MUST filter shadow-banned repliers via `JOIN visible_users` and tombstoned replies via `pr.deleted_at IS NULL`, and MUST NOT apply viewer-block exclusion to the counter (same privacy tradeoff as Nearby and Following).

The query SHALL project `p.city_name` directly from `visible_posts` and MUST NOT perform any `ST_Contains`, `admin_regions` JOIN, or other spatial work at read time.

#### Scenario: Post from auto-hidden author excluded
- **WHEN** a post has `is_auto_hidden = TRUE`
- **THEN** that post does NOT appear in the response (enforced by the `visible_posts` filter)

#### Scenario: Viewer-blocked author excluded
- **WHEN** the calling user has a `user_blocks` row `(blocker_id = caller, blocked_id = X)` AND X has a recent post
- **THEN** X's post does NOT appear in the response

#### Scenario: Viewer-blocked-by-author excluded
- **WHEN** there is a `user_blocks` row `(blocker_id = X, blocked_id = caller)` AND X has a recent post
- **THEN** X's post does NOT appear in the response

#### Scenario: Non-followed author NOT excluded (Global has no follows filter)
- **WHEN** a post has `author_user_id = X` AND the caller does NOT follow X AND there is no `user_blocks` row between caller and X
- **THEN** X's post DOES appear in the response (Global surfaces every visible author)

#### Scenario: No admin_regions JOIN at read time
- **WHEN** inspecting the SQL issued by `GlobalTimelineService`
- **THEN** the SQL contains neither `admin_regions` as a table reference NOR `ST_Contains` as a function call

#### Scenario: LEFT JOIN post_likes does not alter row count
- **WHEN** 35 visible posts exist for a viewer who has liked 7 of them
- **THEN** the query returns exactly 35 rows

#### Scenario: Reply counter excludes shadow-banned repliers
- **WHEN** post P has 3 replies, 1 of which is by a `is_shadow_banned = TRUE` user
- **THEN** the response item for P has `reply_count = 2`

### Requirement: Keyset pagination on (created_at DESC, id DESC)

The endpoint SHALL paginate via keyset on `(created_at DESC, id DESC)` using the existing `posts_timeline_cursor_idx` index. The cursor parameter is a base64url-encoded JSON object `{"c":"<created_at ISO-8601>","i":"<post UUID>"}`, identical in shape and codec to Nearby and Following. The endpoint MUST NOT use SQL `OFFSET`. A malformed cursor MUST yield HTTP 400 with error code `invalid_cursor`.

#### Scenario: First page no cursor
- **WHEN** the request has no `cursor`
- **THEN** the SQL has no `(p.created_at, p.id) <` clause AND returns the most recent posts

#### Scenario: Subsequent page with cursor
- **WHEN** the response on page 1 contains `next_cursor` AND the next request supplies that cursor
- **THEN** the SQL includes `(p.created_at, p.id) < (cursor.c, cursor.i)` AND no row from page 1 appears in page 2

#### Scenario: Malformed cursor rejected
- **WHEN** `cursor=not-a-base64-json`
- **THEN** the response is HTTP 400 with `error.code = "invalid_cursor"`

### Requirement: Per-page cap of 30

The endpoint SHALL `LIMIT` the SQL query to `31` (page-size 30 plus one probe row). The response `posts` array MUST contain at most 30 elements. The probe row, if present, MUST NOT appear in the response and MUST seed `next_cursor`. Redis-backed session/hour soft+hard caps are explicitly out of scope for this change.

#### Scenario: At most 30 posts in response
- **WHEN** there are 100 visible posts for a given viewer
- **THEN** `response.posts.length <= 30`

#### Scenario: next_cursor present when more exist
- **WHEN** there are >30 matching posts AND the response contains 30 posts
- **THEN** `response.next_cursor` is a non-null base64url string

#### Scenario: next_cursor null on last page
- **WHEN** the response contains <30 posts (or exactly 30 with no further matches)
- **THEN** `response.next_cursor` is `null`

### Requirement: Response shape

A successful response SHALL be HTTP 200 with body:

```json
{
  "posts": [
    {
      "id": "<uuid>",
      "author_user_id": "<uuid>",
      "content": "<string>",
      "latitude": <double>,
      "longitude": <double>,
      "city_name": "<string>",
      "created_at": "<ISO-8601 UTC>",
      "liked_by_viewer": <boolean>,
      "reply_count": <integer>
    }
  ],
  "next_cursor": "<string or null>"
}
```

The shape is identical to Nearby **minus** `distance_m` and **plus** `city_name`. The `latitude`/`longitude` fields MUST be derived from `display_location` (NOT `actual_location`). Global MUST NOT include a `distance_m` field at all (neither as `null` nor as a number) — it is a chronological feed with no reference point.

The `city_name` field MUST be a JSON string and MUST be present on EVERY post in the response (never omitted). It MUST equal `posts.city_name` as populated by the `posts_set_city_tg` trigger (see `region-polygons` capability). If the underlying DB value is NULL (legacy pre-trigger post or polygon-coverage gap), the field MUST serialize as the empty string `""`, never as JSON `null` and never omitted.

The `liked_by_viewer` and `reply_count` fields MUST behave exactly as in the Nearby and Following specs: `liked_by_viewer` is derived from the V7 LEFT JOIN, `reply_count` from the V8 LEFT JOIN LATERAL, both always present and never null.

#### Scenario: Coordinates from display_location
- **WHEN** a post in the response has database `display_location = POINT(106.8 -6.2)`
- **THEN** the response item has `latitude = -6.2` AND `longitude = 106.8`

#### Scenario: actual_location not exposed
- **WHEN** searching the response JSON for `actual_location` or any value matching the post's actual coordinates
- **THEN** no match is found

#### Scenario: distance_m field absent
- **WHEN** the response contains any post
- **THEN** no post object contains a `distance_m` key (neither as `null` nor as a number)

#### Scenario: city_name present and string-typed on every post
- **WHEN** the response contains any number of posts (including zero, one, or many)
- **THEN** every post object contains the key `city_name` with a JSON string value (never omitted, never `null`)

#### Scenario: city_name empty string when underlying row is NULL
- **WHEN** a post's `posts.city_name` column is NULL (legacy pre-trigger row OR polygon gap)
- **THEN** the response item for that post has `city_name = ""`

#### Scenario: city_name reflects trigger-populated value
- **WHEN** a post was created after the `posts_set_city_tg` trigger was deployed AND its `actual_location` fell inside the polygon named "Jakarta Selatan"
- **THEN** the response item has `city_name = "Jakarta Selatan"`

### Requirement: Integration test coverage

`GlobalTimelineServiceTest` (tagged `database`) SHALL cover, at minimum, these scenarios end-to-end against a Postgres+PostGIS test DB:
1. Happy path: three posts by three different authors with different city polygons, ordered by `created_at DESC`, each carrying the expected `city_name`.
2. Cursor pagination: 35 posts, page 1 returns 30 + cursor, page 2 returns 5, no overlap.
3. No follows filter: caller follows nobody; response still contains all visible posts.
4. Auto-hidden exclusion.
5. Bidirectional block exclusion (two sub-cases: viewer blocks author; author blocks viewer).
6. Auth required (HTTP 401 without JWT).
7. `liked_by_viewer = true` / `false` / present on every post (three scenarios).
8. LEFT JOIN cardinality invariant with likes.
9. `reply_count = 0` for a post with no replies.
10. `reply_count` excludes shadow-banned repliers.
11. `reply_count` excludes soft-deleted replies.
12. `reply_count` does NOT apply viewer-block exclusion.
13. `city_name` reflects trigger-populated value for a post inserted after V11.
14. `city_name = ""` for a legacy post whose `posts.city_name` column is NULL.
15. `city_name` present on every post (iterate response; assert key presence; assert type is string).
16. `distance_m` absent from every post in every response.
17. Malformed cursor → HTTP 400 `invalid_cursor`.

#### Scenario: Test class exists
- **WHEN** running `./gradlew :backend:ktor:test --tests '*GlobalTimelineServiceTest*'`
- **THEN** the class is discovered AND every scenario above corresponds to at least one `@Test` method

### Requirement: Global route delegates read-rate-limit accounting to `timeline-read-rate-limit`

The `GET /api/v1/timeline/global` route handler SHALL delegate read-side rate-limit accounting (rolling 150-posts/hour hard cap + 50-posts/session soft cap, Free-tier only, Premium exempt) to the `timeline-read-rate-limit` capability per its full contract — for AUTHENTICATED callers only. Guest reads (when wired in a future change) are NOT covered by this requirement; guest read rate limiting (`10 posts/session + 30 posts/hour`) lands in a separate change MODIFYING `timeline-read-rate-limit` once the Layer 1 attestation-gated guest-session JWT issuance work ships (per [`docs/02-Product.md:181-183`](../../../docs/02-Product.md) + [`docs/08-Roadmap-Risk.md`](../../../docs/08-Roadmap-Risk.md) Phase 1 item 25).

The route handler MUST:

- Run the rolling pre-check + session pre-check BEFORE the canonical Global SQL query (per `timeline-read-rate-limit` § "Limiter ordering and pre-execution before DB"). Pre-check key shapes are `{scope:rate_timeline_rolling}:{user:<user_id>}` and `{scope:rate_timeline_session}:{session:<user_id>__<sanitized_session_id>}`.
- On rolling-cap `RateLimited`: return HTTP 200 with `{ "posts": [], "next_cursor": null, "upsell": { "hard": true } }`. Do NOT execute the canonical Global SQL query. The existing Global query (FROM visible_posts, no follows filter, no spatial filter, bidirectional block exclusion, V7 likes LEFT JOIN, V8 reply-count LEFT JOIN LATERAL, V11 `city_name` projection from the trigger-populated column) and response shape requirements remain unchanged for the non-cap-hit path.
- On a successful response (rolling pre-check admitted, query executed, returning `N` posts where `0 ≤ N ≤ 30`): bump both buckets via `(N - 1).coerceAtLeast(0)` additional best-effort `tryAcquire` calls (1 already consumed at pre-check). Build the response per the existing Global response shape PLUS the optional `upsell` object per the `timeline-read-rate-limit` contract.
- Validate the `X-Session-Id` header per `timeline-read-rate-limit` § "X-Session-Id header validation"; substitute with `no-session` on missing or malformed values.
- For Premium callers (`subscription_status IN ('premium_active', 'premium_billing_retry')`): SKIP both pre-checks and post-increment entirely. Run the canonical Global query and respond per the existing shape; never include the `upsell` field.

The existing Global requirements (route + auth, query parameters, canonical query reading FROM visible_posts with bidirectional `user_blocks` NOT-IN exclusion + V7 likes LEFT JOIN + V8 reply-count LEFT JOIN LATERAL + V11 `city_name` projection from the trigger-populated column, keyset pagination, per-page cap of 30, response shape including `city_name` and excluding `distance_m`, and the existing integration test coverage) remain unchanged. The rate-limit gate is a NEW pre-DB short-circuit; it does NOT alter the SQL query, the response post shape, the cursor format, or any of the existing invariants. Guest read access remains explicitly out-of-scope for this change.

#### Scenario: Free Global read at rolling cap returns empty + upsell.hard
- **WHEN** Free-tier caller A's rolling bucket holds 150 entries AND A issues `GET /api/v1/timeline/global`
- **THEN** the response is HTTP 200 with body `{ "posts": [], "next_cursor": null, "upsell": { "hard": true } }` AND zero `posts` SELECTs were issued to Postgres for the request

#### Scenario: Free Global read at session-soft-cap still returns posts with city_name
- **WHEN** Free-tier caller A's session bucket (under `X-Session-Id: SID`) is at 50/50 capacity AND the rolling bucket holds 80/150 entries AND A issues a Global read
- **THEN** the response is HTTP 200 with `posts.length > 0` (DB-permitting) AND `upsell.soft = true` AND every post in the response carries the `city_name` field per V11 invariant

#### Scenario: Premium Global read bypasses rate limit
- **WHEN** Premium caller A (`subscription_status = 'premium_active'`) issues a Global read after having read 500 posts in the last hour
- **THEN** the response is HTTP 200 with the Global content AND no `upsell` field AND zero rate-limit Redis calls were issued for this request (verified via Redis-counter spy)

#### Scenario: Cross-tab accumulation — Global read at cap-after-mixed-reads
- **WHEN** Free-tier caller A has read 60 posts on Nearby (2 pages of 30) + 60 on Following (2 pages of 30) + 30 on Global (1 page of 30) within the current hour AND A issues a 6th request on Global
- **THEN** the 6th Global response is HTTP 200 with `posts = []` AND `upsell.hard = true` (the rolling cap aggregates across all three tabs per the `timeline-read-rate-limit` capability contract)

#### Scenario: Guest endpoint still NOT wired in this change
- **WHEN** searching the codebase post-change for a guest-accessible variant of `GET /api/v1/timeline/global` (i.e., a route accepting an unauthenticated request)
- **THEN** none is found — guest access remains the deferred Phase 1 item 25 work; this change ships only the authenticated path's rate limit

#### Scenario: Global below caps — response shape unchanged
- **WHEN** Free caller A is below both caps AND issues a Global read returning 18 posts
- **THEN** the response body matches the existing Global response shape exactly (the `upsell` key is NOT present, `distance_m` is absent, `city_name` is present per V11) AND all per-post fields are present

#### Scenario: Global per-page cap and post-increment math
- **WHEN** Free-tier caller A is at slot 0/150 rolling AND issues a Global read returning the page-cap of 30 posts
- **THEN** the rolling bucket holds exactly 30 entries after the response (1 from pre-check + `(30-1) = 29` best-effort additional `tryAcquire` calls all admitted) AND every post carries `city_name` per V11 AND no `distance_m` field on any post

#### Scenario: Global empty-result-set on a fresh-region query still consumes 1 rolling slot
- **WHEN** Free-tier caller A is at slot 0/150 rolling AND issues a Global read at a moment when no visible posts exist (an empty test-DB scenario)
- **THEN** the response is HTTP 200 with `posts = []` AND `next_cursor = null` AND no `upsell` field (below caps) AND the rolling bucket holds exactly 1 entry after the response

