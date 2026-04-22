## Context

V9 is the moderation-activation change. V4 `posts.is_auto_hidden`, V7 `post_likes` (no auto-hide column; post-level flag is sufficient), and V8 `post_replies.is_auto_hidden` have each reserved the fields without a code path to flip them. Phase 2 item 4 in `docs/08-Roadmap-Risk.md:151` calls for the Report feature with a 3-unique-reporters auto-hide trigger. The canonical schema, reason enum, trigger enum, and auto-hide semantics are already decided in `docs/05-Implementation.md` §742–816 and §737.

V9 lands on a pipeline where:
- V4 `visible_posts` already filters `is_auto_hidden = FALSE` — so flipping the column automatically hides the post from nearby/following/global/search timelines. No read-path changes needed.
- V8 reply-list endpoint already filters `is_auto_hidden = FALSE OR author_id = :viewer` — so flipping the column auto-hides the reply from non-authors. No read-path changes needed.
- `admin_users` table does NOT yet exist (first migration that references it is Phase 3.5). V9 needs two columns (`reports.reviewed_by`, `moderation_queue.resolved_by`) that the docs declare as FK to `admin_users(id)`. This is the first pipeline-level forward reference.

This design covers the seven non-obvious decisions that needed explicit rationale — the rest of V9 is straightforward schema + endpoint + transactional side-effect.

## Goals / Non-Goals

**Goals:**
- Ship V9 DDL verbatim from `docs/05-Implementation.md` §745–816 (two tables, full trigger enum, full reason enum, full resolution enum) with no column renames.
- One `POST /api/v1/reports` endpoint that (a) validates enums + length, (b) checks target existence without block-exclusion filtering, (c) INSERTs the report, (d) transactionally checks the auto-hide threshold and flips `is_auto_hidden` on the matching target when ≥3 unique reporters aged >7 days, (e) inserts an idempotent `moderation_queue` row, (f) returns 204.
- Rate-limit the endpoint at 10 submissions/hour/user (anti-report-bomb) using the existing Redis hash-tag key infrastructure.
- Introduce the deferred-FK convention (UUID column + `COMMENT ON COLUMN` recording the deferred target) so the Phase 3.5 admin-users migration has a clear upgrade path via `ALTER TABLE ... ADD CONSTRAINT ... NOT VALID` + `VALIDATE`.
- Zero behavioral change to existing endpoints' response shapes. `visible_posts` and the V8 reply-list filter do all the work; flipping `is_auto_hidden` is sufficient.

**Non-Goals:**
- Admin review UI reading `moderation_queue` (Phase 3.5 admin-panel change).
- Auto-unhide path on false-positive resolution (Phase 3.5 admin-panel change; V9 only writes the flag, never clears it).
- The `admin_users` table itself — deferred to Phase 3.5. V9 establishes the deferred-FK convention but does not ship the table.
- `post_auto_hidden` notification (Phase 2 item 5 notifications-api change).
- Perspective API / UU ITE keyword / CSAM / anomaly / username_flagged writers into `moderation_queue` — V9 creates the table with the full trigger enum so these future writers don't need an enum-widening migration, but V9 itself only writes `auto_hide_3_reports`.
- Rate limits on Likes / Replies (Free 10/day / 20/day) — separate rate-limit change. V9's 10/hour/user is scoped to the reports endpoint only.
- Report history endpoint (user-facing "my submitted reports") — covered by Data Export.
- Mobile UI (reason picker flow) — Phase 3.
- Block-exclusion filtering on target resolution — deliberately omitted; see Decision 3.

## Decisions

### 1. Auto-hide check runs in the same DB transaction as the `reports` INSERT

**Decision:** BEGIN → INSERT INTO reports → SELECT COUNT(DISTINCT reporter_id) filtered by account-age → conditional UPDATE on posts/post_replies → INSERT INTO moderation_queue ON CONFLICT DO NOTHING → COMMIT. Single transaction. No out-of-band worker, no post-commit hook.

