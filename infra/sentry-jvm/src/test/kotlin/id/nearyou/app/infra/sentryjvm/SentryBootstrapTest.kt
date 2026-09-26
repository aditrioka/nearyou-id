package id.nearyou.app.infra.sentryjvm

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import id.nearyou.app.infra.sentryjvm.testing.SentryEventRecorder
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.sentry.Sentry
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * backend-error-reporting spec — bootstrap lifecycle, capture path, PII posture, correlation.
 * Wire-level assertions go through [SentryEventRecorder] (the events as they would leave the process).
 */
class SentryBootstrapTest : StringSpec({
    val context = LoggerFactory.getILoggerFactory() as LoggerContext
    val root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
    val appLog = LoggerFactory.getLogger("id.nearyou.app.Probe")

    fun bootstrapLines(block: () -> Unit): List<String> {
        val logger = context.getLogger(SentryBootstrap::class.java) as Logger
        val lines = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(lines)
        try {
            block()
        } finally {
            logger.detachAppender(lines)
        }
        return lines.list.map { it.formattedMessage }
    }

    afterEach {
        root.detachAppender(SentryBootstrap.APPENDER_NAME)
        Sentry.close()
    }

    // --- DSN-gated, idempotent, exception-safe initialization ---

    "null or blank DSN is a no-op with one dsn_missing line" {
        listOf(null, "", "   ").forEach { dsn ->
            val lines = bootstrapLines { SentryBootstrap.start("staging", dsn, "rev-1") shouldBe false }
            lines shouldBe listOf("event=sentry_disabled reason=dsn_missing")
            Sentry.isEnabled() shouldBe false
            root.getAppender(SentryBootstrap.APPENDER_NAME) shouldBe null
        }
    }

    "malformed DSN returns false without throwing and logs init_failed" {
        val lines = bootstrapLines { SentryBootstrap.start("staging", "not-a-dsn", "rev-1") shouldBe false }
        lines.single() shouldContain "event=sentry_disabled reason=init_failed"
        Sentry.isEnabled() shouldBe false
        root.getAppender(SentryBootstrap.APPENDER_NAME) shouldBe null
    }

    "valid DSN enables reporting with one SENTRY appender and tracing off" {
        val lines = bootstrapLines { SentryBootstrap.start("staging", SentryEventRecorder.FAKE_DSN, "rev-1") shouldBe true }
        lines shouldContain "event=sentry_enabled environment=staging release=rev-1"
        Sentry.isEnabled() shouldBe true
        root.iteratorForAppenders().asSequence().count { it.name == SentryBootstrap.APPENDER_NAME } shouldBe 1
        Sentry.getCurrentScopes().options.isTracingEnabled shouldBe false
        Sentry.getCurrentScopes().options.flushTimeoutMillis shouldBe 2_000L
    }

    "repeated start does not double-attach" {
        SentryBootstrap.start("staging", SentryEventRecorder.FAKE_DSN, "rev-1") shouldBe true
        SentryBootstrap.start("staging", SentryEventRecorder.FAKE_DSN, "rev-1") shouldBe true
        root.iteratorForAppenders().asSequence().count { it.name == SentryBootstrap.APPENDER_NAME } shouldBe 1
    }

    // --- capture path ---

    "ERROR with a throwable becomes one event carrying the exception" {
        SentryEventRecorder.start().use { recorder ->
            appLog.error("event=unhandled_exception method=GET path=/probe", IllegalStateException("boom"))
            val event = recorder.events.single()
            event.level shouldBe "error"
            event.message shouldBe "event=unhandled_exception method=GET path=/probe"
            event.exceptionTypes shouldContain "IllegalStateException"
            event.exceptionValues shouldContain "boom"
        }
    }

    "ERROR without a throwable becomes an error-level event" {
        SentryEventRecorder.start().use { recorder ->
            appLog.error("event=moderation_list_unavailable")
            val event = recorder.events.single()
            event.level shouldBe "error"
            event.message shouldBe "event=moderation_list_unavailable"
        }
    }

    "WARN and INFO are not reported" {
        SentryEventRecorder.start().use { recorder ->
            appLog.warn("event=layer3_dispatch_failed failure_kind=timeout")
            appLog.info("event=rate_limit_check")
            recorder.events.shouldBeEmpty()
        }
    }

    "uncaught exception on a background thread is reported AND still printed to stderr" {
        SentryEventRecorder.start().use { recorder ->
            val stderr = ByteArrayOutputStream()
            val original = System.err
            System.setErr(PrintStream(stderr, true))
            try {
                Thread { throw IllegalArgumentException("bg boom") }.apply {
                    start()
                    join()
                }
            } finally {
                System.setErr(original)
            }
            val event = recorder.events.single()
            event.exceptionTypes shouldContain "IllegalArgumentException"
            event.exceptionValues shouldContain "bg boom"
            // Cloud Logging keeps the trace: the SDK's handler replaced the JVM's default printer.
            stderr.toString() shouldContain "IllegalArgumentException: bg boom"
        }
    }

    // --- correlation + PII posture ---

    "environment, release, and call_id tag are set; no user, host, or breadcrumbs" {
        SentryEventRecorder.start(env = "staging", release = "nearyou-backend-00042-xyz").use { recorder ->
            appLog.info("event=earlier_request_noise user_id=someone")
            MDC.put("call_id", "abc-123")
            try {
                appLog.error("event=unhandled_exception", RuntimeException("x"))
            } finally {
                MDC.remove("call_id")
            }
            val event = recorder.events.single()
            event.environment shouldBe "staging"
            event.release shouldBe "nearyou-backend-00042-xyz"
            event.tags["call_id"] shouldBe "abc-123"
            event.hasUser shouldBe false
            event.serverName shouldBe null
            event.breadcrumbCount shouldBe 0
        }
    }

    "message PII is redacted on the wire" {
        SentryEventRecorder.start().use { recorder ->
            appLog.error(
                "at -6.2088,106.8456 auth=Bearer abc.def-ghi jwt=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.sig " +
                    "mail=someone@example.com ip=203.0.113.9 v6=2001:db8:85a3:0:0:8a2e:370:7334",
            )
            val message = recorder.events.single().message!!
            listOf("-6.2088", "abc.def-ghi", "eyJhbGci", "someone@example.com", "203.0.113.9", "2001:db8")
                .forEach { message shouldNotContain it }
            message shouldContain "[redacted]"
        }
    }

    "raw log arguments never leave the process" {
        SentryEventRecorder.start().use { recorder ->
            appLog.error("event=probe mail={} at {}", "a@b.co", "-6.2088,106.8456")
            val event = recorder.events.single()
            event.message shouldBe "event=probe mail=[redacted] at [redacted]"
            event.messageParams shouldBe null
        }
    }

    "exception values are redacted on the wire" {
        SentryEventRecorder.start().use { recorder ->
            val cause =
                IllegalStateException(
                    "ERROR: duplicate key value violates unique constraint\n  Detail: Key (email)=(a@b.co) already exists.",
                )
            appLog.error("event=unhandled_exception", RuntimeException("wrapper for x@y.io", cause))
            val values = recorder.events.single().exceptionValues
            values shouldHaveSize 2
            values.forEach { value ->
                value!! shouldNotContain "a@b.co"
                value shouldNotContain "x@y.io"
            }
            values shouldContain "ERROR: duplicate key value violates unique constraint\n  Detail: Key (email)=([redacted]) already exists."
        }
    }
})
