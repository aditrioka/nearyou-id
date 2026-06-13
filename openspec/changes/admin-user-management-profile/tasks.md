> **Scope (operator decision, 2026-06-13):** ships board frame 6 — the per-user **profile + history page** (`admin-user-management`), the **warning** action (`admin-user-moderation`), and the **±20/hr destructive-action cap** (`admin-destructive-action-rate-limit`) enforced across BOTH the user-page destructive actions AND the just-shipped report-queue destructive resolutions ("Bundle full enforcement"). Standalone permanent-ban + shadow-ban user-page actions and Premium Username Change Oversight are deferred (proposal § Out of Scope). **No Flyway migration.**

## 1. No migration (verify, add nothing)

- [ ] 1.1 Confirm and add NO migration: `admin_actions_log.action_type VARCHAR(64)` (no CHECK, V16) carries `user_warned`; `notifications.type` CHECK already includes `account_action_applied` (V10); `username_history` (V3) + `users` moderation columns `is_banned`/`suspended_until`/`is_shadow_banned`/`token_version` (V2) + `admin_actions_type_idx (action_type, created_at DESC)` (V16/V17) all exist. No file SHALL be added under `db/migration/`.

## 2. Shared destructive-action rate limiter (`admin-destructive-action-rate-limit`)

- [ ] 2.1 Add `backend/ktor/.../admin/ratelimit/DestructiveActionRateLimiter.kt` with `countInTrailingHour(adminId, conn): Int` — the parameterized COUNT over `admin_actions_log` (design D2): `admin_id = ?` AND `created_at > NOW() - INTERVAL '1 hour'` AND (`action_type IN ('user_warned','user_suspended')` OR (`action_type = 'moderation_queue_resolved'` AND `after_state ->> 'resolution' IN ('suspend_author_7d','ban_author','shadow_ban_author')`)). Read-only; no mutation.
- [ ] 2.2 Add `isAtOrOverCap(adminId, conn): Boolean` = `countInTrailingHour(...) >= 20`. Expose the cap constant `DESTRUCTIVE_ACTION_CAP = 20`.
- [ ] 2.3 The check runs inside the same JDBC connection/transaction as the destructive action so it reads a consistent ledger snapshot — this gives read-consistency but NOT serialization (no `FOR UPDATE` on the ledger), so the accepted ±1 concurrency tolerance (design D4) stands. Do NOT over-claim atomicity in code comments: the cap is an abuse-prevention soft limit, not a hard authz boundary.

## 3. Profile read seam (`admin-user-management`)

- [ ] 3.1 Add `backend/ktor/.../admin/usermanagement/UserProfileRepository.kt`:
  - [ ] 3.1a `loadProfile(userId): UserProfile?` — parameterized read of `users` (id, username, display_name, is_premium, created_at, private_profile_opt_in, is_banned, suspended_until, is_shadow_banned); `null` → caller renders the empty-state.
  - [ ] 3.1b `loadAdminActionHistory(userId): List<...>` — `admin_actions_log` WHERE `target_type='user' AND target_id=?`, newest-first, reusing the `AdminActionsLogRepository` row shape (time, acting admin human-readable, action_type, reason, before_state/after_state) where practical.
  - [ ] 3.1c `loadUsernameHistory(userId): List<...>` — `username_history` WHERE `user_id=?` (old_username, new_username, changed_at), newest-first.

## 4. Warning action + audit writer (`admin-user-moderation`)

