package id.nearyou.app.admin

import id.nearyou.app.admin.actionslog.AdminActionsLogRepository
import id.nearyou.app.admin.auth.AdminAuditLogger
import id.nearyou.app.admin.auth.AdminAuthProvider
import id.nearyou.app.admin.auth.AdminLoginRoutes
import id.nearyou.app.admin.auth.AdminLogoutRoute
import id.nearyou.app.admin.auth.AdminUserRepository
import id.nearyou.app.admin.auth.SessionRepository
import id.nearyou.app.admin.auth.adminAuth
import id.nearyou.app.admin.blockregistry.AdminBlockRegistryRepository
import id.nearyou.app.admin.chatredaction.ChatRedactionRepository
import id.nearyou.app.admin.featureflags.FeatureFlagService
import id.nearyou.app.admin.featureflags.FeatureFlagToggleRateLimiter
import id.nearyou.app.admin.moderation.UserModerationRepository
import id.nearyou.app.admin.privacyflips.AdminPrivacyFlipsRepository
import id.nearyou.app.admin.ratelimit.DestructiveActionRateLimiter
import id.nearyou.app.admin.rejectedidentifiers.AdminRejectedIdentifiersRepository
import id.nearyou.app.admin.reportqueue.ReportQueueRepository
import id.nearyou.app.admin.reportqueue.ReportResolutionRepository
import id.nearyou.app.admin.routes.AdminIndexStatsRepository
import id.nearyou.app.admin.routes.AdminLayout
import id.nearyou.app.admin.routes.adminActionsLog
import id.nearyou.app.admin.routes.adminBlockRegistry
import id.nearyou.app.admin.routes.adminChatRedaction
import id.nearyou.app.admin.routes.adminFeatureFlags
import id.nearyou.app.admin.routes.adminIndex
import id.nearyou.app.admin.routes.adminPrivacyFlips
import id.nearyou.app.admin.routes.adminRejectedIdentifiers
import id.nearyou.app.admin.routes.adminReportQueue
import id.nearyou.app.admin.routes.adminReportResolution
import id.nearyou.app.admin.routes.adminUserModeration
import id.nearyou.app.admin.usermanagement.UserProfileRepository
import id.nearyou.app.infra.remoteconfig.NoOpRemoteConfigPublisher
import id.nearyou.app.infra.remoteconfig.RemoteConfigPublisher
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.authentication
import io.ktor.server.http.content.staticResources
import io.ktor.server.pebble.Pebble
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.pebbletemplates.pebble.loader.ClasspathLoader
import java.time.Instant
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
//   - environmentName: deployment env name (`ktor.environment`) — rendered
//                      uppercased as the layout top-bar env chip
//                      (admin-mockup-parity design.md D6).
// ---------------------------------------------------------------------------
fun Application.admin(
    dataSource: DataSource,
    aesKeyProvider: () -> ByteArray,
    csrfHmacKeyProvider: () -> ByteArray,
    environmentName: String,
    // Write seam for the Feature Flag Admin panel (admin-feature-flags). Production
    // binds the REST-backed publisher over the firebase-admin-sa credentials; test +
    // unconfigured deploys bind NoOpRemoteConfigPublisher (panel renders read-only) —
    // also the default, so existing admin wiring/tests that omit it stay read-only.
    remoteConfigPublisher: RemoteConfigPublisher = NoOpRemoteConfigPublisher,
    // Request-time evaluation clock for the privacy-flip monitor's IN_WINDOW /
    // OVERDUE classification (admin-privacy-flip-monitor design D3). Production
    // uses the system clock; tests inject a fixed instant for a deterministic
    // classification boundary.
    privacyFlipsClock: () -> Instant = Instant::now,
) {
    val adminUserRepository = AdminUserRepository(dataSource)
    val sessionRepository = SessionRepository(dataSource)
    val auditLogger = AdminAuditLogger(dataSource)
    val actionsLogRepository = AdminActionsLogRepository(dataSource)
    val rejectedIdentifiersRepository = AdminRejectedIdentifiersRepository(dataSource)
    val blockRegistryRepository = AdminBlockRegistryRepository(dataSource)
    val privacyFlipsRepository = AdminPrivacyFlipsRepository(dataSource)
    val reportQueueRepository = ReportQueueRepository(dataSource)
    val destructiveActionRateLimiter = DestructiveActionRateLimiter(dataSource)
    val userModerationRepository =
        UserModerationRepository(dataSource, auditLogger, destructiveActionRateLimiter)
    val userProfileRepository = UserProfileRepository(dataSource)
    val reportResolutionRepository =
        ReportResolutionRepository(
            dataSource,
            auditLogger,
            userModerationRepository,
            destructiveActionRateLimiter,
        )
    val chatRedactionRepository =
        ChatRedactionRepository(dataSource, auditLogger, destructiveActionRateLimiter)
    val loginRoutes =
        AdminLoginRoutes(
            adminUserRepository = adminUserRepository,
            sessionRepository = sessionRepository,
            auditLogger = auditLogger,
            aesKeyProvider = aesKeyProvider,
            csrfHmacKeyProvider = csrfHmacKeyProvider,
        )
    val logoutRoute = AdminLogoutRoute(sessionRepository, auditLogger)
    // ONE idle-timeout value feeds BOTH the auth provider (enforcement) and
    // the layout's identity-box session line (display) — they must never
    // drift, or the rendered deadline lies about the real cutoff.
    val sessionIdleTimeout = AdminAuthProvider.DEFAULT_IDLE_TIMEOUT
    val layout = AdminLayout(csrfHmacKeyProvider, environmentName, sessionIdleTimeout)
    val indexStatsRepository = AdminIndexStatsRepository(dataSource)
    val featureFlagRateLimiter = FeatureFlagToggleRateLimiter(dataSource)
    val featureFlagService =
        FeatureFlagService(
            publisher = remoteConfigPublisher,
            rateLimiter = featureFlagRateLimiter,
            auditLogger = auditLogger,
            dataSource = dataSource,
        )

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
            this.idleTimeout = sessionIdleTimeout
        }
    }

    routing {
        route("/admin") {
            // Bare /admin (no trailing slash) → 302 /admin/ (admin-mockup-parity
            // design.md D1). Outside the authenticate block: the redirect target
            // applies the session gate, so this response discloses nothing.
            get("") {
                call.respondRedirect("/admin/", permanent = false)
            }

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
                adminIndex(layout, indexStatsRepository)
                adminActionsLog(actionsLogRepository, layout)
                adminRejectedIdentifiers(rejectedIdentifiersRepository, layout)
                adminBlockRegistry(blockRegistryRepository, layout)
                adminPrivacyFlips(privacyFlipsRepository, layout, privacyFlipsClock)
                adminReportQueue(reportQueueRepository, layout)
                adminReportResolution(
                    reportResolutionRepository,
                    reportQueueRepository,
                    auditLogger,
                    layout,
                )
                adminUserModeration(
                    userModerationRepository,
                    userProfileRepository,
                    destructiveActionRateLimiter,
                    auditLogger,
                    layout,
                )
                adminChatRedaction(chatRedactionRepository, auditLogger, layout)
                adminFeatureFlags(featureFlagService, auditLogger, layout)
            }
        }
    }
}

const val ADMIN_AUTH_NAME = "admin"
