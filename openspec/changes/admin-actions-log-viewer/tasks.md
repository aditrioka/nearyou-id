## 1. Repository — `AdminActionsLogRepository`

- [ ] 1.1 Create `backend/ktor/src/main/kotlin/id/nearyou/app/admin/actionslog/AdminActionsLogRepository.kt` mirroring the `SessionRepository` / `AdminUserRepository` JDBC pattern (constructor-injected `DataSource`, `PreparedStatement`, no ORM).
- [ ] 1.2 Define `ActionLogRow` (id, adminId, adminDisplayName, adminEmail, actionType, targetType, targetId, reason, beforeState, afterState, ip, userAgent, createdAt), `ActionLogQuery` (the active filter set + page size + optional cursor), and `ActionLogCursor` (createdAt + id) data classes.
- [ ] 1.3 Implement `fun query(q: ActionLogQuery): ActionLogPage` — `SELECT … FROM admin_actions_log l JOIN admin_users u ON u.id = l.admin_id WHERE <dynamic> ORDER BY l.created_at DESC, l.id DESC LIMIT ?`. Dynamic WHERE assembled from active filters as parameterized `?` fragments bound positionally (design.md D7). Fetch `pageSize + 1` rows; if `pageSize + 1` returned, drop the last and set the next cursor from it (design.md D1).
- [ ] 1.4 Keyset predicate: when a cursor is present, add `(l.created_at, l.id) < (?, ?)` row-value comparison bound to the cursor's `(createdAt, id)`.
- [ ] 1.5 Date-range predicate: `from` → `l.created_at >= ?`; `to` → `l.created_at < ? + INTERVAL '1 day'` (inclusive whole-day upper bound per design.md D2). Bind as `TIMESTAMPTZ` / date params.
- [ ] 1.6 Confirm admin-module raw-read exemption applies (no `visible_*` view, no block-exclusion join needed for admin tables) — add a one-line comment citing `openspec/project.md` § Coding Conventions so a future Detekt sweep / reviewer sees the exemption is deliberate.

## 2. Cursor codec + filter parsing

- [ ] 2.1 Implement the opaque cursor codec (encode/decode `base64url("<created_at>|<id>")` per design.md D1). Decode validates the shape; a malformed token returns `null` (→ first page), never throws.
- [ ] 2.2 Implement lenient filter parsing from query params: `admin_id` → `UUID.fromString` guarded (invalid → ignored); `from`/`to` → ISO-8601 `LocalDate` parse guarded (invalid → ignored); `action_type` / `target_type` / `target_id` → trimmed, length-capped to the column widths (`action_type` ≤ 64, `target_type` ≤ 32), blank → ignored.
- [ ] 2.3 Clamp page size to the fixed constant (50); the page size is an implementation constant, not a client-supplied param.

## 3. Route handler — `AdminActionsLogRoute`

- [ ] 3.1 Create `backend/ktor/src/main/kotlin/id/nearyou/app/admin/routes/AdminActionsLogRoute.kt` with `fun Route.adminActionsLog(repo: AdminActionsLogRepository)`; wire `get("/actions-log")`.
- [ ] 3.2 Parse + clamp query params (Section 2), build `ActionLogQuery`, call `repo.query(...)`, assemble the Pebble model (rows + active-filter echo + next cursor).
- [ ] 3.3 Branch on `call.request.headers["HX-Request"] == "true"` (design.md D3): HX → render `_actions-log-table.peb` fragment only; plain → render `actions-log.peb` full page. Both via `PebbleContent` (mirror `AdminIndexRoute`).
- [ ] 3.4 Ensure the handler performs NO write (no audit insert) and returns 200 with the empty-state model when the page is empty.

## 4. Templates + nav link

- [ ] 4.1 Add `backend/ktor/src/main/resources/templates/admin/actions-log.peb` — extends `_layout.peb`; renders the filter `<form>` (`hx-get` + `hx-target="#actions-log-table"` + `hx-swap="outerHTML"` + `hx-push-url="true"`) with inputs for `action_type`, `admin_id`, `target_type`, `target_id`, `from`, `to`; `{% include %}`s the table fragment.
- [ ] 4.2 Add `backend/ktor/src/main/resources/templates/admin/_actions-log-table.peb` — the `id="actions-log-table"` element: the rows table (structured columns) + per-row `<details>` detail region for `before_state`/`after_state` (HTML-escaped; em-dash for NULL per design.md D8) + the "older" `hx-get` pagination control (rendered only when a next cursor exists) + the empty-state message when there are no rows.
- [ ] 4.3 Verify NO `raw` filter is used on any audit-row value in either template (autoescape preserved — design.md D8 + spec Req "before_state and after_state render HTML-escaped").
- [ ] 4.4 Add the functional "Audit Log" link (→ `/admin/actions-log`) to the nav stub in `_layout.peb` (design.md D6 — implementation task, not a scaffold-spec modification).

