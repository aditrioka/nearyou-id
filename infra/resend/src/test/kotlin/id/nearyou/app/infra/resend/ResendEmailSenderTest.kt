package id.nearyou.app.infra.resend

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

class ResendEmailSenderTest : StringSpec({

    // Recipient + body fragments used to assert they NEVER reach the logs.
    val recipient = "alice@example.com"
    val template =
        EmailTemplate(
            subject = "Unduhan data kamu siap",
            htmlBody = "<p>SECRET-HTML-BODY: link rahasia</p>",
            textBody = "SECRET-TEXT-BODY: link rahasia",
        )
    val idempotencyKey = "abc123idem"
    val config = ResendConfig(apiKey = "re_test_key", fromAddress = "NearYou <noreply@nearyou.id>")

    val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    /** The body string the sender set via `setBody(...)` (a JSON `TextContent`). */
    fun bodyText(req: HttpRequestData): String = (req.body as TextContent).text

    /** Captures INFO+WARN log lines from the sender + no-op classes during [block]. */
    fun captureLogs(block: () -> Unit): List<String> {
        val sender = LoggerFactory.getLogger(ResendEmailSender::class.java) as Logger
        val noop = LoggerFactory.getLogger(NoOpEmailSender::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        val originalSenderLevel = sender.level
        val originalNoopLevel = noop.level
        sender.level = Level.INFO
        noop.level = Level.INFO
        sender.addAppender(appender)
        noop.addAppender(appender)
        try {
            block()
        } finally {
            try {
                sender.level = originalSenderLevel
            } catch (_: Exception) {
                // best-effort
            }
            try {
                noop.level = originalNoopLevel
            } catch (_: Exception) {
                // best-effort
            }
            sender.detachAppender(appender)
            noop.detachAppender(appender)
            appender.stop()
        }
        return appender.list.map { it.formattedMessage }
    }

    "factory returns NoOp when config is null" {
        emailSender(null).isConfigured().shouldBeFalse()
    }

    "factory returns NoOp when config is incomplete (blank key)" {
        emailSender(config.copy(apiKey = "")).isConfigured().shouldBeFalse()
    }

    "factory returns the Resend sender when config is complete" {
        emailSender(config, MockEngine { respond("{}", HttpStatusCode.OK, jsonHeaders) }).isConfigured().shouldBeTrue()
    }

    // Scenario: Send issues a Resend POST /emails with the rendered template + Idempotency-Key.
    "send POSTs /emails carrying subject/html/text + the Idempotency-Key header" {
        val requests = mutableListOf<HttpRequestData>()
        val sender =
            ResendEmailSender(
                config,
                MockEngine { req ->
                    requests += req
                    respond("""{"id":"msg-1"}""", HttpStatusCode.OK, jsonHeaders)
                },
            )

        val result = runBlocking { sender.send(recipient, template, idempotencyKey) }

        result.shouldBeInstanceOf<SendResult.Sent>()
        (result as SendResult.Sent).id shouldBe "msg-1"

        requests.size shouldBe 1
        val req = requests.first()
        req.method.value shouldBe "POST"
        req.url.toString() shouldBe ResendEmailSender.EMAILS_URL
        req.headers[ResendEmailSender.IDEMPOTENCY_KEY_HEADER] shouldBe idempotencyKey
        req.headers[HttpHeaders.Authorization] shouldBe "Bearer ${config.apiKey}"

        val body = bodyText(req)
        body shouldContain template.subject
        body shouldContain template.htmlBody
        body shouldContain template.textBody
        body shouldContain recipient
        body shouldContain config.fromAddress
    }

    // Scenario: Same idempotency key sends once — each send carries the same key unchanged,
    // so Resend's Idempotency-Key dedups a retry to one delivery.
    "two sends with the same idempotency key each carry that key (Resend dedups to one delivery)" {
        val requests = mutableListOf<HttpRequestData>()
        val sender =
            ResendEmailSender(
                config,
                MockEngine { req ->
                    requests += req
                    respond("""{"id":"msg-1"}""", HttpStatusCode.OK, jsonHeaders)
                },
            )

        runBlocking {
            sender.send(recipient, template, idempotencyKey)
            sender.send(recipient, template, idempotencyKey)
        }

        requests.size shouldBe 2
        requests.forEach { it.headers[ResendEmailSender.IDEMPOTENCY_KEY_HEADER] shouldBe idempotencyKey }
    }

    // Scenario: Transient 5xx is retried with backoff — up to 3 attempts total, then Failed.
    "5xx is retried 3 times total then returns Failed without throwing" {
        val requests = mutableListOf<HttpRequestData>()
        val sender =
            ResendEmailSender(
                config,
                MockEngine { req ->
                    requests += req
                    respond("upstream boom", HttpStatusCode.InternalServerError)
                },
            )

        lateinit var result: SendResult
        shouldNotThrowAny { result = runBlocking { sender.send(recipient, template, idempotencyKey) } }

        result.shouldBeInstanceOf<SendResult.Failed>()
        requests.size shouldBe ResendEmailSender.MAX_ATTEMPTS
    }

    "a 5xx that recovers on a later attempt returns Sent" {
        var calls = 0
        val sender =
            ResendEmailSender(
                config,
                MockEngine { _ ->
                    calls += 1
                    if (calls < 3) {
                        respond("boom", HttpStatusCode.ServiceUnavailable)
                    } else {
                        respond("""{"id":"msg-recovered"}""", HttpStatusCode.OK, jsonHeaders)
                    }
                },
            )

        val result = runBlocking { sender.send(recipient, template, idempotencyKey) }

        result.shouldBeInstanceOf<SendResult.Sent>()
        (result as SendResult.Sent).id shouldBe "msg-recovered"
        calls shouldBe 3
    }

    "a non-retryable 4xx returns Failed immediately (one attempt, no throw)" {
        val requests = mutableListOf<HttpRequestData>()
        val sender =
            ResendEmailSender(
                config,
                MockEngine { req ->
                    requests += req
                    respond("""{"message":"bad request"}""", HttpStatusCode.UnprocessableEntity, jsonHeaders)
                },
            )

        lateinit var result: SendResult
        shouldNotThrowAny { result = runBlocking { sender.send(recipient, template, idempotencyKey) } }

        result.shouldBeInstanceOf<SendResult.Failed>()
        requests.size shouldBe 1
    }

    // Scenario: App boots with Resend unconfigured — a no-op send returns Skipped without throwing.
    "NoOp sender returns Skipped without throwing" {
        NoOpEmailSender.isConfigured().shouldBeFalse()
        lateinit var result: SendResult
        shouldNotThrowAny { result = runBlocking { NoOpEmailSender.send(recipient, template, idempotencyKey) } }
        result shouldBe SendResult.Skipped
    }

    // Scenario: Recipient and body are never logged — SUCCESS path.
    "successful send logs only event/status/idempotency-key — never recipient or body" {
        val sender =
            ResendEmailSender(config, MockEngine { respond("""{"id":"msg-1"}""", HttpStatusCode.OK, jsonHeaders) })

        val logs = captureLogs { runBlocking { sender.send(recipient, template, idempotencyKey) } }

        logs.size shouldBeGreaterThanOrEqual 1
        logs.forEach { it.shouldNotContainPii(recipient, template) }
        logs.any { it.contains("event=email_sent") } shouldBe true
        logs.any { it.contains("idempotency_key=$idempotencyKey") } shouldBe true
    }

    // Scenario: Recipient and body are never logged — FAILURE (exhausted 5xx) path.
    "failed send logs only event/status/idempotency-key — never recipient or body" {
        val sender =
            ResendEmailSender(config, MockEngine { respond("boom", HttpStatusCode.InternalServerError) })

        val logs = captureLogs { runBlocking { sender.send(recipient, template, idempotencyKey) } }

        logs.size shouldBeGreaterThanOrEqual 1
        logs.forEach { it.shouldNotContainPii(recipient, template) }
        logs.any { it.contains("idempotency_key=$idempotencyKey") } shouldBe true
    }

    "NoOp send logs only event + idempotency-key — never recipient or body" {
        val logs = captureLogs { runBlocking { NoOpEmailSender.send(recipient, template, idempotencyKey) } }

        logs.forEach { it.shouldNotContainPii(recipient, template) }
        logs.any { it.contains("event=email_send_skipped") } shouldBe true
    }

    // emailSenderModule binds NoOp when the slot is unset/blank (boot-safe), real sender when set.
    "emailSenderModule binds NoOp when the resend-api-key slot is blank" {
        val mod = emailSenderModule { _ -> null }
        val app = org.koin.core.context.startKoin { modules(mod) }
        try {
            app.koin.get<EmailSender>().isConfigured().shouldBeFalse()
        } finally {
            org.koin.core.context.stopKoin()
        }
    }

    "emailSenderModule resolves the UNPREFIXED base slot name and binds a configured sender" {
        // Resolves the unprefixed logical name `resend-api-key` (env var RESEND_API_KEY);
        // the env-specific Secret Manager slot (`staging-resend-api-key`) is mapped onto that
        // env var by the deploy `--set-secrets`, NOT composed in code.
        ResendConfig.SLOT_API_KEY shouldBe "resend-api-key"

        val mod = emailSenderModule { slot -> if (slot == "resend-api-key") "re_live_key" else null }
        val app = org.koin.core.context.startKoin { modules(mod) }
        try {
            app.koin.get<EmailSender>().isConfigured().shouldBeTrue()
        } finally {
            org.koin.core.context.stopKoin()
        }
    }
})

/** Asserts a log line contains neither the recipient address nor any rendered body fragment. */
private fun String.shouldNotContainPii(
    recipient: String,
    template: EmailTemplate,
) {
    contains(recipient) shouldBe false
    contains(template.htmlBody) shouldBe false
    contains(template.textBody) shouldBe false
    contains("SECRET-HTML-BODY") shouldBe false
    contains("SECRET-TEXT-BODY") shouldBe false
}
