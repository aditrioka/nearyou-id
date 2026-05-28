## Why

Admin #2 ([`admin-panel-ktor-htmx-bootstrap`, PR #115](https://github.com/aditrioka/nearyou-id/pull/115)) shipped the `/admin/*` route subtree on the main `:backend:ktor` deployable with an **intentionally unauthenticated posture** — a per-request WARN log + `KTOR_ENV != "production"` mount guard make the scaffold structurally unreachable in production but force the project to ship the auth gate before any DB-touching admin feature can land. Admin #1 ([`admin-schema-bootstrap`, PR #107](https://github.com/aditrioka/nearyou-id/pull/107)) shipped the `admin_users` / `admin_sessions` / `admin_actions_log` tables (with `csrf_token_hash NOT NULL` + `password_hash` Argon2id-typed + `totp_secret_encrypted` AES-256-typed) so the schema layer is ready. This change closes that gap: it ships the first end-to-end admin authentication path — Argon2id password verification + TOTP RFC 6238 verification + opaque-token session cookie + CSRF middleware + audit logging — so subsequent admin features (Admin #4 audit-log viewer, Admin #5 suspend/unban action, and every other operationally-load-bearing admin write) can sit behind an authenticated session. This is **Admin #3** in the [`openspec/project.md`](../../project.md) § Mobile + Admin Scaffolding Priority menu and the spec-source roadmap item is [`docs/08-Roadmap-Risk.md`](../../../docs/08-Roadmap-Risk.md) Phase 3.5 §3 ("Admin login via `admin_users` + Argon2id password + TOTP mandatory (solo admin period)").

## What Changes

**New runtime auth mechanics (ADDED in the `admin-login` capability):**

