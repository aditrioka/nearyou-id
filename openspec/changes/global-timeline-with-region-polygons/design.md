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
- Populate the pre-existing `posts.city_name` + `posts.city_match_type` columns (V4) via a BEFORE INSERT trigger implementing the 4-step fallback ladder from `docs/02-Product.md:192–196`, so the Global hot path is a pure keyset index scan with no spatial work at read time.
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

### Decision 2: Materialize city via a BEFORE INSERT trigger on `posts` implementing the 4-step fallback ladder from `docs/02-Product.md`

The trigger writes to **existing** `posts.city_name` + `posts.city_match_type` columns (added in V4; see `backend/ktor/src/main/resources/db/migration/V4__post_creation.sql:22–23`). V11 adds zero new columns to `posts`.

On `INSERT INTO posts`, the trigger runs the 4-step ladder specified verbatim in [`docs/02-Product.md:192–196`](docs/02-Product.md) §"Polygon-Based Reverse Geocoding":

```sql
CREATE OR REPLACE FUNCTION posts_set_city_fn() RETURNS TRIGGER AS $$
DECLARE
    matched_name TEXT;
BEGIN
    -- Caller override: if city_name was explicitly supplied, do nothing.
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

    -- Step 2: 10-meter buffered match + deterministic centroid tie-breaker.
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

    -- Step 3: nearest kabupaten/kota within 50 km (catches coastal + EEZ-adjacent posts).
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
```

Distance arguments use GEOGRAPHY semantics (meters), not geometry degrees — `admin_regions.geom` + `posts.actual_location` are both `GEOGRAPHY(*, 4326)`, so `ST_DWithin(geog, geog, meters)` is the natural call. Coastal kabupaten polygons are already extended by a 12 nautical mile (~22 km) maritime buffer at import time (Decision 4a below), so step 1 still matches posts in nearshore waters; step 3's 50 km backstop catches posts further out.

**Why denormalize the match name (not just the FK):**
- Global's hot path becomes a simple keyset on `(created_at DESC, id DESC)` against `visible_posts` — no `ST_Contains` per row, no JOIN, no polygon scan.
- `admin_regions` has ~540 rows, but a 4-step ladder against multipolygon GIST indexes is 1–5 ms per INSERT. At Global p95 <200 ms ([`docs/08-Roadmap-Risk.md:169`](docs/08-Roadmap-Risk.md)) with 30 rows/page, that's 30–150 ms budget spent on something we can pre-compute once.
- City names change very rarely (kabupaten/kota boundaries are politically stable); staleness risk is real but bounded. When a polygon re-seed does land, the optional backfill job re-runs the 4-step ladder against the affected posts.

**Why `city_match_type` (provenance tag):**
- Debuggability — when a city label looks wrong, `match_type` reveals which step produced it. Without it, "why is this post labeled Jakarta Selatan?" is an un-answerable question 6 months from now.
- Analytics — measure `strict` vs `fuzzy_match` ratio to prioritize polygon-coverage improvements; a rising `fuzzy_match` rate signals a polygon gap needing a refresh migration.
- Industry precedent — Google Geocoding API's `geometry.location_type` (`ROOFTOP`/`RANGE_INTERPOLATED`/`GEOMETRIC_CENTER`/`APPROXIMATE`), Mapbox's `relevance`, OSM Nominatim's `type` all tag matches with provenance. Dropping provenance is a visible engineering regression.
- Column is DB-internal: client responses project only `city_name`. `city_match_type` is consumed by admin tooling + future analytics. Adding it isn't a payload bloat problem.

**Why NO `city_admin_region_id` FK column:**
- The canonical schema at [`docs/05-Implementation.md:553–567`](docs/05-Implementation.md) §Posts Schema specifies `city_name` + `city_match_type` only — no FK. Adding one diverges from canonical docs.
- YAGNI — the proposed FK's value ("survive polygon name drift in re-seeds") is speculative. Kabupaten/kota names rarely change; when they do, the snapshot `city_name` is arguably what we *want* to preserve (historical truth of what the label was when the post was created).
- If concrete analytics demands ID-stable joins later, a non-breaking follow-up migration can add the column. Ship small now, extend if motivated.

**Alternative considered:** Compute `city_name` at read time via `LEFT JOIN admin_regions ON ST_Contains(geom, p.actual_location)`. Rejected — forces the Global endpoint to read `actual_location` out of `visible_posts`, which is an unacceptable broadening of the coordinate-jitter invariant (`actual_location` gets projected into business-query rows, where the next lint slip exposes it to clients). Also breaks the spatial budget at p95.

