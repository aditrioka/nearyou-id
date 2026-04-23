## Context

Three timelines are defined in `docs/02-Product.md` §3: Nearby, Following, Global. Nearby shipped V5 via `nearby-timeline-with-blocks` and Following shipped V6 via `following-timeline-with-follow-cascade`. Global is the last unshipped timeline and the only one guests can ever read; it is also the app's entry screen before sign-in per product spec.

The canonical Global query is straightforward — it's essentially "Following without the follows filter" — but the product requires each list item to carry a `city_name` label (kabupaten/kota / kotamadya). That label cannot be derived from `display_location` (the fuzzed coordinate) because jitter crosses polygon boundaries on the 0–500 m scale. It must come from `actual_location` via polygon containment, and the polygon dataset is not yet in the DB.

The Flyway chain is V1–V10 (latest: `V10__notifications.sql`). PostGIS, `visible_posts`, cursor encoding (`common/Cursor.kt`), `auth-jwt`, and `BlockExclusionJoinRule` Detekt rule are all wired. No new infrastructure is needed — this change is one migration + one seed + one trigger + one endpoint.

Canonical references:
- `docs/02-Product.md` §3 (Global tab: chronological, no filter, city label, guests read-only).
- `docs/02-Product.md` §"Polygon-Based Reverse Geocoding" (dataset sources, DKI special handling, `actual_location` use).
- `docs/05-Implementation.md` lines 1007–1021 (`admin_regions` schema).
- `docs/05-Implementation.md` lines 1939–1940 (`ST_Contains` + GIST index pattern).
- `docs/08-Roadmap-Risk.md` Phase 1 item 15, Phase 2 item 2.
- `docs/08-Roadmap-Risk.md` Open Decision #4 (dataset source).

## Goals / Non-Goals

**Goals:**
- Ship a `GET /api/v1/timeline/global` endpoint with the same cursor shape, pagination cap, and block-exclusion invariants as Nearby + Following.
- Land `admin_regions` as a seeded PostGIS reference table in a single Flyway migration.
- Denormalize the reverse-geocode result to `posts.city_name` + `posts.city_admin_region_id` via a BEFORE INSERT trigger so the Global hot path is a pure keyset index scan with no spatial work at read time.
- Keep `actual_location` DB-side only: the trigger is a second sanctioned reader (alongside the admin path), never surfacing the coordinate or its lat/lng to any client.
- Extend Nearby + Following response shapes with `city_name` so all three timelines present a consistent post payload.

**Non-Goals:**
- Guest Global access (no-login path). Requires guest JWT pre-issuance with attestation + Redis rate limits (Phase 1 item 24). Lands as a separate change.
- Redis-backed 10/session soft + 30/hour hard scroll caps. Same deferral as Nearby + Following.
- Province-level or island-level filter. Product spec says chronological-only for MVP.
- Backfill of `city_name` on legacy V4 posts. Written as an optional task; Global tolerates NULL and renders `""`.
- Read-time fallback reverse-geocoding if the trigger ever produced NULL. No on-read `ST_Contains` path.
- Admin polygon editor or CSV import tool (Phase 3.5 admin panel).
- Mobile UI wiring for the Global tab (Phase 3 mobile app change).
- Perspective API text moderation.

## Decisions

### Decision 1: One vertical change — schema + seed + trigger + endpoint + Nearby/Following city_name projection

All in one migration + one Ktor module + test coverage, mirroring the `nearby-timeline-with-blocks` and `following-timeline-with-follow-cascade` verticals. Splitting the seed into its own change leaves a non-functional `admin_regions` table; splitting the endpoint postpones the user-visible capability without a real win.

**Alternative considered:** Ship `admin_regions` + trigger in one change, Global endpoint in a follow-up. Rejected — the trigger without the endpoint is an unexercised write-path addition with no behavior under test; the endpoint without the trigger requires a query-time reverse geocode we explicitly reject (Decision 4). Both land together.

