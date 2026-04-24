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
- [x] Post-amendment docs/ audit: scan [`docs/02-Product.md`](docs/02-Product.md) + [`docs/05-Implementation.md`](docs/05-Implementation.md) for any other areas where proposals authored in the last 2 weeks may have diverged (especially around chat, search, moderation — similarly detailed domain sections). File separate FOLLOW_UPS entries if found. **Done 2026-04-24 — Explore-agent audit covered V5–V10. Three divergences found in V10 in-app notifications body_data; filed as separate entry [`v10-notifications-body-data-missing-post-id`](#v10-notifications-body-data-missing-post-id--2026-04-24) below. V5–V9 all clean.**

**Close criteria:** delete this entry when all 5 checkboxes above are merged.

**Related PRs:**
- [#18](https://github.com/aditrioka/nearyou-id/pull/18) (skill + CLAUDE.md + FOLLOW_UPS convention, merged)
- [#19](https://github.com/aditrioka/nearyou-id/pull/19) (proposal amendment, open)

---

## v10-notifications-body-data-missing-post-id — 2026-04-24

**Observed:** Three V10 in-app notification `body_data` shapes in shipped specs omit the `post_id` field prescribed by the canonical event catalog at [`docs/05-Implementation.md:851–867`](docs/05-Implementation.md):

| Type | Canonical body_data | V10 shipped body_data | Missing |
|---|---|---|---|
| `post_liked` | `{post_id, post_excerpt}` | `{post_excerpt}` | `post_id` |
| `post_replied` | `{post_id, reply_excerpt}` | `{reply_id, reply_excerpt}` | `post_id` (extra: `reply_id`) |
| `post_auto_hidden` | `{post_id, reason}` | `{reason}` | `post_id` |

Specs at fault:
- [`openspec/changes/archive/2026-04-23-in-app-notifications/specs/post-likes/spec.md:5`](openspec/changes/archive/2026-04-23-in-app-notifications/specs/post-likes/spec.md)
- [`openspec/changes/archive/2026-04-23-in-app-notifications/specs/post-replies/spec.md:5`](openspec/changes/archive/2026-04-23-in-app-notifications/specs/post-replies/spec.md)
- [`openspec/changes/archive/2026-04-23-in-app-notifications/specs/reports/spec.md:5`](openspec/changes/archive/2026-04-23-in-app-notifications/specs/reports/spec.md)
- [`openspec/changes/archive/2026-04-23-in-app-notifications/specs/in-app-notifications/spec.md:217, :218, :238`](openspec/changes/archive/2026-04-23-in-app-notifications/specs/in-app-notifications/spec.md)
- Same omissions likely present in the current authoritative specs at [`openspec/specs/in-app-notifications/spec.md`](openspec/specs/in-app-notifications/spec.md) (will be verified in action item 2).

**Root cause:** Same pattern as the global-timeline incident — proposal authored without tight reconciliation against the canonical docs/05 event catalog; shipped via [#12](https://github.com/aditrioka/nearyou-id/pull/12), archive via [#13](https://github.com/aditrioka/nearyou-id/pull/13), both before the reconciliation-pass workflow existed. This is the **first confirmed spillover** of the pre-reconciliation era.

**Impact (if shipped):** Mobile client cannot deterministically deep-link to the notification target without a second query. Outer `notifications.target_id` is populated, so the practical workaround exists — but the body_data contract is violated, and future notification features that rely on richer `post_id` access in body_data won't work.

**Ambiguity to resolve first:** Is the canonical `docs/05` catalog over-specified, or is the shipped `body_data` under-specified? Mobile client today uses `target_id` from the outer row for deep-linking and hasn't complained (no mobile client shipped yet, actually). Either direction is a legit fix:
- **(Direction X) Amend shipped code + spec to add `post_id`** — honors canonical docs as-is. No migration needed (JSONB is additive; reading code tolerates missing key).
- **(Direction Y) Amend `docs/05` to drop `post_id` from body_data** — concede the catalog was over-specified because `target_id` suffices. Docs update only.

**Action items:**
- [ ] Triage X vs Y with user (mobile UX needs). If unclear, default to X (honor canonical; cheaper to amend code than to drop provenance from docs).
- [ ] If X: update emit helper in `backend/ktor/src/main/kotlin/id/nearyou/app/notification/` (search for `post_liked` / `post_replied` / `post_auto_hidden` emitters) to include `post_id` in JSONB body. Integration test asserts presence.
- [ ] If X: update 4 spec files listed above to reflect the corrected body_data shape.
- [ ] If X: verify current `openspec/specs/in-app-notifications/spec.md` (not the archive) — amend there so the authoritative spec carries the canonical shape going forward.
- [ ] If Y: amend `docs/05-Implementation.md:851–867` to drop `post_id` from the three types. No code or spec change.
- [ ] No DB migration in either direction — `body_data` is JSONB, keys are forward-compatible.

**Close criteria:** delete this entry when direction is chosen AND all chosen-path action items are merged.

**Related PRs:**
- (TBD — depends on X vs Y triage)
