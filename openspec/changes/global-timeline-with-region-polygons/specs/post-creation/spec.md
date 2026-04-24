## ADDED Requirements

### Requirement: Post INSERT populates city_name and city_match_type via the 4-step fallback trigger

As of V11, the `posts` INSERT path SHALL populate `city_name` and `city_match_type` via the `posts_set_city_tg` BEFORE INSERT trigger (see `region-polygons` capability), which runs the 4-step fallback ladder from [`docs/02-Product.md:192–196`](docs/02-Product.md): strict `ST_Contains` → 10 m buffered match with centroid tie-breaker → 50 km nearest-neighbor `fuzzy_match` → NULL.

Application code on the post-creation path MUST NOT supply `city_name` or `city_match_type` in its INSERT column list — those columns are reserved for the trigger. The caller's responsibility is unchanged: supply `content`, `latitude`, `longitude`, and the derived `display_location` + `actual_location`.

The `POST /api/v1/posts` handler MAY remain unaware of the trigger; it SHALL NOT issue an additional `SELECT` on `admin_regions` or perform any reverse-geocoding in application code. The trigger is the single source of truth for the post-creation-time reverse-geocode.

#### Scenario: Application code does not reference admin_regions on create path
- **WHEN** grepping `backend/ktor/src/main/kotlin/id/nearyou/app/post/` for `admin_regions`
- **THEN** no match is found (the trigger is DB-side; the application has no reason to read `admin_regions` during post creation)

#### Scenario: INSERT column list does not include city_* columns
- **WHEN** inspecting the SQL issued by `JdbcPostsRepository.insert` (or the equivalent write-path repository)
- **THEN** the INSERT column list does NOT contain `city_name` or `city_match_type` (the trigger populates both)

#### Scenario: Step 1 strict match populates city_name on create
- **WHEN** a post is created via `POST /api/v1/posts` with `latitude`/`longitude` well inside the Jakarta Selatan polygon
- **THEN** any subsequent read of that post (via the Global timeline or future post-detail endpoint) returns `city_name = "Jakarta Selatan"` AND the DB row carries `city_match_type = 'strict'`

#### Scenario: Step 2 buffered_10m match on polygon boundary
- **WHEN** a post is created with coordinates ~5 m outside a kabupaten's polygon edge (floating-point boundary artifact) AND that kabupaten is the nearest centroid within 10 m
- **THEN** the DB row carries that kabupaten's name in `city_name` AND `city_match_type = 'buffered_10m'`

#### Scenario: Step 3 fuzzy_match in coastal/polygon-gap point
- **WHEN** a post is created with coordinates in a coastal or polygon-gap area beyond the 12nm maritime buffer but within 50 km of a kabupaten polygon
- **THEN** the DB row carries the nearest kabupaten's name in `city_name` AND `city_match_type = 'fuzzy_match'`

#### Scenario: Step 4 open-ocean post stays NULL
- **WHEN** a post is created with coordinates more than 50 km from any kabupaten polygon (e.g., deep-sea EEZ)
- **THEN** the INSERT still succeeds (HTTP 201) AND the row has `city_name IS NULL` AND `city_match_type IS NULL`

#### Scenario: Bulk-import caller override
- **WHEN** an admin bulk-import path issues an INSERT explicitly supplying `city_name = 'Bali'`
- **THEN** the trigger's caller-override guard short-circuits the ladder AND the inserted row retains `city_name = 'Bali'` without any of the 4 steps running

### Requirement: Post creation response may project city_name

The `POST /api/v1/posts` response body MAY include a `city_name` field reflecting the trigger-populated value on the newly-created row. This is optional for this capability — clients MUST NOT depend on its presence in the `POST` response; the authoritative source for `city_name` is any subsequent read of the post. If the field is present, it MUST be a JSON string following the same NULL-handling convention as the Global timeline response (underlying NULL serializes as `""`).

The `city_match_type` column is admin/internal only and MUST NOT appear in any client-facing response (POST, GET timeline, GET post-detail). It is consumed by admin tooling and analytics only.

#### Scenario: city_name in POST response consistent with later read
- **WHEN** the `POST /api/v1/posts` response body includes `city_name` AND the same post is later fetched via the Global timeline
- **THEN** the two `city_name` values are identical

#### Scenario: city_match_type never appears in client responses
- **WHEN** inspecting any client-facing response DTO (post creation, Nearby, Following, Global, post-detail)
- **THEN** no field named `city_match_type` (or equivalent camelCase `cityMatchType`) is present

### Requirement: Coordinate-envelope validation unchanged by V11

V11 MUST NOT alter the existing coordinate-envelope validation on the post-creation path. The Indonesia envelope `[-11.0, 6.5]` lat × `[94.0, 142.0]` lng and the `400 location_out_of_bounds` error code remain unchanged. The trigger's fallback ladder is SEPARATE from (and does NOT supersede) the envelope check: the envelope is application-layer cheap rejection; the trigger's polygon lookup is DB-side and produces NULL (not a reject) when the point falls past step 3.

#### Scenario: Envelope rejection still happens pre-INSERT
- **WHEN** a post creation request has `latitude=10.0, longitude=120.0` (outside the envelope)
- **THEN** the response is HTTP 400 with `error.code = "location_out_of_bounds"` AND the INSERT does NOT execute AND the trigger does NOT fire

#### Scenario: Polygon-gap point still accepted
- **WHEN** a post creation request has coordinates inside the envelope but outside every kabupaten/kota polygon after all 4 fallback steps (e.g., far open-ocean point)
- **THEN** the response is HTTP 201 AND the row's `city_name` + `city_match_type` are both NULL
