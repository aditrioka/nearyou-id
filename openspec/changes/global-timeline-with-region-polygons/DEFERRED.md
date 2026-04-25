# Deferred tasks — `global-timeline-with-region-polygons`

Tasks explicitly deferred from Session 1. Each entry lists the blocker and the trigger that unblocks it, so future sessions know when (and how) to pick it up. Tasks are identified by the numeric IDs in [`tasks.md`](tasks.md).

## Session 1 scope (what IS being done)

For reference, Session 1 implements these tasks: **2.2, 2.3, 2.5, 2.6, 2.7, 3.1–3.10, 4.1–4.7, 5.1, 5.2, 7.1–7.5** (Global service + route + repo + DTOs + Nearby/Following `city_name` projection + lint KDoc/fixture updates + V11 DDL skeleton + local verification).

---

## Deferred

### Cluster A — Dataset acquisition (tasks 1.1 – 1.5)

| Task | Status / Blocker | Unblock trigger |
|------|------------------|-----------------|
| 1.1 Decide BPS vs OSM | ✅ **Resolved — OSM.** Rationale + attribution string locked in `design.md` Open Question 1. | Done. |
| 1.2 Download source GeoJSON | Unblocked by 1.1. Reproducible via the Overpass pipeline in [`dev/scripts/import-admin-regions/`](../../../dev/scripts/import-admin-regions/) — no account/auth required. Output ~15–25 MB, fetched in ~5–10 min. | Run the scaffold's `fetch-overpass.sh`; commit the generated SQL into V11. |
| 1.3 Hand-curate DKI 5 kotamadya + Kepulauan Seribu | Automated in the scaffold via a second Overpass query at `admin_level = 6` inside DKI's polygon (no manual polygon-splitting needed — OSM has them as distinct relations). | 1.2 done. |
| 1.4 Convert GeoJSON → SQL `ST_GeomFromGeoJSON` | Covered by `generate-seed.py` in the scaffold: stages to local Postgres, applies `ST_MakeValid`, applies 12nm buffer to coastal kabupaten, emits sorted `INSERT` block (provinces first for FK order). | 1.2 done. |
| 1.5 Validate `ST_IsValid(geom)` per row | Done by the scaffold during staging (invalid → `ST_MakeValid` fixup; fixups logged inline in the generated SQL comment block). Final `SELECT COUNT(*) FROM admin_regions WHERE NOT ST_IsValid(geom::geometry)` asserted = 0 before emitting SQL. | 1.4 done. |

**When to revisit:** Session 2 — run the scaffold end-to-end, commit generated SQL into V11 (or V12 if V11 already merged without seed).

---

### Cluster B — Seed-dependent V11 pieces (tasks 2.1, 2.4, 2.8 – 2.11)

| Task | Blocker | Unblock trigger |
|------|---------|-----------------|
| 2.1 V11 header (license + attribution text) | Depends on 1.1 (source decision). | 1.1 done. Amend header of V11 before first push. |
| 2.4 INSERT province + kabupaten/kota seed | Depends on 1.4 (converted SQL). | 1.4 done. Paste seed block into V11 between DDL and trigger creation. |
| 2.8 Add V11 to `CoordinateJitterRule` allowlist | Rule does not exist in the repo (see Cluster C). | Cluster C done. |
| 2.9 Run `flywayMigrate` + verify row count ≥ 540 | Schema-only migration applies cleanly, but the row-count assertion fails without seed. | Cluster A done. |
| 2.10 `MigrationV11SmokeTest` (polygon-dependent scenarios) | Scenarios that depend on seeded polygons: "all `ST_IsValid`", "DKI 5 kotamadya + Kepulauan Seribu present at `kabupaten_kota` level", "trigger populates city inside polygon". | Cluster A done; extend the test class in Session 2. |
| 2.11 Execute the polygon-dependent scenarios | Depends on 2.10 being green. | 2.10 done. |

**Note on design alignment:** `design.md` Decision 3 explicitly rejected splitting V11 into schema + seed migrations. Session 1 writes V11 DDL now AND keeps the branch unpushed until Cluster A completes, so `admin_regions` never ships empty to `main`. If timeline pressure forces a schema-only ship, amend `design.md` Decision 3 in the same commit.

**When to revisit:** Session 2, after Cluster A.

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
