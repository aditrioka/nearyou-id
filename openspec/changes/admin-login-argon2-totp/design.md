## Context

Admin #1 ([`admin-schema-bootstrap`, PR #107](https://github.com/aditrioka/nearyou-id/pull/107)) shipped the V16 admin schema. Admin #2 ([`admin-panel-ktor-htmx-bootstrap`, PR #115](https://github.com/aditrioka/nearyou-id/pull/115)) shipped the `/admin/*` route subtree with Pebble + HTMX + a base layout, intentionally unauthenticated and guarded against production exposure via a `KTOR_ENV != "production"` mount check + a per-request WARN log carrying the `admin-login-argon2-totp` search marker. This change closes the gate.

Stakeholder posture: solo-operator pre-launch build (Oka). No live admin users yet; the first admin row is bootstrapped via manual SQL INSERT against the live database (procedure documented in D11 below). The admin module is currently co-located with the main `:backend:ktor` deployable; the eventual separate-Cloud-Run-service migration to `admin.nearyou.id` is a [Phase 3.5 deployment task](../../../docs/08-Roadmap-Risk.md), tracked separately from this auth-gate work.

Threat model: the admin panel exposes destructive capabilities (user suspension, content redaction, CSAM handler invocation, feature flag toggle, manual referral grant, audit-log read). Compromising an admin session is high-impact. Defense posture: defense-in-depth across (a) **transport** — TLS via Cloudflare zone-wide; (b) **identity** — Argon2id password + TOTP mandatory in the solo-admin period, WebAuthn mandatory before the second-admin hire (deferred to a focused follow-up); (c) **session** — `__Host-`-prefixed cookie with SameSite=Strict + HttpOnly + Secure; opaque token (not signed JWT) + SHA-256 at rest so DB compromise doesn't expose live session tokens; (d) **request** — CSRF token on every state-changing request, mismatch audit-logged; (e) **observation** — every login attempt + every CSRF violation audit-logged with IP + user-agent.

Constraints:

- Schema is fixed (V16 shipped) — this change ships zero migrations. Schema-layer enforcement of `csrf_token_hash NOT NULL` becomes runtime-exercised here.
- The two new substrate libraries (Password4j for Argon2id, samstevens java-totp for TOTP RFC 6238) MUST be subject to pre-implementation library re-check per [`openspec/project.md`](../../project.md) § Pre-implementation library re-check before `/opsx:apply` lands the first feat commit.
- The 16 critical invariants in [`CLAUDE.md`](../../../CLAUDE.md) § "Critical invariants" apply — specifically: admin-sessions write must populate `csrf_token_hash` (V16 schema-layer enforced + runtime tested here); secrets read via `secretKey(env, name)` helper (for `admin-totp-secret-aes-key`); client IP via `clientIp` request-context (for `admin_sessions.ip` + `admin_actions_log.ip`); no vendor SDK import outside `:infra:*` (Password4j + java-totp are JVM utility libraries, not vendor SDKs — they live in `:backend:ktor`).
- Same-PR convention per [`openspec/project.md`](../../project.md) § Change Delivery Workflow — this change ships proposal + feat + archive on one branch with one squash-merge.

Stakeholders: Oka (solo operator, sole admin until second-admin hire). Code reviewers (multi-lens sub-agents in the proposal-review phase; qodo on the implementation diff at `/opsx:apply` step 8).

## Goals / Non-Goals

**Goals:**

- Ship a production-safe admin authentication path: Argon2id password + TOTP (RFC 6238) verification, `__Host-`-prefixed session cookie with SHA-256-at-rest token storage, CSRF protection on every state-changing request, audit logging on every login attempt + CSRF violation, idle 30-min sliding session timeout with an absolute 8h cap.
- Drop the unauthenticated-scaffold posture from Admin #2 in a positively-observable way (WARN log disappears; production-mount guard lifted; route surface flips from "always open" to "auth-required except /admin/login").
- Make the schema-layer `csrf_token_hash NOT NULL` invariant (V16) runtime-exercised so a future Detekt rule layered on top has positive precedent to reference.
- Establish substrate decisions (Password4j for Argon2id, samstevens java-totp for TOTP) that downstream admin-auth-touching changes (WebAuthn enrollment, password rotation, backup codes) can inherit.
- Author the test matrix that turns each invariant into a regression-resistant assertion: cookie attributes per RFC, CSRF mismatch → 403, idle timeout enforced, no-enumeration response shape, audit row written on every gate.

**Non-Goals:**

