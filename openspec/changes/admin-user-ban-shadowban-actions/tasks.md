## 1. Pre-flight (grounding + reconciliation)

- [ ] 1.1 Re-read the shipped suspend/unban path (`admin/routes/AdminUserModerationRoute.kt`, `admin/moderation/UserModerationRepository.kt`) and the report-queue ban/shadow helpers (`admin/reportqueue/ReportResolutionRepository.kt`: `applyPermanentBan`, `applyShadowBan`, `insertBanNotification`, `BAN_REASON_CODE`) to confirm the exact reuse seam.
- [ ] 1.2 Render + redline the admin mockup board user-profile controls (`dev/mockups/nearyou-admin-mockup.html`; binding rule `docs/11` § 3.6) — generate the measurement annex (`dev/scripts/mockup-measure.sh`) for the ban / shadow-ban / un-shadow-ban controls + `hx-confirm` affordance; note the exact control layout/labels before building the template.
- [ ] 1.3 File the `docs/06` reconciliation follow-up: `gh issue create --label follow-up,backend` — "docs/06 § Suspension vs Ban prescribes token_version+1 + refresh-token deletion for the ordinary suspend/ban; shipped suspend/unban/report-queue-ban do neither (token_version bump is reserved for the CSAM path per docs/07). This change instead closes the session-continuation hole at the refresh endpoint (D8). Reconcile docs/06's ordinary-ban prescription with shipped reality + the new refresh gate." (Design D2 + D8.)

## 2. Backend — repository (shared enforcement, one transaction each)

- [ ] 2.1 Extract/reuse a single shared internal helper for the permanent-ban column write + sanitized notification (so the user-page ban and the report-queue `ban_author` resolution use one path — design D1). Same for `applyShadowBan` (column write, no notification).
- [ ] 2.2 `UserModerationRepository.permanentBan(...)`: `FOR UPDATE` snapshot → eligibility (reject soft-deleted; no-op-no-audit if already permanent; allow time-bound-suspension escalation capturing prior `suspended_until` in `before_state`) → `is_banned=TRUE, suspended_until=NULL` → `user_banned` audit row → sanitized `account_action_applied` notification → commit atomically. No `token_version` change.
- [ ] 2.3 `UserModerationRepository.shadowBan(...)`: snapshot → eligibility (reject soft-deleted; no-op-no-audit if already shadow-banned) → `is_shadow_banned=TRUE` → `user_shadow_banned` audit row → commit (no notification).
- [ ] 2.4 `UserModerationRepository.shadowUnban(...)`: snapshot → no-op-no-audit if not shadow-banned (soft-deleted-but-shadow-banned still allowed) → `is_shadow_banned=FALSE` → `user_shadow_unbanned` audit row → commit (no notification).
- [ ] 2.5 Add the three audit-logger methods in `admin/auth/AdminAuditLogger.kt` (`user_banned`, `user_shadow_banned`, `user_shadow_unbanned`) with before/after state, IP, NULL-tolerant user_agent, human-admin attribution (never the system sentinel).

## 3. Backend — routes + gating

- [ ] 3.1 `AdminUserModerationRoute.kt`: add `POST /admin/users/{id}/ban`, `/shadow-ban`, `/shadow-unban` inside the `authenticate(ADMIN_AUTH_NAME)` block, mirroring the suspend route (CSRF gate FIRST, then malformed-`{id}` 4xx-not-500 guard, then role gate, then repository call, then 303 / `HX-Redirect` back to the profile/lookup view; inline "quota exceeded" fragment on cap rejection).
- [ ] 3.2 Role-gate via `AdminRoleGate`: ban = owner/admin only; shadow-ban + shadow-unban = all write roles; read_only forbidden from all three (checked inside the tx so a rejected role writes nothing).
- [ ] 3.3 Wire ban + shadow-ban through `DestructiveActionRateLimiter` (counted); leave shadow-unban uncounted. Update the limiter's destructive-set definition to include `user_banned` + `user_shadow_banned` (design D6).
- [ ] 3.4 Read the admin free-text reason from the form body AFTER CSRF validation; pass to the repository for the audit row only (never echoed to the offender).

## 3b. Backend — close the refresh-path enforcement gap (D8)

