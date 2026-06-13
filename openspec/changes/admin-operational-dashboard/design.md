## Context

The admin landing at `GET /admin/` (page title "Dashboard") was shipped by `admin-mockup-parity` (frame 2): `AdminIndexRoute.adminIndex` renders `index.peb` with a greeting + three live stat cards sourced from `AdminIndexStatsRepository.load()` (pending reports, `rejected_identifiers` last-24h, audit actions today). Mockup frame 3 ("3 · Operational Dashboard — GET /admin/ (menggantikan scaffold)") is the target end-state for that same page: a read-only operational overview the operator opens first. The backend is MVP-ready and the mobile app is now generating live data, so the dashboard's data sources (signups, posts, reports, rejections) are populated for the first time.

The existing repository's own header comment is the load-bearing precedent for this change:

> *Admin surface — raw-table reads are the established admin precedent (the shadow-ban `visible_*` views exist for member-facing paths); none of the three tables is `posts`/`users`, so the Detekt SQL rules are inert here.*

This change adds the **first** admin reads of `posts` and `users` in this repository, so it is exactly where the two SQL lint rules stop being inert and the annotated exceptions become required.

**Constraints:** read-only (mockup: "tidak ada aksi destruktif di halaman ini"); no Flyway migration (footprint-disjoint from the in-flight `revenuecat-subscription-webhook` V21 claim); `admin_app` is a DB-only scoped role (no Redis, no cross-service calls); any authenticated admin role may view.

## Goals / Non-Goals

**Goals:**
- Expand the existing `GET /admin/` landing into the full Operational Dashboard with every widget whose data source exists in the current schema today.
- Stay read-only and migration-free; reuse the shipped landing-stats repository + Pebble + HTMX render pattern with no parallel pattern.
- Make the data-source-absent widgets an *explicit, tracked* deferral, not a silent omission.

**Non-Goals:**
- No destructive actions, no `admin_actions_log` writes, no CSRF state-change.
- No new route (`GET /admin/dashboard` is **not** introduced — the dashboard *is* the index).
- No new dependency (no Redis read from the admin panel, no cross-module health-probe wiring, no Sentry/Amplitude SDK).
- No historical time-series storage (a snapshot table for trends is out of scope).

## Decisions

### D1 — Standards conformance (Pattern Registry, docs/11)

Builds entirely on existing `docs/11-Engineering-Standards.md` patterns; **introduces no new pattern**:
- **Backend layering** (§3.1): `route → repository` — the route builds a Pebble model, the repository owns all SQL.
- **JDBC discipline** (§3.2): one `dataSource.connection.use { }`, prepared statements, parameter-bound UTC instants computed in Kotlin (no `NOW()`), per-request with no cache — identical to the shipped `AdminIndexStatsRepository`.
- **Admin panel UI** (§3.6): Pebble template extending the shared admin base layout, HTMX-rendered with a plain-`GET` fallback, mockup frame 3 as the binding visual target.

No deviation → no `docs/11` § Pattern Registry amendment task.

### D2 — Spec ownership: new capability MODIFIES the scaffold landing requirement

