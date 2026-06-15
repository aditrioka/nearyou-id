# admin-operational-dashboard Specification

## Purpose

The read-only Operational Dashboard at `GET /admin/` — the admin panel's home surface, the first thing an operator opens to monitor platform health at a glance. It expands the scaffold landing's three quick-link stat cards (see `admin-panel-scaffold`) into the full set of live-data widgets backed by data that exists today: posts / signups / reports volume (with a per-hour trend), the signups age-gate signal, top active cities, and current database size. The dashboard is strictly read-only — no writes, no `admin_actions_log` row, no CSRF state-change — and viewable by any authenticated admin role. Widgets whose data source does not yet exist (subscription, CSAM, attestation, DAU/MAU, external embeds, …) are explicitly deferred until their source lands.

## Requirements
### Requirement: `GET /admin/` renders the read-only Operational Dashboard

The `/admin/` index page SHALL render the Operational Dashboard per admin mockup board frame 3 — a read-only operational overview that extends the shared admin base layout, is served through the authenticated-session middleware, and is HTMX-rendered with a plain-`GET` fallback. The dashboard SHALL be viewable by **any** authenticated admin role (no role gate). The dashboard SHALL be strictly read-only: serving it SHALL NOT write any `admin_actions_log` row, SHALL NOT perform a CSRF-protected state change, and SHALL NOT mutate any table. It SHALL host the three quick-link stat cards from the scaffold landing as its first widgets plus the operational widgets defined below; all time arithmetic SHALL use UTC, computed server-side.

#### Scenario: Authenticated admin sees the dashboard

- **GIVEN** an authenticated admin session of any role (owner, admin, or moderator)
- **WHEN** `GET /admin/` is served
- **THEN** the response status SHALL be 200
- **AND** the page SHALL render the greeting, the three quick-link stat cards, and the operational widgets (posts/signups/reports volume, top active cities, database size)

#### Scenario: Unauthenticated request redirects to login

- **WHEN** `GET /admin/` is requested without a valid admin session
- **THEN** the response SHALL redirect to `/admin/login` (the established index auth behavior is preserved)

#### Scenario: Viewing the dashboard writes nothing

- **GIVEN** an authenticated admin session
- **AND** a snapshot of the `admin_actions_log` row count
- **WHEN** `GET /admin/` is served
- **THEN** the `admin_actions_log` row count SHALL be unchanged (the dashboard performs no audit-logged or state-changing action)

### Requirement: Posts-volume widget counts all posts including moderated ones

The dashboard SHALL render a posts-volume widget showing the count of posts created in the last 24 hours (with a per-hour breakdown for the trend display). The count SHALL be taken over the raw `posts` table and SHALL INCLUDE posts by shadow-banned authors and auto-hidden posts — an operator monitoring true platform volume needs the full total, not the member-facing `visible_posts` subset. The read lives in the admin module, which is path-exempt from `RawFromPostsRule`, so no lint annotation is required; the SQL SHALL carry a justification comment stating this operational rationale (so the deliberate raw read is greppable).

#### Scenario: Posts-volume reflects live data

- **GIVEN** an authenticated session
- **AND** the database contains 30 posts created in the last 24 hours
- **WHEN** `GET /admin/` is served
- **THEN** the posts-volume widget SHALL show a 24-hour count of 30

#### Scenario: Shadow-banned and auto-hidden posts are included in the count

- **GIVEN** an authenticated session
- **AND** the last 24 hours contain 8 posts, of which 2 are by shadow-banned authors and 1 is auto-hidden
- **WHEN** `GET /admin/` is served
- **THEN** the posts-volume widget SHALL show a 24-hour count of 8 (the moderated posts are counted, distinguishing the raw operator metric from a `visible_posts` read)

#### Scenario: Zero-state renders a zero count

- **GIVEN** an authenticated session against a database with no posts in the last 24 hours
- **WHEN** `GET /admin/` is served
- **THEN** the posts-volume widget SHALL render a 24-hour count of 0 (not an error or an omitted widget)

### Requirement: Signups-and-age-gate widget

The dashboard SHALL render a signups widget showing the count of `users` rows created in the last 24 hours (by `created_at`, with a per-hour breakdown), alongside the count of `rejected_identifiers` rows (by `rejected_at`) with reason `age_under_18` over the same window (the age-gate rejection signal). The `users` read lives in the admin module, which is path-exempt from `BlockExclusionJoinRule`, so no lint annotation is required; the SQL SHALL carry a justification comment stating that an operator volume count is deliberately not a viewer-block-scoped read.

#### Scenario: Signups and age-gate rejections reflect live data

- **GIVEN** an authenticated session
- **AND** the last 24 hours contain 15 new `users` rows and 4 `rejected_identifiers` rows with reason `age_under_18`
- **WHEN** `GET /admin/` is served
- **THEN** the signups widget SHALL show a 24-hour signup count of 15 and an age-gate-rejection count of 4

#### Scenario: Zero-state renders zero counts

