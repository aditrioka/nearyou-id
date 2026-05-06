package id.nearyou.app.infra.fcm

import com.google.firebase.messaging.Message
import id.nearyou.app.infra.otel.testing.SpanRecorder
import id.nearyou.data.repository.ActorUsernameLookup
import id.nearyou.data.repository.FcmTokenRow
import id.nearyou.data.repository.NotificationRepository
import id.nearyou.data.repository.NotificationRow
import id.nearyou.data.repository.NotificationType
import id.nearyou.data.repository.TokenSnapshot
import id.nearyou.data.repository.UserFcmTokenReader
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.SpanProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.sql.Connection
import java.time.Instant
import java.util.UUID

/**
 * Span-pairing tests for the OTel-foundation amendment to `fcm-push-dispatch`.
 *
 * Validates that every WARN-log scenario in [FcmDispatcher] also records a
 * span event on the surrounding `fcm.dispatch` span (or the active context
 * span for entrypoint paths like dispatch-after-shutdown / launch-rejected).
 *
 * Mirrors the test scaffolding from [FcmDispatcherTest] without duplicating
 * the full build helper — uses minimal in-test fakes scoped to span-pairing
 * assertions only.
 *
 * The shared `sendCounter` below is used by the per-token partial-failure
 * test to discriminate which call should fail (Kotest `beforeEach` resets it).
 */
private val sendCounter = java.util.concurrent.atomic.AtomicInteger(0)