- Initial admin user bootstrap UI / endpoint — out of scope; the first admin row is provisioned via manual SQL INSERT documented in D11.
- TOTP secret enrollment UI / QR-code generation — base32 encoding + manual authenticator-app configuration is the bootstrap path.
- WebAuthn enrollment + login — `admin-webauthn-enrollment-and-login` focused follow-up before the second-admin hire.
- Per-IP login rate limiting — `admin-login-rate-limit` follow-up once Layer 1 IP-axis rate-limit infrastructure ships. IAP / Cloud Armor at the network layer (Phase 3.5 deployment task #2) is the Layer 1 defense in the interim.
- Privilege-escalation cookie rotation — no admin-role-change actions ship in this change.
- `admin_app` DB-role connection separation — `admin-module-admin-app-role-swap` follow-up tied to the Phase 3.5 separate-Cloud-Run-service deployment task.
- Sentinel `system` admin user — `system-actor-and-worker-audit-rows` follow-up; orthogonal to real admin login.
- Admin self-service password change — `admin-self-service-password-change` follow-up.
- WebAuthn challenge cleanup worker — per [Phase 3.5 §13](../../../docs/08-Roadmap-Risk.md).
- TOTP backup codes — common UX but not in docs; defer.
- Session listing / "log me out everywhere" self-service UI.
- Admin module migration to `admin.nearyou.id` separate Cloud Run service + IAP/Cloud Armor — Phase 3.5 deployment task #2.

## Decisions

### D1: Library substrate — Password4j for Argon2id, samstevens java-totp for TOTP RFC 6238

**Choice:**

- **Argon2id:** `com.password4j:password4j` 1.8.4 (Maven Central, [Password4j/password4j](https://github.com/Password4j/password4j)).
- **TOTP RFC 6238:** `dev.samstevens.totp:totp` 1.7.1 (Maven Central, [samdjstevens/java-totp](https://github.com/samdjstevens/java-totp)).
- **AES-256-GCM:** JCA built-in (`javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")`). No third-party library. **AAD binding:** the admin's UUID (`admin_users.id`, 16 raw bytes) SHALL be bound as Additional Authenticated Data. This prevents a DB-write attacker from swapping admin A's encrypted TOTP secret into admin B's row — the AAD mismatch causes decryption to fail with `AEADBadTagException`. The encrypt path passes the admin UUID via `Cipher.updateAAD(adminUuidBytes)` before `doFinal(plaintext)`; the decrypt path does the same. Trade-off: re-encrypting a TOTP secret to migrate it to a different admin requires re-running through the bootstrap script.
- **SecureRandom:** JCA built-in (`java.security.SecureRandom`).
- **SHA-256 + constant-time compare:** JCA built-in (`java.security.MessageDigest` + `MessageDigest.isEqual()`).

**Rationale (Password4j vs argon2-jvm):**

- Password4j is actively maintained with releases through 2026, OWASP-aligned recommended-settings docs ([wiki](https://github.com/Password4j/password4j/wiki/Recommended-settings)), single coordinate, pure-Java (no JNI), supports Argon2id natively + bcrypt + scrypt + PBKDF2 (forward-compatible if we ever need a second KDF for backup codes or recovery flows).
- `argon2-jvm` ([phxql/argon2-jvm](https://github.com/phxql/argon2-jvm)) is a thinner Argon2-only binding that requires the native `libargon2` C library — adds JNI complexity to the Cloud Run container build, no clear benefit over Password4j's pure-Java implementation.
- Verified via propose-time WebSearch 2026-05-28: Password4j remains the standard JVM Argon2id library per OWASP + Maven Central activity.

**Rationale (samstevens java-totp vs alternatives):**

- `dev.samstevens.totp:totp` 1.7.1 has the most popular Maven Central coord shape for TOTP, includes verifier + secret generation + QR-code generation + URI generation. We only need the verifier in Admin #3, but inheriting the rest is useful for the eventual TOTP enrollment UI (post-MVP).
- `jchambers:java-otp` is leaner (verification + generation only, no QR). Acceptable alternative; samstevens wins on ecosystem completeness for the eventual enrollment work.
- `BastiaanJansen:otp-java` 2.x is newer with OTPAuth URI support; comparable API surface but smaller adoption.
- `bratkartoffel:libtotp` is minimal-deps but lacks the QR/URI helpers we'll need for enrollment.
- Verified via propose-time WebSearch 2026-05-28: samstevens java-totp remains the most-cited Maven Central TOTP library, with active 2025/2026 activity.

**Both libraries are subject to pre-implementation library re-check** per [`openspec/project.md`](../../project.md) § "Pre-implementation library re-check" — `/opsx:apply` MUST run a fresh dated WebSearch before landing the first feat commit and document the re-check outcome in the first commit body (`re-check 2026-MM-DD confirms: <library> still best option, no ecosystem shift since proposal` OR escalate per the rule's outcome ladder).

**Alternatives considered:**

- Spring Security Crypto for Argon2id — rejected: brings the Spring umbrella into a non-Spring codebase (the project uses Ktor + Koin, not Spring).
- Bouncy Castle for Argon2id — rejected: BC is a sprawling cryptography toolkit; Password4j wraps a focused, audited Argon2 implementation with simpler API.
- Hand-rolling TOTP verification (RFC 6238 is conceptually simple) — rejected: timing-side-channel implementation correctness for verification is non-trivial; library wins.
- AES-256-GCM via Bouncy Castle — rejected: JCA built-in handles AES-GCM natively in JDK 11+ (which is the project's pin), no third-party needed.

### D2: Argon2id parameter tuning — target ≥500ms verify on dev machine, document floor

**Choice:** Argon2id parameters tuned during implementation against a benchmark task on the local dev machine. **Floor** (OWASP minimums per Password4j wiki): memory ≥ 15 MiB (15 * 1024 KiB), iterations ≥ 2, parallelism = 1. **Ceiling** target: verify time approximately 500-800ms on the dev machine (single verify call). Concrete values land in the first feat commit with the benchmark output; expected starting point is `memory = 64 MiB, iterations = 3, parallelism = 1` per Password4j's recommended Argon2id config for "minimum production".

**Rationale.** GPU-resistant hashing requires the verify to take meaningful wall time (≥500ms per Password4j wiki) so an offline brute-force attempt is computationally prohibitive. The 500-800ms target trades login latency (acceptable for admin panel; user sees a redirect spinner) for attacker cost. Parallelism = 1 because Cloud Run instances are typically single-vCPU-burst-capable; parallelism > 1 doesn't help in this deployment model.

**Tuning procedure** (lands in tasks.md):

1. Write a benchmark Kotest spec that runs Argon2id verify 10 times with candidate parameters, asserts mean wall time ∈ [400, 800] ms.
2. Run locally; iterate parameters until the assertion passes.
3. Commit the chosen parameters as Kotlin constants in `PasswordHasher.kt` with a `// @benchmark 2026-MM-DD on <machine>: mean 612ms (n=10)` comment.
4. Re-benchmark in staging during pre-archive smoke (verify the staging Cloud Run instance's effective verify time matches dev's).

**Alternatives considered:**

- Hardcode OWASP minimum (15 MiB, 2 iter) — rejected: too fast on modern hardware; Password4j wiki specifically warns "OWASP recommendations are minimums for very resource-constrained environments". The Cloud Run instance for the admin panel is not resource-constrained.
- Time-based auto-tuning at startup (probe verify time, adjust until target hit) — rejected: introduces non-determinism (different Cloud Run instances tune differently); makes audit of the cost factor harder; deferred to a future "operational hardening" follow-up.
- Time-cost calibration via Password4j's `SystemChecker` tool — interesting but introduces a startup-time cost-discovery step; deferred per same rationale as auto-tuning.

### D3: TOTP algorithm + step + skew — SHA-1, 30s step, ±1 step skew

**Choice:**

- Algorithm: HMAC-SHA-1 (RFC 6238 default).
- Step size: 30 seconds (RFC 6238 default + Google Authenticator default).
- Digits: 6 (RFC 6238 default + Google Authenticator default).
- Skew tolerance: ±1 step (so the verifier accepts codes from the previous step, the current step, OR the next step — 90 seconds total window).

**Rationale.** Google Authenticator + Authy + 1Password + Microsoft Authenticator all default to SHA-1 / 30s / 6 digits. SHA-256 / SHA-512 TOTP variants exist but authenticator-app support is variable (1Password supports SHA-256 but Google Authenticator does not in all versions). Defaulting to the widest-compat shape avoids "the admin's authenticator app silently truncates / displays the wrong algorithm" footguns. Cryptographic strength of SHA-1 in TOTP is NOT relevant — TOTP doesn't rely on SHA-1's collision-resistance; it relies on HMAC's PRF property, which holds for HMAC-SHA-1.

±1 step skew tolerates the ~30-second clock-drift window typical of authenticator apps without NTP-sync. ±2 steps doubles the window and weakens replay resistance; ±0 steps fails too many legitimate logins. ±1 is the canonical default per RFC 6238 §5.2.

**Alternatives considered:**

- SHA-256 — rejected: variable authenticator-app support; no security benefit for TOTP use case.
- 8-digit codes — rejected: most authenticator apps default to 6; the UX cost of mismatched digit counts isn't worth the entropy gain (6 digits = 10^6 = 20 bits, plenty for short-lived TOTP).
- ±0 skew — rejected: causes frequent legitimate-login failures on phones with clock drift.

### D4: Cookie shape — `__Host-`-prefixed opaque token, no Domain, SHA-256 at rest

**Choice:** Cookie name `__Host-admin_session`. Attributes: `Secure`, `HttpOnly`, `SameSite=Strict`, `Path=/`. No Domain attribute (required by `__Host-` prefix per [RFC 6265bis](https://www.rfc-editor.org/rfc/rfc6265bis-12.html) §4.1.3.2). Value = base64url-encoded 256-bit random token (43 chars after stripping padding). SHA-256 hash of the token stored at rest in `admin_sessions.session_token_hash` (TEXT NOT NULL UNIQUE). Plain token never persisted.

**Rationale.** The `__Host-` prefix forces the cookie to be host-locked (no Domain attribute, Path=/, Secure required). Combined with `SameSite=Strict`, this gives the strongest cookie isolation available in the platform — no cross-site sending, no domain-scoping abuse, no subdomain cookie injection. `HttpOnly` prevents JS access. The opaque-token + SHA-256-at-rest pattern means a database compromise reveals only hashes, not live session tokens (attacker can't replay against the admin panel without the plaintext token).

**`Path=/` is mandated by `__Host-`, not chosen.** Per RFC 6265bis §4.1.3.2, the `__Host-` prefix REQUIRES `Path=/`; any other value makes the browser reject the Set-Cookie. A narrower `Path=/admin` would reduce exposure to XSS on other paths (`/api/v1/*`), but `__Host-` and `Path=/admin` are mutually exclusive constraints. The chosen trade-off is "lock the cookie to one host via `__Host-`" over "lock the cookie to one path via narrower `Path`"; the host lock is the stronger defense against cookie injection (subdomain attacks) which is the threat we care about more in a multi-host platform. The `HttpOnly` attribute mitigates the residual XSS exfiltration risk on other paths.

**Why opaque random instead of signed JWT / stateless cookie:**

- Sessions are mutable (idle timeout refresh, revocation on logout, revocation on role escalation). Stateless JWT can't support these without a revocation list, which defeats statelessness.
- The `admin_sessions` row carries IP + user_agent + last_active_at — these fields are operationally useful (suspicious-session detection, admin-self-service-view-my-sessions UX). JWT can't carry them without ballooning the cookie size.
- Opaque token + SHA-256-at-rest matches the project's pattern for other secret-storage paths (`refresh_tokens.token_hash` in user auth).

**Hostname considerations.** The docs ("`Domain=admin.nearyou.id`") are aspirational for the eventual separate-Cloud-Run-service deployment to `admin.nearyou.id`. Under the `__Host-` prefix, the cookie has NO Domain attribute regardless of host — it's locked to whichever origin served the response. In this change, `/admin/*` is mounted on the main `:backend:ktor` deployable (`api.nearyou.id` in staging/prod), so cookies are issued to `api.nearyou.id`. After the Phase 3.5 separate-CR migration, cookies are issued to `admin.nearyou.id`. The hostname change is transparent to the cookie format.

**Alternatives considered:**

- Signed cookie (Ktor's default `SessionStorage.SignedSessionStorage`) — rejected: signed cookies are stateless; can't revoke without a deny-list. The `admin_sessions` table is the authoritative session store.
- Encrypted cookie (`EncryptedSessionStorage`) — rejected: same statelessness problem as signed.
- Bearer token in `Authorization` header (no cookie) — rejected: admin panel is HTMX-driven (HTML forms + HTMX requests, not an SPA with JS-controlled auth headers). Cookies are the natural fit; HTMX requests automatically carry them.
- Skip `__Host-` prefix — rejected: prefix gives a free defense against subdomain cookie injection. No reason to forgo it.
- Skip `SameSite=Strict` (use `Lax`) — rejected: admin panel has zero cross-site flows. Strict is the safer choice.

### D5: Session token storage — opaque token + SHA-256-at-rest (per docs, no signing key consumed)

**Choice:** Generate session token via `SecureRandom.nextBytes(new byte[32])` (256-bit), base64url-encode → cookie value. SHA-256 hash → `admin_sessions.session_token_hash`. Lookup: cookie value → SHA-256 → query `admin_sessions WHERE session_token_hash = $1 AND revoked_at IS NULL AND expires_at > NOW() AND last_active_at > NOW() - INTERVAL '30 minutes'`.

**Rationale.** Matches [`docs/05-Implementation.md` § Admin Session Cookie + WebAuthn](../../../docs/05-Implementation.md) verbatim. Pre-Phase 1 §40 reserves a `admin-session-cookie-signing-key` GCP Secret Manager slot for an optional future "signed cookie" mode (with HMAC signing) but this change does NOT consume it — opaque-token + SHA-256-at-rest is sufficient for the threat model and adds no signing-key rotation complexity.

**CSRF token storage — AMENDED during apply (HMAC-derived, not independent-random).** The original design called for an independent SecureRandom 256-bit CSRF token returned to the client + stored as SHA-256. Implementation surfaced that the authenticated layout must RE-RENDER the plaintext CSRF token into the `<meta>` tag on every page load (D6), but the V16 schema stores only the hash — so the server needs to recompute the plaintext at render time without persisting a second secret. Resolution (apply-phase, user-approved 2026-05-29 after a dated re-check against the 2026 OWASP CSRF Cheat Sheet): the CSRF token is **derived from the session token via HMAC-SHA256 with a server-side secret key** — the canonical "Signed Double-Submit Cookie" pattern (OWASP: "HMAC is preferred over simple hashing in all cases", and the token is session-bound). Concretely `csrfToken = base64url(HMAC-SHA256(adminCsrfHmacKey, sessionToken))`, stored as `SHA-256(csrfToken)` hex in `admin_sessions.csrf_token_hash` (V16 NOT NULL enforced). The `admin-csrf-hmac-key` (env-namespaced GCP slot, DISTINCT from the TOTP AES key — key separation) is resolved lazily via `secretKey(env, name)` + threaded through `admin()` to the login + index routes. Properties preserved: per-login rotation (fresh session token → fresh CSRF), no per-request rotation (stable token → stable CSRF). Property gained vs the original plan: forgery requires BOTH the HttpOnly+SameSite=Strict session token AND the server-side HMAC key. (An initial apply attempt used plain `SHA-256(sessionToken + ":csrf")`; the dated re-check flagged that as non-canonical — OWASP mandates HMAC-with-server-secret — and it was upgraded to HMAC before merge.)

### D6: CSRF surfacing — `<meta>` tag in authenticated layout + HTMX configRequest JS hook

**Choice:** When serving an authenticated page, the base layout SHALL render `<meta name="csrf-token" content="${csrfToken}">` in the `<head>`. The same layout SHALL include an inline `<script>` block that registers an `htmx:configRequest` event listener:

```html
<script>
  document.addEventListener('htmx:configRequest', function(evt) {
    var meta = document.querySelector('meta[name="csrf-token"]');
    if (meta) { evt.detail.headers['X-CSRF-Token'] = meta.content; }
  });
</script>
```

This auto-adds the `X-CSRF-Token` header on every HTMX `hx-get`/`hx-post`/`hx-put`/`hx-patch`/`hx-delete` request. The CSRF middleware then validates the header against the session's stored hash.

**Rationale.** HTMX has no built-in CSRF support (it's intentionally framework-agnostic). The meta-tag + JS hook is the canonical HTMX-community pattern for CSRF — documented in the HTMX docs' "Security" section. The JS is tiny (5 lines), runs once at page load, and doesn't interfere with any HTMX feature.

**For non-HTMX traditional form posts (e.g., logout button as a `<form>`):** the layout SHALL render `<input type="hidden" name="_csrf" value="${csrfToken}">` inside any form whose method is not GET. The CSRF middleware accepts the token from EITHER the `X-CSRF-Token` header OR the `_csrf` form field, with the header taking precedence if both present.

**Token rotation:** the CSRF token is regenerated on every successful login (per docs). It does NOT rotate per request — rotating per request would break the meta-tag pattern (the next request's HTMX header would mismatch the stored hash). Per-login rotation is sufficient for the threat model: an attacker who steals a CSRF token also needs the session cookie to use it, and SameSite=Strict on the session cookie already prevents cross-site sends. **Multiple in-flight state-changing requests under the same session SHALL all succeed with the same CSRF token** — this is the natural consequence of per-login rotation; the spec includes an explicit scenario asserting it to prevent a future change from silently flipping to per-request rotation.

**Pebble escape strategy: explicit `html` filter on the CSRF meta tag — AMENDED during apply.** Pebble's default escape strategy is HTML-context (`{{ value }}` produces HTML-entity-escaped output suitable for element content). The CSRF token is rendered as the value of the `content="..."` attribute. The original design specified `{{ csrfToken | escape('html_attr') }}` for attribute-context escaping — but **Pebble 3.x has no `html_attr` escaping strategy** (it throws `PebbleException: Unknown escaping strategy [html_attr]` at render time; the available strategies are `html`, `js`, `css`, `url_param`). Resolution: use `{{ csrfToken | escape('html') }}`. HTML-context escaping escapes `"`, `'`, `<`, `>`, `&` — sufficient for double-quoted attribute context, and more than sufficient for the CSRF token's actual character set (base64url `[A-Za-z0-9_-]`, which has no characters needing escaping at all). The explicit filter still bakes in the defense-in-depth invariant against a future change that puts special characters in the value. The `login.peb` `errorMessage` relies on Pebble's default autoescape (verified by the `AdminTemplateEscapeTest` regression guard).

**Alternatives considered:**

- HTMX `hx-headers` attribute on every state-changing element — rejected: error-prone (every new element must remember it); the JS hook centralizes it.
- Double-submit cookie pattern (CSRF token in a separate cookie, validated against the header) — rejected: requires both cookies to have correct SameSite attributes; the meta-tag pattern is simpler and equally secure for our threat model.
- Origin / Referer header validation as the sole CSRF defense — rejected: Origin/Referer can be omitted in some cases (HTTPS → HTTPS strict-origin policy can drop it); the explicit token is the canonical defense.

### D7: Login POST CSRF protection — NOT required on `/admin/login` POST

**Choice:** The `POST /admin/login` endpoint does NOT require a CSRF token. It is exempt from the CSRF middleware. All OTHER state-changing requests under `/admin/*` (including `POST /admin/logout`) require the token.

**Rationale.** The login endpoint is pre-session — there's no `admin_sessions` row to look up the CSRF hash against. The classical "login CSRF" attack (attacker forces victim to log in to attacker's account, then attacker reads what victim did) is bounded for the admin panel because:

- `SameSite=Strict` on the session cookie prevents cross-site sending after login, so the attacker can't subsequently use the victim's browser to read the admin state.
- The admin panel has no "did the victim do X under my account?" surface (no admin self-service of significance ships in Admin #3 — only audit log + suspend/unban in Admin #4/#5).
- The admin panel UI does not auto-fill credentials; the victim must type their own credentials, which they would only do on the legitimate admin panel.

Documentation of the exemption + audit-log markers on every login attempt (with IP + user-agent) means anomalous login patterns are observable. If the bounded login-CSRF surface ever expands, a follow-up can add a pre-session cookie + double-submit pattern.

**Alternatives considered:**

- Issue a pre-session anti-CSRF cookie on `GET /admin/login` and require its double-submit on `POST /admin/login` — rejected: extra complexity for bounded threat. Document the exemption + revisit if surface expands.
- Require an "honeypot" hidden field on the form — rejected: not a real defense against scripted attackers; security theater.

### D8: Session timeout — sliding 30-min idle + absolute 8h cap

**Choice:**

- **Sliding idle timeout:** session is invalid when `NOW() - last_active_at > INTERVAL '30 minutes'`. Every authenticated request updates `last_active_at = NOW()`.
- **Absolute timeout:** session is invalid when `NOW() > expires_at`. `expires_at` is set to `NOW() + INTERVAL '8 hours'` at session creation and never extended.
- **Revocation:** session is invalid when `revoked_at IS NOT NULL`. Set by logout endpoint and (in future changes) by role-escalation handlers.

**Rationale (sliding 30-min):** Matches [`docs/04-Architecture.md`](../../../docs/04-Architecture.md) + [`docs/05-Implementation.md`](../../../docs/05-Implementation.md) + [`docs/07-Operations.md`](../../../docs/07-Operations.md) all of which prescribe "30 min idle via `last_active_at`". Idle timeout limits the window where a forgotten-open admin tab can be hijacked.

**Rationale (absolute 8h cap):** Not strictly required by docs but added as defense-in-depth. The 8h window matches a typical admin's workday — admins re-authenticate at the start of each shift. A compromised session cannot persist indefinitely even if the admin keeps hitting refresh every 29 minutes. 8h is the project's first absolute cap for session lifetime; it can be tightened (or extended) in a follow-up without schema change.

**Refresh semantics:** every authenticated request inside the session middleware writes `UPDATE admin_sessions SET last_active_at = NOW() WHERE id = $1`. This is one UPDATE per request — acceptable cost for admin panel traffic volumes (single admin, tens of requests per session). For higher-volume future-state with multiple admins, batching last_active_at updates (e.g., only refresh if `last_active_at < NOW() - INTERVAL '30 seconds'`) is a possible optimization tracked under "Open Questions" below.

### D9: No-enumeration login response shape + timing equalization

**Choice:** Every login failure path returns the same response — HTTP 200, same form re-rendered with the same error message ("Email, password, or code is incorrect."), no distinguishing markers in headers or body. The form re-render includes the same hidden fields it would on a successful request (the only state difference is the error vs success path).

Timing equalization: the login handler ALWAYS runs Argon2id verify, even when the email is not found. If `admin_users WHERE email = $1` returns no row, the handler verifies against a fixed sentinel hash (a hardcoded Argon2id hash of an unguessable string, computed once at module bootstrap). This means the wall time of an "email not found" attempt approximates the wall time of a "wrong password" attempt.

Audit logging IS distinguishing — the `admin_actions_log` row records the actual failure reason (`password_mismatch`, `totp_mismatch`, `inactive_admin`, `totp_secret_missing`). The audit log is operator-only; it does not leak to the requester. The fifth failure mode `email_not_found` is captured at the application's structured INFO logger only — see D14 for the schema-driven rationale (the V16 `admin_actions_log.admin_id NOT NULL` invariant forbids unowned audit rows).

**Rationale.** Username enumeration on the admin panel is a real attacker-recon surface. A "no such admin" response distinguishes valid from invalid emails, letting an attacker confirm whether `oka@example.com` is an admin without trying to crack the password. Timing-side-channel distinguishing is the same problem at a different layer — if "email not found" returns in 5ms and "wrong password" returns in 600ms, the attacker has the same signal without needing the response body.

The sentinel-hash approach is well-known (used by Django's `User.set_unusable_password()` + many other auth frameworks). The sentinel value is a constant hash of a 256-bit random string generated once at proposal time and stored in source; the SOURCE secrecy of the sentinel value isn't important (attackers can't ever pass it), only that it's a valid Argon2id hash with the same tuned params as production hashes.

**Implementation detail.** TOTP verification is also conditionally short-circuited if the password verify fails. If we ALSO want TOTP verification timing to match (so an attacker can't tell "wrong password" from "wrong TOTP"), we run a sentinel TOTP verify too. Decision: yes, run a sentinel TOTP verify on the wrong-password path so timing for password_mismatch matches totp_mismatch.

**Alternatives considered:**

- Return HTTP 401 with a generic body — rejected: same effect as 200 + form re-render but less HTMX-idiomatic.
- Reveal the failure reason in the message — rejected: defeats the purpose of no-enumeration.
- Skip timing equalization — rejected: trivial timing attack defeats no-enumeration.

### D10: `Application.admin()` cleanup — auth + CSRF plugins installed inside the extension function

**Choice:** Modify `Application.admin()` per the in-source TODO at `AdminModule.kt:14-39`:

1. Remove the `if (ktorEnv == "production") return` mount guard + its bootstrap WARN log.
2. Remove the per-request WARN interceptor (lines ~75-95 in current `AdminModule.kt`).
3. Update the head-of-file comment block: drop the "unauthenticated scaffold" framing; describe the auth-gate design and reference Admin #3.
4. Install the Ktor `Authentication` plugin with a custom session-validation provider (reads `__Host-admin_session` cookie, looks up session via SHA-256, validates per D8, populates `call.principal` with `AdminPrincipal(adminId, adminRole)`).
5. Install the custom `AdminCsrfPlugin` that validates `X-CSRF-Token` header (or `_csrf` form field) against the session's `csrf_token_hash` on POST/PUT/PATCH/DELETE.
6. Wrap the existing `routing { route("/admin") { adminIndex() } }` block: split `/admin/login` (GET + POST, unauthenticated, CSRF-exempt) and `/admin/logout` (POST, authenticated, CSRF-required) into their own outer route sections; wrap `adminIndex()` + future admin routes inside `authenticate("admin") { ... csrfRequired { ... } }`.

**Structure of new files (under `backend/ktor/src/main/kotlin/id/nearyou/app/admin/auth/`):**

- `AdminAuthPlugin.kt` — `Authentication` provider that reads the session cookie and validates.
- `AdminCsrfPlugin.kt` — custom Ktor plugin for CSRF header/form validation + audit logging on mismatch.
- `LoginRoute.kt` — `GET /admin/login` (render Pebble template) + `POST /admin/login` (verify + session create + cookie + audit).
- `LogoutRoute.kt` — `POST /admin/logout` (revoke session + clear cookie + audit + redirect).
- `PasswordHasher.kt` — Password4j Argon2id wrapper + sentinel hash for timing equalization.
- `TotpVerifier.kt` — samstevens java-totp wrapper.
- `AesGcmCipher.kt` — JCA `AES/GCM/NoPadding` wrapper for `totp_secret_encrypted` decryption.
- `AdminAuditLogger.kt` — helper for inserting `admin_actions_log` rows on the four action types.
- `SessionRepository.kt` — JDBC queries against `admin_sessions` (INSERT, SELECT by token hash, UPDATE last_active_at, UPDATE revoked_at).
- `AdminUserRepository.kt` — JDBC query against `admin_users` (SELECT by email).
- `AdminPrincipal.kt` — Ktor principal data class.

**Pebble template updates (under `backend/ktor/src/main/resources/templates/admin/`):**

- `login.peb` — NEW. Login form (email + password + TOTP) extending the base layout. Renders error message when present.
- `_layout.peb` (or whichever base layout name Admin #2 chose) — MODIFY. Add conditional rendering of `<meta name="csrf-token">` + the HTMX configRequest JS hook when a CSRF token is available in the rendering context.

### D11: Initial admin user bootstrap — manual SQL INSERT procedure

**Choice:** The first admin user (Oka) is provisioned via a manual SQL INSERT against the live database. No admin self-signup endpoint, no admin enrollment UI, no Cloud Run Job — those are post-MVP UX work.

**Procedure (documented in `tasks.md` Section 1 + `docs/10-Setup-Checklist.md`):**

1. **Generate the Argon2id password hash** on the local dev machine using a one-shot Kotlin script in `dev/scripts/admin-bootstrap/` (committed):
   ```kotlin
   val hash = Password.hash("<chosen-password>").addRandomSalt().with(Argon2Function.getInstance(...))
   println(hash.result)
   ```
   Use the same tuned parameters from `PasswordHasher.kt` (D2).
2. **Generate the TOTP secret** as a 160-bit (20-byte) SecureRandom byte array. Base32-encode the secret for authenticator-app provisioning. Manually configure the authenticator app (e.g., 1Password "Add → One-Time Password → Setup manually") with the base32 secret + 30s step + SHA-1 + 6 digits.
3. **Encrypt the TOTP secret** with the AES-256-GCM key from GCP Secret Manager (`staging-admin-totp-secret-aes-key` or `admin-totp-secret-aes-key`):
   ```kotlin
   val encrypted = AesGcmCipher.encrypt(totpSecretBytes, aesKey)  // returns nonce || ciphertext || tag
   println(encrypted.base64())  // for psql \\x literal
   ```
4. **INSERT the admin row** via Supabase Console SQL editor (or `psql` against the live DB):
   ```sql
   INSERT INTO admin_users (email, display_name, password_hash, totp_secret_encrypted, role, is_active)
   VALUES (
     'oka@nearyou.id',
     'Oka',
     '<argon2id-hash-from-step-1>',
     '\\x<hex-encoded-encrypted-totp-from-step-3>',
     'owner',
     TRUE
   );
   ```
5. **Verify the login** by hitting `/admin/login` with the credentials + the current TOTP code from the authenticator app.

**Security notes for the bootstrap script:**

- Script lives at `dev/scripts/admin-bootstrap/Main.kt` (or similar); intended for local-machine use only.
- DO NOT log or persist the plaintext password, plaintext TOTP secret, or the AES key.
- Script reads the AES key from a local env var; the env var is set from a `gcloud secrets versions access` invocation, not from a config file.
- After provisioning, the script's exit prints the SQL INSERT line and a clear "DO NOT save this output to a file" warning.
- For staging-only bootstrap, the AES key is fetched from `staging-admin-totp-secret-aes-key`; production uses `admin-totp-secret-aes-key`.

**Why this approach instead of a Cloud Run Job admin-bootstrap command:**

- One-shot operation for the solo-admin period. Cloud Run Job adds deployment surface for a single use case.
- The manual SQL path is auditable and reviewable (the SQL is committed to a runbook).
- Post-MVP: when admin self-service enrollment ships, this manual path is retired.

### D12: DB connection — use existing `main_app` for admin tables; `admin_app` swap deferred

**Choice:** The admin module reads/writes `admin_users`, `admin_sessions`, `admin_actions_log` via the existing `main_app` JDBC connection (current `:backend:ktor` data source). Separate `admin_app`-role connection is NOT introduced in this change.

**Rationale.** The admin module is currently co-located with the main `:backend:ktor` service. Adding a second DataSource for the same logical service (admin module on main service) doubles connection pool overhead and operational surface. The `admin_app` separation makes sense as part of the Phase 3.5 separate-Cloud-Run-service deployment task (where the admin module becomes a distinct deployable on `admin.nearyou.id`).

**Consequence.** The schema-layer REVOKE on `admin_actions_log` (UPDATE + DELETE revoked from `admin_app`) is NOT yet a runtime defense — `main_app` does not have those revokes. The defense relies on application-layer discipline: the admin code SHALL only INSERT into `admin_actions_log`, never UPDATE/DELETE. A Detekt rule could enforce this at compile time but is out of scope for Admin #3.

**Follow-up.** File `admin-module-admin-app-role-swap` per the proposal's Out-of-Scope list. The follow-up's design problem: under the current single-deployable shape, the admin module would need a second DataSource pointing at the same Postgres with different credentials. Under the eventual separate-CR-deployment shape, the admin module gets its own service with its own credentials trivially. The follow-up's design should defer to the deployment shape that's current at the time it lands.

### D14: `email_not_found` audit path routes to INFO logger, NOT `admin_actions_log`

**Choice:** When a login attempt's email does NOT resolve to any `admin_users` row, the failure is recorded at the application's structured INFO logger only — NOT inserted into `admin_actions_log`. The structured INFO log line carries fields: `event=admin_login_attempt_email_not_found`, `ip` (from `call.clientIp`), `user_agent`, `email_hash` (SHA-256 of the submitted email — so the same email can be correlated across attempts without exposing the plaintext), `timestamp`. The other four failure modes (`password_mismatch`, `totp_mismatch`, `inactive_admin`, `totp_secret_missing`) DO write to `admin_actions_log` because they have a real admin row to FK against. The login success path also writes to `admin_actions_log`.

**Rationale.** The V16 schema declares `admin_actions_log.admin_id UUID NOT NULL REFERENCES admin_users(id)` — the column is NOT NULL and the FK rejects synthetic UUIDs. Writing a row with `admin_id = NULL` (or a non-existent UUID) would throw `not_null_violation` / `foreign_key_violation` at runtime. The proposal-phase round-1 multi-lens review (general lens B1 + security lens B1) surfaced this contradiction in the initial spec draft.

Three resolution paths were considered:

- **(a) Ship a sentinel `system` admin row in this change** — contradicts proposal § Out-of-Scope (the `system-actor-and-worker-audit-rows` follow-up owns this); also requires a CHECK constraint or query-level safeguard to prevent the sentinel from being used as a real-admin login target (the schema mandates `password_hash NOT NULL`, so the sentinel's stored hash would either be a valid hash for an unguessable password OR a special-cased value that bypasses the NOT NULL constraint, both of which add complexity).
- **(b) Ship a V17 migration relaxing `admin_id` to NULLABLE** — contradicts the canonical `admin-schema` spec; also weakens the audit-trail integrity invariant ("every audit row has a real admin actor") which is operationally valuable for the Admin #4 audit-log viewer.
- **(c) Drop the email-not-found row from `admin_actions_log`; route to structured INFO log instead** — chosen. The structured INFO log line goes to the same Sentry + Cloud Logging surface as audit rows, so anomaly detection (per `docs/08-Roadmap-Risk.md` Phase 1 §29 "Anomaly detection metrics") catches email-enumeration probes at the same observability layer. The cost is that the Admin #4 in-app `admin-actions-log-viewer` (a future change) won't surface anonymous failed attempts — but those attempts aren't admin actions, so they don't belong in an admin-actions UI anyway.

**Operationally.** The structured INFO log line at `event=admin_login_attempt_email_not_found` plus the spec's no-enumeration response contract (identical HTML body across all failure modes) means an attacker probing for valid admin emails gets the same response shape every time — the probe is detectable in logs (volume + IP), not in HTTP responses. This is the same defense pattern as user-side login enumeration.

### D15: Sentinel-hash regeneration discipline

**Choice:** The sentinel Argon2id hash (for timing equalization per D9) SHALL be regenerated whenever the Argon2id parameters in `PasswordHasher.kt` (memory, iterations, parallelism) are retuned. Regenerated hash + tuned parameters land in the SAME commit. The sentinel value is committed as a Kotlin constant `SENTINEL_HASH` in `PasswordHasher.kt` with an `// @sentinel-of-params <memory>-<iterations>-<parallelism> generated <date>` comment recording the params combination.

**Rationale.** The sentinel hash carries embedded parameters (Password4j's hash string format includes `$argon2id$v=19$m=...,t=...,p=...$...`). The timing equalization invariant requires the sentinel verify to take the same wall time as a production hash verify. A drift (sentinel embeds OLD params; production embeds NEW params) re-introduces the timing distinguishability that D9 closed.

**Implementation.** Task 3.3 tunes the params; task 3.4 generates the sentinel against those params; both happen in the same commit. A future Argon2 re-tune (the operational follow-up tracked under D2's "auto-tuning at startup" rejected-alternative) MUST also regenerate the sentinel in the same commit. The benchmark Kotest spec (task 11.17) includes a sentinel-params-match-production-params assertion to catch drift at CI time.

### D16: Session middleware fails CLOSED on DB exception

**Choice:** When the session middleware's SELECT against `admin_sessions` throws (connection pool exhausted, Postgres restart mid-request, Cloudflare timeout, etc.), the middleware SHALL fail CLOSED — return 302 to `/admin/login` (or 500 if the implementation prefers to surface the underlying error class without revealing internal details to the client). The middleware SHALL NOT fail open — i.e., the principal SHALL NOT be populated with a "trust me bro" value, and the route handler SHALL NOT be reached without a valid session.

**Rationale.** Fail-open silently bypasses authentication during a DB outage. The cost of fail-closed during a true DB outage is "admin can't log in until DB is back," which is the correct posture — the entire admin tool depends on the DB anyway (Admin #4 audit log, Admin #5 suspend/unban) so a DB outage means the admin can't do anything regardless. Fail-closed preserves the auth invariant during partial outages (e.g., admin_sessions query times out but the rest of the system stays up).

**Implementation.** The session middleware's outer try/catch routes any thrown exception into the same 302-to-login path as an empty-cookie request. Spec Req "Session middleware validates the cookie on every authenticated request" adds a scenario asserting the fail-closed behavior. Tests use a connection-error-injecting test harness.

### D17: TOTP replay tracking is a documented gap

**Choice:** Admin #3 does NOT track previously-used TOTP codes. A code accepted at time T can be replayed at time T + 30s (within the ±1 step skew window). This is a documented gap with mitigation by per-IP rate limiting (deferred to `admin-login-rate-limit` follow-up) + IAP / Cloud Armor at the network layer + audit logging surfacing brute-force patterns.

**Rationale.** Per-code replay prevention requires storing the last-accepted TOTP step in `admin_sessions` (or a new `admin_users.last_totp_step_accepted_at` column). This is a real schema delta and ships in a focused follow-up. The replay surface is bounded: an attacker must shoulder-surf or intercept the 6-digit code within the ±30s window AND have the admin's password AND the admin's email — all three together means the attacker has bigger problems than TOTP replay. The 90-second window with a hardware token assumes the admin promptly clears the code; in practice the cooldown between login attempts (Argon2id 500ms + auth flow rendering) bounds the per-window replay window to maybe 2-3 attempts.

**Follow-up name.** `admin-totp-replay-tracking` — adds either `admin_sessions.last_used_totp_step BIGINT` (per-session) or `admin_users.last_used_totp_step BIGINT` (per-admin) plus the verify path's check + update. The follow-up lands alongside or after the `admin-login-rate-limit` follow-up (both narrow the TOTP-brute-force surface).

### D18: Constant-time comparison discipline

**Choice:** Every secret-derived comparison in the admin auth path uses an explicitly constant-time API:

- **SHA-256 hash comparison** (session token, CSRF token) — `java.security.MessageDigest.isEqual(byte[] a, byte[] b)`. Documented constant-time in JDK 17+ per [JDK-8224030](https://bugs.openjdk.org/browse/JDK-8224030).
- **Argon2id password verification** — Password4j's `Password.check(plaintext, hash).withArgon2()` API. Library handles constant-time internally.
- **TOTP code verification** — samstevens java-totp's `CodeVerifier.isValidCode(secret, code)`. Library handles constant-time internally.

**Forbidden patterns** (audited via grep + tests):

- `==` on `String`, `ByteArray`, or hash output.
- `String.equals(other)`.
- `Arrays.equals(byte[] a, byte[] b)` — NOT constant-time per JDK docs.
- Manual byte-by-byte comparison with early exit.

The auth path code is small enough to review manually for these patterns; a Detekt rule could be added in a focused follow-up if drift becomes a concern.

**Sentinel hash usage** (timing equalization in D9): the sentinel Argon2id hash is checked via `Password.check(submittedPassword, sentinelHash).withArgon2()` — same code path as the real check, same wall time.

**Alternatives considered:**

- Use `MessageDigest.isEqual` only and accept that library APIs handle their own constant-time — chosen, with documentation.
- Implement a project-local constant-time compare helper — rejected: JCA's built-in is the canonical primitive; avoid re-inventing.

## Risks / Trade-offs

**[Risk] Library substrate staleness** — Password4j or samstevens java-totp may have deprecated APIs or stale activity by the time `/opsx:apply` runs.
→ **Mitigation:** Pre-implementation library re-check rule (per [`openspec/project.md`](../../project.md)) fires before the first feat commit. If either library has gone stale, `/opsx:apply` STOPS and surfaces the finding for user resolution (either swap or accept).

**[Risk] Argon2id tuning produces login latency that admins find annoying** — 500-800ms login per attempt feels slow.
→ **Mitigation:** UX shows a spinner during login submission. Admin logins are infrequent (once per shift). If concrete user feedback flags the latency, adjust the tuned parameters down (with a documented trade-off note) — this is a constant change without schema impact.

**[Risk] No per-IP rate limiting means an attacker can attempt unlimited TOTP guesses (90s × 10^6 = ~3 years to brute-force a single 6-digit code, but parallel attempts compress that)** — IP-axis rate limiting is the canonical defense.
→ **Mitigation:** Deferred to `admin-login-rate-limit` follow-up (gated on Layer 1 IP-axis rate-limit infrastructure). In the interim, IAP / Cloud Armor (Phase 3.5 deployment task #2) provides network-layer defense. Audit logging surfaces brute-force patterns to Sentry for manual intervention.

**[Risk] Sentinel hash for timing equalization is hardcoded in source** — if the source leaks, an attacker can compute the sentinel value and... do what? The sentinel is checked but the result is discarded; even if the attacker submits the sentinel-plaintext, the email-not-found branch still returns the no-enumeration error. The sentinel's secrecy is not load-bearing.
→ **Mitigation:** No action needed; document that the sentinel is intentionally fixed.

**[Risk] `main_app` DB connection does not enforce `admin_actions_log` immutability at the DB role level** — the REVOKE is on `admin_app`, which we don't use yet.
→ **Mitigation:** Application-layer discipline + code review + (future) Detekt rule. Document the gap in `tasks.md` + file the `admin-module-admin-app-role-swap` follow-up.

**[Risk] HTMX configRequest JS hook is the SOLE CSRF enforcement on the client side** — if a future template forgets the meta tag, HTMX requests silently fail with 403.
→ **Mitigation:** Test for the meta tag presence in the rendered HTML; test that an HTMX request without the header → 403; test that the JS hook is present in the layout. If a future template inadvertently disables the layout, the failure is observable (admin can't perform any state-changing action; obvious in dev).

**[Risk] 8h absolute cap arbitrarily chosen; not in docs** — may be longer than typical session usage warrants, or shorter than what admins want.
→ **Mitigation:** Document the choice + revisit during the second-admin onboarding (when usage patterns become observable). Cap is a constant in source; future change can adjust without schema impact.

**[Risk] Removing the `KTOR_ENV` mount guard means production immediately exposes `/admin/*` on `api.nearyou.id` (until the Phase 3.5 separate-CR migration)** — anyone hitting `https://api.nearyou.id/admin/login` can attempt to authenticate.
→ **Mitigation:** This is by design — the auth gate is the production-safe replacement for the mount guard. The `/admin/login` endpoint exposes only the login form + the failure response (no enumeration); the rest of `/admin/*` is auth-gated. Pre-launch (Public Launch in [`docs/08-Roadmap-Risk.md`](../../../docs/08-Roadmap-Risk.md)) is when production becomes attacker-relevant; the separate-CR migration + IAP / Cloud Armor land before then. In the interim, the auth gate + the absence of admin users in production (no row inserted until the manual bootstrap) means the only login attempts that can succeed are by Oka.

**[Risk] Pre-archive smoke against staging requires a known-good test admin to exist in staging** — provisioning order matters.
→ **Mitigation:** `tasks.md` Section 1 includes the staging bootstrap as a precondition for the staging smoke task in Section 13. Run the manual SQL INSERT for a `oka-staging@nearyou.id` admin against staging Supabase before the smoke task fires.

**[Risk] Password4j brings dependency footprint** (~500KB) — accept.
**[Risk] samstevens java-totp brings dependency footprint** (~150KB) — accept.

## Migration Plan

**Per-phase deployment** (no DB migration in this change):

1. **`/opsx:apply` lands feat commits to the change branch.** First commit body includes the pre-implementation library re-check outcome per [`openspec/project.md`](../../project.md) § "Pre-implementation library re-check".
2. **CI green on every push.** No `paths-ignore` matches — full lane runs.
3. **Pre-archive smoke task** (`tasks.md` Section 13) provisions a staging test admin via manual SQL INSERT, runs `gh workflow run deploy-staging.yml --ref admin-login-argon2-totp`, polls the deploy run, executes the smoke script that:
   - GETs `https://api-staging.nearyou.id/admin/login` (expect 200 + login form HTML).
   - POSTs to `/admin/login` with the staging test admin's credentials + current TOTP (expect 302 to `/admin/` + `Set-Cookie: __Host-admin_session=...`).
   - GETs `/admin/` with the cookie (expect 200 + index page).
   - POSTs to `/admin/logout` (expect 302 + cookie cleared).
   - GETs `/admin/` again (expect 302 to `/admin/login`).
4. **`/opsx:archive` lands the archive commit** + spec sync.
5. **Squash-merge to `main`** → auto-deploys to `main`-staging.

**Production deploy (post-MVP):**

- After the Phase 3.5 separate-Cloud-Run-service migration lands `admin.nearyou.id` with IAP / Cloud Armor.
- Provision the production admin row via the manual SQL INSERT procedure.
- First production login attempt by Oka.

**Rollback:**

- If the auth gate misbehaves in staging (login impossible, sessions immediately reject, idle timeout misfires), revert the squash commit on `main` and let the next staging deploy roll back.
- Roll-forward path is preferred over rollback for any post-merge issue (hotfix commits to a new branch + a fresh PR).

**Failure modes during the rollout:**

- Library re-check surfaces a materially-better alternative → `/opsx:apply` STOPS per the rule; either swap substrate (new design.md amendment) or document the alternative as a follow-up.
- Argon2id tuning fails to find params within [400, 800] ms on dev → relax to [300, 1000] ms + document; OR adjust Cloud Run instance class to compensate.
- Smoke script reveals a login regression → fix on the branch, push, re-smoke.

## Open Questions

1. **CSRF token storage on the page after login.** The token is returned in the response (or surfaced via the meta tag on subsequent page loads). Should the meta tag value be the plaintext token (so the JS hook reads it directly) or the SHA-256 hash (and the JS hook re-hashes server-side via a no-op endpoint)? **Resolution direction:** plaintext token in meta tag is fine — same security properties as a cookie value (HTTPOnly cookie protects against XSS reading session; meta tag doesn't, but CSRF token + session cookie together still require the attacker to bypass SameSite=Strict, which is the cookie's job, not the token's). Confirm at implementation time.
2. **Last-active-at refresh batching.** Should every authenticated request UPDATE `last_active_at`, or batch (e.g., only refresh if `last_active_at < NOW() - INTERVAL '30 seconds'`)? **Resolution direction:** start with every-request for simplicity; benchmark in staging; optimize if writes become a bottleneck. Defer to a follow-up if needed.
3. **CSRF token rotation cadence.** Per-login is documented. Should we ALSO rotate on successful state-changing requests (so a stolen token has shorter validity)? **Resolution direction:** per-login only — per-request rotation breaks the meta-tag-once-per-page-load pattern. The stolen-token surface requires also stealing the session cookie, which is mitigated by SameSite=Strict + HttpOnly.
4. ~~Sentinel hash storage~~ → **RESOLVED in D15** (regenerated alongside any Argon2id retune; same commit; benchmark spec asserts the sentinel-params-match-production-params invariant).
5. ~~Pebble escape behavior~~ → **RESOLVED in D6** (use explicit `escape(strategy='html_attr')` filter on the CSRF meta tag rather than relying on the default; defense-in-depth).
6. **Logout idempotency.** A `POST /admin/logout` with an already-revoked session — should it return 200 (idempotent), 401 (no session), or 302 to login? **Resolution direction:** idempotent 302 to `/admin/login`. The cookie is cleared regardless; the audit row is not written if no active session was found.

These resolve at `/opsx:apply` implementation time; no proposal-phase blockers.
