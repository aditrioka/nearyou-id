package id.nearyou.app.admin

import id.nearyou.app.admin.auth.AdminAuditLogger
import id.nearyou.app.admin.auth.AdminLoginRoutes
import id.nearyou.app.admin.auth.AdminLogoutRoute
import id.nearyou.app.admin.auth.AdminUserRepository
import id.nearyou.app.admin.auth.SessionRepository
import id.nearyou.app.admin.auth.adminAuth
import id.nearyou.app.admin.routes.adminIndex
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.authentication
import io.ktor.server.http.content.staticResources
import io.ktor.server.pebble.Pebble
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.pebbletemplates.pebble.loader.ClasspathLoader
import javax.sql.DataSource

// ---------------------------------------------------------------------------
// admin-login-argon2-totp — Admin #3 auth gate.
//
// This module mounts the /admin/ route subtree behind a cookie-based session
// + CSRF gate (Argon2id password + TOTP + AES-GCM-decrypted-secret →
// __Host-admin_session cookie with deterministic CSRF derivation from the
// session token). The auth gate replaces the Admin #2 KTOR_ENV mount guard
// + per-request WARN log — those scaffold-validation crutches are gone in
// this change.
//
// Route layout:
//   /admin/login       — GET (form) + POST (auth) — UNAUTHENTICATED
//   /admin/logout      — POST — AUTHENTICATED + CSRF-required
//   /admin/static/...  — public vendored assets (HTMX)
//   /admin/...         — ALL OTHER routes — AUTHENTICATED + CSRF-required
//
// Dependencies injected from Application.kt:
//   - dataSource:      backing repositories (admin_users, admin_sessions,
//                      admin_actions_log)
//   - aesKeyProvider:  resolves the AES-256 key from GCP Secret Manager
//                      slot `admin-totp-secret-aes-key` (env-namespaced
//                      via secretKey(env, name)). Lazy — only invoked at
//                      login-verify time.
// ---------------------------------------------------------------------------
fun Application.admin(
    dataSource: DataSource,
    aesKeyProvider: () -> ByteArray,
    csrfHmacKeyProvider: () -> ByteArray,
) {
    val adminUserRepository = AdminUserRepository(dataSource)
    val sessionRepository = SessionRepository(dataSource)
    val auditLogger = AdminAuditLogger(dataSource)
    val loginRoutes =
        AdminLoginRoutes(
            adminUserRepository = adminUserRepository,
            sessionRepository = sessionRepository,
            auditLogger = auditLogger,
            aesKeyProvider = aesKeyProvider,
            csrfHmacKeyProvider = csrfHmacKeyProvider,
        )
    val logoutRoute = AdminLogoutRoute(sessionRepository, auditLogger)

    install(Pebble) {
        loader(
            ClasspathLoader().apply {
                prefix = "templates/admin"
            },
        )
    }

    // Use `authentication { }` (NOT `install(Authentication) { }`): the main
    // Application.module() already installs the Authentication plugin for the
    // user-JWT provider (AuthPlugin.installAuth), so a second `install` would
    // throw DuplicatePluginException at module boot. `authentication { }` does
    // `pluginOrNull(Authentication)?.configure(block) ?: install(...)` — it
    // ADDS the admin session provider to the existing plugin in production,
    // and installs fresh when admin() is wired standalone (tests).
    authentication {
        adminAuth(ADMIN_AUTH_NAME) {
            this.sessionRepository = sessionRepository
            this.adminUserRepository = adminUserRepository
        }
    }

    routing {
        route("/admin") {
            // /admin/login (GET + POST) — outside the authenticate block.
            // The POST endpoint is CSRF-exempt per design.md D7.
            loginRoutes.install(this)

            // Static assets served from the classpath. Public path-traversal-
            // resistant (Ktor's staticResources uses getResource() which
            // does not resolve `..` segments). Inner path /static + outer
            // route /admin combine to URL /admin/static/<file>.
            staticResources("/static", "admin/static")

            authenticate(ADMIN_AUTH_NAME) {
                // CSRF gating is performed PER STATE-CHANGING HANDLER via an
                // explicit `AdminCsrfGate.validateCsrf(call, auditLogger)`
                // call at the top of each POST/PUT/PATCH/DELETE handler
                // (see AdminLogoutRoute). A route-pipeline `intercept` was
                // tried first but leaked across sibling routes (the
                // CSRF-exempt POST /admin/login) and mis-ordered against the
                // Authentication phase; the explicit per-handler call is the
                // predictable contract. The shared validateCsrf function IS
                // the "CSRF middleware" — every state-changing admin handler
                // MUST call it first (GET/HEAD/OPTIONS short-circuit to true).
                // Unmapped state-changing paths (e.g. POST /admin/) correctly
                // surface as routing-layer 405s because no handler runs.
                logoutRoute.install(this)
                adminIndex(csrfHmacKeyProvider)
            }
        }
    }
}

const val ADMIN_AUTH_NAME = "admin"
