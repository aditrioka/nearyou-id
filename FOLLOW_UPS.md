# Follow-ups

Short-lived working document. Tracks items surfaced during a change or a review that must be addressed but are out-of-scope for the change where they were discovered. Entries are **deleted** from this file when their action items are all merged. When the file is empty, **delete the file itself** — it should not exist in steady state.

**Not** a lessons log or permanent knowledge base. Permanent knowledge belongs in [`docs/`](docs/), [`openspec/specs/`](openspec/specs/), [`openspec/project.md`](openspec/project.md), or [`CLAUDE.md`](CLAUDE.md). Git log + commit messages that reference this file when closing entries serve as the audit trail after deletion.

## Format

```markdown
## <incident-slug> — <YYYY-MM-DD>
**Observed:** what happened / what was discovered, in 1–2 sentences.
**Root cause:** why it happened, in 1–2 sentences.
**Action items:**
- [ ] Concrete change 1 (cite target file or scope)
- [ ] Concrete change 2
- [ ] ...
**Close criteria:** delete this entry when all checkboxes are merged.
**Related PRs:** (filled in as PRs land — e.g., `#18 (skill update)`, `#19 (proposal amend)`)
```

---

## global-timeline-proposal-divergent-from-canonical-reverse-geocode — 2026-04-23

**Observed:** The [`global-timeline-with-region-polygons`](openspec/changes/global-timeline-with-region-polygons/) proposal (merged via [#15](https://github.com/aditrioka/nearyou-id/pull/15)) diverged from the canonical docs in three ways: (1) specced a single `ST_Contains` trigger, while [`docs/02-Product.md`](docs/02-Product.md) §"Polygon-Based Reverse Geocoding" (lines 179–205) prescribes a 4-step fallback ladder (strict → buffered 10m → fuzzy 50km → NULL); (2) proposed adding a new `city_admin_region_id INT` FK column that the canonical schema ([`docs/05-Implementation.md`](docs/05-Implementation.md) §Posts Schema, lines 553–567) does not include; (3) did not reference the existing `posts.city_match_type VARCHAR(16)` column (match-provenance tag) that V4 already ships.

**Root cause:** Three contributing factors — (a) the `/next-change` skill at the time had no reconciliation pass between the scaffolded proposal and canonical docs (it trusted that "read docs" in Phase A was thorough enough); (b) the qodo reviewer on the proposal PR was generic, not CLAUDE.md-aware (CLAUDE.md merged *after* the proposal — [#17](https://github.com/aditrioka/nearyou-id/pull/17) at 17:58 UTC vs proposal at 17:38 UTC, 20 min too late); (c) the author (me, Claude) skimmed `docs/02-Product.md` §3 enough to identify "Global needs admin_regions" but moved on to draft the proposal before re-reading the adjacent §"Polygon-Based Reverse Geocoding" for its algorithmic details.

**Action items:**
- [x] Update `.claude/skills/next-change/SKILL.md`: add Phase B.5 reconciliation pass (diff proposal claims against canonical docs before pushing) + FOLLOW_UPS.md convention bullet in Notes.
- [x] Update [`CLAUDE.md`](CLAUDE.md) "Reviewing a PR" section: add explicit "reconcile proposals against canonical docs" rule for `docs(openspec): propose` reviews.
- [ ] Amend the `global-timeline-with-region-polygons` proposal on its feat branch (`proposal.md` + `design.md` + `specs/region-polygons/spec.md` + `specs/post-creation/spec.md` + `specs/users-schema/spec.md` + `specs/visible-posts-view/spec.md`) to reflect Option A: drop `city_admin_region_id` FK, add `city_match_type` semantics, trigger body = 4-step fallback ladder, keep maritime buffering as dataset-prep concern, keep Redis geocode cache as deferred.
- [ ] Run `openspec validate global-timeline-with-region-polygons --strict` after amendment.
- [ ] Post-amendment docs/ audit: scan [`docs/02-Product.md`](docs/02-Product.md) + [`docs/05-Implementation.md`](docs/05-Implementation.md) for any other areas where proposals authored in the last 2 weeks may have diverged (especially around chat, search, moderation — similarly detailed domain sections). File separate FOLLOW_UPS entries if found.

**Close criteria:** delete this entry when all 5 checkboxes above are merged.

**Related PRs:**
- (will be filled in as PRs land)
