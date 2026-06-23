package id.nearyou.app.analytics

import id.nearyou.app.data.consent.ConsentSnapshotStore
import id.nearyou.app.infra.amplitude.AnalyticsTracker
import kotlinx.serialization.json.JsonElement

/**
 * mobile-amplitude-analytics — the app-layer consent gate (design D3). Wraps the vendor-free
 * [AnalyticsTracker] from `:infra:amplitude` and **silently suppresses** every emission (track,
 * identify, flush) unless analytics consent is granted. The gate lives here (not in `:infra:amplitude`)
 * because the consent snapshot lives in `:mobile:app` and infra must not depend on app (docs/11 §4).
 *
 * Consent is re-read from the durable [ConsentSnapshotStore] on **every** call, so a Settings toggle
 * applies immediately to future events (docs/06 "toggles apply to future events") — no re-init needed.
 * An absent snapshot (first run, before any consent submit) reads as `null` → not-consented → suppressed
 * (the privacy-safe opt-in default).
 */
class ConsentGatedAnalyticsTracker(
    private val delegate: AnalyticsTracker,
    private val consentStore: ConsentSnapshotStore,
) : AnalyticsTracker {
    private fun analyticsConsented(): Boolean = consentStore.read()?.analytics == true

    override fun track(
        eventType: String,
        userId: String,
        eventProperties: Map<String, JsonElement>,
    ) {
        if (analyticsConsented()) delegate.track(eventType, userId, eventProperties)
    }

    override fun identify(
        userId: String,
        userProperties: Map<String, JsonElement>,
    ) {
        if (analyticsConsented()) delegate.identify(userId, userProperties)
    }

    override fun flush() {
        if (analyticsConsented()) delegate.flush()
    }
}
