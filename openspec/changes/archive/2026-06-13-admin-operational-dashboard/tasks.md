## 1. Reconciliation & pre-implementation confirmations

- [x] 1.1 (Resolved in design D7/B.3) Top-active-cities groups by the denormalized `posts.city_name` (V11 `posts_set_city_tg`), counting raw `posts`, `WHERE city_name IS NOT NULL` — NO `admin_regions` join / `ST_Contains` (matches the `global-timeline` invariant). The V12 region-polygon seed (552 regions) is shipped, so `city_name` is populated for in-polygon posts (live rankings); only no-polygon-match posts have NULL `city_name` (excluded). At apply, just confirm nothing has changed.
- [x] 1.2 Confirm `pg_database_size(current_database())` is executable by the `admin_app` role on staging (per `dev/scripts/provision-admin-app-staging.sh` grants). If not callable, omit the DB-size widget per design D7 (do not error the page) and note it.
- [x] 1.3 Confirm the `rejected_identifiers.reason` enum value for under-age rejection is `age_under_18` and the timestamp column is `rejected_at` (V3) before wiring the age-gate signal.
- [x] 1.4 Render admin mockup frame 3 (`dev/mockups/nearyou-admin-mockup.html`) via headless Chrome and generate the per-frame measurement annex (`dev/scripts/mockup-measure.sh nearyou-admin-mockup 3`) for spacing/typography/tokens; translate to the Pebble + vendored-CSS idioms per `docs/11` § 3.6.

## 2. Repository — extend the landing-stats aggregates

- [x] 2.1 Extend `AdminIndexStatsRepository.IndexStats` with the new operational fields (posts 24h count + per-hour series; signups 24h count + per-hour series + `age_under_18` rejection count; reports 24h count + per-hour series; top-10 regions with counts; DB size bytes nullable).
- [x] 2.2 Add the posts-volume aggregate query (raw `COUNT`/per-hour `date_trunc('hour', created_at)` over `posts`, Kotlin-bound UTC window start, no `NOW()`); zero-fill empty hours in Kotlin. Add a justification comment on the SQL (operational volume; includes posts by shadow-banned authors + auto-hidden) — the admin module is path-exempt from `RawFromPostsRule`, so NO `@AllowRawPostsRead` annotation is required (mirror the shipped `ReportResolutionRepository`).
- [x] 2.3 Add the signups + age-gate aggregate query over `users` (by `created_at`) and `rejected_identifiers` (`reason = 'age_under_18'`, by `rejected_at`); add a justification comment on the `users` SQL (operator volume count, not a viewer-block-scoped read) — the admin module is path-exempt from `BlockExclusionJoinRule`, so NO `@AllowMissingBlockJoin` annotation is required.
- [x] 2.4 Add the reports-volume aggregate query over `reports` (24h count + per-hour series).
- [x] 2.5 Add the top-active-cities aggregate: `SELECT city_name, COUNT(*) FROM posts WHERE created_at >= ? AND city_name IS NOT NULL GROUP BY city_name ORDER BY COUNT(*) DESC, city_name ASC LIMIT 10` (raw `posts`, admin-path-exempt like 2.2 — no annotation; no `admin_regions` join / `ST_Contains`).
- [x] 2.6 Add the DB-size read (`pg_database_size(current_database())`), guarded so a missing grant yields a null/omitted widget rather than an exception (per 1.2).
- [x] 2.7 Keep all new aggregates inside the single `load()` connection scope; keep the repository clock-injectable for tests (mirror the shipped pattern).

## 3. Route + template — expand `GET /admin/`

- [x] 3.1 Extend `AdminIndexRoute.adminIndex` to put the new widget values into the Pebble model (reuse the `—`/`EMPTY_SLOT` placeholder convention for empty slots; format DB size human-readable; format counts).
- [x] 3.2 Expand `index.peb` to render the operational widget cards per mockup frame 3 (posts/signups/reports volume + per-hour trend display, top-active-cities list, DB-size card), extending the shared admin base layout; ensure HTMX render + plain-`GET` fallback both work; no destructive controls on the page.
- [x] 3.3 Verify the page renders no deferred-cluster tiles and issues no query against a nonexistent table (design D6 negative guard).

## 4. Tests

- [x] 4.1 Repository unit tests per aggregate: live value + zero-state, mirroring the existing landing-stats test pattern (clock-injected, deterministic). Include the posts-volume test asserting shadow-banned + auto-hidden posts ARE counted (the raw-inclusion invariant-exception behavior).
- [x] 4.2 Top-active-cities test: ranking by count desc + deterministic alphabetical tie-break + empty-state empty list.
- [x] 4.3 Signups-and-age-gate test: signup count + `age_under_18` rejection count; zero-state.
- [x] 4.4 Route/integration test: `GET /admin/` returns 200 with the new widgets for an authenticated admin of any role; unauthenticated redirects to `/admin/login`; serving the page writes no `admin_actions_log` row. If this introduces a new DB-tagged `*RoutesTest`, its HikariPool MUST be `autoClose`d with pool size 2 (CI connection-budget rule).
- [x] 4.5 Run the pre-push gate locally: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` (confirms both lint frameworks pass — the admin module is path-exempt from the posts/block SQL rules, so the raw reads need no annotation).
- [x] 4.6 Deferred-widget negative-guard test (spec: "Deferred tiles are not rendered and absent sources are not queried"): assert `GET /admin/` returns 200 with none of the deferred-cluster tile markers in the body, and that the dashboard SQL references no nonexistent table (`subscription_events`, `csam_detection_archive`, …).
- [x] 4.7 DB-size omit-path test (spec: "Database-size widget is omitted when unavailable"): with a repository/datasource fake whose size query yields null/throws, assert `GET /admin/` still returns 200 with the database-size widget omitted (page does not error).
- [x] 4.8 Per-hour zero-fill unit test: a 24-hour window with posts in only a couple of hours yields a dense 24-bucket series (empty hours = 0), per design D7.
- [x] 4.9 Regression: confirm the three shipped scaffold-landing scenarios (live values, deterministic top-reason tie-break, zero-state) in `AdminIndexStatsRouteTest` still pass after the widget expansion (the MODIFIED `admin-panel-scaffold` requirement preserves them).

## 5. Deferred-widget tracking

- [x] 5.1 File a `follow-up`-labeled GitHub issue (+ `admin` label) enumerating the deferred operational-widget cluster (Sentry/Amplitude embeds, anomaly banner, subscription breakdown, Realtime cost/MAU, refresh-token-reuse log, attestation failure rate, CSAM events, RevenueCat sig-fail, Resend delivery, DAU/MAU, health-status) and referencing the `admin-operational-dashboard` "Operational widgets … are deferred" requirement so the follow-up has a requirement to MODIFY.

## 6. Verification & Definition of Done (docs/11 § 5)

- [x] 6.1 Manually verify the dashboard in the running admin panel (local Ktor boot or staging `api-staging.nearyou.id/admin/`): authenticate, open `GET /admin/`, confirm each live widget renders against seeded data and zero-state. Capture screenshot evidence for the PR body.
- [x] 6.2 Confirm read-only posture end-to-end: no migration in the diff, no `admin_actions_log` write path added, no CSRF state-change control on the page.
- [x] 6.3 Update the PR body with the manual-verification evidence and the deferred-widget follow-up issue link before marking ready-for-review.
