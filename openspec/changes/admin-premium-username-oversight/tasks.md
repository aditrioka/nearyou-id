## 1. Pre-flight & grounding

- [ ] 1.1 Re-confirm at apply time: `username_history` (V3) columns + `username_history_old_lower_idx`; `moderation_queue` `resolution` CHECK includes `accept_flagged_username`/`reject_flagged_username` + `trigger` CHECK includes `username_flagged` (V9); `admin_actions_log.action_type` unconstrained `VARCHAR(64)` (V16); and **V23 is still free** (no in-flight branch grabbed it). If any drifted, STOP and re-scope.
- [ ] 1.2 Render admin mockup frame 22 (`dev/scripts/mockup-measure.sh` per `dev/mockups/README.md` step 4); translate layout/tokens to the admin Pebble + HTMX + vendored-CSS idiom (`docs/11` § 3.6). Confirm the "Hit" column is omitted and the candidate is shown in full (design Decision 4).

## 2. Migration & schema (V23)

- [ ] 2.1 `V23__username_flag_overrides.sql` — `CREATE TABLE username_flag_overrides (id UUID PK DEFAULT gen_random_uuid(), user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE, candidate VARCHAR(60) NOT NULL, approved_by UUID REFERENCES admin_users(id) ON DELETE SET NULL, approved_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), consumed_at TIMESTAMPTZ, UNIQUE(user_id, candidate))`. `candidate VARCHAR(60)` matches the `username_history` column width (V3); the app-layer candidate is ≤30 (`MAX_LENGTH`). No `NOW()` partial-index predicate (critical invariant). Header comment documenting the one-shot per-candidate-override purpose.
- [ ] 2.2 Add `username_flag_overrides` to the `admin_app` per-table grants in `dev/scripts/provision-admin-app-staging.sh` (SELECT/INSERT/UPDATE); note in the PR that the operator must run it on staging (and prod with `PROJECT_OVERRIDE`). Confirm the migration applies on the integration-test Postgres (no `admin_app` dependency).

## 3. premium-username-customization live-gate coupling (MODIFIED capability)

- [ ] 3.1 In `id.nearyou.app.user.UsernameChangeService` moderation gate: BEFORE rejecting on a profanity/UU-ITE hit, query `username_flag_overrides` for a non-consumed `(user_id, candidate)` (candidate normalized lowercase). On a match, SKIP the moderation rejection and proceed through the remaining gates.
- [ ] 3.2 Consume the override via a conditional, rows-affected-gated `UPDATE username_flag_overrides SET consumed_at = NOW() WHERE user_id = ? AND candidate = ? AND consumed_at IS NULL` executed INSIDE the existing `SELECT … FOR UPDATE` change transaction (NOT the pre-lock read connection), and re-validate the skip decision under the lock: if the consume affects zero rows (a concurrent same-user change already spent it), re-moderate the candidate. So consume + rename + history + notification commit/rollback together AND the override grants at most one pass.
- [ ] 3.3 Change the `username_flagged` insert from `ON CONFLICT … DO NOTHING` to `ON CONFLICT (target_type, target_id, trigger) DO UPDATE SET status='pending', resolution=NULL, resolved_by=NULL, resolved_at=NULL, notes=EXCLUDED.notes, created_at=NOW()`, populating `notes` with the flagged candidate (re-open + persist latest candidate). This changes the `:data` `ModerationQueueRepository.upsertUsernameFlaggedRow` signature to carry the candidate — update its callers + the data-module test.

## 4. Admin repository layer (`id.nearyou.app.admin.usernameoversight`)

