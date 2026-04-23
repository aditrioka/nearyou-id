## ADDED Requirements

### Requirement: Response projects city_name on every post as of V11

As of V11, the Nearby timeline response item SHALL include a `city_name` string field on every post, populated from the `posts.city_name` column that V11 adds. The field is additive — no existing field is removed or renamed. The field MUST be present on every post (never omitted); if the underlying DB value is NULL (legacy pre-trigger post or polygon-coverage gap), the field MUST serialize as the empty string `""` (never JSON `null`).

The Nearby canonical SQL (see existing `nearby-timeline` requirement "Canonical query joins visible_posts and excludes blocks bidirectionally") is extended to project `p.city_name` into the result set. No WHERE clause change, no ORDER BY change, no JOIN addition — the column is already visible through `visible_posts` as of V11.

#### Scenario: city_name key present on every Nearby post
- **WHEN** the Nearby response contains any number of posts (including zero, one, or many)
- **THEN** every post object in `response.posts` contains the key `city_name` with a JSON string value (never omitted, never `null`)

#### Scenario: city_name reflects trigger-populated value on Nearby
- **WHEN** a post was created after V11 with `actual_location` inside the "Surabaya" polygon AND the post appears in a Nearby response
- **THEN** the response item has `city_name = "Surabaya"`

#### Scenario: city_name empty string for legacy Nearby post
- **WHEN** a pre-V11 post whose `posts.city_name` column is NULL appears in a Nearby response
- **THEN** the response item has `city_name = ""`

### Requirement: Existing Nearby response fields unchanged

V11 MUST NOT remove, rename, or change the type of any existing Nearby response field (`id`, `author_user_id`, `content`, `latitude`, `longitude`, `distance_m`, `created_at`, `liked_by_viewer`, `reply_count`). The addition of `city_name` is the only response-shape change from V10 to V11 on the Nearby endpoint.

#### Scenario: distance_m still present and raw meters
- **WHEN** a post in a Nearby response has `ST_Distance(display_location, viewer_loc)` ≈ 1234.5 meters
- **THEN** `response.posts[i].distance_m ≈ 1234.5` (unchanged from V8)

#### Scenario: liked_by_viewer and reply_count still present
- **WHEN** the Nearby response contains any post
- **THEN** the post object contains `liked_by_viewer` (Boolean) AND `reply_count` (integer), both never omitted and never null (unchanged from V7/V8)

### Requirement: Integration test coverage extended for city_name

`NearbyTimelineServiceTest` SHALL add at minimum these scenarios:
1. `city_name` key present on every post in every response (assert key presence + type `string`).
2. `city_name` reflects trigger-populated value when the post's `actual_location` falls inside a seeded kabupaten/kota polygon.
3. `city_name = ""` when the underlying `posts.city_name` is NULL (legacy pre-V11 row OR polygon gap).

The existing 18 scenarios (V5–V8) remain in force unchanged.

#### Scenario: Nearby test class covers city_name
- **WHEN** running `./gradlew :backend:ktor:test --tests '*NearbyTimelineServiceTest*'`
- **THEN** at least one `@Test` covers each of the three new `city_name` scenarios AND all 18 pre-existing scenarios continue to pass
