package id.nearyou.app.infra.supabase.realtime

import id.nearyou.app.core.domain.chat.ChatMessageBroadcast
import id.nearyou.app.infra.otel.testing.SpanRecorder
import id.nearyou.app.infra.otel.testing.instrumentedMockClient
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.request.HttpRequestData
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.extension.kotlin.asContextElement
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID

/**
 * Verifies the `observability-otel-foundation` capability spec scenario
 * "Supabase Realtime broadcast publish carries `traceparent`":
 *
 * > GIVEN an active span context inside the chat send handler AND a
 * > `ChatRealtimeClient.publish(...)` call wrapped by `:infra:otel`'s
 * > `withSpan("chat.realtime.publish", ...)` helper
 * > WHEN the Supabase Realtime publish HTTP request to the configured
 * > Supabase endpoint is captured at the test boundary
 * > THEN the request headers include `traceparent` matching the active context
 *
 * Pairs with task §6.2.11 in the change tasks.md. Uses [MockEngine] to capture
 * the outbound request + a [KtorClientTelemetry]-instrumented HttpClient
 * configured against an in-test SDK pipeline.
 */
class SupabaseBroadcastTraceparentPropagationTest : StringSpec({
    val projectUrl = "https://test.supabase.co"
    val serviceRoleKey = "test-service-role-key"
    val sampleMessage =
        ChatMessageBroadcast(
            id = UUID.fromString("a0000000-0000-0000-0000-00000000000a"),
            conversationId = UUID.fromString("11111111-2222-3333-4444-555555555555"),
            senderId = UUID.fromString("b0000000-0000-0000-0000-00000000000b"),
            content = "halo",
            embeddedPostId = null,
            embeddedPostSnapshot = null,
            embeddedPostEditId = null,
            createdAt = Instant.parse("2026-05-04T10:00:00Z"),
            redactedAt = null,
        )

    beforeEach {
        try {
            GlobalOpenTelemetry.resetForTest()
        } catch (_: Throwable) {
            // tolerate older SDKs without resetForTest
        }
    }

    "outbound Supabase Realtime publish HTTP request carries `traceparent` populated from the active span context" {
        // Set up an in-test SDK pipeline backed by SpanRecorder so we know
        // the trace id we're injecting into outbound headers.
        val (sdk, recorder) = SpanRecorder.newPipeline()
        GlobalOpenTelemetry.set(sdk)

        val captured = java.util.concurrent.atomic.AtomicReference<HttpRequestData?>(null)
        // KtorClientTelemetry plugin installed via `:infra:otel`'s
        // `instrumentedMockClient(...)` test fixture — same plugin
        // `httpClientWithOtel(...)` wires into the production HttpClient,
        // but with MockEngine so we can capture the outbound request.
        val httpClient =
            instrumentedMockClient(sdk) { request ->
                captured.set(request)
                respondOk()
            }

        val client =
            SupabaseBroadcastChatClient(
                projectUrl = projectUrl,
                serviceRoleKey = serviceRoleKey,
                httpClient = httpClient,
            )

        // Wrap the publish in a `withSpan` so there's an active context the
        // KtorClientTelemetry plugin can inject `traceparent` from. Mirrors
        // production where `chatRoutes.publishBroadcast(...)` does this.
        // Start a span, propagate it across the suspend boundary via the
        // OTel kotlin extension's `asContextElement()`, then run the publish.
        // This mirrors production: in `chatRoutes.publishBroadcast(...)` the
        // `withSpan(...)` block is inline-expanded into a `suspend` Ktor
        // route handler, so the active OTel context is on the same coroutine
        // dispatcher that the Ktor client picks up `traceparent` from.
        val tracer = sdk.getTracer(id.nearyou.app.infra.otel.TRACER_NAME)
        val span = tracer.spanBuilder("chat.realtime.publish").startSpan()
        val expectedTraceId = span.spanContext.traceId
        val otelContext = io.opentelemetry.context.Context.current().with(span)
        try {
            runBlocking(otelContext.asContextElement()) {
                client.publish(sampleMessage.conversationId, sampleMessage)
            }
        } finally {
            span.end()
        }

        val request = captured.get()
        request shouldNotBe null
        val traceparent = request!!.headers["traceparent"]
        traceparent shouldNotBe null
        // W3C trace-context format: `00-<trace-id-32hex>-<span-id-16hex>-<flags-2hex>`.
        // See https://www.w3.org/TR/trace-context/#traceparent-header
        traceparent!! shouldMatch Regex("^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$")

        // Stronger assertion: the trace id in the header MUST match the
        // active span's trace id, locking the active-context propagation
        // contract end-to-end.
        val traceIdFromHeader = traceparent.split("-")[1]
        traceIdFromHeader shouldBe expectedTraceId
    }
})
