package id.nearyou.app.infra.amplitude

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull

/**
 * Transport assertions for [AmplitudeAnalyticsTracker] via Ktor `MockEngine`. The pure suspend [post]
 * is awaited directly (deterministic — no fire-and-forget launch race); the [trackEvent]/[identifyEvent]
 * builders verify the event mapping. Covers: the HTTP V2 body shape, the configured ingestion endpoint
 * (US default / EU override), the no-`Authorization`-header guard (design D4), and fail-soft.
 */
class AmplitudeAnalyticsTrackerTest {
    private fun tracker(
        config: AmplitudeConfig,
        onRequest: (HttpRequestData) -> Unit = {},
        throwError: Boolean = false,
    ): AmplitudeAnalyticsTracker =
        AmplitudeAnalyticsTracker(
            config = config,
            engine =
                MockEngine { request ->
                    onRequest(request)
                    if (throwError) throw RuntimeException("network down")
                    respond(content = "{}", status = HttpStatusCode.OK)
                },
            nowMillis = { FIXED_TIME },
        )

    @Test
    fun postSendsHttpV2BodyToUsEndpointWithNoAuthHeader() =
        runTest {
            var captured: HttpRequestData? = null
            val tracker = tracker(AmplitudeConfig(apiKey = "test-key"), onRequest = { captured = it })

            tracker.post(
                tracker.trackEvent(
                    eventType = "post_created",
                    userId = "user-123",
                    eventProperties = mapOf("has_location" to JsonPrimitive(true)),
                ),
            )

            val request = requireNotNull(captured) { "no request was issued" }
            // Endpoint = US default; no app credential leaked (design D4).
            assertEquals(AmplitudeConfig.US_HTTP_V2_ENDPOINT, request.url.toString())
            assertNull(request.headers[HttpHeaders.Authorization], "the app JWT must never reach Amplitude")

            // HTTP V2 body shape: api_key + events[] with event_type/user_id/event_properties/time.
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals("test-key", body.getValue("api_key").jsonPrimitive.content)
            val event = body.getValue("events").jsonArray.single().jsonObject
            assertEquals("post_created", event.getValue("event_type").jsonPrimitive.content)
            assertEquals("user-123", event.getValue("user_id").jsonPrimitive.content)
            assertEquals(FIXED_TIME, event.getValue("time").jsonPrimitive.long)
            assertEquals(
                "true",
                event.getValue("event_properties").jsonObject.getValue("has_location").jsonPrimitive.content,
            )
        }

    @Test
    fun identifyEventCarriesUserPropertiesAndIdentifyType() =
        runTest {
            var captured: HttpRequestData? = null
            val tracker = tracker(AmplitudeConfig(apiKey = "test-key"), onRequest = { captured = it })

            tracker.post(
                tracker.identifyEvent(
                    userId = "user-123",
                    userProperties =
                        mapOf(
                            "subscription_status" to JsonPrimitive("free"),
                            "platform" to JsonPrimitive("android"),
                            "install_date_bucket" to JsonPrimitive("2026-W25"),
                            "city_name_at_last_post" to JsonPrimitive("Jakarta"),
                        ),
                ),
            )

            val event =
                Json.parseToJsonElement((requireNotNull(captured).body as TextContent).text)
                    .jsonObject.getValue("events").jsonArray.single().jsonObject
            assertEquals("\$identify", event.getValue("event_type").jsonPrimitive.content)
            val userProps = event.getValue("user_properties").jsonObject
            assertEquals("free", userProps.getValue("subscription_status").jsonPrimitive.content)
            assertEquals("android", userProps.getValue("platform").jsonPrimitive.content)
            assertEquals("2026-W25", userProps.getValue("install_date_bucket").jsonPrimitive.content)
            assertEquals("Jakarta", userProps.getValue("city_name_at_last_post").jsonPrimitive.content)
        }

    @Test
    fun euEndpointIsUsedWhenConfigured() =
        runTest {
            var capturedUrl: String? = null
            val tracker =
                tracker(
                    AmplitudeConfig(apiKey = "k", endpoint = AmplitudeConfig.EU_HTTP_V2_ENDPOINT),
                    onRequest = { capturedUrl = it.url.toString() },
                )

            tracker.post(tracker.trackEvent("signup_completed", "u", emptyMap()))

            assertEquals(AmplitudeConfig.EU_HTTP_V2_ENDPOINT, capturedUrl)
        }

    @Test
    fun transportSurfacesErrorWhilePublicTrackIsFailSoft() =
        runTest {
            var hit = false
            val tracker =
                tracker(
                    AmplitudeConfig(apiKey = "k"),
                    onRequest = { hit = true },
                    throwError = true,
                )

            // The pure transport surfaces the engine error (so the production `emit` wrapper has something
            // to swallow); the engine was invoked.
            assertFails { tracker.post(tracker.trackEvent("post_liked", "u", emptyMap())) }
            assertEquals(true, hit)

            // The public fire-and-forget API never throws despite the exploding engine (fail-soft).
            tracker.track("post_liked", "u")
        }

    private companion object {
        const val FIXED_TIME = 1_700_000_000_000L
    }
}
