package id.nearyou.app.infra.amplitude

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test

/**
 * Smoke for the vendor-free default: [NoOpAnalyticsTracker] (bound when the Amplitude API key is blank)
 * performs no network activity and never throws. Mirrors `NoOpCrashReporterTest`. The blank-key →
 * NoOp *binding* is exercised in `:mobile:app`'s Koin resolution test.
 */
class NoOpAnalyticsTrackerTest {
    @Test
    fun noOpMethodsAreSafeNoOps() {
        // No engine, no network — every call must return normally.
        NoOpAnalyticsTracker.track(
            eventType = "post_created",
            userId = "user-1",
            eventProperties = mapOf("has_location" to JsonPrimitive(true)),
        )
        NoOpAnalyticsTracker.identify(
            userId = "user-1",
            userProperties = mapOf("platform" to JsonPrimitive("android")),
        )
        NoOpAnalyticsTracker.flush()
    }
}
