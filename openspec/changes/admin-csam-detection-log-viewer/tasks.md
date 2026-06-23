## 0. Dependency gate + apply prep

- [x] 0.1 Confirm #358 (`csam-detection-webhook-and-archive`) is squash-merged to `main`; rebase this branch onto post-#358 `main` (the `csam_detection_archive` table, `CsamDetectionService`, `CsamRepository`, `CsamMetadataEncryptor`, and admin-auth reuse must be present before any feat commit)
- [x] 0.2 Run the pre-implementation library re-check ONLY if applicable — N/A here (no `libs.versions.toml` change; reuses existing infra)
- [x] 0.3 Render admin mockup frame f13 (`dev/mockups/nearyou-admin-mockup.html`) and generate its measurement annex (`dev/scripts/mockup-measure.sh nearyou-admin-mockup 13`) for exact spacing/typography/token mapping (on-demand, never committed)

## 1. Repository + service surface (reuse, do not fork)

- [x] 1.1 Extend `CsamRepository` with a keyset list/filter query over `csam_detection_archive` ordered `(created_at DESC, id DESC)`, parameterized by `source`, Kominfo status (`kominfo_reported_at IS [NOT] NULL`), and a `created_at` UTC range + cursor; project only scalar columns (no `encrypted_metadata` in the list projection)
- [x] 1.2 Add a `CsamRepository` pending-count query (`kominfo_reported_at IS NULL`) for the summary banner
- [x] 1.3 Add a `CsamRepository` Kominfo-update write: `UPDATE csam_detection_archive SET kominfo_report_id = ?, kominfo_reported_at = now() WHERE id = ? AND kominfo_reported_at IS NULL` returning affected-rows (idempotency guard — 0 rows = already filed/unknown)
- [x] 1.4 Add a `CsamRepository` single-row fetch of `encrypted_metadata` by id for the decrypt path
- [x] 1.5 Reuse the existing `CsamMetadataEncryptor.decrypt` accessor (#358 ships it, AAD-bound to `image_hash`) for the decrypt path — no new class; wrap its key-unset/NULL cases fail-soft at the call site
- [x] 1.6 Annotate the new raw `csam_detection_archive` reads/writes as admin-module-sanctioned per the lint convention (admin module is an allowed raw reader)

## 2. Detection-log viewer (`GET /admin/csam`)

- [x] 2.1 Add the `GET /admin/csam` admin route (any authenticated admin role) wired into `Application.admin()` + Koin DI, mirroring `admin-privacy-flip-monitor` / `admin-subscription-grace-monitor`
- [x] 2.2 Parse + validate filters (`source`, kominfo status, `from`/`to`, cursor); HTML-escape all rendered values
- [x] 2.3 Pebble template + vendored CSS per mockup f13 (filters bar, pending-count banner, table: Detected/Image hash/Source/Enforcement/Kominfo/Unblock); HTMX fragment swap + plain-`GET` full-page fallback
- [x] 2.4 Add the "Anti-abuse → CSAM Detection Log" admin-nav entry
- [x] 2.5 Ensure NO image bytes / content URL render anywhere on the page (image-content-free invariant)

## 3. Admin-triggered takedown (`POST /admin/csam/takedown`)

- [x] 3.1 Add the `POST /admin/csam/takedown` route on the admin subtree: `owner`/`admin` role gate + same-session CSRF verification; reject others 403
- [x] 3.2 Require non-blank `image_id` + `image_hash`; call `CsamDetectionService.handleDetection(Input(source = ADMIN_MANUAL, actorAdminId = session admin, …))` in-process (design D1)
- [x] 3.3 Render the `actioned`/`archived` result as an HTML fragment; add an `hx-confirm` destructive guard on the form
- [x] 3.4 Gate the takedown with a route-level **pre-flight** check against the shared 20/hour-per-admin destructive limiter (read the `admin_actions_log` trail BEFORE calling the service — the service owns its own tx, so the in-tx limiter placement used by suspend/ban does not apply here; reuse the SAME `DestructiveActionRateLimiter`, not a new one) (design D5)

## 4. Kominfo report tracking (`POST /admin/csam/{id}/kominfo-report`)

- [x] 4.1 Add the route: `owner`/`admin` + CSRF + required non-blank report id; reject others 403
- [x] 4.2 In one transaction, run the idempotent Kominfo update (1.3) + write one immutable `admin_actions_log` `csam_kominfo_reported` row (before/after state); on 0 affected rows (already filed/unknown), reject with no audit row
- [x] 4.3 Wire a dedicated per-admin Kominfo-report rate counter off the audit-trail limiter (~30/hr; finalize cap), independent of the destructive budget
- [x] 4.4 Render the updated Kominfo cell as an HTMX fragment swap

## 5. Audit-logged metadata decrypt (`POST /admin/csam/{id}/decrypt`)

- [x] 5.1 Add the route: `owner`/`admin` + CSRF; reject others 403
- [x] 5.2 Fetch `encrypted_metadata` (1.4), decrypt via the admin-only helper (1.5); render the plaintext metadata fragment (scalar fields only — no image bytes)
- [x] 5.3 Write one `admin_actions_log` `csam_metadata_decrypted` row on EVERY attempt (including failures)
- [x] 5.4 Fail-soft: key unset / `encrypted_metadata` NULL → graceful "metadata unavailable" fragment, no 500; still audit
- [x] 5.5 Wire a dedicated per-admin decrypt rate counter off the audit-trail limiter (~30/hr; finalize cap)

## 6. Unblock-request surfacing

- [x] 6.1 For `cf_worker` rows render the CF review/unblock link-out + status indicator; `admin_manual` rows render no unblock affordance

## 7. Tests — one per spec scenario (31 scenarios, no compression; CLAUDE.md § Engineering judgment)

- [x] 7.1 Viewer (10): newest-first; filter-by-source; filter-by-kominfo-pending; date-range; keyset **stable across an insert-between-pages** (new newest row does not shift the cursor window); **composable filters combined (ANDed, not last-wins)**; **filter inputs HTML-escaped** (feed a `<script>`/quote payload, assert escaped in the response — admin-render XSS guard); plain-GET fallback; pending-count summary; image-bytes-never-rendered
- [x] 7.2 Takedown (7): owner/admin actioned; ledger-miss archived; **idempotent re-invoke asserted count-stable** (one ban event, NO second cascade batch, exactly one `csam_detection_archive` row by `image_hash` — not just "200 converges"); read-only 403; missing/cross-session CSRF 403; missing-field validation; **over-budget pre-flight rejection** (at the 20/hr destructive cap → rejected before the service is invoked)
- [x] 7.3 Kominfo (5): owner/admin files (columns + audit row); re-file rejected — **assert the ORIGINAL `kominfo_reported_at` is byte-equal pre/post** (seed via DB-read or `truncatedTo(MICROS)` to dodge the CI macOS-micros/Linux-nanos round-trip false-fail) + no audit row; blank-id rejected; read-only 403; dedicated-counter rate-limit (independent of the 20/hr destructive budget)
- [x] 7.4 Decrypt (4): owner/admin decrypts (fragment + exactly one audit row); **fail-soft writes exactly one `csam_metadata_decrypted` row for BOTH triggers** (key unset AND `encrypted_metadata` NULL), no 500; read-only 403 → **no `csam_metadata_decrypted` audit row written**; no-image-bytes in the fragment
- [x] 7.5 Unblock surfacing (2): cf_worker shows link; admin_manual shows none
- [x] 7.6 Security invariants (3): cross-session CSRF replay rejected on every write (takedown + kominfo + decrypt); unauthenticated → 302 `/admin/login` on `GET` **AND every `/admin/csam/*` write route**; no-image-content-anywhere
- [x] 7.7 Test-data discipline: seed archive rows with deterministic fixtures (explicit `source`/`kominfo_reported_at`); per-test cleanup by id/prefix so the new DB-tagged spec does not pollute other suites; do NOT `autoClose` a DataSource a cleanup hook uses; the new DB-tagged `*RoutesTest` HikariPool is size≈2 + autoClosed (CI connection budget) and is NOT the same DataSource the cleanup hook uses
- [x] 7.8 Run the touched-area DB-tagged tests explicitly (`--tests "*admin*csam*"`) — CI runs `!network` (DB tests included), so a local `!database` run would green-but-skip them

## 8. Gates + conformance

- [x] 8.1 `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` green on a fresh DB (full set, no `!database` shortcut)
- [x] 8.2 If `admin/static/admin.css` was touched, re-pin the CI static-asset SHA256 (`htmx.min.js.SHA256SUMS` inventory + `sha256sum -c`) — it is a CI lint-lane bash step the local gradle gate misses
- [x] 8.3 Confirm no migration was added and no `libs.versions.toml` change; `openspec validate admin-csam-detection-log-viewer --strict` green

## 9. UI DoD + pre-archive smoke (docs/11 §5)

- [ ] 9.1 Manual admin bring-up via verify-loop (local Ktor boot, admin auth + TOTP) — exercise viewer + takedown form + Kominfo write + decrypt; capture screenshots into the PR body BEFORE archive
- [ ] 9.2 Staging branch deploy (`gh workflow run deploy-staging.yml --ref admin-csam-detection-log-viewer`) + admin smoke: unauthenticated `GET /admin/csam` → 302 `/admin/login` (route mounted + gated + app booted)
- [ ] 9.3 PR title/body refreshed at the phase boundary (project.md hard rule)
