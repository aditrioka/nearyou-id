## Context

The admin panel's `admin-user-moderation` capability ships `/admin/users` lookup + three state actions (`suspend` 7-day, `unban`, `warn`), each as a `POST /admin/users/{id}/<action>` route flowing Route → `UserModerationRepository` JDBC transaction, writing one immutable `admin_actions_log` row via `AdminAuditLogger`, gated by `AdminCsrfGate` + `AdminRoleGate`, and (for destructive actions) checked against the shared `DestructiveActionRateLimiter` (20/admin/trailing-hour, ledger = the audit trail). The `admin-user-management` capability renders the `/admin/users/{id}` profile page, already reading `is_banned` / `suspended_until` / `is_shadow_banned` (`UserProfileRepository`) and showing a destructive-quota chip.

The report-queue path (`ReportResolutionRepository`) **already** contains proven, sanitized helpers for the two enforcement primitives this change exposes from the user page: `applyPermanentBan` (`is_banned = TRUE, suspended_until = NULL`), `applyShadowBan` (`is_shadow_banned = TRUE`, no notification), and `insertBanNotification` (sanitized `account_action_applied`, fixed `BAN_REASON_CODE`, `actor_user_id` NULL). The `admin-user-moderation` spec Purpose pre-declares permanent-ban + shadow-ban as deferred extensions of this capability.

Constraints: admin module is exempt from the `visible_*`/block-join lint; `admin_actions_log.action_type` is a free `VARCHAR(64)` (no CHECK → new action types need no migration); the per-request `AuthPlugin` (`AuthPlugin.kt:116`) already 403s any `is_banned = TRUE` user (`account_banned`) and `AuthRoutes.kt:128` blocks banned users at login.

## Goals / Non-Goals

**Goals:**
- Add `ban` (permanent), `shadow-ban`, and `shadow-unban` as first-class `/admin/users/{id}/…` actions with the same safety contract as the shipped suspend/unban (CSRF, role-gate, malformed-id safety, atomic audit, human-attributed).
- Exactly one ban / shadow-ban behavior across the report-queue and user-page entry points (reuse/extract the shipped helpers).
- Count the two new destructive actions toward the existing per-admin rate-limit cap.
- Surface the controls on the profile page, redlined to the admin mockup, with `hx-confirm` on every destructive action.

**Non-Goals:**
- No Flyway migration; no new columns; no schema change.
- No mobile change (the offender-facing surfaces — login "Akun dinonaktifkan" message, etc. — already exist per `docs/03`).
- No change to the suspend/unban/warn behavior, nor to the report-queue resolutions.
- No `token_version` bump / refresh-token deletion (see D2) and no amendment to `docs/06` here (tracked as a separate doc-reconciliation follow-up).

## Decisions

### D1 — Reuse the shipped pattern; no divergent second implementation (anti-patchwork)
The new routes live in the existing `AdminUserModerationRoute.kt`; the new transactional methods in `UserModerationRepository.kt`; audit via `AdminAuditLogger`; gating via `AdminRoleGate` + `AdminCsrfGate`; rate-limit via `DestructiveActionRateLimiter`. The permanent-ban / shadow-ban column writes + the sanitized ban notification reuse the report-queue's `applyPermanentBan` / `applyShadowBan` / `insertBanNotification` — extract them into a shared internal helper (or call the existing ones) so there is **one** enforcement path. *Alternative considered:* a fresh ban implementation on the user page — rejected: it would create two ban behaviors (the patchwork anti-pattern `docs/11` forbids). No new Pattern-Registry pattern is introduced.

### D2 — Session-termination is columns-only (mirror shipped), NOT a `token_version` bump
Permanent ban sets `is_banned = TRUE, suspended_until = NULL` and nothing else. It does **not** bump `token_version` or delete refresh tokens — mirroring the deliberate shipped behavior (`UserModerationRepository.kt`: *"`token_version` is NOT modified (mirrors the shipped suspend/unban)"*) and the report-queue ban. Enforcement is immediate and correct via the per-request `AuthPlugin` `is_banned` check (→ 403 `account_banned`) plus the login-time `isBanned` block. *Reconciliation:* `docs/06` § Suspension vs Ban still prescribes `token_version = token_version + 1` + refresh-token deletion for ban — this is **stale** relative to all shipped moderation code. The proposal mirrors shipped behavior; introducing a `token_version`-bumping ban here would create a divergent second pattern (D1) and contradict shipped suspend. A `follow-up` issue will reconcile `docs/06`; this change does not amend it. *Alternative considered:* honor `docs/06` literally (bump + delete) — rejected for the divergence + patchwork reasons above; if the operator wants session-token invalidation it should be a deliberate cross-cutting change applied to suspend AND ban together, not smuggled into one new action.

