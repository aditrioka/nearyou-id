package id.nearyou.app.admin.routes

import io.ktor.server.application.call
import io.ktor.server.pebble.PebbleContent
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.time.Duration
import java.time.Instant

/**
 * `GET /admin/` — the admin index ("Dashboard") page.
 *
 * Wired INSIDE the `authenticate("admin") { ... }` block so the session
 * middleware (per `admin-login-argon2-totp` spec Req "Session middleware
 * validates the cookie on every authenticated request") guarantees a
 * valid session before the handler runs.
 *
 * Renders mockup frame 2's landing (admin-mockup-parity spec Req "Scaffold
 * landing renders greeting and live stat cards"): greeting with the admin's
 * display name + three live stat cards + the CSRF info banner. The shell
 * model (CSRF token, identity box, env chip, page title) comes from
 * [AdminLayout.putShellModel].
 */
fun Route.adminIndex(
    layout: AdminLayout,
    statsRepository: AdminIndexStatsRepository,
    clock: () -> Instant = Instant::now,
) {
    get("/") {
        val stats = statsRepository.load()
        val model =
            buildMap<String, Any> {
                layout.putShellModel(call, this, pageTitle = "Dashboard", activePath = "/admin/")
                put("pendingReports", stats.pendingReports)
                put("oldestPendingAge", stats.oldestPendingAt?.let { relativeAge(it, clock()) } ?: EMPTY_SLOT)
                put("rejectedLast24h", stats.rejectedLast24h)
                put("rejectedTopReason", stats.rejectedTopReason ?: EMPTY_SLOT)
                put("auditActionsToday", stats.auditActionsToday)
                put("auditLastActionType", stats.auditLastActionType ?: EMPTY_SLOT)
            }
        call.respond(PebbleContent("index.peb", model = model))
    }
}

/** Placeholder for empty-state secondary slots (spec: zero counts render with `—`). */
private const val EMPTY_SLOT = "—"

/**
 * Coarse relative age for the "Oldest" slot, mirroring the mockup's
 * "2 h ago" shape: `just now` under a minute, then `N m ago` / `N h ago` /
 * `N d ago`. Server-side per design.md D4 (no template logic).
 */
internal fun relativeAge(
    at: Instant,
    now: Instant,
): String {
    val elapsed = Duration.between(at, now)
    return when {
        elapsed.toMinutes() < 1 -> "just now"
        elapsed.toHours() < 1 -> "${elapsed.toMinutes()} m ago"
        elapsed.toDays() < 1 -> "${elapsed.toHours()} h ago"
        else -> "${elapsed.toDays()} d ago"
    }
}
