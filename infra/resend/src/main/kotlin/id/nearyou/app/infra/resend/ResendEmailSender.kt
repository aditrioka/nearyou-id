package id.nearyou.app.infra.resend

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.apache5.Apache5
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Resend implementation of [EmailSender] (design D2): a raw Ktor client against the Resend
 * REST `POST /emails` endpoint. Resend ships no official JVM/Kotlin SDK, so this mirrors the
 * `:infra:cloudflare-images` "no JVM SDK → raw Ktor client" precedent (no new library pin).
 *
 * Idempotency is two-layer: the caller-supplied [EmailSender.send] `idempotencyKey`
 * (`SHA256(user_id + event_type + timestamp_minute)`) is passed as Resend's `Idempotency-Key`
 * header, so a retried send delivers exactly once.
 *
 * Transient `5xx` responses are retried up to [MAX_ATTEMPTS] total with exponential backoff;
 * a non-retryable `4xx` (or exhausted retries) surfaces [SendResult.Failed] WITHOUT throwing.
 *
 * No-PII-in-logs invariant: this class NEVER logs the recipient address or the message body —
 * only `event=`, `status=`, `attempt=`, and the idempotency key.
 */
class ResendEmailSender(
    private val config: ResendConfig,
    engine: HttpClientEngine? = null,
) : EmailSender {
    private val log = LoggerFactory.getLogger(ResendEmailSender::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val client: HttpClient = if (engine != null) HttpClient(engine) else HttpClient(Apache5)

    override fun isConfigured(): Boolean = true

    override suspend fun send(
        to: String,
        template: EmailTemplate,
        idempotencyKey: String,
    ): SendResult {
        val payload =
            ResendSendRequest(
                from = config.fromAddress,
                to = to,
                subject = template.subject,
                html = template.htmlBody,
                text = template.textBody,
            )
        val requestBody = json.encodeToString(ResendSendRequest.serializer(), payload)

        var lastStatus = -1
        for (attempt in 1..MAX_ATTEMPTS) {
            val response: HttpResponse =
                client.post(EMAILS_URL) {
                    header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
                    header(IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }
            val status = response.status.value
            lastStatus = status

            when {
                status in SUCCESS_RANGE -> {
                    val id = parseMessageId(response.bodyAsText())
                    log.info(
                        "event=email_sent status={} attempt={} idempotency_key={}",
                        status,
                        attempt,
                        idempotencyKey,
                    )
                    return SendResult.Sent(id)
                }
                status in SERVER_ERROR_RANGE -> {
                    // Transient — retry with exponential backoff (unless this was the last attempt).
                    log.warn(
                        "event=email_send_retryable_error status={} attempt={} idempotency_key={}",
                        status,
                        attempt,
                        idempotencyKey,
                    )
                    if (attempt < MAX_ATTEMPTS) {
                        delay(BASE_BACKOFF_MS shl (attempt - 1))
                    }
                }
                else -> {
                    // Non-retryable (4xx / unexpected) — fail soft immediately.
                    log.warn(
                        "event=email_send_failed status={} attempt={} idempotency_key={}",
                        status,
                        attempt,
                        idempotencyKey,
                    )
                    return SendResult.Failed("HTTP $status")
                }
            }
        }

        log.warn(
            "event=email_send_exhausted status={} attempts={} idempotency_key={}",
            lastStatus,
            MAX_ATTEMPTS,
            idempotencyKey,
        )
        return SendResult.Failed("HTTP $lastStatus after $MAX_ATTEMPTS attempts")
    }

    private fun parseMessageId(body: String): String =
        runCatching { json.decodeFromString<ResendSendResponse>(body).id }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: ""

    companion object {
        const val EMAILS_URL: String = "https://api.resend.com/emails"
        const val IDEMPOTENCY_KEY_HEADER: String = "Idempotency-Key"

        /** Total attempts including the first (spec: "up to 3 attempts total"). */
        const val MAX_ATTEMPTS: Int = 3

        /** First backoff; doubles each retry (200ms → 400ms). */
        const val BASE_BACKOFF_MS: Long = 200L

        private val SUCCESS_RANGE = 200..299
        private val SERVER_ERROR_RANGE = 500..599
    }
}

/** Resend `POST /emails` request body. Field names match the Resend API (`from`/`to`/`subject`/`html`/`text`). */
@Serializable
private data class ResendSendRequest(
    val from: String,
    val to: String,
    val subject: String,
    val html: String,
    val text: String,
)

/** Resend `POST /emails` success response — the created message `id`. */
@Serializable
private data class ResendSendResponse(
    val id: String = "",
)
