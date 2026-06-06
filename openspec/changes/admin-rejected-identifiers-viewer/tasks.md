## 1. Validation & precedent reconciliation

- [ ] 1.1 Run `openspec validate admin-rejected-identifiers-viewer --strict` and fix any flagged artifact issues before implementation.
- [ ] 1.2 Re-read the shipped `admin-actions-log-viewer` implementation (`backend/ktor/src/main/kotlin/id/nearyou/app/admin/routes/AdminActionsLogRoute.kt`, its repository, its Pebble templates, and its test spec) — this change clones that structure; confirm class/file/template naming conventions to mirror.
- [ ] 1.3 Confirm the live `rejected_identifiers` schema against `V3__signup_flow.sql` (columns `id`, `identifier_hash`, `identifier_type`, `reason`, `rejected_at`; the two CHECK enums; `rejected_identifiers_hash_idx`) — no migration is added (design D2), so verify the existing indexes are what the read path relies on.

## 2. Read-only repository + keyset query

- [ ] 2.1 Add a read-only repository (e.g. `RejectedIdentifierAdminRepository`) over `rejected_identifiers` in the `admin` package, mirroring the actions-log viewer's repository shape. Admin-module raw read of `rejected_identifiers` is lint-exempt (no `visible_*` view needed).
- [ ] 2.2 Implement the page query: `ORDER BY rejected_at DESC, id DESC` with a fixed page size, keyset predicate `(rejected_at, id) < (?, ?)` for the cursor path, fetching page-size + 1 rows to detect "older exists". No SQL `OFFSET`. All filter values bound as parameterized placeholders.
- [ ] 2.3 Implement the count-summary query: counts grouped by `reason` and by `identifier_type` scoped to the **same active filters** as the page query (no cursor), per the count-summary requirement.
- [ ] 2.4 Implement opaque-cursor encode/decode for `(rejected_at, id)`; a malformed/absent cursor decodes to "first page" (never throws), per the keyset requirement.

## 3. Route + auth-gate placement

- [ ] 3.1 Add `routes/AdminRejectedIdentifiersRoute.kt` exposing `get("/rejected-identifiers")` ONLY (no POST/PUT/PATCH/DELETE handler), mounted INSIDE the existing `authenticate(ADMIN_AUTH_NAME)` block in `AdminModule.kt`.
- [ ] 3.2 Branch on the `HX-Request` header: render the `#rejected-identifiers-table` fragment for HTMX requests, the full base-layout page otherwise. Filtered/paginated URLs stay shareable (plain GET reproduces the filtered view).
- [ ] 3.3 Verify the route is accessible to ANY authenticated admin role (no role gate) — matching `admin-actions-log-viewer`.

## 4. Filter parsing (lenient + parameterized)

- [ ] 4.1 Parse `reason` and `identifier_type` against their allowed enum sets; an unrecognized/over-long value is ignored (lenient), other filters still apply.
- [ ] 4.2 Parse `from` / `to` as dates bounding `rejected_at` (inclusive lower; exclusive `< to + 1 day` upper for whole-day-inclusive `to`); an unparseable date is ignored.
- [ ] 4.3 Confirm every applied filter is a parameterized placeholder — a SQL-metacharacter value is a literal/ignored, never executed.

## 5. Pebble templates

- [ ] 5.1 Add the full-page template extending the `admin-panel-scaffold` base layout: filter form (`reason`, `identifier_type`, `from`, `to`), the count summary, the `#rejected-identifiers-table` fragment, and the "older" pagination control (rendered only when an older page exists).
- [ ] 5.2 Add the table-fragment template (`#rejected-identifiers-table`) rendering rows: `identifier_hash` (monospace; may visually truncate with full value available), `identifier_type`, `reason`, `rejected_at`.
- [ ] 5.3 Render every value via Pebble default-on autoescaping — NO `raw` filter on any row value (HTML-escape requirement).
- [ ] 5.4 Render an explicit empty-state message when no rows match (including the empty-table case), not a blank/error page.
- [ ] 5.5 Hash-only PII discipline: the template surfaces only `identifier_hash`; NO raw-identifier resolution and NO `/admin/users` cross-link; NO clear/remove/delete control on any row (deferred-action negative guard).

## 6. Tests

- [ ] 6.1 Authenticated GET renders the table with an existing row's `identifier_hash` + `reason` + base-layout sections; unauthenticated GET → 302 `Location: /admin/login`.
- [ ] 6.2 Newest-first ordering (`rejected_at DESC, id DESC`) across 3 rows.
- [ ] 6.3 Keyset pagination: page capped at page size + "older" control present; following the cursor returns a strictly-older, non-overlapping page; malformed cursor → 200 newest page; last page omits the "older" control.
- [ ] 6.4 Filters: by `reason`; by `identifier_type`; AND-composition; date-range whole-day-inclusive upper bound.
- [ ] 6.5 Malformed-input safety: unrecognized enum ignored (other filters apply); SQL-metacharacter value treated as literal (table still exists/queryable afterward); unparseable date ignored; over-long value bounded → 200 (no 400/500).
- [ ] 6.6 Count summary: both reasons non-zero when unfiltered; `attestation_persistent_fail` bucket zero/omitted under `reason=age_under_18`.
- [ ] 6.7 HTMX vs full page: `HX-Request: true` → fragment with `id="rejected-identifiers-table"` and NO `<html>`/header/footer; plain GET → full page with base-layout sections + the fragment, reflecting the filter.
- [ ] 6.8 HTML-escape: a row whose `identifier_hash` fixture is `<script>alert(1)</script>` renders escaped (`&lt;script&gt;`), no live tag.
- [ ] 6.9 Read-only / mutation-unmapped: `POST /admin/rejected-identifiers` → 405; serving the viewer leaves `rejected_identifiers` count N and `admin_actions_log` count M unchanged (no audit row written on read).
- [ ] 6.10 Deferred-action negative guard: rendered page contains NO clear/remove control; no mutation route under the path removes a row.
- [ ] 6.11 Any-role access: a `role = 'read_only'` admin session → 200 with the table rendered.
- [ ] 6.12 Empty state: a no-match filter (`from=2999-01-01`) → 200 with the empty-state indicator, not an error/blank page.

## 7. Wiring & lint gate

- [ ] 7.1 Register the repository + route in the admin Koin module / route wiring (mirror how `AdminActionsLogRoute` is wired).
- [ ] 7.2 Append a one-line nav link to the admin base layout / index (additive — the only shared-file touch).
- [ ] 7.3 Run `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` locally (CI runs both lint frameworks — passing only detekt is insufficient). Confirm no `visible_*`/block-exclusion lint flags on the admin raw read.

## 8. Staging smoke (N/A) + docs + follow-ups

- [ ] 8.1 **Staging smoke: N/A** — read-only admin view with no runtime-config / secret / rate-limit / schema surface; no `deploy-staging.yml` branch deploy required. Record N/A in the archive commit body.
- [ ] 8.2 At archive time, flip `docs/07-Operations.md` § Core Features "Rejected Identifiers Viewer" from DESIGN to partially-shipped (read-only half) — note the deferred clear action.
- [ ] 8.3 Log `admin-rejected-identifiers-clear-action` to `FOLLOW_UPS.md` (the manual support-clear write surface: role + CSRF + audit-log + rate-limit via the `admin-destructive-action-rate-limit` limiter). Optionally log `admin-rejected-identifiers-keyset-index` (the `(rejected_at DESC, id DESC)` index lever) if cardinality grows.
