## 1. Dataset acquisition

- [ ] 1.1 Decide dataset source: default BPS (CC-BY 4.0); fallback to OpenStreetMap `admin_level = 5` (ODbL) if the BPS GeoJSON is not obtainable in kabupaten/kota MULTIPOLYGON form. Record the decision and the final attribution string in `design.md` Open Question 1 and in the V11 header.
- [ ] 1.2 Acquire the source GeoJSON: either pull BPS kabupaten/kota boundaries or run an Overpass query (`[out:json]; relation[admin_level=5][boundary=administrative](area:3600304716); out geom;`) against OSM Indonesia.
- [ ] 1.3 Hand-curate DKI Jakarta's 5 kotamadya (Jakarta Pusat, Jakarta Utara, Jakarta Selatan, Jakarta Timur, Jakarta Barat) and Kepulauan Seribu at `level = 'kabupaten_kota'`, with `parent_id` pointing to the DKI Jakarta province row. Both default datasets conflate these at a single admin level — this fixup is required.
- [ ] 1.4 Convert the GeoJSON + fixup to SQL INSERT statements with `ST_GeomFromGeoJSON(...)` literals. Commit the generated SQL into `V11__admin_regions_and_post_city.sql`.
- [ ] 1.5 Run `ST_IsValid(geom)` on every seeded row in a local Postgres; fix any invalid multipolygons with `ST_MakeValid` before committing. Document any hand-fixups in a comment block alongside the INSERT.

## 2. V11 migration: schema + seed + trigger + view

- [ ] 2.1 Create `backend/ktor/src/main/resources/db/migration/V11__admin_regions_and_post_city.sql` with a header comment block covering: dataset source + license + attribution text, V11 consumer list (`nearby-timeline`, `following-timeline`, `global-timeline`), note that this is the first BEFORE INSERT trigger + first spatially-seeded reference table in the Flyway history.
- [ ] 2.2 In V11: `CREATE TABLE admin_regions (id INT PRIMARY KEY, name TEXT NOT NULL, level TEXT NOT NULL CHECK (level IN ('province', 'kabupaten_kota')), parent_id INT REFERENCES admin_regions(id), geom GEOGRAPHY(MULTIPOLYGON, 4326) NOT NULL, geom_centroid GEOGRAPHY(POINT, 4326) NOT NULL)` exactly per `docs/05-Implementation.md:1007–1017`.
- [ ] 2.3 In V11: create all four indexes — `admin_regions_geom_idx USING GIST(geom)`, `admin_regions_centroid_idx USING GIST(geom_centroid)`, `admin_regions_level_idx (level)`, `admin_regions_parent_idx (parent_id)`.
- [ ] 2.4 In V11: insert the province seed (~38 rows, `parent_id IS NULL`) followed by the kabupaten/kota seed (~500 rows, `parent_id` resolved to the matching province id). IDs must equal the dataset's stable code (BPS `kode_wilayah` or OSM relation ID) per design Decision 8.
- [ ] 2.5 In V11: `ALTER TABLE posts ADD COLUMN city_name TEXT NULL` and `ALTER TABLE posts ADD COLUMN city_admin_region_id INT NULL REFERENCES admin_regions(id) ON DELETE SET NULL`.
- [ ] 2.6 In V11: `CREATE OR REPLACE FUNCTION posts_set_city_fn() RETURNS TRIGGER AS $$ BEGIN IF NEW.city_admin_region_id IS NULL AND NEW.city_name IS NULL THEN SELECT id, name INTO NEW.city_admin_region_id, NEW.city_name FROM admin_regions WHERE level = 'kabupaten_kota' AND ST_Contains(geom::geometry, NEW.actual_location::geometry) LIMIT 1; END IF; RETURN NEW; END; $$ LANGUAGE plpgsql` and `CREATE TRIGGER posts_set_city_tg BEFORE INSERT ON posts FOR EACH ROW EXECUTE FUNCTION posts_set_city_fn()`.
- [ ] 2.7 In V11: `CREATE OR REPLACE VIEW visible_posts AS SELECT * FROM posts WHERE is_auto_hidden = FALSE` so `pg_views` reflects the new projection.
- [ ] 2.8 Add the V11 file to the `CoordinateJitterRule` / spatial-lint allowlist alongside V4 and any existing entries.
- [ ] 2.9 Run `./gradlew :backend:ktor:flywayMigrate` against the local dev DB; verify the V11 row in `flyway_schema_history`, verify `admin_regions` row count ≥ 540, verify `posts.city_name` + `posts.city_admin_region_id` columns exist, verify `posts_set_city_tg` exists in `pg_trigger`.
- [ ] 2.10 Write `MigrationV11SmokeTest` (tagged `database`) covering: clean migrate from V10, idempotent second run, CHECK constraint on `admin_regions.level`, all four indexes with documented definitions, `ST_IsValid(geom)` = TRUE for every row, DKI 5 kotamadya + Kepulauan Seribu present at `kabupaten_kota` level, `posts.city_*` columns nullable, `posts_set_city_tg` triggers on INSERT inside a polygon (populates), outside any polygon (leaves NULL), and with caller-supplied values (does NOT overwrite), FK `ON DELETE SET NULL` on `posts.city_admin_region_id` works, `visible_posts` view projects `city_name` + `city_admin_region_id`.
- [ ] 2.11 `./gradlew :backend:ktor:test --tests '*MigrationV11SmokeTest*'` green.