- [ ] 4.1 `listPendingFlags(cursor)` — keyset over `moderation_queue WHERE trigger='username_flagged' AND status='pending'` (`created_at DESC, id DESC`), select `notes` (candidate), `LEFT JOIN users` to resolve `target_id` → `username` (null-safe).
- [ ] 4.2 `listHistory(q, cursor)` — keyset over `username_history` (`changed_at DESC, id DESC`), `LEFT JOIN users`; `q` matches `LOWER(old_username)` (V3 index) OR `LOWER(new_username)`.
- [ ] 4.3 `listHolds()` — `username_history WHERE released_at > NOW()` ordered `released_at ASC`, `LEFT JOIN users`.
- [ ] 4.4 `resolveFlag(queueId, resolution, adminId)` — conditional `UPDATE moderation_queue SET status='resolved', resolution=?, resolved_by=?, resolved_at=NOW() WHERE id=? AND status='pending' AND trigger='username_flagged'` returning rows-affected + the row's `target_id` + `notes`.
- [ ] 4.5 `upsertOverride(userId, candidate, adminId)` — `INSERT INTO username_flag_overrides (user_id, candidate, approved_by) VALUES (?, LOWER(?), ?) ON CONFLICT (user_id, candidate) DO UPDATE SET approved_by=EXCLUDED.approved_by, approved_at=NOW(), consumed_at=NULL`.
- [ ] 4.6 `releaseHold(historyId)` — conditional `UPDATE username_history SET released_at=NOW() WHERE id=? AND released_at > NOW()` returning rows-affected + prior `released_at`.
- [ ] 4.7 `countActionsInTrailingHour(adminId, actionType)` — `COUNT(*)` over `admin_actions_log` (`admin_id=? AND action_type=? AND created_at > NOW() - INTERVAL '1 hour'`), reusing the `admin/ratelimit` helper.

## 5. Admin service layer

- [ ] 5.1 `resolveFlag(...)` — in ONE transaction: enforce the 10/hour `username_flag_resolved` cap (audit-log-COUNT; in-band reject at/over cap, no write); run the conditional queue update; on rows-affected>0 AND `resolution=accept_flagged_username` AND a usable `notes` candidate, `upsertOverride(target_id, notes, adminId)`; write one `admin_actions_log` row `action_type='username_flag_resolved'` with `after_state` recording `resolution` (+ approved candidate on accept). Zero-rows / already-resolved = safe no-op (no audit row). Accept with empty `notes` → resolve + audit but no override + in-band note (no 5xx).
- [ ] 5.2 `releaseHold(...)` — in ONE transaction: enforce the 5/hour `username_hold_released` cap; run the conditional release; on rows-affected>0 write one `admin_actions_log` row `action_type='username_hold_released'` with `before_state` recording the prior `released_at`. Already-released = safe no-op.
- [ ] 5.3 Server-side allowlist for `resolution` (`{accept_flagged_username, reject_flagged_username}`) rejected BEFORE any DB write (never rely on a DB CHECK to 5xx). Reject a `queue_id` whose row is not `trigger='username_flagged'`.
- [ ] 5.4 Atomicity: queue mutation + override write + audit (+ rate-limit count) commit or roll back together (fault-injection-testable).

## 6. Admin routes + DI wiring

- [ ] 6.1 `UsernameOversightRoute` — `GET /admin/username-oversight` inside `authenticate(ADMIN_AUTH_NAME)`; any admin role; lenient `q` + `history_cursor` + `flags_cursor` parsing (malformed → first page / ignored, never 4xx/5xx); HTMX-fragment vs full-page render.
- [ ] 6.2 `POST /admin/username-oversight/flags/{queue_id}/resolve` + `POST /admin/username-oversight/holds/{history_id}/release` — CSRF FIRST (403 + `admin_csrf_violation`), THEN `requireWriteRole`, THEN UUID-parse `{id}` (400 on malformed), THEN service call. No-JS → 303 redirect preserving filters; HTMX → re-render the affected fragment.
- [ ] 6.3 Register the package in `AdminModule` (route + Koin/DI for service + repository) alongside the existing admin features.

## 7. Templates + navigation (Pebble + HTMX, frame 22)

- [ ] 7.1 `username-oversight.peb` extending the shared admin base layout: three sections (flagged-candidate queue showing the candidate + Accept/Reject controls, `username_history` viewer with search box, 30-day-hold list with "Release now"), HTML-escaping every dynamic value, keyset "older" controls, user deep-links to `/admin/users?q=`.
- [ ] 7.2 Write controls rendered only for write-capable roles, each carrying the session CSRF token; Accept copy makes clear it approves the shown candidate for the user's next change (one-shot).
- [ ] 7.3 Sidebar nav entry "Username oversight" (frame 22). Reuse existing vendored admin CSS — do NOT edit `backend/ktor/.../admin/static/*`; if a new `admin.css` rule is unavoidable, re-pin `admin.css`/`htmx.min.js` `SHA256SUMS` (CI lint-lane integrity check, not caught by the local gradle gate).

