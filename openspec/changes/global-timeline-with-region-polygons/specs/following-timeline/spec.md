## ADDED Requirements

### Requirement: Response projects city_name on every post as of V11

As of V11, the Following timeline response item SHALL include a `city_name` string field on every post, populated from the `posts.city_name` column that V11 adds. The field is additive — no existing field is removed or renamed. The field MUST be present on every post (never omitted); if the underlying DB value is NULL (legacy pre-trigger post or polygon-coverage gap), the field MUST serialize as the empty string `""` (never JSON `null`).

The Following canonical SQL (see existing `following-timeline` requirement "Canonical query joins visible_posts, follows, and excludes blocks bidirectionally") is extended to project `p.city_name` into the result set. No WHERE clause change, no ORDER BY change, no JOIN addition — the column is already visible through `visible_posts` as of V11.

#### Scenario: city_name key present on every Following post
- **WHEN** the Following response contains any number of posts (including zero, one, or many)
- **THEN** every post object in `response.posts` contains the key `city_name` with a JSON string value (never omitted, never `null`)

#### Scenario: city_name reflects trigger-populated value on Following
- **WHEN** a followed user posted after V11 with `actual_location` inside the "Bandung" polygon AND the post appears in a Following response
- **THEN** the response item has `city_name = "Bandung"`

#### Scenario: city_name empty string for legacy Following post
- **WHEN** a pre-V11 post by a followed user whose `posts.city_name` column is NULL appears in a Following response
- **THEN** the response item has `city_name = ""`

### Requirement: Existing Following response fields unchanged

V11 MUST NOT remove, rename, or change the type of any existing Following response field (`id`, `author_user_id`, `content`, `latitude`, `longitude`, `created_at`, `liked_by_viewer`, `reply_count`). Following does NOT include `distance_m` (correct — Following is chronological, not geographic). The addition of `city_name` is the only response-shape change from V10 to V11 on the Following endpoint.

#### Scenario: distance_m still absent
- **WHEN** the Following response contains any post
- **THEN** no post object contains a `distance_m` key (unchanged from V6)

#### Scenario: liked_by_viewer and reply_count still present
- **WHEN** the Following response contains any post
- **THEN** the post object contains `liked_by_viewer` (Boolean) AND `reply_count` (integer), both never omitted and never null (unchanged from V7/V8)

### Requirement: Integration test coverage extended for city_name

`FollowingTimelineServiceTest` SHALL add at minimum these scenarios:
1. `city_name` key present on every post in every response (assert key presence + type `string`).
2. `city_name` reflects trigger-populated value when a followed user's post was created after V11 inside a seeded kabupaten/kota polygon.
3. `city_name = ""` when the underlying `posts.city_name` is NULL (legacy pre-V11 row OR polygon gap).

The existing Following scenarios (V6/V7/V8) remain in force unchanged.

#### Scenario: Following test class covers city_name
- **WHEN** running `./gradlew :backend:ktor:test --tests '*FollowingTimelineServiceTest*'`
- **THEN** at least one `@Test` covers each of the three new `city_name` scenarios AND all pre-existing scenarios continue to pass