- [ ] 4.1 Add `UserModerationRepository.warn(userId, reason, adminId, ip, userAgent): WarnOutcome` — ONE transaction mirroring `suspend`: eligibility guard (`deleted_at IS NULL`); rate-limit guard FIRST (`DestructiveActionRateLimiter.isAtOrOverCap` → `RateLimited`, no write); write one `admin_actions_log` `user_warned` row (human admin, before/after, audit-only `reason`); insert one `notifications` row `type='account_action_applied'`, `body_data.action_type='warning'` (NO admin free-text in body_data); NO `users` mutation. Sealed outcome (`Applied`/`RateLimited`/`TargetDeleted`).
- [ ] 4.2 Add `AdminAuditLogger.userWarned(...)` mirroring `logUserSuspended` (writes the `user_warned` row in the passed transaction).
- [ ] 4.3 Add the rate-limit guard to the existing `suspend` path (`UserModerationRepository.suspend` / its route): at-or-over cap → `RateLimited`, no mutation, no audit row. Leave `unban` ungated (restorative).

## 5. Routes (mirror `AdminUserModerationRoute` gate order)

- [ ] 5.1 In `AdminUserModerationRoute.kt`, add `GET /admin/users/{id}` (INSIDE `authenticate(ADMIN_AUTH_NAME)`, any role): parse `{id}` UUID (malformed → 4xx inline, no 500) → `UserProfileRepository.loadProfile` (null → empty-state 200) → load both histories → render `user-profile.peb` (full page) or the history fragment for HTMX; read-only (no audit write).
- [ ] 5.2 Add `POST /admin/users/{id}/warn`: `AdminCsrfGate.validateCsrf` FIRST → `AdminRoleGate.requireWriteRole` → parse `{id}` (malformed → 4xx, no write) → `principal<AdminPrincipal>()` → `repo.warn(...)` → outcome mapping (`Applied` → 303 / `HX-Redirect` back to the profile; `RateLimited` → inline "quota exceeded"; `TargetDeleted` → inline message). Reason read via `AdminCsrfGate.formParametersAfterValidation`.
- [ ] 5.3 Wire the rate-limit guard into the report-queue destructive resolutions: in `ReportResolutionRepository.resolveQueueItem`, for `resolution IN {suspend_author_7d, ban_author, shadow_ban_author}`, check `isAtOrOverCap` BEFORE enforcement → new sealed-outcome case `RateLimited` (queue stays `pending`, no `users` write, no audit row); map it in `AdminReportResolutionRoute` to an inline "quota exceeded" message. `keep`/`hide`/report `decision` stay ungated.
- [ ] 5.4 Wire the new GET/POST in `AdminModule.kt` INSIDE `authenticate(ADMIN_AUTH_NAME)`.

## 6. Templates — frame 6 (Pebble + HTMX + vendored CSS; mockup consult per docs/11 §3.6)

- [ ] 6.1 Generate the frame-6 + frame-7 measurement annex (`dev/scripts/mockup-measure.sh nearyou-admin-mockup.html 6` / `7`) at build time; translate tokens into the vendored panel CSS (no inline styles, no CDN, no client framework). Apply the frame-4b responsive contract (fluid layout).
- [ ] 6.2 Add `user-profile.peb` — profile block (identity + moderation state, all values autoescaped) + the action bar (suspend/unban/warn controls each with a `_csrf` hidden field) + the live destructive-quota chip ("N/20 this hour", from `DestructiveActionRateLimiter.countInTrailingHour`).
- [ ] 6.3 Add `user-history-table.peb` — the merged admin-action + username-history view (frame-7 audit-log styling: time/admin/action/reason/state-disclosure), newest-first, empty-state when no rows; an HTMX-swappable fragment with a plain-GET fallback.
- [ ] 6.4 `users-result.peb` — deep-link a resolved lookup hit to `/admin/users/{id}`.
- [ ] 6.5 Verify Pebble autoescape on every rendered value (no `| raw`).

## 7. Tests (Kotest — one per spec scenario; do NOT skip any)

