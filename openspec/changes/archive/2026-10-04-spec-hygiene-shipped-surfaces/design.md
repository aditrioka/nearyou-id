## Context

This is a spec-sync change for issue #499. Eight canonical specs drifted from shipped code: the code changes landed without matching spec deltas. The gear came in PR #312 as a regular PR, the CSAM admin viewer's archive carried no `csam-detection` delta, eleven admin surfaces never MODIFIED the scaffold's sidebar list, the image-upload pipeline added a flag without touching `admin-feature-flags`, and the resume re-read came in fix PR #521. All of them are now hard to find. The code is correct and tested; only the specs are wrong. Every claim was re-verified against `origin/main` before editing (proposal table), and each delta cites the code and test that guard it.

## Goals / Non-Goals

**Goals:**
- Every listed spec matches the shipped code, with zero code change.
- Each "deferred" header that has since shipped is renamed. The name now says what's true, so a grep for "deferred" stops finding shipped things.
- Each corrected requirement keeps a scenario that names the guarding test or behavior.

**Non-Goals:**
- No behavior change, no new tests (the shipped tests already guard every corrected claim; this change only makes the specs say what they enforce).
- No closing of #336, #333, or #335. #336 is closed as a duplicate after merge; #333 and #335 stay open as tracked deferrals.
- No re-audit of specs beyond the issue's rows, its two comments, and the three operator-named additions. Adjacent text in the same specs that is *correct* stays untouched. One example: the `mobile-settings` "backed rows are exactly…" list is scoped to mockup frame-16 rows, and the data-export, account-deletion and referral rows are deliberately off-frame, so it is not stale.

## Decisions

1. **RENAMED + MODIFIED for headers that flip from "deferred/absent" to shipped (csam-detection, moderation-queue, mobile-post-detail, mobile-premium-username), plain MODIFIED where the header stays true (mobile-settings, admin-panel-scaffold, admin-feature-flags).** This is the repo convention (`2026-09-25-timeline-card-block-kebab`, `2026-09-26-embedded-snapshot-author-erasure`). Each FROM header is the current canonical header, checked byte-for-byte by the build script. Alternative REMOVED + ADDED was rejected because it loses requirement history and is the documented anti-pattern.
2. **Delegation instead of duplication for csam-detection and moderation-queue.** The flipped requirements say who owns the reader (`admin-csam-detection-log`, `admin-report-queue`, `admin-premium-username-oversight`) and keep a negative guard (no user-facing reader; no archive content from `/internal/*`). They do not restate those capabilities' contracts, because a second copy would drift.
3. **The scaffold sidebar is enumerated exactly, plus a maintenance rule.** The existing test asserts exactly 16 `nitem`s, so the spec must enumerate them to match. A one-line rule says a change that ships or removes a nav item carries a MODIFIED delta of this requirement. That turns the root cause of this drift (eleven surfaces landed without one) into a reviewable obligation.
4. **mobile-premium-username keeps the (a)–(d) lettering.** The #333/#335 triage comments refer to "deferral (a)" and "deferral (c)". The renamed requirement marks each letter **Deferred (#N)** or **Not planned** in place, rather than splitting into two requirements, so the future #333/#335 change can MODIFY one block.
5. **Purpose fixes go directly into the archive commit.** Delta files have no Purpose section, and precedent PR #463 ("Purpose de-staled") did the same. `tasks.md` lists each Purpose edit so the archive step can't skip it.
6. **docs/03 is amended in the same PR.** It links the now-not-planned #334 and the soon-duplicate #336. Leaving it would put docs and spec out of agreement (CLAUDE.md reviewer rule 8).

**Standards conformance.** No `:mobile:app` or `:backend:ktor` code changes, so no Pattern-Registry entry is added or used. The corrected specs describe patterns already in docs/11: root-stack push via hoisted callbacks wired at the `AppEntryProvider` call site (§2), entry-scoped VM + `LifecycleEventEffect` resume refresh (the `LocationGateViewModel.refresh` idiom), and admin routes behind the session + CSRF middleware (§3).

**Cross-layer scope (docs/12).** No capability is added or extended, so no vertical slice is in play. Each corrected spec records layers that already shipped: mobile ↔ backend for the post-detail by-id reads, backend ↔ admin for the CSAM and moderation-queue readers. Deferred layers stay declared as explicit requirements: `mobile-premium-username` (a)/(c), and `mobile-notifications-list` actor username and live badge, which are unchanged.

## Risks / Trade-offs

- **[Risk] A wrong RENAMED FROM header fails silently at archive** → the build script requires each FROM header to exist verbatim in the canonical spec before writing it, and the archive step greps the synced specs for the new headers and the absence of the old ones.
- **[Risk] Retyped MODIFIED text loses detail or is altered by the session's PII redactor** → unchanged text is spliced verbatim from the canonical spec by script. Each edit is an exact substring replace asserted to match once.
- **[Risk] A future #333/#335 change targets the old header** → the proposal's Impact section and the PR body state the new header. That change must byte-check its FROM header against the then-canonical spec, which is already the repo convention.
- **[Trade-off] The scaffold's exact 16-item list will go stale again with the next admin surface** → accepted. The test already pins 16, so the spec must too. The new maintenance rule makes the update part of that surface's own change.
