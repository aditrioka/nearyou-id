## ADDED Requirements

### Requirement: Following route delegates read-rate-limit accounting to `timeline-read-rate-limit`

The `GET /api/v1/timeline/following` route handler SHALL delegate read-side rate-limit accounting (rolling 150-posts/hour hard cap + 50-posts/session soft cap, Free-tier only, Premium exempt) to the `timeline-read-rate-limit` capability per its full contract.

The route handler MUST:

- Run the rolling pre-check + session pre-check BEFORE the canonical Following SQL query (per `timeline-read-rate-limit` § "Limiter ordering and pre-execution before DB"). Pre-check key shapes are `{scope:rate_timeline_rolling}:{user:<user_id>}` and `{scope:rate_timeline_session}:{user:<user_id>}:<session_id_or_no-session>`.
- On rolling-cap `RateLimited`: return HTTP 200 with `{ "posts": [], "next_cursor": null, "upsell": { "hard": true } }`. Do NOT execute the canonical Following SQL query (which would be especially wasteful since Following has the `IN (SELECT followee_id FROM follows ...)` subquery and bidirectional block exclusion). The existing Following query, block-exclusion, follows-filter, and `liked_by_viewer` / `reply_count` / `city_name` projection requirements remain unchanged for the non-cap-hit path.
- On a successful response (rolling pre-check admitted, query executed, returning `N` posts where `0 ≤ N ≤ 30`): bump both buckets by `N - 1` additional best-effort `tryAcquire` calls (1 already consumed at pre-check). Build the response per the existing Following response shape PLUS the optional `upsell` object per the `timeline-read-rate-limit` contract.
- For Premium callers (`subscription_status IN ('premium_active', 'premium_billing_retry')`): SKIP both pre-checks and post-increment entirely. Run the canonical Following query and respond per the existing shape; never include the `upsell` field.

The existing Following requirements (route + auth, query parameters, the canonical query with follows-IN + bidirectional block exclusion + V7 likes LEFT JOIN + V8 reply-count LEFT JOIN LATERAL, keyset pagination, per-page cap of 30, response shape minus `distance_m` plus `city_name`, the V11 city_name extension, and the V11-extended integration test coverage) remain unchanged. The rate-limit gate is a NEW pre-DB short-circuit; it does NOT alter the SQL query, the response post shape, the cursor format, or any of the V6–V11 invariants.

#### Scenario: Free Following read at rolling cap returns empty + upsell.hard
- **WHEN** Free-tier caller A's rolling bucket holds 150 entries AND A issues `GET /api/v1/timeline/following`
- **THEN** the response is HTTP 200 with body `{ "posts": [], "next_cursor": null, "upsell": { "hard": true } }` AND zero `posts` SELECTs were issued to Postgres for the request (no `follows`-IN subquery executed either)

#### Scenario: Free Following read at session-soft-cap still returns posts
- **WHEN** Free-tier caller A's session bucket (under `X-Session-Id: SID`) holds 55 entries AND the rolling bucket holds 90 entries AND A follows several authors AND A issues a Following read
- **THEN** the response is HTTP 200 with `posts.length > 0` (DB-permitting; could be 0 if A follows nobody, but that's the empty-set case below) AND `upsell.soft = true`

#### Scenario: Premium Following read bypasses rate limit
- **WHEN** Premium caller A (`subscription_status = 'premium_billing_retry'`) issues a Following read after having read 500 posts in the last hour
- **THEN** the response is HTTP 200 with the Following content AND no `upsell` field AND zero rate-limit Redis calls were issued for this request

#### Scenario: Empty-follows result still consumes rolling slot
- **WHEN** Free-tier caller A follows nobody AND issues a Following read at slot 0/150
- **THEN** the response is HTTP 200 with `posts = []` AND `next_cursor = null` AND no `upsell` field (below caps) AND the rolling bucket holds exactly 1 entry after the response (the pre-check consumed 1 slot regardless of N=0; no post-increment occurred since `N - 1 = -1` is skipped)

#### Scenario: Following below caps — response shape unchanged
- **WHEN** Free caller A is below both caps AND issues a Following read returning 12 posts
- **THEN** the response body matches the existing Following response shape exactly (the `upsell` key is NOT present, `distance_m` is absent, `city_name` is present per V11) AND all V6–V11 per-post fields are present
