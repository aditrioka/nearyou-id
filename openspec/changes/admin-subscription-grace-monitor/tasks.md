## 1. Read data layer (query + row model)

- [ ] 1.1 Add a `GraceMonitorRow` data class (username, user id, store/platform, retry-since, latest webhook event_type + timestamp, already-expedited indicator {admin, timestamp}) in the admin package.
- [ ] 1.2 Implement the single keyset query (design D3): `users` filtered to `subscription_status = 'premium_billing_retry' AND deleted_at IS NULL` (index-served by `users_subscription_idx`), LEFT JOIN LATERAL latest `subscription_events` row, LEFT JOIN LATERAL latest `admin_actions_log` row with `action_type = 'subscription_grace_expedite'`; keyset-ordered newest-retry-first. No N+1 (docs/11 §3.2).
- [ ] 1.3 Implement composable optional filters: `q` (exact case-insensitive username OR exact UUID) and `store`; absent filter does not constrain. Add the total-in-window count query for the summary.
- [ ] 1.4 Annotate the admin-module raw read of `users` / `subscription_events` for the block-exclusion / raw-read lint allowance per the `admin-privacy-flip-monitor` precedent.

## 2. Read surface (route + template)

- [ ] 2.1 Mount `GET /admin/subscriptions/grace` (e.g. `Application.adminSubscriptionGrace()`), wired into the admin route subtree and behind the admin auth gate (any authenticated admin role).
- [ ] 2.2 Build the Pebble template + HTMX partial matching admin mockup frame 18 (User + PREMIUM badge deep-linking to `/admin/users?q=`, Store, Retry-since UTC, Last webhook, Expedite control / handled indicator) + the info banner; provide a plain-`GET` (no-JS) fallback rendering the same data. HTML-escape all user-controlled output.
- [ ] 2.3 Render the count summary and keyset pagination controls; empty-state-tolerant rows (user with no events still lists, cells blank).
- [ ] 2.4 Add the admin-nav entry ("Subscription grace" under Premium) per the mockup, flipping its state from proposed.

## 3. Expedite write (bookkeeping action)

- [ ] 3.1 Implement `POST /admin/subscriptions/grace/{user_id}/expedite` accepting a required support-ticket reference + reason; reject (no write, no mutation) when the ticket reference is missing/blank.
- [ ] 3.2 Write exactly one immutable `admin_actions_log` row: `action_type='subscription_grace_expedite'`, `target_type='user'`, `target_id={user_id}`, acting `admin_id`, `reason` incorporating the ticket ref, `before_state`/`after_state` snapshots with identical `subscription_status`. Mutate nothing else — no `users` update, no `subscription_events` insert (design D1, append-only).
- [ ] 3.3 On success, re-render the affected row (HTMX swap) showing the handled indicator; plain-`GET` fallback re-displays the list.

## 4. Expedite guards (role + CSRF + rate limit)

- [ ] 4.1 Role-gate the expedite to `role IN ('owner','admin')`; reject read-only roles (forbidden, no write).
- [ ] 4.2 Enforce CSRF: require `X-CSRF-Token` matching `admin_sessions.csrf_token_hash`; on mismatch return 403, no mutation, write an `admin_csrf_violation` audit entry.
- [ ] 4.3 Enforce the 20/hour-per-admin expedite cap by reusing the `admin-destructive-action-rate-limit` mechanism on a DISTINCT counter (`action_type='subscription_grace_expedite'`, trailing hour, in-transaction soft count); at-or-over-cap → inline "quota exceeded" (not 5xx), no write. Verify it neither consumes nor is blocked by the destructive budget (design D2).

## 5. Tests

- [ ] 5.1 Read tests: lists `premium_billing_retry` non-deleted users; excludes active/free/soft-deleted; billing-retry user with zero `subscription_events` still renders; unauthenticated → login redirect.
- [ ] 5.2 Filter + pagination + count tests: `q` by username; `store` filter; filters compose; count reflects full population independent of page limit; keyset paging drops/duplicates nothing.
- [ ] 5.3 PII/escaping tests: no location/email/DOB rendered; username/reason with HTML metacharacters is escaped.
- [ ] 5.4 Expedite-semantics tests: success writes exactly one `subscription_grace_expedite` row with matching before/after `subscription_status` AND leaves `subscription_status='premium_billing_retry'` (no `subscription_events` row); missing ticket ref rejected (no write); repeat expedite appends a second row with no prior-row UPDATE/DELETE.
- [ ] 5.5 Guard tests: read-only role cannot expedite; CSRF mismatch → 403 + `admin_csrf_violation` + no write.
- [ ] 5.6 Rate-limit tests: 21st expedite in an hour → "quota exceeded", no write, `subscription_status` unchanged; expedite succeeds even when the destructive budget is exhausted (and does not consume it); cap is per-admin (admin B unaffected by admin A's exhausted expedite quota); already-expedited indicator surfaces the latest matching audit row and rendering writes no audit row.
- [ ] 5.7 If a new DB-tagged `*RoutesTest` Hikari pool is introduced, build it with `autoClose(hikari())` + pool size 2 (CI connection budget).

## 6. Verification, lint, smoke (one-PR gates)

- [ ] 6.1 Pre-push gate: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` green locally.
- [ ] 6.2 If `backend/ktor/.../admin/static/*` is touched (e.g. admin.css), re-pin `htmx.min.js.SHA256SUMS` (CI lint-lane integrity check). Default: reuse existing assets → N/A.
- [ ] 6.3 UI-affecting bring-up (docs/11 §5 DoD): boot the admin panel (local Ktor `:8080` admin bootstrap + TOTP, or staging `api-staging.nearyou.id/admin`), seed a `premium_billing_retry` user + `billing_issue` event, screenshot the grace list + an expedite result; attach evidence to the PR body BEFORE archive.
- [ ] 6.4 Pre-archive staging smoke (per Migration Plan): `gh workflow run deploy-staging.yml --ref admin-subscription-grace-monitor` → confirm the list renders, an expedite writes one audit row with unchanged `subscription_status`, and a CSRF-less expedite is 403'd.

## 7. Docs / mockup reconciliation (at archive)

- [ ] 7.1 Reflect docs/07 § Subscription Grace Monitor as SHIPPED (mirroring how `admin-privacy-flip-monitor` annotated its line) and flip the admin mockup frame 18 tag from "Usulan" to shipped.
- [ ] 7.2 `openspec validate admin-subscription-grace-monitor --strict` green; archive via the one-PR lifecycle (`/opsx:archive` pushes the archive commit to the same branch).
