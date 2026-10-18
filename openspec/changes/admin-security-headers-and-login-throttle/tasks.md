## 1. Admin security headers (#531, design D1–D2)

- [ ] 1.1 New `backend/ktor/.../admin/AdminSecurityHeaders.kt`: `createApplicationPlugin("AdminSecurityHeaders")` whose `onCall` appends the five-header set when the request path is `/admin` or starts with `/admin/`. Values are exposed as one `ADMIN_SECURITY_HEADERS` map so the test asserts the same source of truth.
- [ ] 1.2 `admin/AdminModule.kt`: `install(AdminSecurityHeaders)` next to `install(Pebble)`, plus a KDoc line in the route-layout comment.

## 2. Inline scripts → vendored `admin.js` (#531 prerequisite, design D3–D4)

- [ ] 2.1 New `backend/ktor/src/main/resources/admin/static/admin.js`, with three self-guarding hooks:
  - the CSRF `htmx:configRequest` listener, registered only when `meta[name="csrf-token"]` exists and reading the meta at request time;
  - the `#pw-toggle` password toggle;
  - the delegated `#wl-content .wl-filter` input filter.
- [ ] 2.2 `templates/admin/layout.peb`: add `<meta name="htmx-config" content='{"includeIndicatorStyles":false,"allowEval":false}'>` and `<script src="/admin/static/admin.js" defer>` after the htmx script; remove the inline CSRF `<script>` block. The CSRF meta tag stays conditional.
- [ ] 2.3 `templates/admin/login.peb`: remove the inline toggle `<script>`, keeping the markup unchanged.
- [ ] 2.4 `templates/admin/wordlist-editor-content.peb`: remove the inline filter `<script>`, keeping the markup unchanged.
- [ ] 2.5 `admin/static/htmx.min.js.SHA256SUMS`: add the `admin.js` line and re-verify every existing line (the CI integrity step's logic).

## 3. Login throttle (#532, design D5–D10)

- [ ] 3.1 `admin/auth/AdminLoginRoutes.kt`, constructor and constants:
  - new constructor parameter `rateLimiter: RateLimiter`;
  - companion constants for the three tiers' caps, windows and key builders (`{scope:rate_admin_login}:{ip:…}`, `{scope:rate_admin_login}:{email:…}`, `{scope:rate_admin_login_lockout}:{email:…}`);
  - the throttle message.
- [ ] 3.2 `AdminLoginRoutes.handlePost`, throttle behaviour:
  - after the oversized-field guard and before `findActiveByEmail`, evaluate IP → email burst → email lockout, stopping at the first exhausted tier;
  - on throttle, call `logLoginThrottled`, set `Retry-After`, and respond `429` with `login.peb` and the throttle message;
  - no Argon2, no DB, no session.
- [ ] 3.3 `admin/auth/AdminAuditLogger.kt`: `logLoginThrottled(axis, submittedEmail, ip, userAgent, retryAfterSeconds)` emitting `event=admin_login_throttled axis=… email_hash=… ip=… user_agent="…" retry_after_s=…` at WARN, placed right after `logEmailNotFoundAttempt`.
- [ ] 3.4 `admin/AdminModule.kt`: a new required `loginRateLimiter: RateLimiter` parameter (no default), passed to `AdminLoginRoutes`.
- [ ] 3.5 `Application.kt`: pass `loginRateLimiter = rateLimiter` (the shared env-aware limiter) to `admin(...)`.

## 4. Tests (one per spec'd scenario)

- [ ] 4.1 `AdminAuthTestSupport.withAdminApp`: new `loginRateLimiter: RateLimiter = NoOpRateLimiter()` parameter threaded into both `admin(...)` calls. `AdminCsrfMiddlewareTest`'s direct `admin(...)` call passes `NoOpRateLimiter()`.
- [ ] 4.2 New `AdminSecurityHeadersTest` covering admin-panel-scaffold "Admin responses carry security response headers":
  - the exact five headers on `GET /admin/login`, `GET /admin/` (authenticated), an HTMX fragment (`HX-Request: true` on an admin list route), the unauthenticated `GET /admin/users` 302, `GET /admin/static/admin.js` 200 and `GET /admin/nonexistent-page` 404;
  - no admin CSP on `GET /administrator`, or on a non-admin route mounted beside the admin module;
  - `admin.js` served with a JS content type and its SHA-256 matching the SHA256SUMS line.
- [ ] 4.3 `AdminTemplateStyleTest`: a new assertion that no `templates/admin/*.peb` contains a `<script` without `src=` or an inline `on<event>=` attribute.
- [ ] 4.4 `AdminLoginRouteTest`, retargeted assertions:
  - the toggle-wiring and CSRF-hook assertions move from the page body to the served `/admin/static/admin.js` (`input.type === 'password'`, `evt.detail.headers['X-CSRF-Token']`, the meta-tag guard);
  - the page bodies assert `<script src="/admin/static/admin.js"`, the `htmx-config` meta, and no inline `<script>`;
  - the login body still has no `htmx:configRequest`.
- [ ] 4.5 `AdminWordlistEditorRouteTest`: keep `wl-filter` markup presence and add "no inline `<script>` in the fragment".
- [ ] 4.6 New `AdminLoginThrottleTest` (DB-backed, `InMemoryRateLimiter` with an injectable clock), one test per admin-login "Login POST is throttled…" scenario:
  - IP tier: 11th → 429, `Retry-After` in 1..900, throttle message + form in the body;
  - email burst across IPs: 6th → 429;
  - lockout tier: 4 × 5 attempts spaced 16 min apart, then the 21st → 429 with `Retry-After` > 900 and `axis=email_lockout`;
  - correct credentials while throttled → 429, no cookie, no session row, no audit row;
  - existing vs phantom email → byte-identical 429 bodies and the same header names;
  - WARN line fields present, with no plaintext email, password or TOTP; no audit row;
  - a recording limiter captures the exact three keys in order, with no raw IP or email;
  - an IP-throttled attempt leaves the email tiers unconsulted, and another IP is then admitted;
  - fail-open: `NoOpRateLimiter`, 12 attempts, no 429, generic 200 each;
  - an oversized email gets 400 and consumes no slot.

## 5. Docs

- [ ] 5.1 `docs/07-Operations.md` § Security (Defense in Depth), Layer 2:
  - a bullet for the login throttle (the three tiers, 429 + `Retry-After`, the `event=admin_login_throttled` WARN line, fail-open);
  - a break-glass unlock (Upstash `DEL` of the two `{email:<hash>}` keys plus the hash one-liner);
  - a bullet for the `/admin/*` security header set.
- [ ] 5.2 `docs/11-Engineering-Standards.md` § 3.6: "no inline scripts or `on*=` handlers either — panel JS lives in the vendored `admin/static/admin.js` (pinned in SHA256SUMS), so the panel runs under CSP `'self'`" beside the no-inline-styles rule (Pattern Registry: one place for panel JS).

## 6. Verification & lifecycle

- [ ] 6.1 Run `/opsx:preflight` and record the result in the PR body.
- [ ] 6.2 Gate (docs/13): `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` on a throwaway Postgres + Redis (`--rerun`), and record the counts.
- [ ] 6.3 Local verify (verify-loop §A, dist boot, `KTOR_ENV=test`, real Redis):
  - a HEAD request shows the header set on `/admin/login`, `/admin/static/admin.js` and the `/admin/users` 302;
  - an `/api` path lacks the admin CSP;
  - login throttle: the 11th POST → 429 + `Retry-After`;
  - browser bring-up with screenshots of the login page, an authenticated page (password toggle works, HTMX filter/fragment swap works with the CSRF header attached on an HTMX POST, wordlist filter works) and a zero-CSP-violation console;
  - check the board frame 1 throttle banner (it reuses the error banner).
- [ ] 6.4 Staging branch deploy + smoke (DoD #4), with operator go-ahead, since parallel sessions share staging:
  - a HEAD request to the staging `/admin/login` shows the header set;
  - 11 bad POSTs → 429 + `Retry-After`;
  - Cloud Logging shows `event=admin_login_throttled`.
- [ ] 6.5 Keep the PR title/body current at each phase boundary. The body carries `Closes #531` and `Closes #532` on separate lines, plus the `Review findings applied` line.
- [ ] 6.6 Archive via `/opsx:archive`.
