## Why

Two admin trust-boundary gaps from the 2026-10-03 architecture review (BARU-2 / BARU-3, `dev/audits/2026-10-03-architecture-review/REPORT.md` § 3 + § 11), both Bucket 1 (free, staging, before the feature-complete milestone):

- **#531: no security response header anywhere.** `grep DefaultHeaders|Content-Security-Policy|X-Frame-Options|Strict-Transport backend/` → 0, and no edge adds them (`api-staging` points straight at Cloud Run). The HTMX admin panel can be framed (clickjacking a `hx-confirm` destructive action), and a single template-escaping slip has no CSP backstop.
- **#532: `POST /admin/login` has no limiter.** `AdminLoginRoutes.kt` never calls a `RateLimiter`, and `DestructiveActionRateLimiter.kt` explicitly leaves login out of its set. The only brake is the Argon2id verify (19 MiB, t=8 — `PasswordHasher`; the earlier 128 MiB tuning OOM'd staging). That makes online guessing cheap. It also makes a concurrent spray a memory/CPU DoS: `--memory=512Mi` leaves a ~128 MiB heap, so ~6 parallel verifies exhaust it at `--concurrency=80`. `admin-login-argon2-totp` design.md deferred the throttle "once Layer 1 IP-axis rate-limit infrastructure ships". That infrastructure has shipped (`tryAcquireByKey`, `AuthRateLimiter` #214), but the tracker was lost.

Both are admin hardening, and the CSP requires moving the panel's three inline `<script>` blocks, so they ship as one bundle.

## What Changes

