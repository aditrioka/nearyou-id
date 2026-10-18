## Context

The admin panel (`/admin/*`, Pebble + HTMX + vendored CSS, docs/11 § 3.6) runs inside the `:backend:ktor` Cloud Run service on `api-staging.nearyou.id`. That host CNAMEs straight to Cloud Run: no Cloudflare proxy, no IAP yet (BARU-1 is Bucket 2). Nothing sets a security response header, and `POST /admin/login` has no rate limiter.

Existing pieces this change reuses:

- **`RateLimiter.tryAcquireByKey`** (`:core:domain`): the axis-agnostic sliding-window entry point. Its Redis implementation (`RedisRateLimiter`) is fail-soft (admits on a Redis error), and the dev/test binding without `redis-url` is `NoOpRateLimiter`. `AuthRateLimiter` (#214) is the precedent for unauthenticated, IP-keyed throttles on `/signin` / `/signup` / `/refresh`, using a 429 + `Retry-After` shape.
- **`AdminAuditLogger.logEmailNotFoundAttempt`** (design D14 of `admin-login-argon2-totp`): a structured INFO line for login attempts with no admin actor, because V16's `admin_actions_log.admin_id NOT NULL` forbids an unowned row. Its `email_hash` is SHA-256 base64url of the submitted email.
- **PR #585**: removed every inline `style=` attribute from the admin templates and added `AdminTemplateStyleTest` (a source scan), so `style-src 'self'` is already safe for markup.

What still blocks a strict CSP is three inline `<script>` blocks:
- `layout.peb`: the CSRF `htmx:configRequest` hook;
- `login.peb`: the password toggle;
- `wordlist-editor-content.peb`: the entry filter.

There is also one htmx default, `includeIndicatorStyles: true`, under which htmx injects a `<style>` element at init.

## Goals / Non-Goals

**Goals:**
- Every `/admin/*` response carries a strict CSP (`'self'`, no `'unsafe-inline'`), anti-framing, `nosniff`, `Referrer-Policy` and HSTS, and the panel still works under it.
- `POST /admin/login` is throttled per client IP and per submitted email, plus a sustained per-email lockout tier, before any Argon2id or DB work. The throttle answers 429 + `Retry-After`, emits a structured `admin_login_throttled` signal, never leaks account existence, and fails open on a Redis outage.

**Non-Goals:**
- Headers on the `/api/*` JSON surface (no browser rendering context; a separate decision if wanted).
- `Cache-Control: no-store`, `Permissions-Policy` and COOP/COEP on admin pages. They are cheap but not in the review finding, so they are left for whenever the IAP / separate-service move (BARU-1) revisits the admin edge.
- CSP violation reporting (`report-to`). There is no collector, and adding one is a new endpoint and contract.
- Throttling admin actions other than login (already covered by the per-action limiters).

## Decisions

### D1 — One application plugin keyed on the `/admin` path prefix, not `DefaultHeaders` or a route-scoped plugin

`AdminSecurityHeaders` is a `createApplicationPlugin` whose `onCall` appends the header set when the request path is `/admin` or starts with `/admin/`. `admin()` installs it.

- **Not `DefaultHeaders`**: it is application-wide and would stamp the admin CSP onto every JSON API response.
- **Not a route-scoped plugin on `route("/admin")`**: it would miss responses that resolve no route, i.e. the `/admin/nonexistent` 404 and the 405s on unmapped verbs.

The path check gives "every `/admin/*` response" literally. That includes pages, HTMX fragments, the unauthenticated 302 to `/admin/login`, the throttle 429, and the vendored static assets. A header on a JS/CSS/font response is harmless, and `nosniff` is what makes the browser enforce the `text/javascript` / `text/css` types Ktor's `staticResources` already sends. HSTS is host-wide by browser semantics anyway, so sending it only from `/admin/*` still covers the host. Browsers ignore HSTS over plain HTTP, so local `http://localhost` dev is unaffected.

### D2 — Header values

- **`Content-Security-Policy`**: `default-src 'self'; script-src 'self'; style-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'`. The review's four directives, plus three that do **not** fall back to `default-src`:
  - `base-uri` stops an injected `<base>` from re-pointing relative URLs;
  - `form-action` stops an injected `<form>` from posting a stolen CSRF token off-origin;
  - `object-src 'none'` is stricter than the inherited `'self'`.

  No nonces or hashes, because no inline script or style remains (D3/D4). The forms post same-origin, including the login 303 → `/admin/` redirect that `form-action` also governs, so `'self'` holds.
- **`X-Frame-Options: DENY`**: the legacy twin of `frame-ancestors 'none'`.
- **`X-Content-Type-Options: nosniff`**.
- **`Referrer-Policy: same-origin`**: admin URLs carry user UUIDs and search terms, which must not leave in a `Referer`.
- **`Strict-Transport-Security: max-age=31536000`**: one year. No `includeSubDomains` (the host has none) and no `preload` (a list commitment for the apex domain, not this host).

### D3 — One vendored `admin.js`, every hook self-guarding

The three inline scripts move verbatim, in behaviour, into `admin/static/admin.js`. The base layout loads it after htmx, as `<script src="/admin/static/admin.js" defer>`, on every admin page, login included. Each hook is a no-op where its markup is absent:

- **CSRF hook**: the `htmx:configRequest` listener is registered only if `meta[name="csrf-token"]` exists when the deferred script runs. The meta tag renders only on authenticated pages, so the unauthenticated login page still registers no listener, as the existing spec requires. The listener still reads the meta at request time, so a per-login rotation propagates without a reload.
- **Password toggle**: binds to `#pw-toggle` and `#password` if present.
- **Wordlist filter**: one delegated `input` listener on `document`, matching `#wl-content .wl-filter`. The inline version re-ran after each HTMX swap of the fragment. A delegated listener needs no re-binding, and htmx no longer has to execute script tags inside swapped fragments.

The toggle and filter write `element.style.display` through the CSSOM. CSP `style-src` governs `style` attributes in markup, `setAttribute('style', …)` and `<style>` elements, not CSSOM property writes. So the existing behaviour is kept byte-for-byte, with no new CSS classes.

**One file, not one per page.** It is three small hooks, and splitting them buys nothing but more `<script>` tags and SHA256SUMS lines (rule of three: extract when a third panel script appears).

**Integrity**: `admin.js` gets a line in `admin/static/htmx.min.js.SHA256SUMS`. The CI step already requires every file in the directory to be listed, so an unpinned edit fails CI.

**Guard**: `AdminTemplateStyleTest` gains a source-scan assertion. An admin template may not contain a `<script>` without `src=`, or an `on<event>=` attribute. This mirrors the #585 no-inline-style guard, so the CSP cannot silently regress at the template level.

### D4 — htmx under CSP: `<meta name="htmx-config" content='{"includeIndicatorStyles":false,"allowEval":false}'>`

htmx 2.0.4 inserts a `<style>` block for `.htmx-indicator` at init. Under `style-src 'self'` that is a console CSP violation. No template uses `htmx-indicator` / `hx-indicator`, so turning the injection off loses nothing. `allowEval:false` makes htmx refuse `hx-on`, `js:`-prefixed `hx-vals` and trigger filters instead of hitting the CSP. None is used today: the templates use only `hx-get`, `hx-post`, `hx-target`, `hx-swap`, `hx-push-url` and `hx-confirm`. A future template that reaches for one will fail visibly in development, not silently in production. The meta is in `<head>` before the deferred htmx script, so htmx reads it at init.

### D5 — Throttle placement and order

The throttle runs in `handlePost` **after** form parsing and the existing oversized-field 400 guard (it needs the parsed email), and **before** `findActiveByEmail` or any Argon2id / TOTP work:

1. **IP axis**: `{scope:rate_admin_login}:{ip:<IpHasher.hash(clientIp)>}`, 10 / 15 min.
2. **Email burst**: `{scope:rate_admin_login}:{email:<sha256 base64url of the submitted email>}`, 5 / 15 min.
3. **Email lockout**: `{scope:rate_admin_login_lockout}:{email:<same hash>}`, 20 / rolling 24 h.

The first exhausted tier answers. Later tiers are not consulted, so an IP-throttled flood does not spend the target email's slots, and the lockout tier counts only attempts the burst tier admitted.

Every attempt that reaches the tier counts, including a successful login. The shared limiter has no by-key release (`releaseMostRecent` is user-axis only), and an admin logging in more than 5 times in 15 minutes is not a real workflow.

All three keys are rolling sliding windows. None ends in `_day`, so they take sliding (not fixed-window) semantics, and `RateLimitTtlRule` correctly does not demand `computeTTLToNextReset`, which is the per-user WIB-staggered daily-quota reset and has nothing to stagger here. The keys use the two-segment hash-tag shape (`RedisHashTagRule`), and the IP value is the `IpHasher` hash, never the raw IP.

### D6 — The numbers, and why a lockout tier

- **IP 10 / 15 min**: admins are few and log in rarely, so 10 per 15 minutes is generous for a shared office or CGNAT exit and still caps one source at 960 Argon2id verifies a day (vs unbounded). The Argon2 cost of a spray is what makes this a DoS concern on a 2-vCPU, 512 MiB instance.
- **Email 5 / 15 min**: the per-account bound that survives IP rotation.
- **Email lockout 20 / 24 h**: the "progressive lockout" of #532's title and the review's "lockout bertahap". The arithmetic, given a phished or leaked password where only TOTP stands:
  - each attempt has about a 3 × 10⁻⁶ chance (±1 step skew = 3 valid codes of 10⁶);
  - the burst tier alone still allows 480 attempts a day ≈ 175 k a year, about a 41 % yearly chance of a hit;
  - the lockout tier caps it at 20 a day ≈ 7.3 k a year, about 2 %.

  Short bursts recover in 15 minutes. A sustained attack on one account stays locked for up to a day.

Constants live in `AdminLoginRoutes`' companion. Tests use them as-is, which is affordable because each attempt in a test is one Argon2 verify.

### D7 — Email key = hash of the exact submitted string, no normalisation

`findActiveByEmail` is an exact `email = ?` match, so only the exact stored string can ever authenticate. Attempts with case or whitespace variants can never succeed against the real account, so they cannot advance an attack on it, and bucketing the exact string bounds every useful attempt. The key value reuses `AdminAuditLogger.sha256Base64Url`, the same `email_hash` the D14 log line carries, so a throttle line and an email-not-found line for the same submission correlate. Base64url (`[A-Za-z0-9_-]`) is safe inside a hash-tag segment.

### D8 — Throttle response and no-enumeration

A throttled attempt gets:
- **`429 Too Many Requests`**;
- **`Retry-After: <seconds>`**, from the exhausted tier's `retryAfterSeconds`;
- the login page re-rendered with the throttle message in the existing single error-banner slot: `Too many login attempts. Try again in N min.` (N = ⌈Retry-After / 60⌉).

A plain browser form POST renders a 429 HTML body normally, and the login form is a plain POST (no `hx-post`).

All three tiers run before any lookup, so status, headers and body depend only on the counters for the client IP and the submitted string, never on whether that string is an admin. The existing no-enumeration requirement (identical 200 on every credential failure) is untouched. Throttling is a distinct, non-credential state, and the 429 is equally identical for existing and phantom emails.

### D9 — `admin_login_throttled` is a structured WARN log line, not an `admin_actions_log` row

No admin actor is identified pre-lookup, and V16's `admin_id NOT NULL` forbids an unowned row (D14's reasoning). Looking the email up just to attribute the row would put a DB read on the exact flood path the throttle exists to absorb.

