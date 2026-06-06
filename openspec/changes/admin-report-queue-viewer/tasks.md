## 1. Migration (index-only)

- [ ] 1.1 Add `V19__reports_created_idx.sql` creating `CREATE INDEX IF NOT EXISTS reports_created_idx ON reports(created_at DESC, id DESC);` (mirror `V17__admin_actions_log_created_idx.sql` verbatim, including `IF NOT EXISTS` for idempotency against hand-applied staging indexes — no `CONCURRENTLY`). Confirm V19 is still the next free version at apply time (re-check `find … db/migration/V*.sql | sort`).
- [ ] 1.2 Verify the migration applies cleanly from the prior version in the test JVM (DB tests auto-boot the full Flyway set via `KotestProjectConfig.beforeProject()` — no per-spec migrate call).
- [ ] 1.3 Verify `reports_created_idx` exists post-migration (query `pg_indexes WHERE tablename = 'reports' AND indexname = 'reports_created_idx'`), mirroring the sibling's index-existence assertion (`admin-actions-log-viewer` V17 test).

## 2. Read model + query (read-only repository)

- [ ] 2.1 Add a `ReportQueueRow` data class (typed, returned from the repository — not a raw `ResultSet` leak) carrying report fields (`id`, `created_at`, `target_type`, `target_id`, `reason_category`, `reason_note`, `status`, reporter display) + nullable queue context (`trigger`, `priority`, queue `status`) + resolved action-link user id (nullable).
- [ ] 2.2 Implement the keyset query over `reports` ordered `created_at DESC, id DESC` with a fixed page size + `(created_at, id) < (cursor)` predicate; reuse the `admin-actions-log-viewer` opaque-cursor codec (do NOT write a second codec).
- [ ] 2.3 Attach `moderation_queue` context via `LEFT JOIN LATERAL (SELECT trigger, priority, status FROM moderation_queue mq WHERE mq.target_type = r.target_type AND mq.target_id = r.target_id ORDER BY priority ASC, created_at DESC LIMIT 1) mq ON TRUE` (single representative row — no fan-out). Confirm the `priority ASC` direction against any existing triage convention (design Open Question 1).
- [ ] 2.4 Resolve the action-link user per `target_type` via `LEFT JOIN`s (`user`→target_id; `post`→posts author; `reply`→post_replies author; `chat_message`→chat_messages sender). Verify exact author/sender column names against the shipped schema. Hard-deleted target → NULL → no link (graceful).
- [ ] 2.5 Build the composable, AND-combined filter clause (`status`, `target_type`, `reason_category`, `from`/`to` date range with `< to + 1 day` upper bound, and `trigger` as an `EXISTS` over `moderation_queue`) — every value bound via parameterized JDBC placeholders, never string-interpolated.

## 3. Route + auth gate

