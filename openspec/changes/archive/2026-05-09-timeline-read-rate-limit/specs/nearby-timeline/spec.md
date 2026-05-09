## ADDED Requirements

### Requirement: Nearby route delegates read-rate-limit accounting to `timeline-read-rate-limit`

The `GET /api/v1/timeline/nearby` route handler SHALL delegate read-side rate-limit accounting (rolling 150-posts/hour hard cap + 50-posts/session soft cap, Free-tier only, Premium exempt) to the `timeline-read-rate-limit` capability per its full contract.

The route handler MUST:

- Run the rolling pre-check + session pre-check BEFORE the canonical Nearby SQL query (per `timeline-read-rate-limit` § "Limiter ordering and pre-execution before DB"). Pre-check key shapes are `{scope:rate_timeline_rolling}:{user:<user_id>}` and `{scope:rate_timeline_session}:{session:<user_id>__<sanitized_session_id>}`.
- On rolling-cap `RateLimited`: return HTTP 200 with `{ "posts": [], "next_cursor": null, "upsell": { "hard": true } }`. Do NOT execute the canonical Nearby SQL query (which is especially expensive for Nearby due to the PostGIS `ST_DWithin` + `ST_Distance` cost on `display_location`). The existing Nearby query, block-exclusion, and `liked_by_viewer` / `reply_count` / `city_name` projection requirements remain unchanged for the non-cap-hit path.
- On a successful response (rolling pre-check admitted, query executed, returning `N` posts where `0 ≤ N ≤ 30`): bump both buckets via `(N - 1).coerceAtLeast(0)` additional best-effort `tryAcquire` calls (1 already consumed at pre-check). Build the response per the existing Nearby response shape PLUS the optional `upsell` object per the `timeline-read-rate-limit` contract.
- Validate the `X-Session-Id` header per `timeline-read-rate-limit` § "X-Session-Id header validation"; substitute with `no-session` on missing or malformed values.
- For Premium callers (`subscription_status IN ('premium_active', 'premium_billing_retry')`): SKIP both pre-checks and post-increment entirely. Run the canonical Nearby query and respond per the existing shape; never include the `upsell` field.

The existing Nearby requirements ("Canonical query joins visible_posts and excludes blocks bidirectionally", "Keyset pagination on (created_at DESC, id DESC)", "Per-page cap of 30", "Response shape", "Response projects city_name on every post as of V11", and the V11-extended Integration test coverage requirement) remain unchanged. The rate-limit gate is a NEW pre-DB short-circuit; it does NOT alter the SQL query, the response post shape (per-post fields are unchanged), the cursor format, or any of the V5–V11 invariants.

#### Scenario: Free Nearby read at rolling cap returns empty + upsell.hard
- **WHEN** Free-tier caller A's rolling bucket holds 150 entries AND A issues `GET /api/v1/timeline/nearby?lat=-6.2&lng=106.8&radius_m=1000`
- **THEN** the response is HTTP 200 with body `{ "posts": [], "next_cursor": null, "upsell": { "hard": true } }` AND zero `posts` SELECTs were issued to Postgres for the request AND no PostGIS `ST_DWithin` execution

#### Scenario: Free Nearby read at session-soft-cap still returns posts
- **WHEN** Free-tier caller A's session bucket (under `X-Session-Id: SID`) is at 50/50 capacity AND the rolling bucket holds 80/150 entries AND A issues a Nearby read
- **THEN** the response is HTTP 200 with `posts.length > 0` (DB-permitting) AND `upsell.soft = true` AND the canonical Nearby SQL DID execute

#### Scenario: Premium Nearby read bypasses rate limit
- **WHEN** Premium caller A (`subscription_status = 'premium_active'`) issues a Nearby read after having read 500 posts in the last hour
- **THEN** the response is HTTP 200 with the Nearby content AND no `upsell` field AND zero rate-limit Redis calls were issued for this request (verified via Redis-counter spy)

#### Scenario: Nearby below caps — response shape unchanged
- **WHEN** Free caller A is below both caps AND issues a Nearby read returning 5 posts
- **THEN** the response body matches the existing Nearby response shape exactly (the `upsell` key is NOT present) AND all V5–V11 per-post fields are present (id, author_user_id, content, latitude, longitude, distance_m, created_at, liked_by_viewer, reply_count, city_name)

#### Scenario: Nearby empty radius result still consumes 1 rolling slot
- **WHEN** Free-tier caller A is at slot 0/150 rolling AND issues a Nearby read where the spatial filter returns zero posts (e.g., a remote ocean coordinate where no posts exist)
- **THEN** the response is HTTP 200 with `posts = []` AND `next_cursor = null` AND no `upsell` field (below caps) AND the rolling bucket holds exactly 1 entry after the response (the pre-check consumed 1 slot regardless of N=0; the post-increment is `(0-1).coerceAtLeast(0) = 0` — skipped)

#### Scenario: Nearby PostGIS query NOT executed on cap-hit
- **WHEN** Free-tier caller A's rolling bucket is at 150/150 AND A issues a Nearby read with valid `lat`/`lng`/`radius_m`
- **THEN** the response is HTTP 200 with `posts = []` + `upsell.hard = true` AND zero PostGIS function invocations are issued (no `ST_DWithin`, no `ST_Distance`)

#### Scenario: Nearby per-page cap and post-increment math
- **WHEN** Free-tier caller A is at slot 0/150 rolling AND issues a Nearby read returning the page-cap of 30 posts
- **THEN** the rolling bucket holds exactly 30 entries after the response (1 from pre-check + `(30-1) = 29` best-effort additional `tryAcquire` calls all admitted) AND the response body matches the existing Nearby response shape with 30 posts AND no `upsell` field
