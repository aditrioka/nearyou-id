# Design: admin-mockup-parity

## Context

The admin panel ships as Pebble templates + HTMX + vendored vanilla CSS (`admin-panel-scaffold` spec; docs/11 § 3.6). The visual target for every frame is the admin mockup board `dev/mockups/nearyou-admin-mockup.html` — frame 1 (login) and frame 2 (shell + scaffold landing) are the targets here; frame 3 (Operational Dashboard) is a later, separate change that *replaces* the scaffold landing. Current state: `layout.peb` renders a flat 4-link nav + bare Logout button; `index.peb` renders four static description cards; `login.peb` renders a heading + 3 unadorned inputs; `GET /admin` (no trailing slash) 404s because only `get("/")` is mounted inside `route("/admin")` and `IgnoreTrailingSlash` is not installed.

Constraints: no client framework, no external CDN (everything vendored); login no-enumeration contract (byte-identical failure responses) must hold; `AdminPrincipal` already carries `role` / `expiresAt` / `lastActiveAt` but not the display name; admin reads are an admin surface (no shadow-ban view requirement — those views exist for member-facing paths).

## Goals / Non-Goals

**Goals:**

- `/admin` behaves like `/admin/` from the operator's point of view (session-routed, never 404).
- Login, shell, and scaffold-landing visuals match mockup frames 1–2 at the 1240 px snapshot, translated through the existing `admin.css` token set; keep the existing narrow-viewport (hamburger) behavior working.
- Landing stat cards show live values from the three shipped data sources.
- Shipped-only sidebar (decision recorded in the proposal): Dashboard, Reports, Users, Rejected identifiers, Audit log.

**Non-Goals:**

- Operational Dashboard (frame 3), `admin.nearyou.id` host + IAP move, any Usulan page or menu, restyling the four feature pages' tables/forms (their layout-level chrome — top bar, sidebar — improves for free via `layout.peb`; their in-page content is untouched), i18n of admin copy (stays English, matching shipped templates).

## Decisions

**D1 — `/admin` redirect: explicit `get("")` inside `route("/admin")`, outside the `authenticate` block, responding 302 → `/admin/`.**
Unauthenticated flow becomes two hops (`/admin` → `/admin/` → `/admin/login`), which is fine and keeps the session logic in exactly one place (the authenticated index route). Alternatives rejected: global `IgnoreTrailingSlash` (changes matching semantics for every API route — too broad a blast radius for a cosmetic fix); a session-aware handler at `/admin` (duplicates the auth gate). 302 over 301: nothing should cache the bare-path redirect permanently while the panel's routing is still evolving.

**D2 — Icons: vendored inline-SVG partial, not an icon font.**
The mockup uses the Material Symbols *font* with ligature names (`mail`, `key`, `timer`, `visibility`, `error`, `info`, `dashboard`, `flag`, `group`, `block`, `receipt_long`, `logout`). The product needs ~12 icons; vendoring the font (~200 KB+ subset pipeline) is not worth it. Instead: one `icons.peb` Pebble macro emitting inline `<svg>` (24dp Material Symbols outlines, `fill="currentColor"`), called as `{{ icon('mail') }}`. Apache-2.0 attribution comment at the top of the partial. Alternative rejected: separate `.svg` static files + `<img>` (can't inherit `currentColor`).

**D3 — Password visibility toggle: minimal vanilla JS in `login.peb`.**
A `<button type="button">` end-icon that flips the input's `type` between `password`/`text` and swaps the icon. No HTMX involvement, no new static asset (inline `<script>` of a few lines, mirroring the layout's existing inline `htmx:configRequest` hook pattern). No-JS degradation: the button does nothing (input stays masked) — acceptable; masking is the safe default.

