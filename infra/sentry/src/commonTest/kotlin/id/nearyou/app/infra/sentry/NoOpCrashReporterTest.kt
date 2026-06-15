package id.nearyou.app.infra.sentry

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Smoke for the vendor-free foundation: the [NoOpCrashReporter] (bound for blank-DSN / consent-decline
 * / headless paths) honors the [CrashReporter] contract without throwing. Behavioral coverage of the
 * real actual (init/sendDefaultPii/beforeSend scrubbing, consent gating) lands with the Sentry impl
 * + the :mobile:app wiring tests.
 */
class NoOpCrashReporterTest {
    @Test
    fun noOpHonorsTheContractWithoutThrowing() {
        val reporter: CrashReporter = NoOpCrashReporter
        reporter.init(CrashReporterConfig(dsn = "", environment = "dev", release = "1.0.0"))
        reporter.setUser("opaque-sub-123")
        reporter.addBreadcrumb(Breadcrumb(message = "nearby_network_error", category = "diagnostic"))
        reporter.captureException(RuntimeException("boom"), mapOf("screen" to "timeline"))
        reporter.clearUser()
        reporter.close()
        // Reaching here means every no-op method returned without throwing.
        assertTrue(true)
    }
}