### Decision 2: Materialize city via a BEFORE INSERT trigger on `posts`, write to `posts.city_name` + `posts.city_admin_region_id`

On `INSERT INTO posts`, a trigger runs:
```sql
SELECT id, name
  INTO NEW.city_admin_region_id, NEW.city_name
  FROM admin_regions
 WHERE level = 'kabupaten_kota'
   AND ST_Contains(geom, NEW.actual_location::geometry)
 LIMIT 1;
```
If no polygon contains the point (rare — BPS coverage is full Indonesia; gaps are coastal artifacts), both columns stay NULL. The post INSERT still succeeds.

**Why denormalize:**
- Global's hot path becomes a simple keyset on `(created_at DESC, id DESC)` against `visible_posts` — no `ST_Contains` per row, no JOIN, no polygon scan.
- `admin_regions` has ~540 rows, but `ST_Contains` against a multipolygon GIST index is still 1–3 ms per call. At Global p95 <200 ms (`docs/08-Roadmap-Risk.md:169`) with 30 rows/page, that's 30–90 ms budget spent on something we can pre-compute once.
- City names change very rarely (kabupaten/kota boundaries are politically stable); staleness risk is real but bounded. When a polygon re-seed does land, the optional backfill job re-runs the reverse-geocode against the affected posts.
- The FK `city_admin_region_id` preserves the link even if a future re-seed changes the display `name` — the string snapshot is what renders; the FK is what survives.

**Alternative considered:** Compute `city_name` at read time via `LEFT JOIN admin_regions ON ST_Contains(geom, p.actual_location)`. Rejected — forces the Global endpoint to read `actual_location` out of `visible_posts`, which is an unacceptable broadening of the coordinate-jitter invariant (`actual_location` gets projected into business-query rows, where the next lint slip exposes it to clients). Also breaks the spatial budget at p95.

**Alternative considered:** Materialized view (`CREATE MATERIALIZED VIEW global_posts AS ...`) refreshed periodically. Rejected — refresh cadence is a new operational knob, and incremental refresh on a chronological feed would need trigger-based invalidation anyway (back to Decision 2 by another path).

**Alternative considered:** Application-layer reverse-geocode in `PostService.createPost()` before the INSERT (read `admin_regions`, compute, pass into the INSERT columns). Rejected — the trigger is simpler, race-free, and the only place `actual_location` is used; keeping the geocoding in SQL means no additional application reader of `actual_location` (`CoordinateJitterRule` allowlist stays tight).

### Decision 3: Ship `admin_regions` + seed in one versioned Flyway migration

The next unused number is **V11**. File name: `V11__admin_regions_and_post_city.sql`. Contents, in order:
1. `CREATE TABLE admin_regions (...)` exactly per `docs/05-Implementation.md:1007–1017`.
2. GIST + btree indexes.
3. Province seed (~38 rows).
4. Kabupaten/kota seed (~500 rows) including the 5 DKI kotamadya + Kepulauan Seribu at `level = 'kabupaten_kota'`.
5. `ALTER TABLE posts ADD COLUMN city_name TEXT NULL`.
6. `ALTER TABLE posts ADD COLUMN city_admin_region_id INT NULL REFERENCES admin_regions(id) ON DELETE SET NULL`.
7. `CREATE OR REPLACE FUNCTION posts_set_city_fn() RETURNS TRIGGER ...`.
8. `CREATE TRIGGER posts_set_city_tg BEFORE INSERT ON posts FOR EACH ROW EXECUTE FUNCTION posts_set_city_fn()`.
9. `CREATE OR REPLACE VIEW visible_posts AS ...` (replaces the V4/V5 definition; same filter, adds `p.city_name` + `p.city_admin_region_id` to the SELECT list).

The migration will be large (polygon WKT inline: ~15–25 MB). That's acceptable — Flyway handles it, the repo already expects versioned migrations to be authoritative, and a subsequent polygon update lands as V12 (or later) with full diff auditability. We explicitly reject `R__admin_regions_seed.sql` repeatable migration; the project has no `R__` precedent, and introducing one for one reference table is scope creep.

