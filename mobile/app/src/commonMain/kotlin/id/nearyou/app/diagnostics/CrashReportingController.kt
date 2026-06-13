package id.nearyou.app.diagnostics

import id.nearyou.app.infra.sentry.CrashReporter
import id.nearyou.app.infra.sentry.CrashReporterConfig

/**
 * Centralizes the [CrashReporter] start/stop lifecycle so BOTH the startup init (`initKoin`) and the
 * runtime consent toggle (`ConsentSettingsViewModel`) share ONE path — no duplicated init/config logic
 * (mobile-crash-reporting). [start] inits with the flavor-resolved [config] (a blank DSN no-ops);
 * [stop] closes reporting for the session; [applyConsent] maps a `crash` consent value: ON → (re)start,
 * OFF → stop. Vendor-free (it only touches the `:infra:sentry` interface), so it is unit-testable with a
 * capturing fake reporter.
 */
class CrashReportingController(
    private val crashReporter: CrashReporter,
    private val config: CrashReporterConfig,
) {
    fun start() {
        crashReporter.init(config)
    }

    fun stop() {
        crashReporter.close()
    }

    fun applyConsent(crashConsent: Boolean) {
        if (crashConsent) start() else stop()
    }
}
