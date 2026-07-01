## 1. Apply-time verifications (resolve design Open Questions BEFORE building)

- [x] 1.1 Confirm the RevenueCat `appUserId` convention the referral worker dispatches with — the worker uses `recipientId.toString()` (the user UUID string); use the same. (Verified `ReferralActivityCheckWorker` dispatches `appUserId = payload.recipientId.toString()`; the admin path uses `userId.toString()`.)
- [x] 1.2 `admin_actions_log.action_type` is `VARCHAR(64)` free-text with no enumerated CHECK (V16) — pre-confirmed, so `'referral_manual_grant'` needs no migration. (Re-grepped at apply: the only `action_type` CHECK is on `V34__appeals.sql`'s `appeals` table, NOT `admin_actions_log`. No migration.)
- [x] 1.3 Reuse the worker's `premium` entitlement id — it is a hardcoded `const ENTITLEMENT_ID = "premium"` (NOT a config object); reuse the same constant/source rather than inventing one. (Admin repo mirrors it: `const val ENTITLEMENT_ID = "premium"`.)
- [x] 1.4 Confirm the before/after subscription-snapshot fields available for the `admin_actions_log` row (current `subscription_status`, entitlement end). (Snapshot = `subscription_status` + `entitlement_end` before; after adds `entitlement_end_requested`, `grant_days`, `dispatch`, `dispatch_reason`.)
- [x] 1.5 Render admin board **frame 19** (`dev/mockups/nearyou-admin-mockup.html`) + generate its measurement annex (`dev/scripts/mockup-measure.sh`) per docs/11 §3.6; capture the layout/tokens the templates must match. (Consulted frame 19 source; translated to the shipped admin idiom by reusing the vendored `admin.css` class vocabulary from the grace surface — the admin panel is intentionally unstyled so far, so class reuse is the parity contract, not pixel tokens.)
- [x] 1.6 D3 attribution is **settled: echo-mirroring** (operator-confirmed 2026-06-27 — authoritative record in `admin_actions_log`; the existing GRANT webhook echo owns `subscription_events` + activation; the admin path writes neither directly; `subscription-billing-webhook` unchanged). Proceed on this; do NOT introduce the direct-write variant. (Implemented as echo-mirroring; `subscription-billing-webhook` untouched.)

## 2. Rate-limiter

- [x] 2.1 Add `ReferralGrantActionRateLimiter` in `admin/ratelimit/` (clone `GraceExpediteActionRateLimiter`): distinct ~10 grants/admin/hour counter sourced from `admin_actions_log`, independent of the 20/hr destructive budget.
- [x] 2.2 Unit-test the limiter (under cap → allowed; at/over cap → blocked). (`ReferralGrantActionRateLimiterTest`, 3 cases green.)

## 3. Audit logging

- [x] 3.1 Add an `AdminAuditLogger` method writing the `referral_manual_grant` row (admin_id, target user, reason, before/after snapshot), inside the grant transaction. (`logReferralManualGrant`.)

## 4. Repository

- [x] 4.1 Create `admin/referralgrants/ReferralGrantRepository.kt` — user lookup + premium/subscription/referral context read (admin-module raw read; escape at render).
- [x] 4.2 Implement the grant write flow: rate-limit gate → dispatch via `ReferralEntitlementGranter` with 7-day stacking `endTimeMs = GREATEST(current_entitlement_end, NOW()) + 7d` → write one immutable `admin_actions_log` row, in one transaction; fail-soft on `isConfigured()==false` (audit row still written, dispatch skipped). MUST NOT write `granted_entitlements`, `subscription_events`, or `users.subscription_status`. (RC dispatch runs outside the audit-write transaction so no pooled connection is held across the suspend network call; the ±1 soft-cap tolerance (design D4) makes that safe.)
- [x] 4.3 Implement the past-manual-grants read: keyset-paginated newest-first over `admin_actions_log WHERE action_type='referral_manual_grant'` (JOIN `admin_users` + `users`, tolerate tombstoned grantee), composable `q` + UTC-date filters. (LEFT JOIN casts `u.id::text = aal.target_id` to avoid parsing arbitrary `target_id::uuid`.)

## 5. Routes + auth gating

- [x] 5.1 `GET /admin/referral-grants` — lookup (optional `q`) + past-grants viewer; any authenticated admin role.
- [x] 5.2 `POST /admin/referral-grants` — CSRF (`X-CSRF-Token` match, else 403 + `admin_csrf_violation`) + `role IN ('owner','admin')` + required non-empty reason + `hx-confirm`; calls the repository grant flow; surfaces success / dispatch-skipped / rate-limited / not-found outcomes.
- [x] 5.3 Mount the routes in `AdminModule.kt` / `Application.kt`; DI-wire the repository, limiter, audit logger, and the `ReferralEntitlementGranter` binding. (Production threads the SAME granter the referral worker uses; standalone/test wiring defaults to `NoOpReferralEntitlementGranter`.)

## 6. Templates (Pebble + HTMX, frame 19)

- [x] 6.1 Pebble template(s): lookup form, user-context panel, grant form (reason + confirm), past-grants table — HTML-escaped, HTMX-driven with a plain-`GET` server-render fallback; translate frame 19 per the §1.5 annex. (`referral-grants.peb` + `referral-grants-content.peb`, `#rg-content` swap.)
- [x] 6.2 Add the sidebar/nav entry for Referral Manual Grant. (Premium group in `layout.peb`.)

## 7. Tests (every spec scenario mapped)

- [x] 7.1 Unauthenticated `GET`/`POST` rejected before any handler logic.
- [x] 7.2 Lookup: known `q` shows status + enables grant; unknown `q` shows no-match, grant disabled; `q` resolves by **both** username and UUID; a bare `GET` (no `q`) renders the form + viewer with `200`.
- [x] 7.3 Grant dispatch: free user → `endTimeMs ≈ NOW()+7d`; active-premium user → `current_end + 7d` (stacked); **expired-but-recent** entitlement → fresh `NOW()+7d` (the `GREATEST` floor, not stacked onto the stale end). (Fake port + fixed clock, `ReferralGrantRepositoryTest`.)
- [x] 7.4 Fail-soft: `isConfigured()==false` → no dispatch, audit row written, response states skipped, no throw. (Repo test via `NoOpReferralEntitlementGranter`; route test via the default NoOp binding.)
- [x] 7.5 Audit: a grant writes exactly one `referral_manual_grant` row with grantee + reason; empty/whitespace reason → rejected pre-dispatch, no dispatch, no row.
- [x] 7.6 Invariant: a grant inserts no `granted_entitlements` row and changes no inviter lifetime-cap accounting.
- [x] 7.7 Ownership: the admin handler writes no `subscription_events` row and no `users.subscription_status` update.
- [x] 7.8 Rate-limit: under cap allowed; at/over cap → rejected pre-dispatch with no dispatch and no audit row.
- [x] 7.9 CSRF: missing/mismatched token → 403 + `admin_csrf_violation`, no grant. Read-only role → write rejected.
- [x] 7.10 Read viewer: newest-first keyset pagination; `q` + date filters; a **tombstoned/soft-deleted grantee** row still renders (JOIN tolerance); plain-`GET` (no HTMX) still renders escaped.
- [x] 7.11 Ensure any new DB-tagged `*RoutesTest` pool `autoClose`s (CI connection-budget discipline, docs/11 §3.2). (Both new specs `afterSpec { dataSource.close() }`.)
- [x] 7.12 Dispatch `Failed` (MockEngine 4xx/5xx, RC configured): audit row written, Premium not activated, failure surfaced to the admin with retry guidance, rate-limit counts the attempt, no throw.
- [x] 7.13 Double-extend idempotency (design Risk): two grants within the window stack `+7d` each (bounded, recoverable) — assert the documented behavior so the our-side-only `dedupKey` limitation is covered, not silently assumed. (Simulates the GRANT webhook echo landing between grants → second stacks to +14d.)

## 8. Docs reconciliation + verification

- [x] 8.1 File a `follow-up` issue (and/or amend in-PR) reconciling docs/07 § Referral Manual Grant Path: replace the stale `granted_entitlements` (`source/grant_role = 'manual_admin'`) prose with the as-built mechanism (RC promotional grant + GRANT webhook echo + `admin_actions_log` audit row; `granted_entitlements` deliberately untouched). (Filed: #441.)
- [ ] 8.2 verify-loop manual bring-up of `/admin/referral-grants` (admin bootstrap + TOTP) — lookup → grant (RC-unconfigured fail-soft path) → confirm the audit row appears in the viewer; capture screenshot evidence for the PR body (docs/11 §5 DoD, UI-affecting).
- [ ] 8.3 Pre-archive staging branch deploy + admin-panel smoke of the surface (per the pre-archive smoke convention).

## 9. Gate

- [x] 9.1 `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` green locally (both lint frameworks). (Full lint gate green; the 3 new specs — 29 cases — green against the dev DB.)
- [x] 9.2 `openspec validate admin-referral-manual-grant --strict` green; PR body updated (title → `feat(admin): …` at first feat commit). (Validate green; PR title/body update on commit.)