- [ ] 7.1 Profile GET: existing user → 200 with identity + moderation state; serving writes no audit row and mutates nothing; unauth → 302 `/admin/login`; `display_name` markup HTML-escaped.
- [ ] 7.2 History merge: a prior `user_suspended` row appears; a `username_history` change appears (`old`→`new`); no-history user → empty-state 200; newest-first ordering INCLUDING an interleaved case (rows from BOTH `admin_actions_log` and `username_history` with interleaved timestamps render in correct combined newest-first order, not merely newest-first within one source).
- [ ] 7.3 Action controls + chip render: suspend/unban/warn controls each with `_csrf`; quota chip shows the acting admin's count against 20 — assert the chip value equals a SEEDED destructive-action count (e.g. seed 14 rows → chip reads "14/20"), AND that rendering the chip writes no `admin_actions_log` row / mutates nothing (the read-only half of the `admin-destructive-action-rate-limit` count-exposure scenario).
- [ ] 7.4 Profile GET robustness: non-UUID `{id}` → 4xx not 500, no mutation; SQL-metacharacter id → not 500, `users` table intact; unknown well-formed UUID → empty-state 200 (not 404).
- [ ] 7.5 Lookup deep-link: `GET /admin/users?q=<uuid>` result fragment contains a link to `/admin/users/<uuid>`.
- [ ] 7.6 Warning happy path: writes exactly one `user_warned` audit row + exactly one `account_action_applied` notification (`body_data.action_type='warning'`); `is_banned`/`suspended_until`/`is_shadow_banned`/`token_version` unchanged; audit `admin_id` = acting admin (not the `system` sentinel).
- [ ] 7.7 Warning atomicity: injected notification-insert failure → full rollback (no `user_warned` row, no notification).
- [ ] 7.8 Warning reason discipline: distinctive `reason` text appears in `admin_actions_log.reason` but NOT anywhere in the notification `body_data`.
- [ ] 7.9 Warning gating: unauth → 302 no write; missing/invalid CSRF → 403 with a POSITIVE assertion that an `admin_csrf_violation` row IS written AND no `user_warned` row is written; CSRF-before-role (read_only + bad CSRF → CSRF rejection, not role rejection); read_only + valid CSRF → role-rejected no write; malformed `{id}` → 4xx no write (and `parseTargetId` stays AFTER the role gate, so a read_only admin POSTing a malformed id gets the role rejection, not a parse 400).
- [ ] 7.10 Rate-limit count (`admin-destructive-action-rate-limit`): a mix of 3 `user_suspended` + 2 `user_warned` + 1 `ban_author` (in window) → count 6; unban + hide + report_resolved + 5 out-of-window suspends → count 0.
- [ ] 7.11 Rate-limit reject: at 20, a 21st `warn` and a 21st `suspend` → "quota exceeded" (not 5xx), no new audit row, count stays 20 — assert the SPECIFIC target columns are unchanged (`is_banned`/`suspended_until` for the suspend attempt; `is_banned`/`suspended_until`/`is_shadow_banned`/`token_version` for the warn attempt), not just a generic "no mutation"; at 19, a destructive action applies and count → 20.
- [ ] 7.12 Rate-limit scope: admin A at 20 does not block admin B (0); a non-destructive action (`unban`, `keep`/`hide`) applies even at the cap.
- [ ] 7.13 Report-queue cap enforcement: at 20, `resolution=ban_author` → "quota exceeded", queue stays `pending`, author `is_banned` unchanged, no audit row; at the cap `resolution=hide` and `decision=dismissed` still apply; at 19, `resolution=suspend_author_7d` applies + one audit row.

## 8. Verification + follow-ups

- [ ] 8.1 Gates green locally: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test`.
- [ ] 8.2 UI-affecting (admin panel) — manual bring-up via `verify-loop` before archive: admin login (bootstrap + TOTP) → open a user's `/admin/users/{id}` → screenshot the profile + history + quota chip; issue a warning and confirm the audit row + notification + unchanged state; exercise the cap. Evidence in the PR body (docs/11 §5 DoD).
- [ ] 8.3 At archive: file the three deferred `follow-up` issues (label `follow-up` + `admin`) — standalone permanent-ban action, standalone shadow-ban action, Premium Username Change Oversight — and reconcile the `admin-user-moderation` spec Purpose forward-reference to `admin-user-management` now that it exists.
