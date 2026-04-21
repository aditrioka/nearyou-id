# nearby-timeline Specification

## Purpose

Defines the HTTP contract for `GET /api/v1/timeline/nearby` — the first read endpoint that surfaces visible, geographically-relevant posts to authenticated viewers. Specifies authentication, required query parameters, coordinate envelope and radius validation, the canonical SQL query (joining `visible_posts` with bidirectional `user_blocks` exclusion), keyset cursor format, server-side distance computation, per-page cap, response shape, and integration test coverage.

See `docs/05-Implementation.md § Timeline Implementation` for the canonical Nearby query and `docs/08-Roadmap-Risk.md` Phase 1 item 30 for cap rationale.

## Requirements

### Requirement: GET /api/v1/timeline/nearby endpoint exists

A Ktor route SHALL be registered at `GET /api/v1/timeline/nearby`. The route MUST require Bearer JWT authentication via the existing `auth-jwt` plugin; an unauthenticated request MUST receive HTTP 401 with error code `unauthenticated`. The route handler MUST live under `backend/ktor/src/main/kotlin/id/nearyou/app/timeline/`.

#### Scenario: Unauthenticated rejected
- **WHEN** `GET /api/v1/timeline/nearby?lat=-6.2&lng=106.8&radius_m=1000` is called with no `Authorization` header
- **THEN** the response is HTTP 401 with `error.code = "unauthenticated"`

#### Scenario: Authenticated routed to handler
- **WHEN** the same request is made with a valid Bearer JWT
- **THEN** the request reaches the handler (HTTP status is not 401)

### Requirement: Required query parameters

The endpoint SHALL require `lat`, `lng`, and `radius_m` as query parameters. Missing or non-numeric values MUST yield HTTP 400 with error code `invalid_request`. The `cursor` parameter is optional; if absent, the endpoint returns the first page.

#### Scenario: Missing lat rejected
- **WHEN** the request omits `lat`
- **THEN** the response is HTTP 400 with `error.code = "invalid_request"`

#### Scenario: Non-numeric radius rejected
- **WHEN** `radius_m=abc`
- **THEN** the response is HTTP 400 with `error.code = "invalid_request"`

### Requirement: Coordinate envelope check (reuses post-creation guard)

The endpoint SHALL reject requests where `lat` is outside `[-11.0, 6.5]` or `lng` is outside `[94.0, 142.0]` with HTTP 400 code `location_out_of_bounds`. This MUST use the same envelope and error code as `post-creation`'s envelope check.

#### Scenario: Out-of-envelope rejected
- **WHEN** the request has `lat=10.0, lng=120.0`
- **THEN** the response is HTTP 400 with `error.code = "location_out_of_bounds"` AND no DB query executes

### Requirement: Radius bounds

`radius_m` SHALL be validated to the inclusive integer range `[100, 50000]`. Out-of-range values MUST yield HTTP 400 with error code `radius_out_of_bounds`.

#### Scenario: Radius too small
- **WHEN** `radius_m=50`
- **THEN** the response is HTTP 400 with `error.code = "radius_out_of_bounds"`

#### Scenario: Radius too large
- **WHEN** `radius_m=100000`
- **THEN** the response is HTTP 400 with `error.code = "radius_out_of_bounds"`

#### Scenario: Boundary radius accepted
- **WHEN** `radius_m=100` or `radius_m=50000`
- **THEN** the request is not rejected for radius bounds

### Requirement: Canonical query joins visible_posts and excludes blocks bidirectionally

The endpoint's data query SHALL be the canonical Nearby query from `docs/05-Implementation.md` § Timeline Implementation: `FROM visible_posts` (NOT `FROM posts`), with `ST_DWithin(display_location, ST_MakePoint(:lng, :lat)::geography, :radius_m)`, AND two NOT-IN subqueries excluding `user_blocks` rows in BOTH directions:
- `author_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = :viewer)` (viewer-blocked authors hidden)
- `author_id NOT IN (SELECT blocker_id FROM user_blocks WHERE blocked_id = :viewer)` (authors-who-blocked-viewer hidden)

#### Scenario: Auto-hidden post excluded
- **WHEN** a post within radius has `is_auto_hidden = TRUE`
- **THEN** that post does NOT appear in the response

#### Scenario: Viewer-blocked author excluded
- **WHEN** the calling user has a `user_blocks` row `(blocker_id = caller, blocked_id = X)` AND X has a post within radius
- **THEN** X's post does NOT appear in the response

