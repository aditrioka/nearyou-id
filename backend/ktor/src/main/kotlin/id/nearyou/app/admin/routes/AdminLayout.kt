package id.nearyou.app.admin.routes

import id.nearyou.app.admin.auth.AdminAuthProvider
import id.nearyou.app.admin.auth.AdminPrincipal
import id.nearyou.app.admin.auth.HashUtil
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.principal
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Builds the `layout.peb` shell model shared by every full-page admin render
 * (admin-mockup-parity design.md D5–D8):
 *
 *  - `csrfToken` — derived from the session cookie exactly as the routes did
 *    individually pre-parity (HMAC over the session token; design.md D6 of
 *    `admin-login-argon2-totp`). Absent on unauthenticated renders.
 *  - `pageTitle` — top-bar crumb text (D7).
 *  - `activePath` — the nav item to mark active (D8).
 *  - `envName` — uppercased deployment env for the top-bar chip (D6).
 *  - `adminName` / `adminRole` — identity box, from [AdminPrincipal] (D5).
 *  - `sessionIdleMinutes` / `sessionExpiresAt` — the identity-box session
 *    line. The displayed expiry is the sooner of (now + idle-timeout) and
 *    the session's absolute cap: the auth plugin refreshed `last_active_at`
 *    to "now" earlier in this very request, so `now + idleTimeout` IS the
 *    idle deadline; the absolute `expiresAt` wins as the session nears its
 *    8 h cap. Formatted `HH:mm` UTC (the panel renders UTC throughout).
 */
class AdminLayout(
    private val csrfHmacKeyProvider: () -> ByteArray,
    environmentName: String,
    private val idleTimeout: Duration = AdminAuthProvider.DEFAULT_IDLE_TIMEOUT,
    private val clock: () -> Instant = Instant::now,
) {
    private val envName = environmentName.uppercase()

    /**
     * Put the shell model values into [model]. Call from every full-page
     * (non-HTMX-fragment) render inside the `authenticate` block.
     */
    fun putShellModel(
        call: ApplicationCall,
        model: MutableMap<String, Any>,
        pageTitle: String,
        activePath: String,
    ) {
        model["pageTitle"] = pageTitle
        model["activePath"] = activePath
        model["envName"] = envName

        call.request.cookies[AdminAuthProvider.COOKIE_NAME]
            ?.takeIf { it.isNotBlank() }
            ?.let { HashUtil.deriveCsrfFromSessionToken(it, csrfHmacKeyProvider()) }
            ?.let { model["csrfToken"] = it }

        call.principal<AdminPrincipal>()?.let { principal ->
            model["adminName"] = principal.displayName
            model["adminRole"] = principal.role
            model["sessionIdleMinutes"] = idleTimeout.toMinutes()
            val idleDeadline = clock().plus(idleTimeout)
            val shownExpiry =
                if (idleDeadline.isBefore(principal.expiresAt)) idleDeadline else principal.expiresAt
            model["sessionExpiresAt"] = EXPIRY_FORMAT.format(shownExpiry)
        }
    }

    private companion object {
        val EXPIRY_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC)
    }
}
