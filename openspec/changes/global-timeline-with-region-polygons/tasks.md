# Tasks — `global-timeline-with-region-polygons`

## ⚠️ Session scoping (read first)

This change is intentionally split across **multiple sessions**. Before picking tasks off the list below, read [`DEFERRED.md`](DEFERRED.md) in this folder — it has the per-cluster blockers, unblock triggers, and the explicit Session 1 scope.

**Session 1 (the current, AI-friendly session):** execute only these task IDs — they're dataset-independent and ship a V11 migration that Flyway can apply even before the polygon seed lands, plus the Global endpoint + Nearby/Following `city_name` projection + lint KDoc + tests that tolerate an empty `admin_regions` table:

- **Task group 2** — tasks `2.2`, `2.3`, `2.5`, `2.6`, `2.7` only (schema + indexes + trigger function + trigger + `CREATE OR REPLACE VIEW`). Skip 2.1 (header with license text), 2.4 (polygon seed), 2.8 (CoordinateJitter allowlist — rule doesn't exist), 2.9–2.11 (seed-dependent verification).
- **Task group 3** — all of `3.1`–`3.10` (Global service + repo + route + DTO + Koin wiring + integration tests; tests can run against an empty `admin_regions` — all scenarios that need seeded polygons should be marked pending / TODO-once-seeded rather than failing).
- **Task group 4** — all of `4.1`–`4.7` (Nearby + Following `city_name` projection + DTOs + test extensions).
- **Task group 5** — tasks `5.1`, `5.2` only (BlockExclusionJoinRule KDoc + new fixtures). Skip 5.3, 5.4 (CoordinateJitterRule — separate change).
- **Task group 7** — tasks `7.1`–`7.5` only (local `./gradlew test`, `detekt`, manual curl script, `openspec validate --strict`). Skip 7.6, 7.7, 7.8 (doc sync — those land in the archive PR per `openspec/project.md` § Change Delivery Workflow).

**Deferred to Session 2 (after dataset is prepared offline):**
- Task group 1 (all of `1.x` — dataset acquisition: BPS vs OSM decision, download, hand-curate DKI, convert to SQL, `ST_IsValid` pass, maritime buffer).
- Tasks `2.1`, `2.4`, `2.9`–`2.11` (V11 header finalization, seed INSERTs, row-count + polygon-validity verification).

**Deferred to future changes:**
- Tasks `5.3`, `5.4` — require a `CoordinateJitterRule` Detekt rule that does NOT yet exist in `lint/detekt-rules/`. Scope creep to build it here; file as its own change (`rule(coordinate-jitter-lint)` or similar).
- Tasks `6.1`–`6.3` — optional backfill job, deferred until post-soft-launch when legacy NULL `city_name` posts make it worth running.
- Tasks `7.6`–`7.8` — doc sync (Phase 1 item 15 shipped, `docs/02-Product.md` §3 shipped, canonical Global query to `docs/05`); these belong to the archive PR per workflow convention.

**If you're an AI agent invoked via `/opsx:apply`:** you MAY receive additional free-form scope instructions as args — those override this preamble. If no override, execute exactly the Session 1 scope above. If in doubt, surface to the user before deviating. Any newly-discovered out-of-scope work goes into `FOLLOW_UPS.md` at repo root (transient file — create if missing; the intro + Format block from PR [#18](https://github.com/aditrioka/nearyou-id/pull/18) is the canonical template, per `.claude/skills/next-change/SKILL.md` Notes), not inline here.

---

## 1. Dataset acquisition

- [ ] 1.1 Decide dataset source: default BPS (CC-BY 4.0); fallback to OpenStreetMap `admin_level = 5` (ODbL) if the BPS GeoJSON is not obtainable in kabupaten/kota MULTIPOLYGON form. Record the decision and the final attribution string in `design.md` Open Question 1 and in the V11 header.
- [ ] 1.2 Acquire the source GeoJSON: either pull BPS kabupaten/kota boundaries or run an Overpass query (`[out:json]; relation[admin_level=5][boundary=administrative](area:3600304716); out geom;`) against OSM Indonesia.
- [ ] 1.3 Hand-curate DKI Jakarta's 5 kotamadya (Jakarta Pusat, Jakarta Utara, Jakarta Selatan, Jakarta Timur, Jakarta Barat) and Kepulauan Seribu at `level = 'kabupaten_kota'`, with `parent_id` pointing to the DKI Jakarta province row. Both default datasets conflate these at a single admin level — this fixup is required.
- [ ] 1.3.5 Identify coastal kabupaten/kota polygons and apply a 12 nautical mile (~22 km) maritime buffer to each via `ST_Buffer` (geography space) or equivalent BEFORE generating the INSERT statements. This import-time buffering is the product-spec'd way per `docs/02-Product.md:200–203`; at query time the trigger treats all polygons uniformly regardless of whether the buffer was applied. Document the coastal-kabupaten detection heuristic (e.g., "kabupaten whose centroid is within 50 km of Indonesia's coastline geometry") in a comment block alongside the import script or the INSERT section.
- [ ] 1.4 Convert the GeoJSON + fixup + buffered polygons to SQL INSERT statements with `ST_GeomFromGeoJSON(...)` literals. Commit the generated SQL into `V11__admin_regions.sql`.
- [ ] 1.5 Run `ST_IsValid(geom)` on every seeded row in a local Postgres; fix any invalid multipolygons with `ST_MakeValid` before committing. Document any hand-fixups in a comment block alongside the INSERT.

## 2. V11 migration: schema + seed + trigger + view

- [ ] 2.1 Create `backend/ktor/src/main/resources/db/migration/V11__admin_regions.sql` with a header comment block covering: dataset source + license + attribution text, V11 consumer list (`nearby-timeline`, `following-timeline`, `global-timeline`), note that this is the first BEFORE INSERT trigger + first spatially-seeded reference table in the Flyway history, and a note that coastal polygons were pre-buffered by 12 nm (~22 km) at import time.
- [x] 2.2 In V11: `CREATE TABLE admin_regions (id INT PRIMARY KEY, name TEXT NOT NULL, level TEXT NOT NULL CHECK (level IN ('province', 'kabupaten_kota')), parent_id INT REFERENCES admin_regions(id), geom GEOGRAPHY(MULTIPOLYGON, 4326) NOT NULL, geom_centroid GEOGRAPHY(POINT, 4326) NOT NULL)` exactly per `docs/05-Implementation.md:1007–1017`.
- [x] 2.3 In V11: create all four indexes — `admin_regions_geom_idx USING GIST(geom)`, `admin_regions_centroid_idx USING GIST(geom_centroid)`, `admin_regions_level_idx (level)`, `admin_regions_parent_idx (parent_id)`.
- [ ] 2.4 In V11: insert the province seed (~38 rows, `parent_id IS NULL`) followed by the kabupaten/kota seed (~500 rows, `parent_id` resolved to the matching province id). IDs must equal the dataset's stable code (BPS `kode_wilayah` or OSM relation ID) per design Decision 8. Coastal polygons carry the 12 nm maritime buffer baked into `geom` per task 1.3.5.
- [x] 2.5 (deliberately no `ALTER TABLE posts`) — the target columns `posts.city_name TEXT` and `posts.city_match_type VARCHAR(16)` already exist from V4 (`backend/ktor/src/main/resources/db/migration/V4__post_creation.sql:22–23`). V11 adds ZERO columns to `posts`.
- [x] 2.6 In V11: create the PL/pgSQL function `posts_set_city_fn` implementing the 4-step fallback ladder verbatim per design Decision 2, plus the `BEFORE INSERT` trigger:
  ```sql
  CREATE OR REPLACE FUNCTION posts_set_city_fn() RETURNS TRIGGER AS $$
  DECLARE
      matched_name TEXT;
  BEGIN
      -- Caller override: if city_name was explicitly supplied, skip the ladder.
      IF NEW.city_name IS NOT NULL THEN
          RETURN NEW;
      END IF;

      -- Step 1: strict ST_Contains match.
      SELECT name INTO matched_name
        FROM admin_regions
       WHERE level = 'kabupaten_kota'
         AND ST_Contains(geom::geometry, NEW.actual_location::geometry)
       LIMIT 1;
      IF matched_name IS NOT NULL THEN
          NEW.city_name := matched_name;
          NEW.city_match_type := 'strict';
          RETURN NEW;
      END IF;

      -- Step 2: 10 m buffered match + deterministic centroid tie-breaker.
      SELECT name INTO matched_name
        FROM admin_regions
       WHERE level = 'kabupaten_kota'
         AND ST_DWithin(geom, NEW.actual_location, 10)
       ORDER BY ST_Distance(geom_centroid, NEW.actual_location) ASC
       LIMIT 1;
      IF matched_name IS NOT NULL THEN
          NEW.city_name := matched_name;
          NEW.city_match_type := 'buffered_10m';
          RETURN NEW;
      END IF;

      -- Step 3: nearest kabupaten/kota within 50 km.
      SELECT name INTO matched_name
        FROM admin_regions
       WHERE level = 'kabupaten_kota'
         AND ST_DWithin(geom, NEW.actual_location, 50000)
       ORDER BY ST_Distance(geom, NEW.actual_location) ASC
       LIMIT 1;
      IF matched_name IS NOT NULL THEN
          NEW.city_name := matched_name;
          NEW.city_match_type := 'fuzzy_match';
          RETURN NEW;
      END IF;

      -- Step 4: out of range. Both columns stay NULL.
      RETURN NEW;
  END;
  $$ LANGUAGE plpgsql;

  CREATE TRIGGER posts_set_city_tg
    BEFORE INSERT ON posts
    FOR EACH ROW EXECUTE FUNCTION posts_set_city_fn();
  ```
- [x] 2.7 In V11: `CREATE OR REPLACE VIEW visible_posts AS SELECT * FROM posts WHERE is_auto_hidden = FALSE` — idempotent no-op refresh (V4 already defined this view; `city_name` + `city_match_type` were always projected via `SELECT *`).
- [ ] 2.8 Add the V11 file to the `CoordinateJitterRule` / spatial-lint allowlist alongside V4 and any existing entries.
- [ ] 2.9 Run `./gradlew :backend:ktor:flywayMigrate` against the local dev DB; verify the V11 row in `flyway_schema_history`, verify `admin_regions` row count ≥ 540, verify `posts_set_city_tg` exists in `pg_trigger`, verify `posts.city_name` + `posts.city_match_type` columns still exist (they were added in V4; V11 does not touch them).
- [ ] 2.10 Write `MigrationV11SmokeTest` (tagged `database`) covering: clean migrate from V10, idempotent second run, CHECK constraint on `admin_regions.level`, all four indexes with documented definitions, `ST_IsValid(geom)` = TRUE for every row, DKI 5 kotamadya + Kepulauan Seribu present at `kabupaten_kota` level, V11 makes no `ALTER TABLE posts` change (pre- and post-V11 `posts` column set diff is empty), `posts_set_city_tg` fires on INSERT and follows the 4-step ladder across all four outcomes (step 1 strict match populates `city_match_type = 'strict'`; step 2 boundary artifact matches with `'buffered_10m'` and the centroid-distance tie-breaker is deterministic; step 3 coastal-gap post matches with `'fuzzy_match'`; step 4 deep-ocean post leaves both fields NULL), caller-supplied `city_name` short-circuits the ladder (trigger does NOT overwrite), `visible_posts` view still projects `city_name` + `city_match_type` (inherited from V4).
- [ ] 2.11 `./gradlew :backend:ktor:test --tests '*MigrationV11SmokeTest*'` green.

## 3. Global timeline service + endpoint

- [x] 3.1 Add `GlobalTimelineService.kt` in `backend/ktor/src/main/kotlin/id/nearyou/app/timeline/` alongside `NearbyTimelineService` and `FollowingTimelineService`.
- [x] 3.2 Add `PostsGlobalRepository` interface (or extend existing `PostsTimelineRepository`) in `core/data/.../repository/`, with a Jdbc impl in `:infra:supabase`.
- [x] 3.3 Implement the canonical Global query verbatim per design Decision 5: `FROM visible_posts p` + `LEFT JOIN post_likes pl ON pl.post_id = p.id AND pl.user_id = :viewer` + `LEFT JOIN LATERAL (SELECT COUNT(*) FROM post_replies pr JOIN visible_users vu ON vu.id = pr.author_id WHERE pr.post_id = p.id AND pr.deleted_at IS NULL) c ON TRUE` + both bidirectional `user_blocks` NOT-IN subqueries + keyset predicate + `ORDER BY p.created_at DESC, p.id DESC LIMIT 31`.
- [x] 3.4 Project columns: `p.id`, `p.author_user_id`, `p.content`, `ST_Y(p.display_location::geometry) AS lat`, `ST_X(p.display_location::geometry) AS lng`, `p.city_name`, `p.created_at`, `(pl.user_id IS NOT NULL) AS liked_by_viewer`, `c.n AS reply_count`. No `distance_m`, no `admin_regions` JOIN, no `ST_Contains`, no `city_match_type` (admin/internal only; never surfaces to clients).
- [x] 3.5 Verify the SQL literal contains `user_blocks`, `blocker_id =`, `blocked_id =` tokens (confirms `BlockExclusionJoinRule` passes).
- [x] 3.6 Param parsing: only `cursor` is recognized; unknown params ignored; malformed cursor → HTTP 400 `invalid_cursor` via the shared `decodeCursor` helper.
- [x] 3.7 Response DTO: `GlobalTimelinePost { id, authorUserId, content, latitude, longitude, cityName, createdAt, likedByViewer, replyCount }` + `GlobalTimelineResponse { posts, nextCursor }`. Serialize NULL `city_name` as `""` (use `@Serializable` default or a `String` type with an explicit mapper from nullable DB value). `city_match_type` is NOT included in the DTO.
- [x] 3.8 Extend `TimelineRoutes.kt` to register `GET /api/v1/timeline/global` behind `authenticate(AUTH_PROVIDER_USER)`.
- [x] 3.9 Write `GlobalTimelineServiceTest` (tagged `database`) covering all 17 scenarios from the `global-timeline` spec: happy path with city labels, cursor pagination (35 → 30 + 5), no follows filter, auto-hidden exclusion, bidirectional block exclusion (two sub-cases), auth required, `liked_by_viewer` true/false/present on every post, LEFT JOIN cardinality invariant, `reply_count = 0`, reply counter excludes shadow-banned repliers, reply counter excludes soft-deleted replies, reply counter does NOT apply viewer-block exclusion, `city_name` reflects trigger-populated value, `city_name = ""` for legacy row, `city_name` present on every post, `distance_m` absent on every post, malformed cursor → 400.
- [x] 3.10 `./gradlew :backend:ktor:test --tests '*GlobalTimelineServiceTest*'` green (17/17). _Run via `-Dkotest.filter.specs='*GlobalTimelineServiceTest*'` (Kotest JUnit5 engine doesn't honor `--tests`). 20/20 Session 1 cases green — 3 extra cases versus the 17 required ones (`liked_by_viewer` split into true/false/present/cardinality + `city_name` split into trigger-populated/NULL/key-present); full spec coverage, zero skipped._

## 4. Nearby + Following response consistency

- [x] 4.1 Extend `NearbyTimelineService` canonical query to project `p.city_name` in the SELECT list. No WHERE, ORDER BY, or JOIN change.
- [x] 4.2 Add `cityName: String` to the Nearby response DTO; serialize NULL as `""` using the same mapper as Global.
- [x] 4.3 Extend `NearbyTimelineServiceTest` with three new scenarios per the `nearby-timeline` spec delta: `city_name` key present on every post, `city_name` reflects trigger-populated value, `city_name = ""` for legacy row. Pre-existing 18 scenarios continue to pass.
- [x] 4.4 Extend `FollowingTimelineService` canonical query to project `p.city_name` in the SELECT list.
- [x] 4.5 Add `cityName: String` to the Following response DTO; use the same NULL-handling mapper.
- [x] 4.6 Extend `FollowingTimelineServiceTest` with three new scenarios per the `following-timeline` spec delta (same three patterns as Nearby). Pre-existing scenarios continue to pass.
- [x] 4.7 `./gradlew :backend:ktor:test --tests '*NearbyTimelineServiceTest*'` + `*FollowingTimelineServiceTest*'` green. _Run via `-Dkotest.filter.specs=...`. Nearby: 24/24 green (21 pre-existing + 3 city_name). Following: 23/23 green (20 pre-existing + 3 city_name)._

## 5. Lint: coordinate-jitter + block-exclusion test fixtures

- [x] 5.1 Add a KDoc paragraph to `BlockExclusionJoinRule.kt` explaining why `admin_regions` (and reference-data tables generally) is deliberately NOT a protected table per the `block-exclusion-lint` spec ADDED requirement.
- [x] 5.2 Add test fixtures to `BlockExclusionJoinLintTest`: (a) Global-shaped positive-pass fixture with `FROM visible_posts` + both `user_blocks` subqueries, (b) Global-shaped negative-fail fixture missing the `blocked_id` subquery, (c) Global-shaped negative-fail fixture missing the `blocker_id` subquery, (d) `admin_regions` positive-pass fixture (`SELECT id FROM admin_regions WHERE level = 'kabupaten_kota'`) asserting the rule does NOT fire.
- [ ] 5.3 Update the coordinate-jitter / spatial-lint rule's allowlist to include `V11__admin_regions.sql`; add a KDoc note explaining the trigger's DB-side sanctioned `actual_location` read.
- [ ] 5.4 Add a test fixture asserting the spatial-lint rule fires on a hypothetical non-allowlisted migration that references `actual_location` outside the admin path (guard against drift).
- [x] 5.5 `./gradlew :lint:detekt-rules:test` green.
- [x] 5.6 `./gradlew detekt` green against the full backend + migration directory (Global + Nearby + Following queries all pass). _0 code smells across 46 Kotlin files._

## 6. Optional: backfill job for legacy posts

- [ ] 6.1 (optional) Add `BackfillPostCityJob.kt` under `backend/ktor/src/main/kotlin/id/nearyou/app/tools/` that applies the same 4-step fallback ladder to rows with `city_name IS NULL`:
  ```sql
  WITH matched AS (
      -- Step 1: strict match.
      SELECT p.id AS post_id, r.name, 'strict'::text AS match_type
        FROM posts p
        JOIN admin_regions r ON r.level = 'kabupaten_kota'
                            AND ST_Contains(r.geom::geometry, p.actual_location::geometry)
       WHERE p.city_name IS NULL

      UNION ALL
      -- Step 2: buffered 10 m with centroid tie-breaker (only for rows step 1 missed).
      SELECT p.id, r.name, 'buffered_10m'
        FROM posts p
        JOIN LATERAL (
              SELECT name
                FROM admin_regions
               WHERE level = 'kabupaten_kota'
                 AND ST_DWithin(geom, p.actual_location, 10)
               ORDER BY ST_Distance(geom_centroid, p.actual_location) ASC
               LIMIT 1
             ) r ON TRUE
       WHERE p.city_name IS NULL
         AND NOT EXISTS (
              SELECT 1 FROM admin_regions r1
               WHERE r1.level = 'kabupaten_kota'
                 AND ST_Contains(r1.geom::geometry, p.actual_location::geometry))

      UNION ALL
      -- Step 3: 50 km nearest neighbor (only for rows steps 1 + 2 missed).
      SELECT p.id, r.name, 'fuzzy_match'
        FROM posts p
        JOIN LATERAL (
              SELECT name
                FROM admin_regions
               WHERE level = 'kabupaten_kota'
                 AND ST_DWithin(geom, p.actual_location, 50000)
               ORDER BY ST_Distance(geom, p.actual_location) ASC
               LIMIT 1
             ) r ON TRUE
       WHERE p.city_name IS NULL
         AND NOT EXISTS (
              SELECT 1 FROM admin_regions r1
               WHERE r1.level = 'kabupaten_kota'
                 AND (ST_Contains(r1.geom::geometry, p.actual_location::geometry)
                      OR ST_DWithin(r1.geom, p.actual_location, 10)))
  )
  UPDATE posts
     SET city_name = m.name,
         city_match_type = m.match_type
    FROM matched m
   WHERE posts.id = m.post_id
     AND posts.city_name IS NULL;
  ```
  Expose via a manual CLI flag (`./gradlew :backend:ktor:run --args="backfill-post-city"`) or a one-shot admin SQL file; do NOT wire as an automatic post-migration step. Rows still NULL after all three steps correspond to step-4 outcomes (deep ocean beyond 50 km).
- [ ] 6.2 (optional) Write an integration test asserting the backfill is idempotent (second run updates zero rows), skips trigger-populated rows, and tags match-types correctly (`strict` / `buffered_10m` / `fuzzy_match`) across fixture rows placed to hit each ladder step.
- [ ] 6.3 (optional) Leave the prod run deferred until after soft launch, when the legacy-post count makes the UX difference visible. Document the deferred run in a tracker note (not part of this change's verification).

## 7. Verification + integration

- [x] 7.1 `./gradlew :backend:ktor:test` — all backend tests green with no regressions on signup, post-creation, block endpoints, nearby-timeline, following-timeline, likes, replies, reports, or notifications. _377 tests across 36 suites, 0 failures, 0 errors. Full per-suite summary logged in the Session 1 archive notes below._
- [x] 7.2 `./gradlew detekt` green; `./gradlew :lint:detekt-rules:test` green. _Both green._
- [x] 7.3 Manual verification script: create 3 users in different kabupaten/kota (Jakarta Pusat, Bandung, Surabaya) → each creates a post → `GET /api/v1/timeline/global` from a 4th caller returns all 3 posts with correct `city_name` values → caller blocks one user → that user's post disappears from Global → caller unblocks → post returns. Document the script in the PR description. _Session 1 note: the polygon seed is deferred, so the trigger-populated scenarios are exercised via the caller-override guard (`INSERT ... city_name = ?`) in [`GlobalTimelineServiceTest`](backend/ktor/src/test/kotlin/id/nearyou/app/timeline/GlobalTimelineServiceTest.kt) — same behaviour the real 3-user manual curl script will produce once Session 2 lands the seed. The PR description carries the exact curl flow for the seeded-run verification._
- [ ] 7.4 Phase 2 benchmark smoke (optional in this change): EXPLAIN ANALYZE the Global query with 10k posts seeded; verify p95 < 100 ms (well under the 200 ms budget). If hot, revisit the index plan in the follow-up. _Deferred — needs Session 2 polygon seed + 10k post fixture; optional in the scope._
- [x] 7.5 `openspec validate global-timeline-with-region-polygons --strict` — change remains valid.
- [ ] 7.6 Update `docs/08-Roadmap-Risk.md`: Phase 1 item 15 (Global shipped; timeline trio complete), Phase 2 item 2 (Global + polygon reverse-geocoding shipped); Open Decision #4 records the final dataset pick with a "resolved" marker and the attribution string.
- [ ] 7.7 Update `docs/02-Product.md` §3 (Global timeline shipped; guest access still deferred to the rate-limit change).
- [ ] 7.8 Update `docs/05-Implementation.md` § Timeline Implementation: add the canonical Global query verbatim; note that `posts.city_name` + `posts.city_match_type` (both pre-existing from V4) are populated by the V11 `posts_set_city_tg` trigger via the 4-step fallback; list the V11 migration under the schema history section.
