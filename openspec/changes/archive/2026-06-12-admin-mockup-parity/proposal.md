# Proposal: admin-mockup-parity

## Why

The shipped admin panel is functional but intentionally unstyled, and three operator-reported gaps (2026-06-12, first real staging walkthrough) make it noticeably rough to use: `GET /admin` (no trailing slash) 404s instead of routing by session, the login screen diverges from the canonical mockup board, and the shell + landing page lack the mockup's navigation structure, identity/session context, and at-a-glance stats. The admin mockup board (`dev/mockups/nearyou-admin-mockup.html`, docs/11 § 3.6) froze the visual target for exactly these frames — this change brings the shipped surface up to it.

## What Changes

- **`/admin` (no trailing slash) routes by session instead of 404** — an explicit handler 302-redirects `/admin` → `/admin/`, which then applies the existing session gate (no session → `/admin/login`; session → index). No global `IgnoreTrailingSlash` (that would change matching for every API route).
- **Login screen parity with mockup frame 1**: remove the "Admin login" heading under the logo; start icons on the email / password / TOTP inputs; end-icon show/hide password toggle; start icon on the error banner. No behavior change to the login POST contract (no-enumeration, audit rows, etc.).
- **Shell parity with mockup frame 2**: grouped sidebar with per-item icons for the **shipped pages only** (Moderasi: Dashboard, Reports, Users · Anti-abuse & keamanan: Rejected identifiers · Sistem: Audit log) — "Usulan" (unshipped) mockup menus are NOT rendered; sidebar footer identity box (role chip + display name + session-expiry line + Logout button); top bar with active-page title + environment chip (e.g. STAGING). The Dashboard nav item is rendered even though the board annotates it with a hollow ("usulan") dot — that dot refers to the frame-3 *Operational Dashboard content* being unbuilt; the `/admin/` landing the item links to is shipped, and the operator needs a nav entry for the page they land on. The layout's current page footer is removed — frame 2 has none.
- **Landing page parity with mockup frame 2**: "Welcome back, {display name}" greeting; the static quick-link description cards (including the User moderation card, which frame 2 drops) are replaced by three **live stat cards** — Report queue (pending count + oldest pending age), Rejected identifiers (last-24 h count + top reason), Audit log (actions today + last action type); CSRF info banner under the cards.
- Mockup board status dots (`dot ada` / `dot usul`) are **board annotations, not product UI** (per the frame 2 caption) — they are not rendered.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `admin-panel-scaffold`: routing requirement gains the `/admin` → `/admin/` redirect (carve-out from "unmapped admin routes return 404"); the shared-layout requirement gains the grouped sidebar (shipped pages only), identity/session box, and top bar with page title + environment chip; the index requirement gains the greeting, the three live stat cards, and the CSRF info banner.
- `admin-login`: the "Login form GET renders the unauthenticated login page" requirement gains the mockup frame 1 presentation contract (no heading, input start icons, password visibility toggle, error-banner icon). The login POST contract is unchanged.

## Impact

- **Backend routes**: `AdminModule.kt` (mount `/admin` redirect), `AdminIndexRoute.kt` (index model gains admin identity, session expiry, env name, stat values).
- **New read queries** (admin-path, no shadow-ban views needed — admin surface): pending-report count + oldest pending `created_at` (reports/moderation queue), `rejected_identifiers` last-24 h count + top reason, `admin_actions_log` count-today + latest `action_type`. Each is a small aggregate on existing tables; no schema change, no migration.
- **Templates/CSS**: `layout.peb`, `index.peb`, `login.peb`, `admin.css` (vendored vanilla CSS; icons vendored — no external icon CDN, consistent with the existing no-client-framework posture).
- **Session model**: layout needs the authenticated admin's display name, role, and session expiry — plumbed from the existing session middleware/repository (read-only; no session behavior change).
- **Tests**: route test for `/admin` redirect; template/render assertions updated for login + index + layout; stat-card values covered by a DB-tagged route test.
- **Docs**: none beyond the OpenSpec lifecycle itself; mockup board already reflects the target (it is the source).
- **Not in scope**: Operational Dashboard (mockup frame 3 — replaces the scaffold landing later, separate change), `admin.nearyou.id` host move (Phase 3.5), any Usulan menu/page, restyling of the Reports/Users/Rejected-identifiers/Audit-log feature pages themselves.