- [ ] 3b.1 In `auth/routes/AuthRoutes.kt` `POST /api/v1/auth/refresh`, after re-loading the owner, deny when banned (`is_banned = TRUE` → 403 `account_banned`) or soft-deleted (`deleted_at IS NOT NULL` → 401 `token_revoked`) — mirroring the `AuthPlugin` account-state gate (`AuthPlugin.kt:105-123`); issue no new access token. Prefer ordering the check so a denied refresh leaves no dangling usable rotated token (re-order before rotation, or revoke the rotated token on denial). Healthy accounts are unaffected.

## 4. Admin UI

- [ ] 4.1 Add the ban / shadow-ban / un-shadow-ban controls to the `/admin/users/{id}` profile template (Pebble): state-reflecting (ban hidden when already permanent; shadow-ban vs un-shadow-ban mutually exclusive by `is_shadow_banned`), `_csrf` hidden field on each, `hx-confirm` on the destructive ones, ban control rendered only for owner/admin sessions. Redline to the mockup annex from 1.2.

## 5. Tests

- [ ] 5.1 Repository tests: eligibility guards (soft-deleted reject; already-permanent no-op; suspension→ban escalation for BOTH a future-dated AND an elapsed-but-unswept `suspended_until`, asserting `before_state.suspended_until` value-equals the prior instant by parsed-`Instant` equality ±10s; already-shadow-banned no-op; not-shadow-banned shadow-unban no-op; un-shadow-ban of a soft-deleted-but-shadow-banned target applies with `deleted_at` unchanged), column state, audit `action_type` + before/after JSON, ban notification present, shadow-ban/shadow-unban notification absent (assert count-0 on the real notifications table for the target, not merely "fake not invoked"), atomic rollback on forced audit/notification failure.
- [ ] 5.2 Route tests: role-gate matrix (moderator forbidden from ban; admin permitted; moderator permitted for shadow-ban/unban; read_only forbidden from all), CSRF missing/wrong rejection, malformed-`{id}` 4xx-not-500-no-write, redirect/HX-Redirect on success. **Ordering assertions** (mirror the shipped suspend precedent): (a) read_only + missing/wrong CSRF → CSRF violation, NOT a role rejection (CSRF gate runs first); (b) read_only + valid CSRF + malformed id → 403 role rejection, NOT 400 (role gate before id-parse).
- [ ] 5.3 Rate-limit tests: ban + shadow-ban count toward the cap (over-cap rejection with no mutation/no audit); shadow-unban allowed at the cap; standalone direct-`action_type`s not double-counted against `moderation_queue_resolved`.
- [ ] 5.4 Profile-page render test: all controls + `hx-confirm` + quota chip render; un-shadow-ban shown for a shadow-banned target (shadow-ban control absent then); ban control absent for a moderator session; the quota chip reflects seeded `user_banned` + `user_shadow_banned` rows (the new action types advance the displayed count).
- [ ] 5.5 `database`-tagged tests: confirm any new DB-touching test pools `autoClose(hikari())` / size-2 per the CI connection-budget convention; run the touched-area DB tests explicitly (CI includes `database`, excludes `network`).
- [ ] 5.6 Refresh-guard tests (D8): a permanently-banned owner → `POST /api/v1/auth/refresh` returns 403 `account_banned` with no new access token; a suspended owner (future `suspended_until`) → 403 `account_banned`; a soft-deleted owner → 401 `token_revoked`; **happy-path regression**: an active owner still gets 200 + a new token pair.

## 6. Verify + ship

- [ ] 6.1 Local gate: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` green (CI runs both lint frameworks).
- [ ] 6.2 `verify-loop` admin bring-up (Ktor on :8080, admin bootstrap + TOTP): exercise ban / shadow-ban / shadow-unban against a synthetic user; capture screenshot evidence of the controls + post-action state for the PR body (`docs/11` §5 DoD — UI-affecting change).
- [ ] 6.3 Pre-archive staging smoke (branch deploy): assert column state + one audit row each + the ban notification + the over-cap rejection; additionally smoke the D8 refresh gate (a banned user's `POST /api/v1/auth/refresh` → 403 `account_banned`, an active user's refresh still → 200); tick Section 6 before archive.
