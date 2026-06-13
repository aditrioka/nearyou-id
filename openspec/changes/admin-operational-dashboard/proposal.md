## Why

The mobile authenticated core loop is now demoable end-to-end (the mobile-first flip trigger in `openspec/project.md` § Mobile-First to Full-Demo Priority has fired), so the app is generating real operational data — signups, posts, reports, age-gate rejections. The admin landing at `GET /admin/` ("Dashboard") still shows only the three scaffold stat cards from `admin-mockup-parity` (frame 2). The operator needs the full **Operational Dashboard** (admin mockup frame 3) — the home surface they open first to monitor platform health at a glance. Every other admin screen is a drill-down; this is the landing. It is the conspicuous foundational gap left while the leaf viewers (reports, blocks, privacy-flips, rejected-identifiers) shipped.

## What Changes

- **Expand the existing `GET /admin/` landing into the full Operational Dashboard** (mockup frame 3 path note: "GET /admin/ — menggantikan scaffold"). The three shipped stat cards are retained and joined by the additional live-data widgets below. This is **not** a new route — it grows the existing index page and its repository (`AdminIndexRoute` / `AdminIndexStatsRepository`).
- **Add live-data operational widgets** (every widget below reads a table/function that exists in the current schema today — no Flyway migration):
  - Posts volume — last-24h count + per-hour buckets, over **raw `posts`** (includes shadow-banned / auto-hidden, because an operator monitoring platform volume wants the true total, not the `visible_posts` subset).
  - Signups volume — last-24h count + per-hour buckets, over `users` by `created_at`.
  - Reports volume/rate — last-24h count + per-hour buckets, over `reports`.
  - Top 10 active cities — posts grouped by the denormalized `posts.city_name` (set at write time by the `posts_set_city_tg` trigger, V11; no read-time `admin_regions` join or `ST_Contains`, per the `global-timeline` canonical pattern). `city_name` is NULL until the `admin_regions` polygon seed lands (deferred content work), so the widget shows its empty-state until then — buildable and correct now, auto-populating later with no code change.
  - Age-gate rejection signal — derived from `rejected_identifiers` (underage) relative to signups.
  - `rejected_identifiers` insert-rate trend (per-hour framing of the shipped 24h card).
  - Current database size — `pg_database_size()` point-in-time (a historical trend is deferred — needs a snapshot table).
  - DAU/MAU and a health-check-status summary are **design-decision** widgets: included only if a live source is cleanly queryable by the `admin_app` role; otherwise folded into the deferred cluster (resolved in `design.md`, not silently dropped).
- **Read-only, no destructive actions** — the dashboard writes nothing (no `admin_actions_log` rows, no CSRF state-change), open to any authenticated admin role. Pebble + HTMX, extending the shared admin base layout, mirroring the shipped landing-stats render pattern (HTMX render + plain-`GET` fallback).
- **Explicitly defer the widgets whose data source does not exist yet** as ONE umbrella requirement with a negative-guard scenario (so a follow-up change has a requirement to MODIFY): Sentry error-rate + Amplitude funnel embeds, anomaly spike-alert banner, subscription paid-vs-referral breakdown, Realtime cost/MAU, refresh-token-reuse log, attestation failure rate, CSAM detection events, RevenueCat webhook signature-fail count, Resend email delivery rate. These are **dependency-blocked** (Phase-4 tables / external services that don't exist), not scope-cut. A `follow-up` GitHub issue tracks the cluster.

## Capabilities

### New Capabilities
- `admin-operational-dashboard`: The read-only admin Operational Dashboard at `GET /admin/` — live-data operational widgets (volumes/rates, top cities, DB size, age-gate signal), the no-destructive-action posture, the raw-`posts`/raw-`users` aggregate-read invariant exceptions, and the explicit deferral of data-source-absent widgets.

### Modified Capabilities
- `admin-panel-scaffold`: the existing **"Scaffold landing renders greeting and live stat cards"** requirement is broadened — the `GET /admin/` index now hosts the full Operational Dashboard (the three scaffold cards become its first widgets). The greeting + base-layout + zero-state behavior is preserved; the card set expands.

## Impact

- **Code:** `backend/ktor/.../admin/routes/AdminIndexRoute.kt` + `AdminIndexStatsRepository.kt` (expanded with the new aggregates, or a sibling `OperationalDashboardRepository`), the `GET /admin/` Pebble template (additional widget cards per mockup frame 3), and the static/HTMX render path already in place.
- **Database:** none — read-only aggregate queries over existing tables (`posts` incl. the denormalized `city_name`, `users`, `reports`, `rejected_identifiers`, `admin_actions_log`) + `pg_database_size()`. No read-time `admin_regions` join / `ST_Contains`. No Flyway migration; footprint-disjoint from the in-flight `revenuecat-subscription-webhook` (V21) claim.
- **Invariants:** raw `FROM posts` aggregate read requires an annotated `@AllowRawPostsRead` exception; raw `FROM users` aggregate read requires an annotated `@AllowMissingBlockJoin` exception (both with justification comments — an operator volume count is deliberately not the block/shadow-ban-scoped view). `admin_app` role stays read-only; `pg_database_size()` callability is confirmed in design (else DB-size defers).
- **Visual contract:** admin mockup board frame 3 (`dev/mockups/nearyou-admin-mockup.html`), binding per `docs/11` § 3.6.
- **Docs:** spec sources `docs/07-Operations.md` § Core Features → Operational Dashboard; `docs/08-Roadmap-Risk.md` Phase 3.5 #19.
