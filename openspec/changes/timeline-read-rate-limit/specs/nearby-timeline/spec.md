## ADDED Requirements

### Requirement: Nearby route delegates read-rate-limit accounting to `timeline-read-rate-limit`

The `GET /api/v1/timeline/nearby` route handler SHALL delegate read-side rate-limit accounting (rolling 150-posts/hour hard cap + 50-posts/session soft cap, Free-tier only, Premium exempt) to the `timeline-read-rate-limit` capability per its full contract.

The route handler MUST:

- Run the rolling pre-check + session pre-check BEFORE the canonical Nearby SQL query (per `timeline-read-rate-limit` § "Limiter ordering and pre-execution before DB"). Pre-check key shapes are `{scope:rate_timeline_rolling}:{user:<user_id>}` and `{scope:rate_timeline_session}:{user:<user_id>}:<session_id_or_no-session>`.
- On rolling-cap `RateLimited`: return HTTP 200 with `{ "posts": [], "next_cursor": null, "upsell": { "hard": true } }`. Do NOT execute the canonical Nearby SQL query. The existing Nearby query, block-exclusion, and `liked_by_viewer` / `reply_count` / `city_name` projection requirements remain unchanged for the non-cap-hit path.
- On a successful response (rolling pre-check admitted, query executed, returning `N` posts where `0 ≤ N ≤ 30`): bump both buckets by `N - 1` additional best-effort `tryAcquire` calls (1 already consumed at pre-check). Build the response per the existing Nearby response shape PLUS the optional `upsell` object per the `timeline-read-rate-limit` contract.
- For Premium callers (`subscription_status IN ('premium_active', 'premium_billing_retry')`): SKIP both pre-checks and post-increment entirely. Run the canonical Nearby query and respond per the existing shape; never include the `upsell` field.

The existing Nearby requirements ("Canonical query joins visible_posts and excludes blocks bidirectionally", "Keyset pagination on (created_at DESC, id DESC)", "Per-page cap of 30", "Response shape", "Response projects city_name on every post as of V11", and the V11-extended Integration test coverage requirement) remain unchanged. The rate-limit gate is a NEW pre-DB short-circuit; it does NOT alter the SQL query, the response post shape (per-post fields are unchanged), the cursor format, or any of the V5–V11 invariants.

#### Scenario: Free Nearby read at rolling cap returns empty + upsell.hard
- **WHEN** Free-tier caller A's rolling bucket holds 150 entries AND A issues `GET /api/v1/timeline/nearby?lat=-6.2&lng=106.8&radius_m=1000`
- **THEN** the response is HTTP 200 with body `{ "posts": [], "next_cursor": null, "upsell": { "hard": true } }` AND zero `posts` SELECTs were issued to Postgres for the request

#### Scenario: Free Nearby read at session-soft-cap still returns posts
- **WHEN** Free-tier caller A's session bucket (under `X-Session-Id: SID`) holds 60 entries AND the rolling bucket holds 80 entries AND A issues a Nearby read
- **THEN** the response is HTTP 200 with `posts.length > 0` (DB-permitting) AND `upsell.soft = true` AND the canonical Nearby SQL DID execute

#### Scenario: Premium Nearby read bypasses rate limit
- **WHEN** Premium caller A (`subscription_status = 'premium_active'`) issues a Nearby read after having read 500 posts in the last hour
- **THEN** the response is HTTP 200 with the Nearby content AND no `upsell` field AND zero rate-limit Redis calls were issued for this request

#### Scenario: Nearby below caps — response shape unchanged
- **WHEN** Free caller A is below both caps AND issues a Nearby read returning 5 posts
- **THEN** the response body matches the existing Nearby response shape exactly (the `upsell` key is NOT present) AND all V5–V11 per-post fields are present
