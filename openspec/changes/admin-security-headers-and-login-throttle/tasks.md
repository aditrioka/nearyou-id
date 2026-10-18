## 1. Security headers (#531, design D1–D2)

- [x] 1.1 New `backend/ktor/.../admin/AdminSecurityHeaders.kt`: `createApplicationPlugin("AdminSecurityHeaders")` appending the five-header set in `on(CallSetup)` on every response, so early rejections are covered and there is no path-prefix check the router could bypass. Values live in one `ADMIN_SECURITY_HEADERS` map that the test asserts.
- [x] 1.2 `admin/AdminModule.kt`: `install(AdminSecurityHeaders)` next to `install(Pebble)`, plus a line in the route-layout comment.

## 2. Inline scripts → vendored `admin.js` (#531 prerequisite, design D3–D4)

- [x] 2.1 New `backend/ktor/src/main/resources/admin/static/admin.js`, with three self-guarding hooks:
  - the CSRF `htmx:configRequest` listener, registered only when `meta[name="csrf-token"]` exists and reading the meta at request time;
  - the `#pw-toggle` password toggle;
  - the delegated `#wl-content .wl-filter` input filter.
- [x] 2.2 `templates/admin/layout.peb`:
  - add `<meta name="htmx-config" content='{"includeIndicatorStyles":false,"allowEval":false,"allowScriptTags":false,"historyCacheSize":0,"refreshOnHistoryMiss":true}'>`;
  - add `<script src="/admin/static/admin.js" defer>` after the htmx script;
  - remove the inline CSRF `<script>` block.