- **GIVEN** an authenticated session against a database with no new users and no `age_under_18` rejections in the last 24 hours
- **WHEN** `GET /admin/` is served
- **THEN** the signups widget SHALL render a signup count of 0 and an age-gate-rejection count of 0

### Requirement: Reports-volume widget

The dashboard SHALL render a reports-volume widget showing the count of `reports` rows created in the last 24 hours (with a per-hour breakdown for the trend display). This is the inflow-rate signal, distinct from the shipped pending-queue-depth quick-link card.

#### Scenario: Reports-volume reflects live data

- **GIVEN** an authenticated session
- **AND** the database contains 6 reports created in the last 24 hours
- **WHEN** `GET /admin/` is served
- **THEN** the reports-volume widget SHALL show a 24-hour count of 6

#### Scenario: Zero-state renders a zero count

- **GIVEN** an authenticated session against a database with no reports in the last 24 hours
- **WHEN** `GET /admin/` is served
- **THEN** the reports-volume widget SHALL render a 24-hour count of 0

### Requirement: Top-active-cities widget

The dashboard SHALL render a top-active-cities widget listing the 10 cities with the most posts over a recent window, ordered by post count descending with a deterministic tie-break (count descending, then city name ascending). Cities SHALL be grouped by the denormalized `posts.city_name` column (populated at write time by the `posts_set_city_tg` trigger), counting raw `posts`; rows with a NULL `city_name` SHALL be excluded. The widget SHALL NOT perform any read-time `admin_regions` join or `ST_Contains` spatial work (matching the `global-timeline` canonical pattern). When fewer than 10 cities have posts, the widget SHALL render only the populated rows. The V12 region-polygon seed is shipped, so in-polygon posts carry a populated `city_name`; posts whose location matches no seeded polygon have NULL `city_name` and are excluded.

#### Scenario: Cities are ranked by post count

- **GIVEN** an authenticated session
- **AND** the window contains posts with several distinct non-NULL `city_name` values of differing counts
- **WHEN** `GET /admin/` is served
- **THEN** the top-active-cities widget SHALL list up to 10 cities ordered by post count descending

#### Scenario: Ties break deterministically

- **GIVEN** an authenticated session
- **AND** two `city_name` values have an equal post count in the window
- **WHEN** `GET /admin/` is served
- **THEN** the tied cities SHALL be ordered alphabetically by city name (per `ORDER BY count DESC, city_name ASC`)

#### Scenario: Zero-state renders an empty list

- **GIVEN** an authenticated session against a database with no posts carrying a non-NULL `city_name` in the window (no region-matched posts yet)
- **WHEN** `GET /admin/` is served
- **THEN** the top-active-cities widget SHALL render an empty-state (no rows) rather than an error or an omitted widget

### Requirement: Database-size widget

The dashboard SHALL render a database-size widget showing the current size of the database via `pg_database_size(current_database())` as a point-in-time value (no historical trend). If the `admin_app` role cannot execute `pg_database_size`, the widget SHALL be omitted rather than error the page.

#### Scenario: Database size renders a current value

- **GIVEN** an authenticated session
- **WHEN** `GET /admin/` is served
- **THEN** the database-size widget SHALL show the current database size as a human-readable value

#### Scenario: Database-size widget is omitted when unavailable

- **GIVEN** an authenticated session
- **AND** the size query is unavailable (e.g. the `admin_app` role cannot execute `pg_database_size`)
- **WHEN** `GET /admin/` is served
- **THEN** the response status SHALL be 200 with the database-size widget omitted, and the page SHALL NOT error

### Requirement: Operational widgets whose data source does not yet exist are deferred

Operational widgets whose data source is a Phase-4 table or an external service that does not yet exist SHALL be deferred from this change and SHALL NOT be rendered, and the dashboard SHALL NOT query any nonexistent table or call any unconfigured external service. The deferred cluster is: Sentry error-rate embed, Amplitude funnel embed, anomaly spike-alert banner, subscription paid-vs-referral breakdown, Realtime cost per MAU, refresh-token-reuse detection log, attestation failure rate, CSAM detection events, RevenueCat webhook signature-fail count, Resend email delivery rate, DAU/MAU (canonical source is Redis sliding-window session tracking, not reachable by the DB-only `admin_app` role), and a health-check-status summary (the operator uses `/health/*` directly). A `follow-up` GitHub issue SHALL track this cluster and reference this requirement so a future change has a requirement to modify.

#### Scenario: Deferred tiles are not rendered and absent sources are not queried

- **GIVEN** an authenticated session
- **WHEN** `GET /admin/` is served
- **THEN** the response SHALL be 200 without a subscription-breakdown tile, a CSAM-events tile, an attestation-failure tile, a DAU/MAU tile, or any other deferred-cluster tile
- **AND** the dashboard SHALL issue no query against a table that does not exist in the current schema (e.g. `subscription_events`, `csam_detection_archive`)

#### Scenario: Deferral is tracked

- **WHEN** this change ships
- **THEN** a `follow-up`-labeled GitHub issue SHALL exist enumerating the deferred cluster and pointing at this requirement

