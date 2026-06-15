package id.nearyou.app.infra.sentry

import io.sentry.kotlin.multiplatform.Sentry
import io.sentry.kotlin.multiplatform.SentryLevel
import io.sentry.kotlin.multiplatform.protocol.User
import io.sentry.kotlin.multiplatform.protocol.Breadcrumb as SentryBreadcrumb

/**
 * The Sentry-backed [CrashReporter]. The Sentry KMP facade (`io.sentry.kotlin.multiplatform.*`) is
 * multiplatform, so this is a single commonMain implementation — an apply-phase refinement of
 * design.md Decision 1 (which sketched androidMain/iosMain actuals; the facade being multiplatform,
 * verified 2026-06-14, means no expect/actual is needed — mirrors `:infra:supabase-realtime`'s single
 * commonMain `SupabaseChatRealtimeSubscriber`). Every vendor symbol is fenced to this module
 * (invariant #16); the dependency is `implementation`-scoped so it never reaches `:mobile:app`.
 *
 * PII posture (mobile-crash-reporting spec): the PRIMARY crash-event defenses are `sendDefaultPii =
 * false` (the SDK never infers/attaches the client IP `{{auto}}`), the OPAQUE-`sub`-only user
 * correlation (never username/email), and the coordinate-free breadcrumb/tag contract (breadcrumbs are
 * scrubbed at the input boundary, capture-context tag values are scrubbed below). The `beforeSend` hook
 * is a backstop that nulls any residual user IP and scrubs `event.message` — note that is populated for
 * `captureMessage` payloads, not for an unhandled-`Throwable` crash (whose `message` is null), so it is
 * the upstream contract, not `beforeSend`, that protects the crash path.
 */
class SentryCrashReporter : CrashReporter {
    override fun init(config: CrashReporterConfig) {
        if (config.dsn.isBlank()) return // blank DSN no-ops init safely (spec: "Blank DSN no-ops safely")
        Sentry.init { options ->
            options.dsn = config.dsn
            options.environment = config.environment
            options.release = config.release
            options.sendDefaultPii = false
            options.beforeSend = { event ->
                event.user?.ipAddress = null
                event.message?.let { message ->
                    message.formatted?.let { message.formatted = PiiScrubber.scrub(it) }
                }
                event
            }
        }
    }

    override fun captureException(
        throwable: Throwable,
        context: Map<String, String>,
    ) {
        if (context.isNotEmpty()) {
            // No production caller passes context yet (task 7.8). Values are PII-scrubbed here as the
            // backstop. NOTE: when a real caller lands, move to an event-LOCAL scope
            // (`captureException(t) { scope -> ... }` / `withScope`) — the global `configureScope` below
            // would otherwise persist these tags onto every subsequent event.
            Sentry.configureScope { scope -> context.forEach { (key, value) -> scope.setTag(key, PiiScrubber.scrub(value)) } }
        }
        Sentry.captureException(throwable)
    }

    override fun setUser(id: String) {
        Sentry.setUser(User().apply { this.id = id })
    }

    override fun clearUser() {
        Sentry.configureScope { scope -> scope.user = null }
    }

    override fun addBreadcrumb(breadcrumb: Breadcrumb) {
        Sentry.addBreadcrumb(
            SentryBreadcrumb().apply {
                message = PiiScrubber.scrub(breadcrumb.message)
                category = breadcrumb.category
                level = breadcrumb.level.toSentryLevel()
            },
        )
    }

    override fun close() {
        Sentry.close()
    }
}

private fun BreadcrumbLevel.toSentryLevel(): SentryLevel =
    when (this) {
        BreadcrumbLevel.DEBUG -> SentryLevel.DEBUG
        BreadcrumbLevel.INFO -> SentryLevel.INFO
        BreadcrumbLevel.WARNING -> SentryLevel.WARNING
        BreadcrumbLevel.ERROR -> SentryLevel.ERROR
    }
