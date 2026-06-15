## MODIFIED Requirements

### Requirement: Response shape

A successful response SHALL be HTTP 200 with body:

```json
{
  "posts": [
    {
      "id": "<uuid>",
      "authorUserId": "<uuid>",
      "authorUsername": "<string>",
      "authorDisplayName": "<string>",
      "content": "<string>",
      "latitude": <double>,
      "longitude": <double>,
      "distanceM": <double — present only when not hidden; omitted when the hide-distance rule applies>,
      "city_name": "<string>",
      "createdAt": "<ISO-8601 UTC>",
      "liked_by_viewer": <boolean>,
      "reply_count": <integer>
    }
  ],
  "nextCursor": "<string or null>"
}
```

(The example reflects the SHIPPED mixed-case wire of `TimelineRoutes.kt` — bare camelCase `authorUserId`/`authorUsername`/`authorDisplayName`/`distanceM`/`createdAt`/`nextCursor`; `@SerialName` snake_case `city_name`/`liked_by_viewer`/`reply_count`. `city_name` was added by V11 per § "Response projects city_name on every post as of V11" and is included here for example accuracy. The shape is UNCHANGED by `shadow-ban-feed-self-visibility` — self-arm rows serialize identically to visible-arm rows.)

The `latitude`/`longitude` fields MUST be derived from `display_location` (NOT `actual_location`) — including on the viewer's own self-arm rows (the author sees their own post at its fuzzed location). The `distanceM` field, WHEN PRESENT, MUST be the value computed by `ST_Distance(display_location, ST_MakePoint(:lng, :lat)::geography)` in the SQL query — server-computed, returned in raw meters. As of the `hide-distance` capability, `distanceM` is **conditionally present**: it MUST be OMITTED from a post's response object (via the app-wide `explicitNulls = false`; neither a number nor `null` on the wire) when the symmetric hide-distance rule applies to that (author, viewer) pair — i.e. when the post author's hide-distance preference is effective OR the requesting viewer's preference is effective (effectiveness = `hide_distance_opt_in = TRUE AND subscription_status IN ('premium_active','premium_billing_retry')`, per the `hide-distance` capability). Suppressing `distanceM` MUST NOT change which posts are returned, their order, the radius filter, or the `city_name` value — only the presence of the distance number. The canonical query is extended to project the author's and viewer's effective-hide inputs to evaluate this rule; this adds no JOIN beyond the existing per-arm author-identity join.

The `liked_by_viewer` field MUST be a JSON Boolean and MUST be present on EVERY post in the response (never omitted, never null). It MUST be `true` if and only if a `post_likes` row exists with `(post_id = <that post's id>, user_id = <caller>)`; otherwise `false`. The value is derived from the `LEFT JOIN post_likes` in the canonical query.

The `reply_count` field (added in V8) MUST be a JSON integer ≥ 0 and MUST be present on EVERY post in the response (never omitted, never null). It MUST equal the count of `post_replies` rows for the post where the reply's author is shadow-ban-visible (`JOIN visible_users`) AND the reply is not soft-deleted (`deleted_at IS NULL`). Viewer-block exclusion is DELIBERATELY NOT applied to this counter (privacy tradeoff; per-viewer count would leak block state). The value is derived from the `LEFT JOIN LATERAL` sub-scalar in the canonical query.