## 8. Tests (`:backend:ktor`)

- [ ] 8.1 GET render: three sections; unauth → 302; empty states → 200; flagged queue shows candidate (from `notes`) + excludes non-`username_flagged` triggers + resolved rows; hard-deleted user → no deep-link; history search matches old AND new handle case-insensitively; no username-edit control; holds list excludes elapsed holds.
- [ ] 8.2 Pagination (cap + non-overlapping older page + malformed cursor → first page); HTML-escaping of a markup-bearing candidate/handle; HTMX fragment vs plain-GET; GET mutates nothing (audit count unchanged).
- [ ] 8.3 Flag resolution: accept sets `status/resolution/resolved_*` + writes a `username_flag_overrides` row for `(target_id, notes)` + one `username_flag_resolved` audit row; reject resolves + audits + writes NO override; non-`username_flagged` `queue_id` rejected; out-of-allowlist `resolution` rejected pre-write (no 5xx); malformed UUID → 400; re-resolve → no-op (no duplicate audit, no second override); accept on empty-`notes` flag → resolve+audit, no override, no 5xx.
- [ ] 8.4 **Coupling (premium-username-customization gate):** a candidate with a non-consumed override skips moderation + succeeds + sets `consumed_at`; a consumed/absent override → still rejected (`422`); accept does NOT grant a pass to a DIFFERENT flagged candidate (per-candidate scope); the `username_flagged` insert re-opens a resolved row with the latest candidate in `notes`; an override grants at most one pass under **concurrent same-user double-submit** (conditional consume affects one row; the loser is re-moderated or `409`); the repeated-flagged-attempts throttle still applies (a flagged attempt counts against the 10-failed/hour limit) with the standing row reflecting the latest candidate.
- [ ] 8.5 Manual release: clears the hold (`released_at` no longer `> NOW()`) + one `username_hold_released` audit row with prior `released_at` in `before_state`; already-released → no-op; malformed UUID → 400; released handle becomes claimable.
- [ ] 8.6 CSRF/role ordering: missing/invalid CSRF → 403 + `admin_csrf_violation` + no mutation (incl. no override); read-only role → 403 + no mutation; CSRF before role; unauth write → 302 + no write.
- [ ] 8.7 Rate limits: flag resolution 10/hour (under proceeds; at-cap rejected in-band, no queue/override/audit write; per-admin; counts only `username_flag_resolved`); manual release 5/hour (analogous). Atomicity: injected audit-write failure rolls back queue + override.
- [ ] 8.8 If a new `*RoutesTest` opens its own DB-backed Hikari pool, bound it (`autoClose(hikari())`, size 2) for the CI connection budget.
- [ ] 8.9 **Concurrency (admin writes):** two admins resolving the SAME pending flag concurrently → exactly one wins (conditional `WHERE status='pending'`), the loser is a no-op (no second audit row, no second override); two admins releasing the SAME held hold concurrently → exactly one wins (conditional `WHERE released_at > NOW()`), the loser is a no-op.

## 9. Verification & close-out

- [ ] 9.1 Local pre-push gate: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` green (full-gate fresh DB containers if dev-DB seed pollution false-fails specs).
- [ ] 9.2 Manual verify (`/verify-loop` backend + admin): boot Ktor, log in (admin bootstrap + TOTP); seed a `username_flagged` flag (via a PATCH that hits moderation) + a held `username_history` row; open `GET /admin/username-oversight`; Accept the flag, then change the username to the approved candidate as that user and confirm it passes + the override is consumed; Reject another; manual-release a hold; confirm audit rows + rate-limit copy. Capture evidence for the PR (docs/11 §5 DoD).
- [ ] 9.3 Confirm design "Standards conformance" holds — no new Pattern-Registry pattern introduced; no `docs/11` § Pattern Registry amendment required.
- [ ] 9.4 `openspec validate admin-premium-username-oversight --strict` green; PR title/body current for the implementation phase before `/opsx:apply` posts `/review`.
