package id.nearyou.app.infra.fcm

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import id.nearyou.data.repository.NotificationDispatcher
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Covers `fcm-push-dispatch` spec § "DI graph SHALL bind a composite
 * dispatcher in production and InAppOnlyDispatcher in tests, with catch-and-
 * log composite error handling". Tasks 6.5.1 — 6.5.3.
 */
class FcmAndInAppDispatcherTest : StringSpec(
    {
        fun captureLogs(block: () -> Unit): List<ILoggingEvent> {
            val logger = LoggerFactory.getLogger(FcmAndInAppDispatcher::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>()
            appender.start()
            logger.addAppender(appender)
            try {
                block()
            } finally {
                logger.detachAppender(appender)
                appender.stop()
            }
            return appender.list
        }

        // 6.5.1 — Composite invokes FCM then in-app log in call-order.
        "6.5.1 composite invokes FCM then in-app log in call-order" {
            val callOrder = mutableListOf<String>()
            val fcm = NotificationDispatcher { callOrder += "fcm" }
            val inApp = NotificationDispatcher { callOrder += "inApp" }
            val composite = FcmAndInAppDispatcher(fcm = fcm, inApp = inApp)
            composite.dispatch(UUID.randomUUID())
            callOrder shouldBe listOf("fcm", "inApp")
        }

        // 6.5.2 — FcmDispatcher unexpectedly throws → composite catches at
        // ERROR + still invokes in-app + counter incremented + no propagation.
        "6.5.2 FCM delegate unexpected throw: ERROR log + counter increment + in-app still called" {
            val counterCalls = mutableListOf<String>()
            var inAppCalled = false
            val composite =
                FcmAndInAppDispatcher(
                    fcm = NotificationDispatcher { _ -> throw NullPointerException("synthetic") },
                    inApp = NotificationDispatcher { _ -> inAppCalled = true },
                    errorCounter = { delegate -> counterCalls += delegate },
                )
            val notificationId = UUID.randomUUID()
            val logs = captureLogs { composite.dispatch(notificationId) }
            inAppCalled shouldBe true
            counterCalls shouldBe listOf("FcmDispatcher")
            logs.any { ev ->
                ev.level == Level.ERROR &&
                    ev.formattedMessage.contains("event=fcm_composite_dispatcher_unexpected_error") &&
                    ev.formattedMessage.contains("delegate=FcmDispatcher")
            } shouldBe true
        }

        // 6.5.3 — InApp delegate unexpectedly throws → composite catches +
        // FCM was already invoked first + no propagation.
        "6.5.3 in-app delegate unexpected throw: ERROR log + FCM still invoked first + no propagation" {
            val callOrder = mutableListOf<String>()
            val composite =
                FcmAndInAppDispatcher(
                    fcm = NotificationDispatcher { callOrder += "fcm" },
                    inApp =
                        NotificationDispatcher { _ ->
                            callOrder += "inApp"
                            throw IllegalStateException("synthetic")
                        },
                )
            val logs = captureLogs { composite.dispatch(UUID.randomUUID()) }
            callOrder shouldBe listOf("fcm", "inApp")
            logs.any { it.level == Level.ERROR && it.formattedMessage.contains("delegate=InAppOnlyDispatcher") } shouldBe true
        }
    },
)
