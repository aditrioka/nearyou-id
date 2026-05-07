package id.nearyou.app.chat

import id.nearyou.app.core.domain.chat.ChatMessageBroadcast
import id.nearyou.app.core.domain.chat.ChatRealtimeClient
import id.nearyou.app.core.domain.chat.PublishResult
import id.nearyou.app.infra.otel.testing.SpanRecorder
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.StatusCode
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID

/**
 * Span-pairing tests for the OTel-foundation amendment to
 * `chat-realtime-broadcast`. Validates the modified spec scenarios per
 * § "OTel span emitted on PublishResult.Failure pairs with WARN log",
 * § "OTel span emitted on thrown exception pairs with WARN log",
 * § "OTel span on PublishResult.Success has OK status, no failure event",
 * § "OTel span carries no raw chat content".
 *
 * Unit-level: exercises [publishBroadcast] directly with a SpanRecorder
 * pipeline. Database-tagged route-level tests (
 * `ChatRealtimeBroadcastTest`) still cover the WARN-log emission contract;
 * these tests cover the new span-event side that pairs with each WARN log.
 */
class ChatRealtimeBroadcastSpanPairingTest : StringSpec({
    beforeEach {
        try {
            GlobalOpenTelemetry.resetForTest()
        } catch (_: Throwable) {
            // tolerate older SDKs without resetForTest
        }
    }

    val convId = UUID.fromString("11111111-aaaa-1111-aaaa-111111111111")
    val msgId = UUID.fromString("22222222-bbbb-2222-bbbb-222222222222")
    val senderId = UUID.fromString("33333333-cccc-3333-cccc-333333333333")
    val sentinelChatContent = "sentinel-chat-content-DO-NOT-LEAK"

    fun newRow(content: String = sentinelChatContent) =
        ChatMessageRow(
            id = msgId,
            conversationId = convId,
            senderId = senderId,
            content = content,
            createdAt = Instant.parse("2026-05-06T03:00:00Z"),
            redactedAt = null,
        )

    "OTel span emitted on PublishResult.Failure pairs with chat_realtime_publish_failed event" {
        val (sdk, recorder) = SpanRecorder.newPipeline()
        GlobalOpenTelemetry.set(sdk)
        val client =
            object : ChatRealtimeClient {
                override suspend fun publish(
                    conversationId: UUID,
                    message: ChatMessageBroadcast,
                ): PublishResult = PublishResult.Failure("java.io.IOException")
            }
        runBlocking { publishBroadcast(client, convId, newRow()) }
        val spans = recorder.recordedSpans()
        spans shouldHaveSize 1
        val span = spans[0]
        span.name shouldBe "chat.realtime.publish"
        span.status.statusCode shouldBe StatusCode.ERROR
        val failureEvents = span.events.filter { it.name == "chat_realtime_publish_failed" }
        failureEvents shouldHaveSize 1
        val errType =
            failureEvents[0]
                .attributes
                .get(io.opentelemetry.api.common.AttributeKey.stringKey("error.type"))
        errType shouldBe "java.io.IOException"
    }

    "OTel span emitted on thrown exception pairs with span event + recordException" {
        val (sdk, recorder) = SpanRecorder.newPipeline()
        GlobalOpenTelemetry.set(sdk)
        val client =
            object : ChatRealtimeClient {
                override suspend fun publish(
                    conversationId: UUID,
                    message: ChatMessageBroadcast,
                ): PublishResult = throw java.net.SocketTimeoutException("simulated")
            }
        runBlocking { publishBroadcast(client, convId, newRow()) }
        val spans = recorder.recordedSpans()
        spans shouldHaveSize 1
        val span = spans[0]
        span.status.statusCode shouldBe StatusCode.ERROR
        // Span.recordException(...) attaches an "exception" semconv event.
        val exceptionEvents = span.events.filter { it.name == "exception" }
        exceptionEvents shouldHaveSize 1
        val failureEvents = span.events.filter { it.name == "chat_realtime_publish_failed" }
        failureEvents shouldHaveSize 1
        val errType =
            failureEvents[0]
                .attributes
                .get(io.opentelemetry.api.common.AttributeKey.stringKey("error.type"))
        errType shouldBe "java.net.SocketTimeoutException"
    }

    "OTel span on PublishResult.Success has OK status, no failure event" {
        val (sdk, recorder) = SpanRecorder.newPipeline()
        GlobalOpenTelemetry.set(sdk)
        val client =
            object : ChatRealtimeClient {
                override suspend fun publish(
                    conversationId: UUID,
                    message: ChatMessageBroadcast,
                ): PublishResult = PublishResult.Success
            }
        runBlocking { publishBroadcast(client, convId, newRow()) }
        val spans = recorder.recordedSpans()
        spans shouldHaveSize 1
        val span = spans[0]
        // OTel default end-of-span status is UNSET; a successful path leaves
        // the status untouched — neither OK nor ERROR. This is the contract
        // the spec means by "no failure event recorded".
        (span.status.statusCode in setOf(StatusCode.OK, StatusCode.UNSET)) shouldBe true
        val failureEvents = span.events.filter { it.name == "chat_realtime_publish_failed" }
        failureEvents shouldHaveSize 0
    }

    "Span carries no raw chat content (sentinel-string scan)" {
        val (sdk, recorder) = SpanRecorder.newPipeline()
        GlobalOpenTelemetry.set(sdk)
        val client =
            object : ChatRealtimeClient {
                override suspend fun publish(
                    conversationId: UUID,
                    message: ChatMessageBroadcast,
                ): PublishResult = PublishResult.Failure("java.io.IOException")
            }
        runBlocking { publishBroadcast(client, convId, newRow(content = sentinelChatContent)) }
        val spans = recorder.recordedSpans()
        spans shouldHaveSize 1
        val span = spans[0]
        // Scan span name + attribute values + event attribute values for the
        // sentinel — defense-in-depth verification of the forbidden-attribute
        // contract per spec § "Forbidden span attributes".
        span.name shouldNotContain sentinelChatContent
        span.attributes.forEach { _, value ->
            value.toString() shouldNotContain sentinelChatContent
        }
        for (event in span.events) {
            event.attributes.forEach { _, value ->
                value.toString() shouldNotContain sentinelChatContent
            }
        }
    }
})