**D4 — Landing stats: one `AdminIndexStatsRepository` with three aggregate reads, executed per request.**
- Report queue: `COUNT(*)` + `MIN(created_at)` over pending reports (same pending predicate the report-queue page uses).
- Rejected identifiers: `COUNT(*)` over last 24 h + top `reason` (`GROUP BY reason ORDER BY count DESC, reason ASC LIMIT 1` — tie-broken deterministically).
- Audit log: `COUNT(*)` for the current UTC day + `action_type` of the newest row.

Per-request with no cache: this is a solo-operator panel; three indexed aggregates per index render is nothing. Day boundary and all timestamps are UTC (the panel already renders UTC; the session line in frame 2 says "UTC" explicitly). "Oldest" renders as a relative age ("2 h ago") via a small server-side formatter in the route (Pebble model value, not template logic). Lint: none of the three tables is `posts`/`users`, so `RawFromPostsRule`/`BlockExclusionJoinRule` are inert; queries are parameterized JDBC like the existing admin repositories.

**D5 — Identity box data: add `displayName` to `AdminPrincipal`.**
The session-validation read already returns the admin row's `role`; selecting `display_name` in the same query and carrying it on the principal costs nothing and avoids a second per-request lookup in the layout path. The identity box renders: role chip (uppercased role, `owner` gets the accent style per mockup), display name, and `Session idle {idleTimeout} · expires {HH:mm} UTC` where the expiry shown is `min(lastActiveAt_afterRefresh + idleTimeout, expiresAt)` — the actual moment this session dies if the operator goes idle now. The 8 h absolute cap thus surfaces naturally as it becomes the binding constraint.

**D6 — Environment chip: derive from the existing deployment-environment config.**
The chip renders the deployment env name uppercased (STAGING / PRODUCTION / DEV) from the app's existing environment configuration (the same source that namespaces secrets / selects config), passed once into the layout model. No new env var.

**D7 — Page-title plumbing: each page template already extends `layout.peb`; the layout gains a `pageTitle` model value rendered in the top bar crumb.**
Index passes "Dashboard"; Reports/Users/Rejected identifiers/Audit log pass their names — this is the one place feature pages are touched (one model value each), keeping the top bar truthful on every page.

**D8 — Sidebar markup lives entirely in `layout.peb`** (groups: Moderasi → Dashboard `/admin/`, Reports, Users; Anti-abuse & keamanan → Rejected identifiers; Sistem → Audit log), with an `active` state matched on the current path (model value, same mechanism as `pageTitle`). The mockup's per-item status dots are board annotations (frame 2 caption: "dot hijau = shipped, dot hollow = usulan") and are not product UI.

## Risks / Trade-offs

- **[No-enumeration regression risk]** Touching `login.peb` could accidentally make failure responses differ (e.g. conditional icon markup). → The error banner stays a single generic slot exactly as today (icon + fixed message, same bytes on every failure path); the existing byte-equality scenario in `admin-login` + the staging smoke (`smoke-admin-login-argon2-totp.sh` step e) gate it.
- **[Template assertion churn]** Existing route tests assert on current template content. → Update assertions alongside; the spec deltas below are the contract for what the new assertions check.
- **[Stat query cost on a cold panel]** Unindexed aggregates could table-scan. → The three tables are small at current scale and the predicates ride existing indexes (pending-status partial index on reports; `created_at` ordering on `admin_actions_log`); verify with `EXPLAIN` during apply and note findings in the PR. No `NOW()` in any *index* definition (queries may use it; the partial-index invariant is about index `WHERE` clauses).
- **[Icon drift vs mockup]** Hand-vendored SVGs may not match the font glyphs pixel-perfectly. → Source paths from the official Material Symbols outline set at 24dp; the docs/11 § 2.8-equivalent render + measurement-annex comparison during apply is the gate.

## Migration Plan

No schema change, no migration, no config change. Ships as one deploy; rollback = redeploy previous revision. The `/admin` 302 is additive (a previously-404 path starts working).

## Open Questions

(none — both operator decisions, shipped-only sidebar and single-change packaging, are recorded in the proposal)