**Alternative considered:** Materialized view (`CREATE MATERIALIZED VIEW global_posts AS ...`) refreshed periodically. Rejected — refresh cadence is a new operational knob, and incremental refresh on a chronological feed would need trigger-based invalidation anyway (back to Decision 2 by another path).

**Alternative considered:** Application-layer reverse-geocode in `PostService.createPost()` before the INSERT (read `admin_regions`, compute, pass into the INSERT columns). Rejected — the trigger is simpler, race-free, and the only place `actual_location` is used; keeping the geocoding in SQL means no additional application reader of `actual_location` (`CoordinateJitterRule` allowlist stays tight).

**Alternative considered:** Single-step `ST_Contains` only (drop steps 2–4). Rejected during the post-proposal reconciliation pass — Indonesia is an archipelago, and canonical docs explicitly prescribe the 4-step fallback to handle coastal points, boundary artifacts, and polygon-coverage gaps. Shipping single-step would systematically NULL-out thousands of legitimate posts; see [`FOLLOW_UPS.md`](../../../FOLLOW_UPS.md) for the incident write-up.

**Alternative considered:** Redis `geocode:{geocell:<lat2dp>_<lng2dp>}` cache (per [`docs/02-Product.md:205`](docs/02-Product.md)). Deferred — the 4-step ladder is 1–5ms at current write rate. Redis cache makes sense at scale; its wiring belongs with the broader Redis rate-limit infrastructure change (Phase 1 item 24), not with this change.

### Decision 3: Ship `admin_regions` schema + trigger in V11, seed in V12 (amended)

**Status: amended during Session 2.** Original decision required a single V11 migration containing schema + seed + trigger + view. Amended split: V11 ships schema + trigger + view (empty table), V12 ships the ~552-row polygon seed generated by the offline import pipeline. See "Why the amendment" below.

**V11__admin_regions.sql** (shipped in Session 1 feat PR #29, merged 2026-04-25):
1. `CREATE TABLE admin_regions (...)` exactly per [`docs/05-Implementation.md:1007–1017`](docs/05-Implementation.md).
2. GIST + btree indexes.
3. `CREATE OR REPLACE FUNCTION posts_set_city_fn() RETURNS TRIGGER ...` implementing the 4-step fallback (Decision 2).
4. `CREATE TRIGGER posts_set_city_tg BEFORE INSERT ON posts FOR EACH ROW EXECUTE FUNCTION posts_set_city_fn()`.
5. `CREATE OR REPLACE VIEW visible_posts AS SELECT * FROM posts WHERE is_auto_hidden = FALSE` — idempotent no-op refresh.

**V12__admin_regions_seed.sql** (Session 2):
1. 552 `INSERT INTO admin_regions` rows (38 provinces + 514 kabupaten/kota) sorted provinces-first for FK order.
2. Coastal kabupaten polygons carry the 12nm maritime buffer baked into `geom` per Decision 4a (48 of 514 applied at import time).
3. IDs = stable OSM relation IDs per Decision 8.

**No `ALTER TABLE posts`** in either migration. The target columns (`city_name`, `city_match_type`) already exist on `posts` from V4.

**Why the amendment.** The original "single migration" argument was about preventing a window where `INSERT INTO posts` would fail or produce semantically-wrong rows because `admin_regions` is empty. That concern turned out to be over-weighted in hindsight:
- The trigger's 4-step fallback ladder short-circuits gracefully when `admin_regions` is empty (step 1–3 all return zero rows; step 4 leaves `city_name` NULL).
- The Global/Nearby/Following response DTO layer was already designed NULL-tolerant (`city_name` serializes as `""` for any NULL underlying value), so a V11-only deployment produces functional endpoints with empty `city_name` labels — semantically degraded but not broken.
- The original concern "breaks local dev that applies migrations one at a time" is a one-time dev-loop annoyance, not a prod risk.

Given those mitigations, splitting V11 (schema, small, reviewable quickly) from V12 (33 MB seed, needs offline dataset prep) let the change ship via two parallel-reviewable PRs instead of one blocked-on-dataset PR. The V11 feat PR merged the code-and-schema slice to `main` while the dataset was being prepared; V12 follows-up with the seed-only append. Net: faster end-to-end delivery, smaller individual diffs, same final state on `main`.

**Alternative considered (original, now rejected):** One monolithic V11. Rejected because it blocks the Global endpoint / lint / DTO work behind dataset acquisition — the non-trivial offline step. The original rationale (avoid empty-table window) is addressed by the NULL-tolerance design.

**Alternative considered:** `R__admin_regions_seed.sql` repeatable migration. Rejected — the project has no `R__` precedent, and introducing one for one reference table is scope creep. Future polygon updates land as new versioned migrations (V13+) for full diff auditability.

**Alternative considered:** Seed data loaded via a separate Cloud Run Job post-migration. Rejected — introduces a deploy-ordering dependency the trigger doesn't tolerate (any INSERT between "Flyway V12 done" and "job done" would get NULL city fields — same concern as the original V11-V12 split but worse because Cloud Run Jobs aren't sequenced with migrations).