**Rationale:** The threshold crossing is the most report-sensitive state transition in the system. Doing it in-process in the same TX (a) guarantees the 3rd reporter's row + the flip happen atomically (no window where reports=3 exists but is_auto_hidden=FALSE), (b) eliminates eventual-consistency lag visible to the 3rd reporter (their own submission triggers the flip they want to see), (c) avoids a whole queue + worker + retry machinery for a single-digit-per-second write path. The INSERT + SELECT + UPDATE + INSERT pattern is ~4 round-trips inside a TX with one row-level lock on the target row, which is bounded and fast.

**Alternatives considered:**
- **Postgres trigger** fired on `AFTER INSERT ON reports`: considered and rejected. A DB trigger makes the auto-hide logic invisible to application-level observability (Sentry breadcrumb, OTel span, structured log), harder to unit-test, and couples the moderation policy (the 7-day account-age filter + count-distinct logic) to DDL rather than versioned Kotlin code. Phase 4 may want to tune this threshold with feature flags; that's trivial in Kotlin, awkward in a trigger.
- **Out-of-band worker** (Cloud Scheduler / queue): considered and rejected. Adds infra (queue wiring + worker process + retry) for a write path that handles <100 rps at MVP scale. Opens a reports=3 but is_auto_hidden=FALSE window the 3rd reporter can observe by immediately GETting the target. Worth reconsidering if the endpoint hits 1000+ rps; trivial to split later since the Kotlin side is the only auto-hide writer.

### 2. `reviewed_by` / `resolved_by` FKs are deferred, not introduced as stub `admin_users` rows

**Decision:** Both columns ship as `UUID` (nullable, no constraint). `COMMENT ON COLUMN` on each records the deferred target: `'FK to admin_users(id) ON DELETE SET NULL — deferred to the Phase 3.5 admin-users migration'`. The Phase 3.5 migration will add the constraints via `ALTER TABLE ... ADD CONSTRAINT ... FOREIGN KEY ... REFERENCES admin_users(id) ON DELETE SET NULL NOT VALID` followed by `VALIDATE CONSTRAINT` (two-step to avoid long lock on production once data exists).

**Rationale:** `admin_users` is a substantive schema (Argon2id password hash, TOTP secret encrypted at rest, WebAuthn enrollment flag, role enum, active flag, timestamps — plus `admin_webauthn_credentials` and `admin_sessions` companions). Introducing a minimal stub in V9 either leaves columns empty for months (error-prone) or front-loads a big schema into the wrong change. Deferred-FK is cleaner: the columns are semantically correct now (nullable UUID; will be populated only when admins start reviewing), and the constraint arrives with the table that backs it.

**Alternatives considered:**
- **Ship a minimal `admin_users` stub in V9** (just `id UUID PRIMARY KEY`): rejected. Stub tables accumulate debt — the real V_N+k migration then has to decide whether to ALTER the existing table or to drop-and-recreate. Keeping admin_users entirely inside its own change is cleaner.
- **Defer the columns themselves** (ship V9 without `reviewed_by` / `resolved_by`, add them in the admin migration): rejected. The columns are semantically part of the reports + moderation_queue tables from day one; admins will want to populate them as soon as Phase 3.5 lands. Column add-later would fragment the canonical DDL away from `docs/05-Implementation.md`.

### 3. Target existence check does NOT apply block-exclusion or shadow-ban filtering

**Decision:** The POST /reports target-resolution is a point-lookup by id against the raw tables (`posts` / `post_replies` / `users` / `chat_messages`), gated only on soft-delete (`deleted_at IS NULL` where applicable). It does NOT go through `visible_posts`, does NOT JOIN `visible_users`, does NOT NOT-IN against `user_blocks`. Self-report on `target_type = 'user' AND target_id = :reporter_id` is the only rejection beyond existence.

