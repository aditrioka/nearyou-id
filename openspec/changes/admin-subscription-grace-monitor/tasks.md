## 1. Read data layer (query + row model)

- [x] 1.1 Add a `GraceMonitorRow` data class (username, user id, store/platform, retry-since, latest webhook event_type + timestamp, already-expedited indicator {admin, timestamp}) in the admin package.
- [x] 1.2 Implement the single keyset query (design D3): `users` filtered to `subscription_status = 'premium_billing_retry' AND deleted_at IS NULL` (index-served by `users_subscription_idx`), LEFT JOIN LATERAL latest `subscription_events` row, LEFT JOIN LATERAL latest `admin_actions_log` row with `action_type = 'subscription_grace_expedite'`; keyset-ordered newest-retry-first. No N+1 (docs/11 §3.2).
- [x] 1.3 Implement composable optional filters: `q` (exact case-insensitive username OR exact UUID) and `store`; absent filter does not constrain. Add the total-in-window count query for the summary.
- [x] 1.4 Match the admin read-only-monitor lint posture for the raw `users` / `subscription_events` read (mirror `AdminPrivacyFlipsRepository`): the admin module is exempt from the `visible_*` views, and neither `BlockExclusionJoinRule` nor `RawFromPostsRule` fires on `FROM users` / `FROM subscription_events` — so **no functional block-exclusion annotation is required**. Add only a descriptive KDoc note of the admin exemption; add a functional lint annotation ONLY IF a rule actually fires at build time.

## 2. Read surface (route + template)

- [x] 2.1 Mount `GET /admin/subscriptions/grace` (e.g. `Application.adminSubscriptionGrace()`), wired into the admin route subtree and behind the admin auth gate (any authenticated admin role).
- [x] 2.2 Build the Pebble template + HTMX partial matching admin mockup frame 18 (User + PREMIUM badge deep-linking to `/admin/users?q=`, Store, Retry-since UTC, Last webhook, Expedite control / handled indicator) + the info banner; provide a plain-`GET` (no-JS) fallback rendering the same data. HTML-escape all user-controlled output.
- [x] 2.3 Render the count summary and keyset pagination controls; empty-state-tolerant rows (user with no events still lists, cells blank).
- [x] 2.4 Add the admin-nav entry ("Subscription grace" under Premium) per the mockup, flipping its state from proposed.

## 3. Expedite write (bookkeeping action)

- [x] 3.1 Implement `POST /admin/subscriptions/grace/{user_id}/expedite` accepting a required support-ticket reference + reason; reject (no write, no mutation) when the ticket reference is missing/blank.
- [x] 3.2 Write exactly one immutable `admin_actions_log` row: `action_type='subscription_grace_expedite'`, `target_type='user'`, `target_id={user_id}`, acting `admin_id`, `reason` incorporating the ticket ref, `before_state`/`after_state` snapshots with identical `subscription_status`. Mutate nothing else — no `users` update, no `subscription_events` insert (design D1, append-only).
- [x] 3.3 On success, re-render the affected row (HTMX swap) showing the handled indicator; plain-`GET` fallback re-displays the list.

## 4. Expedite guards (role + CSRF + rate limit)

- [x] 4.1 Enforce CSRF **first** (before the role gate, per the `AdminChatRedactionRoute` precedent — so a CSRF violation is audited as `admin_csrf_violation`, not masked by a silent role-403): require `X-CSRF-Token` matching `admin_sessions.csrf_token_hash`; on mismatch return 403, no mutation, write the `admin_csrf_violation` audit entry.
- [x] 4.2 Then role-gate the expedite to `role IN ('owner','admin')` (e.g. `AdminRoleGate.requireOwnerOrAdmin`); reject read-only roles (forbidden, no write).
- [x] 4.3 Add a dedicated `GraceExpediteActionRateLimiter` (mirror `ReservedUsernameActionRateLimiter` — do NOT reuse `DestructiveActionRateLimiter` directly, whose COUNT_SQL is hardcoded to the destructive set): same audit-trail-as-ledger mechanism (trailing hour, in-transaction soft count, inline "quota exceeded" not 5xx, no write on reject) counted on the DISTINCT `action_type='subscription_grace_expedite'`; cap 20/hour per admin. Verify it neither consumes nor is blocked by the destructive budget (design D2).

