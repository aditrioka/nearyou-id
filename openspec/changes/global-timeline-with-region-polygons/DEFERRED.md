# Deferred tasks — `global-timeline-with-region-polygons`

Tasks explicitly deferred from Session 1. Each entry lists the blocker and the trigger that unblocks it, so future sessions know when (and how) to pick it up. Tasks are identified by the numeric IDs in [`tasks.md`](tasks.md).

## Session 1 scope (what IS being done)

For reference, Session 1 implements these tasks: **2.2, 2.3, 2.5, 2.6, 2.7, 3.1–3.10, 4.1–4.7, 5.1, 5.2, 7.1–7.5** (Global service + route + repo + DTOs + Nearby/Following `city_name` projection + lint KDoc/fixture updates + V11 DDL skeleton + local verification).

---

## Deferred

### Cluster A — Dataset acquisition (tasks 1.1 – 1.5) — ✅ RESOLVED

| Task | Status |
|------|--------|
| 1.1 Decide BPS vs OSM | ✅ **OSM.** Rationale + attribution string locked in `design.md` Open Question 1. |
| 1.2 Download source GeoJSON | ✅ Session 2 ran the scaffold's `fetch-overpass.sh` against `area:3600304751` (Indonesia, verified). 39 level-4 relations + 516 level-5 relations fetched. |
| 1.3 Hand-curate DKI 5 kotamadya + Kepulauan Seribu | ✅ OSM models all 6 DKI children (5 kotamadya + Kepulauan Seribu) at `admin_level = 5` alongside every other kabupaten/kota — no DKI-specific query needed; all 6 present in the level-5 set. |
| 1.3.5 12nm maritime buffer on coastal kab | ✅ Applied at import time by `generate-seed.py` via `ST_Buffer(geom::geometry, 0.198°)` on rows whose centroid is within 50 km of `ST_Boundary(ST_Union(provinces::geometry))`. 48 of 514 kabupaten/kota buffered. |
| 1.4 Convert GeoJSON → SQL | ✅ `generate-seed.py` stages to Postgres, runs ST_MakeValid + buffer + ST_SimplifyPreserveTopology (5.5m tolerance) + 6-decimal ST_AsText, emits 552 sorted INSERTs to `backend/ktor/src/main/resources/db/migration/V12__admin_regions_seed.sql` (~33 MB). |
| 1.5 ST_IsValid(geom) = TRUE per row | ✅ 0 invalid after the pipeline's ST_MakeValid pass. A final post-INSERT sweep in V12 (`ST_Multi(ST_CollectionExtract(ST_MakeValid(...), 3))`) handles 3 rows that tip into self-intersection on precision=6 WKT round-trip. |

**One filter worth noting:** one level-4 OSM relation (`Ngawi`, relation 20407794) is mis-tagged as a province — its `ref:ID:kemendagri` code is `35.21` (4-digit dotted = kabupaten) instead of `35` (2-digit = province). The scaffold filters level-4 with dotted kemendagri. See `dev/scripts/import-admin-regions/generate-seed.py` `_is_misplaced_province()`.

---

### Cluster B — Seed-dependent V11/V12 pieces (tasks 2.1, 2.4, 2.9 – 2.11) — ✅ RESOLVED

| Task | Status |
|------|--------|
| 2.1 License + attribution header | ✅ Shipped in V12 header (not V11 — see Decision 3 amendment). ODbL attribution string present, source URL + fetch date + snapshot notes + Ngawi filter + validity sweep all documented. |
| 2.4 INSERT province + kabupaten/kota seed | ✅ 552 INSERTs (38 provinces + 514 kabupaten/kota) in `V12__admin_regions_seed.sql`. Coastal kab carry the 22km buffer in `geom`. IDs = OSM relation IDs per Decision 8. |
| 2.8 Add V11 to `CoordinateJitterRule` allowlist | Rule does not exist in the repo (see Cluster C). | Cluster C done.
| 2.9 `flywayMigrate` + verify row count ≥ 540 | ✅ V12 applies cleanly in a cold migrate and via test-bootstrap. Row count = 552 (≥ 540). |
| 2.10 `MigrationV12SmokeTest` polygon scenarios | ✅ `backend/ktor/src/test/kotlin/id/nearyou/app/infra/db/MigrationV12SmokeTest.kt` covers all scenarios: V12 applied + row counts, ST_IsValid=TRUE for every row, all 6 DKI kotamadya present, every kab/kota has non-null parent, trigger step-1 strict matches for Jakarta Selatan + Kota Bandung, trigger step-4 NULL for deep-ocean posts, caller-override short-circuit, flyway_schema_history row for V12, ODbL attribution string in the migration file. |
| 2.11 Green test run | ✅ 10/10 MigrationV12SmokeTest cases green. Full suite: 384 tests / 0 failures / 0 errors. |