Create a new capability spec `admin-operational-dashboard` (the dashboard's full behavior) and, in the same change, MODIFY `admin-panel-scaffold`'s existing **"Scaffold landing renders greeting and live stat cards"** requirement so it reflects that `GET /admin/` now hosts the Operational Dashboard (the three scaffold cards are preserved as its first widgets; greeting + base-layout + zero-state behavior unchanged). *Alternative considered:* leaving the scaffold requirement untouched and only ADDING — rejected because the scaffold requirement asserts the landing's complete card set, which is no longer accurate; an unmodified requirement would contradict the new spec.

### D3 — Repository shape: extend `AdminIndexStatsRepository`, do not fork a parallel repo

Add the new aggregates to the existing `AdminIndexStatsRepository` (extend `IndexStats` with the new fields; add query blocks within the single `load()` connection scope), rather than introducing a sibling `OperationalDashboardRepository`. Rationale: it is already the stats repository for this exact page; one page → one stats repository keeps the anti-patchwork contract (D1). The added aggregates are all indexed COUNT/GROUP-BY over existing tables on one connection — acceptable for a solo-operator panel. *Alternative considered:* a separate repo for "new" widgets — rejected as a parallel pattern for one page.

### D4 — Raw `posts` / `users` aggregate reads + required lint annotations

Posts-volume and signups-volume widgets count **all** rows — including shadow-banned/auto-hidden posts and all users — because an operator monitoring platform volume needs the true total, not the member-facing `visible_posts`/block-scoped subset. This is a deliberate, correct use of the raw tables, so:
- The `posts` aggregate SQL gets `@AllowRawPostsRead` with a justification comment (`RawFromPostsRule` matches `posts`; the rule is otherwise inert in this repo per its header).
- The `users` aggregate SQL gets `@AllowMissingBlockJoin` with a justification comment (`BlockExclusionJoinRule` protects `FROM users`; an operator volume COUNT is explicitly not a viewer-block-scoped read).
- Per the lint mechanics, the annotation goes on the SQL-holding `const`/property the rule walks up to — not the function. Justification states: *operational volume metric; shadow-ban/block exclusion would understate true platform activity and defeat the health signal.*

*Alternative considered:* counting `visible_posts` instead — rejected: it would silently hide moderated content from the volume signal, which is the opposite of what an operations dashboard is for.

### D5 — DAU/MAU and health-status: deferred (keep the dashboard DB-only, no new dependency)

Both fold into the deferred cluster (D6):
- **DAU/MAU** — the canonical "active user" source is the Redis server-side sliding-window session tracking (docs/05 § Sliding Window Session Tracking). The `admin_app` role is Postgres-only; reaching Redis from the admin panel is a new dependency outside this change's read-only-DB scope. A posting/session *proxy* would mislabel a partial count as DAU — worse than deferring. (Reconciliation hook: if B.3 finds a Postgres-resident session-activity column cleanly readable by `admin_app`, a clearly-labeled proxy MAY be promoted — but the default is defer.)
- **Health-status summary** — surfacing it means wiring the existing `/health/*` probes into the admin module (a cross-component call). The operator already has `/health/ready` + `/health/live` directly; a dashboard mirror is not worth the new coupling now.

### D6 — Deferred-widget cut line + single umbrella requirement

**Cut line:** a widget is in-scope iff its data source is a table/function that exists in the current schema/deployment today. Everything else is **dependency-blocked, not scope-cut**, and is captured as ONE umbrella requirement ("Operational widgets whose data source does not yet exist are deferred") with: a positive statement enumerating the cluster, and a **negative-guard scenario** asserting the dashboard does NOT query a nonexistent source / does NOT render those tiles (so the page can't silently start depending on Phase-4 state). A single `follow-up` GitHub issue tracks the cluster and references this requirement, giving the eventual follow-up change a requirement to MODIFY (per the project's capture-deferral-as-requirement rule). Cluster: Sentry error-rate + Amplitude funnel embeds, anomaly spike-alert banner, subscription paid-vs-referral breakdown, Realtime cost/MAU, refresh-token-reuse log, attestation failure rate, CSAM detection events, RevenueCat webhook signature-fail count, Resend email delivery rate, **DAU/MAU**, **health-status** (D5).

### D7 — DB size + per-hour bucketing

- **DB size:** `SELECT pg_database_size(current_database())` — point-in-time only. `pg_database_size` needs CONNECT on the current DB, which `admin_app` has; if implementation finds the grant absent, the widget defers (low-risk, isolated). No historical trend (needs a snapshot table — out of scope).
- **Per-hour buckets:** `GROUP BY date_trunc('hour', created_at)` over a Kotlin-bound 24h window start, clock-injectable exactly like the shipped repo (no `NOW()`); zero-count hours are filled in Kotlin so the sparkline/series is dense. Rides each table's existing `created_at` index.
- **Top-10 cities:** `GROUP BY posts.city_name` over the same raw `posts` read (operator-volume semantics, so same `@AllowRawPostsRead`), `WHERE city_name IS NOT NULL`, `ORDER BY COUNT(*) DESC, city_name ASC LIMIT 10`. `city_name` is the column the `posts_set_city_tg` trigger denormalizes at write time (V11); there is **no** read-time `admin_regions` join and **no** `ST_Contains` — this matches the `global-timeline` canonical invariant ("the SQL contains neither `admin_regions` as a table reference NOR `ST_Contains`"). Because `admin_regions` is currently unseeded (polygon dataset deferred — `global-timeline-with-region-polygons/DEFERRED.md`), `city_name` is NULL for all posts today, so the widget renders its empty-state until the seed lands; the query is correct now and auto-populates later with no code change.

## Risks / Trade-offs

- **Raw posts/users reads flagged by reviewers as an invariant violation** → the annotations + justification comments (D4) make the exception explicit and self-documenting; the spec states the rationale as a requirement so a reviewer greps it (precedent: CLAUDE.md § Reviewing — "things that look like bugs are often deliberate").
- **`load()` grows to ~9 sequential aggregates on one connection** → all are indexed COUNT/GROUP-BY; solo-operator panel, per-request, no hot path. If latency ever matters, the queries are independently cacheable later — not now (premature).
- **Top-cities shows empty until the region-polygon seed lands** → `posts.city_name` is NULL platform-wide while `admin_regions` is unseeded (deferred content work). Accepted: the widget is correct and renders its specced empty-state now, auto-populating when the seed lands — no code change, no perf concern (it is a simple indexed `GROUP BY` on a denormalized column, not a spatial join).
- **DB-size grant missing on `admin_app`** → isolated widget defer, no impact on the rest (D7).
- **Deferring DAU/MAU leaves the headline metric out** → accepted: a DB-only proxy would be misleading; honest deferral with a tracked follow-up beats a wrong number (CLAUDE.md § Engineering judgment over context budget).

## Migration Plan

No Flyway migration, no schema change, no data backfill. Ships as backend code + a Pebble template expansion on the existing `GET /admin/` page. Rollback = revert the PR; the page reverts to the three scaffold cards. No deploy ordering constraints; lands in parallel with the in-flight admin/billing claims (disjoint files, no shared migration number).

## Open Questions

- None blocking. Top-cities is resolved (B.3): `GROUP BY posts.city_name`, no `admin_regions`/`ST_Contains`, empty-state until the polygon seed lands. The only remaining impl-time confirmation is whether a Postgres session-activity column could back a clearly-labeled active-user proxy (else DAU/MAU stays deferred per D5); decided at apply, surfaced to the user only if it changes scope.