- [ ] 3.1 Add `ReportQueueRoute` mounting `GET /admin/reports` INSIDE the existing `authenticate(ADMIN_AUTH_NAME)` block (session-gated — any valid admin session, NOT role-restricted, matching `admin-actions-log-viewer`; unauthenticated → 302 `/admin/login`). Do NOT add any mutation route.
- [ ] 3.2 Parse query params (`cursor`, `status`, `target_type`, `reason_category`, `trigger`, `from`, `to`); malformed/absent cursor → first page (never error); malformed filter values handled leniently (unparseable date ignored, out-of-enum value → zero matches, never 4xx/5xx); wire to the repository; compute the "older" cursor only when a further page exists.
- [ ] 3.3 Place the route + repository under the `app/admin/` package (so the `RawFromPostsRule` / `BlockExclusionJoinRule` admin exemption applies by path/package) — NOT under the `moderation` package (whose exemption is narrowly `Report*`-file-scoped). Wire the route into the admin Koin module + the admin route subtree. (No pre-existing admin "queue stub" exists to retire — the `moderation` package's `ReportRoutes` is the unrelated user-facing `POST /api/v1/reports` path; leave it untouched.)

## 4. Templates + navigation

- [ ] 4.1 Add the Pebble full-page template extending the `admin-panel-scaffold` base layout (filter form + results table + pagination control), and a result-fragment template for HTMX swaps.
- [ ] 4.2 Serve the fragment when `HX-Request: true` (no `<html>` wrapper / no base-layout header-footer); serve the full page on a plain `GET`. Ensure pagination + filters work in both modes.
- [ ] 4.3 Confirm Pebble autoescaping is ON for every dynamic value (no `| raw` on `reason_note` or any joined string).
- [ ] 4.4 Render the per-row deep-link to `/admin/users?q=<resolved-user>` when the action-link user resolved; render bare `target_id` text otherwise.
- [ ] 4.5 Add a nav entry ("Reports" / "Antrean Laporan") to the admin base layout nav.

## 5. Tests (Kotest + Ktor test framework — one per spec scenario)

- [ ] 5.1 Authenticated `GET /admin/reports` renders the table (reason_category + status visible) + base-layout sections.
- [ ] 5.2 Unauthenticated → 302 `/admin/login`, no content served.
- [ ] 5.3 Rows ordered newest-first (`created_at DESC, id DESC`).
- [ ] 5.4 Empty result → empty-state message + HTTP 200 (not 404/500).
- [ ] 5.5 Keyset: page capped at fixed size + "older" control present; following cursor returns strictly-older non-overlapping page; malformed cursor → first page; last page omits "older" control.
- [ ] 5.6 Filters: `status` / `target_type` / `reason_category` each return only matching rows; date range bounds the whole "to" day; filters compose with AND. Use explicit-offset `created_at` fixtures (not "now"-relative) so the `< to + 1 day` boundary is deterministic across CI runners (mirrors the sibling's tz-determinism note).
- [ ] 5.7 `trigger` filter returns only reports whose target has a matching `moderation_queue` row (EXISTS), excluding targets with no queue row.
- [ ] 5.8 Injection-inert: a `status` value containing SQL stays bound as a parameter; `reports` table still exists; response 200/empty.
- [ ] 5.9 `moderation_queue` context: report WITH a queue row shows trigger/priority/queue-status; report WITHOUT one renders fine (no crash); a target with two triggers yields exactly ONE display row (no fan-out).
- [ ] 5.10 Deep-link: `user`/`post`/`reply`/`chat_message` reports each link to `/admin/users?q=<author/sender/target>`; hard-deleted target renders `target_id` with no link.
- [ ] 5.11 HTML-escaping: a report whose `reason_note` contains `<script>` renders escaped (`&lt;script&gt;`), not executable.
- [ ] 5.12 HTMX: `HX-Request: true` → fragment only (no `<html>` wrapper); plain `GET` → full page with base layout.
- [ ] 5.13 Read-only negative guard: serving the page (with/without filters/cursor) leaves `admin_actions_log` count unchanged and inserts/updates/deletes no `reports` or `moderation_queue` row.
- [ ] 5.14 Deferred-guard: no resolution control is rendered (status read-only, no actioned/dismissed form); `?has_edit_history=true` is ignored (200, no filtering); no resolution route is mounted by this change.
- [ ] 5.15 Reporter identity: a report by a user with a known `username` renders the resolved username, not the bare `reporter_id` UUID.
- [ ] 5.16 Malformed filters tolerated: unparseable `from` → 200, date filter ignored; out-of-enum/over-long `status` → 200 empty-state (no 400/500); `from` > `to` → 200 empty-state.
- [ ] 5.17 Pagination boundary + tiebreaker: exactly-one-full-page omits the "older" control; two rows with identical `created_at` + distinct `id` straddling the page boundary appear once each across pages (no skip/dup); following the "older" cursor with a filter active stays filtered + non-overlapping.
- [ ] 5.18 Only `GET` mounted: `POST /admin/reports` → 405 (Method Not Allowed).

## 6. Lint + local gate

- [ ] 6.1 Run `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` locally (CI runs BOTH ktlint + detekt; passing only detekt is insufficient). Worktree needs a copied `local.properties` SDK pointer.
- [ ] 6.2 Confirm no `visible_*`-view / block-exclusion lint violation is raised (admin-module raw reads of `reports`/`moderation_queue`/`users`/content tables are exempt — verify the route lives in the admin package the lint allowlists).

## 7. Staging smoke (pre-archive, lightweight)

- [ ] 7.1 `gh workflow run deploy-staging.yml --ref admin-report-queue-viewer`; poll the deploy run to green.
- [ ] 7.2 Log in to the staging admin panel (Argon2id + TOTP) and `GET /admin/reports`: confirm the page renders, an existing seeded report appears, a filter narrows results, pagination works, and a deep-link points at `/admin/users?q=…`. (Tick this section N/A in the archive commit body only if a staging admin login is unavailable — do not skip silently.)

## 8. Docs + follow-ups

- [ ] 8.1 At archive: update `docs/07-Operations.md` §Core Features "Report Queue" + the top-of-file Status note from DESIGN → partially-shipped (read-only viewer at `GET /admin/reports`; resolution write-back still DESIGN). While editing that same Status note, correct the pre-existing stale "V15 `admin-schema-bootstrap`" reference → "V16" (the admin schema shipped at `V16__admin_users.sql`).
- [ ] 8.2 Add `FOLLOW_UPS.md` entries: `admin-report-queue-resolution-actions` (the write surface — status transitions + `moderation_queue.resolution`/`resolved_by`/`resolved_at` + `admin_actions_log` audit row, CSRF + role-gated) and `admin-report-queue-has-edit-history-filter` (the deferred prioritization filter needing a `post_edits` existence join).
- [ ] 8.3 No new Gradle module is added → README module-list sync (`dev/scripts/sync-readme.sh`) is N/A; confirm no `settings.gradle.kts` change crept in.