The `authorUsername` and `authorDisplayName` fields (added by `mobile-timeline-card-redesign`) MUST be JSON strings present on EVERY post in the response (never omitted, never null — `users.username` and `users.display_name` are NOT NULL since V2). Their wire names are declared EXPLICITLY as bare camelCase `authorUsername` / `authorDisplayName` (no `@SerialName`), following the shipped identity-field precedent (`authorUserId` in the timeline DTOs; `username`/`displayName` in `UserProfileRoutes.kt`) — NOT snake_case. They MUST equal the post author's `users.username` / `users.display_name` values as projected by the canonical query's per-arm author-identity join (`visible_users` on the visible arm; raw `users` on the self arm, whose rows are always the authenticated caller's own — amended by `shadow-ban-feed-self-visibility`: self-arm rows of a shadow-banned author have no `visible_users` row, so the previous "MUST equal the `visible_users` values" wording is per-arm now).

#### Scenario: Coordinates from display_location
- **WHEN** a post in the response has database `display_location = POINT(106.8 -6.2)`
- **THEN** the response item has `latitude = -6.2` AND `longitude = 106.8`

#### Scenario: actual_location not exposed
- **WHEN** searching the response JSON for `actual_location` or any value matching the post's actual coordinates
- **THEN** no match is found

#### Scenario: Self-arm rows expose only the fuzzed location to their own author
- **WHEN** shadow-banned caller A's own post appears in A's Nearby response
- **THEN** its `latitude`/`longitude` derive from `display_location` exactly like every other row (no `actual_location` leak on the own-content path), and `distanceM` (when present) likewise derives from `display_location`

#### Scenario: distanceM is raw meters when present
- **WHEN** the response contains a post whose distance is NOT hidden AND for which `ST_Distance(display_location, viewer_loc)` is approximately 1234.5 meters
- **THEN** the response field `distanceM` is approximately 1234.5 (NOT a formatted "1km" string)

#### Scenario: distanceM is omitted when the hide-distance rule applies
- **WHEN** a post's distance is suppressed for the requesting viewer (the author's hide-distance preference is effective, OR the viewer's preference is effective)
- **THEN** that post's response object contains NO `distanceM` key (neither a number nor `null`) AND still contains its `city_name`, `liked_by_viewer`, and `reply_count` fields unchanged

#### Scenario: liked_by_viewer true when caller has liked the post
- **WHEN** a post P is in the response AND a `post_likes` row `(P, caller)` exists
- **THEN** the response item for P has `liked_by_viewer = true`

#### Scenario: liked_by_viewer false when caller has not liked the post
- **WHEN** a post P is in the response AND no `post_likes` row `(P, caller)` exists
- **THEN** the response item for P has `liked_by_viewer = false`

#### Scenario: liked_by_viewer present on every post
- **WHEN** the response contains any number of posts (including zero, one, or many)
- **THEN** every post object in `response.posts` contains the key `liked_by_viewer` with a JSON Boolean value (never omitted, never `null`)

#### Scenario: reply_count is a non-negative JSON integer
- **WHEN** any post P is in the response
- **THEN** `P.reply_count` is a JSON number with no fractional component AND `P.reply_count >= 0`

#### Scenario: reply_count present on every post
- **WHEN** the response contains any number of posts (including zero, one, or many)
- **THEN** every post object in `response.posts` contains the key `reply_count` with a JSON integer value (never omitted, never `null`)

#### Scenario: reply_count = 0 for post with no replies
- **WHEN** a post P has zero `post_replies` rows
- **THEN** the response item for P has `reply_count = 0` (NOT omitted, NOT `null`)

#### Scenario: authorUsername and authorDisplayName present on every post with exact camelCase keys
- **WHEN** the response contains any number of posts
- **THEN** every post object contains the keys `authorUsername` and `authorDisplayName` with non-null JSON string values AND contains NO `author_username` / `author_display_name` snake_case variants

#### Scenario: Author identity values match the author's row
- **WHEN** a post P in the response was authored by a user with `username = "raka.jkt"`, `display_name = "Raka Pratama"`
- **THEN** the response item for P has `authorUsername = "raka.jkt"` AND `authorDisplayName = "Raka Pratama"`

#### Scenario: Identity fields populated on a shadow-banned author's own rows
- **WHEN** shadow-banned caller A's own post appears in A's Nearby response
- **THEN** `authorUsername` / `authorDisplayName` carry A's `users` row values (non-null strings — sourced via the self arm's raw `users` join, since A has no `visible_users` row)

### Requirement: Existing Nearby response fields unchanged

V11 MUST NOT remove, rename, or change the type of any existing Nearby response field (`id`, `author_user_id`, `content`, `latitude`, `longitude`, `distance_m`, `created_at`, `liked_by_viewer`, `reply_count`). The addition of `city_name` is the only response-shape change from V10 to V11 on the Nearby endpoint. As of the `hide-distance` capability, the `distanceM` field is no longer unconditionally present: it is RAW METERS when shown but OMITTED when the symmetric hide-distance rule applies (per the "Response shape" requirement). This is a deliberate, separately-specified change to the *presence* of `distanceM` ONLY — its type-when-present (raw-meters double), name, and the presence/type of every other field remain unchanged.

#### Scenario: distance_m present and raw meters when not hidden
- **WHEN** a post in a Nearby response has `ST_Distance(display_location, viewer_loc)` ≈ 1234.5 meters AND the hide-distance rule does NOT apply to it
- **THEN** `response.posts[i].distanceM ≈ 1234.5` (unchanged from V8; raw meters)

#### Scenario: distance_m omitted only via the hide-distance rule
- **WHEN** the hide-distance rule applies to a post for the requesting viewer
- **THEN** `distanceM` is absent on that post object (the only sanctioned reason for omission) AND every other field retains its V11 presence and type

#### Scenario: liked_by_viewer and reply_count still present
- **WHEN** the Nearby response contains any post
- **THEN** the post object contains `liked_by_viewer` (Boolean) AND `reply_count` (integer), both never omitted and never null (unchanged from V7/V8)

### Requirement: Nearby route delegates read-rate-limit accounting to `timeline-read-rate-limit`

The `GET /api/v1/timeline/nearby` route handler SHALL delegate read-side rate-limit accounting (rolling 150-posts/hour hard cap + 50-posts/session soft cap, Free-tier only, Premium exempt) to the `timeline-read-rate-limit` capability per its full contract.

The route handler MUST:

