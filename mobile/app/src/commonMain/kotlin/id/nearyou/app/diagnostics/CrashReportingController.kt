package id.nearyou.app.diagnostics

import id.nearyou.app.infra.sentry.CrashReporter
import id.nearyou.app.infra.sentry.CrashReporterConfig

/**
 * Centralizes the [CrashReporter] start/stop lifecycle so BOTH the startup init (`initKoin`) and the
 * consent submits (`ConsentViewModel`, `ConsentSettingsViewModel`) share ONE path — no duplicated
 * init/config logic (mobile-crash-reporting). [start] inits with the flavor-resolved [config] (a blank DSN no-ops);
 * [stop] closes reporting for the session; [applyConsent] maps a `crash` consent value: ON → (re)start,
 * OFF → stop. Vendor-free (it only touches the `:infra:sentry` interface), so it is unit-testable with a
 * capturing fake reporter.
 */
class CrashReportingController(
    private val crashReporter: CrashReporter,
    private val config: CrashReporterConfig,
) {
    // Re-initializing a live SDK replaces its hub, dropping the scope (the signed-in user), so a consent
    // save that keeps crash ON must not re-init (#492). Main-thread only (startup + consent VMs).
    private var started = false

    fun start() {
        if (started) return
        crashReporter.init(config)
        started = true
    }

    fun stop() {
        crashReporter.close()
        started = false
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
