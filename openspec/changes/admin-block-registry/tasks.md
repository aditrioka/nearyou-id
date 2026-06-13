## 1. Validation & precedent reconciliation

- [x] 1.1 Run `openspec validate admin-block-registry --strict` and fix any flagged artifact issues before implementation.
- [x] 1.2 Re-read the shipped `admin-rejected-identifiers-viewer` implementation — the route `backend/ktor/src/main/kotlin/id/nearyou/app/admin/routes/AdminRejectedIdentifiersRoute.kt`, its repository at `admin/rejectedidentifiers/AdminRejectedIdentifiersRepository.kt` (the repo lives in a per-feature sub-package, NOT under `routes/`), its Pebble templates (`templates/admin/rejected-identifiers.peb` + the `*-table.peb` fragment), and its test spec — this change clones that structure; confirm class/file/template naming conventions to mirror, plus the opaque-cursor encode/decode and HX-Request fragment branch.
- [x] 1.3 Confirm the live `user_blocks` schema against `V5__user_blocks.sql` (columns `blocker_id`, `blocked_id`, `created_at`; composite PK `(blocker_id, blocked_id)`; both FKs `ON DELETE CASCADE`; no `created_at` index) and `users` against `V2__auth_foundation.sql` (`id`, `username`, `display_name`; `users_username_lower_idx`). No migration is added (design D2), so verify the existing PK + username-lower index are what the read path relies on.

## 2. Read-only repository + keyset query

- [x] 2.1 Add a read-only repository `AdminBlockRegistryRepository` over `user_blocks` in a new `admin/blockregistry/` sub-package (mirroring `admin/rejectedidentifiers/`). Admin-module raw read of `user_blocks` + `users` is lint-exempt (no `visible_*` view / block-exclusion join needed — design D3).
- [x] 2.2 Implement the page query: INNER `JOIN users` twice (blocker + blocked) to resolve usernames; `ORDER BY created_at DESC, blocker_id DESC, blocked_id DESC` with the fixed page size (50 — confirm it matches the shipped `admin-rejected-identifiers-viewer` `PAGE_SIZE`); keyset predicate `(created_at, blocker_id, blocked_id) < (?, ?, ?)` for the cursor path; fetch `pageSize + 1` rows to detect "older exists". No SQL `OFFSET`. All filter values bound as parameterized placeholders. **Project only the columns rendered** — `blocker_id`, `blocked_id`, `created_at`, both usernames, and the bidirectional flag; do NOT `SELECT users.display_name` or other PII columns from the joins (keep the surface minimal — review Q2).
- [x] 2.3 Compute the per-row "Bidirectional?" flag via `EXISTS (SELECT 1 FROM user_blocks r WHERE r.blocker_id = ub.blocked_id AND r.blocked_id = ub.blocker_id)` as a projected boolean column (design D6 — not a row-multiplying self-join).
- [x] 2.4 Implement opaque-cursor encode/decode for `(created_at, blocker_id, blocked_id)` (base64url; epoch-micros + the two UUIDs); a malformed/absent cursor decodes to "first page" (never throws), per the keyset requirement.

## 3. Either-side search parsing (lenient + parameterized)

- [x] 3.1 Parse the `q` parameter leniently: trim; blank/absent → no filter. If `q` parses as a `UUID`, build the predicate `(blocker_id = ? OR blocked_id = ?)` (the UUID bound twice). Otherwise treat `q` as an exact case-insensitive username and build `(LOWER(b.username) = LOWER(?) OR LOWER(t.username) = LOWER(?))` against the two joined `users` aliases (design D4).
- [x] 3.2 Length-bound the `q` value during parse (≤ the 60-char username column width is sufficient; an over-long value matches no username and yields the empty state — never a 400/500). Confirm SQL-metacharacter `q` is bound as a literal (parameterized), never interpolated.

## 4. Route + auth-gate placement

- [x] 4.1 Add `routes/AdminBlockRegistryRoute.kt` exposing `get("/blocks")` ONLY (no POST/PUT/PATCH/DELETE handler), mounted INSIDE the existing `authenticate(ADMIN_AUTH_NAME)` block in `AdminModule.kt`.
- [x] 4.2 Branch on the `HX-Request` header: render the `#block-registry-table` fragment for HTMX requests, the full base-layout page otherwise. Filtered/paginated URLs stay shareable (plain GET reproduces the filtered view).
- [x] 4.3 Verify the route is accessible to ANY authenticated admin role (no role gate) — matching `admin-rejected-identifiers-viewer`.

## 5. Pebble templates