- **Security headers on every response (#531).** A Ktor application plugin attaches the set at `CallSetup` (before any other plugin can reject the call, e.g. the 413 body limit):
  - `Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'`
  - `X-Frame-Options: DENY`
  - `X-Content-Type-Options: nosniff`
  - `Referrer-Policy: same-origin`
  - `Strict-Transport-Security: max-age=31536000`

  It is host-wide, not keyed on an `/admin` path prefix: the router decodes and collapses path segments, so `//admin/users` and `/%61dmin/login` reach the admin handlers while a raw-path check misses them. The headers are inert on the JSON API (the backend serves no other HTML), and `nosniff` + HSTS help it. This matches the issue's own `DefaultHeaders` suggestion, without Ktor's `DefaultHeaders` plugin, whose `Date` / `Server` headers are not security headers.
- **Inline scripts → one vendored `admin/static/admin.js` (#531 prerequisite).** PR #585 already removed every inline `style=`. The remaining inline `<script>` blocks move into a same-origin static file loaded by the base layout:
  - the `htmx:configRequest` CSRF hook (`layout.peb`);
  - the password visibility toggle (`login.peb`);
  - the wordlist entry filter (`wordlist-editor-content.peb`). It becomes a delegated listener, so it keeps working across HTMX swaps without re-binding.

  `admin.js` is pinned in `htmx.min.js.SHA256SUMS` (CI integrity check). The layout's `<meta name="htmx-config">` turns off, in order:
  - the injected indicator `<style>`, which `style-src 'self'` would block;
  - eval, through `allowEval: false`;
  - re-execution of scripts found in swapped content;
  - the `localStorage` page-snapshot cache. Up to 10 admin pages of user PII and report data would otherwise outlive logout, so a back/forward miss does a full reload instead.

  A source-scan guard fails the build if an admin template regains an inline `<script>`, an `on*=` handler or an eval-dependent htmx feature.
- **Login throttle on `POST /admin/login` (#532).** Before any `admin_users` lookup or Argon2id verify:
  - **Concurrency gate.** At most 2 credential checks run at once per instance. A request that cannot get a permit within 2 s is shed (429) before any Redis, DB or hash work. This is the bound that holds even though the IP tier is spoofable on this host.
  - **Three sliding-window tiers** through the shared Redis `RateLimiter.tryAcquireByKey`:
    - per client IP, 10 per 15 min: `{scope:rate_admin_login}:{ip:<IpHasher hash>}`;
    - per submitted email, 5 per 15 min: `{scope:rate_admin_login}:{email:<sha256 base64url>}`;
    - sustained per-email lockout, 20 per rolling 24 h: `{scope:rate_admin_login_lockout}:{email:…}`. This is the "progressive lockout" of #532's title and the review's "lockout bertahap". A burst recovers in 15 minutes; a sustained attack on one account stays locked for up to a day.
  - **Counting.** Only failed attempts count: a successful login hands its slot back in every tier. So the operator's logins and the staging smoke scripts never burn budget, and the counters reveal no legitimate activity.
  - **Throttled response.** `429` + `Retry-After` with the login form re-rendered and a throttle banner, identical whether or not the email exists (no-enumeration preserved). It also emits a structured WARN line, `event=admin_login_throttled` (axis, `email_hash`, quoted+escaped `ip` / `user_agent`, `retry_after_s`). It writes no `admin_actions_log` row: no admin actor is identified pre-lookup, and V16's `admin_id NOT NULL` forbids one (design D14's `email_not_found` rationale).
  - **Fail-open**, consistent with `rate-limit-infrastructure`: a Redis outage admits via the `RedisRateLimiter` fail-soft path, and so does the dev/test `NoOpRateLimiter` binding. The concurrency gate is in-process and stays in force.
  - **Wiring.** `admin()` takes the limiter as a **required** parameter, so no wiring can silently omit it. `Application.kt` threads the shared `rateLimiter` in.
- **Docs.**
  - `docs/05` § Rate Limiting Layer 1 lists the three admin keys.
  - `docs/07` § Security Layer 2 gains the throttle, the header set and a break-glass procedure.
  - `docs/11` § 3.6 adds "no inline scripts or eval-dependent htmx; panel JS lives in the vendored `admin.js`" next to the no-inline-styles rule.

No migration. No mobile change. No new secret slot.

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `admin-login`:
  - ADDED: the login POST is throttled: concurrency gate, per IP, per email, sustained per-email lockout, failed attempts only. It answers 429 + `Retry-After`, emits an `admin_login_throttled` WARN line, fails open and preserves no-enumeration.
  - MODIFIED: the login page's toggle is wired by the vendored `admin.js`, not inline JS.
  - MODIFIED: the authenticated CSRF hook lives in `admin.js`, registered only when the CSRF meta tag is present.
- `admin-panel-scaffold`:
  - ADDED: every response carries the security header set, and the panel runs under CSP `'self'`.
  - MODIFIED: the base layout loads `admin.js` and the htmx CSP/privacy config meta instead of an inline `<script>` block.

## Impact

- **Code:**
  - new `backend/ktor/.../admin/AdminSecurityHeaders.kt`;
  - `admin/AdminModule.kt`: install the headers plugin; a new required `loginRateLimiter` parameter; a defaulted `loginCredentialGate` test seam;
  - `admin/auth/AdminLoginRoutes.kt`: the gate and the throttle;
  - `admin/auth/AdminAuditLogger.kt`: `logLoginThrottled`, plus quoted/escaped client fields in the existing email-not-found line;
  - `Application.kt`: pass the shared `rateLimiter`;
  - templates `layout.peb`, `login.peb`, `wordlist-editor-content.peb`;
  - new `admin/static/admin.js` plus a SHA256SUMS line.
- **Tests:**
  - new `AdminSecurityHeadersTest` and `AdminLoginThrottleTest`;
  - retargeted inline-script assertions in `AdminLoginRouteTest` and `AdminWordlistEditorRouteTest`;
  - an extended `AdminTemplateStyleTest` guard;
  - the limiter argument in `withAdminApp` and `AdminCsrfMiddlewareTest`.
- **Coordination:** `AdminModule.kt` and `AdminAuditLogger.kt` are shared with the in-flight #277 and #496 sessions (PRs #601 / #599), and `Application.kt` with #266. Each touch is additive and local, so whoever merges second can rebase cleanly.
- **Cross-layer (docs/12):** admin + backend only. The headers and throttle are transport/auth hardening with no wire-contract change for any client. The mobile app never calls `/admin/*`, and the API's new headers are inert for it.
- **Residuals** (design § Risks):
  - The per-IP tier is spoofable until the host sits behind Cloudflare with an origin check (#530 / BARU-1a). The email tiers and the concurrency gate do not depend on it.
  - Someone who knows the admin's email can keep that account throttled. IAP (BARU-1, Bucket 2) is the real fix; docs/07 gets a break-glass.
  - A flood still spends Upstash Free commands.