- [x] 2.3 `templates/admin/login.peb`: remove the inline toggle `<script>`, keeping the markup unchanged.
- [x] 2.4 `templates/admin/wordlist-editor-content.peb`: remove the inline filter `<script>`, keeping the markup unchanged.
- [x] 2.5 `admin/static/htmx.min.js.SHA256SUMS`: add the `admin.js` line and re-verify every line plus the directory listing (the CI step's logic). Re-pin after any later `admin.js` edit.

## 3. Login throttle (#532, design D5–D11)

- [x] 3.1 `admin/auth/AdminLoginRoutes.kt`, constructor and constants:
  - new constructor parameters `rateLimiter: RateLimiter` and `credentialGate: Semaphore = Semaphore(CREDENTIAL_CHECK_PERMITS)`;
  - companion constants for the tier caps and windows, the gate (2 permits, 2 s wait, 60 s shed `Retry-After`), the axes and `throttledMessage(minutes)`.
- [x] 3.2 `handlePost` gate: after the oversized-field guard, take a gate permit with `withTimeoutOrNull`; if none comes, shed (429, `axis=concurrency`). Otherwise run `checkCredentials` and release in `finally`.
- [x] 3.3 `checkCredentials` throttle:
  - before `findActiveByEmail`, evaluate IP → email burst → email lockout (`throttleTiers` + `firstExhausted`, stopping at the first exhausted tier);
  - on throttle, `respondThrottled`: `logLoginThrottled`, `Retry-After`, `429` + `login.peb` with the throttle message;
  - no Argon2, no DB, no session.
- [x] 3.4 Success path: after `logSuccess`, `releaseMostRecent(admin.id, key)` for each tier key, so only failed attempts count.
- [x] 3.5 `admin/auth/AdminAuditLogger.kt`:
  - `logLoginThrottled(axis, submittedEmail, ip, userAgent, retryAfterSeconds)` at WARN, placed after `logEmailNotFoundAttempt`;
  - new `logQuoted` (quote + escape `"`, `\`, CR, LF) applied to the client-controlled `ip` / `user_agent` in both lines (found-while: the D14 line had the same field-forgery shape).
- [x] 3.6 `admin/AdminModule.kt`: a new required `loginRateLimiter: RateLimiter` (no default) and a defaulted `loginCredentialGate: Semaphore` test seam, both passed to `AdminLoginRoutes`.
- [x] 3.7 `Application.kt`: pass `loginRateLimiter = rateLimiter` (the shared env-aware limiter) to `admin(...)`.

## 4. Tests (one per spec'd scenario)

- [x] 4.1 Test harnesses:
  - `AdminAuthTestSupport.withAdminApp`: new `loginRateLimiter: RateLimiter = NoOpRateLimiter()` and `loginCredentialGate: Semaphore? = null` parameters, threaded into both `admin(...)` calls;
  - `AdminCsrfMiddlewareTest`'s direct `admin(...)` passes `NoOpRateLimiter()`;
  - shared `INLINE_SCRIPT_TAG` regex and `ADMIN_JS_TAG` constants.
- [x] 4.2 New `AdminSecurityHeadersTest`:
  - the exact header map;
  - the five headers on `GET /admin/login`, `GET /admin/` (authenticated) and an `HX-Request` fragment;
  - the unauthenticated `/admin/users` 302 and bare `/admin` 302;
  - `/admin/static/admin.js` 200 and `/admin/nonexistent-page` 404;
  - `//admin/users` 302 and `/%61dmin/login` 200;
  - non-admin paths;
  - a RequestBodyLimit 413 (`productionBodyLimit = true`);
  - `admin.js` content type plus its SHA-256 = the SHA256SUMS pin;
  - every page loads `admin.js` after htmx, with the exact htmx-config meta and no inline script.
- [x] 4.3 `AdminTemplateStyleTest`: no template contains a `<script` without `src=`, an `on<event>=` attribute, `hx-on`, `hx-vars`, `js:` / `javascript:` or an `hx-trigger` `[filter]`.
- [x] 4.4 `AdminLoginRouteTest`, retargeted assertions:
  - the toggle-wiring and CSRF-hook assertions move from the page body to the served `admin.js` (`input.type === 'password'`, `evt.detail.headers['X-CSRF-Token']`, the meta-tag guard);
  - the page bodies assert `ADMIN_JS_TAG` and no inline `<script>`;
  - the login body still has no `htmx:configRequest`.
- [x] 4.5 `AdminWordlistEditorRouteTest`: keep `wl-filter` markup presence and add "no inline `<script>` in the page".
- [x] 4.6 New `AdminLoginThrottleTest`, one test per admin-login "Login POST is throttled…" scenario:
  - IP tier: 11th → 429, `Retry-After` 1..900, the exact message + form;
  - email burst across IPs: 6th → 429;
  - lockout: 4 × 5 failures 16 min apart (injected clock), then 21st → 429, `Retry-After` > 900, `axis=email_lockout`;
  - correct credentials while throttled → 429, no cookie, session or audit row;
  - existing vs phantom → byte-identical 429 body and identical header names;
  - WARN line fields, quoted `ip` / `user_agent`, no plaintext email, password or TOTP, no audit row;
  - field-forgery escaping;
  - success hands back every tier's slot (6 successes, empty buckets);
  - a held gate → shed 429 (`Retry-After: 60`, `axis=concurrency`) with no limiter call;
  - the gate leaks no permit across success, failure, phantom, 400 and 429;
  - a recording limiter sees the exact (key, cap, window) triples in order, with no raw IP or email;
  - an IP-throttled attempt spends no email slot;
  - fail-open: NoOp, 12 attempts, no 429;
  - oversized → 400 with no limiter call.

## 5. Docs

- [x] 5.1 `docs/05-Implementation.md` § Rate Limiting Layer 1: an "Admin login throttle — SHIPPED" block after the auth-endpoint table, covering the three keys, caps and windows, failed-only counting, the gate, and the spoofable-IP note. No section renumbering.
- [x] 5.2 `docs/07-Operations.md` § Security (Defense in Depth), Layer 2:
  - a login-throttle bullet (tiers, gate, 429 + `Retry-After`, `event=admin_login_throttled`, fail-open);
  - the break-glass (Upstash `DEL` with the portable hash one-liner; the unraceable fallback of rotating `admin_users.email`);
  - a smoke-script note (successes are free, failures count);
  - a security-header-set bullet.
- [x] 5.3 `docs/11-Engineering-Standards.md` § 3.6: "no inline scripts, `on*=` handlers or eval-dependent htmx (`hx-on`, `js:`, trigger filters) either — panel JS lives in the vendored `admin/static/admin.js` (pinned in SHA256SUMS), so the panel runs under CSP `'self'`", beside the no-inline-styles rule (Pattern Registry: one place for panel JS).

## 6. Verification & lifecycle

- [ ] 6.1 Run `/opsx:preflight` and record the result in the PR body.
- [ ] 6.2 Gate (docs/13): `./gradlew ktlintCheck detekt :backend:ktor:test --rerun :lint:detekt-rules:test --rerun` on a throwaway Postgres + Redis, and record the counts.
- [ ] 6.3 Local verify (verify-loop §A, dist boot, `KTOR_ENV=test`, real Redis):
  - a HEAD request shows the header set on `/admin/login`, `/admin/static/admin.js`, the `/admin/users` 302 and an `/api` path;
  - 11 phantom-email POSTs → the 11th gets 429 + `Retry-After`;
  - browser bring-up with screenshots: the login page (toggle works), an authenticated page (HTMX filter/fragment swap with the CSRF header on an HTMX POST, wordlist filter works), the throttle banner (it reuses the frame-1 error banner), and a console with zero CSP violations.
- [ ] 6.4 Staging branch deploy + smoke (DoD #4), with operator go-ahead, since parallel sessions share staging:
  - a HEAD request to the staging `/admin/login` shows the header set;
  - 11 bad POSTs with a **phantom** email → 429 + `Retry-After`;
  - Cloud Logging shows `event=admin_login_throttled`;
  - `smoke-admin-login-argon2-totp.sh` still passes.
- [ ] 6.5 Keep the PR title/body current at each phase boundary. The body carries `Closes #531` and `Closes #532` on separate lines, plus the `Review findings applied` line.
- [ ] 6.6 Archive via `/opsx:archive`.