- Run the rolling pre-check + session pre-check BEFORE the canonical Nearby SQL query (per `timeline-read-rate-limit` § "Limiter ordering and pre-execution before DB"). Pre-check key shapes are `{scope:rate_timeline_rolling}:{user:<user_id>}` and `{scope:rate_timeline_session}:{session:<user_id>__<sanitized_session_id>}`.
- On rolling-cap `RateLimited`: return HTTP 200 with `{ "posts": [], "nextCursor": null, "upsell": { "hard": true } }`. Do NOT execute the canonical Nearby SQL query (which is especially expensive for Nearby due to the PostGIS `ST_DWithin` + `ST_Distance` cost on `display_location`). The existing Nearby query, block-exclusion, and `liked_by_viewer` / `reply_count` / `city_name` projection requirements remain unchanged for the non-cap-hit path.
- On a successful response (rolling pre-check admitted, query executed, returning `N` posts where `0 ≤ N ≤ 30`): bump both buckets via `(N - 1).coerceAtLeast(0)` additional best-effort `tryAcquire` calls (1 already consumed at pre-check). Build the response per the existing Nearby response shape PLUS the optional `upsell` object per the `timeline-read-rate-limit` contract.
- Validate the `X-Session-Id` header per `timeline-read-rate-limit` § "X-Session-Id header validation"; substitute with `no-session` on missing or malformed values.
- For Premium callers (`subscription_status IN ('premium_active', 'premium_billing_retry')`): SKIP both pre-checks and post-increment entirely. Run the canonical Nearby query and respond per the existing shape; never include the `upsell` field.

The existing Nearby requirements ("Canonical query joins visible_posts and excludes blocks bidirectionally", "Keyset pagination on (created_at DESC, id DESC)", "Per-page cap of 30", "Response shape", "Response projects city_name on every post as of V11", and the V11-extended Integration test coverage requirement) remain unchanged. The rate-limit gate is a NEW pre-DB short-circuit; it does NOT alter the SQL query, the cursor format, or any of the V5–V11 invariants. The response post shape is unchanged by the rate-limit gate itself; the only per-post-field change anywhere on this endpoint is the conditional omission of `distanceM` under the `hide-distance` rule (per the "Response shape" requirement) — every other per-post field is unchanged.

#### Scenario: Free Nearby read at rolling cap returns empty + upsell.hard
- **WHEN** Free-tier caller A's rolling bucket holds 150 entries AND A issues `GET /api/v1/timeline/nearby?lat=-6.2&lng=106.8&radius_m=1000`
- **THEN** the response is HTTP 200 with body `{ "posts": [], "nextCursor": null, "upsell": { "hard": true } }` AND zero `posts` SELECTs were issued to Postgres for the request AND no PostGIS `ST_DWithin` execution

#### Scenario: Free Nearby read at session-soft-cap still returns posts
- **WHEN** Free-tier caller A's session bucket (under `X-Session-Id: SID`) is at 50/50 capacity AND the rolling bucket holds 80/150 entries AND A issues a Nearby read
- **THEN** the response is HTTP 200 with `posts.length > 0` (DB-permitting) AND `upsell.soft = true` AND the canonical Nearby SQL DID execute

#### Scenario: Premium Nearby read bypasses rate limit
- **WHEN** Premium caller A (`subscription_status = 'premium_active'`) issues a Nearby read after having read 500 posts in the last hour
- **THEN** the response is HTTP 200 with the Nearby content AND no `upsell` field AND zero rate-limit Redis calls were issued for this request (verified via Redis-counter spy)

#### Scenario: Nearby below caps — response shape unchanged
- **WHEN** Free caller A is below both caps AND issues a Nearby read returning 5 posts
- **THEN** the response body matches the existing Nearby response shape exactly (the `upsell` key is NOT present) AND all V5–V11 per-post fields are present (id, author_user_id, content, latitude, longitude, created_at, liked_by_viewer, reply_count, city_name) AND `distanceM` is present on each post UNLESS that post is subject to the `hide-distance` rule (per the "Response shape" requirement — the only sanctioned omission)

#### Scenario: Nearby empty radius result still consumes 1 rolling slot
- **WHEN** Free-tier caller A is at slot 0/150 rolling AND issues a Nearby read where the spatial filter returns zero posts (e.g., a remote ocean coordinate where no posts exist)
- **THEN** the response is HTTP 200 with `posts = []` AND `next_cursor = null` AND no `upsell` field (below caps) AND the rolling bucket holds exactly 1 entry after the response (the pre-check consumed 1 slot regardless of N=0; the post-increment is `(0-1).coerceAtLeast(0) = 0` — skipped)

#### Scenario: Nearby PostGIS query NOT executed on cap-hit
- **WHEN** Free-tier caller A's rolling bucket is at 150/150 AND A issues a Nearby read with valid `lat`/`lng`/`radius_m`
- **THEN** the response is HTTP 200 with `posts = []` + `upsell.hard = true` AND zero PostGIS function invocations are issued (no `ST_DWithin`, no `ST_Distance`)

#### Scenario: Nearby per-page cap and post-increment math
- **WHEN** Free-tier caller A is at slot 0/150 rolling AND issues a Nearby read returning the page-cap of 30 posts
- **THEN** the rolling bucket holds exactly 30 entries after the response (1 from pre-check + `(30-1) = 29` best-effort additional `tryAcquire` calls all admitted) AND the response body matches the existing Nearby response shape with 30 posts AND no `upsell` field
