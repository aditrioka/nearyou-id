## 1. Reconciliation & pre-implementation confirmations

- [ ] 1.1 (Resolved in design D7/B.3) Top-active-cities groups by the denormalized `posts.city_name` (V11 `posts_set_city_tg`), counting raw `posts`, `WHERE city_name IS NOT NULL` — NO `admin_regions` join / `ST_Contains` (matches the `global-timeline` invariant). At apply, just confirm nothing has changed; note that `admin_regions` is unseeded so `city_name` is NULL platform-wide and the widget shows empty-state until the polygon seed lands (no code change needed then).
- [ ] 1.2 Confirm `pg_database_size(current_database())` is executable by the `admin_app` role on staging (per `dev/scripts/provision-admin-app-staging.sh` grants). If not callable, omit the DB-size widget per design D7 (do not error the page) and note it.
- [ ] 1.3 Confirm the `rejected_identifiers.reason` enum value for under-age rejection is `age_under_18` (matches the shipped scaffold scenario) before wiring the age-gate signal.
- [ ] 1.4 Render admin mockup frame 3 (`dev/mockups/nearyou-admin-mockup.html`) via headless Chrome and generate the per-frame measurement annex (`dev/scripts/mockup-measure.sh nearyou-admin-mockup 3`) for spacing/typography/tokens; translate to the Pebble + vendored-CSS idioms per `docs/11` § 3.6.

## 2. Repository — extend the landing-stats aggregates

- [ ] 2.1 Extend `AdminIndexStatsRepository.IndexStats` with the new operational fields (posts 24h count + per-hour series; signups 24h count + per-hour series + `age_under_18` rejection count; reports 24h count + per-hour series; top-10 regions with counts; DB size bytes nullable).
- [ ] 2.2 Add the posts-volume aggregate query (raw `COUNT`/per-hour `date_trunc('hour', created_at)` over `posts`, Kotlin-bound UTC window start, no `NOW()`); zero-fill empty hours in Kotlin. Place the SQL on a `const`/property carrying `@AllowRawPostsRead` with a justification comment (operational volume; includes shadow-banned/auto-hidden by design D4).
- [ ] 2.3 Add the signups + age-gate aggregate query over `users` (by `created_at`) and `rejected_identifiers` (`reason = 'age_under_18'`); place the `users` SQL on a member carrying `@AllowMissingBlockJoin` with a justification comment (operator volume count, not a viewer-block-scoped read).
- [ ] 2.4 Add the reports-volume aggregate query over `reports` (24h count + per-hour series).
- [ ] 2.5 Add the top-active-cities aggregate: `SELECT city_name, COUNT(*) FROM posts WHERE created_at >= ? AND city_name IS NOT NULL GROUP BY city_name ORDER BY COUNT(*) DESC, city_name ASC LIMIT 10` (raw `posts`, covered by the same `@AllowRawPostsRead` member as 2.2; no `admin_regions` join / `ST_Contains`).
- [ ] 2.6 Add the DB-size read (`pg_database_size(current_database())`), guarded so a missing grant yields a null/omitted widget rather than an exception (per 1.2).
- [ ] 2.7 Keep all new aggregates inside the single `load()` connection scope; keep the repository clock-injectable for tests (mirror the shipped pattern).

## 3. Route + template — expand `GET /admin/`

- [ ] 3.1 Extend `AdminIndexRoute.adminIndex` to put the new widget values into the Pebble model (reuse the `—`/`EMPTY_SLOT` placeholder convention for empty slots; format DB size human-readable; format counts).
- [ ] 3.2 Expand `index.peb` to render the operational widget cards per mockup frame 3 (posts/signups/reports volume + per-hour trend display, top-active-cities list, DB-size card), extending the shared admin base layout; ensure HTMX render + plain-`GET` fallback both work; no destructive controls on the page.
- [ ] 3.3 Verify the page renders no deferred-cluster tiles and issues no query against a nonexistent table (design D6 negative guard).

## 4. Tests

- [ ] 4.1 Repository unit tests per aggregate: live value + zero-state, mirroring the existing landing-stats test pattern (clock-injected, deterministic). Include the posts-volume test asserting shadow-banned + auto-hidden posts ARE counted (the raw-inclusion invariant-exception behavior).
- [ ] 4.2 Top-active-cities test: ranking by count desc + deterministic alphabetical tie-break + empty-state empty list.
- [ ] 4.3 Signups-and-age-gate test: signup count + `age_under_18` rejection count; zero-state.
- [ ] 4.4 Route/integration test: `GET /admin/` returns 200 with the new widgets for an authenticated admin of any role; unauthenticated redirects to `/admin/login`; serving the page writes no `admin_actions_log` row. If this introduces a new DB-tagged `*RoutesTest`, its HikariPool MUST be `autoClose`d with pool size 2 (CI connection-budget rule).
- [ ] 4.5 Run the pre-push gate locally: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` (confirms both lint frameworks + the `@AllowRawPostsRead` / `@AllowMissingBlockJoin` annotations satisfy the SQL rules).

## 5. Deferred-widget tracking

- [ ] 5.1 File a `follow-up`-labeled GitHub issue (+ `admin` label) enumerating the deferred operational-widget cluster (Sentry/Amplitude embeds, anomaly banner, subscription breakdown, Realtime cost/MAU, refresh-token-reuse log, attestation failure rate, CSAM events, RevenueCat sig-fail, Resend delivery, DAU/MAU, health-status) and referencing the `admin-operational-dashboard` "Operational widgets … are deferred" requirement so the follow-up has a requirement to MODIFY.

## 6. Verification & Definition of Done (docs/11 § 5)

- [ ] 6.1 Manually verify the dashboard in the running admin panel (local Ktor boot or staging `api-staging.nearyou.id/admin/`): authenticate, open `GET /admin/`, confirm each live widget renders against seeded data and zero-state. Capture screenshot evidence for the PR body.
- [ ] 6.2 Confirm read-only posture end-to-end: no migration in the diff, no `admin_actions_log` write path added, no CSRF state-change control on the page.
- [ ] 6.3 Update the PR body with the manual-verification evidence and the deferred-widget follow-up issue link before marking ready-for-review.
