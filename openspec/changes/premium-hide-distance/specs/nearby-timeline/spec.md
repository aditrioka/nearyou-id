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
