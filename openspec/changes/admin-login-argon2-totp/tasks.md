## 1. Library + secret-slot prep

- [ ] 1.1 Run pre-implementation library re-check per [`openspec/project.md`](../../project.md) § "Pre-implementation library re-check": fresh dated `WebSearch` for `"password4j vs argon2-jvm <current-year> production"` AND `"dev.samstevens.totp vs java-otp <current-year> production"`. Record the outcome in the first feat commit body (`re-check 2026-MM-DD confirms: <library> still best option per <source>, no ecosystem shift since proposal`) OR escalate per the rule's outcome ladder.
- [ ] 1.2 Add new pins to [`gradle/libs.versions.toml`](../../../gradle/libs.versions.toml): `password4j = "1.8.4"` (or whichever the re-check confirms), `samstevens-totp = "1.7.1"`. Library entries: `password4j = { module = "com.password4j:password4j", version.ref = "password4j" }` and `samstevens-totp = { module = "dev.samstevens.totp:totp", version.ref = "samstevens-totp" }`.
- [ ] 1.3 Activate Ktor sessions plugin: add `ktor-serverSessions = { module = "io.ktor:ktor-server-sessions-jvm", version.ref = "ktor" }` to libs.versions.toml (shared `ktor = "3.4.1"` version variable; same shape as `ktor-serverPebble` in PR #115).
- [ ] 1.4 Wire the three new coordinates into [`backend/ktor/build.gradle.kts`](../../../backend/ktor/build.gradle.kts) under `dependencies { implementation(libs.password4j); implementation(libs.samstevens.totp); implementation(libs.ktor.serverSessions) }` (the catalog-accessor names per ktlint-friendly camelCase).
- [ ] 1.5 Provision GCP Secret Manager slot `staging-admin-totp-secret-aes-key` for staging: `gcloud secrets create staging-admin-totp-secret-aes-key --project=nearyou-staging --replication-policy=automatic` + add a 256-bit AES key (`openssl rand -base64 32 | gcloud secrets versions add staging-admin-totp-secret-aes-key --data-file=-`) + grant `roles/secretmanager.secretAccessor` to the staging Cloud Run runtime SA (per the `provision-admin-app-staging.sh` precedent).
- [ ] 1.6 Document the production slot provisioning procedure in `dev/scripts/admin-totp-key-bootstrap.sh` (idempotent script for both staging + prod with `PROJECT_OVERRIDE` env var per the `provision-admin-app-staging.sh` precedent). DO NOT execute against production in this change — production provisioning is deferred to the production-bootstrap milestone.
- [ ] 1.7 Document the manual first-admin bootstrap procedure (per `design.md` D11) in `dev/scripts/admin-bootstrap/README.md` and ship the script at `dev/scripts/admin-bootstrap/Main.kt` (Kotlin one-shot CLI). Script accepts `--email`, `--display-name`, `--role`, reads the AES key from `ADMIN_TOTP_AES_KEY_BASE64` env var (set via `gcloud secrets versions access ...`), generates Argon2id hash for an interactively-entered password, generates a SecureRandom 160-bit TOTP secret + base32-encodes it for authenticator-app provisioning + AES-256-GCM-encrypts it for `totp_secret_encrypted`, prints the SQL INSERT statement to stdout with a "DO NOT save this output to a file" warning. Script does NOT log plaintext password / TOTP secret / AES key.
- [ ] 1.8 Provision the staging-test admin row using the script + a known fixed password + TOTP secret for the pre-archive smoke test (Section 13). Document the staging-test-admin credentials separately from this change's PR — staging-test secret pinning is operational, not in the PR.

## 2. AES-256-GCM helper

- [ ] 2.1 Create `backend/ktor/src/main/kotlin/id/nearyou/app/admin/auth/AesGcmCipher.kt` with two methods: `fun decrypt(ciphertext: ByteArray, key: ByteArray): ByteArray` and `fun encrypt(plaintext: ByteArray, key: ByteArray): ByteArray` (encrypt is used by the bootstrap script + tests; decrypt is used at login-verify time). Internal format: `BYTEA = nonce (12 bytes) || ciphertext || auth_tag (16 bytes)` per design.md D1.
- [ ] 2.2 Use `javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")` with a fresh `SecretKeySpec(key, "AES")`, `GCMParameterSpec(128, nonce)`. The nonce is a SecureRandom 12-byte value for encrypt; extracted from the BYTEA's first 12 bytes for decrypt.
- [ ] 2.3 Unit tests: (a) round-trip on a fixed `secret = SecureRandom.nextBytes(20)` + fixed `key = SecureRandom.nextBytes(32)` returns the original secret; (b) tampered ciphertext (any byte flipped in the auth_tag region) throws `AEADBadTagException`; (c) tampered ciphertext in the data region throws `AEADBadTagException`; (d) ciphertext format BYTEA length ≥ 12 + 16 + plaintext length.

## 3. Argon2id verifier

- [ ] 3.1 Create `backend/ktor/src/main/kotlin/id/nearyou/app/admin/auth/PasswordHasher.kt` exposing `fun verify(plaintext: String, hash: String): Boolean` (delegates to Password4j's `Password.check(plaintext, hash).withArgon2()`) and `fun hash(plaintext: String): String` (used by tests + bootstrap script). Constants for tuned parameters: `MEMORY_KIB`, `ITERATIONS`, `PARALLELISM` per design.md D2.
- [ ] 3.2 Write a Kotest benchmark spec at `backend/ktor/src/test/kotlin/id/nearyou/app/admin/auth/PasswordHasherBenchmarkTest.kt` that runs `PasswordHasher.verify(plaintext, hash)` 10 times against a freshly-hashed value, asserts mean wall time ∈ [300, 2000] ms. Tag the test `@Tag("benchmark")` so it can be excluded from PR-time CI if needed (but document that it MUST run during apply-phase tuning).
- [ ] 3.3 Tune `MEMORY_KIB`, `ITERATIONS`, `PARALLELISM` against the dev machine until the benchmark target (mean 400-800ms) is hit. Document the chosen values + measured mean in a `// @benchmark 2026-MM-DD on <machine>: mean Nms (n=10)` comment in `PasswordHasher.kt`.
- [ ] 3.4 Generate the sentinel Argon2id hash for timing equalization (design.md D9): hash a fixed unguessable plaintext (e.g., `"sentinel-${UUID.randomUUID()}"` baked into source at tuning time) with the chosen parameters; store as a `SENTINEL_HASH` constant in `PasswordHasher.kt` with a comment explaining its role.
- [ ] 3.5 Unit tests for PasswordHasher: (a) verify of a freshly-hashed plaintext returns TRUE; (b) verify of a different plaintext against the same hash returns FALSE; (c) verify against the sentinel hash returns FALSE for any plaintext other than the sentinel plaintext (sentinel's secrecy isn't load-bearing, but the test verifies the constant parses as a valid hash); (d) verify of an Argon2id hash with different parameters than the configured ones still parses correctly (Password4j embeds the parameters in the hash string).

## 4. TOTP verifier

- [ ] 4.1 Create `backend/ktor/src/main/kotlin/id/nearyou/app/admin/auth/TotpVerifier.kt` exposing `fun verify(secret: ByteArray, code: String): Boolean`. Internally constructs samstevens java-totp's `DefaultCodeVerifier` with `HashingAlgorithm.SHA1`, time period 30s, allowed time period discrepancy 1; uses `SystemTimeProvider`. Constants for the parameter values per design.md D3.
- [ ] 4.2 Generate the sentinel TOTP secret (design.md D9) for timing equalization: a fixed 20-byte unguessable secret baked into source. Used when password verify failed but we still want the TOTP verify time to match.
- [ ] 4.3 Unit tests: (a) verify of a code generated at time T against the same secret at time T returns TRUE; (b) verify at time T against code generated at T-30s returns TRUE (skew tolerance); (c) verify at time T against code generated at T+30s returns TRUE (forward skew); (d) verify at time T against code generated at T-60s returns FALSE; (e) verify at time T against code generated at T+60s returns FALSE; (f) verify of a malformed code (non-digit characters, wrong length) returns FALSE without throwing.

## 5. Session middleware

- [ ] 5.1 Create `backend/ktor/src/main/kotlin/id/nearyou/app/admin/auth/AdminPrincipal.kt` — `data class AdminPrincipal(val adminId: UUID, val role: String)`. Implements `io.ktor.server.auth.Principal`.
- [ ] 5.2 Create `backend/ktor/src/main/kotlin/id/nearyou/app/admin/auth/SessionRepository.kt` exposing:
   - `fun insert(adminId: UUID, sessionTokenHash: ByteArray, csrfTokenHash: ByteArray, ip: String, userAgent: String?, expiresAt: Instant): UUID` — INSERTs and returns the new session id.
   - `fun findActiveBySessionHash(sessionTokenHash: ByteArray): SessionRow?` — SELECTs `id, admin_id, csrf_token_hash, expires_at, last_active_at, revoked_at` WHERE `session_token_hash = $1 AND revoked_at IS NULL AND expires_at > NOW() AND last_active_at > NOW() - INTERVAL '30 minutes'`. Returns NULL when no match.
   - `fun refreshLastActive(sessionId: UUID)` — UPDATE `last_active_at = NOW() WHERE id = $1`.
   - `fun revoke(sessionId: UUID)` — UPDATE `revoked_at = NOW() WHERE id = $1`.
   - All queries via `PreparedStatement` (parameterized).
- [ ] 5.3 Create `backend/ktor/src/main/kotlin/id/nearyou/app/admin/auth/AdminAuthPlugin.kt` — custom Ktor `Authentication` provider. Reads `__Host-admin_session` cookie; if absent, calls `respondRedirect("/admin/login", permanent = false)` (302); else SHA-256s the value via `MessageDigest.getInstance("SHA-256")`, calls `SessionRepository.findActiveBySessionHash(...)`, if NULL → 302 redirect; else calls `SessionRepository.refreshLastActive(...)`, populates `call.principal` with `AdminPrincipal(...)`, also stores the SessionRow (or specifically the csrfTokenHash) in `call.attributes` so the CSRF middleware can read it without a second DB lookup.
- [ ] 5.4 Wire `install(Authentication) { admin { ... } }` block in `Application.admin()` (after the existing Pebble install).

## 6. CSRF middleware

- [ ] 6.1 Create `backend/ktor/src/main/kotlin/id/nearyou/app/admin/auth/AdminCsrfPlugin.kt` — custom Ktor plugin for CSRF validation. Behavior per design.md D6, D7 + spec admin-login Requirements 6 + 7:
   - Triggers on `ApplicationCallPipeline.Plugins` phase.
   - Skips when `call.request.httpMethod` is GET, HEAD, OPTIONS.
   - Skips when `call.request.path()` is `/admin/login` (exempt per design.md D7).
   - Reads `X-CSRF-Token` header; if absent, reads `_csrf` form field via `call.receiveParameters()` (be careful — receiveParameters consumes the body; the plugin should ONLY read form fields when method is POST/PUT/PATCH/DELETE AND the request has a form content-type; otherwise rely on header only).
   - SHA-256s the submitted token; constant-time compares (`MessageDigest.isEqual`) against `call.attributes[csrfTokenHashKey]` (populated by AdminAuthPlugin).
   - On match: pass-through.
   - On mismatch / missing: call `AdminAuditLogger.logCsrfViolation(...)` with the appropriate reason (`'missing_token'`, `'header_mismatch'`, `'form_field_mismatch'`); respond 403; halt the pipeline.
- [ ] 6.2 Wire the CSRF plugin inside the `authenticate("admin") { ... }` block in `Application.admin()` so it ONLY runs on auth-required routes.

## 7. Login + logout routes

- [ ] 7.1 Create `backend/ktor/src/main/resources/templates/admin/login.peb` — Pebble template extending the base layout. Login form with `<input type="email" name="email" required>`, `<input type="password" name="password" required>`, `<input type="text" name="totp" pattern="\d{6}" maxlength="6" required>` fields. `<form action="/admin/login" method="POST">`. Conditional `<p class="error">{{ errorMessage }}</p>` block when `errorMessage` is in the rendering context. Page MUST NOT include the CSRF meta tag (no session yet) — the base layout should already make that conditional per task 8.2.
- [ ] 7.2 Modify `backend/ktor/src/main/resources/templates/admin/` base layout (whichever filename Admin #2 chose) to conditionally render `<meta name="csrf-token" content="{{ csrfToken }}">` in the `<head>` ONLY when `csrfToken` is defined in the rendering context. Add the inline `<script>` block from design.md D6 (HTMX configRequest listener) also conditional on `csrfToken` being defined.
- [ ] 7.3 Create `backend/ktor/src/main/kotlin/id/nearyou/app/admin/auth/AdminUserRepository.kt` exposing `fun findActiveByEmail(email: String): AdminUserRow?` — SELECT `id, email, password_hash, totp_secret_encrypted, role, is_active` FROM `admin_users` WHERE `email = $1 AND is_active = TRUE`. Parameterized query.
- [ ] 7.4 Create `backend/ktor/src/main/kotlin/id/nearyou/app/admin/auth/LoginRoute.kt` exposing `fun Routing.adminLogin(...)` extension that wires:
   - `get("/admin/login")` → if authenticated (call.principal != null) → 302 to `/admin/`. Else render `login.peb` with no `errorMessage`.
   - `post("/admin/login")` → call `processLogin(...)`. Body of `processLogin`:
     1. Parse form params: `email`, `password`, `totp`.
     2. SELECT admin_users row via AdminUserRepository.findActiveByEmail(email).
     3. If row is NULL: run sentinel PasswordHasher.verify(password, SENTINEL_HASH) + sentinel TotpVerifier.verify(SENTINEL_SECRET, totp); log audit row with `admin_id = NULL`, `reason = 'email_not_found'`; render `login.peb` with the generic error message; status 200.
     4. If row's `is_active = FALSE`: same sentinel verifies; log audit row with `admin_id = row.id`, `reason = 'inactive_admin'`; same generic-error render.
     5. If row's `totp_secret_encrypted IS NULL`: same sentinel verifies; log audit row with `reason = 'totp_secret_missing'`; same generic-error render.
     6. Run `PasswordHasher.verify(password, row.password_hash)`. If FALSE: run sentinel TotpVerifier.verify; log audit row with `reason = 'password_mismatch'`; same generic-error render.
     7. AesGcmCipher.decrypt(row.totp_secret_encrypted, aesKey). Run TotpVerifier.verify(decryptedSecret, totp). If FALSE: log audit row with `reason = 'totp_mismatch'`; same generic-error render.
     8. Success path: SecureRandom 32-byte session token + 32-byte CSRF token (base64url-encode both); SHA-256 each. SessionRepository.insert(...) returning sessionId. Set `__Host-admin_session` cookie with the plaintext session token value + the attributes per spec admin-login Req 5. AdminAuditLogger.logSuccess(adminId = row.id, sessionId, ip, userAgent). Respond 200 with `HX-Redirect: /admin/` header. (Alternative: a non-HTMX-aware browser will get 200 with empty body — that's fine for the HTMX-driven admin UI.)
- [ ] 7.5 Create `backend/ktor/src/main/kotlin/id/nearyou/app/admin/auth/LogoutRoute.kt` exposing `fun Routing.adminLogout(...)` wiring `post("/admin/logout")` (inside the `authenticate("admin")` block + after the CSRF plugin): SessionRepository.revoke(sessionId from call.principal); set `__Host-admin_session=` (empty value) with Max-Age=0 + same other attributes; AdminAuditLogger.logLogout(adminId); respondRedirect("/admin/login", permanent=false).

## 8. `Application.admin()` cleanup

- [ ] 8.1 In `backend/ktor/src/main/kotlin/id/nearyou/app/admin/AdminModule.kt`: remove the `if (ktorEnv == "production") return` block (lines ~63-71 in current shape) + its bootstrap WARN log line. Remove the `ktorEnv` variable read if it has no other use after the guard removal.
- [ ] 8.2 Remove the bootstrap "admin module mounted: unauthenticated scaffold" WARN log (lines ~73-82 in current shape).
- [ ] 8.3 Remove the per-request WARN interceptor inside `route("/admin")` (lines ~95-122 in current shape — the one that emits `event=admin_request_unauthenticated_scaffold` per `/admin/*` hit).
- [ ] 8.4 Update the head-of-file comment block (lines 14-39): replace the "unauthenticated scaffold + admin-login-argon2-totp will close it" framing with a brief description of the auth gate ("Argon2id password + TOTP + opaque-token session + CSRF" per design.md D10), reference Admin #3 (`admin-login-argon2-totp`) as the lifecycle origin, reference the `admin-login` capability spec for the canonical behavior.
- [ ] 8.5 Install Authentication plugin: `install(Authentication) { admin { /* AdminAuthPlugin config */ } }`.
- [ ] 8.6 Restructure the `routing { route("/admin") { ... } }` block:
   - Move the `staticResources("/static", "admin/static")` mount to the outer scope (no auth, no CSRF — public static assets).
   - Add `Routing.adminLogin(...)` extension to wire the login GET + POST routes (unauthenticated; CSRF-exempt for POST).
   - Wrap the authenticated subtree in `authenticate("admin") { install(AdminCsrfPlugin); adminIndex(); adminLogout(...); /* future admin routes */ }`.
- [ ] 8.7 Verify Detekt + ktlint stay green after the restructure: `./gradlew ktlintCheck detekt`.

## 9. `admin-panel-scaffold` spec amendments (post-archive sync)

- [ ] 9.1 At archive time, the `openspec archive` tool MERGES the `admin-panel-scaffold/spec.md` delta from `openspec/changes/admin-login-argon2-totp/specs/admin-panel-scaffold/spec.md` INTO `openspec/specs/admin-panel-scaffold/spec.md` (renaming the canonical to reflect the MODIFIED + REMOVED operations). Verify via `openspec validate --specs admin-panel-scaffold --strict` after the merge.
- [ ] 9.2 Verify the canonical spec post-merge does NOT contain the removed requirements (Req 4 "Admin subtree does NOT require authentication in this change", Req 5 "Production mount guard via KTOR_ENV check") nor the in-source-comment references to them in [`AdminModule.kt`](../../../backend/ktor/src/main/kotlin/id/nearyou/app/admin/AdminModule.kt) post-task 8.4.

## 10. Audit log writes

- [ ] 10.1 Create `backend/ktor/src/main/kotlin/id/nearyou/app/admin/auth/AdminAuditLogger.kt` exposing:
   - `fun logSuccess(adminId: UUID, sessionId: UUID, ip: String, userAgent: String?)` — INSERT `admin_actions_log` with `action_type = 'admin_login_success'` + `after_state` JSON `{ "session_id": "$sessionId", "expires_at": "...", "last_active_at": "..." }`.
   - `fun logFailure(adminId: UUID?, reason: String, ip: String, userAgent: String?)` — INSERT with `action_type = 'admin_login_failure'`, `admin_id` nullable, `reason` from the enumerated set.
   - `fun logLogout(adminId: UUID, ip: String, userAgent: String?)` — INSERT with `action_type = 'admin_logout'`.
   - `fun logCsrfViolation(adminId: UUID?, reason: String, ip: String, userAgent: String?)` — INSERT with `action_type = 'admin_csrf_violation'`, `target_type = 'csrf'`.
   - All INSERTs via `PreparedStatement`.
- [ ] 10.2 Verify the audit-log JSON never contains plaintext secrets: the `logFailure` method explicitly does NOT take `password` or `totp` parameters (only `reason`, which is from the enumerated set); the `logSuccess` method's `after_state` JSON is built from non-secret session metadata only.
- [ ] 10.3 Wire `AdminAuditLogger` into the four call sites: LoginRoute (success + failure paths), LogoutRoute (logout), AdminCsrfPlugin (csrf violation).

## 11. Tests

- [ ] 11.1 **Login form GET test (admin-login spec Req 1).** `testApplication { application { admin() } }` → `client.get("/admin/login")` SHALL return 200; body contains the three `<input>` fields (email, password, totp); body contains `<form action="/admin/login" method="POST">`; body does NOT contain `<meta name="csrf-token"`.
- [ ] 11.2 **Authenticated GET /admin/login redirect.** Seed a valid session + cookie; `client.get("/admin/login")` → 302; `Location: /admin/`.
- [ ] 11.3 **Login POST happy path (admin-login spec Req 2).** Seed an `admin_users` row via test fixture (Argon2id hash of `"correct-pw"`, AES-GCM-encrypted TOTP secret, `is_active = TRUE`); compute current TOTP code; `client.submitForm(url = "/admin/login", formParameters = parametersOf("email" to listOf("test@nearyou.id"), "password" to listOf("correct-pw"), "totp" to listOf(currentCode)))`. Assertions: status 200; header `HX-Redirect = /admin/`; `Set-Cookie` header for `__Host-admin_session`; cookie value matches regex `^[A-Za-z0-9_-]{43}$`; new `admin_sessions` row exists with correct `admin_id`, `session_token_hash = SHA-256(cookieValue)`, non-null `csrf_token_hash`, `expires_at` ≈ NOW() + 8h, `revoked_at IS NULL`.
- [ ] 11.4 **Login POST failure scenarios (admin-login spec Req 2 + Req 3).** Five sub-tests, each asserting identical response shape: (a) wrong password → 200 + no cookie + no session row + body contains "Email, password, or code is incorrect."; (b) wrong TOTP → same; (c) is_active = FALSE → same; (d) totp_secret_encrypted = NULL → same; (e) email not found → same. Cross-test assertion: response bodies of the five sub-tests SHALL be byte-identical (modulo a session-irrelevant CSRF token that's not present on the login page).
- [ ] 11.5 **Login POST timing equalization (admin-login spec Req 3).** Statistical test: run 10 "email not found" attempts + 10 "wrong password" attempts; assert mean wall time difference ≤ 100ms. Use `kotlin.time.measureTime`.
- [ ] 11.6 **Login POST audit row (admin-login spec Req 4).** For each failure mode (a-e) + success: SELECT * FROM admin_actions_log ORDER BY created_at DESC LIMIT 1 after the POST; assert `action_type`, `admin_id`, `reason` per the spec scenarios. Cross-assertion: no `admin_actions_log` column value contains the substring of the submitted password or TOTP.
- [ ] 11.7 **Cookie format (admin-login spec Req 5).** Parse the `Set-Cookie` header from a successful login: assert `Secure`, `HttpOnly`, `SameSite=Strict`, `Path=/` attributes present; assert no `Domain=` attribute; assert `Max-Age` within ±60s of 28800.
- [ ] 11.8 **Session validation (admin-login spec Req 6).** Five sub-tests against `client.get("/admin/")`: (a) no cookie → 302 to `/admin/login`; (b) valid session → 200 + `admin_sessions.last_active_at` refreshed; (c) expired session (`expires_at < NOW()` via direct DB update) → 302; (d) idle session (`last_active_at` 31min ago via direct DB update) → 302; (e) revoked session (`revoked_at = NOW()`) → 302.
- [ ] 11.9 **CSRF validation (admin-login spec Req 6 + 7).** Five sub-tests against `client.post("/admin/logout")` with valid session cookie: (a) no CSRF header/form → 403 + `admin_csrf_violation` row with reason `missing_token`; (b) wrong `X-CSRF-Token` header → 403 + `header_mismatch`; (c) correct `X-CSRF-Token` header → 302 to `/admin/login`; (d) correct `_csrf` form field (no header) → 302; (e) header takes precedence over form field (correct header + wrong form → 302).
- [ ] 11.10 **CSRF exemption for /admin/login POST (admin-login spec Req 6).** POST `/admin/login` with no `X-CSRF-Token` header → not rejected by CSRF middleware (reaches login handler; produces the normal credentials-based response).
- [ ] 11.11 **GET is not gated by CSRF (admin-login spec Req 6).** GET `/admin/` with valid session + no `X-CSRF-Token` header → 200 (CSRF only gates state-changing methods).
- [ ] 11.12 **Logout (admin-login spec Req 8).** Valid session + valid CSRF → POST `/admin/logout` → 302 to `/admin/login` + `Set-Cookie: __Host-admin_session=` (empty) with `Max-Age=0`; `admin_sessions.revoked_at` ≈ NOW(); `admin_actions_log` row with `action_type = 'admin_logout'`.
- [ ] 11.13 **Logout idempotency (admin-login spec Req 8).** Pre-revoke a session in DB; POST `/admin/logout` with that session's cookie → 302 to `/admin/login` from the session middleware (logout handler not reached; no second audit row).
- [ ] 11.14 **AES-256-GCM helper (admin-login spec Req 9).** Round-trip on fixed secret + key returns original; tampered tag → AEADBadTagException; tampered ciphertext → AEADBadTagException; ciphertext length ≥ 28 + plaintext length.
- [ ] 11.15 **Authenticated layout CSRF meta + JS hook (admin-login spec Req 10 + admin-panel-scaffold MODIFIED Req 2).** Authenticated `client.get("/admin/")` body contains `<meta name="csrf-token" content="...">`; the `content` attribute value's SHA-256 equals the session's `csrf_token_hash`; body contains an `htmx:configRequest` listener with literal substring `evt.detail.headers['X-CSRF-Token']` (or double-quoted variant). Unauthenticated `client.get("/admin/login")` body contains NEITHER.
- [ ] 11.16 **Constant-time compare audit (admin-login spec Req 11).** Source-code scan: grep `backend/ktor/src/main/kotlin/id/nearyou/app/admin/auth/` for `Arrays.equals(` / `String.equals(` / unqualified `==` on `byte[]` / `String` typed locals named `*token*` / `*hash*`. Assert all findings are either in test fixtures (`/test/`) or the canonical `MessageDigest.isEqual` pattern.
- [ ] 11.17 **Argon2id benchmark + parameter floor (admin-login spec Req 12).** Mean verify time within [300, 2000] ms; constants meet OWASP floor (memory ≥ 15 MiB, iter ≥ 2, parallelism = 1).
- [ ] 11.18 **TOTP verifier RFC 6238 (admin-login spec Req 13).** Parameter constants assertion + the four skew scenarios (current step / -30s / +30s / -60s).
- [ ] 11.19 **admin-panel-scaffold MODIFIED Req 1 — auth redirect.** Unauthenticated `GET /admin/` → 302 to `/admin/login`; authenticated → 200.
- [ ] 11.20 **admin-panel-scaffold MODIFIED Req 1 — 404 on unmapped admin routes.** `GET /admin/nonexistent-page` (with and without session) → 404.
- [ ] 11.21 **admin-panel-scaffold MODIFIED Req 1 — 405 on POST to bare index.** `POST /admin/` → 405 Method Not Allowed.
- [ ] 11.22 **admin-panel-scaffold MODIFIED Req 2 — login page extends base layout without CSRF block.** Unauthenticated `GET /admin/login` body contains header / nav-stub / footer markup; does NOT contain `<meta name="csrf-token"` or `htmx:configRequest`.
- [ ] 11.23 **admin-panel-scaffold REMOVED Req 4 — no per-request WARN log.** Test using log-capture utility: `GET /admin/` (authenticated) does NOT emit any WARN log line containing the substring `unauthenticated scaffold`.
- [ ] 11.24 **admin-panel-scaffold REMOVED Req 5 — production mount no longer gated.** Test with `MapApplicationConfig("ktor.environment" to "production")`: `GET /admin/login` SHALL return 200 (route mounted in production, just behind the auth gate); the previous expectation of 404 in production no longer holds.

## 12. Documentation

- [ ] 12.1 Update the head-of-file comment in `AdminModule.kt` (covered by task 8.4 — listed here for the documentation-trail).
- [ ] 12.2 Update [`docs/10-Setup-Checklist.md`](../../../docs/10-Setup-Checklist.md) to record: (a) the `staging-admin-totp-secret-aes-key` slot provisioning status; (b) the staging-test admin row provisioning status; (c) the production-side provisioning as a deferred check item gated on production bootstrap.
- [ ] 12.3 Add Version Pinning Decisions Log rows to [`docs/09-Versions.md`](../../../docs/09-Versions.md) for `com.password4j:password4j` + `dev.samstevens.totp:totp` at archive time. Include: pinned-on date, rationale (Admin #3 substrate selection per design.md D1 + re-check outcome from task 1.1), next review (2026-Q3).
- [ ] 12.4 Reconciliation pass (per `/next-change` Phase B step 3): the proposal claims about cookie format + session timeout + audit-log action types + library substrate were diffed against docs/04, 05, 07, and admin-schema spec at proposal authoring. Verify no divergence emerged during implementation; if any did, classify per the rule (fix proposal / log stale-doc / surface ambiguous).
- [ ] 12.5 If reconciliation surfaces a stale-doc divergence (per `/next-change` Phase B bucket (b)), add a FOLLOW_UPS entry documenting the stale-doc finding + the proposed update.

## 13. Pre-archive smoke

- [ ] 13.1 Author `dev/scripts/smoke-admin-login-argon2-totp.sh` — bash script that: (a) hits `GET https://api-staging.nearyou.id/admin/login` with `curl -i` and asserts 200 + login form HTML in response; (b) POSTs valid credentials + current TOTP code (sourced from a local `oathtool --totp -b <STAGING_TEST_TOTP_SECRET>` invocation) and asserts 200 + `HX-Redirect: /admin/` header + `Set-Cookie: __Host-admin_session` header; (c) extracts the session cookie + the CSRF token from the next `GET /admin/` response (HTML parse), then POSTs `/admin/logout` with cookie + CSRF header, asserts 302 to `/admin/login`; (d) GETs `/admin/` with the now-revoked cookie, asserts 302 to `/admin/login`.
- [ ] 13.2 Provision the staging-test admin row via the bootstrap script (task 1.7 + 1.8) — store the staging-test password + base32 TOTP secret in a secure operator-only location (NOT in this PR).
- [ ] 13.3 Trigger the staging deploy: `gh workflow run deploy-staging.yml --ref admin-login-argon2-totp` + poll the deploy run via `gh run watch` until success.
- [ ] 13.4 Execute the smoke script against the staging deploy: `STAGING_TEST_EMAIL=... STAGING_TEST_PASSWORD=... STAGING_TEST_TOTP_SECRET=... dev/scripts/smoke-admin-login-argon2-totp.sh`. All assertions SHALL pass.
- [ ] 13.5 If the smoke fails, fix on the change branch + re-deploy + re-smoke. Do NOT proceed to `/opsx:archive` until the smoke passes.
- [ ] 13.6 Document the smoke outcome in the PR body (per [`openspec/project.md`](../../project.md) § "PR title and body MUST stay current at every phase boundary"). Tick the Section 6-equivalent rows in `tasks.md` (Section 13 here).