**Rationale:** All four of these cases are legitimate report sources:
- Reporting content from a user the reporter has blocked (blocker can see the target via cache, direct link, or the moment before the block took effect).
- Reporting content from a user who has blocked the reporter (blocked user saw the content pre-block; legitimately wants to report).
- Reporting a shadow-banned user's content (the reporter saw the content before the shadow-ban; the shadow-ban is a moderation state, not an erasure).
- Reporting an auto-hidden post (the reporter saw it before the auto-hide flipped; wants the moderation queue to record their concern so the admin review priority rises).

Filtering any of these out via visible_*/block joins would suppress exactly the reports the system most wants.

The target does have to *exist* — reporting a random UUID is 404 — because the UNIQUE `(reporter_id, target_type, target_id)` constraint + `auto_hide_3_reports` threshold are meaningless without a resolvable target.

**Alternative considered:**
- **Resolve the target via `visible_*` views**: rejected per the four cases above. The block/shadow-ban/auto-hide state of the target is precisely the state a report is *about*; filtering it would be circular.

### 4. Account-age filter (>7 days) on reporters at COUNT-DISTINCT time, not at INSERT time

**Decision:** The report row is always written (the `UNIQUE` constraint still enforces one-per-reporter-per-target). The 7-day filter is applied only at the COUNT-DISTINCT step that decides whether to flip `is_auto_hidden`. A report from a <7-day-old account is valid (admin sees it in the queue) but does not count toward the auto-hide threshold.

**Rationale:** `docs/08-Roadmap-Risk.md:317` is explicit: "3 unique reporters then `is_auto_hidden = TRUE`" combined with `docs/05-Implementation.md:777`: "when 3 distinct reporters (accounts >7 days old) report...". The intent is anti-Sybil: a coordinated attack registers 3 fresh accounts and files 3 reports. Filtering at count time preserves the admin-triage signal (the reports *exist* in the queue for review) while refusing to let brand-new accounts unilaterally hide content.

When a reporter crosses the 7-day mark, their historical reports become counting. This is correct behavior — an account that matured past the anti-Sybil window is now a legitimate signal source — but the flip only fires on a *subsequent* report submission for the same target (the COUNT-DISTINCT rerun). We don't retroactively flip when accounts age in; the next report submission triggers a re-check.