### D3 — Notifications: ban notifies (sanitized), shadow-ban / un-shadow-ban do not
Permanent ban reuses the report-queue's sanitized `account_action_applied` notification (fixed `BAN_REASON_CODE`, no free-text reason, `actor_user_id` NULL) for cross-entry-point consistency. Shadow-ban and un-shadow-ban write **no** notification (a shadow ban is invisible to the offender by design; its reversal is likewise silent). *Alternative considered:* suppress the ban notification because a banned user is 403'd — rejected: matching the report-queue ban's existing notification keeps the two ban paths identical (D1), and the suspend precedent already writes the same notification type.

### D4 — Role-gating per the admin mockup
Permanent ban: **owner/admin only** (admin mockup `nearyou-admin-mockup.html` line 508-509 "ban permanen tier owner/admin"; symmetric with the shipped "lifting a permanent ban is owner/admin only"). Shadow-ban + un-shadow-ban: **all write roles** (owner/admin/moderator; mockup "shadow-ban boleh semua role tulis"). `read_only` is forbidden from all three. Enforcement is inside the transaction so a rejected role writes nothing.

### D5 — Un-shadow-ban included as the restorative companion (separable)
Today nothing reverses a shadow ban (`unban` clears `is_banned` only). `shadow-unban` (`is_shadow_banned = FALSE`, restorative, ungated beyond write-role, not rate-limited, no notification, no-op-no-audit when not shadow-banned) closes that gap and mirrors the restorative `unban`. It is **not** a tracked follow-up (#283/#284 cover apply only) — it is included because a one-way shadow-ban with no admin reversal is an operational hazard, and the operator prefers complete scope over a minimal slice. *Separability:* if review/the operator wants to trim it, it is an independent requirement + route and can be dropped without touching the ban/shadow-ban requirements.

### D6 — Rate-limit destructive-set extension
`user_banned` + `user_shadow_banned` are punitive → added to the counted destructive set (the direct-`action_type` arm, alongside `user_warned` / `user_suspended` / `admin_chat_redaction`). `user_shadow_unbanned` is restorative → **not** counted (mirrors `unban`, content keep/hide, report bookkeeping). The cap stays a soft ±1 abuse-prevention guard sourced from the audit ledger.

### D7 — Eligibility guards (mirror suspend semantics)
- **Ban**: reject soft-deleted (`deleted_at IS NOT NULL`) → no state change/no audit; reject (no-op, no audit) if already permanently banned (`is_banned = TRUE AND suspended_until IS NULL`); **allow** escalating a time-bound suspension → permanent ban (capture the prior `suspended_until` in audit `before_state`).
- **Shadow-ban**: reject soft-deleted; no-op (no audit) if already `is_shadow_banned = TRUE`.
- **Un-shadow-ban**: no-op (no audit) if `is_shadow_banned = FALSE`; soft-deleted target MAY be un-shadow-banned (mirrors "soft-deleted-but-banned may be unbanned").

## Risks / Trade-offs

- **Shipped-vs-docs divergence on session termination (D2)** → Mitigation: design mirrors shipped + AuthPlugin enforcement is verified per-request; the stale `docs/06` line is captured as a `follow-up` rather than silently diverged.
- **Two ban entry points drifting** → Mitigation: D1 forces a single shared helper; tests assert the user-page ban produces the same column state + notification as the report-queue ban.
- **Rate-limit ledger double-counting** → Mitigation: the new actions log direct `action_type`s disjoint from `moderation_queue_resolved`, so the report-queue `after_state->>'resolution'` arm and the direct-`action_type` arm never both match one row (same property the existing `admin_chat_redaction` relies on).
- **Escalation race (suspend→ban under FOR UPDATE)** → Mitigation: the eligibility check + UPDATE run against a `SELECT … FOR UPDATE` snapshot in one transaction, as the shipped suspend does.

## Migration Plan

No Flyway migration. Deploy is code-only; rollback is a code revert (no schema to undo). Staging smoke (pre-archive): exercise ban / shadow-ban / shadow-unban against a synthetic user, assert column state + one audit row each + the ban notification, and assert the rate-limit cap rejects the over-cap attempt.

## Standards Conformance

Per `docs/11-Engineering-Standards.md`: this change builds on the existing **backend-layering pattern** (Route → Repository → JDBC transaction, parameterized statements, `FOR UPDATE` snapshot for read-modify-write) and the admin cross-cutting patterns (`AdminAuditLogger`, `AdminRoleGate`, `AdminCsrfGate`, `DestructiveActionRateLimiter`). It introduces **no new Pattern-Registry pattern** and declares **no deviation** → no `docs/11` § Pattern Registry amendment is required in this PR. Admin-UI controls follow the Pebble + HTMX + vendored-CSS admin pattern and MUST be redlined to the admin mockup board (`docs/11` § 3.6).

## Open Questions

- None blocking. (D5 un-shadow-ban is flagged as separable should the operator prefer to trim it; the default is to include it.)