## 5. Tests

- [x] 5.1 Read tests: lists `premium_billing_retry` non-deleted users; excludes active/free/soft-deleted; billing-retry user with zero `subscription_events` still renders; unauthenticated → login redirect.
- [x] 5.2 Filter + pagination + count tests: `q` by username; `store` filter; filters compose; count reflects full population independent of page limit; keyset paging drops/duplicates nothing; `q`/`store` with SQL metacharacters bound as a literal (no injection; table unaffected); blank/whitespace `q` ignored (full population listed, not an empty-username match).
- [x] 5.3 PII/escaping tests: no location/email/DOB rendered; username/reason with HTML metacharacters is escaped.
- [x] 5.4 Expedite-semantics tests: success writes exactly one `subscription_grace_expedite` row with matching before/after `subscription_status` AND leaves `subscription_status='premium_billing_retry'` (no `subscription_events` row); missing ticket ref rejected (no write); repeat expedite appends a second row with no prior-row UPDATE/DELETE.
- [x] 5.5 Guard tests: read-only role cannot expedite; CSRF mismatch → 403 + `admin_csrf_violation` + no write; a CSRF- or role-rejected attempt leaves the expedite counter untouched (writes no `subscription_grace_expedite` row).
- [x] 5.6 Rate-limit tests: 21st expedite in an hour → "quota exceeded", no write, `subscription_status` unchanged; expedite succeeds even when the destructive budget is exhausted (and does not consume it); cap is per-admin (admin B unaffected by admin A's exhausted expedite quota); already-expedited indicator: with two expedites recorded for the same user, the indicator surfaces the **most recent** one (newer admin + timestamp), not an arbitrary row; rendering the list writes no audit row.
- [x] 5.7 If a new DB-tagged `*RoutesTest` Hikari pool is introduced, build it with `autoClose(hikari())` + pool size 2 (CI connection budget).
- [x] 5.8 Expedite target-state tests: expedite against a `premium_active`/`free` user → rejected, no `admin_actions_log` row, no mutation; expedite against a soft-deleted (`deleted_at IS NOT NULL`) or unknown `{user_id}` → rejected, no row, no mutation.

## 6. Verification, lint, smoke (one-PR gates)

- [x] 6.1 Pre-push gate: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` green locally.
- [x] 6.2 If `backend/ktor/.../admin/static/*` is touched (e.g. admin.css), re-pin `htmx.min.js.SHA256SUMS` (CI lint-lane integrity check). Default: reuse existing assets → N/A.
- [x] 6.3 UI-affecting bring-up (docs/11 §5 DoD): boot the admin panel (local Ktor `:8080` admin bootstrap + TOTP, or staging `api-staging.nearyou.id/admin`), seed a `premium_billing_retry` user + `billing_issue` event, screenshot the grace list + an expedite result; attach evidence to the PR body BEFORE archive.
- [x] 6.4 Pre-archive staging smoke (per Migration Plan): `gh workflow run deploy-staging.yml --ref admin-subscription-grace-monitor` → confirm the list renders, an expedite writes one audit row with unchanged `subscription_status`, and a CSRF-less expedite is 403'd.

## 7. Docs / mockup reconciliation (at archive)

- [ ] 7.1 Reflect docs/07 § Subscription Grace Monitor as **SHIPPED** — add an explicit bold **SHIPPED** status tag to the line (mirror how the §61 `admin-privacy-flip-monitor` line is tagged; don't just edit prose) — and flip the admin mockup frame 18 tag from "Usulan" to shipped. Apply note: the stored `subscription_events.event_type` literal is lowercase `billing_issue` (not the mockup's display text `BILLING_ISSUE`) — query the lowercase value.
- [ ] 7.2 `openspec validate admin-subscription-grace-monitor --strict` green; archive via the one-PR lifecycle (`/opsx:archive` pushes the archive commit to the same branch).
