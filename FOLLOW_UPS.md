# Follow-ups

Transient working file for findings discovered during a change cycle that are NOT in scope of the current change but need a tracked owner. Per repo convention:

- Add an entry when a finding is real, fixable, but should NOT be silently swept into the current change's scope.
- Tick the action-item checkboxes as they are completed.
- Delete the entry once all its action items are merged.
- Delete the file itself when it has zero entries left.
- Recreate the file (with this same intro blurb) the next time a finding arises.

Format per entry:

```
## <kebab-case-finding-name>

**Discovered during:** <change name + step that surfaced it>
**Status:** open | triaged | in-progress | resolved-not-merged

**Finding:** <one paragraph: what the divergence is, with file:line citations on both sides>

**Specs at fault:** <list>
**Code at fault:** <list>
**Docs at fault:** <list>

**Impact (if shipped):** <one paragraph>

**Ambiguity to resolve first:** <if any>

**Action items:**
- [ ] <step>
- [ ] <step>
```

---

## reports-rate-limit-cap-doc-vs-spec-drift

**Discovered during:** `like-rate-limit` proposal scoping (Phase B step 7 reconciliation pass — checking the V9 in-process limiter port for any drift before reusing its hash-tag key shape as a precedent).
**Status:** open

**Finding:** The shipped `reports` capability enforces a 10/hour cap on `POST /api/v1/reports` ([`openspec/specs/reports/spec.md:170-192`](openspec/specs/reports/spec.md) — Requirement "Rate limit 10 submissions per hour per user", and the corresponding implementation in [`backend/ktor/src/main/kotlin/id/nearyou/app/moderation/ReportRateLimiter.kt:97-98`](backend/ktor/src/main/kotlin/id/nearyou/app/moderation/ReportRateLimiter.kt) with `DEFAULT_CAP = 10`). The canonical Layer 2 rate-limit table in [`docs/05-Implementation.md:1742`](docs/05-Implementation.md) prescribes **20/hour** for reports. The two values disagree by 2x. Both pre-date this change cycle.

**Specs at fault:** `openspec/specs/reports/spec.md` (or canonical docs — TBD)
**Code at fault:** `backend/ktor/src/main/kotlin/id/nearyou/app/moderation/ReportRateLimiter.kt` (`DEFAULT_CAP = 10`)
**Docs at fault:** `docs/05-Implementation.md:1742` Layer 2 table

**Impact (if shipped):** Low. The shipped code + spec are internally consistent (10/hour everywhere they appear together). Risk is mostly to future maintainers reading the canonical Layer 2 table and assuming the docs match the code. No user-facing impact unless ops decides to retighten or loosen the cap based on the wrong number.

**Ambiguity to resolve first:** Is the docs Layer 2 table over-stated (V9 deliberately shipped tighter, 10/hour, for early-launch anti-abuse), or did the spec drift below the canonical docs by accident? Both directions have been seen in this project's history (cf. PR #24 v10 notifications body_data audit — Direction Y resolved to "shipped code is correct; docs were over-specified"). A quick review of the V9 PR + design.md + commit history should clarify.

**Action items:**
- [ ] Triage X vs Y by re-reading the V9 `reports-v9` change's design.md / proposal.md for any explicit rationale on 10/hour vs 20/hour. Search the archived `openspec/changes/archive/2026-04-22-reports-v9/` directory.
- [ ] If X (docs are canonical, spec drifted low): amend the `reports` spec via a new OpenSpec change `reports-rate-limit-bump-to-20-per-hour`; bump `DEFAULT_CAP` and the spec scenarios from 10→20.
- [ ] If Y (spec is intentionally tighter, docs over-stated): amend `docs/05-Implementation.md:1742` table value from 20→10; no code/spec change.
- [ ] In either direction: the `like-rate-limit` change does NOT silently adjust either side — that's a separate ticket.
