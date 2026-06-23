## 1. Pre-flight (grounding + reconciliation)

- [x] 1.1 Re-read the shipped suspend/unban path (`admin/routes/AdminUserModerationRoute.kt`, `admin/moderation/UserModerationRepository.kt`) and the report-queue ban/shadow helpers (`admin/reportqueue/ReportResolutionRepository.kt`: `applyPermanentBan`, `applyShadowBan`, `insertBanNotification`, `BAN_REASON_CODE`) to confirm the exact reuse seam.
- [x] 1.2 Consulted the admin mockup board (`dev/mockups/nearyou-admin-mockup.html` line 508-509) for the binding role contract — permanent ban = owner/admin tier, shadow-ban = all write roles; the profile-page controls reuse the page's existing no-JS plain-form styling (no new visual frame / measurement annex needed — confirmation affordances live on the htmx report-queue surface).
- [x] 1.3 Filed the `docs/06` reconciliation follow-up: [#377](https://github.com/aditrioka/nearyou-id/issues/377) — docs/06 § Suspension vs Ban's ordinary-ban `token_version`+1 / refresh-token-deletion prescription is stale vs shipped (bump is CSAM-path-only per docs/07); this change closes the hole at the refresh endpoint (D8). (Design D2 + D8.)

## 2. Backend — repository (shared enforcement, one transaction each)

- [x] 2.1 Reused the report-queue ban/shadow primitives (identical column UPDATEs + the sanitized ban notification matching `ReportResolutionRepository.insertBanNotification` / `BAN_REASON_CODE`, with cross-ref comments so they stay one behavior — design D1). No divergent second implementation.
- [x] 2.2 `UserModerationRepository.permanentBan(...)`: `FOR UPDATE` snapshot → eligibility (reject soft-deleted; no-op-no-audit if already permanent; allow time-bound-suspension escalation capturing prior `suspended_until` in `before_state`) → `is_banned=TRUE, suspended_until=NULL` → `user_banned` audit row → sanitized `account_action_applied` notification → commit atomically. No `token_version` change.
- [x] 2.3 `UserModerationRepository.shadowBan(...)`: snapshot → eligibility (reject soft-deleted; no-op-no-audit if already shadow-banned) → `is_shadow_banned=TRUE` → `user_shadow_banned` audit row → commit (no notification).
- [x] 2.4 `UserModerationRepository.shadowUnban(...)`: snapshot → no-op-no-audit if not shadow-banned (soft-deleted-but-shadow-banned still allowed) → `is_shadow_banned=FALSE` → `user_shadow_unbanned` audit row → commit (no notification).
- [x] 2.5 Added the three audit-logger methods in `admin/auth/AdminAuditLogger.kt` (`user_banned`, `user_shadow_banned`, `user_shadow_unbanned`) with before/after state, IP, NULL-tolerant user_agent, human-admin attribution (never the system sentinel).

## 3. Backend — routes + gating

- [x] 3.1 `AdminUserModerationRoute.kt`: added `POST /admin/users/{id}/ban`, `/shadow-ban`, `/shadow-unban` inside the `authenticate(ADMIN_AUTH_NAME)` block, mirroring the suspend route (CSRF gate FIRST, then role gate, then malformed-`{id}` 4xx-not-500 guard, then repository call, then 303 / `HX-Redirect`; inline "quota exceeded" message on cap rejection).
- [x] 3.2 Role-gate: ban = `requireOwnerOrAdmin` (route-level, owner/admin only); shadow-ban + shadow-unban = `requireWriteRole` (all write roles); read_only forbidden from all three.
- [x] 3.3 Wired ban + shadow-ban through `DestructiveActionRateLimiter` (counted via the repo's `isAtOrOverCap`); shadow-unban uncounted. Extended the limiter's `COUNT_SQL` direct-arm to include `user_banned` + `user_shadow_banned` (design D6).
- [x] 3.4 Read the admin free-text reason from the form body AFTER CSRF validation; passed to the repository for the audit row only (never echoed to the offender).

## 3b. Backend — close the refresh-path enforcement gap (D8)

- [x] 3b.1 In `auth/routes/AuthRoutes.kt` `POST /api/v1/auth/refresh`, after re-loading the owner, deny when banned (`is_banned = TRUE` → 403 `account_banned`) or soft-deleted (`deleted_at IS NOT NULL` → 401 `token_revoked`) — mirroring the `AuthPlugin` account-state gate; no new access token issued, the rotated refresh token's raw value is discarded (denied caller gains no usable token). Healthy accounts unaffected.

## 4. Admin UI

- [x] 4.1 Added the ban / shadow-ban / un-shadow-ban controls to `user-profile.peb` as CSRF-protected plain POST forms matching the existing suspend/warn/unban pattern: state-reflecting (ban hidden when `isPermanentlyBanned`; shadow-ban vs un-shadow-ban mutually exclusive by `is_shadow_banned`), `_csrf` hidden field on each, ban control rendered only for owner/admin sessions (`adminRole`).

## 5. Tests

- [x] 5.1 Repository tests (in `UserModerationRepositoryTest`, 49 total green): eligibility guards (soft-deleted reject; already-permanent no-op; suspension→ban escalation for future-dated AND elapsed-but-unswept `suspended_until` with parsed-`Instant` `before_state` equality; already-shadow-banned no-op; not-shadow-banned shadow-unban no-op; un-shadow-ban of a soft-deleted-but-shadow-banned target with `deleted_at` unchanged), column state, audit before/after JSON, ban notification present + sanitized (free-text absent), shadow-ban/shadow-unban notification count-0, atomic rollback on forced audit + notification failure, token_version unchanged.
- [x] 5.2 Route tests (in `AdminUserModerationRouteTest`, 59 total green): role-gate matrix (moderator forbidden from ban, admin permitted, moderator permitted for shadow-ban/unban, read_only forbidden), CSRF missing rejection, malformed-`{id}` 400-not-500, redirect on success. Ordering: (a) read_only + wrong CSRF → CSRF violation row (CSRF before role); (b) read_only + valid CSRF + bad id → 403 (role before id-parse).
- [x] 5.3 Rate-limit tests (`DestructiveActionRateLimiterTest`, 5 green): standalone `user_banned` + `user_shadow_banned` count; `user_shadow_unbanned` excluded; over-cap ban/shadow rejected with no mutation/no audit (repo-level); shadow-unban applies at the cap; no double-count vs `moderation_queue_resolved`.
- [x] 5.4 Profile-page render tests: ban + shadow-ban controls render for owner; ban control absent for a moderator; un-shadow-ban shown for a shadow-banned target (shadow-ban absent); quota chip reflects seeded `user_banned` + `user_shadow_banned` rows ("3/20").
- [x] 5.5 `database`-tagged tests added to the existing specs (reuse their `afterSpec { dataSource.close() }` pools — no new pool, respecting the CI connection budget); touched-area DB specs run explicitly green.
- [x] 5.6 Refresh-guard tests (`SignInFlowTest`, 10 green): permanently-banned owner → 403 `account_banned`; suspended owner → 403 `account_banned`; soft-deleted owner → 401 `token_revoked`; happy-path regression: active owner still gets 200 + a new token pair.

## 6. Verify + ship

- [x] 6.1 Local gate: `ktlintCheck` + `detekt` + `:lint:detekt-rules:test` green; touched specs green (49+59+5+10). Full `:backend:ktor:test` run against fresh disposable PG+Redis containers (CI-equivalent, avoids dev-DB pollution).
- [x] 6.2 `verify-loop` admin bring-up DONE (Ktor on :8081 — :8080 held by another process; admin bootstrap + TOTP): drove `/admin/users/{id}` across active / shadow-banned / perma-banned users (controls render per state — ban+shadow-ban for active, shadow-unban for shadow-banned, ban hidden for permanent); exercised shadow-ban (303, `is_shadow_banned=TRUE`, audit, 0 notifications) + permanent ban (303, `is_banned=TRUE/suspended_until=NULL`, audit, sanitized notification) live; quota chip → 2/20. Screenshot evidence posted to PR #373 (orphan `evidence/admin-user-ban-shadowban-actions` branch). (`docs/11` §5 DoD.)
- [x] 6.3 Pre-archive staging smoke DONE (branch deploy run 27906347164, success): `/health/ready` 200; the three NEW routes (`POST …/ban`, `…/shadow-ban`, `…/shadow-unban`) return 302 → `/admin/login` (mounted + auth-gated on the deployed branch; a control bogus route returns 404, proving route existence); `POST /api/v1/auth/refresh` with an invalid token returns a structured 401 `refresh_token_invalid` (D8 guard intact, no 5xx). No deploy-config surface changed (no new secret slot / env var / migration / module), so the deeper authenticated state-mutation smoke is redundant with the green full suite + the live local verify-loop bring-up; the D8 banned-403 path is unit-covered (`SignInFlowTest`).