The V12 file is ~33 MB (ST_SimplifyPreserveTopology at 5.5 m tolerance brings raw OSM down from ~72 MB; 6-digit `ST_AsText` precision halves it again). Over the original 15–25 MB estimate but acceptable — Flyway + GitHub handle it, and this is a one-time cost; polygon refreshes land as new versioned migrations.

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

Use INT (small, ~540 rows). Seeded IDs come from the dataset's stable code (BPS `kode_wilayah` for BPS, OSM relation ID for OSM). The exact mapping is in the migration header. Once committed, IDs MUST NOT change across re-seeds — even though `posts` holds no FK to `admin_regions` (per Decision 2), the `parent_id` self-FK within `admin_regions` depends on stable IDs, and any future analytics change that wants to re-introduce a posts→regions FK gets broken by ID churn.

If a re-seed renames a region (e.g., "Kabupaten Nagan Raya" → "Nagan Raya" stylistic fix), update the row in place (`UPDATE admin_regions SET name = ... WHERE id = ...`). Existing `posts.city_name` snapshots stay frozen until the optional backfill job re-runs — that's the deliberate tradeoff in Decision 2.

### Decision 4a: Maritime 12nm buffer applied during import, not at query time

Per [`docs/02-Product.md:200–203`](docs/02-Product.md): "Points at sea within 12 nautical miles of a coastal kabupaten's shoreline are assigned to that kabupaten. Buffer coastal kabupaten polygons by 12 nautical miles (~22km) maritime extension in the import script."

This is a **data-engineering concern, not a query concern.** The import / dataset-prep step identifies coastal kabupaten polygons and applies `ST_Buffer(geom::geometry, <22km in degrees>)` (or equivalent geography-space buffer) before inserting. At query time, the trigger's 4-step ladder operates on the already-buffered polygons — step 1 (`ST_Contains`) matches posts in nearshore waters as if they were inside land.

Posts in the EEZ (beyond ~22 km from any coast) do NOT match step 1. Step 3 (50km nearest neighbor) catches most of them; posts in the open ocean / international waters beyond 50 km legitimately fall to step 4 (both NULL). Per product spec, those render as "Indonesia" or "Luar Indonesia" at the UI layer.

**Alternative considered:** Apply the 12nm buffer at query time (`ST_DWithin(geom, actual_location, 22000)` as a step 1.5). Rejected — doubles the query work per INSERT and conflates "inside buffer" with "on land" in provenance. Import-time buffering keeps the trigger's step semantics clean (step 1 match = inside the authoritative boundary, including maritime extension).

**Alternative considered:** Ship without maritime buffering, rely on step 3 for all coastal posts. Rejected — coastal Jakarta posts would routinely fall to `fuzzy_match` even when the post is 500m offshore in Teluk Jakarta, which is UX-wrong (they clearly belong to Jakarta Utara, not fuzzy-matched by distance). Buffering at import is the right layer.

### Decision 9: No guest-access path in this change

Guest read of Global requires:
- Guest JWT issuance flow (`docs/05-Implementation.md:1646–1655`) with attestation + pre-issuance rate limits.
- Redis counters for the 10/session + 30/hour caps (`docs/01-Business.md` guest read policy).
- A separate auth middleware branch that admits guest JWTs only on `/timeline/global`.

Shipping all three in one vertical doubles the change footprint and drags in Redis client wiring that no existing endpoint needs. Scope it out — the follow-up rate-limit change is the right home.

The Global endpoint in this change rejects missing `Authorization` headers with HTTP 401 `unauthenticated`, same as Nearby and Following. Product-visible consequence: until the guest change ships, the Indonesia-wide feed requires sign-in. That matches the Ktor + mobile dev experience already in place (no guest UI yet, no guest token yet).

