## 0. Dependency gate + apply prep

- [ ] 0.1 Confirm #358 (`csam-detection-webhook-and-archive`) is squash-merged to `main`; rebase this branch onto post-#358 `main` (the `csam_detection_archive` table, `CsamDetectionService`, `CsamRepository`, `CsamMetadataEncryptor`, and admin-auth reuse must be present before any feat commit)
- [ ] 0.2 Run the pre-implementation library re-check ONLY if applicable — N/A here (no `libs.versions.toml` change; reuses existing infra)
- [ ] 0.3 Render admin mockup frame f13 (`dev/mockups/nearyou-admin-mockup.html`) and generate its measurement annex (`dev/scripts/mockup-measure.sh nearyou-admin-mockup 13`) for exact spacing/typography/token mapping (on-demand, never committed)

## 1. Repository + service surface (reuse, do not fork)

- [ ] 1.1 Extend `CsamRepository` with a keyset list/filter query over `csam_detection_archive` ordered `(created_at DESC, id DESC)`, parameterized by `source`, Kominfo status (`kominfo_reported_at IS [NOT] NULL`), and a `created_at` UTC range + cursor; project only scalar columns (no `encrypted_metadata` in the list projection)
- [ ] 1.2 Add a `CsamRepository` pending-count query (`kominfo_reported_at IS NULL`) for the summary banner
- [ ] 1.3 Add a `CsamRepository` Kominfo-update write: `UPDATE csam_detection_archive SET kominfo_report_id = ?, kominfo_reported_at = now() WHERE id = ? AND kominfo_reported_at IS NULL` returning affected-rows (idempotency guard — 0 rows = already filed/unknown)
- [ ] 1.4 Add a `CsamRepository` single-row fetch of `encrypted_metadata` by id for the decrypt path
- [ ] 1.5 Confirm `CsamMetadataEncryptor` exposes a `decrypt` accessor (AAD-bound to `image_hash`); if #358 shipped encrypt-only, add the fail-soft decrypt counterpart in the same class (no new class)
- [ ] 1.6 Annotate the new raw `csam_detection_archive` reads/writes as admin-module-sanctioned per the lint convention (admin module is an allowed raw reader)

## 2. Detection-log viewer (`GET /admin/csam`)

- [ ] 2.1 Add the `GET /admin/csam` admin route (any authenticated admin role) wired into `Application.admin()` + Koin DI, mirroring `admin-privacy-flip-monitor` / `admin-subscription-grace-monitor`
- [ ] 2.2 Parse + validate filters (`source`, kominfo status, `from`/`to`, cursor); HTML-escape all rendered values
- [ ] 2.3 Pebble template + vendored CSS per mockup f13 (filters bar, pending-count banner, table: Detected/Image hash/Source/Enforcement/Kominfo/Unblock); HTMX fragment swap + plain-`GET` full-page fallback
- [ ] 2.4 Add the "Anti-abuse → CSAM Detection Log" admin-nav entry
- [ ] 2.5 Ensure NO image bytes / content URL render anywhere on the page (image-content-free invariant)

## 3. Admin-triggered takedown (`POST /admin/csam/takedown`)

- [ ] 3.1 Add the `POST /admin/csam/takedown` route on the admin subtree: `owner`/`admin` role gate + same-session CSRF verification; reject others 403
- [ ] 3.2 Require non-blank `image_id` + `image_hash`; call `CsamDetectionService.handleDetection(Input(source = ADMIN_MANUAL, actorAdminId = session admin, …))` in-process (design D1)
- [ ] 3.3 Render the `actioned`/`archived` result as an HTML fragment; add an `hx-confirm` destructive guard on the form
- [ ] 3.4 Wire the takedown into the shared 20/hour-per-admin destructive rate-limit budget (design D5)

## 4. Kominfo report tracking (`POST /admin/csam/{id}/kominfo-report`)

