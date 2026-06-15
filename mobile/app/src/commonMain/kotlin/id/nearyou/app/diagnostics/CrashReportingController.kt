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

    /**
     * Cold-start gate (called from `initKoin`): [start] (opt-out default ON) then [stop] iff a
     * last-known crash DECLINE is present. [lastKnownCrash] is the device-local snapshot's `crash` value,
     * or null when no snapshot exists yet (cold start before durable persistence #198 → fall back to the
     * opt-out default = stay started). The init→close ordering matches the spec's "persisted decline is
     * honored" scenario.
     */
    fun applyStartupConsent(lastKnownCrash: Boolean?) {
        start()
        if (lastKnownCrash == false) stop()
    }
}