- **Login routes.** `GET /admin/login` renders an unauthenticated login form (email + password + 6-digit TOTP code) via Pebble template extending the existing base layout. `POST /admin/login` verifies credentials and, on success, creates an `admin_sessions` row, sets a `__Host-admin_session` cookie, and returns `HX-Redirect: /admin/`. All failure paths return the same generic error message ("Email, password, or code is incorrect.") with the same 200 OK shape (no enumeration).
- **Logout route.** `POST /admin/logout` revokes the current session (`revoked_at = NOW()`), clears the cookie, redirects to `/admin/login`. HTMX-friendly POST.
- **Session cookie + middleware.** `__Host-admin_session` cookie carrying a base64url-encoded opaque 256-bit random token (no Domain attribute per `__Host-` prefix RFC 6265bis, `Secure`, `HttpOnly`, `SameSite=Strict`, `Path=/`). SHA-256 at rest in `admin_sessions.session_token_hash`. Middleware validates the cookie by SHA-256 hash lookup, checks `revoked_at IS NULL` + `expires_at > NOW()` + `last_active_at > NOW() - INTERVAL '30 minutes'` (sliding idle timeout per [`docs/05-Implementation.md` § Admin Users Schema](../../../docs/05-Implementation.md) + `expires_at` absolute cap of 8h for prudence), refreshes `last_active_at` on success, redirects to `/admin/login` (302) on failure.
- **CSRF middleware.** State-changing requests (POST/PUT/PATCH/DELETE under `/admin/*` except `/admin/login`) MUST include `X-CSRF-Token` header matching the session's `csrf_token_hash` (SHA-256 of submitted token, constant-time compare). Mismatch → 403 + `admin_csrf_violation` audit row. CSRF token surfaced via `<meta name="csrf-token">` tag in the authenticated layout + a small inline JS hook (`htmx:configRequest` listener) that auto-adds the header on every HTMX request.
- **Argon2id password verification** via the [`com.password4j:password4j`](https://github.com/Password4j/password4j) library (subject to pre-implementation library re-check per [`openspec/project.md`](../../project.md) § Pre-implementation library re-check). OWASP-recommended parameters (≥15 MiB memory, ≥2 iterations, parallelism = 1) tuned in `design.md` to target ≥500ms verify time on the local dev machine. Library handles constant-time compare internally.
- **TOTP RFC 6238 verification** via the [`dev.samstevens.totp:totp`](https://github.com/samdjstevens/java-totp) library (subject to pre-implementation library re-check). 6-digit codes, 30-second window, SHA-1 algorithm (Google Authenticator default), ±1 step (90s total) tolerance for clock skew.
- **AES-256-GCM decryption** of `admin_users.totp_secret_encrypted` via JCA built-in `javax.crypto.Cipher` (`AES/GCM/NoPadding`). Key sourced via `secretKey(env, "admin-totp-secret-aes-key")` per the project's env-namespaced secret helper convention. Encryption format: `BYTEA = nonce (12 bytes) || ciphertext || auth_tag (16 bytes)`. Inline helper in admin module (single use case; refactor to `:core:data` if a second use case emerges).
- **No-enumeration login.** All failure paths (wrong email, wrong password, wrong TOTP, `is_active = FALSE`, `totp_secret_encrypted IS NULL`) return identical responses. Timing-side-channel mitigation: always run Argon2id verify (against a fixed sentinel hash if email not found) so login attempts take roughly-equal wall time regardless of which gate failed.
- **Audit logging.** `admin_actions_log` insert on each of: `admin_login_success`, `admin_login_failure` (with `reason` ∈ `{password_mismatch, totp_mismatch, inactive_admin, totp_secret_missing}`, NEVER the submitted password/TOTP value), `admin_logout`, `admin_csrf_violation`. `admin_id` is always the matched admin's UUID — the V16 schema's `admin_actions_log.admin_id NOT NULL` invariant forbids unowned audit rows. The `email_not_found` failure mode (the requester's email did not resolve to any admin row) does NOT write to `admin_actions_log`; it is captured at the application's structured INFO logger only (same Sentry + Cloud Logging surface, just not in-app surfaced to the future `admin-actions-log-viewer`). Rationale: `admin_actions_log` is the in-app audit surface for *admin-actor* actions; an attacker probing for valid admin emails is not an admin actor. `ip` from `call.clientIp` (per `ClientIpExtractor`); `user_agent` from request header.
- **Constant-time hash comparison** for session-token and CSRF-token validation via `java.security.MessageDigest.isEqual()`. Password and TOTP verifications use library APIs that handle constant-time internally. The admin auth path SHALL NOT use raw `==` / `equals` / `String.equals` on any secret-derived value.

**MODIFIED `admin-panel-scaffold` capability deltas:**

- **REMOVE Requirement `Admin subtree does NOT require authentication in this change`** — superseded by the Admin #3 auth gate. The per-request WARN log line + the `admin-login-argon2-totp` marker disappear from `/admin/*` request logs — its absence is positively observable as the gate-landed signal.
- **REMOVE Requirement `Production mount guard via KTOR_ENV check`** — the auth gate replaces the platform-level mount guard. The opaque-token cookie + CSRF + Argon2id + TOTP combination makes `/admin/*` production-safe regardless of `KTOR_ENV` value. (Network-layer defense via IAP / Cloud Armor + the eventual separate-Cloud-Run-service migration to `admin.nearyou.id` is the [Phase 3.5 §2 deployment task](../../../docs/08-Roadmap-Risk.md), tracked separately.)
- **MODIFY Requirement `Admin routes mount under /admin/* namespace`** — the `/admin/` index now requires an authenticated session; the unauthenticated-200 scenario flips to a 302 redirect to `/admin/login`. New scenarios: authenticated GET → 200; non-authenticated GET → 302 to `/admin/login`.
- **MODIFY Requirement `Shared base layout template exists and is extended by admin pages`** — the authenticated layout additionally renders a `<meta name="csrf-token">` tag carrying the per-session CSRF token + the inline JS hook for HTMX. The unauthenticated `/admin/login` page extends the same base layout but without the CSRF meta tag (no session, no token).

**Out of scope (deferred to focused follow-ups):**

- Initial admin user bootstrap via manual SQL INSERT (Oka's row) — documented procedure in `design.md`; no admin self-signup endpoint, no admin enrollment UI in this change.
- TOTP secret enrollment UI / QR-code generation — bootstrap path encodes base32 secret + manually configures the authenticator app.
- WebAuthn enrollment + login (`admin-webauthn-enrollment-and-login`, before the second-admin hire per [`docs/07-Operations.md`](../../../docs/07-Operations.md) § Multi-admin period).
- Per-IP login rate limiting (`admin-login-rate-limit`, once Layer 1 IP-axis rate-limit infrastructure ships per [Phase 1 §24](../../../docs/08-Roadmap-Risk.md)).
- Privilege-escalation cookie rotation (no admin-role-change actions land in #3; rotation logic ships when Admin #5+ adds role mutations).
- `admin_app` DB-role connection separation (`admin-module-admin-app-role-swap`, tied to the Phase 3.5 separate-Cloud-Run-service deployment task; staging `admin_app` role already exists per `admin-app-revoke-staging-and-prod` FOLLOW_UP entry).
- Sentinel `system` admin user row (`system-actor-and-worker-audit-rows`, separate concern from real admin login).
- Admin self-service password change (`admin-self-service-password-change`).
- WebAuthn challenge cleanup worker (per [Phase 3.5 §13](../../../docs/08-Roadmap-Risk.md)).
- Admin module migration to `admin.nearyou.id` separate Cloud Run service + IAP/Cloud Armor (Phase 3.5 deployment task #2).
- TOTP backup codes (common UX but not in docs).
- Session listing / "log me out everywhere" self-service UI.

## Capabilities

### New Capabilities

- `admin-login`: Runtime admin authentication mechanics — Argon2id password verification, TOTP RFC 6238 verification, AES-256-GCM decryption of the stored TOTP secret, opaque-token session cookie issuance + validation with sliding 30-min idle timeout, CSRF token issuance + validation on every state-changing request, no-enumeration login response shape, audit logging on `admin_login_success` / `admin_login_failure` / `admin_logout` / `admin_csrf_violation`. Owns the `GET /admin/login`, `POST /admin/login`, `POST /admin/logout` routes plus the session and CSRF middleware that wrap all other `/admin/*` routes. Builds on the schema-layer enforcement in [`admin-schema`](../../specs/admin-schema/spec.md) (the `csrf_token_hash NOT NULL` invariant + the FK + immutability shape).

### Modified Capabilities

- `admin-panel-scaffold`: Drops the deliberately-unauthenticated-subtree posture (Requirement 4) and the `KTOR_ENV` production-mount guard (Requirement 5). The `/admin/` index now requires an authenticated session (Requirement 1 amended). The shared base layout renders the CSRF meta tag + HTMX JS hook when serving authenticated pages (Requirement 2 amended). The HTMX-availability requirement (Requirement 3) is unchanged.

## Impact

**Affected code.**

- `backend/ktor/src/main/kotlin/id/nearyou/app/admin/AdminModule.kt` — `Application.admin()` cleanup per the in-source TODO at lines 14-39: remove the `KTOR_ENV != "production"` mount guard, remove the per-request WARN interceptor, install the Authentication + CSRF plugins, wrap `/admin/*` (except `/admin/login`, `/admin/logout`, `/admin/static/*`) in `authenticate("admin")`, update the head-of-file comment block.
- `backend/ktor/src/main/kotlin/id/nearyou/app/admin/auth/` (new directory) — `AdminAuthPlugin.kt` (session middleware), `AdminCsrfPlugin.kt` (CSRF middleware), `LoginRoute.kt` (GET + POST handlers), `LogoutRoute.kt` (POST handler), `PasswordHasher.kt` (Password4j wrapper), `TotpVerifier.kt` (samstevens java-totp wrapper), `AesGcmCipher.kt` (JCA wrapper for the AES-256-GCM decrypt), `AdminAuditLogger.kt` (audit row insert helper), `SessionRepository.kt` (admin_sessions JDBC queries), `AdminUserRepository.kt` (admin_users JDBC queries).
- `backend/ktor/src/main/resources/templates/admin/` — `login.peb` template, `_layout.peb` updates (CSRF meta tag + HTMX JS hook in the authenticated section).
- `backend/ktor/src/test/kotlin/id/nearyou/app/admin/` — unit + integration test classes covering the scenarios enumerated in `tasks.md` Section 11.
- `gradle/libs.versions.toml` — pin `com.password4j:password4j` + `dev.samstevens.totp:totp` + activate `io.ktor:ktor-server-sessions-jvm` (BOM-resolved via shared `ktor = "3.4.1"` variable, same pattern as `ktor-serverPebble` in Admin #2). Each new pin requires a Version Pinning Decisions Log row in [`docs/09-Versions.md`](../../../docs/09-Versions.md) at archive time.
- `backend/ktor/build.gradle.kts` — wire the new library coordinates.

**Affected APIs (HTTP surface).**

- `GET /admin/login` — NEW. Unauthenticated.
- `POST /admin/login` — NEW. Unauthenticated; pre-session (no CSRF requirement on this single endpoint; rationale in `design.md` D7).
- `POST /admin/logout` — NEW. Authenticated + CSRF-required.
- `GET /admin/` — MODIFIED. Now authenticated + returns 302 to `/admin/login` if no valid session.
- `GET /admin/nonexistent-page` — MODIFIED. Still 404 (route subtree exists), but the 404 is returned regardless of auth state (admin module exists at the platform level).
- `GET /admin/static/*` — UNCHANGED. Public path-traversal-resistant static asset serving (HTMX vendored JS).

**Affected dependencies (`gradle/libs.versions.toml`).**

- `com.password4j:password4j` — NEW PIN, version per pre-impl re-check (currently 1.8.4 per propose-time WebSearch on 2026-05-28).
- `dev.samstevens.totp:totp` — NEW PIN, version per pre-impl re-check (currently 1.7.1 per propose-time WebSearch on 2026-05-28).
- `io.ktor:ktor-server-sessions-jvm` — ACTIVATED (BOM-resolved via shared `ktor = "3.4.1"`; same shape as `ktor-serverPebble` in Admin #2 / [PR #115](https://github.com/aditrioka/nearyou-id/pull/115)).

**Affected secrets (GCP Secret Manager).**

- `admin-totp-secret-aes-key` — NEW slot (production). 256-bit AES key for `totp_secret_encrypted` decryption. Provision per Pre-Phase 1 secret-slot procedure (slot creation + `secretAccessor` IAM grant to the Cloud Run runtime SA).
- `staging-admin-totp-secret-aes-key` — NEW slot (staging). Same shape.
- `admin-session-cookie-signing-key` — RESERVED per Pre-Phase 1 §40, NOT consumed in this change. Reserved for the optional future signed-cookie mode; opaque-token + SHA-256-at-rest is the canonical path here.

**Affected database tables (runtime, not schema):**

- `admin_users` — first runtime SELECTs: `WHERE email = $1 AND is_active = TRUE`. No INSERT/UPDATE in this change.
- `admin_sessions` — first runtime INSERTs (one row per successful login). UPDATEs on `last_active_at` (per authenticated request) and `revoked_at` (per logout). The schema-layer `csrf_token_hash NOT NULL` invariant is now runtime-exercised.
- `admin_actions_log` — first runtime INSERTs. Four action types added to the catalog: `admin_login_success`, `admin_login_failure`, `admin_logout`, `admin_csrf_violation`.

**Affected operational state.**

- The unauthenticated-scaffold WARN log + `admin-login-argon2-totp` search marker disappear from `/admin/*` request logs after this change ships. Their absence is positively observable as the gate-landed signal in Sentry.
- The first admin user row (Oka) must be bootstrapped via manual SQL INSERT against the live database with a pre-hashed Argon2id password + a pre-encrypted TOTP secret. Documented procedure in `design.md` D11.
- Pre-archive staging smoke (per [`openspec/project.md`](../../project.md) § Staging deploy timing) hits the live `/admin/login` endpoint against a known-good test admin provisioned in staging.

**Downstream unblocks.**

- Admin #4 (`admin-actions-log-viewer`) can now sit behind the auth gate + read `admin_actions_log` rows including the four action types this change introduces.
- Admin #5 (`admin-suspend-unban-user-action`) can now write admin-triggered suspensions with proper `admin_id` provenance.
- The `system-actor-and-worker-audit-rows` FOLLOW_UP can resolve the design tension around the sentinel admin row (it now coexists with real admin users, not as the only `admin_users` row). Note: that follow-up is OPTIONAL for Admin #3 — this change resolves the `admin_actions_log.admin_id NOT NULL` constraint by routing the only no-admin-context failure mode (`email_not_found`) to the structured INFO logger instead of `admin_actions_log`, so the sentinel admin row is no longer Admin #3's blocking dependency.
- The `admin-app-revoke-staging-and-prod` FOLLOW_UP's production-side work becomes pre-requisite for the eventual `admin-module-admin-app-role-swap` follow-up (which tightens the runtime DB role).