**Alternative considered:** Split into `V11__admin_regions_schema.sql` + `V12__admin_regions_seed.sql`. Rejected — two migrations for one logical change means V11 ships a table that cannot satisfy the trigger's subquery until V12 runs; ordering is implicit on same-deploy but breaks local dev that applies migrations one at a time. Keep schema + seed coupled so the table is never in an unseeded state.

**Alternative considered:** Seed data loaded via a separate Cloud Run Job post-migration. Rejected — introduces a deploy-ordering dependency the Ktor `posts_set_city_tg` trigger doesn't tolerate (any INSERT between "migration done" and "seed done" gets NULL city fields).

### Decision 4: Dataset — BPS primary, OSM fallback

Open Decision #4 in `docs/08-Roadmap-Risk.md` leaves the source open between BPS (CC-BY) and OSM (ODbL, attribution-required). Default to **BPS**:
- BPS is the official Indonesian statistics agency; boundaries match administrative law.
- CC-BY attribution is cleanly disclosed in the app's legal section (one line: "Kabupaten/kota boundaries © BPS, CC-BY 4.0").
- ODbL attribution + share-alike terms for OSM are heavier to satisfy and can cascade to any derived dataset; CC-BY is simpler.

If the BPS dataset is not practically obtainable in kabupaten/kota MULTIPOLYGON form (some vendors ship only province-level or rasterized PDFs), fall back to OSM `admin_level = 5` relations exported via Overpass. The decision is finalized in the migration header's licensing note; see **Open Question 1** for the explicit checkpoint.

DKI Jakarta and Kepulauan Seribu receive explicit hand-curated entries at `level = 'kabupaten_kota'` per `docs/02-Product.md:187` — both datasets conflate them with "DKI Jakarta" at level 3, so the migration does a 5-row post-import fixup.

**Alternative considered:** Hand-curate only the top-30 kabupaten by population and use a catch-all "Indonesia" polygon for the rest. Rejected — half the posts in non-Jabodetabek Indonesia would label as "Indonesia," which is product-broken.

### Decision 5: Canonical Global query mirrors Following minus the follows filter, plus `city_name` projection

```sql
SELECT p.id,
       p.author_user_id,
       p.content,
       ST_Y(p.display_location::geometry) AS lat,
       ST_X(p.display_location::geometry) AS lng,
       p.city_name,
       p.created_at,
       (pl.user_id IS NOT NULL) AS liked_by_viewer,
       c.n AS reply_count
FROM   visible_posts p
LEFT JOIN post_likes pl
       ON pl.post_id = p.id AND pl.user_id = :viewer
LEFT JOIN LATERAL (
  SELECT COUNT(*) AS n
    FROM post_replies pr
    JOIN visible_users vu ON vu.id = pr.author_id
   WHERE pr.post_id = p.id
     AND pr.deleted_at IS NULL
) c ON TRUE
WHERE  p.author_user_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = :viewer)
  AND  p.author_user_id NOT IN (SELECT blocker_id FROM user_blocks WHERE blocked_id = :viewer)
  AND  (p.created_at, p.id) < (:cursor_created_at, :cursor_id)
ORDER BY p.created_at DESC, p.id DESC
LIMIT  :page_size + 1;
```

Shape notes:
- No `follows` filter (Global is every visible author).
- No `ST_DWithin` / `ST_Distance` / geo params; no `distance_m` in the response.
- `LEFT JOIN post_likes` (V7) and `LEFT JOIN LATERAL` reply counter (V8) are carried verbatim from Nearby/Following — same cardinality invariants, same shadow-ban filter on the counter, same no-viewer-block-exclusion privacy tradeoff on the counter.
- `p.city_name` is projected directly from `visible_posts`.
- Both directional `user_blocks` NOT-IN subqueries remain — `BlockExclusionJoinRule` passes.

