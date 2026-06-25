## 1. Schema — V36 trigger-enum extension

- [ ] 1.1 Collision check: confirm `V36` is still free (`ls backend/ktor/src/main/resources/db/migration/ | grep V36`); if a sibling branch claimed it pre-merge, `git mv` this migration to the next free version and update all references (project memory: Flyway version collision; V35 is current max, the V29 gap is behind the staging/prod frontier — do NOT reuse it).
- [ ] 1.2 Add `V36__moderation_queue_area_spam_trigger.sql`: `ALTER TABLE moderation_queue DROP CONSTRAINT moderation_queue_trigger_check, ADD CONSTRAINT moderation_queue_trigger_check CHECK (trigger IN ('auto_hide_3_reports','perspective_api_high_score','uu_ite_keyword_match','admin_flag','csam_detected','anomaly_detection','username_flagged','area_spam'));` — additive, no data rewrite. Confirm the auto-generated constraint name by inspecting V9 (`information_schema.table_constraints` for `moderation_queue` if the literal `moderation_queue_trigger_check` differs).
- [ ] 1.3 DB-tagged migration test (`moderation-queue` spec): `area_spam` INSERT succeeds at V36; all eight values accepted; `spam_pattern_detected` still rejected; a DB pre-loaded with the seven original-value rows migrates with rows intact.

## 2. Backend — area-density limiter