#### Scenario: Viewer-blocked-by-author excluded
- **WHEN** there is a `user_blocks` row `(blocker_id = X, blocked_id = caller)` AND X has a post within radius
- **THEN** X's post does NOT appear in the response

### Requirement: Keyset pagination on (created_at DESC, id DESC)

The endpoint SHALL paginate via keyset on `(created_at DESC, id DESC)` using the `posts_timeline_cursor_idx` index. The cursor parameter is a base64url-encoded JSON object `{"c":"<created_at ISO-8601>","i":"<post UUID>"}`. The endpoint MUST NOT use SQL `OFFSET`. A malformed cursor MUST yield HTTP 400 with error code `invalid_cursor`.

#### Scenario: First page no cursor
- **WHEN** the request has no `cursor`
- **THEN** the SQL query has no `(created_at, id) <` clause AND returns the most recent posts

#### Scenario: Subsequent page with cursor
- **WHEN** the response on page 1 contains `next_cursor = "<token>"` AND the next request supplies `cursor=<token>`
- **THEN** the SQL query includes `(created_at, id) < (cursor.c, cursor.i)` AND no row from page 1 appears in page 2

#### Scenario: Malformed cursor rejected
- **WHEN** `cursor=not-a-base64-json`
- **THEN** the response is HTTP 400 with `error.code = "invalid_cursor"`

### Requirement: Per-page cap of 30

The endpoint SHALL `LIMIT` the SQL query to `31` (page-size 30 plus one probe row to detect a next page). The response `posts` array MUST contain at most 30 elements. The probe row, if present, MUST NOT appear in the response and MUST seed `next_cursor`.

#### Scenario: At most 30 posts in response
- **WHEN** there are 100 posts within radius for a given viewer
- **THEN** `response.posts.length <= 30`

#### Scenario: next_cursor present when more exist
- **WHEN** there are >30 matching posts AND the response contains 30 posts
- **THEN** `response.next_cursor` is a non-null base64url string

#### Scenario: next_cursor null on last page
- **WHEN** the response contains <30 posts (or exactly 30 with no further matches)
- **THEN** `response.next_cursor` is `null`

### Requirement: Response shape

A successful response SHALL be HTTP 200 with body:

```json
{
  "posts": [
    {
      "id": "<uuid>",
      "author_user_id": "<uuid>",
      "content": "<string>",
      "latitude": <double>,
      "longitude": <double>,
      "distance_m": <double>,
      "created_at": "<ISO-8601 UTC>"
    }
  ],
  "next_cursor": "<string or null>"
}
```

The `latitude`/`longitude` fields MUST be derived from `display_location` (NOT `actual_location`). The `distance_m` field MUST be the value computed by `ST_Distance(display_location, ST_MakePoint(:lng, :lat)::geography)` in the SQL query — server-computed, returned in raw meters.

#### Scenario: Coordinates from display_location
- **WHEN** a post in the response has database `display_location = POINT(106.8 -6.2)`
- **THEN** the response item has `latitude = -6.2` AND `longitude = 106.8`

#### Scenario: actual_location not exposed
- **WHEN** searching the response JSON for `actual_location` or any value matching the post's actual coordinates
- **THEN** no match is found

#### Scenario: distance_m is raw meters
- **WHEN** the response contains a post for which `ST_Distance(display_location, viewer_loc)` is approximately 1234.5 meters
- **THEN** the response field `distance_m` is approximately 1234.5 (NOT a formatted "1km" string)

### Requirement: Integration test coverage

`NearbyTimelineServiceTest` (tagged `database`) SHALL cover, at minimum, these scenarios end-to-end against a Postgres+PostGIS test DB:
1. Happy path: viewer at Jakarta, three posts within radius, ordered by `created_at DESC`.
2. Cursor pagination: 35 posts, page 1 returns 30 + cursor, page 2 returns 5, no overlap.
3. Radius filter: a post outside `radius_m` is excluded.
4. Auto-hidden exclusion: a post with `is_auto_hidden = TRUE` is excluded.
5. Bidirectional block exclusion (two sub-cases):
   - A blocked B (viewer = A): B's posts hidden.
   - B blocked A (viewer = A): B's posts hidden.
6. Out-of-envelope coordinates: HTTP 400 `location_out_of_bounds`.
7. Auth required: HTTP 401 without JWT.

#### Scenario: Test class exists
- **WHEN** running `./gradlew :backend:ktor:test --tests '*NearbyTimelineServiceTest*'`
- **THEN** the class is discovered AND every scenario above corresponds to at least one `@Test` method
