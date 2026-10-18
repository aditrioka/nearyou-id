## Why

Two admin trust-boundary gaps from the 2026-10-03 architecture review (BARU-2 / BARU-3, `dev/audits/2026-10-03-architecture-review/REPORT.md` § 3 + § 11), both Bucket 1 (free, staging, before the feature-complete milestone):

- **#531: no security response header anywhere.** `grep DefaultHeaders|Content-Security-Policy|X-Frame-Options|Strict-Transport backend/` → 0, and no edge adds them (`api-staging` CNAMEs straight to Cloud Run). The HTMX admin panel can be framed (clickjacking a `hx-confirm` destructive action), and a single template-escaping slip has no CSP backstop.
- **#532: `POST /admin/login` has no limiter.** `AdminLoginRoutes.kt` never calls a `RateLimiter`, and `DestructiveActionRateLimiter.kt` explicitly leaves login out of its set. The only brake is the ~500 ms / 128 MiB Argon2id verify, so password spraying is also a cheap CPU + memory DoS on the single 2-vCPU instance. `admin-login-argon2-totp` design.md deferred this "once Layer 1 IP-axis rate-limit infrastructure ships". That infrastructure has shipped (`tryAcquireByKey`, `AuthRateLimiter` #214), but the tracker was lost.

Both are admin-only `/admin/*` hardening, and the CSP requires moving the panel's three inline `<script>` blocks, so they ship as one bundle.

## What Changes

- **Security headers on every `/admin/*` response (#531).** A route-scoped Ktor plugin on the `/admin` route adds:
  - `Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'`
  - `X-Frame-Options: DENY`
  - `X-Content-Type-Options: nosniff`
  - `Referrer-Policy: same-origin`
  - `Strict-Transport-Security: max-age=31536000`

  This covers pages, HTMX fragments, redirects and vendored static assets. The `/api/*` surface is unchanged (JSON API, no browser rendering context).
- **Inline scripts → one vendored `admin/static/admin.js` (#531 prerequisite).** PR #585 already removed every inline `style=`. The remaining inline `<script>` blocks move into a same-origin static file loaded by the base layout:
  - the `htmx:configRequest` CSRF hook (`layout.peb`);
  - the password visibility toggle (`login.peb`);
  - the wordlist entry filter (`wordlist-editor-content.peb`). It becomes a delegated listener, so it keeps working across HTMX swaps without re-binding.

  `admin.js` is pinned in `htmx.min.js.SHA256SUMS` (CI integrity check). The layout also sets `<meta name="htmx-config">` with `includeIndicatorStyles:false`, so htmx does not inject a `<style>` element that `style-src 'self'` would block, and `allowEval:false`. No template uses `hx-on`, `js:` values or trigger filters, so nothing needs eval. A source-scan guard fails the build if an admin template regains an inline `<script>` or an `on*=` handler attribute.
- **Tiered login throttle on `POST /admin/login` (#532).** It runs before any `admin_users` lookup or Argon2id verify, through the shared Redis `RateLimiter.tryAcquireByKey`, with sliding windows:
  - **per client IP**: 10 per 15 min, key `{scope:rate_admin_login}:{ip:<IpHasher hash>}`;
  - **per submitted email**: 5 per 15 min, key `{scope:rate_admin_login}:{email:<sha256 base64url>}`;
  - **sustained per-email lockout**: 20 per rolling 24 h, key `{scope:rate_admin_login_lockout}:{email:…}`. This is the "progressive lockout" tier: a burst is throttled for at most 15 minutes, while a sustained attack on one account is locked out for up to a day.

  When throttled, the response is **`429` + `Retry-After`** with the login form re-rendered and a throttle banner. It is identical whether or not the email exists (no-enumeration preserved). The attempt also emits a structured WARN line, `event=admin_login_throttled` (axis, `email_hash`, `ip`, `user_agent`, `retry_after_s`). It writes no `admin_actions_log` row, because no admin actor is identified pre-lookup and V16's `admin_id NOT NULL` forbids one (same rationale as design D14's `email_not_found`).

  Fail-open is consistent with `rate-limit-infrastructure`: a Redis outage admits, via the `RedisRateLimiter` fail-soft path, and so does the dev/test `NoOpRateLimiter` binding. `admin()` takes the limiter as a **required** parameter, so no wiring can silently omit it. `Application.kt` threads the shared `rateLimiter` in.
- **Docs.**
  - `docs/07` § Security Layer 2 gains the throttle numbers, the header set, and a break-glass unlock line.
  - `docs/11` § 3.6 adds "no inline scripts; panel JS lives in the vendored `admin.js`" next to the existing no-inline-styles rule.

No migration. No mobile change. No new secret slot.

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `admin-login`:
  - ADDED: the login POST is throttled per IP, per email, and by the sustained per-email lockout (429 + `Retry-After`, `admin_login_throttled` log, fail-open, no-enumeration).
  - MODIFIED: the login page's toggle script is now a vendored static script, not inline JS.
  - MODIFIED: the authenticated CSRF hook lives in the vendored `admin.js`, registered only when the CSRF meta tag is present.
- `admin-panel-scaffold`:
  - ADDED: every `/admin/*` response carries the security header set, and the panel runs under CSP `'self'`.
  - MODIFIED: the base layout loads the vendored `admin.js` and the htmx CSP config meta instead of an inline `<script>` block.

## Impact

- **Code:**
  - new `backend/ktor/.../admin/AdminSecurityHeaders.kt`;
  - `admin/AdminModule.kt`: install the headers plugin, plus a new required `loginRateLimiter` parameter;
  - `admin/auth/AdminLoginRoutes.kt`: the throttle;
  - `admin/auth/AdminAuditLogger.kt`: `logLoginThrottled`;
  - `Application.kt`: pass the shared `rateLimiter`;
  - templates `layout.peb`, `login.peb`, `wordlist-editor-content.peb`;
  - new `admin/static/admin.js` plus a `htmx.min.js.SHA256SUMS` entry.
- **Tests:**
  - new `AdminSecurityHeadersTest` covering the header set on the login page, an authenticated page, an HTMX fragment, the unauthenticated redirect and a static asset;
  - `AdminLoginRouteTest` throttle scenarios: the IP axis, the email axis across IPs, the lockout tier, an identical 429 for existing vs phantom emails, `Retry-After`, the log line, no audit row, fail-open, and no Argon2/DB work once throttled;
  - existing inline-script assertions retargeted to the served `admin.js`;
  - an `AdminTemplateStyleTest` no-inline-script guard;
  - the other direct `admin()` caller (`AdminCsrfMiddlewareTest`) and `withAdminApp` gain the limiter argument.
- **Coordination:** `AdminModule.kt` and `AdminAuditLogger.kt` are shared with the in-flight #277 and #496 sessions, and `Application.kt` with #266. The diff stays additive and local (one parameter, one install line, one logger method) so whoever merges second can rebase cleanly.
- **Cross-layer (docs/12):** admin + backend only. The headers and throttle are transport/auth hardening with no wire-contract change. The mobile app never calls `/admin/*`.
- **Residual:** a per-email limiter lets someone who knows the admin's email keep that account throttled (true of any per-account limiter). This is bounded by the IAP perimeter (BARU-1, Bucket 2) and the break-glass Redis `DEL` documented in docs/07. See design.md.