- [ ] 2.1 Add `AreaPostDensityLimiter` under `id.nearyou.app.post`, delegating to the shared `RateLimiter` (mirror `PostRateLimiter`'s delegation shape — NOT a parallel Redis-counter mechanism). Key `{scope:area_post}:{cell:<lat>_<lng>}`, capacity `AREA_POST_THRESHOLD = 50`, window `AREA_POST_WINDOW = 1h`. Do NOT call `computeTTLToNextReset` (hourly limit — skips the per-user daily stagger). Expose an outcome of `Allowed | OverThreshold` (NOT a 429-mapped exception).
- [ ] 2.2 Cell derivation helper: round `display_location` lat/lng to a fixed `AREA_CELL_PRECISION = 0.01°` grid → `"<round(lat,2)>_<round(lng,2)>"`. Read `display_location` (the fuzzed coordinate the create flow already derives), NEVER `actual_location` (spatial-fuzzing invariant). Reuse the `display` value already computed in `CreatePostService` — no recompute.
- [ ] 2.3 Verify the constructed Redis key passes `RedisHashTagRule` (`./gradlew :lint:detekt-rules:test` + `:backend:ktor:detekt`); the two-segment `{scope:area_post}:{cell:...}` shape is mandatory (project memory: Redis hash-tag strict key shape).

## 3. Backend — CreatePostService integration

- [ ] 3.1 Wire the area gate into `CreatePostService.create()` AFTER the moderator `Verdict.Reject` short-circuit and AFTER envelope + `display_location` derivation, BEFORE the INSERT (design Decision 7). Run the limiter `INCR`/check on the bounded `dbDispatcher`/Redis path consistent with the existing gates.
- [ ] 3.2 On `OverThreshold`, INSERT one `moderation_queue` row in the SAME transaction as the `posts` INSERT: `target_type='post'`, `target_id=<new_post_id>`, `trigger='area_spam'`, `status='pending'`, `priority=5`, `ON CONFLICT (target_type, target_id, trigger) DO NOTHING`. Compose with the existing `Verdict.Flag` queue write (both may fire → two rows). `is_auto_hidden` stays FALSE.
- [ ] 3.3 Apply to ALL tiers — the area gate runs regardless of `subscription_status` (Premium is NOT exempt; contrast the daily cap which Premium skips).
- [ ] 3.4 DI wiring: production constructs `CreatePostService` with the real `AreaPostDensityLimiter` (shared Redis `RateLimiter` + `DbDispatchers.db`); test fixtures get an injectable limiter (default keeps existing pre-gate fixtures constructing).
- [ ] 3.5 Confirm NO route/DTO/wire change: `POST /api/v1/posts` request body + `201` response field set + error envelope are byte-identical (no new error code, no `Retry-After` on an area flag).

## 4. Admin read-path cohesion (docs/12)

- [ ] 4.1 Grep admin Pebble templates (`templates/admin/**`) + admin Kotlin (`admin/reportqueue/**`, moderation viewer) for any hardcoded `moderation_queue.trigger` allow-list / label map / filter dropdown. The viewer addresses rows by `(target_type, target_id)` and should render `trigger` generically.
- [ ] 4.2 If the admin layer hardcodes the trigger set anywhere, add the one-line `area_spam` entry (label/filter) IN THIS CHANGE — do not defer (docs/12 §2: no backend-only slice writing rows no surface can triage). If no hardcoding exists, record that the generic render covers it.
- [ ] 4.3 Negative-guard test/assertion: an `area_spam` `moderation_queue` row is displayable + resolvable in the existing admin moderation/report-queue viewer (rendered trigger string present; resolution actions available).

## 5. Tests — area-density behavior (DB-tagged kotest; `autoClose(hikari())` size 2, docs/11 §3.2)

- [ ] 5.1 Below threshold (50th post in a cell) → 201, no `moderation_queue` row.
- [ ] 5.2 Over threshold (51st post in a cell) → 201 with canonical payload, `is_auto_hidden=FALSE`, exactly one `moderation_queue` row `trigger='area_spam'`, `status='pending'`.
- [ ] 5.3 Response wire shape unchanged on a flag (field set identical, no error code, no `Retry-After`).
- [ ] 5.4 Premium poster is ALSO flagged over threshold (not exempt).
- [ ] 5.5 Cell isolation — saturating cell A does not flag a below-threshold post in a distinct cell B.
- [ ] 5.6 Gate keys on `display_location` (not `actual_location`) — assert via the cell the counter increments / a static check that the code path reads `display`.
- [ ] 5.7 Window expiry — after the 1h window elapses (advance the limiter's clock / TTL), a subsequent post in the same cell is not flagged.
- [ ] 5.8 Idempotency — a duplicate `(post, area_spam)` insert leaves exactly one row.
- [ ] 5.9 Silent to poster — no client-observable signal, no `notifications` row of any area-spam type for the poster.
- [ ] 5.10 Rejected content (`Verdict.Reject`) → 400, no INSERT, area counter NOT incremented, no `area_spam` row.
- [ ] 5.11 Area-flag + UU-ITE-flag coexist on one post → 201, exactly two `moderation_queue` rows (one per trigger, same `target_id`).
- [ ] 5.12 Call-order static-analysis test (extend the existing moderator call-order test): the area gate appears after `moderate(...)` and before `INSERT INTO posts`, never below the INSERT.

## 6. Docs reconciliation + gate

- [ ] 6.1 Amend `docs/05-Implementation.md` § Layer 4 (line ~1330) so the sentence describes the shipped geocell-counter mechanism (display_location-derived ~1.1km grid cell, Redis hourly limiter, 50/1h, threshold → `area_spam` manual-review routing) rather than the literal "spatial query" (design Decision 1 reconciliation — canonical-doc-is-source, in-PR fix).
- [ ] 6.2 Run the pre-push gate locally: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` (both lint frameworks; CI runs both). Fresh DB containers for the DB-tagged tests (docs/13 §5; CI-equiv tag `!network`, not `!database`).
- [ ] 6.3 Live backend verify (verify-loop §A): boot Ktor (`KTOR_ENV=test`), POST 51 posts to one tight coordinate, observe the 51st gets a `moderation_queue` row `trigger='area_spam'` (psql) while still returning 201; confirm the Redis key `{scope:area_post}:{cell:...}` exists with TTL ≤ 3600 (redis-cli); a post in a far coordinate is unflagged. Capture evidence for the PR body.
