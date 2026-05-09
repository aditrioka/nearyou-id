## ADDED Requirements

### Requirement: Global route delegates read-rate-limit accounting to `timeline-read-rate-limit`

The `GET /api/v1/timeline/global` route handler SHALL delegate read-side rate-limit accounting (rolling 150-posts/hour hard cap + 50-posts/session soft cap, Free-tier only, Premium exempt) to the `timeline-read-rate-limit` capability per its full contract — for AUTHENTICATED callers only. Guest reads (when wired in a future change) are NOT covered by this requirement; guest read rate limiting (`10 posts/session + 30 posts/hour`) lands in a separate change MODIFYING `timeline-read-rate-limit` once the Layer 1 attestation-gated guest-session JWT issuance work ships (per [`docs/02-Product.md:181-183`](../../../docs/02-Product.md) + [`docs/08-Roadmap-Risk.md`](../../../docs/08-Roadmap-Risk.md) Phase 1 item 25).

The route handler MUST:

- Run the rolling pre-check + session pre-check BEFORE the canonical Global SQL query (per `timeline-read-rate-limit` § "Limiter ordering and pre-execution before DB"). Pre-check key shapes are `{scope:rate_timeline_rolling}:{user:<user_id>}` and `{scope:rate_timeline_session}:{user:<user_id>}:<session_id_or_no-session>`.
- On rolling-cap `RateLimited`: return HTTP 200 with `{ "posts": [], "next_cursor": null, "upsell": { "hard": true } }`. Do NOT execute the canonical Global SQL query. The existing Global query (FROM visible_posts, no follows filter, no spatial filter, bidirectional block exclusion, V7 likes LEFT JOIN, V8 reply-count LEFT JOIN LATERAL, V11 `city_name` projection from the trigger-populated column) and response shape requirements remain unchanged for the non-cap-hit path.
- On a successful response (rolling pre-check admitted, query executed, returning `N` posts where `0 ≤ N ≤ 30`): bump both buckets by `N - 1` additional best-effort `tryAcquire` calls (1 already consumed at pre-check). Build the response per the existing Global response shape PLUS the optional `upsell` object per the `timeline-read-rate-limit` contract.
- For Premium callers (`subscription_status IN ('premium_active', 'premium_billing_retry')`): SKIP both pre-checks and post-increment entirely. Run the canonical Global query and respond per the existing shape; never include the `upsell` field.

The existing Global requirements (route + auth, query parameters, canonical query reading FROM visible_posts with bidirectional `user_blocks` NOT-IN exclusion + V7 likes LEFT JOIN + V8 reply-count LEFT JOIN LATERAL + V11 `city_name` projection from the trigger-populated column, keyset pagination, per-page cap of 30, response shape including `city_name` and excluding `distance_m`, and the existing integration test coverage) remain unchanged. The rate-limit gate is a NEW pre-DB short-circuit; it does NOT alter the SQL query, the response post shape, the cursor format, or any of the existing invariants. Guest read access remains explicitly out-of-scope for this change.

#### Scenario: Free Global read at rolling cap returns empty + upsell.hard
- **WHEN** Free-tier caller A's rolling bucket holds 150 entries AND A issues `GET /api/v1/timeline/global`
- **THEN** the response is HTTP 200 with body `{ "posts": [], "next_cursor": null, "upsell": { "hard": true } }` AND zero `posts` SELECTs were issued to Postgres for the request

#### Scenario: Free Global read at session-soft-cap still returns posts with city_name
- **WHEN** Free-tier caller A's session bucket (under `X-Session-Id: SID`) holds 60 entries AND the rolling bucket holds 80 entries AND A issues a Global read
- **THEN** the response is HTTP 200 with `posts.length > 0` (DB-permitting) AND `upsell.soft = true` AND every post in the response carries the `city_name` field per V11 invariant

#### Scenario: Premium Global read bypasses rate limit
- **WHEN** Premium caller A (`subscription_status = 'premium_active'`) issues a Global read after having read 500 posts in the last hour
- **THEN** the response is HTTP 200 with the Global content AND no `upsell` field AND zero rate-limit Redis calls were issued for this request

#### Scenario: Cross-tab accumulation — Global read at cap-after-mixed-reads
- **WHEN** Free-tier caller A has read 50 posts on Nearby + 50 on Following + 50 on Global within the current hour AND A issues a 4th Global read
- **THEN** the 4th Global response is HTTP 200 with `posts = []` AND `upsell.hard = true` (the rolling cap aggregates across all three tabs per the `timeline-read-rate-limit` capability contract)

#### Scenario: Guest endpoint still NOT wired in this change
- **WHEN** searching the codebase post-change for a guest-accessible variant of `GET /api/v1/timeline/global` (i.e., a route accepting an unauthenticated request)
- **THEN** none is found — guest access remains the deferred Phase 1 item 25 work; this change ships only the authenticated path's rate limit

#### Scenario: Global below caps — response shape unchanged
- **WHEN** Free caller A is below both caps AND issues a Global read returning 18 posts
- **THEN** the response body matches the existing Global response shape exactly (the `upsell` key is NOT present, `distance_m` is absent, `city_name` is present per V11) AND all per-post fields are present