- [ ] 4.1 Add the route: `owner`/`admin` + CSRF + required non-blank report id; reject others 403
- [ ] 4.2 In one transaction, run the idempotent Kominfo update (1.3) + write one immutable `admin_actions_log` `csam_kominfo_reported` row (before/after state); on 0 affected rows (already filed/unknown), reject with no audit row
- [ ] 4.3 Wire a dedicated per-admin Kominfo-report rate counter off the audit-trail limiter (~30/hr; finalize cap), independent of the destructive budget
- [ ] 4.4 Render the updated Kominfo cell as an HTMX fragment swap

## 5. Audit-logged metadata decrypt (`POST /admin/csam/{id}/decrypt`)

- [ ] 5.1 Add the route: `owner`/`admin` + CSRF; reject others 403
- [ ] 5.2 Fetch `encrypted_metadata` (1.4), decrypt via the admin-only helper (1.5); render the plaintext metadata fragment (scalar fields only — no image bytes)
- [ ] 5.3 Write one `admin_actions_log` `csam_metadata_decrypted` row on EVERY attempt (including failures)
- [ ] 5.4 Fail-soft: key unset / `encrypted_metadata` NULL → graceful "metadata unavailable" fragment, no 500; still audit
- [ ] 5.5 Wire a dedicated per-admin decrypt rate counter off the audit-trail limiter (~30/hr; finalize cap)

## 6. Unblock-request surfacing

- [ ] 6.1 For `cf_worker` rows render the CF review/unblock link-out + status indicator; `admin_manual` rows render no unblock affordance

## 7. Tests — one per spec scenario (no compression; CLAUDE.md § Engineering judgment)

- [ ] 7.1 Viewer: newest-first, filter-by-source, filter-by-kominfo-pending, date-range, keyset pagination, plain-GET fallback, pending-count summary, image-bytes-never-rendered (8 scenarios)
- [ ] 7.2 Takedown: owner/admin actioned, ledger-miss archived, idempotent re-invoke, read-only 403, missing/cross-session CSRF 403, missing-field validation (6 scenarios)
- [ ] 7.3 Kominfo: owner/admin files (columns + audit row), re-file rejected (timestamp preserved, no audit), blank-id rejected, read-only 403, dedicated-counter rate-limit (5 scenarios)
- [ ] 7.4 Decrypt: owner/admin decrypts (fragment + audit row), key-unprovisioned fail-soft (+audit, no 500), read-only 403, no-image-bytes (4 scenarios)
- [ ] 7.5 Unblock surfacing: cf_worker shows link, admin_manual shows none (2 scenarios)
- [ ] 7.6 Security invariants: cross-session CSRF replay rejected on every write, unauthenticated → 302 `/admin/login`, no-image-content-anywhere (3 scenarios)
- [ ] 7.7 Test-data discipline: seed archive rows with deterministic fixtures (explicit `source`/`kominfo_reported_at`); per-test cleanup by id/prefix so the new DB-tagged spec does not pollute other suites; do NOT `autoClose` a DataSource a cleanup hook uses
- [ ] 7.8 Run the touched-area DB-tagged tests explicitly (`--tests "*admin*csam*"`) — CI runs `!network` (DB tests included), so a local `!database` run would green-but-skip them

## 8. Gates + conformance

- [ ] 8.1 `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` green on a fresh DB (full set, no `!database` shortcut)
- [ ] 8.2 If `admin/static/admin.css` was touched, re-pin the CI static-asset SHA256 (`htmx.min.js.SHA256SUMS` inventory + `sha256sum -c`) — it is a CI lint-lane bash step the local gradle gate misses
- [ ] 8.3 Confirm no migration was added and no `libs.versions.toml` change; `openspec validate admin-csam-detection-log-viewer --strict` green

## 9. UI DoD + pre-archive smoke (docs/11 §5)

- [ ] 9.1 Manual admin bring-up via verify-loop (local Ktor boot, admin auth + TOTP) — exercise viewer + takedown form + Kominfo write + decrypt; capture screenshots into the PR body BEFORE archive
- [ ] 9.2 Staging branch deploy (`gh workflow run deploy-staging.yml --ref admin-csam-detection-log-viewer`) + admin smoke: unauthenticated `GET /admin/csam` → 302 `/admin/login` (route mounted + gated + app booted)
- [ ] 9.3 PR title/body refreshed at the phase boundary (project.md hard rule)