## 3. Global timeline service + endpoint

- [ ] 3.1 Add `GlobalTimelineService.kt` in `backend/ktor/src/main/kotlin/id/nearyou/app/timeline/` alongside `NearbyTimelineService` and `FollowingTimelineService`.
- [ ] 3.2 Add `PostsGlobalRepository` interface (or extend existing `PostsTimelineRepository`) in `core/data/.../repository/`, with a Jdbc impl in `:infra:supabase`.
- [ ] 3.3 Implement the canonical Global query verbatim per design Decision 5: `FROM visible_posts p` + `LEFT JOIN post_likes pl ON pl.post_id = p.id AND pl.user_id = :viewer` + `LEFT JOIN LATERAL (SELECT COUNT(*) FROM post_replies pr JOIN visible_users vu ON vu.id = pr.author_id WHERE pr.post_id = p.id AND pr.deleted_at IS NULL) c ON TRUE` + both bidirectional `user_blocks` NOT-IN subqueries + keyset predicate + `ORDER BY p.created_at DESC, p.id DESC LIMIT 31`.
- [ ] 3.4 Project columns: `p.id`, `p.author_user_id`, `p.content`, `ST_Y(p.display_location::geometry) AS lat`, `ST_X(p.display_location::geometry) AS lng`, `p.city_name`, `p.created_at`, `(pl.user_id IS NOT NULL) AS liked_by_viewer`, `c.n AS reply_count`. No `distance_m`, no `admin_regions` JOIN, no `ST_Contains`.
- [ ] 3.5 Verify the SQL literal contains `user_blocks`, `blocker_id =`, `blocked_id =` tokens (confirms `BlockExclusionJoinRule` passes).
- [ ] 3.6 Param parsing: only `cursor` is recognized; unknown params ignored; malformed cursor → HTTP 400 `invalid_cursor` via the shared `decodeCursor` helper.
- [ ] 3.7 Response DTO: `GlobalTimelinePost { id, authorUserId, content, latitude, longitude, cityName, createdAt, likedByViewer, replyCount }` + `GlobalTimelineResponse { posts, nextCursor }`. Serialize NULL `city_name` as `""` (use `@Serializable` default or a `String` type with an explicit mapper from nullable DB value).
- [ ] 3.8 Extend `TimelineRoutes.kt` to register `GET /api/v1/timeline/global` behind `authenticate(AUTH_PROVIDER_USER)`.
- [ ] 3.9 Write `GlobalTimelineServiceTest` (tagged `database`) covering all 17 scenarios from the `global-timeline` spec: happy path with city labels, cursor pagination (35 → 30 + 5), no follows filter, auto-hidden exclusion, bidirectional block exclusion (two sub-cases), auth required, `liked_by_viewer` true/false/present on every post, LEFT JOIN cardinality invariant, `reply_count = 0`, reply counter excludes shadow-banned repliers, reply counter excludes soft-deleted replies, reply counter does NOT apply viewer-block exclusion, `city_name` reflects trigger-populated value, `city_name = ""` for legacy row, `city_name` present on every post, `distance_m` absent on every post, malformed cursor → 400.
- [ ] 3.10 `./gradlew :backend:ktor:test --tests '*GlobalTimelineServiceTest*'` green (17/17).

## 4. Nearby + Following response consistency

- [ ] 4.1 Extend `NearbyTimelineService` canonical query to project `p.city_name` in the SELECT list. No WHERE, ORDER BY, or JOIN change.
- [ ] 4.2 Add `cityName: String` to the Nearby response DTO; serialize NULL as `""` using the same mapper as Global.
- [ ] 4.3 Extend `NearbyTimelineServiceTest` with three new scenarios per the `nearby-timeline` spec delta: `city_name` key present on every post, `city_name` reflects trigger-populated value, `city_name = ""` for legacy row. Pre-existing 18 scenarios continue to pass.
- [ ] 4.4 Extend `FollowingTimelineService` canonical query to project `p.city_name` in the SELECT list.
- [ ] 4.5 Add `cityName: String` to the Following response DTO; use the same NULL-handling mapper.
- [ ] 4.6 Extend `FollowingTimelineServiceTest` with three new scenarios per the `following-timeline` spec delta (same three patterns as Nearby). Pre-existing scenarios continue to pass.
- [ ] 4.7 `./gradlew :backend:ktor:test --tests '*NearbyTimelineServiceTest*'` + `*FollowingTimelineServiceTest*'` green.

