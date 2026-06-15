package id.nearyou.app.diagnostics

import id.nearyou.app.infra.sentry.Breadcrumb
import id.nearyou.app.infra.sentry.CrashReporter
import id.nearyou.app.infra.sentry.CrashReporterConfig

/**
 * Capturing test double for [CrashReporter] — records calls so the mobile-crash-reporting wiring tests
 * (consent gating, breadcrumbs, user correlation) can assert behavior without the real Sentry SDK.
 */
class FakeCrashReporter : CrashReporter {
    var initCount = 0
    var lastConfig: CrashReporterConfig? = null
    var closeCount = 0
    var captureCount = 0
    var lastUserId: String? = null
    var clearUserCount = 0
    val breadcrumbs = mutableListOf<Breadcrumb>()

    override fun init(config: CrashReporterConfig) {
        initCount++
        lastConfig = config
    }

    override fun captureException(
        throwable: Throwable,
        context: Map<String, String>,
    ) {
        captureCount++
    }

    override fun setUser(id: String) {
        lastUserId = id
    }

    override fun clearUser() {
        clearUserCount++
    }

    override fun addBreadcrumb(breadcrumb: Breadcrumb) {
        breadcrumbs += breadcrumb
    }

    override fun close() {
        closeCount++
    }
}