## Risks / Trade-offs

- **Risk:** Polygon coverage gaps produce step-4 NULL `city_name` on some legitimate posts (deep ocean, remote EEZ, reclaimed land not yet in the dataset). → **Mitigation:** Response spec tolerates NULL (serializes as empty string). The optional backfill job re-computes on polygon re-seed. Ops monitoring via `SELECT city_match_type, COUNT(*) FROM posts WHERE created_at > :last_deploy GROUP BY city_match_type` surfaces rising fuzzy/NULL rates — that's exactly what `city_match_type` provenance is for.
- **Risk:** Trigger slows down `INSERT INTO posts` by 1–5 ms (4-step ladder across GIST-indexed MULTIPOLYGON). → **Mitigation:** 1–5 ms on the post-creation path is acceptable (current p95 budget is far larger). Benchmarked as part of Phase 2 item 14 dataset work. Step 1 short-circuits in the 95%+ strict-match case; steps 2–4 only run on the minority.
- **Risk:** Polygon dataset licensing disclosure is missed. → **Mitigation:** Migration header carries the license line; app legal section (`docs/01-Business.md` privacy policy checklist) adds the attribution line pre-launch. Tracked as a task.
- **Risk:** Nearby/Following response shape change (additive `city_name`) surprises a client that uses strict decoding. → **Mitigation:** `:mobile:app` uses permissive kotlinx.serialization config. No prod mobile client is shipped yet (Phase 3). Backend integration tests cover the new shape. Document the change in the PR description.
- **Risk:** `admin_regions` IDs drift across re-seeds (e.g., someone uses a new dataset with different codes). → **Mitigation:** Decision 8 explicitly fixes IDs at the dataset's stable code (BPS `kode_wilayah` / OSM relation ID). Migration header documents the rule. Re-seeds MUST UPDATE by ID, not DELETE + INSERT.
- **Risk:** Trigger conflict with future `posts` write paths (e.g., admin backfill, bulk import). → **Mitigation:** Trigger's first check is `IF NEW.city_name IS NOT NULL THEN RETURN NEW` — callers that explicitly supply `city_name` short-circuit the 4-step ladder. Bulk imports and backfill jobs can either (a) supply `city_name` + `city_match_type` explicitly (trigger no-ops), or (b) run the 4-step SQL manually in their `UPDATE`. Design pattern matches V3's `reserved_usernames_protect_seed_trigger`.
- **Risk:** Very large migration file (~15–25 MB WKT) triggers repo-size concerns or CI diff timeouts. → **Mitigation:** One-time cost. GitHub handles; CI `git diff` is unaffected because migration diffs are not reviewed line-by-line. Future polygon updates land as new versioned migrations — no edit churn on V11.
- **Risk:** A polygon-coverage gap beyond 50 km (step-4 NULL) misleads users who expect every post to carry a label. → **Mitigation:** Product-acceptable per [`docs/02-Product.md:196`](docs/02-Product.md) — UI substitutes "Indonesia" / "Luar Indonesia" at the render layer. Response spec renders `""` (DB NULL → JSON empty string); client handles the substitution.
- **Risk:** `city_match_type` values diverge from the enum vocabulary if a refresh migration adds a new step. → **Mitigation:** V11 locks the 4-value enum (`strict` / `buffered_10m` / `fuzzy_match` / NULL). Adding a step means amending `docs/02-Product.md` first (canonical), then proposing a change that bumps the enum with a CHECK constraint. No ad-hoc values.

## Migration Plan

