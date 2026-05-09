## ADDED Requirements

### Requirement: Following route delegates read-rate-limit accounting to `timeline-read-rate-limit`

The `GET /api/v1/timeline/following` route handler SHALL delegate read-side rate-limit accounting (rolling 150-posts/hour hard cap + 50-posts/session soft cap, Free-tier only, Premium exempt) to the `timeline-read-rate-limit` capability per its full contract.

The route handler MUST:

- Run the rolling pre-check + session pre-check BEFORE the canonical Following SQL query (per `timeline-read-rate-limit` § "Limiter ordering and pre-execution before DB"). Pre-check key shapes are `{scope:rate_timeline_rolling}:{user:<user_id>}` and `{scope:rate_timeline_session}:{session:<user_id>__<sanitized_session_id>}`.
- On rolling-cap `RateLimited`: return HTTP 200 with `{ "posts": [], "next_cursor": null, "upsell": { "hard": true } }`. Do NOT execute the canonical Following SQL query (which is especially wasteful since Following has the `IN (SELECT followee_id FROM follows ...)` subquery and bidirectional block exclusion). The existing Following query, block-exclusion, follows-filter, and `liked_by_viewer` / `reply_count` / `city_name` projection requirements remain unchanged for the non-cap-hit path.
- On a successful response (rolling pre-check admitted, query executed, returning `N` posts where `0 ≤ N ≤ 30`): bump both buckets via `(N - 1).coerceAtLeast(0)` additional best-effort `tryAcquire` calls (1 already consumed at pre-check). Build the response per the existing Following response shape PLUS the optional `upsell` object per the `timeline-read-rate-limit` contract.
- Validate the `X-Session-Id` header per `timeline-read-rate-limit` § "X-Session-Id header validation"; substitute with `no-session` on missing or malformed values.
- For Premium callers (`subscription_status IN ('premium_active', 'premium_billing_retry')`): SKIP both pre-checks and post-increment entirely. Run the canonical Following query and respond per the existing shape; never include the `upsell` field.

The existing Following requirements (route + auth, query parameters, the canonical query with follows-IN + bidirectional block exclusion + V7 likes LEFT JOIN + V8 reply-count LEFT JOIN LATERAL, keyset pagination, per-page cap of 30, response shape minus `distance_m` plus `city_name`, the V11 city_name extension, and the V11-extended integration test coverage) remain unchanged. The rate-limit gate is a NEW pre-DB short-circuit; it does NOT alter the SQL query, the response post shape, the cursor format, or any of the V6–V11 invariants.

#### Scenario: Free Following read at rolling cap returns empty + upsell.hard
- **WHEN** Free-tier caller A's rolling bucket holds 150 entries AND A issues `GET /api/v1/timeline/following`
- **THEN** the response is HTTP 200 with body `{ "posts": [], "next_cursor": null, "upsell": { "hard": true } }` AND zero `posts` SELECTs were issued to Postgres for the request (no `follows`-IN subquery executed either)

#### Scenario: Free Following read at session-soft-cap still returns posts
- **WHEN** Free-tier caller A's session bucket (under `X-Session-Id: SID`) is at 50/50 capacity AND the rolling bucket holds 90/150 entries AND A follows several authors AND A issues a Following read
- **THEN** the response is HTTP 200 with `posts.length > 0` (DB-permitting) AND `upsell.soft = true`

#### Scenario: Premium Following read bypasses rate limit
- **WHEN** Premium caller A (`subscription_status = 'premium_billing_retry'`) issues a Following read after having read 500 posts in the last hour
- **THEN** the response is HTTP 200 with the Following content AND no `upsell` field AND zero rate-limit Redis calls were issued for this request (verified via Redis-counter spy)

#### Scenario: Empty-follows result still consumes rolling slot exactly once
- **WHEN** Free-tier caller A follows nobody AND issues a Following read at slot 0/150
- **THEN** the response is HTTP 200 with `posts = []` AND `next_cursor = null` AND no `upsell` field (below caps) AND the rolling bucket holds exactly 1 entry after the response (the pre-check consumed 1 slot regardless of N=0; no post-increment occurred since `(0-1).coerceAtLeast(0) = 0` calls)

#### Scenario: Following below caps — response shape unchanged
- **WHEN** Free caller A is below both caps AND issues a Following read returning 12 posts
- **THEN** the response body matches the existing Following response shape exactly (the `upsell` key is NOT present, `distance_m` is absent, `city_name` is present per V11) AND all V6–V11 per-post fields are present

#### Scenario: Following follows-IN subquery NOT executed on cap-hit
- **WHEN** Free-tier caller A's rolling bucket is at 150/150 AND A issues a Following read
- **THEN** the response is HTTP 200 with `posts = []` + `upsell.hard = true` AND zero `follows`-IN subquery executions occur (no `IN (SELECT followee_id FROM follows ...)` issued to Postgres)

#### Scenario: Following per-page cap and post-increment math
- **WHEN** Free-tier caller A is at slot 0/150 rolling AND follows enough authors AND issues a Following read returning the page-cap of 30 posts
- **THEN** the rolling bucket holds exactly 30 entries after the response (1 from pre-check + `(30-1) = 29` best-effort additional `tryAcquire` calls all admitted) AND the response shape matches the existing Following shape (no `distance_m`, includes `city_name`)
