package id.nearyou.app.analytics

import id.nearyou.app.data.consent.ConsentSnapshot
import id.nearyou.app.data.consent.InMemoryConsentSnapshotStore
import id.nearyou.app.infra.amplitude.AnalyticsTracker
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The consent gate (design D3, the Pre-Launch "Amplitude opt-out silent" requirement): the decorator
 * delegates only when analytics consent is granted, re-evaluated per fire; an absent snapshot suppresses.
 */
class ConsentGatedAnalyticsTrackerTest {
    /** Records delegate invocations so the test can assert call vs no-call. */
    private class RecordingTracker : AnalyticsTracker {
        var trackCount = 0
        var identifyCount = 0
        var flushCount = 0

        override fun track(
            eventType: String,
            userId: String,
            eventProperties: Map<String, JsonElement>,
        ) {
            trackCount++
        }

        override fun identify(
            userId: String,
            userProperties: Map<String, JsonElement>,
        ) {
            identifyCount++
        }

        override fun flush() {
            flushCount++
        }
    }

    private fun gate(store: InMemoryConsentSnapshotStore): Pair<ConsentGatedAnalyticsTracker, RecordingTracker> {
        val delegate = RecordingTracker()
        return ConsentGatedAnalyticsTracker(delegate, store) to delegate
    }

    @Test
    fun consentOff_suppressesAllEmission() {
        val store = InMemoryConsentSnapshotStore()
        store.write(ConsentSnapshot(analytics = false, crash = true, adsPersonalization = false))
        val (tracker, delegate) = gate(store)

        tracker.track("post_created", "u")
        tracker.identify("u", emptyMap())
        tracker.flush()

        assertEquals(0, delegate.trackCount)
        assertEquals(0, delegate.identifyCount)
        assertEquals(0, delegate.flushCount)
    }

    @Test
    fun absentSnapshot_suppresses() {
        val (tracker, delegate) = gate(InMemoryConsentSnapshotStore())
        tracker.track("post_created", "u")
        assertEquals(0, delegate.trackCount, "an absent snapshot defaults to not-consented → suppressed")
    }

    @Test
    fun consentOn_delegates() {
        val store = InMemoryConsentSnapshotStore()
        store.write(ConsentSnapshot(analytics = true, crash = true, adsPersonalization = false))
        val (tracker, delegate) = gate(store)

        tracker.track("post_created", "u")
        tracker.identify("u", emptyMap())

        assertEquals(1, delegate.trackCount)
        assertEquals(1, delegate.identifyCount)
    }

    @Test
    fun consentIsReEvaluatedPerFire() {
        val store = InMemoryConsentSnapshotStore()
        store.write(ConsentSnapshot(analytics = true, crash = true, adsPersonalization = false))
        val (tracker, delegate) = gate(store)

        tracker.track("post_created", "u")
        assertEquals(1, delegate.trackCount)

        // Toggle OFF mid-session — the next emission is suppressed with no re-init.
        store.write(ConsentSnapshot(analytics = false, crash = true, adsPersonalization = false))
        tracker.track("post_liked", "u")
        assertEquals(1, delegate.trackCount, "a later event after toggling OFF is suppressed")
    }
}