**Alternative considered:** `FROM posts` (not `visible_posts`) + explicit `is_auto_hidden = FALSE` and shadow-ban filters inline. Rejected — mandatory use of `visible_posts` is a CI lint rule (`docs/08-Roadmap-Risk.md:84` Coding Conventions § Shadow-ban safety). No exceptions outside admin paths.

### Decision 6: Nearby + Following responses gain `city_name` (additive; no behavior change)

For cross-timeline payload consistency (the same post item shape across all three feeds), extend the Nearby and Following response items with `city_name: string`. The Nearby + Following canonical queries get `p.city_name` added to the SELECT list; nothing else changes (no WHERE, no ORDER BY, no JOIN).

Mobile clients parsing with permissive decoders (kotlinx.serialization default or the JSON-ignore-unknown-keys convention in `:mobile:app`) absorb the new field silently. The spec change is an additive-column contract change; documented in the spec deltas for `nearby-timeline` and `following-timeline`.

**Alternative considered:** Only Global carries `city_name`; Nearby + Following stay structurally different. Rejected — mobile's post renderer would need per-timeline branching, which is future churn for every post-surface change.

### Decision 7: `actual_location` use inside the trigger is sanctioned; `CoordinateJitterRule` KDoc is updated, not the rule

The existing `CoordinateJitterRule` (from the `coordinate-jitter` capability, shipped pre-V5) enforces "non-admin paths must use `fuzzed_location`, never `actual_location`." The trigger reads `actual_location` from `NEW` (the row being inserted). This is:
- DB-side — no application reader gains access.
- Write-path only — reverse-geocode happens once at INSERT, result stored as `city_name` string.
- Never projected into any user-facing response JSON (only `city_name` is — a string, not a coordinate).
- Aligned with `docs/02-Product.md:190` ("Queries use `actual_location`" … "since accuracy matters for administrative boundaries").

The rule's current allowlist already permits the admin path. Extend the KDoc to list the INSERT trigger as the second sanctioned reader; no rule behavior change. If the lint rule scans `.sql` files under `db/migration/`, the migration file (which contains `ST_Contains(geom, NEW.actual_location::geometry)`) must be either (a) exempt from the rule like V5 is exempt from `BlockExclusionJoinRule`, or (b) covered by a dedicated annotation / header comment. Use pattern (a): add the V11 migration file to the `CoordinateJitterRule` allowlist alongside any pre-existing allowed files.

**Alternative considered:** Rewrite the trigger to read `display_location`. Rejected — `display_location` is fuzzed by up to 500 m, which crosses polygon boundaries at the kotamadya scale; a post in Jakarta Pusat would routinely label as Jakarta Selatan.

**Alternative considered:** Add a new annotation `@AllowActualLocationDbRead("reason")` like the block-exclusion rule's `@AllowMissingBlockJoin`. Rejected — the annotation machinery is for application Kotlin code; SQL file allowlist is the existing pattern and is already minimal (V11 joins V5).

### Decision 8: `admin_regions.id` is INT, surrogate; seeded IDs are stable across re-seeds

Use INT (small, ~540 rows). Seeded IDs come from the dataset's stable code (BPS `kode_wilayah` for BPS, OSM relation ID for OSM). The exact mapping is in the migration header. Once committed, IDs MUST NOT change across re-seeds — `posts.city_admin_region_id` references them. Changing an ID would orphan existing denorms.

If a re-seed renames a region (e.g., "Kabupaten Nagan Raya" → "Nagan Raya" stylistic fix), update the row in place (`UPDATE admin_regions SET name = ... WHERE id = ...`). Existing `posts.city_name` snapshots stay frozen until the optional backfill job re-runs — that's the deliberate tradeoff in Decision 2.

### Decision 9: No guest-access path in this change

Guest read of Global requires:
- Guest JWT issuance flow (`docs/05-Implementation.md:1646–1655`) with attestation + pre-issuance rate limits.
- Redis counters for the 10/session + 30/hour caps (`docs/01-Business.md` guest read policy).
- A separate auth middleware branch that admits guest JWTs only on `/timeline/global`.

