package id.nearyou.app.diagnostics

import id.nearyou.app.infra.sentry.CrashReporterConfig
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * mobile-crash-reporting spec § "Consent gating" — the controller is the shared start/stop seam used by
 * both startup (initKoin) and the runtime consent toggle. Verifies the consent decision maps to
 * init/close on the (faked) reporter: ON → (re)init, OFF → close.
 */
class CrashReportingControllerTest {
    private val config = CrashReporterConfig(dsn = "https://k@o.ingest.sentry.io/1", environment = "dev", release = "1.0")

    @Test
    fun startInitsTheReporterWithTheConfig() {
        val fake = FakeCrashReporter()
        CrashReportingController(fake, config).start()
        assertEquals(1, fake.initCount)
        assertEquals(config, fake.lastConfig)
    }

    @Test
    fun applyConsentOnInits() {
        val fake = FakeCrashReporter()
        CrashReportingController(fake, config).applyConsent(crashConsent = true)
        assertEquals(1, fake.initCount)
        assertEquals(0, fake.closeCount)
    }

    @Test
    fun applyConsentOffCloses() {
        val fake = FakeCrashReporter()
        CrashReportingController(fake, config).applyConsent(crashConsent = false)
        assertEquals(0, fake.initCount)
        assertEquals(1, fake.closeCount)
    }

    @Test
    fun reConsentAfterDeclineReInits() {
        val fake = FakeCrashReporter()
        val controller = CrashReportingController(fake, config)
        controller.applyConsent(crashConsent = false)
        controller.applyConsent(crashConsent = true)
        assertEquals(1, fake.initCount)
        assertEquals(1, fake.closeCount)
    }
}