## 5. Lint: coordinate-jitter + block-exclusion test fixtures

- [ ] 5.1 Add a KDoc paragraph to `BlockExclusionJoinRule.kt` explaining why `admin_regions` (and reference-data tables generally) is deliberately NOT a protected table per the `block-exclusion-lint` spec ADDED requirement.
- [ ] 5.2 Add test fixtures to `BlockExclusionJoinLintTest`: (a) Global-shaped positive-pass fixture with `FROM visible_posts` + both `user_blocks` subqueries, (b) Global-shaped negative-fail fixture missing the `blocked_id` subquery, (c) Global-shaped negative-fail fixture missing the `blocker_id` subquery, (d) `admin_regions` positive-pass fixture (`SELECT id FROM admin_regions WHERE level = 'kabupaten_kota'`) asserting the rule does NOT fire.
- [ ] 5.3 Update the coordinate-jitter / spatial-lint rule's allowlist to include `V11__admin_regions_and_post_city.sql`; add a KDoc note explaining the trigger's DB-side sanctioned `actual_location` read.
- [ ] 5.4 Add a test fixture asserting the spatial-lint rule fires on a hypothetical non-allowlisted migration that references `actual_location` outside the admin path (guard against drift).
- [ ] 5.5 `./gradlew :lint:detekt-rules:test` green.
- [ ] 5.6 `./gradlew detekt` green against the full backend + migration directory (Global + Nearby + Following queries all pass).

## 6. Optional: backfill job for legacy posts

- [ ] 6.1 (optional) Add `BackfillPostCityJob.kt` under `backend/ktor/src/main/kotlin/id/nearyou/app/tools/` that runs `UPDATE posts p SET city_name = r.name, city_admin_region_id = r.id FROM admin_regions r WHERE r.level = 'kabupaten_kota' AND ST_Contains(r.geom::geometry, p.actual_location::geometry) AND p.city_name IS NULL`. Expose via a manual CLI flag (`./gradlew :backend:ktor:run --args="backfill-post-city"`) or a one-shot admin SQL file; do NOT wire as an automatic post-migration step.
- [ ] 6.2 (optional) Write an integration test asserting the backfill is idempotent (second run updates zero rows) and skips trigger-populated rows.
- [ ] 6.3 (optional) Leave the prod run deferred until after soft launch, when the legacy-post count makes the UX difference visible. Document the deferred run in a tracker note (not part of this change's verification).

## 7. Verification + integration

- [ ] 7.1 `./gradlew :backend:ktor:test` — all backend tests green with no regressions on signup, post-creation, block endpoints, nearby-timeline, following-timeline, likes, replies, reports, or notifications.
- [ ] 7.2 `./gradlew detekt` green; `./gradlew :lint:detekt-rules:test` green.
- [ ] 7.3 Manual verification script: create 3 users in different kabupaten/kota (Jakarta Pusat, Bandung, Surabaya) → each creates a post → `GET /api/v1/timeline/global` from a 4th caller returns all 3 posts with correct `city_name` values → caller blocks one user → that user's post disappears from Global → caller unblocks → post returns. Document the script in the PR description.
- [ ] 7.4 Phase 2 benchmark smoke (optional in this change): EXPLAIN ANALYZE the Global query with 10k posts seeded; verify p95 < 100 ms (well under the 200 ms budget). If hot, revisit the index plan in the follow-up.
- [ ] 7.5 `openspec validate global-timeline-with-region-polygons --strict` — change remains valid.
- [ ] 7.6 Update `docs/08-Roadmap-Risk.md`: Phase 1 item 15 (Global shipped; timeline trio complete), Phase 2 item 2 (Global + polygon reverse-geocoding shipped); Open Decision #4 records the final dataset pick with a "resolved" marker and the attribution string.
- [ ] 7.7 Update `docs/02-Product.md` §3 (Global timeline shipped; guest access still deferred to the rate-limit change).
- [ ] 7.8 Update `docs/05-Implementation.md` § Timeline Implementation: add the canonical Global query verbatim; note the `posts.city_name` + `posts.city_admin_region_id` columns + the trigger; list the V11 migration under the schema history section.