**Design Decision 3 amendment.** The original "single V11 migration" approach was amended during Session 2 to document the schema/seed split (V11 = schema + trigger + view, V12 = seed). The V11-without-seed window was safe because the trigger + response DTOs are both NULL-tolerant. See `design.md` Decision 3 "Status: amended during Session 2" for the full rationale.

**2.8 remains deferred** — it's a `CoordinateJitterRule` concern in Cluster C, not Cluster B. The allowlist update rides with whichever change creates that rule.

---

### Cluster C — `CoordinateJitterRule` Detekt rule (tasks 5.3, 5.4)

| Task | Blocker | Unblock trigger |
|------|---------|-----------------|
| 5.3 Update CoordinateJitterRule allowlist to include V11; add KDoc note about the trigger's sanctioned `actual_location` read | Rule does not exist. `lint/detekt-rules/` currently hosts only `BlockExclusionJoinRule` + `RawFromPostsRule`. | A new change `rule(coordinate-jitter-lint)` creates the rule (~200 LOC + tests + registration in `NearYouRuleSetProvider`). |
| 5.4 Test fixture: rule fires on non-allowlisted migration referencing `actual_location` | Depends on 5.3. | 5.3 done. |

**Scope note:** Creating `CoordinateJitterRule` belongs in its own OpenSpec change, not folded into Global-timeline — it's a net-new capability that fences spatial reads across the entire codebase, not just a Global-timeline concern. Follow-up change names to consider: `rule(coordinate-jitter-lint)` or extend the `coordinate-jitter` capability's spec with Detekt-rule requirements.

**When to revisit:** after Global-timeline ships. A separate `/next-change` cycle produces the rule.

---

### Cluster D — Optional backfill (tasks 6.1 – 6.3)

| Task | Blocker | Unblock trigger |
|------|---------|-----------------|
| 6.1 `BackfillPostCityJob.kt` (manual CLI trigger) | Explicitly marked `(optional)` in `tasks.md`. With `admin_regions` empty (pre-Cluster A), the UPDATE produces zero changes — dead code path. | Cluster A done AND a soft-launch data event creates enough legacy NULL posts to make the UX difference visible. |
| 6.2 Backfill idempotency integration test | Depends on 6.1. | 6.1 implemented. |
| 6.3 Defer prod run until post-soft-launch | This task itself is a "defer" statement; not implementation work. | N/A — informational. |

**Design.md alignment:** Decision 2 explicitly flags backfill as optional and post-soft-launch. Ship with NULL-tolerance in the response DTO; only implement the backfill code when the data reality demands it.

**When to revisit:** post-soft-launch (Month 1+ after public launch per `docs/08-Roadmap-Risk.md`).

---

### Cluster E — Doc sync (tasks 7.6 – 7.8)

| Task | Blocker | Unblock trigger |
|------|---------|-----------------|
| 7.6 Update `docs/08-Roadmap-Risk.md` (Phase 1 item 15 → shipped, Phase 2 item 2 → shipped, Open Decision #4 → resolved) | Workflow convention: docs are marked "shipped" only after feat PR merged + staging deploy green. | Feat PR merged + staging deploy green. Typically handled in the archive PR, not the feat PR. |
| 7.7 Update `docs/02-Product.md` §3 (Global timeline shipped; guest access still deferred) | Same as 7.6. | Same as 7.6. |
| 7.8 Update `docs/05-Implementation.md` § Timeline Implementation with canonical Global query verbatim + V11 notes | Same as 7.6. | Same as 7.6. |

**Workflow reference:** `openspec/project.md` § Change Delivery Workflow explicitly says archive PR is the home for doc sync; feat PR ships code + spec deltas only.

**When to revisit:** in the `/opsx:archive global-timeline-with-region-polygons` run after the feat PR merges and staging reports green.

---

## Revisit checklist

When any deferred cluster is about to be picked up, check these in order:

1. Re-read this file and the relevant `tasks.md` lines to confirm the blocker is actually resolved.
2. Re-read `design.md` — if the deferral required a design amendment (e.g., splitting V11), verify the amendment happened.
3. Run `openspec validate global-timeline-with-region-polygons --strict` before committing to confirm the artifact set is still coherent.
4. Update the ✅ / ⏸ in `tasks.md` as work lands.
