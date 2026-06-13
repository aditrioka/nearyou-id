package id.nearyou.app.admin.routes

import io.ktor.server.application.call
import io.ktor.server.pebble.PebbleContent
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.time.Duration
import java.time.Instant
import java.util.Locale

/**
 * `GET /admin/` — the admin index ("Dashboard") page.
 *
 * Wired INSIDE the `authenticate("admin") { ... }` block so the session
 * middleware (per `admin-login-argon2-totp` spec Req "Session middleware
 * validates the cookie on every authenticated request") guarantees a
 * valid session before the handler runs.
 *
 * Renders the Operational Dashboard (mockup frame 3; `admin-operational-dashboard`
 * spec): the greeting + three quick-link stat cards retained from frame 2
 * (`admin-panel-scaffold` Req "Scaffold landing renders greeting and live stat
 * cards"), plus the operational widgets — posts/signups/reports volume with
 * per-hour sparklines, top active cities, database size — and the CSRF info
 * banner. The shell model (CSRF token, identity box, env chip, page title)
 * comes from [AdminLayout.putShellModel].
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
                // Operational widgets (admin-operational-dashboard). Sparkline
                // bar heights are precomputed server-side (design D4: no
                // template logic) as a % of the series max.
                put("postsLast24h", stats.postsLast24h)
                put("postsSpark", spark(stats.postsByHour))
                put("signupsLast24h", stats.signupsLast24h)
                put("signupsSpark", spark(stats.signupsByHour))
                put("ageGateRejections24h", stats.ageGateRejections24h)
                put("reportsLast24h", stats.reportsLast24h)
                put("reportsSpark", spark(stats.reportsByHour))
                put("topCities", stats.topCities)
                val dbSizeBytes = stats.dbSizeBytes
                put("dbSizePresent", dbSizeBytes != null)
                put("dbSize", dbSizeBytes?.let { humanBytes(it) } ?: EMPTY_SLOT)
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

/** Minimum rendered bar height (% of the spark box) for a non-zero hour, so a 1-count bar stays visible. */
private const val SPARK_MIN_PCT = 8

/**
 * Precompute sparkline bar heights (% of the box) from a per-hour series,
 * scaled to the series max. Server-side per design.md D4 (no template
 * arithmetic): the template just binds each height. All-zero series → all-zero
 * bars (empty sparkline).
 */
internal fun spark(series: List<Long>): List<Int> {
    val max = series.maxOrNull() ?: 0L
    if (max <= 0L) return series.map { 0 }
    return series.map { if (it <= 0L) 0 else maxOf(SPARK_MIN_PCT, ((it * 100) / max).toInt()) }
}

/** Human-readable byte size for the DB-size widget (binary units, en-US decimal). */
internal fun humanBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB", "PB")
    var value = bytes.toDouble() / 1024
    var idx = 0
    while (value >= 1024 && idx < units.size - 1) {
        value /= 1024
        idx++
    }
    return String.format(Locale.US, "%.1f %s", value, units[idx])
}
