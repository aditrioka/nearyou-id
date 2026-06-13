package id.nearyou.app.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * mobile-crash-reporting spec § "Repository diagnostics surface as breadcrumbs" — the bridge forwards a
 * diagnostic message to the CrashReporter as a `diagnostic`-category breadcrumb (the real Sentry-backed
 * sink the CompositeDiagnosticSink fans into).
 */
class SentryBreadcrumbDiagnosticSinkTest {
    @Test
    fun forwardsDiagnosticAsBreadcrumb() {
        val fake = FakeCrashReporter()
        SentryBreadcrumbDiagnosticSink(fake).log("nearby_network_error status=503")

        assertEquals(1, fake.breadcrumbs.size)
        assertEquals("nearby_network_error status=503", fake.breadcrumbs.single().message)
        assertEquals("diagnostic", fake.breadcrumbs.single().category)
    }

    @Test
    fun compositeFansToEverySink() {
        val fake = FakeCrashReporter()
        val recorded = mutableListOf<String>()
        val composite =
            CompositeDiagnosticSink(
                listOf(
                    DiagnosticSink { recorded += it },
                    SentryBreadcrumbDiagnosticSink(fake),
                ),
            )

        composite.log("global_network_error")

        assertEquals(listOf("global_network_error"), recorded)
        assertEquals("global_network_error", fake.breadcrumbs.single().message)
    }
}
