## ADDED Requirements

### Requirement: Post INSERT populates city_name and city_admin_region_id via trigger

As of V11, the `posts` INSERT path SHALL populate `city_name` and `city_admin_region_id` via the `posts_set_city_tg` BEFORE INSERT trigger (see `region-polygons` capability). Application code on the post-creation path MUST NOT supply `city_name` or `city_admin_region_id` in its INSERT column list — those columns are reserved for the trigger's subquery. The caller's responsibility is unchanged: supply `content`, `latitude`, `longitude`, and the derived `display_location` + `actual_location`.

The `POST /api/v1/posts` handler MAY remain unaware of the trigger; it SHALL NOT issue an additional `SELECT` on `admin_regions` or perform any reverse-geocoding in application code. The trigger is the single source of truth for the post-creation-time reverse-geocode.

#### Scenario: Application code does not reference admin_regions on create path
- **WHEN** grepping `backend/ktor/src/main/kotlin/id/nearyou/app/post/` for `admin_regions`
- **THEN** no match is found (the trigger is DB-side; the application has no reason to read `admin_regions` during post creation)

#### Scenario: INSERT column list does not include city_* columns
- **WHEN** inspecting the SQL issued by `JdbcPostsRepository.insert` (or the equivalent write-path repository)
- **THEN** the INSERT column list does NOT contain `city_name` or `city_admin_region_id` (the trigger populates both)

#### Scenario: Created post in response reflects trigger-populated city_name
- **WHEN** a post is created via `POST /api/v1/posts` with `latitude`/`longitude` inside the Jakarta Selatan polygon
- **THEN** any subsequent read of that post (via the Global timeline or future post-detail endpoint) returns `city_name = "Jakarta Selatan"`

### Requirement: Post creation response may project city_name

The `POST /api/v1/posts` response body MAY include a `city_name` field reflecting the trigger-populated value on the newly-created row. This is optional for this capability — clients MUST NOT depend on its presence in the `POST` response; the authoritative source for `city_name` is any subsequent read of the post. If the field is present, it MUST be a JSON string following the same NULL-handling convention as the Global timeline response (underlying NULL serializes as `""`).

#### Scenario: city_name in POST response consistent with later read
- **WHEN** the `POST /api/v1/posts` response body includes `city_name` AND the same post is later fetched via the Global timeline
- **THEN** the two `city_name` values are identical

### Requirement: Coordinate-envelope validation unchanged by V11

V11 MUST NOT alter the existing coordinate-envelope validation on the post-creation path. The Indonesia envelope `[-11.0, 6.5]` lat × `[94.0, 142.0]` lng and the `400 location_out_of_bounds` error code remain unchanged. The trigger's polygon-containment check is SEPARATE from (and does NOT supersede) the envelope check: the envelope is application-layer cheap rejection; the trigger's polygon lookup is DB-side and produces NULL (not a reject) when the point is in a polygon gap.

#### Scenario: Envelope rejection still happens pre-INSERT
- **WHEN** a post creation request has `latitude=10.0, longitude=120.0` (outside the envelope)
- **THEN** the response is HTTP 400 with `error.code = "location_out_of_bounds"` AND the INSERT does NOT execute AND the trigger does NOT fire

#### Scenario: Polygon-gap point still accepted
- **WHEN** a post creation request has coordinates inside the envelope but outside every kabupaten/kota polygon (e.g., a coastal coordinate that falls in a polygon gap)
- **THEN** the response is HTTP 201 AND the row's `city_name` + `city_admin_region_id` are both NULL