class FcmDispatcherSpanPairingTest : StringSpec({
    val recipient = UUID.fromString("44444444-dddd-4444-dddd-444444444444")
    val sentinelToken = "sentinel-token-string-DO-NOT-LEAK"

    beforeEach {
        sendCounter.set(0)
    }

    fun newRow(type: NotificationType = NotificationType.POST_LIKED): NotificationRow =
        NotificationRow(
            id = UUID.randomUUID(),
            userId = recipient,
            type = type,
            actorUserId = UUID.randomUUID(),
            targetType = "post",
            targetId = UUID.randomUUID(),
            bodyDataJson = """{"post_excerpt":"Hi"}""",
            createdAt = Instant.now(),
            readAt = null,
        )

    fun setupSpanRecorder(extraProcessor: SpanProcessor? = null): SpanRecorder {
        try {
            GlobalOpenTelemetry.resetForTest()
        } catch (_: Throwable) {
            // tolerate
        }
        val recorder = SpanRecorder()
        val builder = SdkTracerProvider.builder().addSpanProcessor(recorder)
        if (extraProcessor != null) {
            builder.addSpanProcessor(extraProcessor)
        }
        val sdk = OpenTelemetrySdk.builder().setTracerProvider(builder.build()).build()
        GlobalOpenTelemetry.set(sdk)
        return recorder
    }

    fun build(
        notification: NotificationRow,
        tokens: List<FcmTokenRow>,
        sender: FcmSender,
        scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob()),
    ): FcmDispatcher {
        val notRepo =
            object : NotificationRepository {
                override fun insert(
                    conn: Connection,
                    recipientId: UUID,
                    type: NotificationType,
                    actorUserId: UUID?,
                    targetType: String?,
                    targetId: UUID?,
                    bodyDataJson: String,
                ): UUID = UUID.randomUUID()

                override fun isBlockedBetween(
                    conn: Connection,
                    userA: UUID,
                    userB: UUID,
                ): Boolean = false

                override fun listByUser(
                    userId: UUID,
                    cursorCreatedAt: Instant?,
                    cursorId: UUID?,
                    unreadOnly: Boolean,
                    limit: Int,
                ): List<NotificationRow> = emptyList()

                override fun countUnread(userId: UUID): Long = 0

                override fun markRead(
                    userId: UUID,
                    notificationId: UUID,
                ): Int = 0

                override fun markAllRead(userId: UUID): Int = 0

                override fun existsForUser(
                    userId: UUID,
                    notificationId: UUID,
                ): Boolean = false

                override fun findById(notificationId: UUID): NotificationRow? = notification.takeIf { it.id == notificationId }
            }
        val reader =
            object : UserFcmTokenReader {
                override fun activeTokens(userId: UUID): TokenSnapshot = TokenSnapshot(tokens = tokens, dispatchStartedAt = Instant.now())

                override fun deleteTokenIfStale(
                    userId: UUID,
                    platform: String,
                    token: String,
                    dispatchStartedAt: Instant,
                ): Int = 0
            }
        val actorLookup =
            object : ActorUsernameLookup {
                override fun lookup(actorUserId: UUID?): String? = "bobby"
            }
        return FcmDispatcher(
            notificationRepository = notRepo,
            userFcmTokenReader = reader,
            sender = sender,
            actorUsernameLookup = actorLookup,
            dispatcherScope = scope,
        )
    }

    "INVALID_ARGUMENT WARN log pairs with fcm_dispatch_failed span event" {
        val recorder = setupSpanRecorder()
        val notification = newRow()
        val tokens = listOf(FcmTokenRow(platform = "android", token = sentinelToken, lastSeenAt = Instant.now()))
        val sender = FcmSender { FcmSendResult.TransientError(code = "INVALID_ARGUMENT", cause = null) }
        val dispatcher = build(notification, tokens, sender)
        dispatcher.dispatch(notification.id)
        Thread.sleep(50) // give the launched coroutine time to settle
        val spans = recorder.recordedSpans()
        val fcmSpans = spans.filter { it.name == "fcm.dispatch" }
        fcmSpans shouldHaveSize 1
        val span = fcmSpans[0]
        val failureEvents = span.events.filter { it.name == "fcm_dispatch_failed" }
        failureEvents shouldHaveSize 1
        val errorCode =
            failureEvents[0]
                .attributes
                .get(io.opentelemetry.api.common.AttributeKey.stringKey("error_code"))
        errorCode shouldBe "INVALID_ARGUMENT"
        span.status.statusCode shouldBe StatusCode.ERROR
    }

    "5xx-class error WARN log pairs with fcm_dispatch_failed (INTERNAL)" {
        val recorder = setupSpanRecorder()
        val notification = newRow()
        val tokens = listOf(FcmTokenRow(platform = "android", token = sentinelToken, lastSeenAt = Instant.now()))
        val sender = FcmSender { FcmSendResult.TransientError(code = "INTERNAL", cause = null) }
        val dispatcher = build(notification, tokens, sender)
        dispatcher.dispatch(notification.id)
        Thread.sleep(50)
        val span = recorder.recordedSpans().first { it.name == "fcm.dispatch" }
        val event = span.events.first { it.name == "fcm_dispatch_failed" }
        val errorCode = event.attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey("error_code"))
        errorCode shouldBe "INTERNAL"
    }

    "Network timeout WARN log pairs with fcm_dispatch_failed (error_code=unknown)" {
        val recorder = setupSpanRecorder()
        val notification = newRow()
        val tokens = listOf(FcmTokenRow(platform = "android", token = sentinelToken, lastSeenAt = Instant.now()))
        val timeout = java.net.SocketTimeoutException("simulated")
        val sender = FcmSender { throw timeout }
        val dispatcher = build(notification, tokens, sender)
        dispatcher.dispatch(notification.id)
        Thread.sleep(50)
        val span = recorder.recordedSpans().first { it.name == "fcm.dispatch" }
        val event = span.events.first { it.name == "fcm_dispatch_failed" }
        val errorCode = event.attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey("error_code"))
        errorCode shouldBe "unknown"
    }

    "Successful dispatch records OK status, no failure event" {
        val recorder = setupSpanRecorder()
        val notification = newRow()
        val tokens = listOf(FcmTokenRow(platform = "android", token = sentinelToken, lastSeenAt = Instant.now()))
        val sender = FcmSender { FcmSendResult.Sent(messageId = "msg-1") }
        val dispatcher = build(notification, tokens, sender)
        dispatcher.dispatch(notification.id)
        Thread.sleep(50)
        val span = recorder.recordedSpans().first { it.name == "fcm.dispatch" }
        (span.status.statusCode in setOf(StatusCode.OK, StatusCode.UNSET)) shouldBe true
        span.events.filter { it.name == "fcm_dispatch_failed" } shouldHaveSize 0
    }

    "dispatch-after-shutdown WARN log pairs with span event" {
        val recorder = setupSpanRecorder()
        val notification = newRow()
        val tokens = listOf(FcmTokenRow(platform = "android", token = sentinelToken, lastSeenAt = Instant.now()))
        val sender = FcmSender { FcmSendResult.Sent("ok") }
        val dispatcher = build(notification, tokens, sender)
        dispatcher.markShutdown()
        dispatcher.dispatch(notification.id)
        Thread.sleep(50)
        // The shutdown path runs synchronously on the caller — no `withSpan`
        // wraps it, so the event lands on whatever span is current
        // (the implicit `INVALID` span if none). The recorder captures spans
        // at end; if no span was ever created, recordedSpans is empty —
        // verify by checking that the span recording attempted to add the
        // event by checking the log — alternative: assert the event is on
        // the test span we explicitly wrap below.
        // Simpler scenario: wrap the call in a withSpan ourselves so the
        // event has somewhere to land, then assert.
        recorder.clear()
        id.nearyou.app.infra.otel.withSpan("test.shutdown.outer") {
            dispatcher.dispatch(notification.id)
            Thread.sleep(50)
        }
        val outerSpan = recorder.recordedSpans().first { it.name == "test.shutdown.outer" }
        val shutdownEvents = outerSpan.events.filter { it.name == "fcm_dispatch_after_shutdown" }
        shutdownEvents shouldHaveSize 1
    }

    "INFO-level skip paths do NOT record a span event" {
        // No tokens → INFO `fcm_skipped_no_tokens` per spec § "Recipient
        // hard-deleted between emit and dispatch". This path is BEFORE the
        // sendOne `withSpan`, so no `fcm.dispatch` span is created — and no
        // failure event lands on any wrapping span.
        val recorder = setupSpanRecorder()
        val notification = newRow()
        val sender = FcmSender { FcmSendResult.Sent("never-called") }
        val dispatcher = build(notification, tokens = emptyList(), sender)
        id.nearyou.app.infra.otel.withSpan("test.info.outer") {
            dispatcher.dispatch(notification.id)
            Thread.sleep(50)
        }
        val outerSpan = recorder.recordedSpans().first { it.name == "test.info.outer" }
        outerSpan.events.filter { it.name == "fcm_dispatch_failed" } shouldHaveSize 0
        outerSpan.events.filter { it.name == "fcm_dispatch_after_shutdown" } shouldHaveSize 0
    }

    "Span carries no raw FCM token (sentinel-token scan)" {
        val recorder = setupSpanRecorder()
        val notification = newRow()
        val tokens = listOf(FcmTokenRow(platform = "android", token = sentinelToken, lastSeenAt = Instant.now()))
        val sender = FcmSender { FcmSendResult.TransientError("INVALID_ARGUMENT", null) }
        val dispatcher = build(notification, tokens, sender)
        dispatcher.dispatch(notification.id)
        Thread.sleep(50)
        val span = recorder.recordedSpans().first { it.name == "fcm.dispatch" }
        // Scan attributes + event attributes for the sentinel; fail if found.
        span.attributes.forEach { _, value ->
            value.toString() shouldNotContain sentinelToken
        }
        for (event in span.events) {
            event.attributes.forEach { _, value ->
                value.toString() shouldNotContain sentinelToken
            }
        }
    }

    "Span carries hashed user.id, not raw UUID" {
        val recorder = setupSpanRecorder()
        val notification = newRow()
        val tokens = listOf(FcmTokenRow(platform = "android", token = sentinelToken, lastSeenAt = Instant.now()))
        val sender = FcmSender { FcmSendResult.Sent("ok") }
        val dispatcher = build(notification, tokens, sender)
        dispatcher.dispatch(notification.id)
        Thread.sleep(50)
        val span = recorder.recordedSpans().first { it.name == "fcm.dispatch" }
        val userIdAttr =
            span.attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey("user.id"))
        userIdAttr shouldNotBe null
        // The hashed form is 16 hex chars; assert shape AND that it is NOT
        // the raw recipient UUID's string form.
        userIdAttr!!.length shouldBe 16
        userIdAttr shouldNotBe recipient.toString()
        userIdAttr shouldBe id.nearyou.app.infra.otel.UserIdHasher.hash(recipient)
    }

    "Per-token partial-failure pairs with one span event per failed token (no event for succeeding tokens)" {
        // Two tokens: tokenA → sender throws RuntimeException; tokenB → Sent.
        // Each token gets its own `withSpan("fcm.dispatch")` invocation in
        // `sendOne(...)` so the event scope is per-token by construction.
        // Assert: 2 fcm.dispatch spans recorded, EXACTLY one with the
        // fcm_dispatch_failed event, the other clean (no failure event).
        val recorder = setupSpanRecorder()
        val notification = newRow()
        val tokenA = "token-A-fails-DO-NOT-LEAK"
        val tokenB = "token-B-succeeds-DO-NOT-LEAK"
        val tokens =
            listOf(
                FcmTokenRow(platform = "android", token = tokenA, lastSeenAt = Instant.now()),
                FcmTokenRow(platform = "android", token = tokenB, lastSeenAt = Instant.now()),
            )
        val sender =
            FcmSender { msg ->
                // FcmSender API forwards the Message; we use `Message`'s
                // override-able token field to discriminate. The Firebase
                // Admin SDK's `Message` does not expose token at runtime, so
                // discriminate by call order via an atomic counter.
                if (sendCounter.getAndIncrement() == 0) {
                    throw RuntimeException("simulated failure for first token")
                } else {
                    FcmSendResult.Sent("ok")
                }
            }
        val dispatcher = build(notification, tokens, sender)
        dispatcher.dispatch(notification.id)
        Thread.sleep(80)
        val fcmSpans = recorder.recordedSpans().filter { it.name == "fcm.dispatch" }
        // Both per-token spans recorded.
        fcmSpans shouldHaveSize 2
        val spansWithFailureEvent =
            fcmSpans.filter { span ->
                span.events.any { it.name == "fcm_dispatch_failed" }
            }
        // EXACTLY one span carries the failure event — the failing token's.
        spansWithFailureEvent shouldHaveSize 1
        val spansWithoutFailureEvent =
            fcmSpans.filter { span ->
                span.events.none { it.name == "fcm_dispatch_failed" }
            }
        // The other span is clean — succeeding token has no failure event.
        spansWithoutFailureEvent shouldHaveSize 1
    }

    "Span recording failure does not block dispatch (FailingSpanProcessor)" {
        val failing = id.nearyou.app.infra.otel.testing.FailingSpanProcessor()
        val recorder = setupSpanRecorder(extraProcessor = failing)
        val notification = newRow()
        val tokens = listOf(FcmTokenRow(platform = "android", token = sentinelToken, lastSeenAt = Instant.now()))
        val attempted = java.util.concurrent.atomic.AtomicBoolean(false)
        val sender =
            FcmSender {
                attempted.set(true)
                FcmSendResult.TransientError("INVALID_ARGUMENT", null)
            }
        val dispatcher = build(notification, tokens, sender)
        // The FailingSpanProcessor throws on every span end. The dispatcher's
        // best-effort try/catch around span event recording must swallow the
        // SDK exception. The FCM send must still be attempted (the actual
        // operational outcome is preserved), and dispatch returns normally.
        dispatcher.dispatch(notification.id)
        Thread.sleep(50)
        attempted.get() shouldBe true
    }
})

/**
 * Functional adapter so test bodies can write
 * `FcmSender { FcmSendResult.Sent(...) }` without constructing a SAM.
 */
private fun FcmSender(handler: (Message) -> FcmSendResult): FcmSender =
    object : FcmSender {
        override fun send(message: Message): FcmSendResult = handler(message)
    }
