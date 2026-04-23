## ADDED Requirements

### Requirement: posts_set_city_tg trigger is a sanctioned DB-side reader of actual_location

The `posts_set_city_tg` BEFORE INSERT trigger (see `region-polygons` capability) reads `NEW.actual_location` to perform a polygon-containment reverse-geocode against `admin_regions`. This SHALL be recognized as a sanctioned use of `actual_location` alongside the pre-existing admin-module path, and the sanction MUST be scoped by the following invariants:

1. **DB-side only** — the read happens in PL/pgSQL inside the trigger function. No application code gains access to `actual_location` through this path.
2. **Write-path only** — the trigger fires on INSERT, not on SELECT. Read paths still MUST NOT touch `actual_location` in any non-admin code.
3. **No client-facing projection** — the trigger writes `city_name` (a string) and `city_admin_region_id` (an integer). Neither value reveals coordinates. No `actual_location`-derived latitude, longitude, or geometry representation is stored outside `posts.actual_location` itself.

Read paths that surface posts to clients (Nearby, Following, Global timelines, post-detail endpoints) MUST continue to derive displayed `latitude`/`longitude` from `display_location` (the fuzzed coordinate). The existence of the trigger MUST NOT be interpreted as a broadening of the read-path rule.

#### Scenario: Trigger body uses NEW.actual_location
- **WHEN** reading the function definition of `posts_set_city_fn` via `pg_get_functiondef`
- **THEN** the body contains `NEW.actual_location` AND does NOT contain `NEW.display_location`

#### Scenario: No application-side read of actual_location on the create path
- **WHEN** grepping `backend/ktor/src/main/kotlin/id/nearyou/app/post/` for `actual_location`
- **THEN** occurrences are confined to the write path (INSERT column list, repository SQL) and do NOT include any SELECT that projects `actual_location` into application memory on the create path

### Requirement: Jitter-rule allowlist extended for V11 migration file

The Detekt / CI rule enforcing "non-admin paths must use `fuzzed_location` / `display_location`, never `actual_location`" (per `docs/08-Roadmap-Risk.md` § Coding Conventions Spatial rule) SHALL add `backend/ktor/src/main/resources/db/migration/V11__admin_regions_and_post_city.sql` to its allowlist. The allowlist entry is narrowly scoped to that one file; future migrations that touch `actual_location` outside the admin path require explicit per-file additions.

The rule's existing allowed patterns (admin module, V4 migration where `actual_location` is defined, the coordinate-jitter implementation) remain unchanged. Rule behavior (token-matching logic, error messaging) is unchanged.

#### Scenario: V11 migration allowed despite containing actual_location
- **WHEN** `V11__admin_regions_and_post_city.sql` contains `ST_Contains(geom::geometry, NEW.actual_location::geometry)`
- **THEN** `./gradlew detekt` passes (the file is on the allowlist)

#### Scenario: Unrelated new migration NOT auto-allowed
- **WHEN** a hypothetical `V12__some_other_thing.sql` contains `FROM posts WHERE ST_DWithin(actual_location, ...)`
- **THEN** `./gradlew detekt` fails AND the output identifies the jitter / spatial lint rule as the source (the allowlist requires an explicit per-file entry)

### Requirement: Reverse-geocode uses actual_location per product spec

The polygon-containment subquery executed by `posts_set_city_tg` MUST use `actual_location`, not `display_location`. This matches `docs/02-Product.md` § "Polygon-Based Reverse Geocoding" which states: *"Queries use `actual_location` (not `display_location`, since accuracy matters for administrative boundaries)."* The rationale is that jitter of up to 500 m routinely crosses kotamadya-scale boundaries in Jakarta; using `display_location` would mis-label ~15–20% of Jakarta posts.

The same rule applies transitively to the optional backfill job (see `region-polygons` capability): if the backfill is implemented, it MUST read `actual_location`, not `display_location`.

#### Scenario: Trigger uses actual_location
- **WHEN** reading the function body of `posts_set_city_fn`
- **THEN** the polygon-containment call is `ST_Contains(geom::geometry, NEW.actual_location::geometry)` (NOT `display_location`)

#### Scenario: Backfill uses actual_location (if implemented)
- **WHEN** the optional `BackfillPostCityJob` SQL or any equivalent SQL migration is authored
- **THEN** it references `p.actual_location` (NOT `p.display_location`) as the point argument to `ST_Contains`