1. **Dataset prep (pre-migration authoring):** Pull BPS kabupaten/kota GeoJSON (or OSM fallback). Apply the 12nm (~22km) maritime buffer to coastal kabupaten polygons via the import script (see Decision 4a). Convert to SQL INSERT statements with `ST_GeomFromGeoJSON(...)` literals. Hand-curate the 5 DKI kotamadya + Kepulauan Seribu rows. Verify row count (~540) and that `ST_IsValid(geom)` returns TRUE for every row (including the buffered ones). Commit the generated SQL into `V11__admin_regions.sql`.
2. **Flyway V11 applies:** Creates `admin_regions`, seeds it (with buffered polygons), creates the trigger + function, idempotent refresh of `visible_posts`. Fully additive — no `ALTER TABLE posts`, no downtime, no lock on existing reads. Flyway wraps in a transaction.
3. **Backend deploy:** `GlobalTimelineService`, `TimelineRoutes` registration for `/timeline/global`, DTO `city_name: String` field added to `Post` (+ `NearbyTimelineService` + `FollowingTimelineService` SELECT lists extended to project `city_name`). Deploys after Flyway V11 completes. Container startup is unchanged.
4. **Backfill (optional):** `backend/ktor/.../tools/BackfillPostCityJob.kt` — a one-shot manually-invoked job that runs the same 4-step ladder as the trigger across every `posts` row where `city_name IS NULL`. The SQL is a `WITH matched AS (... 4 UNION-ALL'd ladder steps ...) UPDATE posts SET city_name = m.name, city_match_type = m.match_type FROM matched m WHERE posts.id = m.id`. Safe to run at any time. NOT required for this change to ship. Tracked as an optional task; defer until the Global feed has enough legacy posts to make the UX difference visible (~post-soft-launch).
5. **Rollback:** Revert the Ktor deploy first; Global endpoint disappears. V11 stays — it's additive; no other code paths break with the new trigger present (Nearby + Following tolerate NULL `city_name` just like Global). If V11 must also be reverted (unlikely in prod; possible in dev): drop the trigger + function, drop `admin_regions`. `posts` schema and `visible_posts` view are unchanged, so no revert work there. Pre-revert snapshot: `pg_dump` the `admin_regions` table so the dataset is preserved.
6. **Doc sync:** Update `docs/02-Product.md` §3 (Global timeline shipped), `docs/08-Roadmap-Risk.md` Phase 1 item 15 + Phase 2 item 2, `docs/05-Implementation.md` §"Timeline Implementation" (Global query added). Land docs in the archive PR per the change delivery workflow in `openspec/project.md`.

## Open Questions

1. **BPS vs OSM kabupaten/kota dataset — final source.** ✅ **Resolved — OSM** (`admin_level = 5` for kabupaten/kota + `admin_level = 6` for DKI kotamadya, pulled via Overpass API). Default-BPS was overridden after weighing (a) availability risk — BPS distribution of kabupaten/kota MULTIPOLYGON GeoJSON is historically spotty and would block the migration; OSM Overpass is reliable and reproducible; (b) the product use case — `city_name` is a display label on a social feed, so millimeter-accurate legal-boundary purity is overrated, and the 4-step fallback ladder + 12nm maritime buffer already tolerate imprecision; (c) ID stability — OSM relation IDs are inherently stable and re-queryable, whereas BPS `kode_wilayah` embedding varies by vendor file; (d) ODbL share-alike does NOT cascade to this project's use (we project `city_name` strings into API responses, not redistribute polygon data as a derived database). Attribution string locked to: **"Administrative boundaries © OpenStreetMap contributors, available under the Open Database License (ODbL)."** That string goes in the V11 header (task 2.1) and the app's legal section (`docs/01-Business.md` privacy/legal checklist). DKI Jakarta's 5 kotamadya + Kepulauan Seribu come from a second Overpass query at `admin_level = 6` inside the DKI province polygon, per task 1.3. See [`dev/scripts/import-admin-regions/README.md`](../../../dev/scripts/import-admin-regions/README.md) for the reproducible import pipeline.
2. **Backfill job — ship in this change, or defer to a follow-up?** Current: the optional backfill is noted in `tasks.md` but unchecked; shipping the code path is 20 minutes of work but it is unexercised until a data event triggers it. Lean toward shipping the code + one integration test behind a manual-trigger CLI flag (`./gradlew :backend:ktor:run --args="backfill-post-city"`), and leaving the actual prod run for post-soft-launch. Defer final call to the implementer.
3. **Response spec — `city_name: ""` vs `city_name: null` for NULL DB value.** Pick one and lock it across all three timelines. Default: empty string `""` (avoids mobile-side null-handling branching). Confirm during spec review.
4. **Should `admin_regions` rows carry `province_name` denormalized, to avoid a JOIN when a future endpoint wants "Jakarta Selatan, DKI Jakarta"?** Current: NO — the `parent_id` FK is enough; name resolution is a single self-join. Revisit if product UX demands "city, province" as the Global label. Out of scope here.
5. **The Nearby + Following shape change (additive `city_name`) — is this a "modified" spec or just a test-coverage update?** Current: modified spec, documented in the spec deltas for `nearby-timeline` and `following-timeline`. No behavior loss; purely additive field.