- [x] 5.1 Add the full-page template (`templates/admin/block-registry.peb`) extending the `admin-panel-scaffold` base layout: page title + read-only badge, the search form (single "username or user ID (either side)" field), the read-only banner (enforcement stays in the product path via the bidirectional NOT-IN join — `BlockExclusionJoinRule`), and the `#block-registry-table` fragment include.
- [x] 5.2 Add the table fragment template (`#block-registry-table`): columns Blocker / arrow / Blocked / Since (UTC) / Bidirectional?; blocker + blocked usernames rendered as links to `/admin/users?q=<username>` (design D5); the "older" pagination control carrying `q` + `cursor`; the empty-state message when no rows. All values via default-on Pebble autoescape — NO `raw` filter (escaping is load-bearing for user-controlled usernames — design D1 delta).

## 6. Nav entry + Koin wiring

- [x] 6.1 Append a `Block Registry` nav entry under the admin "Anti-abuse" group with `activePath = /admin/blocks`, mirroring the `admin-rejected-identifiers-viewer` entry (design D7).
- [x] 6.2 Wire `AdminBlockRegistryRepository` into the admin Koin module and the route registration alongside the other admin viewers.

## 7. Tests (mirror the AdminRejectedIdentifiers suite)

- [x] 7.1 Auth gate: unauthenticated `GET /admin/blocks` → 302 `Location: /admin/login`; authenticated → 200 rendering the table with both usernames + base-layout sections.
- [x] 7.1a Deep-link (review B1): assert the blocker + blocked usernames render as links whose `href` is `/admin/users?q=<username>` AND assert the NEGATIVE — the link does NOT target `/admin/users/{id}` (guards the design-D5 decoupling from the in-flight PR #251 profile page).
- [x] 7.2 Ordering: three rows with strictly increasing `created_at` render newest-first.
- [x] 7.2a UTC render (review B2): with a fixture row whose `created_at` is pinned at an EXPLICIT UTC offset near a day boundary (e.g. `...T23:30:00Z` — never CI-host-local time), assert the rendered "Since (UTC)" cell shows the UTC date/time, NOT a host-local (+07:00 WIB) interpretation. Mirrors the `admin-rejected-identifiers-viewer` / `admin-actions-log-viewer` UTC-fixture discipline.
- [x] 7.3 Keyset pagination: page-size cap + older-control presence; **follow the rendered older-link `href` over HTTP** (exercising the cursor encode→query-param→decode round-trip, not just inspecting the href) and assert it returns a strictly-older non-overlapping page; malformed cursor → first page (200); last page omits the older control; exact page-size-boundary fencepost (page-size → no control, page-size+1 → control); identical-`created_at` rows paginate by the `(blocker_id, blocked_id)` PK tiebreaker with no loss/duplication; the older-link carries the active `q`.
- [x] 7.4 Search: by username matches pairs on EITHER side; case-insensitive + exact (not substring); by UUID matches either id column on either side; absent `q` → unfiltered; a term matching no user → empty state (200); SQL-metacharacter `q` bound as literal (`user_blocks` still queryable afterward); over-long `q` → empty state, not 500.
- [x] 7.5 Bidirectional indicator: mutual pair `(A↔B)` → affirmative on the `(A→B)` row; one-directional `(A→B)` only → negative; the `EXISTS` flag does not duplicate the directed row.
- [x] 7.6 HTMX vs plain-GET: `HX-Request: true` → only the `#block-registry-table` fragment (no `<html>`/header/footer); no header → full page incl. the fragment + reflecting the filter.
- [x] 7.7 HTML-escaping: a fixture username containing `<script>alert(1)</script>` renders escaped (`&lt;script&gt;`), never a live tag.
- [x] 7.8 Read-only: `POST /admin/blocks` → 405; serving the viewer writes no `admin_actions_log` row and mutates no `user_blocks` row; viewing a pair writes no `notifications` row for either user.
- [x] 7.9 Role access: a `read_only` admin session → 200 with the table rendered (no role rejection).
- [x] 7.10 Empty state renders both as a full page and inside the HTMX fragment — for BOTH the no-match-search case (`q` matches no user) AND the truly-empty `user_blocks` table (unfiltered, zero rows), per the empty-state requirement's "including the unfiltered case of an empty table" clause (review Q1).
- [x] 7.11 Run `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` — all green (pre-push gate).

## 8. Follow-ups & archive

- [ ] 8.1 File the `admin-block-registry-keyset-index` follow-up GitHub issue (label `follow-up` + `admin`): add a `(created_at DESC, blocker_id DESC, blocked_id DESC)` index on `user_blocks` if cardinality grows beyond the low-volume MVP assumption (design D2). Optionally an `admin-block-registry-profile-deeplink` follow-up to re-point the username deep-link to `/admin/users/{id}` once PR #251 lands (design D5).
- [ ] 8.2 At archive: flip `docs/07-Operations.md` § Core Features "Block User Registry" from DESIGN to shipped; sync `openspec/specs/admin-block-registry/` from the change delta; move the change under `openspec/changes/archive/`; mark the staging-smoke section N/A (read-only, no runtime-config/secret/rate-limit surface).
