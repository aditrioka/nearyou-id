# Tasks: admin-mockup-parity

## 1. Mockup grounding (docs/11 § 3.6 render rule)

- [x] 1.1 Render mockup frames `#f01` (login) and `#f02` (shell + landing) from `dev/mockups/nearyou-admin-mockup.html` (headless-Chrome standalone-frame extraction per the known render recipe) and generate the per-frame measurement annex via `dev/scripts/mockup-measure.sh` for both frames (spacing / typography / tokens; output is on-demand, never committed)
- [x] 1.2 Inventory the icon set needed from the frames (mail, key, timer, visibility, visibility_off, error, info, dashboard, flag, group, block, receipt_long, logout) and vendor them as a single `icons.peb` inline-SVG macro partial (Material Symbols outline 24dp paths, `currentColor`, `data-icon="<name>"` attribute on each emitted `<svg>` for test/annex assertions, Apache-2.0 attribution comment)

## 2. `/admin` redirect (routing)

- [x] 2.1 Mount `get("")` inside `route("/admin")` (outside the `authenticate` block) responding 302 with `Location: /admin/`, per design D1
- [x] 2.2 Route test: `GET /admin` → 302 `/admin/` with and without a session cookie; `POST /admin` → 404 (redirect is GET-only; verified — Ktor resolves non-GET on the bare node as path-not-found); existing 404-for-unmapped and 405-for-POST-index tests still green

## 3. Identity/session plumbing

- [x] 3.1 Add `displayName` to `AdminPrincipal`, populated from the same session-validation read (no extra query), per design D5
- [x] 3.2 Pass layout model values from authenticated routes: admin display name, role, computed session-expiry display value (`min(lastActive_afterRefresh + idleTimeout, expiresAt)`, formatted `HH:mm` UTC), idle-timeout label, environment name (design D6), page title + active-path marker (design D7/D8)

## 4. Shell (`layout.peb` + `admin.css`)

- [x] 4.1 Rebuild the layout shell per frame `#f02` + measurement annex: grouped sidebar (Moderasi → Dashboard / Reports / Users; Anti-abuse & keamanan → Rejected identifiers; Sistem → Audit log) with per-item icons + active state; NO unshipped menu items, NO status dots
- [x] 4.2 Sidebar footer identity box: role chip (uppercase; owner accent), display name, `Session idle {n} m · expires {HH:mm} UTC` line, Logout button with icon (existing POST + `_csrf` semantics untouched)
- [x] 4.3 Top bar: page-title crumb + environment chip (STAGING/PRODUCTION/DEV uppercased)
- [x] 4.4 Keep the existing narrow-viewport hamburger behavior working with the new sidebar markup (frame `#f04b` responsive contract)
- [x] 4.5 Feature pages (`reports.peb`, `users.peb`, `rejected-identifiers.peb`, `actions-log.peb`) pass their `pageTitle` + active-path values; in-page content otherwise untouched
- [x] 4.6 Layout/shell route tests: five shipped nav items present with `data-icon` assertions, no Usulan items, no footer, identity box fields, env chip (test-config value), active-state on `/admin/reports`, session-expiry display for both branches (idle-deadline-sooner AND absolute-cap-sooner, `HH:mm` UTC format)

## 5. Landing page (`index.peb` + stats)

- [x] 5.1 `AdminIndexStatsRepository`: three parameterized aggregate reads per design D4 (pending count + oldest pending `created_at`; rejected last-24h count + top reason with deterministic tie-break; audit actions today (UTC) + newest `action_type`) — `EXPLAIN` each against the dev DB and note index usage in the PR
- [x] 5.2 Index route: model gains greeting name + stat values + relative-age formatting ("2 h ago") done server-side in the route
- [x] 5.3 `index.peb` per frame `#f02`: `Welcome back, {name}` heading, three stat cards (icon + title + kv rows, linking to their pages), CSRF info banner; static description cards (incl. User moderation card) removed
- [x] 5.4 DB-tagged route test: seeded stat values render (4 pending / oldest 2 h; 12 last-24h / `age_under_18`; 9 today / `user_suspended`) + top-reason tie-break case (equal counts → alphabetically-first reason renders) + empty-DB zero-state placeholders + no "User moderation" card. Pool follows the autoClose + size-2 CI pattern

## 6. Login page (`login.peb`)

- [x] 6.1 Rebuild per frame `#f01` + measurement annex: logo only (drop "Admin login" heading), start icons on email/password/totp inputs, password visibility toggle (`<button type="button">` + minimal inline JS per design D3), error banner with leading icon
- [x] 6.2 Verify the no-enumeration byte-equality contract still holds (existing scenario/test + assert the error banner markup is failure-path-invariant)
- [x] 6.3 Login route tests updated: presentation scenario (no heading, icons present, toggle script present), CSRF-meta-absence + authenticated-redirect scenarios still green

## 7. Verification & delivery

- [x] 7.1 Full local gate: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` (fresh DB containers for the DB-tagged specs)
- [x] 7.2 Manual verification per docs/11 § 5 DoD: run the panel locally (`verify-loop` recipe), screenshot login + landing + one feature page at desktop and narrow widths, compare against rendered mockup frames, attach evidence to the PR body
- [ ] 7.3 Staging smoke after deploy: `GET /admin` 302-chain works unauthenticated; login → landing shows live stats; `smoke-admin-login-argon2-totp.sh` green
- [x] 7.4 `openspec validate admin-mockup-parity --strict` + PR title/body current at the phase boundary
