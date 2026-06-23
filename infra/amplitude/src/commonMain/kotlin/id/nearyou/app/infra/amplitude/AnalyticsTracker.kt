package id.nearyou.app.infra.amplitude

import kotlinx.serialization.json.JsonElement

/**
 * mobile-amplitude-analytics — the vendor-SDK-FREE product-analytics seam.
 *
 * `:mobile:app` depends on this interface (consumed through a consent-gating decorator that lives in
 * the app layer, since the consent snapshot lives there). The Amplitude integration is an HTTP V2 API
 * wrapper ([AmplitudeAnalyticsTracker]) per `docs/04` § Amplitude — there is no vendor SDK, so nothing
 * to fence (CLAUDE.md invariant #16 is trivially satisfied) and the impl ships in commonMain.
 *
 * All methods are synchronous fire-and-forget: implementations queue the network send off the caller's
 * thread and swallow every transport/serialization error, so analytics never blocks or fails a user
 * flow (product analytics is best-effort per `docs/04`).
 */
interface AnalyticsTracker {
    /**
     * Record a product event for the authenticated [userId]. [eventProperties] MUST be privacy-safe —
     * never raw coordinates, never post/message content (see the `mobile-amplitude-analytics` spec).
     */
    fun track(
        eventType: String,
        userId: String,
        eventProperties: Map<String, JsonElement> = emptyMap(),
    )

    /**
     * Associate [userProperties] with the authenticated [userId] (`subscription_status`, `platform`,
     * `install_date_bucket` — week-level only — and `city_name_at_last_post`, per `docs/04`).
     */
    fun identify(
        userId: String,
        userProperties: Map<String, JsonElement>,
    )

    /** Best-effort flush of any buffered events. A no-op for the per-call HTTP V2 transport. */
    fun flush()
}

/**
 * Amplitude ingestion config. [apiKey] is an Amplitude **ingestion write key** — client-embeddable
 * (like the Sentry DSN), NOT a secret-grade credential; a blank [apiKey] MUST cause [NoOpAnalyticsTracker]
 * to be bound (no network activity). [endpoint] defaults to the US HTTP V2 host; override to
 * [EU_HTTP_V2_ENDPOINT] for EU data residency (Indonesia is not EU residency — US is the default).
 */
data class AmplitudeConfig(
    val apiKey: String,
    val endpoint: String = US_HTTP_V2_ENDPOINT,
) {
    companion object {
        const val US_HTTP_V2_ENDPOINT: String = "https://api2.amplitude.com/2/httpapi"
        const val EU_HTTP_V2_ENDPOINT: String = "https://api.eu.amplitude.com/2/httpapi"
    }
}

/**
 * The default no-op tracker: bound when the Amplitude API key is blank (dev / un-provisioned builds),
 * and used as the test/headless default. Every method is a safe no-op (no network activity). Mirrors
 * `NoOpCrashReporter`.
 */
object NoOpAnalyticsTracker : AnalyticsTracker {
    override fun track(
        eventType: String,
        userId: String,
        eventProperties: Map<String, JsonElement>,
    ) = Unit

    override fun identify(
        userId: String,
        userProperties: Map<String, JsonElement>,
    ) = Unit

    override fun flush() = Unit
}
