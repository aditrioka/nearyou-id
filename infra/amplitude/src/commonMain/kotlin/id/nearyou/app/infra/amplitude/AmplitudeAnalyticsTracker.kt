package id.nearyou.app.infra.amplitude

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

/**
 * mobile-amplitude-analytics — the Amplitude **HTTP V2 API** wrapper (`docs/04` § Amplitude). Posts
 * events to [AmplitudeConfig.endpoint] using an injected [engine] (the caller supplies the platform
 * engine — `:mobile:app`'s `httpClientEngine()`; tests pass `MockEngine`), so this module owns its
 * dedicated transport without a per-platform engine dependency.
 *
 * **Security (design D4):** the client installs NO `Auth` plugin — the app's RS256 JWT is never sent
 * to the third-party Amplitude host. The only credential transmitted is the Amplitude ingestion
 * `api_key` in the request body.
 *
 * **Fail-soft:** [track]/[identify] build the event and launch the POST on [scope], wrapping it in
 * `runCatching` so a network/serialization failure never crashes or blocks the caller (product
 * analytics is best-effort). The pure suspend transport [post] is exposed `internal` for deterministic
 * unit testing (no launch race).
 *
 * Consent gating is NOT done here — it lives in the app-layer `ConsentGatedAnalyticsTracker` decorator
 * (the consent snapshot lives in `:mobile:app`; infra must not depend on app, docs/11 §4).
 */
class AmplitudeAnalyticsTracker(
    private val config: AmplitudeConfig,
    engine: HttpClientEngine,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : AnalyticsTracker {
    private val json =
        Json {
            encodeDefaults = false
            explicitNulls = false
        }

    private val client =
        HttpClient(engine) {
            // NO Auth plugin (design D4) — never attach the app bearer to a third-party host.
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout) { requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS }
            install(HttpRequestRetry) {
                retryOnServerErrors(maxRetries = MAX_RETRIES)
                exponentialDelay()
            }
        }

    override fun track(
        eventType: String,
        userId: String,
        eventProperties: Map<String, JsonElement>,
    ) = emit(trackEvent(eventType, userId, eventProperties))

    override fun identify(
        userId: String,
        userProperties: Map<String, JsonElement>,
    ) = emit(identifyEvent(userId, userProperties))

    /** No-op: the per-call HTTP V2 transport buffers nothing. Present for interface completeness. */
    override fun flush() = Unit

    /** Fire-and-forget dispatch: launch off the caller's thread and swallow all errors (fail-soft). */
    private fun emit(event: AmplitudeEvent) {
        // Fail-soft, but never swallow structured cancellation (codebase convention).
        scope.launch { runCatching { post(event) }.onFailure { if (it is CancellationException) throw it } }
    }

    /** Maps a [track] call to its HTTP V2 event (`internal` for deterministic tests). */
    internal fun trackEvent(
        eventType: String,
        userId: String,
        eventProperties: Map<String, JsonElement>,
    ): AmplitudeEvent =
        AmplitudeEvent(
            eventType = eventType,
            userId = userId,
            eventProperties = eventProperties,
            time = nowMillis(),
        )

    /**
     * Maps an [identify] call to its HTTP V2 event (`internal` for deterministic tests). HTTP V2 sets a
     * user's properties by attaching `user_properties` to an event; the reserved "$identify" event_type
     * carries them without polluting the product-event stream.
     */
    internal fun identifyEvent(
        userId: String,
        userProperties: Map<String, JsonElement>,
    ): AmplitudeEvent =
        AmplitudeEvent(
            eventType = IDENTIFY_EVENT_TYPE,
            userId = userId,
            userProperties = userProperties,
            time = nowMillis(),
        )

    /** The pure suspend transport. `internal` so tests await it deterministically (no launch race). */
    internal suspend fun post(event: AmplitudeEvent) {
        client.post(config.endpoint) {
            contentType(ContentType.Application.Json)
            setBody(AmplitudeRequest(apiKey = config.apiKey, events = listOf(event)))
        }
    }

    private companion object {
        const val REQUEST_TIMEOUT_MILLIS = 10_000L
        const val MAX_RETRIES = 2
        const val IDENTIFY_EVENT_TYPE = "\$identify"
    }
}

/** Amplitude HTTP V2 request envelope: `{ "api_key": ..., "events": [ ... ] }`. */
@Serializable
internal data class AmplitudeRequest(
    @SerialName("api_key") val apiKey: String,
    val events: List<AmplitudeEvent>,
)

/** A single Amplitude HTTP V2 event. */
@Serializable
internal data class AmplitudeEvent(
    @SerialName("event_type") val eventType: String,
    @SerialName("user_id") val userId: String,
    @SerialName("event_properties") val eventProperties: Map<String, JsonElement> = emptyMap(),
    @SerialName("user_properties") val userProperties: Map<String, JsonElement> = emptyMap(),
    val time: Long,
)