Shipping all three in one vertical doubles the change footprint and drags in Redis client wiring that no existing endpoint needs. Scope it out — the follow-up rate-limit change is the right home.

The Global endpoint in this change rejects missing `Authorization` headers with HTTP 401 `unauthenticated`, same as Nearby and Following. Product-visible consequence: until the guest change ships, the Indonesia-wide feed requires sign-in. That matches the Ktor + mobile dev experience already in place (no guest UI yet, no guest token yet).

## Risks / Trade-offs

- **Risk:** Polygon coverage gaps produce NULL `city_name` on some legitimate posts (coastal spots, reclaimed land, very new regions). → **Mitigation:** Response spec tolerates NULL (serializes as empty string). The optional backfill job re-computes on polygon re-seed. Ops monitoring via a one-shot query `SELECT COUNT(*) FROM posts WHERE created_at > :last_deploy AND city_name IS NULL` surfaces anomalies.
- **Risk:** Trigger slows down `INSERT INTO posts` by 1–3 ms (single `ST_Contains` on GIST-indexed MULTIPOLYGON). → **Mitigation:** 1–3 ms on the post-creation path is acceptable (current p95 budget is far larger). Benchmarked as part of Phase 2 item 14 dataset work.
- **Risk:** Polygon dataset licensing disclosure is missed. → **Mitigation:** Migration header carries the license line; app legal section (`docs/01-Business.md` privacy policy checklist) adds the attribution line pre-launch. Tracked as a task.
- **Risk:** Nearby/Following response shape change (additive `city_name`) surprises a client that uses strict decoding. → **Mitigation:** `:mobile:app` uses permissive kotlinx.serialization config. No prod mobile client is shipped yet (Phase 3). Backend integration tests cover the new shape. Document the change in the PR description.
- **Risk:** `admin_regions` IDs drift across re-seeds (e.g., someone uses a new dataset with different codes). → **Mitigation:** Decision 8 explicitly fixes IDs at the dataset's stable code (BPS `kode_wilayah` / OSM relation ID). Migration header documents the rule. Re-seeds MUST UPDATE by ID, not DELETE + INSERT.
- **Risk:** Trigger conflict with future `posts` write paths (e.g., admin backfill, bulk import). → **Mitigation:** Trigger is `BEFORE INSERT FOR EACH ROW` — fires on every INSERT. Bulk imports that want to bypass reverse-geocoding (e.g., importing without `actual_location`) can set `city_name` + `city_admin_region_id` explicitly in the INSERT column list; the trigger's subquery runs but the NEW fields already populated by the caller remain untouched. Document the subquery's `SELECT ... INTO` semantics (no-op when the target fields are already non-null AND no error if the query returns zero rows).

  Actually — the trigger above always writes `NEW.city_admin_region_id` / `NEW.city_name`. To make it caller-overridable, the trigger body must be:
  ```sql
  IF NEW.city_admin_region_id IS NULL AND NEW.city_name IS NULL THEN
    SELECT id, name
      INTO NEW.city_admin_region_id, NEW.city_name
      FROM admin_regions
     WHERE level = 'kabupaten_kota'
       AND ST_Contains(geom, NEW.actual_location::geometry)
     LIMIT 1;
  END IF;
  ```
  This is the version the migration ships.
- **Risk:** Very large migration file (~15–25 MB WKT) triggers repo-size concerns or CI diff timeouts. → **Mitigation:** One-time cost. GitHub handles; CI `git diff` is unaffected because migration diffs are not reviewed line-by-line. Future polygon updates land as new versioned migrations — no edit churn on V11.
- **Risk:** A polygon with no `ST_Contains` match (gap) plus a caller that does NOT supply `city_name` explicitly yields NULL — but only on legacy re-writes where `actual_location` somehow falls into a gap. → **Mitigation:** Product-acceptable. Response spec renders `""`.

## Migration Plan