## 5. Wiring

- [ ] 5.1 In `AdminModule.kt`: construct `AdminActionsLogRepository(dataSource)`; call `adminActionsLog(repo)` inside the existing `authenticate(ADMIN_AUTH_NAME) { ... }` block alongside `adminIndex(...)` + `logoutRoute.install(this)`. No change to auth/CSRF wiring.

## 6. Tests — repository (DB-tagged)

- [ ] 6.1 `AdminActionsLogRepositoryTest.kt` (DB-tagged, runs against the service-container Postgres). Seed `admin_users` + `admin_actions_log` fixtures.
- [ ] 6.2 Assert newest-first ordering (`created_at DESC, id DESC`).
- [ ] 6.3 Assert keyset pagination: first page is full + has-next; following the cursor returns strictly-older, non-overlapping rows; last page has no next cursor (spec Req "Keyset pagination").
- [ ] 6.4 Assert each filter narrows correctly + filters compose with AND (spec Req "Composable, index-aligned filtering").
- [ ] 6.5 Assert the date-range inclusive-whole-day upper bound (a row at the very end of the `to` day is included; the next day's row is excluded) — pin the boundary per design.md D2.
- [ ] 6.6 Assert the `admin_users` join resolves `display_name` + `email` for each row.
- [ ] 6.7 Assert an SQL-metacharacter `action_type` value matches zero rows as a literal AND the table still exists afterward (spec Req "Malformed filter inputs … without injection").
- [ ] 6.8 Assert empty result (no matching rows) returns an empty page, not an error.

## 7. Tests — route (`testApplication`)

- [ ] 7.1 `AdminActionsLogRouteTest.kt` using the admin `testApplication` harness from Admin #3 (authenticated-session helper).
- [ ] 7.2 Authenticated GET → 200 with rendered table containing a seeded row's `action_type` + actor `display_name` (spec Req "Authenticated GET … renders").
- [ ] 7.3 Unauthenticated GET → 302 → `/admin/login` (spec Req scenario "Unauthenticated request redirects").
- [ ] 7.4 `HX-Request: true` → fragment only (`id="actions-log-table"` present; no `<html>` wrapper / base layout) (spec Req "HTMX partial swap").
- [ ] 7.5 Plain GET with a filter → full page reflecting the filter (shareable filtered link) (spec Req "HTMX partial swap" scenario "Plain GET returns the full page").
- [ ] 7.6 Malformed `admin_id` / unparseable `from` → 200, other filters still apply (spec Req "Malformed filter inputs").
- [ ] 7.7 `after_state` containing `<script>alert(1)</script>` → rendered escaped, no live script tag (spec Req "before_state and after_state render HTML-escaped").
- [ ] 7.8 `POST /admin/actions-log` → 405 (spec Req "adds only read routes" scenario "POST … is not wired").
- [ ] 7.9 GET does not change the `admin_actions_log` row count (spec Req scenario "Serving the viewer writes no audit row").
- [ ] 7.10 `read_only`-role admin session → 200 (spec Req "accessible to every authenticated admin role").
- [ ] 7.11 No-match filter → 200 with empty-state indicator (spec Req "Empty result renders an empty state").

## 8. Pre-archive staging smoke + bookkeeping

- [ ] 8.1 Run `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` locally (per CLAUDE.md pre-push verification — both lint frameworks).
- [ ] 8.2 Manual staging deploy on the branch (`gh workflow run deploy-staging.yml --ref <branch>`); authenticate as the staging test admin (provisioned in Admin #3); confirm the `admin_login_success` row from that login is visible in `/admin/actions-log`; exercise each filter + the "older" control. (Per `openspec/project.md` § Staging deploy timing — pre-archive smoke.)
- [ ] 8.3 No `gradle/libs.versions.toml` change and no Flyway migration in this change — confirm `git diff --stat` shows neither (sanity check that scope stayed read-only-feature-only).
- [ ] 8.4 No new module added to `settings.gradle.kts` ⇒ no `dev/module-descriptions.txt` / `sync-readme.sh` update needed (confirm).
- [ ] 8.5 Archive bookkeeping handled by `/opsx:archive` (spec sync for the new `admin-actions-log-viewer` capability + move under `archive/`).