**Alternative considered:**
- **Refuse the INSERT** for <7-day reporters: rejected. The UNIQUE-constraint semantics would be broken (a <7-day account that later matures past 7 days couldn't report a target they already tried to report). Admin would also lose visibility into coordinated-young-account patterns that are themselves signal.

### 5. `moderation_queue` INSERT is idempotent via the schema's UNIQUE `(target_type, target_id, trigger)` + `ON CONFLICT DO NOTHING`

**Decision:** Every `auto_hide_3_reports` write uses `INSERT ... ON CONFLICT (target_type, target_id, trigger) DO NOTHING`. No UPDATE branch.

**Rationale:** The UNIQUE constraint already exists (`docs/05-Implementation.md:809`). The only case this matters is the 4th / 5th / Nth reporter — their submission still crosses the threshold, still runs the `is_auto_hidden = TRUE` UPDATE (which is itself idempotent), and still attempts the moderation_queue INSERT. `ON CONFLICT DO NOTHING` keeps the original row (original `created_at`, original `priority`) intact; the target already has one admin-triage entry for this trigger — multi-triggering a queue row doesn't help admin. If a future priority-bumping mechanism is needed (e.g., bump priority on 10+ reports), it's a separate UPDATE path triggered by count thresholds.

**Alternative considered:**
- `ON CONFLICT ... DO UPDATE SET priority = priority + 1` to bump severity on repeat reports: rejected for V9. The Phase 3.5 admin panel can compute current reporter count at display time (`SELECT COUNT(*) FROM reports WHERE target_type = ? AND target_id = ?`) without encoding it into moderation_queue. Keeping V9 narrow — one insert-or-nothing per (target, trigger).

### 6. Report submission rate limit: 10/hour/user (not 10/day; not per-target)

**Decision:** Redis-backed sliding window with key `{scope:rate_report}:{user:<id>}`, cap 10, window 1 hour. Enforced at the Ktor endpoint BEFORE DB work. 429 response with `rate_limited` error code and `Retry-After` header.

**Rationale:** Two competing concerns:
- Report-bombing (one user spamming reports on many targets): 10/hour is generous for a legitimate user (most users never file a single report; a user cleaning up a group conversation might file 3-5) but hard-caps automated abuse.
- Not throttling legitimate moderation reports (e.g., a user encountering a coordinated spam campaign and reporting all of it): per-day would be too tight; per-hour replenishes fast enough.

**Not per-target-per-user**: that's covered by the UNIQUE `(reporter_id, target_type, target_id)` constraint → 409 on retry, zero cost.

**Alternatives considered:**
- **Per-day cap (e.g., 30/day)**: rejected as too easy to circumvent over the 24h window while punishing legitimate bursts. The WIB-stagger infrastructure the system has for daily limits is overkill for an anti-abuse counter.
- **Per-minute cap**: rejected as too punishing for the legitimate moderation-burst case.
- **No rate limit**: rejected. Every write endpoint that doesn't have one eventually gets abused.

### 7. Full trigger + resolution enum lists ship at V9, even though V9 only writes one trigger value

**Decision:** The `moderation_queue.trigger` CHECK constraint includes all 7 values from `docs/05-Implementation.md:793-795` at V9 (`auto_hide_3_reports`, `perspective_api_high_score`, `uu_ite_keyword_match`, `admin_flag`, `csam_detected`, `anomaly_detection`, `username_flagged`). The `resolution` CHECK constraint includes all 8 values from `docs/05-Implementation.md:800-802`. V9 only writes `auto_hide_3_reports` rows and never writes any resolution (Phase 3.5 admin panel owns that).

**Rationale:** Enum-widening on a CHECK constraint requires an `ALTER TABLE ... DROP CONSTRAINT` + `ADD CONSTRAINT ... NOT VALID` + `VALIDATE` sequence that takes a brief exclusive lock. Cheaper and cleaner to ship the full enum up-front, since the docs have already enumerated every value. Phase 2 item 16 (Perspective API), Phase 3.5 admin-flag workflows, Phase 4 CSAM, Phase 4 anomaly detection, Phase 4 Premium username customization — each just writes the trigger value; zero schema migration cost.

**Risk:** If Phase 2 feedback reshapes a trigger name (e.g., `perspective_api_high_score` → something more generic), V9's constraint needs edit-before-deploy or a post-hoc migration. Accepted — enum vocabulary has been stable in the docs through 4 months of pre-Phase-1 refinement, and the migration cost of "rename one enum value" is trivial relative to the weekly cost of "add one enum value".

**Alternative considered:**
- **Ship only `auto_hide_3_reports` in V9**; add the other 6 as each feature lands. Rejected — creates 6 micro-migrations with no structural benefit, and the vocabulary is already frozen in docs.

## Risks / Trade-offs

- **Race: two 3rd-reporter INSERTs land simultaneously on the same target.** Mitigation: the `reports` UNIQUE constraint ensures both can't be from the same user, so one of them is the *actual* 3rd reporter (the other is a 4th or later, or an unrelated target). Both transactions run the COUNT-DISTINCT → both see ≥3 → both run the UPDATE (idempotent; `is_auto_hidden = TRUE` is a no-op on the second) → both try the `moderation_queue` INSERT → ON CONFLICT DO NOTHING keeps only one queue row. No lock needed beyond the default row-level locking.
- **Risk: reporter cohort contains adversarial coordinated >7-day accounts.** Mitigation: the 3-threshold is a small cost for adversaries. Future hardening (shared-IP clustering, device-fingerprint clustering, behavioral signals) lands as admin-side dashboards in Phase 3.5 and an anomaly-detection moderation_queue writer in Phase 4. V9 is the minimum viable auto-hide; sophistication comes later.
- **Risk: `reviewed_by` / `resolved_by` start populated before the FK exists, producing orphaned UUIDs.** Mitigation: V9 code NEVER writes these columns (only admin panel does, and it doesn't exist yet in Phase 2). When the Phase 3.5 admin migration runs, both columns are still NULL across the board; the `NOT VALID` + `VALIDATE` path succeeds without any cleanup.
- **Trade-off: legitimate auto-hides produce no user-facing notification in V9.** The `post_auto_hidden` notification is gated on the notifications API change (Phase 2 item 5). Users see their post / reply disappear from timelines and have no explanation. Accepted for V9; the `// TODO: notifications-api-v??` stub at the flip site is the hand-off.
- **Trade-off: no automatic un-hide when a report is dismissed.** Dismissed reports stay in the DB but the `is_auto_hidden = TRUE` flag remains. Admin panel (Phase 3.5) has explicit un-hide authority; there is no user self-service un-hide (would defeat the moderation purpose). A user whose content is wrongly auto-hidden can appeal via Settings (Open Decision #2 at `docs/08-Roadmap-Risk.md:539`) once the admin panel ships.
- **Risk: the single-TX approach serializes auto-hide flips through row-level locks on popular targets.** Mitigation: the lock is held only for the duration of the POST /reports handler (~milliseconds); contention is bounded by the rate limit (10/hr/user × N users). Postgres row-level lock contention at <100 rps on a single row is not a bottleneck. If a target receives thousands of reports/minute (viral coordinated attack), the rate limit gates that at the front door.

## Migration Plan

1. Author `V9__reports_moderation.sql` with the two tables, 5 indexes, and 2 `COMMENT ON COLUMN` statements (for `reviewed_by` and `resolved_by`).
2. Stage Flyway migration run against `dev` local DB via `scripts/flyway-migrate-local.sh` (existing). Verify both tables + indexes exist and CHECK constraints reject out-of-enum values.
3. Ship the ReportService + routes + `RawFromPostsRule` allow-list update together. Detekt must pass locally before PR.
4. Staging deploy: `main` merge auto-triggers Cloud Run Job `nearyou-migrate` (Flyway) → applies V9 → backend deploys reading V9 schema. Zero-downtime because the endpoint is brand new (no contract break on existing routes).
5. Prod deploy: git tag `v*` after staging smoke tests pass (POST /reports with a seed user; verify target-existence 404s; verify 409 duplicates; verify auto-hide flip + queue row after 3 manually-crafted reports from aged seed users).

**Rollback strategy:**
- If V9 migration fails: Flyway records the failure; next run re-attempts. DB has no partial state because the migration is two `CREATE TABLE` + indexes + comments — no data mutation.
- If V9 migration succeeds but the application code has a bug: deploy a hot-fix; the `reports` and `moderation_queue` tables sit idle (no writers). They are forward-compatible — the presence of the tables harms no existing endpoint.
- If we need to remove the tables (extreme case, unlikely): `V10__drop_reports_moderation.sql` would DROP both. There's no data to preserve in the rollback scenario.

## Open Questions

- **Should the auto-hide flip emit a Sentry event (not just a breadcrumb)?** Leaning yes for V9 to catch the first production fire; can demote to breadcrumb-only after two weeks of clean signal. Decision deferred to the observability tasks — default is breadcrumb + structured log; upgrade to Sentry event is a one-liner.
- **Should rate limit 429 reveal remaining quota in the response body?** Existing rate-limit middleware returns `Retry-After` but no quota exposure. Proposal: match existing convention (header only); revisit when mobile UX specifies countdown rendering. No decision needed for V9 scaffolding.