`AdminAuditLogger.logLoginThrottled` emits `event=admin_login_throttled axis=<ip|email|email_lockout> email_hash=… ip=… user_agent="…" retry_after_s=…`. The fields match the D14 line; the plaintext email, password and TOTP never appear. It is WARN rather than D14's INFO because a throttle is an abuse signal worth filtering for in Cloud Logging.

There is one line per throttled request, so a flood costs log volume. At ~300 bytes a line, a million throttled requests is ~300 MB, well inside Cloud Logging's free allotment. Sampling or first-per-window suppression can come if that ever bites.

### D10 — Fail-open, and a required limiter parameter

The throttle trusts the limiter's fail-soft contract (`rate-limit-infrastructure`): `RedisRateLimiter` catches Lettuce errors and admits, and the dev/test binding is `NoOpRateLimiter`. The route adds no try/catch of its own, matching `AuthRoutes`.

Fail-open is the right default for a login throttle. A Redis outage must not lock the operator out of the panel they would use to respond, and Argon2id plus mandatory TOTP still stand behind it.

`admin()` takes `loginRateLimiter: RateLimiter` with **no default**, so a wiring cannot silently ship without it. `Application.module()` passes the shared env-aware `rateLimiter` (Redis in staging/prod). `withAdminApp` defaults to `NoOpRateLimiter()`, so the ~40 existing admin suites, including the 60-attempt timing benchmark, are unaffected, and throttle tests inject an `InMemoryRateLimiter`.