1. **Dataset prep (pre-migration authoring):** Pull BPS kabupaten/kota GeoJSON (or OSM fallback). Convert to SQL INSERT statements with `ST_GeomFromGeoJSON(...)` literals. Hand-curate the 5 DKI kotamadya + Kepulauan Seribu rows. Verify row count (~540) and that `ST_IsValid(geom)` returns TRUE for every row. Commit the generated SQL into `V11__admin_regions_and_post_city.sql`.
2. **Flyway V11 applies:** Creates `admin_regions`, seeds it, alters `posts` to add two columns, creates the trigger, replaces the `visible_posts` view. Additive — no downtime, no lock on existing reads of `posts` or `visible_posts` beyond the standard ALTER TABLE brief exclusive lock (seconds on Supabase Pro prod; dev + staging smaller). Flyway wraps in a transaction.
3. **Backend deploy:** `GlobalTimelineService`, `TimelineRoutes` registration for `/timeline/global`, DTO `city_name: String` field added to `Post` (+ `NearbyTimelineService` + `FollowingTimelineService` SELECT lists extended to project `city_name`). Deploys after Flyway V11 completes. Container startup is unchanged.
4. **Backfill (optional):** `backend/ktor/.../tools/BackfillPostCityJob.kt` (manually-invoked SQL: `UPDATE posts p SET city_name = r.name, city_admin_region_id = r.id FROM admin_regions r WHERE r.level = 'kabupaten_kota' AND ST_Contains(r.geom, p.actual_location::geometry) AND p.city_name IS NULL`). Safe to run at any time. NOT required for this change to ship. Tracked as an optional task; defer until the Global feed has enough legacy posts to make the UX difference visible (~post-soft-launch).
5. **Rollback:** Revert the Ktor deploy first; Global endpoint disappears. V11 stays — it's additive; no other code paths break with the new columns present. If V11 must also be reverted (unlikely in prod; possible in dev): drop the trigger, drop the two `posts` columns, restore the pre-V11 `visible_posts` view definition, drop `admin_regions`. Pre-revert snapshot: `pg_dump` the `admin_regions` table so the dataset is preserved.
6. **Doc sync:** Update `docs/02-Product.md` §3 (Global timeline shipped), `docs/08-Roadmap-Risk.md` Phase 1 item 15 + Phase 2 item 2, `docs/05-Implementation.md` §"Timeline Implementation" (Global query added). Land docs in the archive PR per the change delivery workflow in `openspec/project.md`.

## Open Questions

1. **BPS vs OSM kabupaten/kota dataset — final source.** Default: BPS (CC-BY) per Decision 4. If the BPS GeoJSON is not obtainable in kabupaten/kota MULTIPOLYGON form by the time the migration is authored, fall back to OSM `admin_level = 5` via Overpass. The migration header carries the final pick + license line. Checkpoint: resolved during task 2.1 (dataset acquisition) in `tasks.md`.
2. **Backfill job — ship in this change, or defer to a follow-up?** Current: the optional backfill is noted in `tasks.md` but unchecked; shipping the code path is 20 minutes of work but it is unexercised until a data event triggers it. Lean toward shipping the code + one integration test behind a manual-trigger CLI flag (`./gradlew :backend:ktor:run --args="backfill-post-city"`), and leaving the actual prod run for post-soft-launch. Defer final call to the implementer.
3. **Response spec — `city_name: ""` vs `city_name: null` for NULL DB value.** Pick one and lock it across all three timelines. Default: empty string `""` (avoids mobile-side null-handling branching). Confirm during spec review.
4. **Should `admin_regions` rows carry `province_name` denormalized, to avoid a JOIN when a future endpoint wants "Jakarta Selatan, DKI Jakarta"?** Current: NO — the `parent_id` FK is enough; name resolution is a single self-join. Revisit if product UX demands "city, province" as the Global label. Out of scope here.
5. **The Nearby + Following shape change (additive `city_name`) — is this a "modified" spec or just a test-coverage update?** Current: modified spec, documented in the spec deltas for `nearby-timeline` and `following-timeline`. No behavior loss; purely additive field.
