## Context

The admin panel (`/admin/*`, Pebble + HTMX + vendored CSS, docs/11 § 3.6) runs inside the `:backend:ktor` Cloud Run service on `api-staging.nearyou.id`. That host points straight at Cloud Run: no Cloudflare proxy, no IAP yet (BARU-1 is Bucket 2). The service runs with `--memory=512Mi` (≈128 MiB JVM heap), `--cpu=2` and `--concurrency=80`. Nothing sets a security response header, and `POST /admin/login` has no rate limiter.

Existing pieces this change reuses:

- **`RateLimiter.tryAcquireByKey`** (`:core:domain`): the axis-agnostic sliding-window entry point. Its Redis implementation (`RedisRateLimiter`) is fail-soft (admits on a Redis error), and the dev/test binding without `redis-url` is `NoOpRateLimiter`. Staging and prod fail boot without `redis-url`, so a deployed env never silently runs on NoOp. `releaseMostRecent(userId, key)` pops the newest entry of a key in both implementations. `AuthRateLimiter` (#214) is the precedent for unauthenticated, IP-keyed throttles with a 429 + `Retry-After` shape.
- **`AdminAuditLogger.logEmailNotFoundAttempt`** (design D14 of `admin-login-argon2-totp`): a structured INFO line for login attempts with no admin actor, because V16's `admin_actions_log.admin_id NOT NULL` forbids an unowned row. Its `email_hash` is SHA-256 base64url of the submitted email.
- **PR #585**: removed every inline `style=` from the admin templates and added `AdminTemplateStyleTest` (a source scan), so `style-src 'self'` is already safe for markup.
- **`ClientIpExtractor`**: trusts `CF-Connecting-IP` as sent, then the first `X-Forwarded-For` entry. Until the host sits behind Cloudflare with an origin check (#530 / BARU-1a), `call.clientIp` is client-chosen.

What still blocks a strict CSP is three inline `<script>` blocks:
- `layout.peb`: the CSRF `htmx:configRequest` hook;
- `login.peb`: the password toggle;
- `wordlist-editor-content.peb`: the entry filter.

There are also two htmx 2.0.4 defaults: `includeIndicatorStyles: true`, under which htmx injects a `<style>` element at init, and `historyCacheSize: 10`, under which it keeps `<body>` snapshots of `hx-push-url` pages in `localStorage`.

## Goals / Non-Goals

**Goals:**
- Every response carries a strict CSP (`'self'`, no `'unsafe-inline'` / `'unsafe-eval'`), anti-framing, `nosniff`, `Referrer-Policy` and HSTS, and the admin panel still works under it.
- `POST /admin/login` is throttled before any Argon2id or DB work by an in-process concurrency gate plus per-IP, per-email and sustained per-email lockout tiers, counting failed attempts only. The throttle answers 429 + `Retry-After`, emits a structured `admin_login_throttled` signal, never leaks account existence, and fails open on a Redis outage.

**Non-Goals:**
- `Cache-Control: no-store`, `Permissions-Policy` and COOP/COEP. They are cheap but not in the review finding, so they are left for whenever the IAP / separate-service move (BARU-1) revisits the admin edge.
- CSP violation reporting (`report-to`). There is no collector, and adding one is a new endpoint and contract.
- Validating `CF-Connecting-IP` / an origin check. That is #530 (BARU-1a), which needs the operator's Cloudflare DNS change and fixes every IP-axis limiter at once.
- TOTP replay protection (a code reused inside its ±1-step window). It is out of the issues' scope and orthogonal to throttling.
- Throttling admin actions other than login (already covered by the per-action limiters).

## Decisions

### D1 — One application plugin on every response, attached at `CallSetup`

`AdminSecurityHeaders` is a `createApplicationPlugin` that appends the header set in an `on(CallSetup)` hook. `admin()` installs it.

- **Host-wide, not path-keyed.** A first draft matched `call.request.path()` against `/admin`. Ktor 3.4.3's router decodes each segment (`decodeURLPart`) and drops empty ones, so `//admin/users` and `/%61dmin/login` reach the admin handlers while the raw-path check sees neither. Normalising the path the way the router does would be more code and another drift surface. The headers cost nothing on the JSON API: CSP / XFO / Referrer-Policy only act in a browser rendering context, the backend serves no HTML outside `/admin`, and `nosniff` + HSTS actively help the API. This also matches the issue's "`install(DefaultHeaders)` + an admin plugin" intent with one plugin. Ktor's `DefaultHeaders` itself only adds `Date` / `Server`.
- **`CallSetup`, not `onCall`.** `RequestBodyLimit` rejects with a 413 in its own `onCall`, before a later plugin's `onCall` would run. `CallSetup` runs ahead of every plugin's `onCall`, the same hook `ClientIpExtractorPlugin` uses, so even early rejections carry the set.
- **Static assets.** `nosniff` is what makes the browser enforce the `text/javascript` / `text/css` types Ktor's `staticResources` sends.
- **HSTS** is host-wide by browser semantics anyway. Browsers ignore it over plain HTTP, so local dev is unaffected.
- **BARU-1.** When the admin moves to its own `admin.nearyou.id` service, that service still calls `admin()`, so the set moves with it.

### D2 — Header values

- **`Content-Security-Policy`**: `default-src 'self'; script-src 'self'; style-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'`. The review's four directives, plus three that do **not** fall back to `default-src`:
  - `base-uri` stops an injected `<base>` from re-pointing relative URLs;
  - `form-action` stops an injected `<form>` from posting a stolen CSRF token off-origin;
  - `object-src 'none'` is stricter than the inherited `'self'`.

  `connect-src`, `img-src`, `font-src` and the rest inherit `'self'`, which is all the panel uses (same-origin XHR, the vendored font). There are no nonces or hashes, because no inline script or style remains (D3/D4). Every form posts same-origin, including the login 303 → `/admin/` redirect that `form-action` also governs.
- **`X-Frame-Options: DENY`**: the legacy twin of `frame-ancestors 'none'`.
- **`X-Content-Type-Options: nosniff`**.
- **`Referrer-Policy: same-origin`**: admin URLs carry user UUIDs and search terms, which must not leave in a `Referer`.
- **`Strict-Transport-Security: max-age=31536000`**: one year. No `includeSubDomains` (the host has none) and no `preload` (an apex-domain list commitment, not this host's call).

### D3 — One vendored `admin.js`, every hook self-guarding

The three inline scripts move, unchanged in behaviour, into `admin/static/admin.js`. The base layout loads it after htmx, as `<script src="/admin/static/admin.js" defer>`, on every admin page, login included. Each hook is a no-op where its markup is absent:

- **CSRF hook**: the `htmx:configRequest` listener is registered only if `meta[name="csrf-token"]` exists when the deferred script runs. The meta renders only on authenticated pages, so the login page still registers no listener. The listener reads the meta at request time, so a per-login rotation propagates without a reload.
- **Password toggle**: binds to `#pw-toggle` and `#password` if present.
- **Wordlist filter**: one delegated `input` listener on `document`, matching `#wl-content .wl-filter`. The inline version re-ran after each HTMX swap of the fragment. A delegated listener needs no re-binding, so htmx no longer needs to execute swapped scripts (D4 turns that off).

The toggle and filter write `element.style.display` through the CSSOM. CSP `style-src` governs `style` attributes in markup, `setAttribute('style', …)` and `<style>` elements, not CSSOM property writes. So the behaviour is kept byte-for-byte, with no new CSS classes.

**One file, not one per page.** It is three small hooks; split when a third panel script would make the file a grab-bag.

**Integrity**: `admin.js` gets a line in `htmx.min.js.SHA256SUMS`. The CI step requires every file in the directory to be listed and matched, so an unpinned edit fails CI.

**Guard**: `AdminTemplateStyleTest` fails if an admin template contains:
- a `<script>` without `src=`;
- an `on<event>=` attribute;
- an eval-dependent htmx feature: `hx-on`, `hx-vars`, a `js:` / `javascript:` value, or an `hx-trigger` filter `[...]`.

With `allowEval:false`, such features fail only as a console event, so the build is where they must be caught. This mirrors the #585 no-inline-style guard.

### D4 — htmx config: `{"includeIndicatorStyles":false,"allowEval":false,"allowScriptTags":false,"historyCacheSize":0,"refreshOnHistoryMiss":true}`

- **`includeIndicatorStyles:false`**: htmx would insert a `<style>` block for `.htmx-indicator` at init, which `style-src 'self'` blocks. No template uses `htmx-indicator` / `hx-indicator`.
- **`allowEval:false`**: htmx refuses `hx-on`, `js:` values and trigger filters instead of hitting the CSP. None is used today; the D3 guard keeps it that way.
- **`allowScriptTags:false`**: htmx removes `<script>` elements from swapped content instead of re-creating them. Nothing relies on fragment scripts any more (D3). `script-src 'self'` alone would still let a swapped `<script src="/same-origin">` run, and other same-origin paths (`/api/*`) are not script assets.
- **`historyCacheSize:0` + `refreshOnHistoryMiss:true`** (found-while, security review):
  - Problem: 27 `hx-push-url` sites mean htmx would keep up to 10 `<body>` snapshots of admin pages (user PII, report text, CSAM metadata) in `localStorage`, where they survive logout.
  - At 0, htmx clears the cache key and never writes it.
  - `refreshOnHistoryMiss` makes back/forward do a full page load instead of an `HX-Request` fetch, which our routes would answer with a bare fragment.

The meta sits in `<head>` before the deferred htmx script, so htmx reads it at init.

### D5 — Throttle placement, order and counting

The order inside `handlePost`:

1. Parse the form; the existing oversized-field 400 guard.
2. **Concurrency gate** (D11).
3. **IP tier**: `{scope:rate_admin_login}:{ip:<IpHasher.hash(clientIp)>}`, 10 / 15 min.
4. **Email burst tier**: `{scope:rate_admin_login}:{email:<sha256 base64url of the submitted email>}`, 5 / 15 min.
5. **Email lockout tier**: `{scope:rate_admin_login_lockout}:{email:<same hash>}`, 20 / rolling 24 h.
6. `findActiveByEmail`, then Argon2id / TOTP.

**Tier evaluation.** The first exhausted tier answers. Later tiers are not consulted, so an IP-throttled flood does not spend the target email's slots. A throttled attempt has spent one slot in each earlier tier.

**Only failures count.** After a successful login (session inserted, success audit row written), the route calls `releaseMostRecent(admin.id, key)` for each of the three keys, popping the newest entry. Under concurrency that may be another attempt's entry, which leaves the count the same. As a result:
- the operator's own logins never approach the lockout tier;
- the staging smoke scripts (`smoke-admin-*.sh`, up to 3 successful logins per run with one test email) never trip it through their successes. The login smoke's one wrong-password POST against the test email does count. That allows 5 runs per 15 min on the burst tier and **20 runs per 24 h** on the lockout tier. The 21st run in a day gets 429 on every step, its successful login included. The wrong-password probe must use the real test email, since it is compared byte-for-byte against the phantom body for no-enumeration. The budget is documented in docs/07 rather than changing the script;
- a prober cannot read "recent legitimate activity" off a bucket that trips early (the counter-state oracle the security review raised).

**Key rules.** All three keys are rolling sliding windows. None ends in `_day`, so they take sliding (not fixed-window) semantics, and `RateLimitTtlRule` correctly does not demand `computeTTLToNextReset` (the per-user, WIB-staggered daily-quota reset, with nothing to stagger here). The keys use the two-segment hash-tag shape (`RedisHashTagRule`), and the IP value is the `IpHasher` hash, never the raw IP.

**Why Redis and not the `admin_actions_log` COUNT limiter** every other admin limiter uses: no admin is identified before the lookup, and the flood path must not hit the DB.

### D6 — The numbers, and why a lockout tier

- **IP 10 / 15 min**: admins are few and log in rarely, so it is generous for a shared office or CGNAT exit. It is **not** a hard per-source bound on this host today, because `CF-Connecting-IP` is client-chosen (see Risks). D11 is the bound that holds regardless.
- **Email 5 / 15 min**: the per-account bound that survives IP rotation and spoofing.
- **Email lockout 20 / 24 h**: the "progressive lockout" of #532's title and the review's "lockout bertahap". The arithmetic, given a phished or leaked password where only TOTP stands:
  - each attempt has about a 3 × 10⁻⁶ chance (±1 step skew = 3 valid codes of 10⁶);
  - the burst tier alone allows 480 failures a day ≈ 175 k a year, about a 41 % yearly chance of a hit;
  - the lockout tier caps it at 20 a day ≈ 7.3 k a year, about 2 %.

  A burst recovers in 15 minutes; a sustained attack on one account stays locked for up to a day. Successes don't count (D5), so the operator's own typos are the only legitimate consumer of the 20.

The constants live in `AdminLoginRoutes`' companion. Tests use them as-is; a test attempt on a phantom email costs one sentinel Argon2 verify (~150 ms on a dev machine).

### D7 — Email key = hash of the exact submitted string, no normalisation

`findActiveByEmail` is an exact `email = ?` TEXT match, so only the exact stored string can ever authenticate. Case or whitespace variants can never succeed against the real account, so they cannot advance an attack on it, and bucketing the exact string bounds every useful attempt. The key value reuses `AdminAuditLogger.sha256Base64Url`, the same `email_hash` the D14 log line carries, so throttle and email-not-found lines for the same submission correlate. Base64url (`[A-Za-z0-9_-]`) is safe inside a hash-tag segment.

### D8 — Throttle response and no-enumeration

A throttled or shed attempt gets:
- **`429 Too Many Requests`**;
- **`Retry-After: <seconds>`**, from the exhausted tier's `retryAfterSeconds`, or 60 for a shed;
- the login page re-rendered with the throttle message in the existing single error-banner slot: `Too many login attempts. Try again in N min.` (N = ⌈Retry-After / 60⌉).

A plain browser form POST renders a 429 HTML body normally, and the login form is a plain POST.

The gate and all three tiers run before any lookup, so status, headers and body depend only on in-flight load and on the counters for the client IP and the submitted string, never on whether that string is an admin. Since only failures count (D5), the counters for a real admin's address and a phantom address evolve identically under the same failed traffic. The existing no-enumeration requirement (identical 200 on every credential failure) is untouched. Throttling is a distinct, non-credential state, and its 429 is equally identical for existing and phantom emails.

### D9 — `admin_login_throttled` is a structured WARN log line, not an `admin_actions_log` row

No admin actor is identified pre-lookup, and V16's `admin_id NOT NULL` forbids an unowned row (D14's reasoning). Looking the email up just to attribute the row would put a DB read on the exact flood path the throttle exists to absorb.

`AdminAuditLogger.logLoginThrottled` emits `event=admin_login_throttled axis=<ip|email|email_lockout|concurrency> email_hash=… ip="…" user_agent="…" retry_after_s=…`. It is WARN rather than D14's INFO because a throttle is an abuse signal worth filtering for in Cloud Logging. The plaintext email, password and TOTP never appear.

**Quoting (found-while, security review).** `ip` (an unvalidated header today) and `user_agent` are client-controlled. Unquoted, a header value like `<ip> axis=email` would forge a field for log queries. Both go through `logQuoted`, which quotes and escapes `"`, `\`, CR and LF. The existing D14 email-not-found line had the same shape, and gets the same fix in this PR, since it is the same file and helper.

**Log volume.** One line per throttled request, so a flood costs log volume. At ~300 bytes a line, a million throttled requests is ~300 MB, well inside Cloud Logging's free allotment. Sampling or first-per-window suppression can come if that ever bites.

### D10 — Fail-open, and a required limiter parameter

The rate tiers trust the limiter's fail-soft contract (`rate-limit-infrastructure`). The route adds no try/catch of its own, matching `AuthRoutes`. Fail-open is the right default for a login throttle: a Redis outage must not lock the operator out of the panel they would use to respond, and Argon2id plus mandatory TOTP still stand behind it. The concurrency gate (D11) is in-process, so it keeps bounding hash work while Redis is down.

`admin()` takes `loginRateLimiter: RateLimiter` with **no default**, so a wiring cannot silently ship without it. `Application.module()` passes the shared env-aware `rateLimiter`. `withAdminApp` defaults to `NoOpRateLimiter()`, so the existing admin suites (including the 60-attempt timing benchmark) are unaffected, and throttle tests inject an `InMemoryRateLimiter` or a recording limiter.

### D11 — A credential-check concurrency gate (security review, blocking)

Rate tiers bound attempts per key, not concurrent work. With the IP tier spoofable on this host, a spray that rotates `CF-Connecting-IP` and the email on every request passes all three tiers, and each attempt runs a sentinel Argon2 verify at 19 MiB. About 6 of those in parallel exhaust the ~128 MiB heap, which is a crash-loop DoS of the whole API, not just the admin.

So `AdminLoginRoutes` holds a `kotlinx.coroutines.sync.Semaphore(2)`, one per application, sized to `--cpu=2` and 2 × 19 MiB. `handlePost` takes a permit (waiting at most 2 s) after the oversized-field guard and before the tiers, and returns it in a `finally`:
- **Before the tiers**, a shed request costs no Upstash command. That is the flood case where Redis spend matters most.
- **Permit safety.** Admission is a flag set on the line right after `acquire()` returns, with no suspension point between them. It is not derived from `withTimeoutOrNull`'s result. Prompt cancellation only covers a timeout that fires while `acquire()` is suspended: then the permit handed over is released. A timeout that fires after `acquire()` returned still makes `withTimeoutOrNull` return null (the kotlinx "asynchronous timeout and resources" caveat). Reading that null as "no permit" would leak one per race, and under a FIFO flood that race is exactly where hand-offs land (round-2 review).
- **Shed response.** A request that waits out the 2 s is shed as a throttled attempt (`axis=concurrency`, `Retry-After: 60`).

The cost is that under a sustained concurrent flood the operator's own login is likely shed too. That is the same outcome the attack would cause by taking the instance down, minus the outage for every API user.

The semaphore is a defaulted `admin()` / `AdminLoginRoutes` constructor seam, so a test can hold every permit and observe the shed.

## Risks / Trade-offs

- **The per-IP tier is spoofable until #530 (BARU-1a).** `CF-Connecting-IP` is trusted as sent on a host that is not behind Cloudflare. This is true of every IP-axis limiter in the app (`AuthRateLimiter` included), and the review's § 15.4 already says the IP axis "doesn't count as working" until then. Here, the email tiers (per-account) and the concurrency gate (per-instance) are the controls that hold. The IP tier becomes real once the origin check ships.
- **Upstash amplification.** Each admitted attempt with a fresh email costs ~3 `EVALSHA` and leaves a 24 h lockout ZSET. A sustained spoofed spray can spend the Upstash Free command or memory quota, which makes *every* limiter in the app fail open. The gate sheds concurrent floods before Redis, but a slow, steady spray still spends commands. The real bound is edge rate-limiting (Cloudflare rules, #530 / BARU-1). Monitor the Upstash usage graph after deploy.
- **Targeted lockout of the admin.** Anyone who knows the admin's email can keep that account throttled: 5 requests per 15 min, or 20 a day once the lockout tier fills. The address is not secret: the admin-bootstrap README and smoke scripts name the operator's address pattern. This is inherent to any per-account limiter; the lockout tier lowers the attacker's cost but adds no capability the burst tier lacked. Mitigations:
  - IAP (BARU-1) is the real fix, removing unauthenticated reachability when Bucket 2 opens.
  - **Break-glass**, documented in docs/07, part (a): `DEL` the `{scope:rate_admin_login}:{email:<h>}` and `{scope:rate_admin_login_lockout}:{email:<h>}` keys (and the operator's `{ip:…}` key) in the Upstash console. The email hash is `printf %s "$EMAIL" | openssl dgst -sha256 -binary | openssl base64 -A | tr '+/' '-_' | tr -d '='` (portable; byte-equal to `sha256Base64Url`). The IP hash is `printf %s "$IP" | openssl dgst -sha256 -r | cut -c1-16` (= `IpHasher`).
  - **Break-glass**, part (b): an attacker who keeps polling refills the bucket within seconds, so (a) can be raced. The fallback that cannot be raced is to rotate `admin_users.email`, a human-gated DB write, which orphans the attacker's keys.
  - An OWASP-style device-cookie exemption is the next step if this ever bites before IAP.
- **The gate sheds the operator under a concurrent flood** (D11): an accepted trade versus an OOM crash-loop of the whole service.
- **CSP breaks a future template that adds inline JS, an inline style or an eval-dependent htmx feature.** The source-scan guards catch all three at build time. This is the intended trade.
- **Shared-file collisions** with in-flight #277 / #496 (PRs #601 / #599: `AdminModule.kt`, `AdminAuditLogger.kt`) and #266 (`Application.kt`). Each touch is additive and local, and whoever merges second rebases.

## Migration Plan

No schema change. Deploy is a normal revision. Rollback is a revert: the headers and throttle are stateless apart from short-lived Redis keys that expire on their own (≤ 24 h).

## Open Questions

None blocking. The lockout numbers (D6) and the gate size (D11) are tunable constants. The operator may adjust them at review time.