## Risks / Trade-offs

- **Targeted lockout of the admin.** Anyone who knows the admin's email can keep that account throttled, by spending 5 requests per 15 minutes, or 20 a day once the lockout tier is full. This is inherent to any per-account limiter; the lockout tier lowers the attacker's cost but adds no capability the burst tier lacked. Mitigations:
  - the admin email is not published;
  - IAP (BARU-1) removes the unauthenticated reachability entirely when Bucket 2 opens;
  - the break-glass is documented in docs/07: `DEL` the two `{email:…}` keys in Upstash. The hash is computed with `printf %s '<email>' | openssl dgst -sha256 -binary | basenc --base64url | tr -d =`.
- **CSP breaks a future template that adds inline JS, an inline style, or an eval-dependent htmx feature.** The source-scan guards catch the first two at build time, and `allowEval:false` makes the third fail loudly in development. This is the intended trade.
- **Shared-file collisions** with in-flight #277 / #496 (`AdminModule.kt`, `AdminAuditLogger.kt`) and #266 (`Application.kt`). Each touch is additive and local: one parameter plus one install line, one method placed right after `logEmailNotFoundAttempt`, and one named argument. Whoever merges second rebases.

## Migration Plan

No schema change. Deploy is a normal revision. Rollback is a revert: the headers and throttle are stateless apart from short-lived Redis keys that expire on their own (≤ 24 h).

## Open Questions

None blocking. The lockout numbers (D6) are the review's suggested shape plus the 24 h tier. The operator may tune them at review time.
