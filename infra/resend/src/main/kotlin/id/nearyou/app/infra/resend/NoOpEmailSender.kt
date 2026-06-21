package id.nearyou.app.infra.resend

import org.slf4j.LoggerFactory

/**
 * Fail-soft binding when Resend is unconfigured (no API key) — the [id.nearyou.app.infra.cloudflareimages]
 * `NoOpImageStore` precedent. Boot succeeds and [send] returns [SendResult.Skipped] without
 * throwing, so consumers degrade gracefully. Logs only non-PII metadata (never the recipient
 * or body).
 */
object NoOpEmailSender : EmailSender {
    private val log = LoggerFactory.getLogger(NoOpEmailSender::class.java)

    override fun isConfigured(): Boolean = false

    override suspend fun send(
        to: String,
        template: EmailTemplate,
        idempotencyKey: String,
    ): SendResult {
        // No `to`/body in the log line — only the event + idempotency key (no-PII-in-logs).
        log.info("event=email_send_skipped reason=unconfigured idempotency_key={}", idempotencyKey)
        return SendResult.Skipped
    }
}
